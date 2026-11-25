package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.WithdrawMode;
import io.github.digitalisp.mapart.bot.impl.MenuClicks.Op;
import io.github.digitalisp.mapart.bot.impl.MenuClicks.Script;
import io.github.digitalisp.mapart.bot.impl.MenuClicks.Switches;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Kind;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Step;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure parts of the menu timing ({@link MenuClicks}): real ticks
 * per action, the request's ops with the switches off exactly as before, the answer gate, the switch rules, the pacer
 * and the rate limit.
 */
class MenuClicksTest {
    private static final int TOP = 27;
    private static final String CARPET = "minecraft:white_carpet";
    private static final String LIME = "minecraft:lime_carpet";
    private static final String PANE = "minecraft:glass_pane";

    @Test
    void anActionTakesDelayPlusOneTicksInOneRequest() {
        assertEquals(2, MenuClicks.pitch(1), "SyncedTickTimer: tickTime - last > 1 holds every second tick");
        assertEquals(6, MenuClicks.pitch(5));
        assertEquals(2, MenuClicks.pitch(0), "a delay below 1 is 1");
        assertEquals(2, MenuClicks.ticksPerAction(1, false));
        assertEquals(1, MenuClicks.ticksPerAction(1, true), "one-action requests: one a tick");
        assertEquals(3, MenuClicks.ticksPerAction(3, true));
    }

    @Test
    void theGateIsTheAckTimeInRealTicks() {
        assertEquals(3, MenuClicks.gateWaits(8, 2), "3 waits and the next action: 8 ticks at 2 a action");
        assertEquals(7, MenuClicks.gateWaits(8, 1));
        assertEquals(0, MenuClicks.gateWaits(2, 2));
        for (int t = 1; t <= 6; t++) {
            for (int ack = 1; ack <= 12; ack++) {
                int w = MenuClicks.gateWaits(ack, t);
                assertTrue((w + 1) * t >= ack, "next action " + (w + 1) * t + " ticks after the click, ack " + ack);
                assertTrue(w == 0 || w * t < ack, "no wait more than needed");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the ops

    /** The request ContainerTransferTask built before the answer waits (the code of 44773bb, as FastClicksTest has it). */
    private static List<String> before(List<Step> steps, List<Integer> empty, int delay, int ack) {
        int[] waits = TransferPlanner.waitsAfter(steps, empty, delay, ack);
        int firstShift = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).kind() == Kind.QUICK_MOVE) {
                firstShift = i;
                break;
            }
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            if (i == firstShift && i > 0) out.add("FIXUP");
            out.add(steps.get(i).kind() + "@" + steps.get(i).slot());
            for (int k = 0; k < waits[i]; k++) out.add("WAIT");
        }
        return out;
    }

    private static List<String> names(Script s, List<Step> steps) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.ops().size(); i++) {
            Op op = s.ops().get(i);
            if (op == Op.CLICK) {
                Step st = steps.get(s.stepOf().get(i));
                out.add(st.kind() + "@" + st.slot());
            } else {
                out.add(op.name());
            }
        }
        return out;
    }

    private static Map<String, Integer> want(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    /** The withdrawals of FastClicksTest and more: top-ups, two partials, exact counts, whole stacks, top-up only. */
    static List<Object[]> plans() {
        List<Object[]> out = new ArrayList<>();
        out.add(new Object[]{"top-up + 2 stacks", new VanillaClickModel(TOP).put(60, CARPET, 10, 64)
            .put(0, CARPET, 64, 64).put(1, CARPET, 64, 64).put(2, CARPET, 64, 64), want(CARPET, 170), WithdrawMode.TOP_UP});
        out.add(new Object[]{"two partials topped up", new VanillaClickModel(TOP).put(60, LIME, 30, 64)
            .put(61, LIME, 20, 64).put(0, LIME, 64, 64).put(1, LIME, 64, 64).put(2, LIME, 64, 64), want(LIME, 70),
            WithdrawMode.TOP_UP});
        out.add(new Object[]{"exact 3 panes", new VanillaClickModel(TOP).put(4, PANE, 64, 64), want(PANE, 3),
            WithdrawMode.EXACT});
        out.add(new Object[]{"exact 3 panes + stacks", new VanillaClickModel(TOP).put(4, PANE, 64, 64)
            .put(0, CARPET, 64, 64).put(1, CARPET, 64, 64), want(PANE, 3, CARPET, 128), WithdrawMode.EXACT});
        out.add(new Object[]{"whole stacks", new VanillaClickModel(TOP).put(0, CARPET, 64, 64).put(1, CARPET, 64, 64)
            .put(2, CARPET, 40, 64), want(CARPET, 168), WithdrawMode.WHOLE_STACKS});
        out.add(new Object[]{"top-up only", new VanillaClickModel(TOP).put(60, CARPET, 10, 64).put(0, CARPET, 64, 64),
            want(CARPET, 54), WithdrawMode.TOP_UP});
        return out;
    }

    static TransferPlanner.Plan plan(Object[] c) {
        VanillaClickModel m = (VanillaClickModel) c[1];
        @SuppressWarnings("unchecked") Map<String, Integer> wanted = (Map<String, Integer>) c[2];
        return TransferPlanner.planWithdraw(m.containerStacks(), wanted, m.playerStacks(), m.playerFree(),
            (WithdrawMode) c[3], m.emptyPlayerSlots());
    }

    @Test
    void withTheAnswerWaitsOffTheRequestIsExactlyTheOneBefore() {
        for (Object[] c : plans()) {
            List<Step> steps = plan(c).steps();
            List<Integer> empty = ((VanillaClickModel) c[1]).emptyPlayerSlots();
            for (int delay = 1; delay <= 5; delay++) {
                for (int ack : new int[]{1, 3, 8, 12}) {
                    for (boolean everyTick : new boolean[]{false, true}) {
                        Script s = MenuClicks.withdraw(steps, empty, delay, ack, false,
                            MenuClicks.ticksPerAction(delay, everyTick));
                        String what = c[0] + ", delay " + delay + ", ack " + ack;
                        assertEquals(before(steps, empty, delay, ack), names(s, steps), what);
                        assertEquals(-1, s.gateFrom(), what + ": no gate");
                        assertEquals(-1, s.gateEnd(), what);
                    }
                }
            }
        }
    }

    @Test
    void theAnswerWaitsPutTheGateAfterTheLastCursorClick() {
        Object[] c = plans().getFirst(); // L0 L60 L0, then 2 shift-clicks
        List<Step> steps = plan(c).steps();
        List<Integer> empty = ((VanillaClickModel) c[1]).emptyPlayerSlots();
        Script s = MenuClicks.withdraw(steps, empty, 1, 8, true, MenuClicks.pitch(1));
        assertEquals(List.of("LEFT@0", "LEFT@60", "LEFT@0", "GATE", "GATE", "GATE", "FIXUP", "QUICK_MOVE@1",
            "QUICK_MOVE@2"), names(s, steps));
        assertEquals(3, s.gateFrom());
        assertEquals(6, s.gateEnd(), "the fix-up after the gate");
        assertEquals(2, s.lastCursor());
        assertEquals(8, s.lastClick());
        Script every = MenuClicks.withdraw(steps, empty, 1, 8, true, MenuClicks.ticksPerAction(1, true));
        assertEquals(7, every.gateEnd() - every.gateFrom(), "7 waits at one action a tick: 8 ticks to the fix-up");

        Object[] only = plans().getLast(); // a top-up and nothing after it
        List<Step> st = plan(only).steps();
        Script t = MenuClicks.withdraw(st, ((VanillaClickModel) only[1]).emptyPlayerSlots(), 1, 8, true, 2);
        assertEquals(List.of("LEFT@0", "LEFT@60", "LEFT@0"), names(t, st), "the run waits for the last click's answer");
        assertEquals(-1, t.gateFrom());
        assertEquals(2, t.lastClick());

        Object[] stacks = plans().get(4);
        List<Step> ss = plan(stacks).steps();
        Script w = MenuClicks.withdraw(ss, List.of(), 1, 8, true, 2);
        assertEquals(before(ss, List.of(), 1, 8), names(w, ss), "shift-clicks only: as before");
    }

    @Test
    void exactCountsKeepTheirWaitBetweenRightClicksOntoAnEmptySlot() {
        Object[] c = plans().get(3); // L4 R(e) R(e) R(e) L4, then 2 shift-clicks
        List<Step> steps = plan(c).steps();
        List<Integer> empty = ((VanillaClickModel) c[1]).emptyPlayerSlots();
        List<String> on = names(MenuClicks.withdraw(steps, empty, 1, 8, true, 2), steps);
        List<String> off = before(steps, empty, 1, 8);
        int firstRight = off.indexOf("RIGHT@" + steps.get(1).slot());
        assertEquals(off.subList(0, firstRight + 9), on.subList(0, firstRight + 9), "the 7 waits after the first right click");
        assertEquals(3, on.stream().filter("GATE"::equals).count());
    }

    @Test
    void theGateSkipsToTheShiftClickWhenTheCursorIsEmpty() {
        // gate 3..5, fix-up 6, first shift-click 7
        assertEquals(7, MenuClicks.skipTo(3, 3, 6, true, true));
        assertEquals(6, MenuClicks.skipTo(4, 3, 6, true, false), "an item on the cursor: the fix-up first");
        assertEquals(7, MenuClicks.skipTo(6, 3, 6, true, true), "gate over, the fix-up would click nothing");
        assertEquals(-1, MenuClicks.skipTo(6, 3, 6, true, false));
        assertEquals(-1, MenuClicks.skipTo(3, 3, 6, false, true), "no answer: the gate's waits run");
        assertEquals(-1, MenuClicks.skipTo(2, 3, 6, true, true), "before the gate (the click not yet out)");
        assertEquals(-1, MenuClicks.skipTo(7, 3, 6, true, true), "past it");
        assertEquals(-1, MenuClicks.skipTo(3, -1, -1, true, true), "no gate");
    }

    // ------------------------------------------------------------------------------------------- switch rules

    @Test
    void theSwitchRulesAreTheOldOnesWithTheNewSwitchesOff() {
        Switches off = Switches.before(true);
        assertEquals(7, MenuClicks.answerWait(off, true, 0, true, true, 8, 20), "shift-clicks only: answer-early as before");
        assertEquals(0, MenuClicks.answerWait(off, true, 3, false, true, 8, 20), "cursor clicks: the fixed wait");
        assertEquals(0, MenuClicks.answerWait(off, true, 3, true, true, 8, 20), "cursor + shift-clicks: the fixed wait");
        assertEquals(0, MenuClicks.answerWait(Switches.before(false), true, 0, true, true, 8, 20));
        assertEquals(0, MenuClicks.answerWait(off, false, 0, true, true, 8, 20), "not a withdrawal or deposit");
        assertEquals(0, MenuClicks.answerWait(off, true, 0, true, false, 8, 20), "nothing to move");
        assertEquals(0, MenuClicks.answerWait(new Switches(false, false, true, true, true, true), true, 0, true, true, 8,
            20), "no fast clicks");
        assertEquals(0, MenuClicks.answerWait(new Switches(true, true, true, true, false, false), true, 0, true, true, 8,
            20), "ncpStrict");
        assertTrue(MenuClicks.answersIn(off, 3, false, false, true), "the moves showing, as before");
        assertTrue(MenuClicks.answersIn(off, 3, true, false, true));
        assertFalse(MenuClicks.answersIn(off, 0, true, true, false), "off: an answer alone is not the moves");
        assertFalse(MenuClicks.settleFast(off, true, true));
        assertFalse(off.waitsForAnswers());
        assertFalse(off.clicksEveryTick());

        Switches on = new Switches(true, false, true, true, true, true);
        assertEquals(7, MenuClicks.answerWait(on, true, 3, false, true, 8, 20), "a last cursor click: the ack cap");
        assertEquals(19, MenuClicks.answerWait(on, true, 3, true, true, 8, 20),
            "a last shift-click: containerShiftAnswerTicks real ticks after it");
        assertEquals(19, MenuClicks.answerWait(on, true, 0, true, true, 8, 20), "shift-clicks only too");
        assertEquals(19, MenuClicks.answerWait(new Switches(true, false, false, true, false, false), true, 0, true, true,
            8, 20), "shift-clicks only without answer-early");
        assertEquals(11, MenuClicks.answerWait(on, true, 0, true, true, 4, 12));
        assertEquals(1, MenuClicks.answerWait(on, true, 0, true, true, 4, 0), "at least the tick after the request");
        assertFalse(MenuClicks.answersIn(on, 3, false, false, true), "a cursor click's prediction is no answer");
        assertTrue(MenuClicks.answersIn(on, 3, false, true, true));
        assertTrue(MenuClicks.answersIn(on, 3, true, false, true), "after a last shift-click the moves showing count");
        assertFalse(MenuClicks.answersIn(on, 3, true, false, false));
        assertTrue(MenuClicks.answersIn(on, 0, true, false, true), "shift-clicks: the moves showing still count");
        assertTrue(MenuClicks.answersIn(on, 0, true, true, false), "or the last one's answer");
        assertTrue(MenuClicks.settleFast(on, true, true));
        assertFalse(MenuClicks.settleFast(on, false, true), "answers not in: settle");
        assertFalse(MenuClicks.settleFast(on, true, false), "an item on the cursor goes back into the inventory: settle");
        assertFalse(new Switches(true, true, true, true, true, true).clicksEveryTick(), "never with ncpStrict");
        assertFalse(new Switches(true, true, true, true, true, true).waitsForAnswers());
    }

    // -------------------------------------------------------------------------------------- pacer and rate

    private static final long MS = 1_000_000L;

    @Test
    void thePacerSendsOneActionAPerTickAndTakesAWaitsTick() {
        // click, wait, click, click
        MenuClicks.Pacer p = new MenuClicks.Pacer(new boolean[]{false, true, false, false}, -1, -1, 1,
            MenuClicks.Rate.containerClicks());
        List<String> log = new ArrayList<>();
        for (long t = 1; t <= 8 && !p.finished(); t++) {
            int i = p.due(t, t * 50 * MS, false, true, t != 4); // tick 4: the input pressed a key
            if (i >= 0) {
                p.sent(t, t * 50 * MS);
                log.add(t + ":" + i);
            }
        }
        assertEquals(List.of("1:0", "3:2", "5:3"), log, "the wait takes tick 2; no click in tick 4 (keys)");
        assertTrue(p.finished());
    }

    @Test
    void thePacerSkipsAnAnsweredGate() {
        // click, gate, gate, gate, fix-up, shift-click
        boolean[] waits = {false, true, true, true, false, false};
        MenuClicks.Pacer p = new MenuClicks.Pacer(waits, 1, 4, 1, MenuClicks.Rate.containerClicks());
        assertEquals(0, p.due(1, 50 * MS, false, true, true));
        p.sent(1, 50 * MS);
        assertEquals(-1, p.due(2, 100 * MS, false, true, true), "tick 2: the gate's first wait");
        assertEquals(5, p.due(3, 150 * MS, true, true, true), "tick 3: answered, empty cursor: the shift-click");
        p.sent(3, 150 * MS);
        assertTrue(p.finished());
    }

    @Test
    void sixClicksIn300MsAtMost() {
        MenuClicks.Rate r = MenuClicks.Rate.containerClicks();
        List<Long> sent = new ArrayList<>();
        for (long ms = 0; ms < 2000; ms += 40) { // ticks 40 ms apart (the tick loop catching up)
            if (r.allows(ms * MS)) {
                r.add(ms * MS);
                sent.add(ms);
            }
        }
        for (int i = 6; i < sent.size(); i++) {
            assertTrue(sent.get(i) - sent.get(i - 6) >= 300, "7 clicks within 300 ms at " + sent.get(i));
        }
        MenuClicks.Rate fifty = MenuClicks.Rate.containerClicks();
        for (long ms = 0; ms < 1000; ms += 50) {
            assertTrue(fifty.allows(ms * MS), "one a 50 ms tick keeps the limit: " + ms);
            fifty.add(ms * MS);
        }
    }

    // ------------------------------------------------------------------------- the cursor guard

    private static MenuClicks.CursorGuard guard(int budget, Boolean gateCursorEmpty) {
        MenuClicks.CursorGuard g = new MenuClicks.CursorGuard(budget);
        g.gateClick(6, gateCursorEmpty); // the gate's click went out in tick 6
        return g;
    }

    @Test
    void theFixUpHoldsOnAStaleCursorUntilTheGatesAnswer() {
        MenuClicks.CursorGuard g = guard(20, true);
        assertTrue(g.holdFixup(10, false, false), "predicted empty, the cache shows items, unanswered: hold");
        assertTrue(g.holdFixup(12, false, false));
        assertFalse(g.holdFixup(14, true, true), "the gate's full state: answered, the cursor empty");
        assertEquals(", held 4 t (stale cursor, answered)", g.heldText());
        assertFalse(g.holdFixup(16, false, false), "one hold per withdrawal: over once ended");
        assertEquals(", held 4 t (stale cursor, answered)", g.heldText());
    }

    @Test
    void anAnswerThatLeavesTheCursorStaleDoesNotEndTheHold() {
        // the gate's click counted answered (a slot update matching its prediction) while the cache still
        // showed the remainder on the cursor; the full state after the gate's click shows it empty
        MenuClicks.CursorGuard g = guard(20, true);
        assertTrue(g.holdFixup(10, false, true), "answered, but the cursor is stale: hold");
        assertTrue(g.holdFixup(12, false, true));
        assertFalse(g.holdFixup(14, true, true));
        assertEquals(", held 4 t (stale cursor, answered)", g.heldText());
        MenuClicks.CursorGuard capped = guard(20, true);
        long t = 10;
        while (capped.holdFixup(t, false, true)) t += 2;
        assertEquals(26, t, "never corrected: the cap");
        assertEquals(", held 16 t (stale cursor, cap)", capped.heldText());
    }

    @Test
    void theFixUpHoldEndsWhenTheCacheCursorMatchesThePrediction() {
        MenuClicks.CursorGuard g = guard(20, true);
        assertTrue(g.holdFixup(10, false, false));
        assertFalse(g.holdFixup(12, true, false), "the cache shows the cursor empty again, the answer not seen");
        assertEquals(", held 2 t (stale cursor, matched)", g.heldText());
    }

    @Test
    void theFixUpHoldIsCappedAtTheBudgetFromTheGatesClick() {
        for (int budget : new int[]{20, 8}) {
            MenuClicks.CursorGuard g = guard(budget, true);
            long t = 10;
            while (g.holdFixup(t, false, false)) t += 2;
            assertEquals(6 + budget, t, "the first action tick at the budget, counted from the gate's click (tick 6)");
            assertEquals(", held " + (t - 10) + " t (stale cursor, cap)", g.heldText());
        }
        MenuClicks.CursorGuard none = guard(0, true);
        assertFalse(none.holdFixup(10, false, false), "no budget: the fix-up clicks as before");
        assertEquals("", none.heldText());
    }

    @Test
    void noHoldWhenTheGatesClickLeavesARemainderOrTheCursorIsEmpty() {
        MenuClicks.CursorGuard remainder = guard(20, false);
        assertFalse(remainder.holdFixup(10, false, false),
            "a remainder predicted on the cursor: no hold (Paper answers a right prediction with nothing)");
        MenuClicks.CursorGuard unknown = guard(20, null);
        assertFalse(unknown.holdFixup(10, false, false), "no prediction noted: no hold");
        MenuClicks.CursorGuard empty = guard(20, true);
        assertFalse(empty.holdFixup(10, true, false), "the cache agrees: nothing to wait for");
        MenuClicks.CursorGuard noGate = new MenuClicks.CursorGuard(20);
        assertFalse(noGate.holdFixup(10, false, false), "no gate click noted: no budget");
        for (MenuClicks.CursorGuard g : List.of(remainder, unknown, empty, noGate)) {
            assertFalse(g.held());
            assertEquals("", g.heldText());
            assertEquals("", g.retryText());
        }
    }

    @Test
    void aRefusedShiftClickGoesAgainOnceTheCursorIsEmpty() {
        MenuClicks.CursorGuard g = guard(20, true);
        assertEquals(MenuClicks.CursorGuard.Shift.SEND, g.shiftDue(9, 12, true, true));
        assertTrue(g.refused(9, 12), "refused for the cache's cursor: it goes again");
        assertEquals(MenuClicks.CursorGuard.Shift.HOLD, g.shiftDue(9, 14, false, true), "the cursor still shows items");
        assertEquals(MenuClicks.CursorGuard.Shift.SEND, g.shiftDue(9, 16, true, true), "the re-send");
        assertEquals(MenuClicks.CursorGuard.Shift.SEND, g.shiftDue(10, 18, true, true), "the next shift-click as usual");
        assertEquals(1, g.retries());
        assertEquals(", shift retried 1", g.retryText());

        assertTrue(g.refused(10, 18));
        assertEquals(MenuClicks.CursorGuard.Shift.SEND, g.shiftDue(10, 20, true, true));
        assertFalse(g.refused(10, 20), "at most " + MenuClicks.SHIFT_RETRIES + " re-sends");
        assertEquals(", shift retried 2, shift refused 1", g.retryText());

        MenuClicks.CursorGuard late = guard(20, true);
        assertTrue(late.refused(9, 12));
        assertEquals(MenuClicks.CursorGuard.Shift.HOLD, late.shiftDue(9, 24, false, true));
        assertEquals(MenuClicks.CursorGuard.Shift.GIVE_UP, late.shiftDue(9, 26, false, true),
            "the budget ran out (20 ticks from the gate's click at 6) with the cursor still showing items");
        assertFalse(late.refused(10, 28), "the next refusal past the budget stays refused");
        assertEquals(", shift refused 2", late.retryText());
    }

    @Test
    void aShiftClickEndsAHoldTheGatesAnswerCutShort() {
        // the gate's answer with an empty cursor moves the request past the held fix-up (skipTo): the first shift-click
        // ends the hold
        MenuClicks.CursorGuard g = guard(20, true);
        assertTrue(g.holdFixup(10, false, false));
        assertEquals(MenuClicks.CursorGuard.Shift.SEND, g.shiftDue(9, 12, true, true));
        assertEquals(", held 2 t (stale cursor, answered)", g.heldText());
        MenuClicks.Waits w = new MenuClicks.Waits();
        w.gateClick(6);
        w.gateSkipped();
        w.firstShift(12);
        w.lastClick(14);
        w.closed(19, true, true);
        assertEquals("; waits: mid answered 6 t, held 2 t (stale cursor, answered), tail answered 5 t",
            w.text(true, true, true, g));
        assertEquals("; waits: mid answered 6 t, tail answered 5 t", w.text(true, true, true, null),
            "without the guard as before");
        assertEquals("; waits: mid answered 6 t, tail answered 5 t", w.text(true, true, true, guard(20, true)),
            "a guard that did nothing adds nothing");
    }
}
