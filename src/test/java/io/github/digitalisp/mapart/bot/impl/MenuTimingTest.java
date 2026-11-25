package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.MessageFormatter;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.WithdrawMode;
import io.github.digitalisp.mapart.bot.impl.MenuClicks.Switches;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Step;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Container menus in real ticks on {@link MenuTimingSim}: ZenithProxy
 * 3.7.2's InventoryManager runs one action every {@code actionDelayTicks + 1} ticks, the server answers each click
 * after a latency (live 2026-10-24: 41-43 ms median, 99.9 % within 100 ms), the sorter's hoppers update other slots.
 * With the new switches off the click/tick schedule is exactly the one before them (live: 902 ms from the last cursor
 * click to the first shift-click, 858 ms from the last answer to the close); {@code build.containerAnswerWaits} ends
 * both waits on the answer to the last click, capped at {@code containerAckTicks} real ticks (a tail after a last
 * shift-click also on the moves showing, capped at {@code containerShiftAnswerTicks});
 * {@code build.containerSettleFast} drops the 2 settle ticks; {@code build.containerClickEveryTick} clicks once a tick.
 */
class MenuTimingTest {
    private static final int DELAY = 1; // live build.containerClickTicks
    private static final int ACK = 8;   // live build.containerAckTicks
    private static final Switches OFF = Switches.before(true);
    private static final Switches WAITS = new Switches(true, false, true, true, false, false);
    private static final Switches WAITS_SETTLE = new Switches(true, false, true, true, true, false);
    private static final Switches EVERY_TICK = new Switches(true, false, true, true, false, true);

    private static Object[] plan(String name) {
        for (Object[] c : MenuClicksTest.plans()) if (c[0].equals(name)) return c;
        throw new IllegalArgumentException(name);
    }

    private static MenuTimingSim sim(Switches sw, String plan) {
        Object[] c = plan(plan);
        return new MenuTimingSim(sw, DELAY, ACK, (VanillaClickModel) c[1], MenuClicksTest.plan(c).steps());
    }

    private static MenuTimingSim done(MenuTimingSim s) {
        s.run();
        assertEquals(TaskStatus.SUCCESS, s.status, s.run == null ? "no run" : s.run.failure());
        assertEquals(null, s.run.problem());
        assertTrue(s.endTick > s.closeTick, "the run never ends in its close tick (Grim MultiActionsD)");
        return s;
    }

    // ------------------------------------------------------------------------- the switches off: as before

    @Test
    void offATopUpAndShiftClicksKeepTheirScheduleTick_for_tick() {
        MenuTimingSim s = done(sim(OFF, "top-up + 2 stacks"));
        assertEquals(List.of(1L, 3L, 5L, 23L, 25L), s.clickTicks(), "L0 L60 L0, 7 waits, fix-up, Q1 Q2: 2 ticks an action");
        assertEquals(List.of(21L), s.fixups);
        assertEquals(41L, s.snapshotTick, "7 waits after the last click, then the snapshot");
        assertEquals(43L, s.closeTick);
        assertEquals(46L, s.endTick, "2 settle ticks");
        assertEquals(18L, s.mid(), "902 ms on the wire (live 2026-10-24)");
        assertEquals(18L, s.tail());
        assertFalse(s.run.settledFast());
    }

    @Test
    void offATopUpOnlyKeepsItsFixedTail() {
        MenuTimingSim s = done(sim(OFF, "top-up only"));
        assertEquals(List.of(1L, 3L, 5L), s.clickTicks());
        assertEquals(21L, s.snapshotTick);
        assertEquals(23L, s.closeTick);
        assertEquals(26L, s.endTick);
    }

    @Test
    void offShiftClicksOnlyCloseOnTheirAnswersAsBefore() {
        MenuTimingSim s = done(sim(OFF, "whole stacks"));
        assertEquals(List.of(1L, 3L, 5L), s.clickTicks());
        assertEquals(-1L, s.snapshotTick, "build.containerAnswerEarly: no fixed tail");
        assertEquals(8L, s.closeTick, "answer at 292 ms, seen at tick 7, closed at tick 8 (about 110 ms later, live ~111 ms)");
        assertEquals(11L, s.endTick);
    }

    @Test
    void offTheScheduleDoesNotDependOnTheAnswersOfCursorClicks() {
        for (int latency : new int[]{0, 42, 80, 400, 10_000}) {
            for (MenuTimingSim.Answer kind : MenuTimingSim.Answer.values()) {
                MenuTimingSim s = sim(OFF, "top-up + 2 stacks");
                s.latencyMs = latency;
                s.cursorAnswer = kind;
                s.hopperEveryMs = 50;
                done(s);
                assertEquals(List.of(1L, 3L, 5L, 23L, 25L), s.clickTicks(), latency + " ms, " + kind);
                assertEquals(43L, s.closeTick, latency + " ms, " + kind);
                assertEquals(46L, s.endTick);
            }
        }
    }

    // ------------------------------------------------------------------------- build.containerAnswerWaits

    @Test
    void answersWithinOneTickEndBothWaitsAtOnce() {
        MenuTimingSim s = done(sim(WAITS, "top-up + 2 stacks"));
        assertEquals(List.of(1L, 3L, 5L, 7L, 9L), s.clickTicks(), "the shift-clicks right after the answer");
        assertEquals(List.of(), s.fixups, "answered with an empty cursor: no fix-up action");
        assertEquals(2L, s.mid(), "the next action ZenithProxy runs (2 ticks a action): 100 ms instead of 900");
        assertEquals(1L, s.tail(), "closed on the tick that sees the last answer: 50 ms instead of 900");
        assertEquals(10L, s.closeTick);
        assertEquals(13L, s.endTick, "settle ticks as before (build.containerSettleFast off)");
    }

    @Test
    void answersInTheSecondTick() {
        MenuTimingSim s = sim(WAITS, "top-up + 2 stacks");
        s.latencyMs = 80; // live p99
        done(s);
        assertEquals(2L, s.mid(), "ZenithProxy could not run the next action before the second tick anyway");
        assertEquals(2L, s.tail());
        s = sim(WAITS, "top-up + 2 stacks");
        s.latencyMs = 120;
        done(s);
        assertEquals(4L, s.mid(), "a gate wait ran (tick 7), the shift-click 2 ticks later");
        assertEquals(3L, s.tail());
    }

    @Test
    void answersLaterThanTheCapWaitAckTicksRealTicks() {
        for (int latency : new int[]{450, 10_000}) {
            MenuTimingSim s = sim(WAITS, "top-up + 2 stacks");
            s.latencyMs = latency;
            done(s);
            assertEquals(List.of(1L, 3L, 5L, 15L, 17L), s.clickTicks(), latency + " ms");
            assertEquals(List.of(13L), s.fixups, "the fix-up 8 real ticks after the last cursor click");
            assertEquals(10L, s.mid(), "the cap: 8 ticks to the fix-up, its own 2");
            assertEquals(latency == 450 ? 9L : 20L, s.tail(),
                "after the last shift-click: its answer (450 ms: 9 ticks), else containerShiftAnswerTicks");
        }
    }

    @Test
    void hopperUpdatesDuringTheWaitsDoNotEndThem() {
        // no answer in time; a hopper updates slot 20 every tick, and the gate's slot shows its state before the click
        MenuTimingSim s = sim(WAITS, "top-up + 2 stacks");
        s.latencyMs = 10_000;
        s.hopperEveryMs = 50;
        s.hopperOnGateSlot = true;
        done(s);
        assertEquals(10L, s.mid(), "the full gate");
        assertEquals(20L, s.tail(), "the full tail (containerShiftAnswerTicks after the last shift-click)");
        assertTrue(s.ignored() >= 20, "hopper updates seen and ignored: " + s.ignored());

        MenuTimingSim late = sim(WAITS, "top-up + 2 stacks");
        late.latencyMs = 120;
        late.hopperEveryMs = 50;
        late.hopperOnGateSlot = true;
        done(late);
        assertEquals(4L, late.mid(), "ends on the answer, not on a hopper update before it");
        assertEquals(3L, late.tail());
    }

    @Test
    void theCursorPathAnswersByCursorOrContentsAndWaitsWithoutOne() {
        for (MenuTimingSim.Answer kind : List.of(MenuTimingSim.Answer.SLOT, MenuTimingSim.Answer.CURSOR,
            MenuTimingSim.Answer.CONTENT)) {
            MenuTimingSim s = sim(WAITS, "top-up + 2 stacks");
            s.cursorAnswer = kind;
            done(s);
            assertEquals(2L, s.mid(), kind.name());
        }
        MenuTimingSim none = sim(WAITS, "top-up + 2 stacks");
        none.cursorAnswer = MenuTimingSim.Answer.NONE; // a prediction the server does not answer
        done(none);
        assertEquals(10L, none.mid(), "no answer: the cap");
        assertEquals(1L, none.tail(), "the shift-click's contents answer the tail");
    }

    @Test
    void aTopUpOnlyClosesOnItsLastClicksAnswer() {
        MenuTimingSim s = done(sim(WAITS, "top-up only"));
        assertEquals(List.of(1L, 3L, 5L), s.clickTicks());
        assertEquals(-1L, s.snapshotTick, "no fixed tail: the run reads the contents when it closes");
        assertEquals(1L, s.tail(), "instead of 18");
        MenuTimingSim capped = sim(WAITS, "top-up only");
        capped.cursorAnswer = MenuTimingSim.Answer.NONE;
        done(capped);
        assertEquals(8L, capped.tail());
    }

    @Test
    void theShiftPathClosesOnTheLastShiftClicksAnswer() {
        MenuTimingSim s = done(sim(WAITS, "whole stacks"));
        assertEquals(List.of(1L, 3L, 5L), s.clickTicks());
        assertEquals(1L, s.tail(), "the tick that sees it, not 2 ticks later (answer-early alone: 3)");
        assertEquals("; waits: tail answered 1 t", s.waitsText());
        MenuTimingSim late = sim(WAITS, "whole stacks");
        late.latencyMs = 10_000;
        done(late);
        assertEquals(20L, late.tail(), "the cap: containerShiftAnswerTicks real ticks, not containerAckTicks");
        assertEquals("; waits: tail cap 20 t (no answer)", late.waitsText());
    }

    // ------------------------------------------------------------------------- the tail after a last shift-click

    @Test
    void aShiftClickTailEndsOnTheMovesWhenTheAnswerIsNotSeen() {
        // the contents arrive, but a hopper refilled the shift-clicked slot: no answer for ClickAnswers, the moves show
        for (String plan : List.of("whole stacks", "top-up + 2 stacks")) {
            MenuTimingSim s = sim(WAITS, plan);
            s.hopperRefillsShiftSlot = true;
            done(s);
            assertEquals(1L, s.tail(), plan + ": the tick that sees the moves");
            assertTrue(s.waitsText().endsWith("tail moves 1 t"), plan + ": " + s.waitsText());
        }
        MenuTimingSim late = sim(WAITS, "top-up + 2 stacks");
        late.hopperRefillsShiftSlot = true;
        late.latencyMs = 450;
        done(late);
        assertEquals("; waits: mid cap 10 t, tail moves 9 t", late.waitsText(), "moves 9 ticks after the click, no cap");
    }

    @Test
    void aShiftClickTailWaitsForItsAnswerBeyondTheAckCap() {
        // containerAckTicks 4 and answers 4-5 ticks after the click closed every shift-click tail before
        // its answer (46 of 46 counted short); the tail now waits for it
        for (String plan : List.of("whole stacks", "top-up + 2 stacks")) {
            Object[] p = plan(plan);
            MenuTimingSim s = new MenuTimingSim(WAITS, DELAY, 4, (VanillaClickModel) p[1], MenuClicksTest.plan(p).steps());
            s.latencyMs = 225; // 4.5 ticks
            done(s);
            assertEquals(5L, s.tail(), plan + ": the answer, not the 4-tick cap");
            assertTrue(s.waitsText().endsWith("tail answered 5 t"), plan + ": " + s.waitsText());
        }
        MenuTimingSim topUp = new MenuTimingSim(WAITS, DELAY, 4, (VanillaClickModel) plan("top-up only")[1],
            MenuClicksTest.plan(plan("top-up only")).steps());
        topUp.cursorAnswer = MenuTimingSim.Answer.NONE;
        done(topUp);
        assertEquals(4L, topUp.tail(), "a last cursor click keeps containerAckTicks as its cap");
    }

    @Test
    void theShiftClickTailCapIsContainerShiftAnswerTicks() {
        for (int cap : new int[]{20, 12, 5}) {
            MenuTimingSim s = sim(WAITS, "whole stacks");
            s.latencyMs = 10_000;
            s.shiftAnswerTicks = cap;
            done(s);
            assertEquals(cap, s.tail(), "cap " + cap);
            assertEquals("; waits: tail cap " + cap + " t (no answer)", s.waitsText());
        }
        MenuTimingSim off = sim(OFF, "whole stacks");
        off.latencyMs = 10_000;
        off.shiftAnswerTicks = 40;
        done(off);
        assertEquals(9L, off.tail(), "off: answer-early's containerAckTicks cap as before (7 ticks in ANSWERS from"
            + " the tick that saw the request done, the close a tick later)");
        assertEquals("", off.waitsText());
    }

    // ------------------------------------------------------------------------- the cursor guard

    @Test
    void aStaleCursorHoldsTheFixUpUntilTheGatesOwnStateIsIn() {
        // every cursor click answered with the menu's full state 450 ms late: the states of the pick-up (tick 10) and of
        // the top-up click (tick 12, the remainder on the cursor) reach the cache before the gate's own (tick 14)
        MenuTimingSim s = sim(WAITS, "top-up + 2 stacks");
        s.cursorAnswer = MenuTimingSim.Answer.CONTENT;
        s.latencyMs = 450;
        done(s);
        assertEquals(List.of(), s.fixupClicks, "held at tick 13 (5.22: clicked the remainder into an empty slot)");
        assertEquals(List.of(), s.refusals);
        assertEquals(List.of(1L, 3L, 5L, 15L, 17L), s.clickTicks(), "the gate's state at tick 14: the shift-click at 15");
        assertEquals("; waits: mid answered 10 t, held 2 t (stale cursor, answered), tail answered 9 t", s.waitsText());
    }

    @Test
    void aStaleCursorNothingCorrectsHoldsTheFixUpToTheBudget() {
        MenuTimingSim s = sim(WAITS, "top-up + 2 stacks");
        s.cursorAnswer = MenuTimingSim.Answer.NONE; // the gate's click stays unanswered
        s.staleStep = 1;   // the top-up click's state (the remainder on the cursor) ...
        s.staleAtMs = 550; // ... reaches the cache at tick 11, nothing corrects it
        done(s);
        assertEquals(List.of(25L), s.fixupClicks, "20 ticks after the gate's click (tick 5): the fix-up clicks as before");
        assertEquals(27L, s.firstAfter(TransferPlanner.Kind.QUICK_MOVE, 5));
        assertEquals("; waits: mid cap 22 t, held 12 t (stale cursor, cap), tail answered 1 t", s.waitsText());

        MenuTimingSim short8 = sim(WAITS, "top-up + 2 stacks");
        short8.cursorAnswer = MenuTimingSim.Answer.NONE;
        short8.staleStep = 1;
        short8.staleAtMs = 550;
        short8.shiftAnswerTicks = 10;
        done(short8);
        assertEquals(List.of(15L), short8.fixupClicks, "the budget is containerShiftAnswerTicks");
    }

    @Test
    void anAnswerThatLeavesTheCursorStaleKeepsTheFixUpHeld() {
        // the gate's click counted answered (here: its slot refilled, as it predicted) while the cache
        // still showed the remainder on the cursor; the hold waits for the cursor
        MenuTimingSim s = sim(WAITS, "top-up + 2 stacks");
        s.cursorAnswer = MenuTimingSim.Answer.NONE;
        s.staleStep = 1;
        s.staleAtMs = 550;          // tick 11
        s.gateSlotFilledAtMs = 560; // tick 12: counts as the gate's answer
        s.staleFixedAtMs = 800;     // tick 16: the server's state, the cursor empty
        done(s);
        assertEquals(List.of(), s.fixupClicks, "first run of the code: clicked the stale cursor at tick 13");
        assertEquals(17L, s.firstAfter(TransferPlanner.Kind.QUICK_MOVE, 5));
        assertEquals("; waits: mid answered 12 t, held 4 t (stale cursor, answered), tail answered 1 t", s.waitsText());
    }

    @Test
    void theFixUpHoldEndsWhenTheCacheShowsTheCursorEmpty() {
        MenuTimingSim s = sim(WAITS, "top-up + 2 stacks");
        s.cursorAnswer = MenuTimingSim.Answer.NONE;
        s.staleStep = 1;
        s.staleAtMs = 550;      // tick 11
        s.staleFixedAtMs = 700; // tick 14: the cache shows the cursor empty, in a packet ClickAnswers does not take
        s.staleFixSeen = false; // for the gate's answer
        done(s);
        assertEquals(List.of(15L), s.fixups, "held at 13, at 15 nothing on the cursor: nothing to click");
        assertEquals(List.of(), s.fixupClicks);
        assertEquals("; waits: mid cap 12 t, held 2 t (stale cursor, matched), tail answered 1 t", s.waitsText());
    }

    @Test
    void noHoldWithoutAStaleCursor() {
        // the gate's click unanswered, nothing on the cursor: the fix-up runs on its tick, as in 5.22
        MenuTimingSim s = sim(WAITS, "top-up + 2 stacks");
        s.cursorAnswer = MenuTimingSim.Answer.NONE;
        done(s);
        assertEquals(List.of(13L), s.fixups);
        assertFalse(s.guard.held());
        assertEquals("; waits: mid cap 10 t, tail answered 1 t", s.waitsText());
    }

    @Test
    void aRefusedShiftClickGoesAgainOnceTheCursorShowsEmpty() {
        // the gate answered at tick 8 (the fix-up skipped); a stale state reaches the cache at tick 9, just before the
        // first shift-click, and the server's current state at tick 11
        MenuTimingSim s = sim(WAITS, "top-up + 2 stacks");
        s.latencyMs = 120;
        s.staleStep = 1;
        s.staleAtMs = 420;
        s.staleFixedAtMs = 520;
        done(s);
        assertEquals(List.of(9L), s.refusals, "ZenithProxy's ShiftClick refuses it: mouse stack is not empty");
        assertEquals(List.of(1L, 3L, 5L, 11L, 13L), s.clickTicks(), "re-sent at 11, the next one at 13");
        assertEquals("; waits: mid answered 6 t, tail answered 3 t, shift retried 1", s.waitsText());

        MenuTimingSim never = sim(WAITS, "top-up + 2 stacks");
        never.latencyMs = 120;
        never.staleStep = 1;
        never.staleAtMs = 420; // nothing corrects it
        done(never);
        assertEquals(List.of(9L, 27L), never.refusals, "held to the budget (tick 25), then the next one past it");
        assertEquals(List.of(1L, 3L, 5L), never.clickTicks(), "both stay refused: the withdrawal counts short");
        assertEquals("; waits: mid n/a, tail n/a, shift refused 2", never.waitsText());
    }

    @Test
    void exactCountsKeepTheirWaitsAndGateTheShiftClicks() {
        MenuTimingSim off = done(sim(OFF, "exact 3 panes + stacks"));
        MenuTimingSim on = done(sim(WAITS, "exact 3 panes + stacks"));
        assertEquals(off.clickTicks().subList(0, 5), on.clickTicks().subList(0, 5),
            "the cursor clicks as before, the right clicks onto the empty slot included");
        assertEquals(18L, off.mid());
        assertEquals(2L, on.mid());
        assertEquals(1L, on.tail());
    }

    // ------------------------------------------------------------------------- build.containerSettleFast

    @Test
    void settleFastEndsOnTheTickAfterTheClose() {
        MenuTimingSim s = done(sim(WAITS_SETTLE, "top-up + 2 stacks"));
        assertEquals(10L, s.closeTick);
        assertEquals(11L, s.endTick, "the tick after the close, not 3 ticks after");
        assertTrue(s.run.settledFast());

        MenuTimingSim capped = sim(WAITS_SETTLE, "top-up + 2 stacks");
        capped.latencyMs = 10_000;
        done(capped);
        assertEquals(capped.closeTick + 3, capped.endTick, "answers not in at the close: the settle ticks");
        assertFalse(capped.run.settledFast());

        MenuTimingSim alone = done(sim(new Switches(true, false, true, false, true, false), "top-up + 2 stacks"));
        assertEquals(List.of(1L, 3L, 5L, 23L, 25L), alone.clickTicks(), "settle-fast alone leaves the clicks as before");
        assertEquals(43L, alone.closeTick);
        assertEquals(44L, alone.endTick);
    }

    // ------------------------------------------------------------------------- build.containerClickEveryTick

    @Test
    void everyTickClicksOncePerTick() {
        MenuTimingSim s = done(sim(EVERY_TICK, "top-up + 2 stacks"));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), s.clickTicks());
        assertEquals(1, s.maxActionsInATick, "never two clicks in a tick");
        assertEquals(1L, s.mid(), "the shift-click on the tick that sees the answer");
        assertEquals(1L, s.tail());

        MenuTimingSim late = sim(EVERY_TICK, "top-up + 2 stacks");
        late.latencyMs = 80;
        done(late);
        assertEquals(2L, late.mid());
        assertEquals(2L, late.tail());

        MenuTimingSim capped = sim(EVERY_TICK, "top-up + 2 stacks");
        capped.latencyMs = 10_000;
        done(capped);
        assertEquals(9L, capped.mid(), "8 real ticks to the fix-up, then the shift-click");
        assertEquals(20L, capped.tail(), "containerShiftAnswerTicks after the last shift-click");
    }

    @Test
    void everyTickWithoutAnswerWaitsCountsTheOldWaitsInRealTicks() {
        MenuTimingSim s = done(sim(new Switches(true, false, true, false, false, true), "top-up + 2 stacks"));
        assertEquals(List.of(1L, 2L, 3L, 12L, 13L), s.clickTicks());
        assertEquals(List.of(11L), s.fixups, "8 ticks after the last cursor click, as containerAckTicks says");
        assertEquals(21L, s.snapshotTick);
        assertEquals(23L, s.closeTick);
        assertEquals(26L, s.endTick);
    }

    @Test
    void everyTickKeepsSixClicksIn300Ms() {
        VanillaClickModel m = new VanillaClickModel(27);
        for (int i = 0; i < 8; i++) m.put(i, "minecraft:white_carpet", 64, 64);
        var plan = TransferPlanner.planWithdraw(m.containerStacks(), Map.of("minecraft:white_carpet", 512),
            m.playerStacks(), m.playerFree(), WithdrawMode.WHOLE_STACKS, m.emptyPlayerSlots());
        List<Step> steps = plan.steps();
        assertEquals(8, steps.size());
        MenuTimingSim s = new MenuTimingSim(EVERY_TICK, DELAY, ACK, m, steps);
        s.tickMs = 40; // the tick loop catching up: ticks 40 ms apart
        done(s);
        assertEquals(8, s.sent.size());
        for (int i = 6; i < s.sent.size(); i++) {
            long span = s.sent.get(i).ms() - s.sent.get(i - 6).ms();
            assertTrue(span >= 300, "7 clicks in " + span + " ms");
        }
        assertEquals(1, s.maxActionsInATick);
        MenuTimingSim fifty = new MenuTimingSim(EVERY_TICK, DELAY, ACK, m, steps);
        done(fifty);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), fifty.clickTicks(), "50 ms ticks: one a tick, no hold");
    }

    // ------------------------------------------------------------------------- the per-chest log line

    private static String waits(Switches sw, String plan, int latencyMs, MenuTimingSim.Answer cursorAnswer) {
        MenuTimingSim s = sim(sw, plan);
        s.latencyMs = latencyMs;
        s.cursorAnswer = cursorAnswer;
        s.hopperEveryMs = 50; // the sorter's hoppers: no wait ends on them
        s.hopperOnGateSlot = true;
        return done(s).waitsText();
    }

    @Test
    void theLogLineSaysHowEachAnswerWaitEndedInRealTicks() {
        var slot = MenuTimingSim.Answer.SLOT;
        assertEquals("; waits: mid answered 2 t, tail answered 1 t", waits(WAITS, "top-up + 2 stacks", 42, slot));
        assertEquals("; waits: mid answered 2 t, tail answered 2 t", waits(WAITS, "top-up + 2 stacks", 80, slot));
        assertEquals("; waits: mid answered 4 t, tail answered 3 t", waits(WAITS, "top-up + 2 stacks", 120, slot));
        assertEquals("; waits: mid cap 10 t, tail answered 9 t", waits(WAITS, "top-up + 2 stacks", 450, slot),
            "the gate's answer came after its cap; the tail after a shift-click waits for its answer");
        assertEquals("; waits: mid cap 10 t (no answer), tail cap 20 t (no answer)",
            waits(WAITS, "top-up + 2 stacks", 10_000, slot));
        assertEquals("; waits: mid cap 10 t, tail answered 1 t",
            waits(WAITS, "top-up + 2 stacks", 42, MenuTimingSim.Answer.NONE),
            "a cursor click the server leaves unanswered: the cap; the shift-clicks' contents show it later");
        assertEquals("; waits: tail answered 1 t", waits(WAITS, "top-up only", 42, slot), "no shift-clicks: no mid wait");
        assertEquals("; waits: tail cap 8 t (no answer)", waits(WAITS, "top-up only", 42, MenuTimingSim.Answer.NONE));
        assertEquals("; waits: mid answered 1 t, tail answered 1 t", waits(EVERY_TICK, "top-up + 2 stacks", 42, slot));
        assertEquals("; waits: mid cap 9 t (no answer), tail cap 20 t (no answer)",
            waits(EVERY_TICK, "top-up + 2 stacks", 10_000, slot));
        // off (or not in effect: ncpStrict): nothing is noted, the line stays as it was
        for (Switches off : List.of(OFF, new Switches(true, false, true, false, true, true),
            new Switches(true, true, true, true, true, true))) {
            assertEquals("", waits(off, "top-up + 2 stacks", 42, slot), off.toString());
        }
    }

    @Test
    void theWithdrawLineIsByteForByteAsBeforeWithTheSwitchOff() {
        String name = "withdraw chest";
        Map<String, Integer> moved = new TreeMap<>(Map.of("minecraft:white_carpet", 128, "minecraft:glass_pane", 3));
        for (int[] n : new int[][]{{3, 2}, {1, 1}, {1, 0}, {5, 0}}) {
            for (WithdrawMode mode : List.of(WithdrawMode.TOP_UP, WithdrawMode.EXACT)) {
                // the call as it was: LOG.info with these placeholders and arguments
                String before = MessageFormatter.arrayFormat("{}: {} with {} cursor click{} ({}) and {} shift-click{}",
                    new Object[]{name, moved, n[0], n[0] == 1 ? "" : "s", mode, n[1], n[1] == 1 ? "" : "s"}).getMessage();
                assertEquals(before, MenuClicks.withdrawLine(name, moved, n[0], mode, n[1], ""));
            }
        }
        assertEquals("withdraw chest: {minecraft:glass_pane=3, minecraft:white_carpet=128} with 3 cursor clicks (TOP_UP)"
            + " and 2 shift-clicks", MenuClicks.withdrawLine(name, moved, 3, WithdrawMode.TOP_UP, 2,
            waits(OFF, "top-up + 2 stacks", 42, MenuTimingSim.Answer.SLOT)));
        assertEquals("withdraw chest: {minecraft:glass_pane=3, minecraft:white_carpet=128} with 3 cursor clicks (TOP_UP)"
            + " and 2 shift-clicks; waits: mid answered 2 t, tail answered 1 t",
            MenuClicks.withdrawLine(name, moved, 3, WithdrawMode.TOP_UP, 2,
                waits(WAITS, "top-up + 2 stacks", 42, MenuTimingSim.Answer.SLOT)));
    }

    @Test
    void waitsWithoutTheirEndSayNa() {
        MenuClicks.Waits w = new MenuClicks.Waits();
        w.gateClick(5);
        w.lastClick(9);
        assertEquals("; waits: mid n/a, tail n/a", w.text(true, false, false), "no shift-click, no close went out");
        w.closed(17, false);
        w.closed(20, true); // the first close counts
        assertEquals("; waits: mid n/a, tail cap 8 t", w.text(true, false, true));
    }
}
