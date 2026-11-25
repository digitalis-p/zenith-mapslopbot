package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The soul sand platform as built on 2026-10-11: the clear buttons sit on a cross
 * at feet art + 6, reached by a one-way walkway; the map is made on the floor below it ({@code mapStandAt}), the clear
 * is waited out ON the cross (not on the floor under it, which is in the sweep), and afterwards the bot hops down to
 * the floor ({@code hopDownTo}), only with health to spare.
 */
class SoulSandCrossTest {
    private static final int LONG = 60_000;
    /** The Sim's soul sand tile: x 192..319, z -64..63, art y 64; the cross 6 above the art layer at the centre. */
    private static final Pos CROSS = new Pos(256, 70, 0);
    private static final Pos FLOOR_CENTRE = new Pos(256, 64, 0);
    private static final Pos LANDING = new Pos(256, 64, -3);

    private static Sim soulSim() {
        Sim sim = new Sim();
        MapArtConfig.PlatformCfg soul = sim.soul;
        soul.center = CROSS;
        soul.mapStandAt = FLOOR_CENTRE;
        soul.hopDownTo = LANDING;
        soul.clear.getFirst().standAt = CROSS;
        return sim;
    }

    @Test
    void mapOnTheFloorClearWaitedOnTheCrossThenHopDown() {
        Sim sim = soulSim();
        sim.config.map.fillNearStand = true; // the near stand too stays on the floor, never on the cross
        // terracotta rows: the NUKE breaks them, then walks up to the cross for the clear; no hop until the clear is over
        // (local 10x run 2026-10-11: the raised-stand hop fired right after that walk, three times, then BLOCKED)
        TileArt art = Sim.stripes(6, 2);
        Job job = sim.addJob("j0002", "Soul", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        AtomicBoolean onFloorDuringClear = new AtomicBoolean();
        AtomicInteger waitTicks = new AtomicInteger();
        assertTrue(sim.runUntil(() -> {
            if (sim.phase() == Phase.WAIT_CLEAR && sim.bot.lastTask != null && sim.bot.lastTask.isFinished()) {
                waitTicks.incrementAndGet();
                if (sim.world.y < CROSS.y() - 1) onFloorDuringClear.set(true);
            }
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(1, sim.output().maps.size(), "the map (made at the floor, so it shows the art) was stored");
        Pos made = createdAt(sim);
        assertEquals(FLOOR_CENTRE.y(), made.y(), "made on the floor (map.fillNearStand: a covering cell of it): " + made);
        assertTrue(FillStand.covers(made.x() - 192, made.z() + 64, FillStand.MARGIN), "covers the whole tile: " + made);
        assertFalse(sim.bot.history.contains("createMap " + CROSS), "never made on the cross");
        assertTrue(waitTicks.get() > 0);
        assertFalse(onFloorDuringClear.get(), "waited on the cross while the machines ran");
        assertEquals(List.of(LANDING), sim.bot.hops, "hopped down once, after the clear");
        int press = indexOf(sim.bot.history, "use ");
        int hop = indexOf(sim.bot.history, "hopDown ");
        assertTrue(press >= 0 && hop > press, sim.bot.history.toString());
        assertEquals(0, sim.artBlocks(sim.soul));
        assertEquals(0, sim.machineBroken);
    }

    @Test
    void lowHealthStaysOnTheCrossUntilItCanHop() {
        Sim sim = soulSim();
        TileArt art = Sim.stripes(4, 0);
        Job job = sim.addJob("j0003", "Soul", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.WAIT_CLEAR, LONG), sim.state());
        sim.inv.health = 12f; // below hopDownMinHealth 14, above the low-health stop
        sim.inv.foodLevel = 20;
        sim.bot.regenTicks = 0;
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.FINISH_TILE || sim.phase() == Phase.SELECT
            || sim.phase() == Phase.IDLE, LONG), sim.state());
        sim.run(400);
        assertTrue(sim.bot.hops.isEmpty(), "no hop below hopDownMinHealth: " + sim.bot.history);
        assertEquals(CROSS.y(), (int) sim.world.y, "stays on the cross");
        sim.inv.health = 20f;
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(List.of(LANDING), sim.bot.hops);
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void withoutHopDownToNothingChanges() {
        Sim sim = new Sim(); // the soul centre at the art layer (as before): no hop, no raised stand
        TileArt art = Sim.stripes(4, 0);
        Job job = sim.addJob("j0004", "Soul", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.bot.hops.isEmpty());
        assertEquals(64, createdAt(sim).y(), sim.bot.history.toString());
    }

    @Test
    void fillNearStandOffMakesTheMapAtTheStation() {
        Sim sim = soulSim();
        sim.config.map.fillNearStand = false;
        TileArt art = Sim.stripes(4, 0);
        Job job = sim.addJob("j0007", "Soul", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(FLOOR_CENTRE, createdAt(sim), sim.bot.history.toString());
    }

    /** Where the first map was made (the FakeBot's "createMap [x,y,z]" history entry). */
    private static Pos createdAt(Sim sim) {
        for (String h : sim.bot.history) {
            if (h.startsWith("createMap [")) return Pos.parseKey(h.substring("createMap [".length(), h.length() - 1));
        }
        throw new AssertionError("no map made: " + sim.bot.history);
    }

    /** The platform's chunks load only with the bot on that side (the supply chests are far from the tile). */
    private static void farSupply(Sim sim) {
        sim.world.loaded = p -> p.x() < 192 || p.x() > 319 || sim.world.x > 100;
    }

    /** History entries from the last STORE's deposit to the clear press. */
    private static List<String> walksBeforePress(Sim sim) {
        List<String> h = sim.bot.history;
        int press = indexOf(h, "use ");
        int store = -1;
        for (int i = 0; i < press; i++) if (h.get(i).startsWith("depositMap")) store = i;
        return h.subList(store + 1, press);
    }

    @Test
    void nukeWithNothingToBreakWalksStraightToTheClearStand() {
        Sim sim = soulSim();
        farSupply(sim);
        TileArt art = Sim.stripes(6, 0);
        Job job = sim.addJob("j0005", "Soul", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        List<String> after = walksBeforePress(sim);
        assertTrue(after.contains("walkTo " + CROSS), after.toString());
        assertFalse(after.contains("walkTo " + FLOOR_CENTRE), "no walk to the floor first: " + after);
        assertEquals(0, sim.artBlocks(sim.soul));
    }

    @Test
    void killSwitchWalksToTheMapStationFirst() {
        Sim sim = soulSim();
        farSupply(sim);
        sim.config.build.nukeLoadFromCentre = false;
        TileArt art = Sim.stripes(6, 0);
        Job job = sim.addJob("j0006", "Soul", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        List<String> after = walksBeforePress(sim);
        assertTrue(after.indexOf("walkTo " + FLOOR_CENTRE) >= 0, after.toString());
    }

    private static int indexOf(List<String> h, String prefix) {
        int last = -1;
        for (int i = 0; i < h.size(); i++) if (h.get(i).startsWith(prefix)) last = i;
        return last;
    }
}
