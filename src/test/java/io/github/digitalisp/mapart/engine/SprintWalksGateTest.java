package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code build.sprintWalks}: the engine lets plain walks sprint in every
 * phase but while the clear runs (CLEAR once pressed or on a retry, WAIT_CLEAR, BLOCKED from either). Synthetic
 * coordinates.
 */
class SprintWalksGateTest {
    private static final int LONG = 60_000;

    @Test
    void everyPhaseButTheRunningClear() {
        for (Phase p : Phase.values()) {
            if (p == Phase.CLEAR || p == Phase.WAIT_CLEAR || p == Phase.BLOCKED || p == Phase.RIDE) continue;
            assertTrue(BuildEngine.walksMaySprint(p, null, false, 0), p.name());
            assertTrue(BuildEngine.walksMaySprint(p, null, true, 3), p + ": the clear's state is the clear's only");
        }
        assertTrue(BuildEngine.walksMaySprint(Phase.CLEAR, null, false, 1), "the walk to the button, first attempt");
        assertFalse(BuildEngine.walksMaySprint(Phase.CLEAR, null, true, 1), "pressed: the machines run");
        assertFalse(BuildEngine.walksMaySprint(Phase.CLEAR, null, false, 2),
            "a retry: the machines of the last press may still run");
        assertFalse(BuildEngine.walksMaySprint(Phase.RIDE, null, false, 0), "the walk off the rail field after a lost ride");
        assertFalse(BuildEngine.walksMaySprint(Phase.WAIT_CLEAR, null, false, 0));
        assertFalse(BuildEngine.walksMaySprint(Phase.WAIT_CLEAR, null, true, 1));
        assertFalse(BuildEngine.walksMaySprint(Phase.BLOCKED, Phase.WAIT_CLEAR, true, 3), "a chase while blocked");
        assertFalse(BuildEngine.walksMaySprint(Phase.BLOCKED, Phase.CLEAR, false, 1));
        assertTrue(BuildEngine.walksMaySprint(Phase.BLOCKED, Phase.VERIFY, false, 0));
        assertTrue(BuildEngine.walksMaySprint(Phase.BLOCKED, null, false, 0));
    }

    private record Seen(Phase phase, boolean gate, boolean pressed, String task) { }

    @Test
    void theEngineClosesTheGateFromThePressUntilTheClearIsOver() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        int art = sim.moss.artY;
        sim.moss.center = new Pos(0, art + 6, 0); // the bridge over the art layer
        sim.moss.stayDuringClear = true; // WAIT_CLEAR walks to the centre
        for (int i = 0; i < 20; i++) {
            sim.world.set(sim.moss.geometry().cell(100 + i % 10, 100 + i / 10), BlockSpec.of(Sim.TERRACOTTA));
            sim.world.set(sim.moss.geometry().cell(50 + i % 10, 60 + i / 10), BlockSpec.of(Sim.RED));
        }
        AtomicBoolean pressed = new AtomicBoolean();
        Runnable clear = sim.bot.useHandlers.get(Sim.MOSS_BUTTON);
        sim.bot.useHandlers.put(Sim.MOSS_BUTTON, () -> {
            pressed.set(true);
            clear.run();
        });
        sim.addJob("j0310", "Sprint", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        List<Seen> seen = new ArrayList<>();
        sim.start();
        assertTrue(sim.runUntil(() -> {
            String task = sim.bot.lastTask == null || sim.bot.lastTask.isFinished() ? "" : sim.bot.lastTask.name();
            seen.add(new Seen(sim.phase(), sim.bot.sprintWalks, pressed.get(), task));
            return sim.phase() == Phase.BUILD && sim.bot.placed > 0;
        }, LONG), sim.state());

        Set<Phase> phases = EnumSet.noneOf(Phase.class);
        for (Seen s : seen) phases.add(s.phase());
        assertTrue(phases.containsAll(EnumSet.of(Phase.NUKE, Phase.CLEAR, Phase.WAIT_CLEAR, Phase.BUILD)), phases.toString());

        boolean walkedToTheButton = false;
        boolean walkedToTheCentreWaiting = false;
        boolean clearOver = false;
        for (Seen s : seen) {
            boolean clearing = s.pressed() && (s.phase() == Phase.CLEAR || s.phase() == Phase.WAIT_CLEAR);
            if (s.pressed() && !clearing) clearOver = true;
            if (clearing) {
                assertFalse(s.gate(), "a walk may sprint while the clear runs: " + s);
                if (s.phase() == Phase.WAIT_CLEAR && s.task().startsWith("walkTo")) walkedToTheCentreWaiting = true;
            } else if (s.phase() != Phase.IDLE && s.phase() != Phase.SELECT) {
                assertTrue(s.gate(), "plain walks may sprint in " + s);
            }
            if (s.phase() == Phase.CLEAR && !s.pressed() && s.task().startsWith("use")) {
                walkedToTheButton = true;
                assertTrue(s.gate(), "the walk to the clear button sprints: " + s);
            }
        }
        assertTrue(walkedToTheButton, "the clear button's walk was seen");
        assertTrue(walkedToTheCentreWaiting, "WAIT_CLEAR walked to the center without sprint");
        assertTrue(clearOver, "phases after the clear were seen");
        assertEquals(1, sim.bot.count("use " + Sim.MOSS_BUTTON));
    }
}
