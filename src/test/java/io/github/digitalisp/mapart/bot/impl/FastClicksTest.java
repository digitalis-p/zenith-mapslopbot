package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.WithdrawMode;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Kind;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Plan;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Step;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast container clicks ({@code build.containerClickTicks}): a withdrawal clicked at
 * one action per {@code delay} ticks while the server's answers come {@code latency} ticks late, as full container
 * states (a click with a stale state id gets the whole menu back; the worst case for the cache, which rolls back to an
 * older state until the next answer). The cache predicts as ZenithProxy 3.7.2 does ({@link VanillaClickModel}); a
 * shift-click with an item on the cached cursor is not sent (ZenithProxy's ShiftClick), and before the first
 * shift-click an item the cache shows on the cursor goes into an empty slot (the task's fix-up action). With the waits
 * of {@link TransferPlanner#waitsAfter} and {@link TransferPlanner#tailWaits}: every click goes out, the snapshot read
 * {@code ack} ticks after the last click equals the server's state, and the right counts moved, for every latency up to
 * {@code ack}.
 */
class FastClicksTest {
    private static final int TOP = 27;
    private static final String CARPET = "minecraft:white_carpet";
    private static final String LIME = "minecraft:lime_carpet";
    private static final String PANE = "minecraft:glass_pane";

    private record Outcome(int unsent, List<Integer> differences, int ticks, VanillaClickModel truth) { }

    private enum Act { CLICK, WAIT, FIXUP, SNAPSHOT }

    private record Action(Act act, Step step) { }

    private static Plan plan(VanillaClickModel m, Map<String, Integer> wanted, WithdrawMode mode) {
        return TransferPlanner.planWithdraw(m.containerStacks(), wanted, m.playerStacks(), m.playerFree(), mode,
            m.emptyPlayerSlots());
    }

    /** The request's actions as ContainerTransferTask and ContainerTask build them in fast mode. */
    private static List<Action> actions(Plan p, List<Integer> empty, int delay, int ack) {
        List<Step> steps = p.steps();
        int[] waits = TransferPlanner.waitsAfter(steps, empty, delay, ack);
        int firstShift = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).kind() == Kind.QUICK_MOVE) {
                firstShift = i;
                break;
            }
        }
        List<Action> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            if (i == firstShift && i > 0) out.add(new Action(Act.FIXUP, null));
            out.add(new Action(Act.CLICK, steps.get(i)));
            for (int k = 0; k < waits[i]; k++) out.add(new Action(Act.WAIT, null));
        }
        int trailing = 0;
        for (int i = out.size() - 1; i >= 0 && out.get(i).act() == Act.WAIT; i--) trailing++;
        for (int k = TransferPlanner.tailWaits(trailing, delay, ack); k > 0; k--) out.add(new Action(Act.WAIT, null));
        out.add(new Action(Act.SNAPSHOT, null));
        return out;
    }

    /** One action per {@code delay} ticks; each click's answer (the server's whole state) arrives {@code latency} later. */
    private static Outcome run(VanillaClickModel start, Plan p, int delay, int ack, int latency) {
        VanillaClickModel truth = start.copy();
        VanillaClickModel cache = start.zenithPrediction();
        List<Action> acts = actions(p, start.emptyPlayerSlots(), delay, ack);
        record Answer(int tick, VanillaClickModel state) { }
        Deque<Answer> answers = new ArrayDeque<>();
        int unsent = 0;
        List<Integer> diff = null;
        int tick = 0;
        for (Action a : acts) {
            while (!answers.isEmpty() && answers.peekFirst().tick() <= tick) cache.resync(answers.pollFirst().state());
            switch (a.act()) {
                case WAIT -> { }
                case SNAPSHOT -> diff = cache.differences(truth);
                case FIXUP -> {
                    if (cache.cursor() != null) {
                        Integer slot = cache.emptyPlayerSlots().isEmpty() ? null : cache.emptyPlayerSlots().getFirst();
                        if (slot != null) {
                            Step put = new Step(Kind.LEFT, slot);
                            cache.click(put);
                            truth.click(put);
                            answers.addLast(new Answer(tick + latency, truth.copy()));
                        }
                    }
                }
                case CLICK -> {
                    Step s = a.step();
                    if (s.kind() == Kind.QUICK_MOVE) {
                        if (cache.cursor() != null || cache.at(s.slot()) == null) {
                            unsent++; // ZenithProxy's ShiftClick sends nothing
                            break;
                        }
                        truth.click(s);
                    } else {
                        int before = cache.unsent;
                        cache.click(s);
                        if (cache.unsent != before) {
                            unsent++;
                            break;
                        }
                        truth.click(s);
                    }
                    answers.addLast(new Answer(tick + latency, truth.copy()));
                }
            }
            tick += delay;
        }
        return new Outcome(unsent, diff, tick - delay, truth);
    }

    private static Map<String, Integer> want(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    /** Cases like the live restock: a top-up and whole stacks, two partials topped up, an exact count, stacks only. */
    private static List<Object[]> cases() {
        List<Object[]> out = new ArrayList<>();
        out.add(new Object[]{"top-up + 2 stacks", new VanillaClickModel(TOP).put(60, CARPET, 10, 64)
            .put(0, CARPET, 64, 64).put(1, CARPET, 64, 64).put(2, CARPET, 64, 64), want(CARPET, 170),
            WithdrawMode.TOP_UP, CARPET, 192});
        out.add(new Object[]{"two partials topped up", new VanillaClickModel(TOP).put(60, LIME, 30, 64)
            .put(61, LIME, 20, 64).put(0, LIME, 64, 64).put(1, LIME, 64, 64).put(2, LIME, 64, 64), want(LIME, 70),
            WithdrawMode.TOP_UP, LIME, 128});
        out.add(new Object[]{"exact 3 panes", new VanillaClickModel(TOP).put(4, PANE, 64, 64), want(PANE, 3),
            WithdrawMode.EXACT, PANE, 3});
        out.add(new Object[]{"whole stacks", new VanillaClickModel(TOP).put(0, CARPET, 64, 64).put(1, CARPET, 64, 64)
            .put(2, CARPET, 40, 64), want(CARPET, 168), WithdrawMode.WHOLE_STACKS, CARPET, 168});
        return out;
    }

    @Test
    void oneClickPerTickStaysExactUpToTheAckTime() {
        for (Object[] c : cases()) {
            VanillaClickModel start = (VanillaClickModel) c[1];
            @SuppressWarnings("unchecked") Map<String, Integer> wanted = (Map<String, Integer>) c[2];
            Plan p = plan(start, wanted, (WithdrawMode) c[3]);
            for (int latency = 0; latency <= 8; latency++) {
                Outcome o = run(start, p, 1, 8, latency);
                String what = c[0] + ", latency " + latency;
                assertEquals(0, o.unsent(), what + ": every click sent");
                assertEquals(List.of(), o.differences(), what + ": the snapshot equals the server's state");
                assertEquals((int) (Integer) c[5], o.truth().playerCount((String) c[4]), what + ": carried afterwards");
                assertEquals(null, o.truth().cursor(), what + ": cursor empty");
            }
        }
    }

    @Test
    void fasterThanFiveTicksPerClick() {
        for (Object[] c : cases()) {
            VanillaClickModel start = (VanillaClickModel) c[1];
            @SuppressWarnings("unchecked") Map<String, Integer> wanted = (Map<String, Integer>) c[2];
            Plan p = plan(start, wanted, (WithdrawMode) c[3]);
            int fast = run(start, p, 1, 8, 4).ticks();
            int today = run(start, p, 5, TransferPlanner.ACK_TICKS, 4).ticks();
            assertTrue(fast < today, c[0] + ": " + fast + " ticks against " + today);
            assertEquals(List.of(), run(start, p, 5, TransferPlanner.ACK_TICKS, 4).differences(), c[0] + " at 5 ticks");
        }
    }

    @Test
    void tailWaitsCountTheWaitsAlreadyThere() {
        assertEquals(7, TransferPlanner.tailWaits(0, 1, 8));
        assertEquals(0, TransferPlanner.tailWaits(7, 1, 8), "a last cursor click's waits cover it");
        assertEquals(3, TransferPlanner.tailWaits(4, 1, 8));
        assertEquals(1, TransferPlanner.tailWaits(0, 5, 8), "5 ticks per action: one wait, as before");
        assertEquals(0, TransferPlanner.tailWaits(0, 8, 8));
        assertEquals(1, TransferPlanner.tailWaits(0, 2, 3));
        for (int d = 1; d <= 6; d++) {
            for (int ack = 1; ack <= 12; ack++) {
                int w = TransferPlanner.tailWaits(0, d, ack);
                assertTrue((w + 1) * d >= ack, "the snapshot " + (w + 1) * d + " ticks after the click, ack " + ack);
                assertTrue(w * d < ack || w == 0, "no wait more than needed");
            }
        }
    }

    @Test
    void waitsAfterTakeTheAckTime() {
        List<Step> topUp = List.of(new Step(Kind.LEFT, 0), new Step(Kind.LEFT, 60), new Step(Kind.LEFT, 0),
            new Step(Kind.QUICK_MOVE, 1));
        assertEquals(7, TransferPlanner.waitsAfter(topUp, List.of(), 1)[2]);
        assertEquals(7, TransferPlanner.waitsAfter(topUp, List.of(), 1, 8)[2]);
        assertEquals(3, TransferPlanner.waitsAfter(topUp, List.of(), 1, 4)[2]);
        assertEquals(0, TransferPlanner.waitsAfter(topUp, List.of(), 2, 2)[2]);
    }
}
