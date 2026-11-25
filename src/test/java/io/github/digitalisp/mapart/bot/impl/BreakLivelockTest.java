package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.DigServer.BlockType;
import io.github.digitalisp.mapart.model.Pos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The NUKE livelock of a local 10x run (2026-10-11, DOUBLE): from a fixed spot the break task alternated its walking goal
 * every tick between two blocks at the same distance (dx 4, dz −3 and dx −3, dz 4), each change restarted Baritone's
 * path, and the bot never moved. {@link BreakRun} now keeps the target it walks to ({@link BreakRun#WALK_SWITCH_MARGIN});
 * {@link Walker} gives a walk up whose goal keeps changing without the bot moving ({@link GoalChurn}).
 */
class BreakLivelockTest {
    static final int Y = 64;

    /**
     * Two targets at exactly the same distance, a distance that sways by a hair every tick, and a walk that moves only
     * two ticks after its target changed (Baritone's search): both targets are broken, with one change of target.
     */
    @Test
    void twoTargetsAtTheSameDistanceDoNotTakeTurns() {
        Map<Pos, BlockType> blocks = new LinkedHashMap<>();
        Pos a = new Pos(16, Y, 6); // from (10.5, 10.5): dx +6, dz -4, out of reach
        Pos b = new Pos(6, Y, 16); // dx -4, dz +6
        blocks.put(a, DigServer.TERRACOTTA);
        blocks.put(b, DigServer.TERRACOTTA);
        DigServer s = new DigServer(5, Y);
        s.world.putAll(blocks);
        var kit = BreakRunSimTest.enchanted();
        for (int i = 0; i < 9; i++) s.hotbar[i] = kit[i] == null ? null : kit[i].copy();
        BreakSim sim = new BreakSim(s, 10.5, 10.5, 20, Set.of(), 6);
        sim.distanceDrift = 1e-6;
        sim.pathStartTicks = 2;
        BreakRun<BreakSim.Shot> run = new BreakRun<>(List.of(a, b), true, BreakSim.REACH, 20, false, sim, sim::broken);
        TaskStatus st = sim.run(run, 2_000);
        assertEquals(TaskStatus.SUCCESS, st, run.failure() + " after " + sim.goalSwitches + " goal changes");
        assertEquals(2, run.done());
        assertTrue(sim.goalSwitches <= 3, "walk target changed " + sim.goalSwitches + " times");
        assertTrue(sim.ticks < 300, "ticks " + sim.ticks);
    }

    @Test
    void aClearlyNearerTargetStillTakesOver() {
        // the margin only damps swaps: a target a block nearer than the one walked to wins at once
        Map<Pos, BlockType> blocks = new LinkedHashMap<>();
        Pos far = new Pos(30, Y, 10);
        Pos near = new Pos(18, Y, 10);
        blocks.put(far, DigServer.TERRACOTTA);
        blocks.put(near, DigServer.TERRACOTTA);
        DigServer s = new DigServer(5, Y);
        s.world.putAll(blocks);
        var kit = BreakRunSimTest.enchanted();
        for (int i = 0; i < 9; i++) s.hotbar[i] = kit[i] == null ? null : kit[i].copy();
        BreakSim sim = new BreakSim(s, 10.5, 10.5, 20, Set.of(), 6);
        BreakRun<BreakSim.Shot> run = new BreakRun<>(List.of(far, near), true, BreakSim.REACH, 20, false, sim,
            sim::broken);
        TaskStatus st = sim.run(run, 2_000);
        assertEquals(TaskStatus.SUCCESS, st, run.failure());
        // the near one first: broken before the bot walked on to the far one
        assertTrue(s.destroys.getFirst().pos().equals(near), "first broken " + s.destroys.getFirst().pos());
    }

    @Test
    void giveUpStuckLeavesTheWalkTargetForLater() {
        Pos a = new Pos(30, Y, 10);
        DigServer s = new DigServer(5, Y);
        s.world.put(a, DigServer.TERRACOTTA);
        var kit = BreakRunSimTest.enchanted();
        for (int i = 0; i < 9; i++) s.hotbar[i] = kit[i] == null ? null : kit[i].copy();
        BreakSim sim = new BreakSim(s, 10.5, 10.5, 20, Set.of(), 6);
        sim.pathStartTicks = 1_000_000; // never gets going
        BreakRun<BreakSim.Shot> run = new BreakRun<>(List.of(a), true, BreakSim.REACH, 20, false, sim, sim::broken);
        for (long t = 0; t < 20; t++) {
            sim.beginTick(t);
            run.tick(t);
            sim.endTick(t);
            s.tick(t);
        }
        assertEquals(a, run.walkTarget());
        assertEquals(a, run.giveUpStuck("test watchdog"));
        assertNull(run.walkTarget());
        sim.beginTick(20);
        TaskStatus st = run.tick(20);
        assertEquals(TaskStatus.FAILED, st);
        assertTrue(run.failure().contains("test watchdog"), run.failure());
    }

    @Test
    void goalChurnEndsAWalkThatNeverMoves() {
        GoalChurn c = new GoalChurn();
        for (int i = 1; i < GoalChurn.LIMIT; i++) assertFalse(c.changed(i, 10.0, 10.0), "change " + i);
        assertTrue(c.changed(GoalChurn.LIMIT, 10.0, 10.0));
        assertEquals(GoalChurn.LIMIT, c.changes());
    }

    @Test
    void goalChurnForgivesMovingOrSlowChanges() {
        GoalChurn moving = new GoalChurn();
        for (int i = 1; i <= 100; i++) assertFalse(moving.changed(i, 10.0 + i * 0.3, 10.0), "moving " + i);
        GoalChurn slow = new GoalChurn();
        for (int i = 1; i <= 100; i++) {
            assertFalse(slow.changed(i * (long) (GoalChurn.GAP_TICKS + 1), 10.0, 10.0), "slow " + i);
        }
    }
}
