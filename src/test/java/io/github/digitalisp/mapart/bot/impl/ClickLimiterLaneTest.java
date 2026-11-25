package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.LaneSim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A click the server's click limiter dropped (Paper's spam-limiter: never handled, so no block updates for it and no
 * ack of its own; a later click's ack covers its sequence) does not use up one of the cell's {@link LaneRun#MAX_SENDS};
 * a click the server handled and refused (block updates, then the ack) and one an anti-cheat cancelled in front of
 * the server (Grim: an ack of exactly its sequence, no block updates) do. Live gate C at 10x: 8,935 of 25,357 clicks
 * counted as rejected, 3,309 cells skipped after two of them.
 */
class ClickLimiterLaneTest {
    private static final String WHITE = "minecraft:white_carpet";
    private static final int MIN_X = -64;
    private static final int MIN_Z = -64;
    private static final int ART_Y = 64;
    /** The cell whose clicks the tests drop, refuse or cancel: mid-lane, so later clicks' acks cover its sequences. */
    private static final Pos TARGET = new Pos(-60, ART_Y, -59);

    private final Map<Pos, BlockSpec> world = new HashMap<>();
    private final LaneSim sim = sim();

    private LaneSim sim() {
        Map<String, Integer> stock = new HashMap<>(Map.of(WHITE, 1000));
        LaneSim s = new LaneSim(new LaneSim.Blocks() {
            @Override
            public BlockSpec get(Pos p) {
                return world.getOrDefault(p, BlockSpec.AIR);
            }

            @Override
            public void set(Pos p, BlockSpec b) {
                world.put(p, b);
            }
        }, new LaneSim.Items() {
            @Override
            public int count(String item) {
                return stock.getOrDefault(item, 0);
            }

            @Override
            public boolean take(String item) {
                int n = stock.getOrDefault(item, 0);
                if (n <= 0) return false;
                stock.put(item, n - 1);
                return true;
            }
        }, MIN_X, MIN_Z, ART_Y, new AdaptivePacer(1, 1, 8), 5);
        s.confirmDelay = 5; // ack round trip of 5 ticks
        return s;
    }

    /** A 5-wide lane at x = -60 (columns -62..-58), rows -62..-55, all white carpet. */
    private static Lane lane() {
        List<Placement> ps = new ArrayList<>();
        for (int z = -62; z <= -55; z++) {
            for (int x = -62; x <= -58; x++) ps.add(new Placement(new Pos(x, ART_Y, z), BlockSpec.of(WHITE), WHITE));
        }
        return new Lane(0, new Pos(-60, ART_Y, -63), new Pos(-60, ART_Y, -54), List.copyOf(ps));
    }

    private static final PlacementListener NONE = new PlacementListener() {
        @Override public void placed(Placement placement) { }
        @Override public void skipped(Placement placement, String reason) { }
    };

    /** Runs the lane in {@code mode} (AIRPLACE: the clicked block is the cell; LOOK: the support below it). */
    private String run(String mode) {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.placeMode = mode;
        sim.silent = PlaceMode.silentRotation(b);
        sim.teleport(-59.5, ART_Y, -63.5);
        sim.runLane(lane(), LaneRun.Params.of(b, sim.tileArea()), NONE, 6000);
        return "clicks=" + sim.clicks + " accepted=" + sim.accepted + " drops=" + sim.limiterDrops + " refusals="
            + sim.refusals + " cancels=" + sim.anticheatCancels + " skips=" + sim.skipReasons + "\n"
            + String.join("\n", sim.log);
    }

    /** The first {@code n} clicks on {@link #TARGET}. */
    private static Predicate<Pos> firstClicksOnTarget(int n) {
        int[] k = {0};
        return p -> p.equals(TARGET) && k[0]++ < n;
    }

    @ParameterizedTest
    @ValueSource(strings = {"AIRPLACE", "LOOK"})
    void droppedClicksDoNotUseUpTheCellsSends(String mode) {
        sim.echoes = true;
        sim.limiter = firstClicksOnTarget(LaneRun.MAX_SENDS + 1);
        String why = run(mode);
        assertEquals(LaneRun.MAX_SENDS + 1, sim.limiterDrops, why);
        assertEquals(BlockSpec.of(WHITE), world.get(TARGET), "placed on the click after three drops\n" + why);
        assertTrue(sim.skipReasons.isEmpty(), why);
        assertEquals(40, sim.accepted, why);
        // a drop whose click is the last one sent (from a stand spot) has no later ack to cover it: unknown, as before
        assertTrue(count(" dropped,") >= LaneRun.MAX_SENDS, why);
        assertEquals(0, count(" rejected,"), why);
        assertEquals(0, sim.pacer.rejections(), "classified drops are not refusals\n" + why);
        assertTrue(sim.pacer.drops() >= LaneRun.MAX_SENDS, why);
    }

    /** The lane line's count before {@code what} (e.g. " dropped,"), -1 without a lane line. */
    private int count(String what) {
        for (String l : sim.log) {
            var m = java.util.regex.Pattern.compile("(\\d+)" + java.util.regex.Pattern.quote(what)).matcher(l);
            if (l.contains("; clicks ") && m.find()) return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    @Test
    void withoutBlockUpdatesWatchedTheSameDropsUseUpTheSends() {
        sim.limiter = firstClicksOnTarget(LaneRun.MAX_SENDS); // echoes off: an acked click without its block is rejected
        String why = run("AIRPLACE");
        assertEquals(LaneRun.MAX_SENDS, sim.limiterDrops, why);
        assertEquals(Map.of("rejected by the server # times", 1), sim.skipReasons, why);
        assertEquals(39, sim.accepted, why);
    }

    @Test
    void clicksTheServerHandledAndRefusedUseUpTheSends() {
        sim.echoes = true;
        sim.refuse = TARGET::equals;
        String why = run("AIRPLACE");
        assertEquals(LaneRun.MAX_SENDS, sim.refusals, why);
        assertEquals(Map.of("rejected by the server # times", 1), sim.skipReasons, why);
        assertEquals(LaneRun.MAX_SENDS, count(" rejected,"), why);
        assertEquals(0, count(" dropped,"), why);
    }

    @Test
    void anticheatCancelsAckedExactlyUseUpTheSends() {
        sim.echoes = true;
        sim.anticheat = TARGET::equals;
        String why = run("LOOK");
        assertEquals(LaneRun.MAX_SENDS, sim.anticheatCancels, why);
        assertEquals(Map.of("rejected by the server # times", 1), sim.skipReasons, why);
    }

    @Test
    void dropsBeyondTheirAllowanceCountAsSends() {
        // handled without block updates and acked only up to a later sequence (vanilla: out of interaction range, not
        // the last click of its server tick): it looks dropped every time; the cell is given up after the allowance
        sim.echoes = true;
        sim.unanswered = TARGET::equals;
        String why = run("AIRPLACE");
        assertEquals(LaneRun.MAX_SENDS + LaneRun.MAX_DROPS, sim.unansweredClicks, why);
        assertEquals(Map.of("dropped by the server (acked without an answer) # times", 1), sim.skipReasons, why);
        assertEquals(39, sim.accepted, why);
    }

    @Test
    void aDropNoLaterClickCoversWaitsForTheWindowAsBefore() {
        // the cell's clicks are all dropped and nothing handled follows them (the last cells, from a stand spot): no
        // ack ever covers them, so they end unknown at the window's cap and count as sends, as before
        sim.echoes = true;
        sim.limiter = TARGET::equals;
        String why = run("AIRPLACE");
        assertEquals(39, sim.accepted, why);
        assertEquals(1, sim.skipReasons.values().stream().mapToInt(Integer::intValue).sum(), why);
        assertTrue(sim.limiterDrops > LaneRun.MAX_SENDS, why);
        assertTrue(sim.limiterDrops <= LaneRun.MAX_SENDS + LaneRun.MAX_DROPS, why);
    }
}
