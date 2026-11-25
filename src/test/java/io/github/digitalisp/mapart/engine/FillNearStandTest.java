package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FILL_MAP's stand and hold: {@code map.fillNearStand} fills the map from the
 * covering cell on the way to the cartography table instead of walking to the station first; {@code map.holdUntilMatch}
 * ends the hold once the map shows the art. The Sim's soul tile: x 192..319, z -64..63, art y 64, station at its
 * centre; the table lies far off it.
 */
class FillNearStandTest {
    private static final int LONG = 60_000;
    private static final PlatformGeometry SOUL = new PlatformGeometry(192, -64, 64);
    private static final Pos STATION = new Pos(256, 64, 0);

    /** A Sim with {@code map.fillNearStand} on (default off). */
    private static Sim nearSim() {
        Sim sim = new Sim();
        sim.config.map.fillNearStand = true;
        return sim;
    }

    @Test
    void offByDefault() {
        Sim sim = new Sim();
        runJob(sim, "j0100");
        assertEquals(List.of(STATION), fillStands(sim));
        assertNull(sim.bot.lastHold.nearStand());
        assertNotNull(sim.bot.lastHold.shows(), "map.holdUntilMatch stays on by default");
    }

    /** A finished one-tile soul job; returns where the bot stood when FILL_MAP began. */
    private static double[] runJob(Sim sim, String id) {
        TileArt art = Sim.stripes(6, 0);
        Job job = sim.addJob(id, "Fill", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        AtomicReference<double[]> atFill = new AtomicReference<>();
        assertTrue(sim.runUntil(() -> {
            if (atFill.get() == null && sim.phase() == Phase.FILL_MAP) atFill.set(new double[]{sim.world.x, sim.world.z});
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(1, sim.output().maps.size(), "the map was stored");
        assertNotNull(atFill.get());
        return atFill.get();
    }

    private static List<Pos> fillStands(Sim sim) {
        List<Pos> out = new ArrayList<>();
        for (String h : sim.bot.history)
            if (h.startsWith("createMap [")) out.add(Pos.parseKey(h.substring("createMap [".length(), h.length() - 1)));
        return out;
    }

    @Test
    void theMapIsFilledOnTheWayToTheTable() {
        Sim sim = nearSim();
        double[] from = runJob(sim, "j0101");
        List<Pos> made = fillStands(sim);
        assertEquals(1, made.size(), sim.bot.history.toString());
        Pos c = made.getFirst();
        assertEquals(SOUL.artY(), c.y());
        assertTrue(FillStand.covers(c.x() - SOUL.minX(), c.z() - SOUL.minZ(), FillStand.MARGIN), "covers: " + c);
        Pos table = sim.config.site.cartographyTable;
        double via = Math.hypot(c.x() + 0.5 - from[0], c.z() + 0.5 - from[1]) + Math.hypot(table.x() - c.x(), table.z() - c.z());
        double viaStation = Math.hypot(STATION.x() + 0.5 - from[0], STATION.z() + 0.5 - from[1])
            + Math.hypot(table.x() - STATION.x(), table.z() - STATION.z());
        assertTrue(via <= viaStation + 1e-6, "via " + c + " " + via + " vs the station " + viaStation);
        assertEquals(c, sim.bot.lastHold.nearStand());
        assertNotNull(sim.bot.lastHold.shows(), "map.holdUntilMatch on by default");
        assertEquals(BuildEngine.HOLD_MATCH_MIN_TICKS, sim.bot.lastHold.minTicks());
    }

    @Test
    void killSwitchesFillAtTheStationForTheFullHold() {
        Sim sim = nearSim();
        sim.config.map.fillNearStand = false;
        sim.config.map.holdUntilMatch = false;
        runJob(sim, "j0102");
        assertEquals(List.of(STATION), fillStands(sim));
        assertNull(sim.bot.lastHold.nearStand());
        assertNull(sim.bot.lastHold.shows());
    }

    @Test
    void anUnreachableNearStandFallsBackToTheStation() {
        Sim sim = nearSim();
        sim.bot.nearStandUnreachable = true;
        runJob(sim, "j0103");
        assertNotNull(sim.bot.lastHold.nearStand(), "a near stand was offered");
        assertEquals(List.of(STATION), fillStands(sim));
    }

    @Test
    void aShortViewDistanceKeepsEveryCornerInReach() {
        Sim sim = nearSim();
        sim.world.viewDistance = 8; // 7 chunks spare: 112 blocks
        runJob(sim, "j0104");
        Pos c = sim.bot.lastHold.nearStand();
        assertNotNull(c);
        assertTrue(FillStand.farthestCorner(SOUL, c) <= 112, c + " " + FillStand.farthestCorner(SOUL, c));
    }

    @Test
    void anUnknownViewDistanceFillsAtTheStation() {
        Sim sim = nearSim();
        sim.world.viewDistance = 0;
        runJob(sim, "j0105");
        assertNull(sim.bot.lastHold.nearStand());
        assertEquals(List.of(STATION), fillStands(sim));
    }

    @Test
    void aRefillAfterAMismatchGoesToTheStation() {
        Sim sim = nearSim();
        AtomicBoolean first = new AtomicBoolean(true);
        // the first fill shows nothing (as if its pixels never came): CHECK_MAP mismatches, VERIFY, FILL_MAP again
        sim.bot.mapRender = p -> first.getAndSet(false) ? new byte[128 * 128] : sim.bot.render(p);
        runJob(sim, "j0106");
        List<Pos> made = fillStands(sim);
        assertEquals(1, made.size(), sim.bot.history.toString());
        assertNotEquals(STATION, made.getFirst(), "the first fill at the near stand");
        assertNull(sim.bot.lastHold.nearStand(), "the refill at the station");
        assertEquals(STATION, sim.bot.heldAt);
    }
}
