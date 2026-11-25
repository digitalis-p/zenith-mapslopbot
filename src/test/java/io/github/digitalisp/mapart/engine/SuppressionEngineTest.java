package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.SuppressionArt;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A colour-suppression job in the simulated site (docs/COLOR_SUPPRESSION.md): its tiles are build passes on the
 * platform's two glass floors, built in order, verified, and never followed by a map, a nuke or a clear.
 */
class SuppressionEngineTest {
    private static final int LONG = 60_000;

    static MapArtConfig.PlatformCfg csPlatform(Sim sim) {
        MapArtConfig.PlatformCfg cs = new MapArtConfig.PlatformCfg();
        cs.id = "cs";
        cs.kind = "COLOR_SUPPRESSION";
        cs.minX = -64;
        cs.minZ = -64;
        cs.artY = 101;
        cs.floorBlock = "minecraft:glass";
        cs.glassY = List.of(100, 114);
        cs.center = new Pos(0, 115, 0);
        MapArtConfig.RegionCfg access = new MapArtConfig.RegionCfg();
        access.min = new Pos(-70, 100, -80);
        access.max = new Pos(-60, 118, -66);
        access.name = "path";
        cs.access.add(access);
        sim.config.site.platforms.add(cs);
        // the two glass floors, as on the owner's platform (the bottom one from the noobline row, the top one a row further)
        for (int z = -66; z < 64; z++)
            for (int x = -64; x < 64; x++) {
                if (z >= -65) sim.world.set(new Pos(x, 100, z), BlockSpec.of("minecraft:glass"));
                sim.world.set(new Pos(x, 114, z), BlockSpec.of("minecraft:glass"));
            }
        return cs;
    }

    /** A few cells on every level of both layers, the noobline included. */
    static SuppressionArt art() {
        return art(false, false);
    }

    /**
     * {@link #art()}; with {@code topRows} / {@code bottomRows} each level of that layer has a noobline row of the item
     * its own map cells are (otherwise rows of an item the level does not place, as {@link #art()} has them).
     */
    static SuppressionArt art(boolean topRows, boolean bottomRows) {
        SuppressionArt a = new SuppressionArt();
        for (int col = 0; col < 24; col++)
            for (int row = 0; row < 6; row++) {
                if (SuppressionArt.dominant(col, row)) a.set(SuppressionArt.BOTTOM, 0, col, row, BlockSpec.of(Sim.WHITE));
                else if (col % 4 == 0) a.set(SuppressionArt.BOTTOM, 1, col, row, BlockSpec.of(Sim.RED));
                else a.set(SuppressionArt.TOP, 0, col, row, BlockSpec.of(Sim.BLACK));
                if (SuppressionArt.dominant(col, row) && row % 2 == 1) a.set(SuppressionArt.TOP, 1, col, row, BlockSpec.of(Sim.RED));
            }
        for (int col = 0; col < 8; col++) {
            // the levels' own items: bottom y+0 white, y+1 red; top y+0 black, y+1 red
            a.set(SuppressionArt.BOTTOM, col % 2, col, SuppressionArt.NOOBLINE,
                BlockSpec.of(!bottomRows ? Sim.BLACK : col % 2 == 0 ? Sim.WHITE : Sim.RED));
            a.set(SuppressionArt.TOP, col % 2, col + 8, SuppressionArt.NOOBLINE,
                BlockSpec.of(!topRows ? Sim.WHITE : col % 2 == 0 ? Sim.BLACK : Sim.RED));
        }
        return a;
    }

    /** The pass list of a job made from {@code a}'s passes, in their order (as an upload stores it). */
    static List<Job.SuppressionPass> passList(SuppressionArt a) {
        List<Job.SuppressionPass> out = new ArrayList<>();
        for (SuppressionArt.Pass p : a.passes()) out.add(new Job.SuppressionPass(p.layer(), p.level(), p.noobline()));
        return out;
    }

    static Job addJob(Sim sim, SuppressionArt a) {
        List<SuppressionArt.Pass> passes = a.passes();
        Job job = sim.addJob("j0001", "cs", passes.size(), 1, List.of("cs"), (x, z) -> passes.get(x).art());
        job.suppression = new ArrayList<>();
        for (SuppressionArt.Pass p : passes) job.suppression.add(new Job.SuppressionPass(p.layer(), p.level(), p.noobline()));
        return job;
    }

    @Test
    void passesAreBuiltInOrderAtTheirHeightsWithoutMapOrClear() {
        Sim sim = new Sim();
        MapArtConfig.PlatformCfg cs = csPlatform(sim);
        SuppressionArt a = art();
        Job job = addJob(sim, a);
        assertEquals(8, job.tiles.size());
        sim.start();
        List<Integer> order = new ArrayList<>();
        boolean[] openDuringBuild = {false};
        assertTrue(sim.runUntil(() -> {
            var st = sim.engine.status();
            if (st.jobId() != null && (order.isEmpty() || order.getLast() != st.tileX())) order.add(st.tileX());
            if (sim.phase() == Phase.BUILD && !sim.config.site.openings.isEmpty()) openDuringBuild[0] = true;
            assertTrue(sim.phase() != Phase.FILL_MAP && sim.phase() != Phase.CLEAR && sim.phase() != Phase.LOCK,
                "no map, no clear: " + sim.phase());
            return job.status == JobStatus.READY_FOR_RIDE;
        }, LONG), sim.state());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), order);
        assertTrue(job.status.terminal());
        for (Job.TileState t : job.tiles) assertEquals(TileStatus.STORED, t.status);
        for (int layer = 0; layer < 2; layer++)
            for (int level = 0; level < 2; level++)
                for (int row = -1; row < 128; row++)
                    for (int col = 0; col < 128; col++) {
                        BlockSpec want = a.get(layer, level, col, row);
                        BlockSpec have = sim.world.block(new Pos(cs.minX + col, cs.glassY.get(layer) + 1 + level, cs.minZ + row));
                        if (want == null) assertTrue(have.isAir(), "air at " + layer + "/" + level + " " + col + "," + row);
                        else assertEquals(want, have, layer + "/" + level + " " + col + "," + row);
                    }
        assertEquals(0, sim.bot.createdMaps, "no map for a color-suppression pass");
        assertEquals(0, sim.bot.count("use " + Sim.MOSS_BUTTON), "no clear pressed");
        assertTrue(openDuringBuild[0], "the zones opened while building");
        assertFalse(sim.notifier.has("info", "Job done"));
        // always on: the start of the map (its first pass) and one "ready for the ride" line naming it
        assertEquals(1, sim.notifier.messages.stream().filter(m -> m.title().startsWith("Ready for the ride: cs")).count(),
            sim.notifier.toString());
        assertEquals(1, sim.notifier.of("info").stream().filter(m -> m.title().startsWith("Started")).count(),
            sim.notifier.toString());
        // on Discord a pass has no number and no tile: the layer in words, the whole map's blocks (owner 2026-11-14)
        var startLine = sim.notifier.of("info").stream().filter(m -> m.title().startsWith("Started")).findFirst().orElseThrow();
        assertEquals("Started color-suppression map cs", startLine.title());
        assertTrue(startLine.body().matches("The (top|bottom) layer first, on platform cs: \\d+ blocks in the whole map\\."),
            startLine.body());
        for (var m : sim.notifier.messages)
            if (m.title().startsWith("Started") || m.title().startsWith("Color suppression"))
                assertFalse((m.title() + m.body()).contains("pass ") || (m.title() + m.body()).contains(",0]"), m.title() + " | " + m.body());
        // the walk never ended a lane at a lethal edge (the sim has nothing under the glass: every edge is lethal)
        var hz = io.github.digitalisp.mapart.bot.impl.NoGoZones.of(io.github.digitalisp.mapart.engine.EdgeHazards.compute(cs,
            new EdgeHazards.Blocks() {
                @Override public boolean loaded(int x, int y, int z) { return true; }
                @Override public BlockSpec block(int x, int y, int z) { return sim.world.block(new Pos(x, y, z)); }
            }).zones());
        for (var l : sim.bot.lanesRun) {
            assertTrue(hz.at(l.from()) == null, "lane " + l.index() + " starts at an edge: " + l.from());
            assertTrue(hz.at(l.to()) == null, "lane " + l.index() + " ends at an edge: " + l.to());
        }
    }

    /**
     * With an {@code entry} the bot walks up the access path whenever it starts a round below the glass; that walk
     * must not use up the lane it was started for (local CS run 2026-10-14: a round from the stair's foot ran its one
     * lane "0 placed" and went back to RESTOCK, 31 cells per round).
     */
    @Test
    void theWalkUpTheAccessPathDoesNotUseUpALane() {
        int[] without = restockRounds(null);
        int[] with = restockRounds(new Pos(-65, 101, -75));
        // the same cells placed, in no more restock rounds (a lane used up by the walk comes back as a repair round)
        assertTrue(with[0] <= without[0], "restock rounds with an entry " + with[0] + ", without " + without[0]);
    }

    /** {rounds into RESTOCK} of a whole job in the sim, with {@code entry} on the platform. */
    private static int[] restockRounds(Pos entry) {
        Sim sim = new Sim();
        MapArtConfig.PlatformCfg cs = csPlatform(sim);
        cs.entry = entry;
        Job job = addJob(sim, art());
        sim.start();
        int[] rounds = {0};
        Phase[] last = {null};
        assertTrue(sim.runUntil(() -> {
            if (sim.phase() == Phase.RESTOCK && last[0] != Phase.RESTOCK) rounds[0]++;
            last[0] = sim.phase();
            return job.status == JobStatus.READY_FOR_RIDE;
        }, LONG), sim.state());
        return rounds;
    }

    @Test
    void aWrongMossBlockInTheArtCellsIsRepaired() {
        Sim sim = new Sim();
        MapArtConfig.PlatformCfg cs = csPlatform(sim);
        SuppressionArt a = art();
        Job job = addJob(sim, a);
        Pos cell = new Pos(cs.minX + 1, 101, cs.minZ); // a dominant pixel of the bottom layer: white wanted
        boolean[] once = {true};
        sim.bot.placeOverride = p -> {
            if (p.pos().equals(cell) && once[0]) {
                once[0] = false;
                return new io.github.digitalisp.mapart.model.Placement(p.pos(), BlockSpec.of("minecraft:moss_block"), p.itemId());
            }
            return null;
        };
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.READY_FOR_RIDE || sim.phase() == Phase.BLOCKED, LONG),
            sim.state());
        assertEquals(JobStatus.READY_FOR_RIDE, job.status, sim.state());
        assertEquals(BlockSpec.of(Sim.WHITE), sim.world.block(cell));
        assertTrue(sim.config.site.repairable.isEmpty(), "repairs only while the job is the current one");
    }

    @Test
    void afterACancelTheZonesStayOpenAndTheEdgesClosedWhileTheBotIsUpThere() {
        Sim sim = new Sim();
        MapArtConfig.PlatformCfg cs = csPlatform(sim);
        Job job = addJob(sim, art());
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD && sim.engine.status().tileX() == 1, LONG), sim.state());
        assertFalse(sim.config.site.hazards.isEmpty(), "lethal edges known while building");
        assertTrue(sim.store.cancel(job.id));
        sim.engine.queueChanged();
        sim.world.moveTo(new Pos(0, 101, 0)); // still on the bottom glass
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.IDLE, LONG), sim.state());
        sim.run(40);
        assertFalse(sim.config.site.openings.isEmpty(), "openings kept while the bot stands in them");
        assertFalse(sim.config.site.hazards.isEmpty(), "lethal edges kept with them");
        assertTrue(sim.config.site.repairable.isEmpty());
        assertTrue(sim.notifier.has("info", "Cancelled job cleaned up"), sim.notifier.toString());
        sim.world.moveTo(new Pos(0, 64, 100)); // down at the chests
        sim.run(400);
        assertTrue(sim.config.site.openings.isEmpty() && sim.config.site.hazards.isEmpty(), sim.state());
    }

    @Test
    void aBotFoundOnTheLayersWithoutAJobGetsTheOpeningsAndEdgesUntilItIsDown() {
        Sim sim = new Sim();
        csPlatform(sim);
        sim.world.moveTo(new Pos(10, 115, 10)); // on the top glass, e.g. after a restart
        sim.start();
        sim.run(400);
        assertFalse(sim.config.site.openings.isEmpty(), sim.state());
        assertFalse(sim.config.site.hazards.isEmpty(), sim.state());
        assertTrue(sim.config.site.repairable.isEmpty());
        sim.world.moveTo(new Pos(0, 64, 100));
        sim.run(400);
        assertTrue(sim.config.site.openings.isEmpty() && sim.config.site.hazards.isEmpty(), sim.state());
    }

    @Test
    void laneEndsMoveOffTheLethalEdge() {
        List<io.github.digitalisp.mapart.model.Lane> lanes = List.of(
            new io.github.digitalisp.mapart.model.Lane(0, new Pos(4, 201, -64), new Pos(4, 201, 63), List.of()),
            new io.github.digitalisp.mapart.model.Lane(1, new Pos(13, 201, 63), new Pos(13, 201, -64), List.of()),
            new io.github.digitalisp.mapart.model.Lane(2, new Pos(22, 201, 63), new Pos(22, 201, 63), List.of()));
        var out = BuildEngine.clampLanes(lanes, p -> p.z() >= 63);
        assertEquals(new Pos(4, 201, 62), out.get(0).to());
        assertEquals(new Pos(4, 201, -64), out.get(0).from());
        assertEquals(new Pos(13, 201, 62), out.get(1).from());
        assertEquals(new Pos(22, 201, 62), out.get(2).from(), "a lane wholly at the edge starts beside it");
        var noob = BuildEngine.clampLanes(List.of(new io.github.digitalisp.mapart.model.Lane(14, new Pos(63, 201, -65),
            new Pos(63, 201, -65), List.of())), p -> p.x() >= 63 && p.z() <= -64);
        assertEquals(new Pos(62, 201, -65), noob.getFirst().from(), "the noobline pass's corner lane");
    }

    /**
     * The last column's single-cell lane at the south edge (local CS run 2026-10-14, lane 14's last cell): the east
     * neighbour lies in the machinery zone, so no cell beside it passes the strict test; the fallback takes the nearest
     * cell that is in no zone itself.
     */
    @Test
    void aCornerLaneEndFallsBackToTheNearestCellOutsideTheZones() {
        var corner = List.of(new io.github.digitalisp.mapart.model.Lane(14, new Pos(63, 201, 63), new Pos(63, 201, 63), List.of()));
        java.util.function.Predicate<Pos> zone = p -> p.z() >= 62 || p.x() >= 64;
        java.util.function.Predicate<Pos> strict = p -> zone.test(p) || zone.test(new Pos(p.x() + 1, p.y(), p.z()))
            || zone.test(new Pos(p.x() - 1, p.y(), p.z())) || zone.test(new Pos(p.x(), p.y(), p.z() + 1))
            || zone.test(new Pos(p.x(), p.y(), p.z() - 1));
        assertEquals(new Pos(63, 201, 63), BuildEngine.clampLanes(corner, strict).getFirst().from(), "stays without it");
        var out = BuildEngine.clampLanes(corner, strict, zone);
        assertEquals(new Pos(63, 201, 61), out.getFirst().from());
        assertEquals(new Pos(63, 201, 61), out.getFirst().to());
    }

    /** STOCK_CHECK blocks a job that uses a block outside the platform's allowed palette, naming it. */
    @Test
    void aBlockOutsideTheChestPaletteBlocksTheJobAtStockCheck() {
        Sim sim = new Sim();
        MapArtConfig.PlatformCfg cs = csPlatform(sim);
        cs.allowedBlocks = List.of(Sim.WHITE, Sim.BLACK); // the art also uses RED
        Job job = addJob(sim, art());
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        String note = sim.engine.status().note();
        assertTrue(note.contains("do not hold") && note.contains(Sim.RED), note);
        assertEquals(JobStatus.ACTIVE, job.status);
    }

    @Test
    void aPlatformThatIsNotEmptyBlocksAndIsNeverNuked() {
        Sim sim = new Sim();
        MapArtConfig.PlatformCfg cs = csPlatform(sim);
        Job job = addJob(sim, art());
        sim.world.set(new Pos(cs.minX + 100, 101, cs.minZ + 100), BlockSpec.of(Sim.LIME));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertTrue(sim.engine.status().note().contains("not reset"), sim.engine.status().note());
        assertEquals(BlockSpec.of(Sim.LIME), sim.world.block(new Pos(cs.minX + 100, 101, cs.minZ + 100)));
        assertEquals(JobStatus.ACTIVE, job.status);
    }

    /**
     * One print of {@link #art}: the passes in the order they ran, the restock rounds, the lanes, the ticks; the lanes
     * run while each pass was the current one.
     */
    private record Print(List<Integer> order, int rounds, int lanes, long ticks, Sim sim, Job job, MapArtConfig.PlatformCfg cs,
                         Map<Integer, Integer> lanesOf, List<Integer> stored) {
        Print(List<Integer> order, int rounds, int lanes, long ticks, Sim sim, Job job, MapArtConfig.PlatformCfg cs) {
            this(order, rounds, lanes, ticks, sim, job, cs, Map.of(), List.of());
        }
    }

    /** A print of the art whose noobline rows are their levels' own items (every level's row can go with its main pass). */
    private static Print print(boolean merge) {
        return print(merge, true);
    }

    private static Print print(boolean merge, boolean skipShortTiles) {
        return print(merge, skipShortTiles, art(true, true));
    }

    private static Print print(boolean merge, boolean skipShortTiles, SuppressionArt art) {
        return print(merge, skipShortTiles, art, null);
    }

    private static Print print(boolean merge, boolean skipShortTiles, SuppressionArt art, RecordingLogger log) {
        Sim sim = new Sim();
        if (log != null) sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.config.build.suppressionMergeNoobline = merge;
        sim.config.build.skipShortTiles = skipShortTiles;
        MapArtConfig.PlatformCfg cs = csPlatform(sim);
        Job job = addJob(sim, art);
        sim.start();
        List<Integer> order = new ArrayList<>();
        int[] rounds = {0};
        long[] ticks = {0};
        Phase[] last = {null};
        assertTrue(sim.runUntil(() -> {
            var st = sim.engine.status();
            if (st.jobId() != null && (order.isEmpty() || order.getLast() != st.tileX())) order.add(st.tileX());
            if (sim.phase() == Phase.RESTOCK && last[0] != Phase.RESTOCK) rounds[0]++;
            last[0] = sim.phase();
            ticks[0]++;
            return job.status == JobStatus.READY_FOR_RIDE;
        }, LONG), sim.state());
        return new Print(order, rounds[0], sim.bot.lanesRun.size(), ticks[0], sim, job, cs);
    }

    /**
     * {@code build.suppressionMergeNoobline}: each level's main pass is built before its noobline pass and places the
     * row with its own lanes; the noobline pass is then only verified. The job's last pair keeps its order (the ride
     * follows the last pass). Same blocks as without the switch, fewer restock rounds and lanes.
     */
    @Test
    void aLevelsNooblineRowGoesWithItsMainPass() {
        Print before = print(false);
        Print merged = print(true);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), before.order());
        // the three noobline passes whose row went with the main pass are verified in passing (no phase of their own
        // long enough to show); the last pair keeps its order
        List<Integer> mains = new ArrayList<>(merged.order());
        mains.removeAll(List.of(0, 2, 4));
        assertEquals(List.of(1, 3, 5, 6, 7), mains, merged.order().toString());
        assertEquals(1, merged.order().getFirst(), merged.order().toString());
        for (Job.TileState t : merged.job().tiles) assertEquals(TileStatus.STORED, t.status);
        SuppressionArt a = art(true, true);
        MapArtConfig.PlatformCfg cs = merged.cs();
        for (int layer = 0; layer < 2; layer++)
            for (int level = 0; level < 2; level++)
                for (int row = -1; row < 128; row++)
                    for (int col = 0; col < 128; col++) {
                        BlockSpec want = a.get(layer, level, col, row);
                        BlockSpec have = merged.sim().world.block(new Pos(cs.minX + col, cs.glassY.get(layer) + 1 + level, cs.minZ + row));
                        if (want == null) assertTrue(have.isAir(), "air at " + layer + "/" + level + " " + col + "," + row);
                        else assertEquals(want, have, layer + "/" + level + " " + col + "," + row);
                    }
        // the three noobline passes that went with their main pass ran no lane and no restock round of their own
        System.out.println("noobline merge in the simulated site: restock rounds " + before.rounds() + " -> " + merged.rounds()
            + ", lanes " + before.lanes() + " -> " + merged.lanes() + ", ticks " + before.ticks() + " -> " + merged.ticks());
        assertTrue(merged.lanes() <= before.lanes() - 3, before.lanes() + " -> " + merged.lanes());
        assertTrue(merged.ticks() < before.ticks(), before.ticks() + " -> " + merged.ticks());
        // one start line, for the main pass that ran first
        assertEquals(1, merged.sim().notifier.of("info").stream().filter(m -> m.title().startsWith("Started")).count(),
            merged.sim().notifier.toString());
        // no lane of a merged pass ends at a lethal edge
        var hz = io.github.digitalisp.mapart.bot.impl.NoGoZones.of(EdgeHazards.compute(cs, new EdgeHazards.Blocks() {
            @Override public boolean loaded(int x, int y, int z) { return true; }
            @Override public BlockSpec block(int x, int y, int z) { return merged.sim().world.block(new Pos(x, y, z)); }
        }).zones());
        for (var l : merged.sim().bot.lanesRun) {
            assertTrue(hz.at(l.from()) == null, "lane " + l.index() + " starts at an edge: " + l.from());
            assertTrue(hz.at(l.to()) == null, "lane " + l.index() + " ends at an edge: " + l.to());
        }
    }

    /**
     * The main pass goes first by SELECT's own choice, not by the stock-aware one: the same order with
     * {@code build.skipShortTiles} off (the live setting of 2026-11-14), where the switch once changed nothing.
     */
    @Test
    void theMainPassGoesFirstWithoutTheStockAwareSelect() {
        Print merged = print(true, false);
        assertEquals(1, merged.order().getFirst(), merged.order().toString());
        List<Integer> mains = new ArrayList<>(merged.order());
        mains.removeAll(List.of(0, 2, 4));
        assertEquals(List.of(1, 3, 5, 6, 7), mains, merged.order().toString());
        for (Job.TileState t : merged.job().tiles) assertEquals(TileStatus.STORED, t.status);
        assertTrue(merged.lanes() <= print(false, false).lanes() - 3, "lanes " + merged.lanes());
    }

    /**
     * A noobline row of an item its main pass does not place keeps its own pass, in the stored order: its lanes would
     * otherwise make every load of the main pass carry that item in a slot of its own for a few cells at the lanes'
     * ends. Local 10x run of 2026-11-14 (a real export: every noobline row moss, the top layer's map cells with moss,
     * the bottom layer's without): the bottom y+0 pass took 10 restock rounds against 8, the first a load of 7 cells.
     */
    @Test
    void aNooblineRowOfAnItemItsMainPassDoesNotPlaceKeepsItsOwnPass() {
        // no level's row is of its own items: the switch changes nothing
        Print off = print(false, true, art());
        Print on = print(true, true, art());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), on.order());
        assertEquals(off.lanes(), on.lanes());
        assertEquals(off.rounds(), on.rounds());
        for (Job.TileState t : on.job().tiles) assertEquals(TileStatus.STORED, t.status);
        // the top layer's rows of its own items, the bottom layer's not (as the real export): only the top levels merge
        RecordingLogger log = new RecordingLogger();
        Print mixed = print(true, true, art(true, false), log);
        for (Job.TileState t : mixed.job().tiles) assertEquals(TileStatus.STORED, t.status);
        List<Job.SuppressionPass> passes = mixed.job().suppression;
        int merged = 0;
        for (int i = 0; i + 1 < passes.size(); i++) {
            Job.SuppressionPass noob = passes.get(i), main = passes.get(i + 1);
            if (!noob.noobline || main.noobline || noob.layer != main.layer || noob.level != main.level) continue;
            // a noobline pass whose row went with its main pass is verified in passing: not in the order at all
            boolean mainFirst = !mixed.order().contains(i) || mixed.order().indexOf(i + 1) < mixed.order().indexOf(i);
            boolean lastPair = i + 1 == passes.size() - 1;
            assertEquals(noob.layer == SuppressionArt.TOP && !lastPair, mainFirst,
                "layer " + noob.layer + " level " + noob.level + ": " + mixed.order());
            if (mainFirst) merged++;
        }
        assertTrue(merged >= 1, mixed.order().toString());
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.contains("which this pass does not place")),
            "the level that keeps its noobline pass says why");
    }

    /**
     * The switch turned off between a main pass and its noobline pass: the noobline pass still reads its row alone
     * (the map rows south of it hold the main pass), builds what is missing and the job goes on in its stored order.
     */
    @Test
    void theSwitchTurnedOffAfterAMainPassWentFirstStillFinishesTheJob() {
        Sim sim = new Sim();
        sim.config.build.suppressionMergeNoobline = true;
        csPlatform(sim);
        Job job = addJob(sim, art(true, true));
        sim.start();
        assertTrue(sim.runUntil(() -> job.tiles.get(1).status == TileStatus.STORED, LONG), sim.state());
        assertFalse(job.tiles.get(0).status == TileStatus.STORED, "the noobline pass is still to verify");
        sim.config.build.suppressionMergeNoobline = false;
        assertTrue(sim.runUntil(() -> job.status == JobStatus.READY_FOR_RIDE, LONG), sim.state());
        for (Job.TileState tile : job.tiles) assertEquals(TileStatus.STORED, tile.status);
    }

    /** A job stored before the switch (same pass list) follows it; the twins are found from the list alone. */
    @Test
    void theNooblineTwinIsThePassBeforeAMainPassOfItsLevelAndNeverTheLastPair() {
        Job j = new Job();
        j.suppression = List.of(new Job.SuppressionPass(1, 0, true), new Job.SuppressionPass(1, 0, false),
            new Job.SuppressionPass(1, 1, false), new Job.SuppressionPass(0, 0, true), new Job.SuppressionPass(0, 1, false),
            new Job.SuppressionPass(0, 1, true), new Job.SuppressionPass(0, 1, false));
        for (int i = 0; i < j.suppression.size(); i++) {
            Job.TileState t = new Job.TileState();
            t.x = i;
            j.tiles.add(t);
        }
        assertEquals(j.tiles.get(0), BuildEngine.nooblineTwin(j, j.tiles.get(1)));
        assertEquals(j.tiles.get(1), BuildEngine.mainOfNoobline(j, j.tiles.get(0)));
        assertNull(BuildEngine.nooblineTwin(j, j.tiles.get(2)), "no noobline pass before it");
        assertNull(BuildEngine.nooblineTwin(j, j.tiles.get(4)), "the noobline before it is another level's");
        assertNull(BuildEngine.mainOfNoobline(j, j.tiles.get(3)));
        assertNull(BuildEngine.nooblineTwin(j, j.tiles.get(6)), "the last pass keeps its place: the ride follows it");
        assertNull(BuildEngine.mainOfNoobline(j, j.tiles.get(5)));
        j.tiles.get(0).status = TileStatus.STORED;
        assertNull(BuildEngine.nooblineTwin(j, j.tiles.get(1)), "a verified noobline pass is nobody's twin");
        j.tiles.get(0).status = TileStatus.PENDING;
        j.tiles.get(1).status = TileStatus.STORED;
        assertNull(BuildEngine.mainOfNoobline(j, j.tiles.get(0)), "the main pass is done: the noobline pass runs");
        assertTrue(BuildEngine.mainBuiltFirst(j, j.tiles.get(0)), "its plan is the row alone, whatever the switch says now");
        assertFalse(BuildEngine.mainBuiltFirst(j, j.tiles.get(3)), "the pass after it is another level's");
        assertFalse(BuildEngine.mainBuiltFirst(j, j.tiles.get(5)), "the last pair in its stored order");
        j.tiles.get(6).placed = 12;
        assertTrue(BuildEngine.mainBuiltFirst(j, j.tiles.get(5)), "a main pass with blocks placed before its noobline pass");
    }

    // ---- build.suppressionMergeLevels: both levels of a layer in one sweep ----

    /** A print with {@code build.suppressionMergeLevels} and {@code build.suppressionMergeNoobline} as given. */
    private static Print printLevels(boolean levels, boolean noobline, SuppressionArt art) {
        Sim sim = new Sim();
        sim.config.build.suppressionMergeLevels = levels;
        sim.config.build.suppressionMergeNoobline = noobline;
        MapArtConfig.PlatformCfg cs = csPlatform(sim);
        Job job = addJob(sim, art);
        sim.start();
        List<Integer> order = new ArrayList<>();
        Map<Integer, Integer> lanesOf = new java.util.TreeMap<>();
        List<Integer> stored = new ArrayList<>();
        Map<Integer, Integer> roundsOf = new java.util.TreeMap<>();
        int[] rounds = {0};
        int[] seen = {0};
        long[] ticks = {0};
        Phase[] last = {null};
        assertTrue(sim.runUntil(() -> {
            var st = sim.engine.status();
            if (st.jobId() != null && (order.isEmpty() || order.getLast() != st.tileX())) order.add(st.tileX());
            if (sim.phase() == Phase.RESTOCK && last[0] != Phase.RESTOCK) {
                rounds[0]++;
                roundsOf.merge(st.tileX(), 1, Integer::sum);
            }
            while (seen[0] < sim.bot.lanesRun.size()) {
                seen[0]++;
                lanesOf.merge(st.tileX(), 1, Integer::sum);
            }
            for (Job.TileState t : job.tiles) if (t.status == TileStatus.STORED && !stored.contains(t.x)) stored.add(t.x);
            last[0] = sim.phase();
            ticks[0]++;
            return job.status == JobStatus.READY_FOR_RIDE;
        }, LONG), sim.state());
        System.out.println("print levels=" + levels + " noobline=" + noobline + ": restock rounds by pass " + roundsOf
            + ", lanes by pass " + lanesOf);
        return new Print(order, rounds[0], sim.bot.lanesRun.size(), ticks[0], sim, job, cs, lanesOf, stored);
    }

    private static void sameBlocks(Print p, SuppressionArt a) {
        MapArtConfig.PlatformCfg cs = p.cs();
        for (int layer = 0; layer < 2; layer++)
            for (int level = 0; level < 2; level++)
                for (int row = -1; row < 128; row++)
                    for (int col = 0; col < 128; col++) {
                        BlockSpec want = a.get(layer, level, col, row);
                        BlockSpec have = p.sim().world.block(new Pos(cs.minX + col, cs.glassY.get(layer) + 1 + level, cs.minZ + row));
                        if (want == null) assertTrue(have.isAir(), "air at " + layer + "/" + level + " " + col + "," + row);
                        else assertEquals(want, have, layer + "/" + level + " " + col + "," + row);
                    }
    }

    /**
     * {@code build.suppressionMergeLevels}: each layer's level-0 main pass places the cells of its level-1 main pass
     * (one block higher) with its own lanes; the level-1 pass then finds them in place and is only verified, no lane of
     * its own. The stored order stays (the ride follows the last pass). Same blocks, fewer lanes and restock rounds.
     * The switch off: the print as before, tick for tick.
     */
    @Test
    void bothLevelsOfALayerGoInOneSweep() {
        SuppressionArt a = art(true, true);
        Print before = printLevels(false, false, a);
        Print reference = print(false, true, a);
        assertEquals(reference.ticks(), before.ticks(), "the switch off changes nothing");
        assertEquals(reference.rounds(), before.rounds());
        assertEquals(reference.lanes(), before.lanes());
        Print merged = printLevels(true, false, a);
        sameBlocks(merged, a);
        for (Job.TileState t : merged.job().tiles) assertEquals(TileStatus.STORED, t.status);
        // the passes are verified in their stored order, the last pass last (the ride follows it)
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), merged.stored(), merged.order().toString());
        // the level-1 main passes (3 and 7 in the stored order) ran no lane of their own
        assertFalse(merged.lanesOf().containsKey(3), merged.lanesOf().toString());
        assertFalse(merged.lanesOf().containsKey(7), merged.lanesOf().toString());
        assertTrue(merged.job().tiles.get(1).carriesRaised && merged.job().tiles.get(5).carriesRaised);
        System.out.println("both levels in one sweep, simulated site: restock rounds " + before.rounds() + " -> "
            + merged.rounds() + ", lanes " + before.lanes() + " -> " + merged.lanes() + ", ticks " + before.ticks() + " -> "
            + merged.ticks() + "; lanes by pass " + before.lanesOf() + " -> " + merged.lanesOf());
        assertTrue(merged.lanes() < before.lanes(), before.lanes() + " -> " + merged.lanes());
        // the level-0 main pass now loads the level-1 item as well: in this small site the top layer's level-0 pass
        // had carried its one item over from the noobline pass's round, and takes a round of its own for the second
        // (the level-1 pass had none to save); the rounds are the risk to watch on a real map (docs/COLOR_SUPPRESSION.md)
        assertTrue(merged.rounds() <= before.rounds() + 1, before.rounds() + " -> " + merged.rounds());
        assertTrue(merged.ticks() < before.ticks(), before.ticks() + " -> " + merged.ticks());
        // the raised cells went in the level-0 lanes one block above them
        boolean raised = false;
        for (var l : merged.sim().bot.lanesRun)
            for (var p : l.placements()) raised |= p.pos().y() == l.from().y() + 1;
        assertTrue(raised, "no lane carried a raised cell");
    }

    /**
     * The progress with {@code build.suppressionMergeLevels}: a raised cell counts for the level-1 pass it belongs to
     * as soon as the level-0 sweep places it, never for the sweep's own pass. Live 2026-11-15 (j0010): the lower sweep
     * counted the raised cells as its own up to its total, the upper pass stood at 0 until its verify, and the status
     * said 85% and "~7h 20m left" with every block of the map in place.
     */
    @Test
    void theProgressCountsWhatTheLowerSweepPlacedForTheUpperPass() {
        SuppressionArt a = art(true, true);
        Sim sim = new Sim();
        sim.config.build.suppressionMergeLevels = true;
        sim.config.build.suppressionMergeNoobline = true;
        MapArtConfig.PlatformCfg cs = csPlatform(sim);
        Job job = addJob(sim, a);
        Job.TileState low = job.tiles.get(5);
        Job.TileState high = job.tiles.get(7);
        sim.start();
        boolean[] sweepDone = {false};
        assertTrue(sim.runUntil(() -> {
            var st = sim.engine.status();
            if (st.jobId() != null && st.tileX() == 5 && sim.phase() == Phase.BUILD) {
                // the sweep's own count: never more than its own cells in place (rows -1..127 at level 0)
                int own = 0;
                for (int row = -1; row < 128; row++)
                    for (int col = 0; col < 128; col++) {
                        BlockSpec want = a.get(SuppressionArt.BOTTOM, 0, col, row);
                        if (want != null && want.equals(sim.world.block(new Pos(cs.minX + col,
                            cs.glassY.get(SuppressionArt.BOTTOM) + 1, cs.minZ + row)))) own++;
                    }
                assertTrue(st.placed() <= own, "the lower pass counts " + st.placed() + " with " + own + " of its cells in place");
            }
            if (!sweepDone[0] && low.status == TileStatus.STORED && high.status != TileStatus.STORED) {
                sweepDone[0] = true;
                assertEquals(0, high.placed, "the upper pass's own count waits for its own run");
                assertEquals(high.total, high.placedAhead, "every raised cell counted for the upper pass");
                var b = io.github.digitalisp.mapart.command.StatusWords.suppressionBlocks(job, st);
                long open = 0;
                for (Job.TileState t : job.tiles) if (t.status != TileStatus.STORED && t != high) open += t.total;
                assertTrue(b.placed() >= b.total() - open, b + " with " + open + " blocks in passes still open");
            }
            return job.status == JobStatus.READY_FOR_RIDE;
        }, LONG), sim.state());
        assertTrue(sweepDone[0], "the lower sweep was verified before the upper pass");
    }

    /**
     * The stock command's need of a color-suppression job is the whole map's: its passes all stand until the ride.
     * Live 2026-11-15: the need was the biggest pass's, so the command named 164 brown concrete as the only shortage
     * while six queued maps needed up to 3,232 more jungle planks than all storage held.
     */
    @Test
    void theStockNeedOfAColorSuppressionMapIsTheSumOfItsPasses() {
        Sim sim = new Sim();
        csPlatform(sim);
        SuppressionArt a = art(true, true);
        Job job = addJob(sim, a);
        sim.start();
        Map<String, Integer> all = new java.util.TreeMap<>();
        Map<String, Integer> biggestPass = new java.util.TreeMap<>();
        Map<String, Integer> lastPass = new java.util.TreeMap<>();
        List<SuppressionArt.Pass> passes = a.passes();
        for (int i = 0; i < passes.size(); i++) {
            Map<String, Integer> own = new java.util.TreeMap<>();
            for (int pz = 0; pz < 128; pz++)
                for (int px = 0; px < 128; px++) {
                    BlockSpec b = passes.get(i).art().get(px, pz);
                    if (b != null) own.merge(b.id(), 1, Integer::sum);
                }
            own.forEach((id, n) -> all.merge(id, n, Integer::sum));
            own.forEach((id, n) -> biggestPass.merge(id, n, Math::max));
            if (i == passes.size() - 1) lastPass.putAll(own);
        }
        Map<String, Integer> peak = sim.engine.largestMapNeeds(List.of(job));
        for (String item : List.of(Sim.WHITE, Sim.RED, Sim.BLACK)) assertEquals(all.get(item), peak.get(item), item);
        assertTrue(all.get(Sim.RED) > biggestPass.get(Sim.RED), "red is in several passes: " + all + " " + biggestPass);
        // passes verified: only what is still to verify counts
        for (int i = 0; i < job.tiles.size() - 1; i++) job.tiles.get(i).status = TileStatus.STORED;
        Map<String, Integer> rest = sim.engine.largestMapNeeds(List.of(job));
        for (String item : lastPass.keySet()) assertEquals(lastPass.get(item), rest.get(item), item);
    }

    /**
     * With {@code build.suppressionMergeNoobline} as well: the level-1 noobline row goes with the level-0 sweep too
     * (its items are the sweep's), so a layer is one sweep; the noobline passes are only verified.
     */
    @Test
    void withTheNooblineMergeALayerIsOneSweep() {
        SuppressionArt a = art(true, true);
        Print noob = printLevels(false, true, a);
        Print both = printLevels(true, true, a);
        sameBlocks(both, a);
        for (Job.TileState t : both.job().tiles) assertEquals(TileStatus.STORED, t.status);
        System.out.println("both levels and the noobline rows in one sweep: restock rounds " + noob.rounds() + " -> "
            + both.rounds() + ", lanes " + noob.lanes() + " -> " + both.lanes() + ", ticks " + noob.ticks() + " -> "
            + both.ticks() + "; lanes by pass " + noob.lanesOf() + " -> " + both.lanesOf());
        // only the level-0 main passes ran lanes: the top layer's (1) and the bottom layer's (5)
        assertEquals(java.util.Set.of(1, 5), both.lanesOf().keySet(), both.lanesOf().toString());
        assertTrue(both.lanes() < noob.lanes(), noob.lanes() + " -> " + both.lanes());
        assertEquals(7, both.stored().getLast(), "the last pass is the last: " + both.stored());
    }

    /**
     * The switch turned off after a level-0 sweep carried the level-1 cells: the level-1 noobline pass still plans its
     * row alone (the map rows its geometry covers hold the raised cells, no foreign blocks) and the job finishes.
     */
    @Test
    void theLevelsSwitchTurnedOffAfterASweepStillFinishesTheJob() {
        Sim sim = new Sim();
        sim.config.build.suppressionMergeLevels = true;
        csPlatform(sim);
        SuppressionArt a = art(true, true);
        Job job = addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> job.tiles.get(1).status == TileStatus.STORED, LONG), sim.state());
        assertTrue(job.tiles.get(1).carriesRaised);
        sim.config.build.suppressionMergeLevels = false;
        assertTrue(sim.runUntil(() -> job.status == JobStatus.READY_FOR_RIDE, LONG), sim.state());
        for (Job.TileState tile : job.tiles) assertEquals(TileStatus.STORED, tile.status);
    }

    /**
     * Wrong blocks on the upper level after the level-0 sweep carried its cells (a carried cell of another item, a stray
     * where the level has no cell): the level-1 main pass, not begun before, resumes over the carried cells and its
     * VERIFY repairs them, as a pass does with the cells of its own lanes. Its noobline pass plans its row alone and
     * never sees them ({@code mainStandsAlready} does not apply); before, this pass's INSPECT ended BLOCKED "not reset".
     */
    @Test
    void wrongBlocksAmongTheCarriedCellsAreRepairedByTheLevelOnePass() {
        Sim sim = new Sim();
        sim.config.build.suppressionMergeLevels = true;
        MapArtConfig.PlatformCfg cs = csPlatform(sim);
        SuppressionArt a = art(true, true);
        Job job = addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> job.tiles.get(1).status == TileStatus.STORED, LONG), sim.state());
        assertTrue(job.tiles.get(1).carriesRaised);
        Job.SuppressionPass up = job.suppression.get(3);
        assertTrue(up.level == 1 && !up.noobline && up.layer == job.suppression.get(1).layer);
        int y = cs.glassY.get(up.layer) + 2;
        Pos cell = null;
        BlockSpec want = null;
        for (int row = 0; row < 6 && cell == null; row++)
            for (int col = 0; col < 24 && cell == null; col++) {
                want = a.get(up.layer, 1, col, row);
                if (want != null) cell = new Pos(cs.minX + col, y, cs.minZ + row);
            }
        assertTrue(cell != null && sim.world.block(cell).equals(want), "a carried cell stands");
        sim.world.set(cell, BlockSpec.of(Sim.WHITE));
        Pos stray = new Pos(cs.minX + 60, y, cs.minZ + 40);
        sim.world.set(stray, BlockSpec.of(Sim.WHITE));
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.READY_FOR_RIDE;
        }, LONG), sim.state());
        for (Job.TileState tile : job.tiles) assertEquals(TileStatus.STORED, tile.status);
        assertEquals(want, sim.world.block(cell), "the carried cell holds its own block again");
        assertTrue(sim.world.block(stray).isAir(), "the stray is gone");
    }

    /** The level-1 twin of a level-0 main pass is its layer's level-1 map pass, until that one is verified. */
    @Test
    void theLevelTwinIsTheLayersLevelOneMainPass() {
        Job j = new Job();
        j.suppression = List.of(new Job.SuppressionPass(1, 0, true), new Job.SuppressionPass(1, 0, false),
            new Job.SuppressionPass(1, 1, true), new Job.SuppressionPass(1, 1, false), new Job.SuppressionPass(0, 0, true),
            new Job.SuppressionPass(0, 0, false), new Job.SuppressionPass(0, 1, true), new Job.SuppressionPass(0, 1, false));
        for (int i = 0; i < j.suppression.size(); i++) {
            Job.TileState t = new Job.TileState();
            t.x = i;
            j.tiles.add(t);
        }
        assertEquals(j.tiles.get(3), BuildEngine.levelTwin(j, j.tiles.get(1)));
        assertEquals(j.tiles.get(7), BuildEngine.levelTwin(j, j.tiles.get(5)), "the last pass too: its order stays");
        for (int i : new int[] {0, 2, 3, 4, 6, 7}) assertNull(BuildEngine.levelTwin(j, j.tiles.get(i)), "pass " + i);
        j.tiles.get(3).status = TileStatus.STORED;
        assertNull(BuildEngine.levelTwin(j, j.tiles.get(1)), "a verified level-1 pass is carried by nobody");
        assertFalse(BuildEngine.raisedCarried(j, 1));
        j.tiles.get(1).carriesRaised = true;
        assertTrue(BuildEngine.raisedCarried(j, 1));
        assertFalse(BuildEngine.raisedCarried(j, 0));
    }

    @Test
    void geometryFollowsThePass() {
        MapArtConfig.PlatformCfg cs = new MapArtConfig.PlatformCfg();
        cs.kind = "COLOR_SUPPRESSION";
        cs.minX = -64;
        cs.minZ = -64;
        cs.glassY = List.of(200, 214);
        Job j = new Job();
        j.suppression = List.of(new Job.SuppressionPass(0, 0, true), new Job.SuppressionPass(0, 1, false),
            new Job.SuppressionPass(1, 1, false));
        Job.TileState t = new Job.TileState();
        t.x = 0;
        assertEquals(new PlatformGeometry(-64, -65, 201), BuildEngine.geometryFor(j, t, cs));
        t.x = 1;
        assertEquals(new PlatformGeometry(-64, -64, 202), BuildEngine.geometryFor(j, t, cs));
        t.x = 2;
        assertEquals(new PlatformGeometry(-64, -64, 216), BuildEngine.geometryFor(j, t, cs));
        MapArtConfig.PlatformCfg moss = new MapArtConfig.PlatformCfg();
        moss.minX = -64;
        moss.minZ = -64;
        moss.artY = 173;
        assertEquals(moss.geometry(), BuildEngine.geometryFor(j, t, moss));
        assertNull(BuildEngine.suppressionPass(new Job(), t));
    }

    // ---- SELECT: a colour-suppression map's passes go in order, whatever the stock index says ----

    static final Pos RED_CHEST = new Pos(-96, 61, 100);

    private static int firstPassWith(Sim sim, Job job, String item) {
        for (int i = 0; i < job.tiles.size(); i++)
            if (sim.store.tiles.get(job.id + ":" + new io.github.digitalisp.mapart.model.TileCoord(i, 0)).blockCounts().keySet()
                .stream().anyMatch(k -> BlockSpec.parse(k).id().equals(item))) return i;
        throw new AssertionError("no pass with " + item);
    }

    /**
     * Live 2026-11-13: with {@code build.skipShortTiles} SELECT weighed the next pass against a stock index that did
     * not show its materials (the site's chests instead of the platform's own), re-scanned the storage "before skipping"
     * it before every pass and would have taken another map first. A pass is never weighed: the next one in order is
     * taken, and STOCK_CHECK (the job's own stock scope) looks at the chests. Here the index is out of date for the red
     * carpet of a later pass and a fully stocked moss map is queued behind the job.
     */
    @Test
    void passesAreTakenInOrderWithoutTheStockAwareSkipAndALaterJobNeverGoesFirst() {
        io.github.digitalisp.mapart.testutil.RecordingLogger log = new io.github.digitalisp.mapart.testutil.RecordingLogger();
        Sim sim = new Sim();
        csPlatform(sim);
        sim.newEngine(new SimFakes.SerpentinePlanner(), new io.github.digitalisp.mapart.plan.GreedyRestockPlanner(), log);
        assertTrue(sim.config.build.skipShortTiles, "the default");
        sim.bot.containers.get(RED_CHEST).items.clear(); // no red carpet when the chests are first read
        Job job = addJob(sim, art());
        Job later = sim.addJob("j0002", "Stocked", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.TERRACOTTA, 2));
        int red = firstPassWith(sim, job, Sim.RED);
        assertTrue(red > 0, "a later pass needs the red carpet: " + red);
        sim.start();
        List<Integer> order = new ArrayList<>();
        boolean[] refilled = {false};
        assertTrue(sim.runUntil(() -> {
            var st = sim.engine.status();
            if (job.id.equals(st.jobId()) && (order.isEmpty() || order.getLast() != st.tileX())) order.add(st.tileX());
            // the red carpet arrives while the pass before it is built: the index still says there is none
            if (!refilled[0] && job.id.equals(st.jobId()) && st.tileX() == red - 1 && sim.phase() == Phase.BUILD) {
                sim.bot.containers.get(RED_CHEST).items.put(Sim.RED, 1728);
                refilled[0] = true;
            }
            if (!job.status.terminal())
                assertEquals(JobStatus.QUEUED, later.status, "the later job never goes before a pass: " + sim.state());
            return job.status == JobStatus.READY_FOR_RIDE;
        }, LONG), sim.state());
        assertTrue(refilled[0]);
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < job.tiles.size(); i++) all.add(i);
        assertEquals(all, order, "every pass once, in order");
        List<String> lines = log.lines;
        assertFalse(lines.stream().anyMatch(l -> l.contains("building it before") || l.contains("fully stocked")
            || l.contains("trying it before")), "no pass was weighed against another map: " + lines.stream()
            .filter(l -> l.contains("before") || l.contains("fully stocked")).toList());
        // the queue goes on behind the map
        assertTrue(sim.runUntil(() -> later.status == JobStatus.ACTIVE, LONG), sim.state());
    }

    /** Two colour-suppression jobs: the second never starts on the layers before the first is finished. */
    @Test
    void aQueueOfColourSuppressionJobsIsTakenInQueueOrder() {
        Sim sim = new Sim();
        MapArtConfig.PlatformCfg cs = csPlatform(sim);
        SuppressionArt a = art();
        Job first = addJob(sim, a);
        List<SuppressionArt.Pass> passes = a.passes();
        Job second = sim.addJob("j0002", "cs2", passes.size(), 1, List.of("cs"), (x, z) -> passes.get(x).art());
        second.suppression = new ArrayList<>();
        for (SuppressionArt.Pass p : passes) second.suppression.add(new Job.SuppressionPass(p.layer(), p.level(), p.noobline()));
        // the first job waits for materials (held, nothing of its next pass placed): the second must not take the layers
        first.tiles.get(0).status = TileStatus.STORED;
        first.status = JobStatus.WAITING_MATERIALS;
        assertFalse(BuildEngine.suppressionTurn(List.of(first, second), second), "the first job's turn");
        assertTrue(BuildEngine.suppressionTurn(List.of(first, second), first));
        // a job that has started goes on before an earlier one that has not (it holds the layers)
        Job early = sim.addJob("j0003", "cs3", 1, 1, List.of("cs"), (x, z) -> passes.get(0).art());
        early.suppression = new ArrayList<>(List.of(new Job.SuppressionPass(0, 0, false)));
        assertFalse(BuildEngine.suppressionTurn(List.of(early, first, second), early));
        assertTrue(BuildEngine.suppressionTurn(List.of(early, first, second), first));
        // neither started: the first in the queue
        assertTrue(BuildEngine.suppressionTurn(List.of(early, second), early));
        assertFalse(BuildEngine.suppressionTurn(List.of(early, second), second));
        // another platform is another queue; a finished job no longer counts
        early.platformIds.clear();
        early.platformIds.add("cs-b");
        assertTrue(BuildEngine.suppressionTurn(List.of(early, first, second), early));
        first.status = JobStatus.READY_FOR_RIDE;
        assertTrue(BuildEngine.suppressionTurn(List.of(first, second), second));
        // other jobs are not held by this rule
        Job flat = sim.addJob("j0004", "flat", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.RED, 1));
        assertTrue(BuildEngine.suppressionTurn(List.of(first, second, flat), flat));
        assertTrue(cs.isColorSuppression());
    }
}
