package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.MenuClicks.Op;
import io.github.digitalisp.mapart.bot.impl.MenuClicks.Script;
import io.github.digitalisp.mapart.bot.impl.MenuClicks.Switches;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Kind;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * One withdrawal after its menu opened, in real ticks ({@code MenuTimingTest}): {@link ContainerRun} on a simulated
 * ZenithProxy 3.7.2 InventoryManager and a server that answers every click {@link #latencyMs} after it went out, while
 * the sorter's hoppers update another chest slot every {@link #hopperEveryMs}.
 *
 * <ul>
 *   <li>InventoryManager (after the task in each tick): a request with {@code actionDelayTicks} d runs its next action
 *       when {@code tick - last > d} ({@code SyncedTickTimer}), one action every d + 1 ticks; a one-action request with
 *       delay 0 runs in the tick it was submitted. A request completes in the tick of its last action; the task sees
 *       it on the next tick.</li>
 *   <li>Server: vanilla clicks ({@link VanillaClickModel}); the cache holds ZenithProxy's prediction of a cursor click
 *       at once, a shift-click's result only with the server's contents. A cursor click is answered as
 *       {@link #cursorAnswer} says, a shift-click with the menu's contents (ZenithProxy's ShiftClick bumps the state
 *       id); either arrives {@link #latencyMs} after the click and is in the cache from the first tick after it.</li>
 * </ul>
 *
 * The decisions are production code, wired as ContainerTask's MenuIo and ContainerTransferTask wire them: the request's
 * actions ({@link MenuClicks#withdraw} and the tail of {@link TransferPlanner#tailWaits} plus the snapshot when the run
 * does not wait for answers), the answer gate ({@link MenuClicks#skipTo}), the pacer ({@link MenuClicks.Pacer}), the
 * clicks noted for {@link ClickAnswers} (the gate's click and the last click), the run's waits and closes
 * ({@link MenuClicks#answerWait}, {@link MenuClicks#answersIn}, {@link MenuClicks#settleFast}), the log line's account
 * of the waits ({@link MenuClicks.Waits}).
 */
final class MenuTimingSim implements ContainerRun.Io {
    static final int MENU = 3;

    /** How the server answers a cursor click. */
    enum Answer { SLOT, CURSOR, CONTENT, NONE }

    /** One action that went out: tick, wall-clock ms, what. */
    record Sent(long tick, long ms, String what) { }

    final Switches sw;
    final int delay;
    final int ack;
    final List<Step> steps;
    private final List<Integer> empty;
    int latencyMs = 42;
    Answer cursorAnswer = Answer.SLOT;
    /** {@code build.containerShiftAnswerTicks}: the cap of a tail after a shift-click with the answer waits. */
    int shiftAnswerTicks = 20;
    /**
     * A hopper refills the slot of the last shift-click before the server's contents go out: the contents show that
     * slot full, so {@link ClickAnswers} takes them for no answer, while the player inventory shows the moves.
     */
    boolean hopperRefillsShiftSlot;
    /** 0: no hopper. */
    int hopperEveryMs;
    int hopperSlot = 20;
    /**
     * The gate's click slot is updated too, showing it as before that click: the late answer to an earlier click on
     * the same slot (a top-up's pick-up), or a hopper refilling it.
     */
    boolean hopperOnGateSlot;
    /**
     * A late full state of cursor click {@code staleStep} (index into the steps; the state right after it) reaches the
     * cache and {@link ClickAnswers} at {@link #staleAtMs}: the server's full state for a click with an old state id,
     * arriving after the later clicks' predictions. -1: none.
     */
    int staleStep = -1;
    long staleAtMs;
    /** Above 0: the server's full state as it is then reaches the cache (and {@link ClickAnswers} if {@link #staleFixSeen}). */
    long staleFixedAtMs;
    boolean staleFixSeen = true;
    /**
     * Above 0: a slot update of the gate's click slot showing it filled reaches {@link ClickAnswers} then (a hopper
     * refilling the slot the pick-up emptied: it matches the gate's prediction and counts as its answer).
     */
    long gateSlotFilledAtMs;
    long tickMs = 50;

    final ClickAnswers answers = new ClickAnswers();
    private final VanillaClickModel truth;
    private final VanillaClickModel cache;

    // the request
    private enum Act { CLICK, WAIT, GATE, FIXUP, SNAPSHOT }

    private record Action(Act act, int step, boolean gate, boolean last) { }

    private final List<Action> actions = new ArrayList<>();
    private int gateFrom = -1;
    private int gateEnd = -1;
    private int cursorClicks;
    private int lastShiftStep = -1;
    private int firstShiftStep = -1;
    private MenuClicks.@Nullable Waits waitLog;
    /** As ContainerTransferTask: the answer waits with a gate, not one click per tick. */
    MenuClicks.@Nullable CursorGuard guard;
    private long gateToken;
    private long lastToken;
    private int imNext = -1;
    private long imLast = Long.MIN_VALUE / 2;
    private MenuClicks.@Nullable Pacer pacer;
    private final MenuClicks.Rate rate = MenuClicks.Rate.containerClicks();
    private @Nullable Integer queued;

    // the world
    private long now;
    private long nowMs;
    private boolean open = true;
    private record Arrival(long ms, int seq, Runnable deliver) { }
    private final PriorityQueue<Arrival> arrivals = new PriorityQueue<>((a, b) -> a.ms() != b.ms()
        ? Long.compare(a.ms(), b.ms()) : Integer.compare(a.seq(), b.seq()));
    private int seq;
    private long lastShiftAnsweredMs = Long.MAX_VALUE;

    // records
    final List<Sent> sent = new ArrayList<>();
    /** Ticks the fix-up ran (not held). */
    final List<Long> fixups = new ArrayList<>();
    /** Ticks the fix-up clicked something into an empty slot. */
    final List<Long> fixupClicks = new ArrayList<>();
    /** Ticks a shift-click was refused for the cache's cursor (ZenithProxy's ShiftClick sends nothing). */
    final List<Long> refusals = new ArrayList<>();
    long snapshotTick = -1;
    long closeTick = -1;
    long endTick = -1;
    int maxActionsInATick;
    private long actionsTick = -1;
    private int actionsThisTick;
    TaskStatus status = TaskStatus.RUNNING;
    @Nullable ContainerRun run;

    MenuTimingSim(Switches sw, int delay, int ack, VanillaClickModel start, List<Step> steps) {
        this.sw = sw;
        this.delay = delay;
        this.ack = ack;
        this.steps = List.copyOf(steps);
        this.empty = start.emptyPlayerSlots();
        this.truth = start.copy();
        this.cache = start.zenithPrediction();
    }

    /** Runs the menu use from tick 1 (the bot holds still, the menu is open) until it ends. */
    MenuTimingSim run() {
        run = new ContainerRun(MENU, this).answersFast(sw.waitsForAnswers());
        if (hopperEveryMs > 0) {
            for (long ms = hopperEveryMs; ms < 60_000; ms += hopperEveryMs) {
                arrive(ms, () -> {
                    answers.slot(MENU, hopperSlot, false);
                    if (hopperOnGateSlot && gateStepSlot() >= 0) answers.slot(MENU, gateStepSlot(), true);
                });
            }
        }
        for (long t = 1; t <= 600; t++) {
            now = t;
            nowMs = t * tickMs;
            while (!arrivals.isEmpty() && arrivals.peek().ms() <= nowMs) arrivals.poll().deliver().run();
            status = run.tick(t);
            inventoryManager(t);
            if (status != TaskStatus.RUNNING) {
                endTick = t;
                return this;
            }
        }
        return this;
    }

    private int gateStepSlot() {
        for (Action a : actions) if (a.gate()) return steps.get(a.step()).slot();
        return -1;
    }

    private void arrive(long ms, Runnable r) {
        arrivals.add(new Arrival(ms, seq++, r));
    }

    // ------------------------------------------------------------------------- ZenithProxy's InventoryManager

    private void inventoryManager(long t) {
        if (pacer != null) {
            if (queued != null) {
                execute(queued, t);
                queued = null;
            }
            return;
        }
        if (imNext < 0 || imNext >= actions.size()) return;
        if (t - imLast > delay) { // SyncedTickTimer.tick(delay)
            imLast = t;
            execute(imNext++, t);
        }
    }

    private void execute(int i, long t) {
        if (!open) return; // the menu is gone: ZenithProxy skips the action
        Action a = actions.get(i);
        switch (a.act()) {
            case WAIT, GATE -> { }
            case SNAPSHOT -> snapshotTick = t;
            case FIXUP -> {
                // ContainerTransferTask.fixup: held = the action runs again at the next action tick
                if (guard != null && guard.holdFixup(t, cache.cursor() == null, answers.answered(gateToken))) {
                    imNext = i;
                    return;
                }
                fixups.add(t);
                if (cache.cursor() != null && !cache.emptyPlayerSlots().isEmpty()) {
                    fixupClicks.add(t);
                    click(new Step(Kind.LEFT, cache.emptyPlayerSlots().getFirst()), t, false, false);
                }
            }
            case CLICK -> {
                Step s = steps.get(a.step());
                if (guard != null && s.kind() == Kind.QUICK_MOVE) { // ContainerTransferTask.guarded
                    switch (guard.shiftDue(i, t, cache.cursor() == null, answers.answered(gateToken))) {
                        case HOLD -> {
                            imNext = i;
                            return;
                        }
                        case GIVE_UP -> {
                            return;
                        }
                        case SEND -> { }
                    }
                    if (cache.cursor() != null) {
                        refusals.add(t);
                        if (guard.refused(i, t)) imNext = i;
                        return;
                    }
                } else if (s.kind() == Kind.QUICK_MOVE && cache.cursor() != null) {
                    refusals.add(t);
                }
                click(s, t, a.gate(), a.last());
            }
        }
    }

    private void click(Step s, long t, boolean gate, boolean last) {
        if (s.kind() == Kind.QUICK_MOVE) {
            if (cache.cursor() != null || cache.at(s.slot()) == null) return; // ShiftClick sends nothing
        } else {
            int before = cache.unsent;
            cache.click(s);
            if (cache.unsent != before) return;
        }
        truth.click(s);
        if (actionsTick != t) {
            actionsTick = t;
            actionsThisTick = 0;
        }
        maxActionsInATick = Math.max(maxActionsInATick, ++actionsThisTick);
        sent.add(new Sent(t, t * tickMs, s.kind() + "@" + s.slot()));
        if (waitLog != null) {
            if (gate) waitLog.gateClick(t);
            if (last) waitLog.lastClick(t);
            if (gateFrom >= 0 && s.kind() == Kind.QUICK_MOVE && steps.indexOf(s) == firstShiftStep) waitLog.firstShift(t);
        }
        if (gate || last) {
            long token = s.kind() == Kind.QUICK_MOVE ? answers.sent(MENU, Map.of(s.slot(), true), null)
                : answers.sent(MENU, Map.of(s.slot(), cache.at(s.slot()) == null), cache.cursor() == null);
            if (gate) gateToken = token;
            if (last) lastToken = token;
            if (gate && guard != null) guard.gateClick(t, Boolean.TRUE); // the plan's cursor, as ContainerTransferTask
            if (gate && gateSlotFilledAtMs > 0) arrive(gateSlotFilledAtMs, () -> answers.slot(MENU, s.slot(), false));
        }
        VanillaClickModel state = truth.copy();
        if (staleStep >= 0 && s.kind() != Kind.QUICK_MOVE && steps.indexOf(s) == staleStep) {
            arrive(staleAtMs, () -> {
                cache.resync(state);
                answers.content(MENU, emptiness(state), state.cursor() == null);
            });
            if (staleFixedAtMs > 0) {
                arrive(staleFixedAtMs, () -> {
                    VanillaClickModel current = truth.copy();
                    cache.resync(current);
                    if (staleFixSeen) answers.content(MENU, emptiness(current), current.cursor() == null);
                });
            }
        }
        Answer kind = s.kind() == Kind.QUICK_MOVE ? Answer.CONTENT : cursorAnswer;
        boolean lastShift = s.kind() == Kind.QUICK_MOVE && steps.indexOf(s) == lastShiftStep;
        long at = t * tickMs + latencyMs;
        arrive(at, () -> {
            // ZenithProxy's cache takes the packet first; ClickAnswers sees it behind that (two hops on the client loop)
            switch (kind) {
                case SLOT -> {
                    cache.correct(s.slot(), state);
                    answers.slot(MENU, s.slot(), state.at(s.slot()) == null);
                }
                case CURSOR -> {
                    cache.correctCursor(state);
                    answers.cursor(state.cursor() == null);
                }
                case CONTENT -> {
                    cache.resync(state);
                    boolean[] e = emptiness(state);
                    if (lastShift && hopperRefillsShiftSlot) e[s.slot()] = false;
                    answers.content(MENU, e, state.cursor() == null);
                }
                case NONE -> { }
            }
            if (lastShift) lastShiftAnsweredMs = Math.min(lastShiftAnsweredMs, at);
        });
    }

    /** Per slot of the menu whether {@code state} shows it empty (a full-contents packet). */
    private static boolean[] emptiness(VanillaClickModel state) {
        boolean[] e = new boolean[state.top + 36];
        for (int k = 0; k < e.length; k++) e[k] = state.at(k) == null;
        return e;
    }

    // ---------------------------------------------------------------------------------- ContainerRun.Io

    @Override
    public int openMenuId() {
        return open ? MENU : 0;
    }

    @Override
    public void holdStill() { }

    @Override
    public InputGate.@Nullable Sent previousInput(long tick) {
        return new InputGate.Sent(tick - 1, false, InputGate.Outcome.DROPPED, 0, 0, 0);
    }

    @Override public double x() { return 0; }
    @Override public double y() { return 0; }
    @Override public double z() { return 0; }

    @Override
    public boolean inventoryBusy() {
        return false;
    }

    /** As ContainerTransferTask.actions and ContainerTask.MenuIo.submitClicks build and submit the request. */
    @Override
    public int submitClicks(int menuId) {
        if (!open || menuId != MENU || steps.isEmpty()) return 0;
        boolean waits = sw.waitsForAnswers();
        Script s = MenuClicks.withdraw(steps, empty, delay, ack, waits,
            MenuClicks.ticksPerAction(delay, sw.clicksEveryTick()));
        actions.clear();
        cursorClicks = 0;
        lastShiftStep = -1;
        firstShiftStep = -1;
        waitLog = waits ? new MenuClicks.Waits() : null;
        for (int k = 0; k < s.ops().size(); k++) {
            Op op = s.ops().get(k);
            switch (op) {
                case WAIT -> actions.add(new Action(Act.WAIT, -1, false, false));
                case GATE -> actions.add(new Action(Act.GATE, -1, false, false));
                case FIXUP -> actions.add(new Action(Act.FIXUP, -1, false, false));
                case CLICK -> {
                    int step = s.stepOf().get(k);
                    if (steps.get(step).kind() != Kind.QUICK_MOVE) {
                        cursorClicks++;
                    } else {
                        lastShiftStep = step;
                        if (firstShiftStep < 0) firstShiftStep = step;
                    }
                    actions.add(new Action(Act.CLICK, step, k == s.lastCursor() && s.gateFrom() >= 0,
                        waits && k == s.lastClick()));
                }
            }
        }
        gateFrom = s.gateFrom();
        gateEnd = s.gateEnd();
        guard = waits && gateFrom >= 0 && !sw.clicksEveryTick() ? new MenuClicks.CursorGuard(shiftAnswerTicks) : null;
        if (answerWait() <= 0) {
            int trailing = 0;
            for (int i = actions.size() - 1; i >= 0 && actions.get(i).act() == Act.WAIT; i--) trailing++;
            for (int i = TransferPlanner.tailWaits(trailing, delay, ack); i > 0; i--) {
                actions.add(new Action(Act.WAIT, -1, false, false));
            }
            actions.add(new Action(Act.SNAPSHOT, -1, false, false));
        }
        if (sw.clicksEveryTick()) {
            boolean[] w = new boolean[actions.size()];
            for (int i = 0; i < w.length; i++) w[i] = actions.get(i).act() == Act.WAIT || actions.get(i).act() == Act.GATE;
            pacer = new MenuClicks.Pacer(w, gateFrom, gateEnd, delay, rate);
            paced(now);
            return actions.size() * Math.max(1, delay);
        }
        imNext = 0;
        return actions.size() * (waits ? MenuClicks.pitch(delay) : delay);
    }

    private void paced(long tick) {
        MenuClicks.Pacer p = pacer;
        if (p == null || p.finished()) return;
        int i = p.due(tick, nowMs * 1_000_000L, answers.answered(gateToken), cache.cursor() == null, true);
        if (p.skipped() && waitLog != null) waitLog.gateSkipped();
        if (i < 0) return;
        p.sent(tick, nowMs * 1_000_000L);
        queued = i;
    }

    @Override
    public void duringRequest(long tick) {
        if (pacer != null) {
            paced(tick);
            return;
        }
        if (imNext < 0 || imNext >= actions.size() || gateFrom < 0) return;
        int to = MenuClicks.skipTo(imNext, gateFrom, gateEnd, answers.answered(gateToken), cache.cursor() == null);
        if (to >= 0) {
            imNext = to;
            if (waitLog != null) waitLog.gateSkipped();
        }
    }

    @Override
    public @Nullable Boolean clicksDone() {
        if (pacer != null) return pacer.finished() && queued == null ? Boolean.TRUE : null;
        return imNext >= actions.size() ? Boolean.TRUE : null;
    }

    @Override
    public void closeMenu() {
        if (waitLog != null) waitLog.closed(now, answers.answered(lastToken), lastShiftStep >= 0 && movesSeen());
        open = false;
        closeTick = now;
    }

    @Override
    public int answerWait() {
        return MenuClicks.answerWait(sw, true, cursorClicks, lastShiftStep >= 0, true, ack, shiftAnswerTicks);
    }

    @Override
    public boolean answersIn() {
        return MenuClicks.answersIn(sw, cursorClicks, lastShiftStep >= 0, answers.answered(lastToken), movesSeen());
    }

    /** The plan's moves show in the player inventory: a shift-click's only with the server's contents. */
    private boolean movesSeen() {
        return lastShiftStep < 0 || lastShiftAnsweredMs <= nowMs;
    }

    @Override
    public boolean settleFast() {
        return MenuClicks.settleFast(sw, answersIn(), cache.cursor() == null);
    }

    // ---------------------------------------------------------------------------------------------- results

    List<Long> clickTicks() {
        List<Long> out = new ArrayList<>();
        for (Sent s : sent) out.add(s.tick());
        return out;
    }

    /** Tick of the first click of {@code kind} after tick {@code after}, -1 if none. */
    long firstAfter(Kind kind, long after) {
        for (Sent s : sent) if (s.tick() > after && s.what().startsWith(kind.name())) return s.tick();
        return -1;
    }

    /** Tick of the last cursor click. */
    long lastCursorTick() {
        long t = -1;
        for (Sent s : sent) if (!s.what().startsWith(Kind.QUICK_MOVE.name())) t = s.tick();
        return t;
    }

    long lastClickTick() {
        return sent.isEmpty() ? -1 : sent.getLast().tick();
    }

    /** Ticks from the last cursor click to the first shift-click after it. */
    long mid() {
        long c = lastCursorTick();
        return firstAfter(Kind.QUICK_MOVE, c) - c;
    }

    /** Ticks from the last click to the close. */
    long tail() {
        return closeTick - lastClickTick();
    }

    long ignored() {
        return answers.ignored();
    }

    /** The log line's suffix at the end, as ContainerTransferTask writes it ("" with the answer waits off). */
    String waitsText() {
        MenuClicks.Waits w = waitLog;
        return w == null ? "" : w.text(gateFrom >= 0, answers.answered(gateToken), answers.answered(lastToken), guard);
    }
}
