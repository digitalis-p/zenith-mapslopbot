package io.github.digitalisp.mapart.bot.impl;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CustomAction;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.actions.WaitAction;
import com.zenith.feature.player.World;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.ContainerSnapshot;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.INVENTORY;

/**
 * Get to a block, open its menu by right-clicking it, run one InventoryActionRequest built by the subclass, snapshot,
 * close. Getting there is an {@link Approach}: a stand spot from which the centre
 * of a face of the block is in reach and in sight (wall signs included), walked to with an exact-cell Baritone goal;
 * look at that face centre, click on the next tick. The spot that worked is remembered for the session
 * ({@link ZenithBotActions#standSpot}). All container clicks of a session go into ONE request because with
 * {@code client.inventory.ncpStrict} ZenithProxy closes the container after every completed request; the request is
 * only submitted after a tick without movement keys (Grim MultiActionsC). It ends with a wait + a snapshot action so
 * the "after" contents are read while still open. Everything after the menu opened is a {@link ContainerRun}: in an
 * open menu ZenithProxy completes every input request false, so the still tick there is "no keys, not rejected, the
 * bot did not move" ({@link InputGate#stillInMenu}), not "executed".
 */
abstract class ContainerTask extends ZenithTask {
    /** WALK: get to it and click; OPEN: wait for the menu; MENU: clicks, close, settle ({@link ContainerRun}). */
    enum Phase { WALK, OPEN, MENU }

    static final int OPEN_TIMEOUT = 40;
    static final int OPEN_ATTEMPTS = 3;

    protected final Pos pos;
    private Phase phase = Phase.WALK;
    private int openAttempts;
    private long clickedAt = -1;
    private @Nullable Approach<Aiming.Shot> approach;
    /** {@link #step}: the walk close to a far container before its stand spots are searched. */
    private boolean nearWalked;
    /** When any container task last clicked a container open (all tasks share it: a sweep runs one task per chest). */
    private static volatile long lastOpenNanos;
    /** No stand spot found: the walk right up to the container and the second search (once). */
    private boolean closeRetried;
    private boolean closeWalk;
    /** Farther than this (blocks), or more than {@code 3} up or down, a container without a planned stand cell is walked to first. */
    static final int NEAR_FIRST = 10;
    /** That walk ends within this many blocks of it. */
    static final int NEAR_RANGE = 4;
    /** {@link #walkCloser} outside the material regions: levels above or below the container the walk may end on. */
    static final int CLOSER_Y = 2;

    /** {@link #floorY}'s column: material regions starting at most this many levels below the container count. */
    static final int COLUMN_BELOW = 5;

    /** The feet level of the stand row of {@code p}'s storage ({@link #floorY(List, Pos, boolean)}). */
    private int floorY(Pos p) {
        return floorY(io.github.digitalisp.mapart.config.CsMaterials.allRegions(ctx.config().site), p,
            ctx.config().build.containerColumnFloor);
    }

    /**
     * The feet level of the stand row of {@code p}'s storage, for a {@code p} inside a material region: with
     * {@code column} ({@code build.containerColumnFloor}) the lowest level of the regions over {@code p}'s x/z that
     * start at most {@link #COLUMN_BELOW} levels below it (a region stacked on another one, like the soul sand sorter's
     * upper rows y177-179 on its front rows y174-175, is opened from the lower one's stand row), never above the lowest
     * level of the region holding {@code p}; without, the lowest level of the region holding {@code p}. Outside every
     * region {@code p}'s own level. Live 2026-10-23, soul sand: the y179 barrels got the goal "feet at y177",
     * which no cell meets, and 4 failed searches each (about 7 s) before the approach opened them from where the bot
     * stood: 380 of one STOCK_CHECK's 463 s.
     */
    static int floorY(List<io.github.digitalisp.mapart.config.MapArtConfig.RegionCfg> regions, Pos p, boolean column) {
        Integer holding = null;
        Integer lowest = null;
        for (var r : regions) {
            if (r == null || r.min == null || r.max == null) continue;
            int lo = Math.min(r.min.y(), r.max.y());
            if (holding == null && r.contains(p)) holding = lo;
            boolean over = r.contains(p.x(), lo, p.z()) && lo <= p.y() && lo >= p.y() - COLUMN_BELOW;
            if (column && over && (lowest == null || lo < lowest)) lowest = lo;
        }
        if (holding == null) return p.y();
        return lowest != null ? Math.min(holding, lowest) : holding;
    }

    /**
     * A plain walk to within {@code range} blocks of the container, never the break retry: failing is fine, the stand
     * spot search goes on from where the bot stands. The goal is the storage floor ({@link #floorY}): live 2026-10-13
     * 23:43 (soul sand) a goal within 3 levels of a chest 4 above the floor was out of reach and the break
     * retry broke storage chests; live 2026-10-14 01:05 a goal on any level within 8 let the walk end on the glow
     * lichen below the storage, the bot dropping down there and walking back up for every upper-row chest.
     */
    private TaskStatus walkCloser(Pos target, int range) {
        int floor = floorY(target);
        GoalNearXZ goal = new GoalNearXZ(target.x(), floor, target.z(), range, floor == target.y() ? CLOSER_Y : 0);
        if (feetIn(goal)) return TaskStatus.SUCCESS;
        return walkRaw(List.of(goal), () -> feetIn(goal), Walker.MAX_FAILURES);
    }

    private boolean farFromIt() {
        return Math.hypot(BOT.getX() - (pos.x() + 0.5), BOT.getZ() - (pos.z() + 0.5)) > NEAR_FIRST
            || Math.abs(BOT.getY() - pos.y()) > 3;
    }
    private @Nullable ContainerRun menu;
    /** The menu's ZenithProxy side ({@link #runAgain}). */
    private @Nullable MenuIo menuIo;
    protected @Nullable Container container;
    protected int top;
    protected @Nullable ContainerSnapshot before;
    protected volatile @Nullable ContainerSnapshot after;
    protected @Nullable String problem;
    /** Blocks that open this container (both halves of a double chest), known once its chunk is loaded. */
    private @Nullable List<Pos> halves;
    /** The stand cell a planned sweep chose ({@code BotActions.planVisits}), tried before the remembered one; or null. */
    private @Nullable Pos standHint;
    /** The engine asked to park (eat, stand still, fight, wait for a totem): taken while still walking (WALK). */
    private boolean parkRequested;
    private boolean parked;
    /** The claim key asked of the menu gate ({@link #claimKey}); given back when the task ends. Null until asked. */
    private @Nullable String gateKey;

    ContainerTask(ZenithBotActions ctx, Pos pos, long timeoutTicks) {
        super(ctx, timeoutTicks);
        this.pos = pos;
    }

    @Override
    protected boolean opensContainers() {
        return true;
    }

    /**
     * The walks to and between containers: plain (nothing is clicked until the bot stands; the open waits for the quiet
     * window), and restock legs in a restock ({@link SprintLegs.Walk#CONTAINER}).
     */
    @Override
    protected SprintLegs.Walk walkKind() {
        return SprintLegs.Walk.CONTAINER;
    }

    /** Stand at {@code cell} if it works (a planned sweep's stop): ranked before the cell remembered for the session. */
    void standAt(@Nullable Pos cell) {
        this.standHint = cell;
    }

    /** Nothing is clicked in the menu (a scan): {@link ContainerRun}'s look-only mode. */
    protected boolean looksOnly() {
        return false;
    }

    /** Null if the block at pos is acceptable, else a reason. */
    protected abstract @Nullable String checkBlock();

    /** Null if the opened menu is acceptable, else a reason. */
    protected abstract @Nullable String checkContainer(Container c);

    /** Clicks to run while the menu is open (may be empty). Slot numbers are the open container's. */
    protected abstract List<InventoryAction> actions(Container c, int top);

    /** Called once the menu is closed again (inventory cache settled). Return SUCCESS or fail(...). */
    protected abstract TaskStatus finish();

    /** Called each tick before walking; return non-null to end the task early (e.g. nothing to do). */
    protected @Nullable TaskStatus preflight() {
        return null;
    }

    @Override
    protected final TaskStatus step() {
        switch (phase) {
            case WALK -> {
                if (parkRequested || parked) return parkTick();
                String refusal = protectedRegionRefusal(pos, "open");
                if (refusal == null && halves != null)
                    for (Pos h : halves) if (refusal == null) refusal = protectedRegionRefusal(h, "open");
                if (refusal != null) return fail(refusal);
                TaskStatus early = preflight();
                if (early != null) return early;
                if (!World.isChunkLoadedBlockPos(pos.x(), pos.z())) {
                    TaskStatus s = walkNear(pos, 3, 3);
                    if (s == TaskStatus.FAILED) return fail("walking to " + pos + ": " + walker.error());
                    return TaskStatus.RUNNING;
                }
                String bad = checkBlock();
                if (bad != null) return fail(bad);
                if (halves == null) halves = ctx.containerHalves(pos);
                if (clickedAt < 0 && Inv.containerOpen()) {
                    closeContainerNow(); // somebody else's menu
                    holdStill();
                    return TaskStatus.RUNNING;
                }
                if (approach == null && closeWalk) {
                    TaskStatus w = walkCloser(pos, 2);
                    if (w == TaskStatus.RUNNING) return w;
                    closeWalk = false;
                    walker.stop();
                }
                if (approach == null && !nearWalked && standHint == null && farFromIt()) {
                    // no planned stand cell and the bot far away or on another level (the platform, the clear stand):
                    // close to it first, so the stand spots are searched from where they can be walked to (live
                    // 2026-10-13, soul sand: all 59 chests of the upper row failed at once from the platform after
                    // a clear, twice, and were skipped for the session as "Material chests unreachable")
                    TaskStatus s = walkCloser(pos, NEAR_RANGE);
                    if (s == TaskStatus.RUNNING) return s;
                    nearWalked = true; // failed: the approach tries from here and says why
                }
                if (approach == null) {
                    walker.stop(); // the walk that loaded the chunk ends here
                    // a cell that did not see this container after all is not tried again, the sweep's included
                    Set<Pos> avoid = ctx.failedStandSpots(pos);
                    Pos first = standHint != null && !avoid.contains(standHint) ? standHint : ctx.standSpot(pos);
                    approach = new Approach<>(blockName() + " at " + pos, halves, reach(), first,
                        new ApproachIo(() -> halves != null ? halves : List.of(pos), StandSpots.CellRule.ANYWHERE))
                        .avoid(avoid)
                        .brake(ctx.config().build.standSpotBrake)
                        .coast(ctx.config().build.coastClick);
                }
                // the coordination's claim on this menu: asked for once, then polled;
                // nothing is walked or clicked until it is held (a single bot's gate always says yes)
                if (gateKey == null) gateKey = claimKey();
                if (!ctx.menuGate().mayOpen(gateKey)) {
                    holdStill();
                    return TaskStatus.RUNNING;
                }
                // container opens are spaced (build.containerOpenMinMillis): live 2026-10-14 01:04 and 20:29 the
                // connection was lost ("You have lost connection to the server") both times in a sweep opening 2-4 chests
                // a second from the same stand spot
                int gap = ctx.config().build.containerOpenMinMillis;
                if (gap > 0 && System.nanoTime() - lastOpenNanos < gap * 1_000_000L && !walker.active()) {
                    holdStill();
                    return TaskStatus.RUNNING;
                }
                TaskStatus s = approach.tick(now);
                if (s != TaskStatus.RUNNING) ctx.missedStandSpots(pos, approach.sightMisses());
                if (s == TaskStatus.FAILED && !closeRetried && String.valueOf(approach.failure()).startsWith("no stand spot")) {
                    // the stand spot search depends on where it starts (live 2026-10-13, soul sand: the west end's
                    // upper chest had none "from the bot" 8 blocks off, yet one from the storage row): right up to
                    // it with the pathfinder, then once more, before this counts as a failed open
                    closeRetried = true;
                    approach = null;
                    closeWalk = true;
                    return TaskStatus.RUNNING;
                }
                if (s == TaskStatus.FAILED) return fail("cannot reach " + pos + ": " + approach.failure());
                if (s == TaskStatus.SUCCESS) {
                    clickedAt = now;
                    lastOpenNanos = System.nanoTime();
                    enter(Phase.OPEN);
                }
                return TaskStatus.RUNNING;
            }
            case OPEN -> {
                if (claimGone()) return dropMenu();
                return openTick();
            }
            case MENU -> {
                if (claimGone()) return dropMenu();
                ContainerRun run = menu;
                if (run == null) return fail("menu open without a run");
                TaskStatus s = run.tick(now);
                if (run.step() == ContainerRun.Step.SETTLE || s == TaskStatus.SUCCESS) ownContainerId = 0;
                if (s == TaskStatus.FAILED) return fail(run.failure());
                if (s == TaskStatus.RUNNING) return s;
                if (run.problem() != null) problem = run.problem();
                return finish();
            }
        }
        return fail("bad phase " + phase);
    }

    /**
     * Parks while getting there (WALK: nothing clicked yet, no menu open), so the engine can eat, stand still, fight or
     * leave harm at once instead of after the click and the menu (live 2026-10-12: "finishing scan first" while two
     * pillagers shot the bot). Once the click went out (OPEN, MENU) it runs to its end, which is short.
     */
    @Override
    public boolean requestPark() {
        if (phase != Phase.WALK || clickedAt >= 0) return false;
        if (!parked) parkRequested = true;
        return true;
    }

    @Override
    public boolean parked() {
        return parked;
    }

    /** A resumed task looks for its stand spot again from where the bot stands now. */
    @Override
    public void resume() {
        parked = false;
        parkRequested = false;
        approach = null;
        clicker.reset();
    }

    private TaskStatus parkTick() {
        if (approach != null) approach.pauseWalk();
        walker.stop();
        holdStill();
        if (BOT.isOnGround()) {
            parked = true;
            parkRequested = false;
        }
        return TaskStatus.RUNNING;
    }

    /** {@link ContainerRun.Io} on ZenithProxy: the open menu, our input requests, the InventoryManager. */
    private final class MenuIo implements ContainerRun.Io {
        private @Nullable RequestFuture request;
        /** The request {@link #request} completes (its next action is moved past an answered gate). */
        private @Nullable InventoryActionRequest current;
        /** {@code build.containerClickEveryTick}: the request's actions, one-action requests one at a time. */
        private MenuClicks.@Nullable Pacer paced;
        private List<InventoryAction> pacedActions = List.of();

        @Override
        public int openMenuId() {
            return Inv.openContainerId();
        }

        @Override
        public void holdStill() {
            ContainerTask.this.holdStill();
        }

        @Override
        public InputGate.@Nullable Sent previousInput(long tick) {
            return clicker.previous(tick);
        }

        @Override public double x() { return BOT.getX(); }
        @Override public double y() { return BOT.getY(); }
        @Override public double z() { return BOT.getZ(); }

        @Override
        public boolean inventoryBusy() {
            return INVENTORY.hasActiveRequest();
        }

        @Override
        public int submitClicks(int menuId) {
            Container c = Inv.openContainer();
            if (c == null || c.getContainerId() != menuId) return 0;
            List<InventoryAction> list = new ArrayList<>(actions(c, top));
            if (list.isEmpty()) return 0;
            final int cid = c.getContainerId();
            int delay = clickDelay();
            if (answerWait() <= 0) {
                for (int i = tailWaits(list, delay); i > 0; i--) list.add(new WaitAction());
                list.add(new CustomAction(cid, () -> {
                    Container open = Inv.openContainer();
                    if (open != null && open.getContainerId() == cid) after = snapshot(open);
                    return null;
                }));
            } // else: the run waits for the answers itself and the snapshot is read when it closes (closeMenu)
            if (clickEveryTick()) return startPaced(list, delay);
            var builder = InventoryActionRequest.builder()
                .owner(ContainerTask.this)
                .priority(Clicker.PRIORITY)
                .actions(list);
            if (delay != CONFIG.client.inventory.actionDelayTicks) builder.actionDelayTicks(delay);
            InventoryActionRequest req = builder.build();
            RequestFuture f = INVENTORY.submit(req);
            if (f == RequestFuture.rejected) return -1;
            request = f;
            current = req;
            // ZenithProxy runs one action every delay + 1 ticks (MenuClicks.pitch); the old estimate stays when the
            // answer waits are off
            return list.size() * (answersFast() ? MenuClicks.pitch(delay) : delay);
        }

        /**
         * {@code build.containerClickEveryTick}: the first action goes out in this tick, as a request's first action
         * would; {@link #duringRequest} sends the others, one per {@code delay} ticks.
         */
        private int startPaced(List<InventoryAction> list, int delay) {
            boolean[] waits = new boolean[list.size()];
            for (int i = 0; i < waits.length; i++) waits[i] = list.get(i) instanceof WaitAction;
            paced = new MenuClicks.Pacer(waits, gateFrom(), gateEnd(), delay, CLICK_RATE);
            pacedActions = list;
            request = null;
            current = null;
            tickPaced(now);
            return list.size() * Math.max(1, delay);
        }

        /** One action of the paced request when it is due: its own one-action request with delay 0 (runs this tick). */
        private void tickPaced(long tick) {
            MenuClicks.Pacer p = paced;
            if (p == null || p.finished()) return;
            // never with keys or sprint in the input (Grim MultiActionsC), never while another request runs
            boolean may = InputGate.noKeysInMenu(tick, clicker.previous(tick)) && !INVENTORY.hasActiveRequest();
            long nanos = System.nanoTime();
            int i = p.due(tick, nanos, gateAnswered(), cursorEmpty(), may);
            if (p.skipped()) gateSkipped();
            if (i < 0) return;
            RequestFuture f = INVENTORY.submit(InventoryActionRequest.builder()
                .owner(ContainerTask.this)
                .priority(Clicker.PRIORITY)
                .actions(List.of(pacedActions.get(i)))
                .actionDelayTicks(0)
                .build());
            if (f == RequestFuture.rejected) return; // due again next tick
            p.sent(tick, nanos);
            request = f;
        }

        @Override
        public void duringRequest(long tick) {
            if (paced != null) {
                tickPaced(tick);
                return;
            }
            InventoryActionRequest req = current;
            RequestFuture f = request;
            if (req == null || f == null || f.isCompleted() || gateFrom() < 0) return;
            // the answer gate (build.containerAnswerWaits): the client tick thread runs this and the InventoryManager
            // alike, the manager after us in the same tick
            int to = MenuClicks.skipTo(req.getActionExecIndex(), gateFrom(), gateEnd(), gateAnswered(), cursorEmpty());
            if (to >= 0) {
                req.setActionExecIndex(to);
                gateSkipped();
            }
        }

        /** {@link ContainerTask#runAgain}: the running request's next action is {@code index} again. */
        boolean runAgain(int index) {
            InventoryActionRequest req = current;
            RequestFuture f = request;
            if (paced != null || req == null || f == null || f.isCompleted()) return false;
            req.setActionExecIndex(index);
            return true;
        }

        @Override
        public @Nullable Boolean clicksDone() {
            MenuClicks.Pacer p = paced;
            if (p != null && !p.finished()) return null;
            RequestFuture f = request;
            if (f == null) return Boolean.TRUE;
            if (!f.isCompleted()) return null;
            return f.isAccepted();
        }

        @Override
        public boolean settleFast() {
            return ContainerTask.this.settleFast();
        }

        @Override
        public int answerWait() {
            return ContainerTask.this.answerWait();
        }

        @Override
        public boolean answersIn() {
            return ContainerTask.this.answersIn();
        }

        @Override
        public void closeMenu() {
            Container open = Inv.openContainer();
            if (after == null && open != null && open.getContainerId() == ownContainerId) after = snapshot(open);
            menuClosing();
            closeContainerNow();
        }
    }

    /**
     * The coordination's claim on this menu went away after the click (lost to its ttl, the link down: another bot may
     * open it now). A single bot's gate never says so.
     */
    private boolean claimGone() {
        return gateKey != null && !ctx.menuGate().mayKeep(gateKey);
    }

    /** The menu closes at once, whatever its clicks were doing; the task fails (the engine's retry asks again). */
    private TaskStatus dropMenu() {
        closeContainerNow();
        return fail("the claim on this menu was lost (another bot may use it): menu closed at once");
    }

    /** Waits for the menu the approach's click should open; clicks again (up to {@link #OPEN_ATTEMPTS}) if it does not. */
    private TaskStatus openTick() {
        holdStill();
        Container open = Inv.openContainer();
        if (open != null) {
            ownContainerId = open.getContainerId();
            container = open;
            top = open.getSize() - 36;
            String bad = checkContainer(open);
            if (bad != null) return fail(bad);
            if (approach != null) ctx.rememberStandSpot(pos, approach.standingCell());
            before = snapshot(open);
            onOpened(open);
            menuIo = new MenuIo();
            menu = new ContainerRun(ownContainerId, menuIo, looksOnly()).coasting(ctx.config().build.coastClick)
                .answersFast(answersFast());
            enter(Phase.MENU);
            return TaskStatus.RUNNING;
        }
        if (now - clickedAt < OPEN_TIMEOUT) return TaskStatus.RUNNING;
        clickedAt = -1;
        if (++openAttempts >= OPEN_ATTEMPTS) return fail("container at " + pos + " did not open");
        if (approach != null) approach.retry();
        enter(Phase.WALK);
        return TaskStatus.RUNNING;
    }

    /** Block id at {@link #pos} without the namespace, for log lines. */
    private String blockName() {
        return ItemIds.path(ctx.world().block(pos).id());
    }

    /** Hook after the menu opened and {@link #before} was taken. */
    protected void onOpened(Container c) { }

    /**
     * The claim this task's menu needs ({@link io.github.digitalisp.mapart.bot.MenuGate}): {@code chest:<x,y,z>} of the container's
     * canonical block. The cartography table and the anvil are stations ({@link LockMapTask}, {@link RenameMapTask}).
     */
    protected String claimKey() {
        return io.github.digitalisp.mapart.bot.MenuGate.chest(ctx.canonicalContainerPos(pos));
    }

    /** The menu is closed (the base class closed it if it was still open): the claim goes back. */
    @Override
    protected void onRelease() {
        String k = gateKey;
        gateKey = null;
        if (k != null) ctx.menuGate().done(k);
    }

    /**
     * Minimum ticks between the request's clicks. Steps that wait for a server-computed result slot (cartography,
     * anvil) need a round trip between clicks; ZenithProxy's configured delay is used when larger.
     */
    protected int minActionDelay() {
        return 1;
    }

    /**
     * Ticks between the request's actions: ZenithProxy's {@code client.inventory.actionDelayTicks}, or
     * {@link #minActionDelay} when larger. A subclass may click faster ({@link ContainerTransferTask} with
     * {@code build.containerClickTicks}).
     */
    protected int clickDelay() {
        return Math.max(Math.max(1, minActionDelay()), CONFIG.client.inventory.actionDelayTicks);
    }

    /**
     * WaitActions between the last of {@code actions} (the clicks, with any waits {@link #actions} put after them) and
     * the snapshot that reads the container before the close, at {@code delay} ticks per action: one (the cadence
     * before {@code build.containerClickTicks}).
     */
    protected int tailWaits(List<InventoryAction> actions, int delay) {
        return 1;
    }

    /**
     * Ticks to wait for the server's answers after the request's last click instead of {@link #tailWaits} and the
     * snapshot in the request, ending as soon as {@link #answersIn}; 0 = the fixed tail, as before. Valid once
     * {@link #actions} has run (the engine's submit asks after it).
     */
    protected int answerWait() {
        return 0;
    }

    /** The answers to the clicks are in the cache ({@link #answerWait} above 0). */
    protected boolean answersIn() {
        return true;
    }

    /**
     * {@code build.containerClickEveryTick}: every container click of a session, whichever task, counts against
     * {@link MenuClicks#RATE_CLICKS} in {@link MenuClicks#RATE_MILLIS}.
     */
    private static final MenuClicks.Rate CLICK_RATE = MenuClicks.Rate.containerClicks();

    /**
     * {@link ContainerRun#answersFast}: the run's steps after the request follow in one tick ({@code build.containerAnswerWaits}).
     * Off by default.
     */
    protected boolean answersFast() {
        return false;
    }

    /**
     * The answer gate in the request {@link #actions} built ({@link MenuClicks#skipTo}): index of its first wait, -1 =
     * none (the default).
     */
    protected int gateFrom() {
        return -1;
    }

    /** Index of the action after the gate (the cursor fix-up). */
    protected int gateEnd() {
        return -1;
    }

    /** The gate's click was answered ({@link ClickAnswers}). */
    protected boolean gateAnswered() {
        return false;
    }

    /** The gate's answer moved the request on ({@link MenuClicks#skipTo}); for the log ({@link MenuClicks.Waits}). */
    protected void gateSkipped() { }

    /** The run closes the menu now (its own close, tick {@link #now}); for the log ({@link MenuClicks.Waits}). */
    protected void menuClosing() { }

    /**
     * From an action of the running request, while ZenithProxy runs it: run that action ({@code index}) again at the
     * request's next action tick (InventoryActionRequest.next() moved past it before it ran). False, and nothing done,
     * without such a request ({@code build.containerClickEveryTick}: each action is its own request).
     */
    protected final boolean runAgain(int index) {
        MenuIo io = menuIo;
        return io != null && io.runAgain(index);
    }

    /** The cache shows nothing on the cursor. */
    protected static boolean cursorEmpty() {
        return Inv.empty(Inv.cache().getMouseStack());
    }

    /** {@code build.containerClickEveryTick}: the actions go as one-action requests, one per tick ({@link MenuClicks.Pacer}). */
    protected boolean clickEveryTick() {
        return false;
    }

    /** {@code build.containerSettleFast}: the run may end without its settle ticks ({@link ContainerRun.Io#settleFast}). */
    protected boolean settleFast() {
        return false;
    }

    private void enter(Phase p) {
        phase = p;
    }

    protected Phase phase() {
        return phase;
    }

    @Override
    protected String progress() {
        return " (phase " + phase + ")" + (phase == Phase.WALK && approach != null ? approach.progress() : "")
            + (phase == Phase.MENU && menu != null ? menu.progress() : "");
    }

    /** Snapshot of the container's own slots. Double chests are one 54-slot menu. */
    protected ContainerSnapshot snapshot(Container c) {
        int own = c.getSize() - 36;
        Map<String, Integer> items = new TreeMap<>();
        int free = 0;
        for (int i = 0; i < own; i++) {
            ItemStack s = c.getItemStack(i);
            String id = Inv.id(s);
            if (id == null) free++;
            else items.merge(id, s.getAmount(), Integer::sum);
        }
        return new ContainerSnapshot(ctx.canonicalContainerPos(pos), typeName(c.getType()), items, free, own);
    }

    /**
     * A {@link KeySwaps} menu plan as ZenithProxy actions in menu {@code cid}: plain clicks ({@code ClickItem}, PICKUP),
     * hotbar-key trades ({@code MoveToHotbarSlot}, SWAP), waits, and the anvil's {@code name}.
     */
    static List<InventoryAction> menuActions(int cid, List<KeySwaps.Op> ops, @Nullable String name) {
        List<InventoryAction> out = new ArrayList<>(ops.size());
        for (KeySwaps.Op op : ops) {
            out.add(switch (op.kind()) {
                case LEFT -> new com.zenith.feature.inventory.actions.ClickItem(cid, op.slot(),
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction.LEFT_CLICK);
                case RIGHT -> new com.zenith.feature.inventory.actions.ClickItem(cid, op.slot(),
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction.RIGHT_CLICK);
                case SWAP -> new com.zenith.feature.inventory.actions.MoveToHotbarSlot(cid, op.slot(),
                    org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction.from(op.key()));
                case WAIT -> new WaitAction();
                case RENAME -> new com.zenith.feature.inventory.actions.RenameItem(cid, name == null ? "" : name);
            });
        }
        return out;
    }

    /** Container's own occupied slots as planner stacks. */
    protected static List<TransferPlanner.Stack> containerStacks(Container c) {
        List<TransferPlanner.Stack> out = new ArrayList<>();
        int own = c.getSize() - 36;
        for (int i = 0; i < own; i++) {
            ItemStack s = c.getItemStack(i);
            String id = Inv.id(s);
            if (id != null) out.add(new TransferPlanner.Stack(i, id, s.getAmount(), Inv.maxStack(s)));
        }
        return out;
    }

    static String typeName(ContainerType t) {
        return t == null ? "unknown" : t.name().toLowerCase(Locale.ROOT);
    }

    static boolean isStorage(ContainerType t) {
        return switch (t) {
            case GENERIC_9X1, GENERIC_9X2, GENERIC_9X3, GENERIC_9X4, GENERIC_9X5, GENERIC_9X6, GENERIC_3X3,
                 SHULKER_BOX, HOPPER -> true;
            default -> false;
        };
    }
}
