package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.WithdrawMode;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Kind;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Step;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Container clicks in real ticks. Pure: {@link ContainerTask} and
 * {@link ContainerTransferTask} run it on ZenithProxy, {@code MenuTimingTest} on a simulated InventoryManager and server.
 *
 * <p><b>Real ticks.</b> ZenithProxy 3.7.2's InventoryManager runs a request's next action only when
 * {@code SyncedTickTimer.tick(delay)} holds, which is {@code tickTime - last > delay}: at {@code actionDelayTicks} d above
 * 0 one action every d + 1 ticks ({@link #pitch}), at 0 every action of the request in the tick it was submitted. The
 * waits of {@link TransferPlanner#waitsAfter} and {@link TransferPlanner#tailWaits} count actions as if each took d
 * ticks: {@code build.containerAckTicks} 8 at {@code build.containerClickTicks} 1 is 8 actions, 16 ticks to the next
 * action and 18 to the shift-click or the close (live 2026-10-24 replay: 902 ms on the wire).
 *
 * <p><b>Answer gate</b> ({@code build.containerAnswerWaits}). The waits between the last cursor click and the first
 * shift-click are {@link #gateWaits} actions, enough for {@code containerAckTicks} real ticks, and end early: once the
 * server's answer to that click is in ({@link ClickAnswers}), the request goes on with the cursor fix-up, or with the
 * first shift-click when the cursor is empty ({@link #skipTo}). The fix-up holds while the cache shows a stale cursor,
 * and a refused shift-click goes again ({@link CursorGuard}). The wait after the last click is not in the request at
 * all: {@link ContainerRun} waits for its answer (step ANSWERS) and closes on the tick it sees it; after a shift-click
 * also on the moves showing, at most {@code build.containerShiftAnswerTicks} real ticks ({@link #answerWait}).
 *
 * <p><b>One click per tick</b> ({@code build.containerClickEveryTick}, {@link Pacer}): each action goes as its own
 * one-action request with delay 0, one every {@code containerClickTicks} ticks, at most one a tick, only in a tick whose
 * input pressed no key, and at most {@link #RATE_CLICKS} in any {@link #RATE_MILLIS} ms ({@link Rate}).
 */
public final class MenuClicks {
    private MenuClicks() { }

    /** {@code build.containerClickEveryTick}: at most this many container actions ... */
    public static final int RATE_CLICKS = 6;
    /** ... in any window of this many milliseconds (Paper's limit is 9 in 300 ms). */
    public static final long RATE_MILLIS = 300;

    /** One action of a withdrawal's request, before {@link ContainerTask}'s tail. */
    public enum Op {
        /** A click of the plan ({@link Script#stepOf}). */
        CLICK,
        /** A plain wait ({@link TransferPlanner#waitsAfter}). */
        WAIT,
        /** A wait of the answer gate: skipped once the gate's click was answered. */
        GATE,
        /** The cursor fix-up before the first shift-click (an item the cache still shows on the cursor goes into an empty slot). */
        FIXUP
    }

    /**
     * The menu switches of one container use, from the config.
     *
     * @param fastClicks  {@code build.containerClickTicks} above 0
     * @param ncpStrict   ZenithProxy's {@code client.inventory.ncpStrict} (it closes the menu after every request)
     * @param answerEarly {@code build.containerAnswerEarly}
     * @param answerWaits {@code build.containerAnswerWaits}
     * @param settleFast  {@code build.containerSettleFast}
     * @param everyTick   {@code build.containerClickEveryTick}
     */
    public record Switches(boolean fastClicks, boolean ncpStrict, boolean answerEarly, boolean answerWaits,
                           boolean settleFast, boolean everyTick) {
        /** Live today: fast clicks at ZenithProxy's cadence, answer-early, no new switch. */
        public static Switches before(boolean answerEarly) {
            return new Switches(true, false, answerEarly, false, false, false);
        }

        /** The answer waits in effect: fast clicks, and ZenithProxy does not close the menu after the request. */
        public boolean waitsForAnswers() {
            return answerWaits && fastClicks && !ncpStrict;
        }

        /** One click per tick in effect: fast clicks; never with ncpStrict (ZenithProxy closes after every request). */
        public boolean clicksEveryTick() {
            return everyTick && fastClicks && !ncpStrict;
        }
    }

    /**
     * Ticks one action of a request takes on the wire at {@code actionDelayTicks} {@code delay} (1 or more): delay + 1
     * (ZenithProxy 3.7.2's {@code SyncedTickTimer}: {@code tickTime - last > delay}).
     */
    public static int pitch(int delay) {
        return Math.max(1, delay) + 1;
    }

    /** Ticks per action: {@link #pitch} in one request, {@code delay} with one-action requests ({@link Pacer}). */
    public static int ticksPerAction(int delay, boolean everyTick) {
        return everyTick ? Math.max(1, delay) : pitch(delay);
    }

    /** Waits after a click so that the next action runs {@code ackTicks} real ticks after it, at that many ticks per action. */
    public static int gateWaits(int ackTicks, int ticksPerAction) {
        int t = Math.max(1, ticksPerAction);
        return Math.max(0, Math.floorDiv(Math.max(1, ackTicks) + t - 1, t) - 1);
    }

    /**
     * A withdrawal's request as ops, before the tail {@link ContainerTask} adds.
     *
     * @param ops        the actions in order
     * @param stepOf     per op the index into the plan's steps of its click, -1 for the others
     * @param gateFrom   index of the gate's first wait, -1 without a gate
     * @param gateEnd    index of the action after the gate (the cursor fix-up), -1 without a gate
     * @param lastCursor index of the last cursor click (the gate's click), -1 without cursor clicks
     * @param lastClick  index of the last click, -1 without clicks
     */
    public record Script(List<Op> ops, List<Integer> stepOf, int gateFrom, int gateEnd, int lastCursor, int lastClick) {
        public Script {
            ops = List.copyOf(ops);
            stepOf = List.copyOf(stepOf);
        }
    }

    /**
     * The ops of a withdrawal of {@code steps}. Without {@code answerWaits}, exactly the request as before: the plan's
     * clicks with the waits of {@link TransferPlanner#waitsAfter} ({@code delay}, {@code ackTicks}), the cursor fix-up
     * before the first shift-click that follows another click. With it: after the last cursor click, when shift-clicks
     * follow, {@link #gateWaits} gate waits ({@code ackTicks} real ticks at {@code ticksPerAction}) instead; when none
     * follow, nothing ({@link ContainerRun} waits for the answer). The other waits (a right click onto an empty slot)
     * stay as before.
     */
    public static Script withdraw(List<Step> steps, Collection<Integer> emptyPlayerSlots, int delay, int ackTicks,
                                  boolean answerWaits, int ticksPerAction) {
        int[] waits = TransferPlanner.waitsAfter(steps, emptyPlayerSlots, delay, ackTicks);
        int firstShift = -1;
        int lastCursorStep = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).kind() == Kind.QUICK_MOVE) {
                if (firstShift < 0) firstShift = i;
            } else {
                lastCursorStep = i;
            }
        }
        List<Op> ops = new ArrayList<>();
        List<Integer> stepOf = new ArrayList<>();
        int gateFrom = -1;
        int gateEnd = -1;
        int lastCursor = -1;
        int lastClick = -1;
        boolean gate = answerWaits && lastCursorStep >= 0 && firstShift > lastCursorStep;
        for (int i = 0; i < steps.size(); i++) {
            if (i == firstShift && i > 0) {
                if (gate) gateEnd = ops.size();
                ops.add(Op.FIXUP);
                stepOf.add(-1);
            }
            lastClick = ops.size();
            if (i == lastCursorStep) lastCursor = ops.size();
            ops.add(Op.CLICK);
            stepOf.add(i);
            if (answerWaits && i == lastCursorStep) {
                if (gate) {
                    gateFrom = ops.size();
                    for (int k = gateWaits(ackTicks, ticksPerAction); k > 0; k--) {
                        ops.add(Op.GATE);
                        stepOf.add(-1);
                    }
                }
                continue; // without shift-clicks after it: ContainerRun waits for its answer
            }
            for (int k = 0; k < waits[i]; k++) {
                ops.add(Op.WAIT);
                stepOf.add(-1);
            }
        }
        return new Script(ops, stepOf, gateFrom, gateEnd, lastCursor, lastClick);
    }

    /**
     * Where the request goes on once the gate's click was answered: {@code next} (the action ZenithProxy runs next) is
     * in the gate or at the fix-up after it, and the answer is in: the fix-up, or the action after it when the cache
     * shows nothing on the cursor (the fix-up would click nothing). -1 = leave it.
     */
    public static int skipTo(int next, int gateFrom, int gateEnd, boolean answered, boolean cursorEmpty) {
        if (!answered || gateFrom < 0 || gateEnd < 0 || next < gateFrom || next > gateEnd) return -1;
        int to = cursorEmpty ? gateEnd + 1 : gateEnd;
        return to > next ? to : -1;
    }

    /**
     * {@code build.containerAnswerWaits}, {@code build.containerAnswerEarly}: ticks {@link ContainerRun} may wait for the
     * answers after the request (0 = the request ends with its fixed tail); the run sees the request done a tick after
     * its last click, hence the {@code - 1}. Without the answer waits: shift-click-only transfers with answer-early,
     * {@code ackTicks - 1}, as before. With them: a transfer whose last click is a shift-click waits up to
     * {@code shiftAnswerTicks} real ticks (ZenithProxy's ShiftClick predicts nothing and the server always answers it;
     * a close before the answer counts its moves short: 46 of 46 at 10 ms each way); one whose last click
     * is a cursor click {@code ackTicks} (Paper answers a right prediction with nothing, the cap only waits for a late
     * correction).
     *
     * @param transfer         a withdrawal or a deposit
     * @param cursorClicks     cursor clicks planned
     * @param lastIsShift      the request's last click is a shift-click
     * @param movesExpected    the plan moves something
     * @param shiftAnswerTicks {@code build.containerShiftAnswerTicks}
     */
    public static int answerWait(Switches s, boolean transfer, int cursorClicks, boolean lastIsShift,
                                 boolean movesExpected, int ackTicks, int shiftAnswerTicks) {
        if (!s.fastClicks() || s.ncpStrict() || !transfer || !movesExpected) return 0;
        if (s.waitsForAnswers() && lastIsShift) return Math.max(1, shiftAnswerTicks - 1);
        boolean wait = cursorClicks > 0 ? s.waitsForAnswers() : s.answerEarly();
        return wait ? Math.max(1, ackTicks - 1) : 0;
    }

    /**
     * The answers to the clicks are in. With the answer waits: the last click's answer ({@link ClickAnswers}), or, when
     * the last click is a shift-click, also the plan's moves showing in full in the inventory (ZenithProxy's ShiftClick
     * predicts nothing, so they show only with the server's resync; a cursor click's prediction shows at once and is no
     * answer). Else the moves showing, as before.
     */
    public static boolean answersIn(Switches s, int cursorClicks, boolean lastIsShift, boolean lastClickAnswered,
                                    boolean movesSeen) {
        if (s.waitsForAnswers()) {
            if (lastClickAnswered) return true;
            if (cursorClicks > 0 && !lastIsShift) return false; // the cache shows the cursor clicks' predictions at once
        }
        return movesSeen;
    }

    /** {@code build.containerSettleFast}: the settle ticks are skipped when the answers are in and the cursor is empty. */
    public static boolean settleFast(Switches s, boolean answersIn, boolean cursorEmpty) {
        return s.settleFast() && answersIn && cursorEmpty;
    }

    /**
     * {@code build.containerClickEveryTick}: a request's actions one at a time, one action every {@code every} ticks
     * (a wait takes its tick and sends nothing), the answer gate as in a request ({@link #skipTo}). An action is due
     * only in a tick that may click (the input of the tick before pressed no key; no other request runs) and while
     * the {@link Rate} allows it. Ask {@link #due} once per tick and report what went out with {@link #sent}.
     */
    public static final class Pacer {
        private final boolean[] waits;
        private final int gateFrom;
        private final int gateEnd;
        private final int every;
        private final Rate rate;
        private int next;
        private long last = Long.MIN_VALUE;
        private boolean skipped;

        /**
         * @param waits    per action of the request: a wait (sends nothing, takes its tick)
         * @param gateFrom {@link Script#gateFrom} (-1: no gate)
         * @param gateEnd  {@link Script#gateEnd}
         * @param every    ticks per action (build.containerClickTicks)
         */
        public Pacer(boolean[] waits, int gateFrom, int gateEnd, int every, Rate rate) {
            this.waits = waits.clone();
            this.gateFrom = gateFrom;
            this.gateEnd = gateEnd;
            this.every = Math.max(1, every);
            this.rate = rate;
        }

        /**
         * The action to submit in tick {@code tick} ({@code nanos} on the wall clock), or -1. Waits that are due are
         * taken here; the gate is skipped once {@code answered}.
         */
        public int due(long tick, long nanos, boolean answered, boolean cursorEmpty, boolean mayClick) {
            int to = skipTo(next, gateFrom, gateEnd, answered, cursorEmpty);
            if (to >= 0) {
                next = to;
                skipped = true;
            }
            if (next >= waits.length) return -1;
            if (last != Long.MIN_VALUE && tick - last < every) return -1;
            if (waits[next]) {
                next++;
                last = tick;
                return -1;
            }
            if (!mayClick || !rate.allows(nanos)) return -1;
            return next;
        }

        /** The action {@link #due} named went out in tick {@code tick}. */
        public void sent(long tick, long nanos) {
            rate.add(nanos);
            last = tick;
            next++;
        }

        /** Every action of the request went out (or was a wait). */
        public boolean finished() {
            return next >= waits.length;
        }

        /** Index of the next action. */
        public int next() {
            return next;
        }

        /** The answer moved it past (part of) the gate ({@link Waits#gateSkipped}). */
        public boolean skipped() {
            return skipped;
        }
    }

    /**
     * The per-chest log line of a withdrawal with cursor clicks, as it was: {@code "<name>: <moved> with N cursor
     * clicks (<mode>) and M shift-clicks"}, then {@code waits} ({@link Waits#text}; "" with the answer waits off, so the
     * line stays as before).
     */
    public static String withdrawLine(String name, Map<String, Integer> moved, int cursorClicks, WithdrawMode mode,
                                      int shiftClicks, String waits) {
        return name + ": " + moved + " with " + cursorClicks + " cursor click" + (cursorClicks == 1 ? "" : "s")
            + " (" + mode + ") and " + shiftClicks + " shift-click" + (shiftClicks == 1 ? "" : "s") + waits;
    }

    /**
     * How the answer waits of one withdrawal ended ({@code build.containerAnswerWaits}), for its log line, in real
     * ticks (the task's ticks): the mid wait from the gate's click (the last cursor click) to the first shift-click,
     * the tail from the last click to the close. Each ended on the answer ({@code answered}: the gate was skipped, the
     * close saw the last click's answer), a tail after a shift-click also on the plan's moves showing in the inventory
     * before the answer was seen ({@code moves}), or on its cap ({@code cap}: the answer came later, before the task
     * ended; {@code cap (no answer)}: not even then). Fed while the request runs, read once at the end.
     */
    public static final class Waits {
        private long gateClick = -1;
        private long firstShift = -1;
        private long lastClick = -1;
        private long close = -1;
        private boolean skipped;
        private boolean closedOnAnswer;
        private boolean closedOnMoves;

        /** The gate's click went out in tick {@code tick}. */
        public void gateClick(long tick) {
            gateClick = tick;
        }

        /** The answer moved the request past (part of) the gate. */
        public void gateSkipped() {
            skipped = true;
        }

        /** The first shift-click after the gate went out in tick {@code tick}. */
        public void firstShift(long tick) {
            if (firstShift < 0) firstShift = tick;
        }

        /** The last click went out in tick {@code tick}. */
        public void lastClick(long tick) {
            lastClick = tick;
        }

        /** Our close went out in tick {@code tick}; {@code lastAnswered}: the last click's answer was in. */
        public void closed(long tick, boolean lastAnswered) {
            closed(tick, lastAnswered, false);
        }

        /**
         * Our close went out in tick {@code tick}; {@code lastAnswered}: the last click's answer was in;
         * {@code movesSeen}: the last click is a shift-click and the plan's moves showed in the inventory.
         */
        public void closed(long tick, boolean lastAnswered, boolean movesSeen) {
            if (close >= 0) return;
            close = tick;
            closedOnAnswer = lastAnswered;
            closedOnMoves = !lastAnswered && movesSeen;
        }

        /**
         * {@code "; waits: mid answered 2 t, tail cap 8 t"}: the mid wait only with a gate ({@code gate}); {@code n/a}
         * for a wait that did not run to its end (a click or the close never went out).
         *
         * @param gateAnswered the gate's click is answered by now
         * @param lastAnswered the last click is answered by now
         */
        public String text(boolean gate, boolean gateAnswered, boolean lastAnswered) {
            return text(gate, gateAnswered, lastAnswered, null);
        }

        /**
         * As {@link #text(boolean, boolean, boolean)}, with the {@link CursorGuard}'s account when one ran: a held fix-up
         * after the mid part ({@code ", held 3 t (stale cursor, answered)"}), re-sent or refused shift-clicks at the end
         * ({@code ", shift retried 1"}). Nothing more when it did nothing.
         */
        public String text(boolean gate, boolean gateAnswered, boolean lastAnswered, @Nullable CursorGuard guard) {
            String tail = part(lastClick, close, closedOnAnswer, closedOnMoves, lastAnswered);
            String end = guard == null ? "" : guard.retryText();
            if (!gate) return "; waits: tail " + tail + end;
            return "; waits: mid " + part(gateClick, firstShift, skipped, false, gateAnswered)
                + (guard == null ? "" : guard.heldText()) + ", tail " + tail + end;
        }

        private static String part(long from, long to, boolean onAnswer, boolean onMoves, boolean answeredByNow) {
            if (from < 0 || to < from) return "n/a";
            long t = to - from;
            if (onAnswer) return "answered " + t + " t";
            if (onMoves) return "moves " + t + " t";
            return "cap " + t + " t" + (answeredByNow ? "" : " (no answer)");
        }
    }

    /** {@code build.containerAnswerWaits}: re-sends of one shift-click ZenithProxy refused for the cache's cursor. */
    public static final int SHIFT_RETRIES = 2;

    /**
     * The cursor between a withdrawal's cursor clicks and its shift-clicks ({@code build.containerAnswerWaits}, a
     * withdrawal with an answer gate, not with {@code build.containerClickEveryTick}). At 10 ms each way: at
     * the fix-up the cache showed the remainder of an earlier cursor click on the cursor (8 of 28 chests), while the
     * server's was empty. That is the server's full state for a click that carried an old state id, arriving after the
     * later clicks' predictions. The later clicks carried old state ids too, so the full state after the gate's click
     * follows and corrects the cache.
     * <ul>
     *   <li>The fix-up holds while the gate's click left the cursor empty and the cache shows items on it, until the
     *       cache shows the cursor empty again, at most {@code build.containerShiftAnswerTicks} real ticks after the
     *       gate's click. At the cap it clicks what the cache shows, as before. The full state after the gate's click
     *       shows the cursor empty; a {@link ClickAnswers} answer that leaves it stale is some other packet. The task
     *       passes the plan's cursor (every cursor sequence leaves it empty, {@link TransferPlanner}), not
     *       ZenithProxy's prediction: that one comes from the cache, which a stale state may reach before the gate's
     *       click goes out.
     *       A remainder predicted on the cursor never holds it: Paper answers a right prediction with nothing, so a
     *       hold would always run to the cap.</li>
     *   <li>A shift-click ZenithProxy refused ("Can't shift click, mouse stack is not empty") goes again once the cache
     *       shows the cursor empty, at most {@link #SHIFT_RETRIES} times, within the same budget. After that it stays
     *       refused and the withdrawal counts short, as before.</li>
     * </ul>
     * In the task's ticks. The request's actions feed it while they run, and the log line reads it at the end.
     */
    public static final class CursorGuard {
        /** What a shift-click does when it is due. */
        public enum Shift {
            /** Go (a refused one: its re-send). */
            SEND,
            /** A refused one waits for the cache's cursor to empty: run it again at the next action tick. */
            HOLD,
            /** A refused one gives up: the budget is spent. */
            GIVE_UP
        }

        private final int budget;
        private long gateClick = -1;
        private @Nullable Boolean gateCursorEmpty;
        private long holdFrom = -1;
        private long holdTo = -1;
        private String holdEnd = "";
        private int refusedAction = -1;
        private int retries;
        private int givenUp;

        /** @param shiftAnswerTicks {@code build.containerShiftAnswerTicks}: the budget, from the gate's click */
        public CursorGuard(int shiftAnswerTicks) {
            this.budget = Math.max(0, shiftAnswerTicks);
        }

        /** The gate's click went out in tick {@code tick}, predicting the cursor empty after it or not (null: unknown). */
        public void gateClick(long tick, @Nullable Boolean cursorEmptyAfter) {
            gateClick = tick;
            gateCursorEmpty = cursorEmptyAfter;
        }

        private boolean inBudget(long tick) {
            return gateClick >= 0 && tick - gateClick < budget;
        }

        /** The cache contradicts the gate's click: it predicted an empty cursor, the cache shows items on it. */
        private boolean stale(boolean cacheCursorEmpty) {
            return Boolean.TRUE.equals(gateCursorEmpty) && !cacheCursorEmpty;
        }

        /** The fix-up is due in tick {@code tick}: true = hold it (it runs again at the request's next action tick). */
        public boolean holdFixup(long tick, boolean cacheCursorEmpty, boolean gateAnswered) {
            if (holdTo >= 0) return false; // one hold, over once it ended
            // the gate's answer alone does not end it: the full state after the gate's click shows its cursor empty, so
            // an answer that leaves the cache's cursor stale is another packet (5 of 8 stale cursors came
            // with the gate's click already counted answered)
            if (stale(cacheCursorEmpty) && inBudget(tick)) {
                if (holdFrom < 0) holdFrom = tick;
                return true;
            }
            endHold(tick, cacheCursorEmpty, gateAnswered);
            return false;
        }

        private void endHold(long tick, boolean cacheCursorEmpty, boolean gateAnswered) {
            if (holdFrom < 0 || holdTo >= 0) return;
            holdTo = tick;
            holdEnd = stale(cacheCursorEmpty) ? "cap" : gateAnswered ? "answered" : "matched";
        }

        /**
         * The shift-click at request action {@code index} is due in tick {@code tick}. It also ends a hold the gate's
         * answer cut short ({@link #skipTo} moves the request past a held fix-up once the cursor shows empty).
         */
        public Shift shiftDue(int index, long tick, boolean cacheCursorEmpty, boolean gateAnswered) {
            endHold(tick, cacheCursorEmpty, gateAnswered);
            if (refusedAction != index) return Shift.SEND;
            if (cacheCursorEmpty) {
                refusedAction = -1;
                retries++;
                return Shift.SEND;
            }
            if (inBudget(tick)) return Shift.HOLD;
            refusedAction = -1;
            givenUp++;
            return Shift.GIVE_UP;
        }

        /**
         * ZenithProxy refused the shift-click at request action {@code index} in tick {@code tick} for the cache's
         * cursor: true = it goes again ({@link #shiftDue} holds it until the cursor shows empty), false = it stays refused.
         */
        public boolean refused(int index, long tick) {
            if (retries < SHIFT_RETRIES && inBudget(tick)) {
                refusedAction = index;
                return true;
            }
            givenUp++;
            return false;
        }

        /** The fix-up held (for tests). */
        public boolean held() {
            return holdFrom >= 0;
        }

        /** Re-sent shift-clicks. */
        public int retries() {
            return retries;
        }

        /** Shift-clicks that stayed refused. */
        public int givenUp() {
            return givenUp;
        }

        /** {@code ", held 3 t (stale cursor, answered|matched|cap)"} when the fix-up held, else "". */
        public String heldText() {
            if (holdFrom < 0) return "";
            String t = holdTo >= 0 ? (holdTo - holdFrom) + " t" : "n/a";
            return ", held " + t + " (stale cursor" + (holdEnd.isEmpty() ? "" : ", " + holdEnd) + ")";
        }

        /** {@code ", shift retried 1"} (re-sends), {@code ", shift refused 1"} (stayed refused), else "". */
        public String retryText() {
            return (retries > 0 ? ", shift retried " + retries : "") + (givenUp > 0 ? ", shift refused " + givenUp : "");
        }
    }

    /** At most {@code max} actions in any window of {@code windowNanos} (wall clock: ticks can come closer than 50 ms). */
    public static final class Rate {
        private final long[] times;
        private final long windowNanos;
        private int count;
        private int head;

        public Rate(int max, long windowNanos) {
            this.times = new long[Math.max(1, max)];
            this.windowNanos = windowNanos;
        }

        /** {@link #RATE_CLICKS} in {@link #RATE_MILLIS}. */
        public static Rate containerClicks() {
            return new Rate(RATE_CLICKS, RATE_MILLIS * 1_000_000L);
        }

        /** Another action at {@code nanos} keeps the limit. */
        public synchronized boolean allows(long nanos) {
            return count < times.length || nanos - times[head] >= windowNanos;
        }

        public synchronized void add(long nanos) {
            if (count < times.length) {
                times[(head + count) % times.length] = nanos;
                count++;
            } else {
                times[head] = nanos;
                head = (head + 1) % times.length;
            }
        }
    }
}
