package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The restock-leg sprint rule and the quiet window before clicks. */
class SprintLegsTest {
    private static final SprintLegs.Rule RULE = new SprintLegs.Rule(8, 4.0, 5);

    @Test
    void offByDefault() {
        MapArtConfig.BuildCfg b = new MapArtConfig().build;
        assertFalse(b.sprintRestockLegs, "sprint stays off until a supervised live trial");
        assertNull(SprintLegs.Rule.of(b));
        assertFalse(SprintLegs.allowed(null, 100, 20, 50));
    }

    @Test
    void ruleFromConfig() {
        MapArtConfig.BuildCfg b = new MapArtConfig().build;
        b.sprintRestockLegs = true;
        SprintLegs.Rule r = SprintLegs.Rule.of(b);
        assertNotNull(r);
        assertEquals(8, r.minFood());
        assertEquals(4.0, r.stopDistance());
        assertEquals(0, r.startTicks(), "sprint from the first step (no stale attribute update after the START)");
        // a hunger floor at or below Grim SprintA's 6 is raised to 7: the rule never sprints where SprintA flags
        b.sprintMinFood = 3;
        b.sprintStopDistance = -2;
        b.sprintStartTicks = -1;
        r = SprintLegs.Rule.of(b);
        assertEquals(7, r.minFood());
        assertEquals(0.0, r.stopDistance());
        assertEquals(0, r.startTicks());
    }

    @Test
    void sprintsOnlyMidLegWithFood() {
        assertTrue(SprintLegs.allowed(RULE, 5, 8, 4.0));
        assertTrue(SprintLegs.allowed(RULE, 200, 20, 120));
        // the walk's first ticks: a container just closed, a click just went out
        assertFalse(SprintLegs.allowed(RULE, 4, 20, 50));
        // near the goal: the bot arrives walking, the stand cell's brake and the click come after the quiet window
        assertFalse(SprintLegs.allowed(RULE, 50, 20, 3.99));
        // hunger
        assertFalse(SprintLegs.allowed(RULE, 50, 7, 50));
        assertFalse(SprintLegs.allowed(new SprintLegs.Rule(0, 0, 0), 50, 6, 50), "never at 6 or below (SprintA)");
        assertTrue(SprintLegs.allowed(new SprintLegs.Rule(0, 0, 0), 0, 7, 0));
        // unknown distance (not a GoalNearXZ)
        assertFalse(SprintLegs.allowed(RULE, 50, 20, Double.NaN));
    }

    @Test
    void distanceLeftToTheGoalCell() {
        GoalNearXZ exact = new GoalNearXZ(10, 174, 20, 0, 1);
        assertEquals(0.0, SprintLegs.distanceLeft(exact, 10.5, 20.5), 1e-9);
        assertEquals(5.0, SprintLegs.distanceLeft(exact, 13.5, 24.5), 1e-9);
        GoalNearXZ near = new GoalNearXZ(10, 174, 20, 2, 1);
        assertEquals(3.0, SprintLegs.distanceLeft(near, 13.5, 24.5), 1e-9);
        assertEquals(0.0, SprintLegs.distanceLeft(near, 11.5, 20.5), 1e-9);
    }

    @Test
    void watchKeepsClicksAwayFromSprinting() {
        SprintLegs.Watch w = new SprintLegs.Watch();
        w.quietTicks(5);
        assertTrue(w.quiet(false), "nothing ever sprinted");
        assertFalse(w.quiet(true), "sprinting now");
        w.tick(true);
        assertEquals(1, w.sprintTicks());
        for (int i = 1; i <= 4; i++) {
            w.tick(false);
            assertFalse(w.quiet(false), "tick " + i + " after the last sprinting tick");
        }
        w.tick(false);
        assertTrue(w.quiet(false), "5 ticks after");
        assertFalse(w.quiet(6, false));
        w.tick(true);
        assertFalse(w.quiet(false));
        assertEquals(2, w.sprintTicks());
        w.quietTicks(0);
        assertTrue(w.quiet(false));
        assertFalse(w.quiet(true));
    }

    @Test
    void onceStartsFromRestAndNeverRestarts() {
        MapArtConfig.BuildCfg b = new MapArtConfig().build;
        b.sprintRestockLegs = true;
        assertTrue(SprintLegs.Rule.of(b).once(), "one sprint per walk by default");
        SprintLegs.Once o = new SprintLegs.Once();
        assertTrue(o.allow(true, false, 10, 20), "at rest on the first block: may start");
        assertTrue(o.allow(true, true, 10, 20));
        assertTrue(o.allow(true, true, 11, 20), "sprinting: block changes do not matter");
        assertFalse(o.allow(true, false, 12, 20), "stopped (a wall bump): no restart");
        assertFalse(o.allow(true, false, 12, 20));
        assertTrue(o.ended());
        o.reset();
        assertTrue(o.allow(true, false, 12, 20), "a new walk starts over");
        assertFalse(o.allow(true, false, 13, 20), "the feet left their first block before the sprint started");
        assertFalse(o.allow(true, false, 12, 20), "late stays late, also back on the first block");
        assertTrue(o.late());
        o.reset();
        assertFalse(o.allow(false, false, 12, 20), "the rule's own no is kept");
    }

    // ------------------------------------------------------------------------------------------ sprint on walks

    @Test
    void sprintWalksIsOnByDefaultAndAKillSwitch() {
        MapArtConfig.BuildCfg b = new MapArtConfig().build;
        assertTrue(b.sprintWalks, "plain walks sprint by default (the owner: sprint everywhere)");
        assertTrue(SprintLegs.any(b));
        SprintLegs.Rule r = SprintLegs.rule(SprintLegs.Walk.STAND_SPOT, b, false, true);
        assertNotNull(r);
        assertEquals(new SprintLegs.Rule(8, 4.0, 0, true), r, "the restock legs' rule and settings");
        b.sprintWalks = false;
        assertFalse(SprintLegs.any(b), "both switches off: nothing sprints, the sync stays off");
        for (SprintLegs.Walk w : SprintLegs.Walk.values()) {
            if (w == SprintLegs.Walk.RETREAT) continue; // the creeper retreat has its own rule
            assertNull(SprintLegs.rule(w, b, false, true), w.name());
            assertNull(SprintLegs.rule(w, b, true, true), w + " in a restock without sprintRestockLegs");
        }
        b.sprintRestockLegs = true;
        assertNotNull(SprintLegs.rule(SprintLegs.Walk.CONTAINER, b, true, true), "the restock legs as before");
        assertNull(SprintLegs.rule(SprintLegs.Walk.STAND_SPOT, b, true, true), "a stand spot is no restock leg");
        assertNull(SprintLegs.rule(SprintLegs.Walk.CONTAINER, b, false, true), "a container outside a restock");
    }

    @Test
    void theCreeperRetreatSprintsWithSprintWalksOff() {
        MapArtConfig.BuildCfg b = new MapArtConfig().build;
        b.sprintWalks = false;
        b.sprintRestockLegs = false;
        SprintLegs.Rule r = SprintLegs.rule(SprintLegs.Walk.RETREAT, b, false, true);
        assertNotNull(r, "its own rule, whatever the switches say");
        assertNull(SprintLegs.rule(SprintLegs.Walk.CHASE, b, false, true), "the approach stays as it is");
        assertNull(SprintLegs.rule(SprintLegs.Walk.WALK, b, false, true));
        assertTrue(SprintLegs.allowed(r, 0, 20, 2.0), "from the first tick, close to the goal");
        assertFalse(SprintLegs.allowed(r, 0, 6, 9.0), "never at food 6 or below");
    }

    @Test
    void whichWalksSprintAndWhichNeverDo() {
        MapArtConfig.BuildCfg b = new MapArtConfig().build;
        Set<SprintLegs.Walk> plain = EnumSet.of(SprintLegs.Walk.LANE_START, SprintLegs.Walk.STAND_SPOT,
            SprintLegs.Walk.LANE_BREAK_OUT, SprintLegs.Walk.CONTAINER, SprintLegs.Walk.DROP_POINT,
            SprintLegs.Walk.BREAK_TRANSIT, SprintLegs.Walk.BUTTON, SprintLegs.Walk.PLACE, SprintLegs.Walk.MAP_STATION,
            SprintLegs.Walk.CART, SprintLegs.Walk.WALK, SprintLegs.Walk.ESCAPE, SprintLegs.Walk.CHASE,
            SprintLegs.Walk.RETREAT);
        Set<SprintLegs.Walk> never = EnumSet.of(SprintLegs.Walk.HOP_DOWN, SprintLegs.Walk.LANE_WALK,
            SprintLegs.Walk.NUKE_SWEEP);
        Set<SprintLegs.Walk> legs = EnumSet.of(SprintLegs.Walk.LANE_START, SprintLegs.Walk.CONTAINER,
            SprintLegs.Walk.DROP_POINT);
        assertTrue(Collections.disjoint(plain, never));
        Set<SprintLegs.Walk> all = EnumSet.copyOf(plain);
        all.addAll(never);
        assertEquals(EnumSet.allOf(SprintLegs.Walk.class), all, "every walk is classified");
        for (SprintLegs.Walk w : SprintLegs.Walk.values()) {
            assertEquals(plain.contains(w), w.plain(), w.name());
            assertEquals(legs.contains(w), w.restockLeg(), w.name());
        }
        for (boolean legsOn : new boolean[]{false, true}) {
            b.sprintRestockLegs = legsOn;
            for (boolean restock : new boolean[]{false, true}) {
                for (SprintLegs.Walk w : plain) {
                    // lane to lane has its own switch (theWalkFromLaneToLaneSprintsOnlyWithItsOwnSwitch)
                    if (w == SprintLegs.Walk.LANE_START && !restock) continue;
                    assertNotNull(SprintLegs.rule(w, b, restock, true), w + " sprints (restock " + restock + ")");
                    // the clear runs: only a restock leg (never in that phase anyway) keeps its own switch
                    boolean leg = restock && legsOn && legs.contains(w) || w == SprintLegs.Walk.RETREAT;
                    assertEquals(leg, SprintLegs.rule(w, b, restock, false) != null, w + " while the clear runs");
                }
                for (SprintLegs.Walk w : never) {
                    assertNull(SprintLegs.rule(w, b, restock, true), w + " never sprints");
                    assertNull(SprintLegs.rule(w, b, restock, false), w + " never sprints");
                }
            }
        }
    }

    @Test
    void theWalkFromLaneToLaneSprintsOnlyWithItsOwnSwitch() {
        MapArtConfig.BuildCfg b = new MapArtConfig().build;
        assertFalse(b.sprintWalksLaneStart, "off by default: set back at the lane start (live 2026-10-23)");
        assertNull(SprintLegs.rule(SprintLegs.Walk.LANE_START, b, false, true), "lane to lane");
        assertNotNull(SprintLegs.rule(SprintLegs.Walk.LANE_START, b, true, true), "back to a lane after a restock");
        assertNotNull(SprintLegs.rule(SprintLegs.Walk.STAND_SPOT, b, false, true), "the other plain walks as before");
        b.sprintRestockLegs = true;
        assertNotNull(SprintLegs.rule(SprintLegs.Walk.LANE_START, b, true, true), "the restock leg's own switch");
        assertNull(SprintLegs.rule(SprintLegs.Walk.LANE_START, b, false, true));
        b.sprintWalksLaneStart = true;
        assertNotNull(SprintLegs.rule(SprintLegs.Walk.LANE_START, b, false, true), "with the switch, as before");
        assertNull(SprintLegs.rule(SprintLegs.Walk.LANE_START, b, false, false), "never while the clear runs");
        b.sprintWalks = false;
        assertNull(SprintLegs.rule(SprintLegs.Walk.LANE_START, b, false, true), "sprintWalks off: no plain walk sprints");
    }

    /** Vanilla ground speeds, blocks per tick: walking, sprinting. */
    private static final double WALK = 0.2158;
    private static final double SPRINT = 0.2806;

    /**
     * A walk of the new kinds, tick by tick as the module runs it: the session watch ticks with the sprint state of the
     * last tick, the walker asks the rule ({@link SprintLegs#allowed} with the distance left to the goal) and the
     * one-sprint latch, the bot moves along x at sprint or walking speed. The walk ends when the task's arrival test
     * holds ({@code arrive}: horizontal distance from the feet to the target); then each tick the click goes out once
     * the watch is quiet. Returns {sprinting ticks, last sprinting tick, click tick}.
     */
    private static long[] walkThenClick(SprintLegs.Rule rule, GoalNearXZ goal, double target, double arrive) {
        SprintLegs.Watch w = new SprintLegs.Watch();
        w.quietTicks(5);
        SprintLegs.Once once = new SprintLegs.Once();
        double x = goal.x() + 0.5 + 30; // 30 blocks east of the goal
        double z = goal.z() + 0.5;
        boolean sprinting = false;
        long sprintTicks = 0;
        long last = -1;
        long t = 0;
        while (Math.abs(x - target) > arrive) {
            t++;
            w.tick(sprinting); // the module, before the engine: the state the bot's last tick sent
            boolean wanted = SprintLegs.allowed(rule, t - 1, 20, SprintLegs.distanceLeft(goal, x, z));
            sprinting = once.allow(wanted, sprinting, (int) Math.floor(x), (int) Math.floor(z));
            if (sprinting) {
                sprintTicks++;
                last = t;
            }
            x -= Math.min(sprinting ? SPRINT : WALK, Math.abs(x - target));
            assertTrue(t < 1000);
        }
        // arrived: the walker stopped (no sprint any more), the click waits for the quiet window
        while (true) {
            t++;
            w.tick(sprinting);
            sprinting = false;
            if (w.quiet(false)) return new long[]{sprintTicks, last, t};
            assertTrue(t < 2000);
        }
    }

    @Test
    void theNewWalksSprintAndTheirClicksWaitTheQuietWindow() {
        MapArtConfig.BuildCfg b = new MapArtConfig().build;
        SprintLegs.Rule rule = SprintLegs.rule(SprintLegs.Walk.STAND_SPOT, b, false, true);
        assertNotNull(rule);
        record Case(String what, GoalNearXZ goal, double target, double arrive) { }
        List<Case> cases = List.of(
            // a lane's stand spot: the exact cell, centred, then the placement
            new Case("stand spot", new GoalNearXZ(0, 64, 0, 0, 2), 0.5, 0.15),
            // a container's stand spot (scans, withdrawals, deposits outside a restock), then the open
            new Case("container", new GoalNearXZ(0, 64, 0, 0, 0), 0.5, 0.15),
            // the nuke's approach to a target: within 3, 2, 1 blocks, arrived once a face is in reach (4.5)
            new Case("nuke approach", new GoalNearXZ(0, 64, 0, 3, 2), 0.5, 4.5),
            // a button (the clear, the map counter): within 3 blocks of its stand cell, then the press
            new Case("button", new GoalNearXZ(0, 64, 0, 3, 3), 0.5, 3.0),
            // the map station: its exact stand cell, then the map's use
            new Case("map station", new GoalNearXZ(0, 64, 0, 0, 1), 0.5, 0.15));
        for (Case c : cases) {
            long[] r = walkThenClick(rule, c.goal(), c.target(), c.arrive());
            assertTrue(r[0] > 0, c.what() + ": a 30-block walk sprints");
            assertTrue(r[2] - r[1] >= b.sprintQuietTicks, c.what() + ": the click " + (r[2] - r[1])
                + " ticks after the last sprinting tick");
        }
        // no stop distance: the bot arrives sprinting, and the click still waits the whole window
        SprintLegs.Rule toTheGoal = new SprintLegs.Rule(8, 0.0, 0, true);
        for (Case c : cases) {
            long[] r = walkThenClick(toTheGoal, c.goal(), c.target(), c.arrive());
            assertTrue(r[0] > 0, c.what());
            assertTrue(r[2] - r[1] >= 5, c.what() + ": " + (r[2] - r[1]));
        }
    }

    @Test
    void theInReachGreedyWaitsTheQuietWindowOnASprintingLaneStartWalk() {
        SprintLegs.Watch w = new SprintLegs.Watch();
        w.quietTicks(5);
        assertTrue(SprintLegs.clickWhileWalking(true, false, w, false), "nothing sprinted: as before");
        assertFalse(SprintLegs.clickWhileWalking(false, false, w, false), "Baritone does not walk for the lane");
        assertFalse(SprintLegs.clickWhileWalking(true, true, w, false), "a container is open");
        for (int i = 0; i < 10; i++) {
            w.tick(true);
            assertFalse(SprintLegs.clickWhileWalking(true, false, w, true), "never while sprinting");
        }
        for (int i = 1; i <= 4; i++) {
            w.tick(false);
            assertFalse(SprintLegs.clickWhileWalking(true, false, w, false), i + " ticks after the sprint");
        }
        w.tick(false);
        assertTrue(SprintLegs.clickWhileWalking(true, false, w, false), "the quiet window is over");
    }

    @Test
    void aSprintTheWalkEndedMayRestartAfterTheQuietTicksOnlyWithTheSwitch() {
        for (int restart : new int[] {0, 10}) {
            SprintLegs.Once o = new SprintLegs.Once();
            long tick = 0;
            assertTrue(o.allow(true, false, 5, 5, tick++, restart), "from rest");
            assertTrue(o.allow(true, true, 5, 5, tick++, restart), "sprinting");
            assertTrue(o.allow(true, true, 6, 5, tick++, restart), "on a later block");
            assertFalse(o.allow(true, false, 7, 5, tick++, restart), "Baritone dropped the sprint: ended");
            for (int i = 0; i < 9; i++) assertFalse(o.allow(true, false, 8 + i, 5, tick++, restart), "inside the pause");
            boolean again = o.allow(true, false, 20, 5, tick++, restart);
            assertEquals(restart > 0, again, "after the pause: restart " + restart);
            if (restart > 0) {
                assertTrue(o.allow(true, true, 21, 5, tick++, restart), "sprinting again, not late");
                assertFalse(o.allow(true, false, 22, 5, tick++, restart), "ended again");
                assertFalse(o.allow(true, false, 23, 5, tick++, restart), "a new pause");
            }
        }
    }

    @Test
    void aLateWalkStartsAfterTheRestartPauseOnlyWithTheLateStartSwitch() {
        for (boolean lateStart : new boolean[] {false, true}) {
            SprintLegs.Once o = new SprintLegs.Once();
            long tick = 0;
            int restart = 12;
            assertFalse(o.allow(false, false, 5, 5, tick++, restart, lateStart), "the rule says no at first");
            assertFalse(o.allow(true, false, 6, 5, tick++, restart, lateStart), "the feet left the first block: late");
            assertTrue(o.late());
            for (int i = 0; i < 11; i++)
                assertFalse(o.allow(true, false, 7 + i, 5, tick++, restart, lateStart), "inside the pause " + i);
            boolean start = o.allow(true, false, 20, 5, tick++, restart, lateStart);
            assertEquals(lateStart, start, "after the pause, lateStart " + lateStart);
            if (lateStart) {
                assertTrue(o.allow(true, true, 21, 5, tick++, restart, true), "sprinting");
                assertTrue(o.lateStarted());
                assertFalse(o.allow(true, false, 22, 5, tick++, restart, true), "stopped: ended");
                for (int i = 0; i < 11; i++) assertFalse(o.allow(true, false, 23 + i, 5, tick++, restart, true));
                assertTrue(o.allow(true, false, 40, 5, tick++, restart, true), "an ordinary restart after it");
            } else {
                assertFalse(o.lateStarted());
                for (int i = 0; i < 40; i++)
                    assertFalse(o.allow(true, false, 21 + i, 5, tick++, restart, false), "late stays late");
            }
        }
    }

    @Test
    void lateStartNeedsTheRestartPause() {
        SprintLegs.Once o = new SprintLegs.Once();
        long tick = 0;
        assertFalse(o.allow(false, false, 5, 5, tick++, 0, true));
        for (int i = 0; i < 40; i++)
            assertFalse(o.allow(true, false, 6 + i, 5, tick++, 0, true), "restart 0: never, whatever lateStart says");
        assertFalse(o.lateStarted());
    }

    @Test
    void aLateWalkThatSprintedAnywayStaysLateWithoutTheSwitch() {
        // as before the switch existed: a sprint the Bot started on its own after the feet left the first block, then
        // ended, is not restarted (the latch said late first)
        SprintLegs.Once o = new SprintLegs.Once();
        long tick = 0;
        assertFalse(o.allow(false, false, 5, 5, tick++, 12, false));
        assertFalse(o.allow(true, false, 6, 5, tick++, 12, false));
        assertFalse(o.allow(true, true, 7, 5, tick++, 12, false));
        assertFalse(o.allow(true, false, 8, 5, tick++, 12, false));
        for (int i = 0; i < 30; i++) assertFalse(o.allow(true, false, 9 + i, 5, tick++, 12, false));
    }

    @Test
    void theLateStartSwitchIsInTheRuleAndOffByDefault() {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.sprintRestockLegs = true;
        assertFalse(SprintLegs.Rule.of(b).lateStart(), "off by default: anticheat-sensitive");
        b.sprintLateStart = true;
        assertTrue(SprintLegs.Rule.of(b).lateStart());
    }

    @Test
    void theRestartPauseIsInTheRule() {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.sprintRestockLegs = true;
        assertEquals(0, SprintLegs.Rule.of(b).restartTicks(), "off by default");
        b.sprintRestartTicks = 12;
        assertEquals(12, SprintLegs.Rule.of(b).restartTicks());
    }
}
