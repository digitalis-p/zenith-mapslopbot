package io.github.digitalisp.mapart.bot.impl;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Airplace and the stack guard ({@code LaneRun.usedUp}, {@link PlaceTracker}): an airplace is a click like a LOOK one,
 * with its block prediction sequence and the hotbar slot its stack lives in, so a stack the cache still shows full
 * (counts one round trip late, 2b2t) is not clicked once the clicks in flight have used it up. Without that, airplace
 * clicks with an empty hand would be sent (the 2b2t "misses": 70 of 71 were used-up stacks).
 */
class AirPlaceStackGuardTest {
    private static final String WHITE = "minecraft:white_carpet";
    private static final int MIN_X = -64;
    private static final int MIN_Z = -64;
    private static final int ART_Y = 64;

    private static LaneSim sim(Map<String, Integer> stock, Map<Pos, BlockSpec> world) {
        LaneSim sim = new LaneSim(new LaneSim.Blocks() {
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
        sim.confirmDelay = 5; // ack round trip of 5 ticks
        sim.countLag = 5;     // the cache shows the counts as they were before the clicks in flight
        return sim;
    }

    /** A 9-wide lane at x = -60 (columns -64..-56), rows -62..-55, all white carpet. */
    private static Lane carpetLane() {
        List<Placement> ps = new ArrayList<>();
        for (int z = -62; z <= -55; z++) {
            for (int x = -64; x <= -56; x++) ps.add(new Placement(new Pos(x, ART_Y, z), BlockSpec.of(WHITE), WHITE));
        }
        return new Lane(0, new Pos(-60, ART_Y, -63), new Pos(-60, ART_Y, -54), List.copyOf(ps));
    }

    private static LaneRun.Params airplace(LaneSim sim) {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg(); // the defaults: AIRPLACE, silent looks
        sim.silent = PlaceMode.silentRotation(b);
        return LaneRun.Params.of(b, sim.tileArea());
    }

    private static final PlacementListener NONE = new PlacementListener() {
        @Override public void placed(Placement placement) { }
        @Override public void skipped(Placement placement, String reason) { }
    };

    @Test
    void airplaceNeverClicksAStackUsedUpByClicksInFlight() {
        Map<String, Integer> stock = new HashMap<>(Map.of(WHITE, 5));
        Map<Pos, BlockSpec> world = new HashMap<>();
        LaneSim sim = sim(stock, world);
        sim.teleport(-59.5, ART_Y, -63.5);
        sim.runLane(carpetLane(), airplace(sim), NONE, 4000);
        String why = "clicks=" + sim.clicks + " airPlaces=" + sim.airPlaces + " wrong=" + sim.wrongClicks + " skips="
            + sim.skipReasons + "\n" + String.join("\n", sim.log);
        assertEquals(0, sim.wrongClicks, "an airplace with an empty hand\n" + why);
        assertEquals(5, sim.accepted, why);
        assertEquals(5, sim.airPlaces, "exactly the stack: no click beyond it while the cache still showed it\n" + why);
        assertEquals(1, sim.maxPlacementsPerTick, why);
        assertTrue(sim.skipReasons.keySet().stream().anyMatch(k -> k.contains("missing item")), why);
    }

    @Test
    void airplaceRunsAtFullSpeedWithEnoughStock() {
        Map<String, Integer> stock = new HashMap<>(Map.of(WHITE, 1000));
        Map<Pos, BlockSpec> world = new HashMap<>();
        LaneSim sim = sim(stock, world);
        sim.teleport(-59.5, ART_Y, -63.5);
        sim.runLane(carpetLane(), airplace(sim), NONE, 4000);
        String why = "ticks=" + sim.lastTicks + " clicks=" + sim.clicks + " skips=" + sim.skipReasons + "\n"
            + String.join("\n", sim.log);
        assertEquals(72, sim.accepted, why);
        assertEquals(72, sim.airPlaces, why);
        assertEquals(0, sim.wrongClicks, why);
        assertEquals(0, sim.realTurns + sim.silentLooks, "no rotation packet for carpets\n" + why);
        assertTrue(sim.skipReasons.isEmpty(), why);
    }
}
