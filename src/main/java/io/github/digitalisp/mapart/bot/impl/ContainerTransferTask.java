package io.github.digitalisp.mapart.bot.impl;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.CustomAction;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.inventory.actions.WaitAction;
import com.zenith.mc.block.Block;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.ContainerSnapshot;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.TransferResult;
import io.github.digitalisp.mapart.bot.WithdrawMode;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.zenith.Globals.BLOCK_DATA;
import static com.zenith.Globals.CONFIG;

/**
 * scan / withdraw / deposit on a storage container (chest, double chest, barrel, shulker box...). Transfers are
 * whole-stack shift-clicks (ZenithProxy {@link ShiftClick}: it sends an empty changed-slot map and bumps the state
 * id, so the server answers with a full resync — vanilla behaviour, not an anti-cheat signal; see report). The
 * amounts actually moved are measured from the player inventory before/after, not assumed. A deposit takes only from
 * the 36 hotbar + main slots (the offhand and armour are not even visible in a chest menu, {@link OffhandGuard}) and
 * never config keepItems or {@link Valuables}. The bot's working items (empty maps, panes, XP bottles) go back to their
 * own chests only by {@link Mode#RETURN_WORKING}, the same whole-stack deposit limited to them.
 *
 * <p>A withdrawal clicks as its {@link WithdrawMode} says ({@link TransferPlanner}): whole stacks are shift-clicks;
 * a top-up is {@code LEFT(S) LEFT(P) LEFT(S)} and an exact count {@code LEFT(S) RIGHT(E)×n LEFT(S)}, ZenithProxy
 * {@link ClickItem}s, all sent before the shift-clicks and one action per tick as every request. ClickItem writes its
 * prediction into the cache; for a top-up that is exactly vanilla's result, for a right click onto an empty slot it is
 * {@code amount - 1} where the server puts 1 (the server corrects it with a SetSlot). So the request waits a round trip
 * ({@link TransferPlanner#waitsAfter}) after the last cursor click, before the shift-clicks read the cursor and before
 * the menu closes (a correction that arrives after the close never reaches the cache), and after the first right click
 * onto an empty slot when more follow. {@code moved} is measured from the inventory as always; an item still on the
 * cursor at the close goes back into the inventory (vanilla {@code AbstractContainerMenu.removed}) and is logged.
 */
final class ContainerTransferTask extends ContainerTask {
    enum Mode { SCAN, WITHDRAW, DEPOSIT, DEPOSIT_MAP, RETURN_WORKING }

    static final String FILLED_MAP = "minecraft:filled_map";

    private final Mode mode;
    /** WITHDRAW: the click pattern. */
    private final WithdrawMode withdrawMode;
    private final @Nullable Consumer<ContainerSnapshot> scanResult;
    private final @Nullable Consumer<TransferResult> transferResult;
    private final Map<String, Integer> wanted;
    private final Predicate<String> which;
    private final Map<String, Integer> keep;
    /** DEPOSIT_MAP: the one filled map to put away. */
    private final int mapId;
    private Map<String, Integer> playerBefore = Map.of();
    private @Nullable String planProblem;
    /** WITHDRAW: cursor clicks (LEFT/RIGHT) planned. */
    private int cursorClicks;
    private int shiftClicks;
    /** WITHDRAW / DEPOSIT: item id → count the plan moves (the answers are in when the player inventory shows it). */
    private Map<String, Integer> expectedMoves = Map.of();
    /** {@code build.containerAnswerWaits}: the answer gate in the request ({@link MenuClicks.Script}), -1 = none. */
    private int gateFrom = -1;
    private int gateEnd = -1;
    /** {@link ClickAnswers} tokens of the gate's click and of the last click, once they went out (0 = not yet). */
    private long gateToken;
    private long lastToken;
    /** {@code build.containerAnswerWaits}: how the waits of this withdrawal ended, for its log line; null when off. */
    private MenuClicks.@Nullable Waits waitLog;
    /**
     * {@code build.containerAnswerWaits}, a withdrawal with an answer gate (not with build.containerClickEveryTick): the
     * held fix-up and the re-sent shift-clicks ({@link MenuClicks.CursorGuard}); null otherwise.
     */
    private MenuClicks.@Nullable CursorGuard guard;

    private ContainerTransferTask(ZenithBotActions ctx, Pos pos, Mode mode, @Nullable Consumer<ContainerSnapshot> scan,
                                  @Nullable Consumer<TransferResult> transfer, Map<String, Integer> wanted,
                                  Predicate<String> which, Map<String, Integer> keep) {
        this(ctx, pos, mode, scan, transfer, wanted, which, keep, -1, WithdrawMode.WHOLE_STACKS);
    }

    private ContainerTransferTask(ZenithBotActions ctx, Pos pos, Mode mode, @Nullable Consumer<ContainerSnapshot> scan,
                                  @Nullable Consumer<TransferResult> transfer, Map<String, Integer> wanted,
                                  Predicate<String> which, Map<String, Integer> keep, int mapId,
                                  @Nullable WithdrawMode withdrawMode) {
        super(ctx, pos, ctx.containerTimeout(pos));
        this.mapId = mapId;
        this.mode = mode;
        this.withdrawMode = withdrawMode == null ? WithdrawMode.WHOLE_STACKS : withdrawMode;
        this.scanResult = scan;
        this.transferResult = transfer;
        this.wanted = normalise(wanted);
        this.which = which;
        this.keep = normalise(keep);
    }

    static ContainerTransferTask scan(ZenithBotActions ctx, Pos pos, Consumer<ContainerSnapshot> result) {
        return new ContainerTransferTask(ctx, pos, Mode.SCAN, result, null, Map.of(), id -> false, Map.of());
    }

    static ContainerTransferTask withdraw(ZenithBotActions ctx, Pos pos, Map<String, Integer> wanted,
                                          Consumer<TransferResult> result) {
        return withdraw(ctx, pos, wanted, WithdrawMode.WHOLE_STACKS, result);
    }

    /** A withdrawal with the click pattern of {@code mode} ({@link WithdrawMode}; null = whole stacks). */
    static ContainerTransferTask withdraw(ZenithBotActions ctx, Pos pos, Map<String, Integer> wanted,
                                          @Nullable WithdrawMode mode, Consumer<TransferResult> result) {
        return new ContainerTransferTask(ctx, pos, Mode.WITHDRAW, null, result, wanted, id -> false, Map.of(), -1, mode);
    }

    static ContainerTransferTask deposit(ZenithBotActions ctx, Pos pos, Predicate<String> which,
                                         Map<String, Integer> keep, Consumer<TransferResult> result) {
        return new ContainerTransferTask(ctx, pos, Mode.DEPOSIT, null, result, Map.of(), which, keep);
    }

    /**
     * Puts the bot's working items back ({@code BotActions.returnWorkingItems}): whole stacks of the ids in {@code keep}
     * that are {@code BotActions.WORKING_ITEMS}, keeping that many of each. They are {@link Valuables} to a deposit.
     */
    static ContainerTransferTask returnWorking(ZenithBotActions ctx, Pos pos, Map<String, Integer> keep,
                                               Consumer<TransferResult> result) {
        Map<String, Integer> k = normalise(keep);
        return new ContainerTransferTask(ctx, pos, Mode.RETURN_WORKING, null, result, Map.of(),
            id -> k.containsKey(id) && io.github.digitalisp.mapart.bot.BotActions.WORKING_ITEMS.contains(id), k);
    }

    /** Puts only the filled map {@code mapId} into the container. */
    static ContainerTransferTask depositMap(ZenithBotActions ctx, Pos pos, int mapId, Consumer<TransferResult> result) {
        return new ContainerTransferTask(ctx, pos, Mode.DEPOSIT_MAP, null, result, Map.of(), id -> false, Map.of(), mapId,
            WithdrawMode.WHOLE_STACKS);
    }

    @Override
    public String name() {
        return mode == Mode.DEPOSIT_MAP ? "depositMap " + mapId + " " + pos : mode.name().toLowerCase() + " " + pos;
    }

    @Override
    protected boolean looksOnly() {
        return mode == Mode.SCAN;
    }

    @Override
    protected @Nullable TaskStatus preflight() {
        if (mode == Mode.DEPOSIT_MAP && Inv.count(Inv.isMap(mapId)) == 0) return fail("filled map " + mapId + " not in inventory");
        return null;
    }

    @Override
    protected @Nullable String checkBlock() {
        int state = ctx.world().stateId(pos);
        Block b = BLOCK_DATA.getBlockDataFromBlockStateId(state);
        if (b == null || b.isAir()) return "no container at " + pos;
        if (b.blockEntityType() == null) return "block at " + pos + " is " + b.name() + ", not a container";
        return null;
    }

    @Override
    protected @Nullable String checkContainer(Container c) {
        return isStorage(c.getType()) ? null : "block at " + pos + " opened a " + typeName(c.getType()) + " menu";
    }

    @Override
    protected void onOpened(Container c) {
        playerBefore = playerCounts();
    }

    @Override
    protected List<InventoryAction> actions(Container c, int top) {
        int cid = c.getContainerId();
        List<InventoryAction> out = new ArrayList<>();
        switch (mode) {
            case SCAN -> { }
            case WITHDRAW -> {
                List<Integer> empty = emptyPlayerSlots(c, top);
                // firework rockets (build.elytraLegs, the rocket chest): only plain duration 1-3 ones are taken or topped up
                boolean rockets = wanted.containsKey(Inv.ROCKET_ID);
                var plan = TransferPlanner.planWithdraw(rockets ? flightRocketsOnly(c) : containerStacks(c), wanted,
                    rockets ? Inv.playerStacks(top, s -> Inv.is(Inv.ROCKET_ID).test(s) && !Inv.FLIGHT_ROCKET.test(s))
                        : Inv.playerStacks(top), Inv.freeSlots(), withdrawMode, empty);
                planProblem = plan.problem();
                expectedMoves = plan.expected();
                List<TransferPlanner.Step> steps = plan.steps();
                MenuClicks.Switches sw = switches();
                boolean answers = sw.waitsForAnswers();
                int delay = clickDelay();
                // the clicks with their waits (TransferPlanner.waitsAfter); with the answer waits the gate after the
                // last cursor click instead, in real ticks (MenuClicks.withdraw)
                MenuClicks.Script script = MenuClicks.withdraw(steps, empty, delay, ackTicks(), answers,
                    MenuClicks.ticksPerAction(delay, sw.clicksEveryTick()));
                cursorClicks = 0; // planned again when the request was refused and is submitted later
                shiftClicks = 0;
                gateFrom = script.gateFrom();
                gateEnd = script.gateEnd();
                gateToken = 0;
                lastToken = 0;
                waitLog = answers ? new MenuClicks.Waits() : null;
                guard = answers && gateFrom >= 0 && !sw.clicksEveryTick()
                    ? new MenuClicks.CursorGuard(cfg.build.containerShiftAnswerTicks) : null;
                for (int k = 0; k < script.ops().size(); k++) {
                    switch (script.ops().get(k)) {
                        case WAIT, GATE -> out.add(new WaitAction());
                        // after the cursor sequences: anything the cache still shows on the cursor goes into an empty
                        // slot, or every shift-click after it is refused (local 10x run: 3 of 206 top-ups); with the
                        // guard not while that cursor is a stale resync's (MenuClicks.CursorGuard)
                        case FIXUP -> {
                            int self = out.size();
                            out.add(new CustomAction(cid, () -> fixup(cid, top, self)));
                        }
                        case CLICK -> {
                            TransferPlanner.Step s = steps.get(script.stepOf().get(k));
                            InventoryAction click = switch (s.kind()) {
                                case QUICK_MOVE -> new ShiftClick(cid, s.slot(), ShiftClickItemAction.LEFT_CLICK);
                                case LEFT -> new ClickItem(cid, s.slot(), ClickItemAction.LEFT_CLICK);
                                case RIGHT -> new ClickItem(cid, s.slot(), ClickItemAction.RIGHT_CLICK);
                            };
                            boolean shift = s.kind() == TransferPlanner.Kind.QUICK_MOVE;
                            if (!shift) cursorClicks++;
                            else shiftClicks++;
                            boolean gate = k == script.lastCursor() && gateFrom >= 0;
                            boolean last = answers && k == script.lastClick();
                            boolean firstShift = shift && shiftClicks == 1 && gateFrom >= 0; // ends the mid wait
                            InventoryAction a = gate || last || firstShift
                                ? tracked(cid, click, shift ? s.slot() : null, gate, last, firstShift) : click;
                            out.add(shift && guard != null ? guarded(cid, a, out.size()) : a);
                        }
                    }
                }
            }
            case DEPOSIT, RETURN_WORKING -> {
                Set<String> never = new HashSet<>();
                for (String k : cfg.build.keepItems) never.add(ItemIds.ns(k));
                int containerFree = before == null ? 0 : before.freeSlots();
                // the engine asks for materials only; totems, gear, shulkers ... never go, whatever it asks. Its own
                // working items (empty maps, panes, XP bottles) go back to their chests only by RETURN_WORKING
                Predicate<String> mayDeposit = mode == Mode.RETURN_WORKING ? which
                    : id -> which.test(id) && !Valuables.isValuable(id);
                var plan = TransferPlanner.planDeposit(Inv.playerStacks(top), mayDeposit, keep, never, containerStacks(c),
                    containerFree);
                planProblem = plan.problem();
                expectedMoves = plan.expected();
                gateFrom = -1;
                gateEnd = -1;
                lastToken = 0;
                waitLog = null;
                guard = null;
                // the answer waits: the run closes on the last shift-click's answer (or the moves showing, as before)
                boolean answers = mode == Mode.DEPOSIT && switches().waitsForAnswers();
                List<TransferPlanner.Stack> clicks = plan.clicks();
                for (int i = 0; i < clicks.size(); i++) {
                    InventoryAction click = new ShiftClick(cid, clicks.get(i).slot(), ShiftClickItemAction.LEFT_CLICK);
                    out.add(answers && i == clicks.size() - 1
                        ? tracked(cid, click, clicks.get(i).slot(), false, true, false) : click);
                }
            }
            case DEPOSIT_MAP -> {
                int slot = Inv.find(Inv.isMap(mapId));
                if (slot < 0 || slot == ItemIds.OFFHAND) {
                    planProblem = "map " + mapId + " not in hotbar/main inventory";
                } else if (before == null || before.freeSlots() <= 0) {
                    planProblem = "container full";
                } else {
                    out.add(new ShiftClick(cid, ItemIds.toContainerSlot(slot, top), ShiftClickItemAction.LEFT_CLICK));
                }
            }
        }
        return out;
    }

    @Override
    protected TaskStatus finish() {
        ContainerSnapshot snap = after != null ? after : before;
        if (mode == Mode.SCAN) {
            if (snap == null) return fail("no snapshot");
            scanResult.accept(snap);
            return TaskStatus.SUCCESS;
        }
        Map<String, Integer> playerAfter = playerCounts();
        Map<String, Integer> moved = new TreeMap<>();
        Set<String> ids = new HashSet<>(playerBefore.keySet());
        ids.addAll(playerAfter.keySet());
        for (String id : ids) {
            int d = playerAfter.getOrDefault(id, 0) - playerBefore.getOrDefault(id, 0);
            if (mode == Mode.WITHDRAW && d > 0 && wanted.containsKey(id)) moved.put(id, d);
            if ((mode == Mode.DEPOSIT || mode == Mode.RETURN_WORKING) && d < 0) moved.put(id, -d);
        }
        if (mode == Mode.DEPOSIT_MAP) {
            moved.clear();
            if (Inv.count(Inv.isMap(mapId)) == 0) moved.put(FILLED_MAP, 1);
        }
        if (mode == Mode.WITHDRAW) {
            // top-ups and exact counts leave no other trace in the log (the coordinator could not tell whether TOP_UP
            // ran live; the replay's click count could). With build.containerAnswerWaits: how its waits ended, and a
            // line for a shift-click-only withdrawal too (how its tail ended); off, those leave no line, as before
            if (cursorClicks > 0 || (waitLog != null && shiftClicks > 0)) {
                LOG.info("{}", MenuClicks.withdrawLine(name(), moved, cursorClicks, withdrawMode, shiftClicks, waitsText()));
            }
            logCursorAtClose();
            clearStaleCursor(); // the server put it back into the inventory; the cache does not clear it on a close
        }
        List<String> problems = new ArrayList<>();
        if (problem != null) problems.add(problem);
        if (mode == Mode.WITHDRAW) {
            for (var e : wanted.entrySet()) {
                int got = moved.getOrDefault(e.getKey(), 0);
                if (got < e.getValue()) {
                    int inBox = before == null ? 0 : before.items().getOrDefault(e.getKey(), 0);
                    problems.add(e.getKey() + " " + got + "/" + e.getValue()
                        + (inBox < e.getValue() ? " (container had " + inBox + ")" : Inv.freeSlots() == 0 ? " (inventory full)" : ""));
                }
            }
        } else if (planProblem != null) {
            problems.add(planProblem);
        } else if (mode == Mode.DEPOSIT_MAP && moved.isEmpty()) {
            problems.add("map " + mapId + " is still in the inventory");
        }
        if (after == null && snap != null) snap = estimateAfter(snap, moved);
        transferResult.accept(new TransferResult(moved, snap, problems.isEmpty() ? null : String.join("; ", problems)));
        return TaskStatus.SUCCESS;
    }

    /**
     * The cache's cursor once the menu is closed (ZenithProxy does not clear it on a close): an item still on it was put
     * back into the inventory by the server, which {@code moved} measures. Logged, since no plan leaves one there.
     */
    private void logCursorAtClose() {
        ItemStack cur = Inv.cache().getMouseStack();
        if (cur == null || Inv.empty(cur)) return;
        LOG.warn("{}: {} {} still on the cursor when the menu closed ({} withdrawal, {} cursor clicks); the server puts it"
            + " back into the inventory", name(), cur.getAmount(), Inv.id(cur), withdrawMode, cursorClicks);
    }

    /**
     * After the menu closed: ZenithProxy keeps the menu's carried item in its cache, though the server put it back into
     * the inventory (vanilla {@code AbstractContainerMenu.removed}) and a vanilla client clears it. A stale cursor would
     * make ZenithProxy mispredict the next inventory click outside a menu; the next menu's contents set it anew.
     */
    private void clearStaleCursor() {
        ItemStack cur = Inv.cache().getMouseStack();
        if (cur == null || Inv.empty(cur)) return;
        Inv.cache().setMouseStack(Container.EMPTY_STACK);
    }

    /**
     * At run time, before the first shift-click of a withdrawal with cursor clicks: if the cache shows an item on the
     * cursor, a left click puts it into the first empty hotbar/main slot of the open menu (null: nothing to do). Safe
     * either way: with the server's cursor empty, a click on an empty slot changes nothing.
     */
    private @Nullable MinecraftPacket cursorToEmptySlot(int cid, int top) {
        ItemStack cur = Inv.cache().getMouseStack();
        if (cur == null || Inv.empty(cur)) return null;
        Container open = Inv.openContainer();
        if (open == null || open.getContainerId() != cid) return null;
        for (int i = top; i < top + 36; i++) {
            if (!Inv.empty(open.getItemStack(i))) continue;
            LOG.warn("{}: {} {} left on the cursor after the cursor clicks; putting it into slot {} before the"
                + " shift-clicks", name(), cur.getAmount(), Inv.id(cur), i);
            return new ClickItem(cid, i, ClickItemAction.LEFT_CLICK).packet();
        }
        return null;
    }

    /**
     * The cursor fix-up at request action {@code self}: with the {@link #guard}, held (run again at the next action
     * tick) while the cache shows a stale cursor ({@link MenuClicks.CursorGuard#holdFixup}); then, or without the
     * guard, {@link #cursorToEmptySlot} as before.
     */
    private @Nullable MinecraftPacket fixup(int cid, int top, int self) {
        MenuClicks.CursorGuard g = guard;
        if (g != null) {
            boolean first = !g.held();
            boolean answered = gateAnswered();
            if (g.holdFixup(now, cursorEmpty(), answered) && runAgain(self)) {
                if (first) {
                    ItemStack cur = Inv.cache().getMouseStack();
                    LOG.info("{}: holding the cursor fix-up: the cache shows {} {} on the cursor the last cursor click"
                        + " left empty (that click {})", name(), cur == null ? 0 : cur.getAmount(), Inv.id(cur),
                        answered ? "answered by " + ClickAnswers.SESSION.lastAnswer() : "not answered yet");
                }
                return null;
            }
        }
        return cursorToEmptySlot(cid, top);
    }

    /**
     * Shift-click {@code click} (plain or {@link #tracked}) at request action {@code self}, with the {@link #guard}:
     * refused by ZenithProxy for the cache's cursor, it runs again once the cursor shows empty
     * ({@link MenuClicks.CursorGuard#shiftDue}, {@link MenuClicks.CursorGuard#refused}). The same packet as the click.
     */
    private CustomAction guarded(int cid, InventoryAction click, int self) {
        return new CustomAction(cid, () -> {
            MenuClicks.CursorGuard g = guard;
            if (g == null) return click.packet();
            switch (g.shiftDue(self, now, cursorEmpty(), gateAnswered())) {
                case HOLD -> {
                    if (runAgain(self)) return null;
                }
                case GIVE_UP -> {
                    return null;
                }
                case SEND -> { }
            }
            MinecraftPacket p = click.packet();
            if (p == null && !cursorEmpty() && g.refused(self, now)) runAgain(self);
            return p;
        });
    }

    /** Empty hotbar + main slots of the open menu {@code c} (its numbering), in slot order: main inventory first. */
    private static List<Integer> emptyPlayerSlots(Container c, int top) {
        List<Integer> out = new ArrayList<>();
        for (int i = top; i < top + 36; i++) if (Inv.empty(c.getItemStack(i))) out.add(i);
        return out;
    }

    /**
     * Ticks between clicks: {@code build.containerClickTicks} when above 0 (fast clicks), else ZenithProxy's {@code
     * client.inventory.actionDelayTicks} as before.
     */
    @Override
    protected int clickDelay() {
        int fast = cfg.build.containerClickTicks;
        return fast > 0 ? Math.max(Math.max(1, minActionDelay()), fast) : super.clickDelay();
    }

    /**
     * Ticks the server's answer to a click may take ({@link TransferPlanner#waitsAfter}): {@code build.containerAckTicks}
     * with fast clicks, else {@link TransferPlanner#ACK_TICKS} as before.
     */
    private int ackTicks() {
        return cfg.build.containerClickTicks > 0 ? Math.max(1, cfg.build.containerAckTicks) : TransferPlanner.ACK_TICKS;
    }

    /**
     * Fast clicks: the snapshot is read (and the menu closed after it) {@code build.containerAckTicks} after the last
     * click, counting the waits already behind it ({@link TransferPlanner#tailWaits}); else one wait, as before.
     */
    @Override
    protected int tailWaits(List<InventoryAction> actions, int delay) {
        if (cfg.build.containerClickTicks <= 0) return super.tailWaits(actions, delay);
        int trailing = 0;
        for (int i = actions.size() - 1; i >= 0 && actions.get(i) instanceof WaitAction; i--) trailing++;
        return TransferPlanner.tailWaits(trailing, delay, ackTicks());
    }

    /**
     * Shift-click-only transfers close as soon as the server's answers are in ({@code build.containerAnswerEarly})
     * instead of after the fixed {@link #tailWaits}: at most {@link #ackTicks} ticks after the last click, as before.
     * Not with cursor clicks: ZenithProxy writes their prediction into the cache at once, so the cache cannot tell
     * the answer from the prediction (those keep the fixed wait). ZenithProxy's ShiftClick predicts nothing, so the
     * player inventory shows a shift-click's result only when the server's resync is in. With
     * {@code build.containerAnswerWaits}: a transfer whose last click is a shift-click (shift-clicks only, or a top-up
     * that ends in shift-clicks) closes on that click's answer ({@link ClickAnswers}) or on the plan's moves showing in
     * full, at most {@code build.containerShiftAnswerTicks} real ticks after it; a top-up that ends in a cursor click on
     * that click's answer, at most {@link #ackTicks} real ticks after it.
     */
    @Override
    protected int answerWait() {
        // not with ncpStrict: ZenithProxy closes the menu itself right after the request; the request ends a tick
        // after its last click (MenuClicks.answerWait). With build.containerAnswerWaits withdrawals with cursor clicks
        // too: their answer is the last click's (ClickAnswers), not the inventory; a last shift-click waits up to
        // build.containerShiftAnswerTicks for its answer or its moves
        return MenuClicks.answerWait(switches(), mode == Mode.WITHDRAW || mode == Mode.DEPOSIT, cursorClicks,
            lastIsShift(), !expectedMoves.isEmpty(), ackTicks(), cfg.build.containerShiftAnswerTicks);
    }

    @Override
    protected boolean answersIn() {
        return MenuClicks.answersIn(switches(), cursorClicks, lastIsShift(), ClickAnswers.SESSION.answered(lastToken),
            movesSeen());
    }

    /**
     * The request's last click is a shift-click: a deposit's clicks all are; a withdrawal's shift-clicks come after its
     * cursor clicks.
     */
    private boolean lastIsShift() {
        return mode == Mode.DEPOSIT || shiftClicks > 0;
    }

    /** The plan's moves show in the player inventory (ZenithProxy's ShiftClick predicts nothing). */
    private boolean movesSeen() {
        Map<String, Integer> now = playerCounts();
        for (var e : expectedMoves.entrySet()) {
            int d = now.getOrDefault(e.getKey(), 0) - playerBefore.getOrDefault(e.getKey(), 0);
            if ((mode == Mode.WITHDRAW ? d : -d) < e.getValue()) return false;
        }
        return true;
    }

    /** The menu switches of this use, from the config. */
    private MenuClicks.Switches switches() {
        var b = cfg.build;
        return new MenuClicks.Switches(b.containerClickTicks > 0, CONFIG.client.inventory.ncpStrict,
            b.containerAnswerEarly, b.containerAnswerWaits, b.containerSettleFast, b.containerClickEveryTick);
    }

    @Override
    protected boolean answersFast() {
        return (mode == Mode.WITHDRAW || mode == Mode.DEPOSIT) && switches().waitsForAnswers();
    }

    @Override
    protected int gateFrom() {
        return gateFrom;
    }

    @Override
    protected int gateEnd() {
        return gateEnd;
    }

    @Override
    protected boolean gateAnswered() {
        return ClickAnswers.SESSION.answered(gateToken);
    }

    @Override
    protected void gateSkipped() {
        MenuClicks.Waits w = waitLog;
        if (w != null) w.gateSkipped();
    }

    @Override
    protected void menuClosing() {
        MenuClicks.Waits w = waitLog;
        if (w != null) w.closed(now, ClickAnswers.SESSION.answered(lastToken), lastIsShift() && movesSeen());
    }

    /** The log line's suffix: how the answer waits ended ({@link MenuClicks.Waits}); "" with them off. */
    private String waitsText() {
        MenuClicks.Waits w = waitLog;
        if (w == null) return "";
        return w.text(gateFrom >= 0, ClickAnswers.SESSION.answered(gateToken), ClickAnswers.SESSION.answered(lastToken),
            guard);
    }

    @Override
    protected boolean clickEveryTick() {
        return mode != Mode.SCAN && switches().clicksEveryTick();
    }

    @Override
    protected boolean settleFast() {
        return mode != Mode.SCAN && MenuClicks.settleFast(switches(), answersIn(), cursorEmpty());
    }

    /**
     * {@code click} as an action that notes, when its packet goes out, what the server's answer to it will show
     * ({@link ClickAnswers}): the slots and the cursor as ZenithProxy predicted them (the packet's changed slots and
     * carried item); a shift-click predicts nothing, its planned whole stack leaves {@code shiftSlot} empty. The same
     * packet as the click itself. The ticks the gate's click, the first shift-click and the last click go out are noted
     * for the log line ({@link MenuClicks.Waits}).
     */
    private CustomAction tracked(int cid, InventoryAction click, @Nullable Integer shiftSlot, boolean gate, boolean last,
                                 boolean firstShift) {
        return new CustomAction(cid, () -> {
            MinecraftPacket p = click.packet();
            if (p instanceof ServerboundContainerClickPacket out) {
                MenuClicks.Waits w = waitLog;
                if (firstShift && w != null) w.firstShift(now);
                if (!gate && !last) return p;
                Map<Integer, Boolean> slots = new HashMap<>();
                Boolean cursor = null;
                if (shiftSlot != null) {
                    slots.put(shiftSlot, true);
                } else {
                    for (var e : out.getChangedSlots().int2ObjectEntrySet()) slots.put(e.getIntKey(), Inv.empty(e.getValue()));
                    cursor = Inv.empty(out.getCarriedItem());
                }
                long token = ClickAnswers.SESSION.sent(cid, slots, cursor);
                if (gate) {
                    gateToken = token;
                    if (w != null) w.gateClick(now);
                    // the plan's cursor after its cursor clicks, not ZenithProxy's prediction: every cursor sequence
                    // leaves it empty (TransferPlanner), while ZenithProxy predicts from the cache, which a stale state
                    // may reach before the gate's click goes out (6-click top-ups, predicted non-empty)
                    MenuClicks.CursorGuard g = guard;
                    if (g != null) g.gateClick(now, Boolean.TRUE);
                }
                if (last) {
                    lastToken = token;
                    if (w != null) w.lastClick(now);
                }
            }
            return p;
        });
    }

    /** Container contents when the post-transfer snapshot could not be read (closed early). */
    private ContainerSnapshot estimateAfter(ContainerSnapshot b, Map<String, Integer> moved) {
        Map<String, Integer> items = new TreeMap<>(b.items());
        moved.forEach((id, n) -> items.merge(id, mode == Mode.WITHDRAW ? -n : n, Integer::sum));
        items.values().removeIf(v -> v <= 0);
        return new ContainerSnapshot(b.pos(), b.containerType(), items, b.freeSlots(), b.totalSlots());
    }

    private static Map<String, Integer> playerCounts() {
        Map<String, Integer> m = new TreeMap<>();
        for (int i = ItemIds.MAIN_START; i <= ItemIds.OFFHAND; i++) {
            var s = Inv.stack(i);
            String id = Inv.id(s);
            if (id != null) m.merge(id, s.getAmount(), Integer::sum);
        }
        return m;
    }

    private static Map<String, Integer> normalise(Map<String, Integer> in) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (in != null) in.forEach((k, v) -> out.merge(ItemIds.ns(k), v == null ? 0 : v, Integer::sum));
        return out;
    }

    /**
     * The container's stacks with every firework rocket that is no flight rocket ({@link Inv#FLIGHT_ROCKET}: a star's
     * explosion, a longer boost) under another id, so a rocket withdrawal never takes it ({@code build.elytraLegs}).
     */
    private static List<TransferPlanner.Stack> flightRocketsOnly(Container c) {
        List<TransferPlanner.Stack> out = new ArrayList<>();
        for (TransferPlanner.Stack st : containerStacks(c)) {
            boolean other = Inv.ROCKET_ID.equals(st.itemId()) && !Inv.FLIGHT_ROCKET.test(c.getItemStack(st.slot()));
            out.add(other ? new TransferPlanner.Stack(st.slot(), st.itemId() + "#not_for_flight", st.count(), st.maxStack()) : st);
        }
        return out;
    }
}
