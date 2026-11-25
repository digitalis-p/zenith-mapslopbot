package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.bot.WithdrawMode;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.ConfiguredRestockPlanner;
import io.github.digitalisp.mapart.plan.RestockLoads;
import io.github.digitalisp.mapart.plan.RestockPlanner;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.plan.SerpentineBuildPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The restock bundle in the engine with the production planners: the
 * configured planner chooses each load, BUILD stops where the load was cut, withdrawals top up carried partial stacks,
 * the sweep ends towards the next lane, the map and the pane come one each with the last load, boots are worn. And
 * {@code GREEDY} without top-up is the previous behaviour. Trip counts here are the engine's; the restock seconds are
 * {@code plan/RestockModelTest}'s.
 */
class RestockBundleTest {
    private static final int LONG = 300_000;
    private static final String[] SIX = {Sim.WHITE, Sim.RED, Sim.BLACK, Sim.LIME,
        "minecraft:blue_carpet", "minecraft:yellow_carpet"};
    private static final Pattern PLACED = Pattern.compile(" (\\d+) placed");

    /** Six colours dithered at random over the whole tile (as RestockLoadTest). */
    static TileArt dithered(long seed) {
        Random r = new Random(seed);
        TileArt a = new TileArt();
        for (int pz = 0; pz < 128; pz++)
            for (int px = 0; px < 128; px++) a.set(px, pz, BlockSpec.of(SIX[r.nextInt(SIX.length)]));
        return a;
    }

    record Run(Sim sim, List<Integer> placedPerTrip, List<String> log) {
        int trips() {
            return placedPerTrip.size();
        }

        long info(String prefix) {
            return log.stream().filter(l -> l.startsWith("INFO " + prefix)).count();
        }

        String rounds() {
            StringBuilder sb = new StringBuilder();
            for (String l : log)
                if (l.startsWith("INFO restock:") || l.startsWith("INFO build round:") || l.startsWith("INFO withdraw from"))
                    sb.append('\n').append(l);
            return sb.toString();
        }
    }

    /**
     * Builds {@code art} on the moss platform with 31 material slots (36 − pickaxe − carrots − 3 reserved for the
     * kit's other stacks), the production lane planner and the configured restock planner.
     */
    static Run run(TileArt art, String placeMode, String planner, boolean topUp, int materialSlots) {
        return run(art, placeMode, planner, topUp, materialSlots, false);
    }

    /** {@link #run(TileArt, String, String, boolean, int)} with {@code build.restockLaneFlip} set to {@code flip}. */
    static Run run(TileArt art, String placeMode, String planner, boolean topUp, int materialSlots, boolean flip) {
        RecordingLogger log = new RecordingLogger();
        Sim sim = new Sim();
        sim.config.build.restockLaneFlip = flip;
        sim.config.build.reservedSlots = 36 - materialSlots;
        sim.config.build.placeMode = placeMode;
        sim.config.build.restockPlanner = planner;
        sim.config.build.topUpStacks = topUp;
        sim.bot.wholeStackWithdrawals = true;
        for (int i = 0; i < SIX.length; i++) {
            sim.bot.addContainer(new Pos(-100 + 2 * i, 61, 104), Map.of(SIX[i], 1728));
            sim.bot.addContainer(new Pos(-100 + 2 * i, 61, 106), Map.of(SIX[i], 1728));
        }
        sim.newEngine(new SerpentineBuildPlanner(), new ConfiguredRestockPlanner(() -> sim.config.build.restockPlanner), log);
        Job job = sim.addJob("j0060", "Bundle", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log.lines));
        List<Integer> placed = new ArrayList<>();
        for (String l : log.at(Level.INFO)) {
            if (!l.startsWith("build round:")) continue;
            Matcher m = PLACED.matcher(l);
            assertTrue(m.find(), l);
            placed.add(Integer.parseInt(m.group(1)));
        }
        Run r = new Run(sim, placed, log.lines);
        System.out.printf("bundle engine sim: %s, %s, top-up %s, %d slots: %d trips, placed per trip %s%n", placeMode,
            planner, topUp, materialSlots, r.trips(), placed);
        return r;
    }

    private static final Pattern PLANNED = Pattern.compile("planned: (\\d+) load");

    /** The load count the first restock's plan expected for the whole tile. */
    private static int plannedLoads(Run r) {
        for (String l : r.log()) {
            if (!l.startsWith("INFO restock:")) continue;
            Matcher m = PLANNED.matcher(l);
            assertTrue(m.find(), l);
            return Integer.parseInt(m.group(1));
        }
        throw new AssertionError("no restock line" + r.rounds());
    }

    private static String tail(List<String> l) {
        return "\n" + String.join("\n", l.subList(Math.max(0, l.size() - 60), l.size()));
    }

    private static void assertEveryCellOnce(Run r) {
        assertEquals(128 * 128, r.sim().bot.placed, "every cell placed once" + r.rounds());
        assertTrue(r.sim().bot.placeCounts.values().stream().allMatch(n -> n == 1), "no cell placed twice");
    }

    @Test
    void airplaceSplitLoadsAreCutAndNeverRunShort() {
        Run before = run(dithered(42), "AIRPLACE", "GREEDY", false, 31);
        Run after = run(dithered(42), "AIRPLACE", "AUTO", true, 31);
        assertEveryCellOnce(before);
        assertEveryCellOnce(after);
        // the DP weighs walking and chests, not trips: the engine runs the loads it planned
        assertEquals(plannedLoads(after), after.trips(), "the first plan's load count" + after.rounds());
        // the cut round carries exactly its cells: no lane walked on with its items used up
        assertTrue(after.log().stream().noneMatch(l -> l.contains("short of items")), after.rounds());
        assertTrue(before.log().stream().anyMatch(l -> l.contains("short of items")), "greedy walks the partial lane on"
            + before.rounds());
        assertTrue(after.sim().bot.withdrawModes.contains(WithdrawMode.TOP_UP), after.sim().bot.withdrawModes.toString());
        assertFalse(before.sim().bot.withdrawModes.contains(WithdrawMode.TOP_UP));
        assertEquals(1, after.sim().store.entries("VERIFY"), "cut rounds go back to RESTOCK: " + after.sim().store.phaseLog);
        assertTrue(after.sim().notifier.of("alert").isEmpty(), after.sim().notifier.toString());
    }

    private static final Pattern LANES_RUN = Pattern.compile("^INFO build round: (\\d+) lanes run");

    /**
     * Each BUILD round's first lane run, after its restock line said whether the load walks the other way: entry 0 the
     * round's last restock line, entry 1 that first lane.
     */
    private static List<Map.Entry<String, Lane>> firstLanes(Run r) {
        List<Map.Entry<String, Lane>> out = new ArrayList<>();
        List<Lane> lanes = r.sim().bot.lanesRun;
        String restock = null;
        int at = 0;
        for (String l : r.log()) {
            if (l.startsWith("INFO restock:") && l.contains("lanes left")) restock = l;
            Matcher m = LANES_RUN.matcher(l);
            if (!m.find()) continue;
            int n = Integer.parseInt(m.group(1));
            if (n > 0 && restock != null) out.add(Map.entry(restock, lanes.get(at)));
            at += n;
            restock = null;
        }
        return out;
    }

    @Test
    void theLoadDirectionWalksALoadsLanesTheOtherWay() {
        Run off = run(dithered(42), "AIRPLACE", "AUTO", true, 31, false);
        Run on = run(dithered(42), "AIRPLACE", "AUTO", true, 31, true);
        assertEveryCellOnce(off);
        assertEveryCellOnce(on);
        assertTrue(off.log().stream().noneMatch(l -> l.contains("walked the other way")), off.rounds());
        int flipped = 0;
        for (var e : firstLanes(off)) {
            Lane first = e.getValue();
            assertTrue(first.from().z() < first.to().z(), "off: every round starts N→S: " + e.getKey() + off.rounds());
        }
        for (var e : firstLanes(on)) {
            Lane first = e.getValue();
            boolean up = e.getKey().contains("walked the other way");
            if (up) flipped++;
            assertEquals(up, first.from().z() > first.to().z(), e.getKey() + ": lane " + first.index() + " "
                + first.from() + " -> " + first.to() + on.rounds());
            // the rows walked in the lane's direction, the walk starts at the lane's from
            int step = Integer.signum(first.to().z() - first.from().z());
            List<Placement> ps = first.placements();
            for (int i = 1; i < ps.size(); i++)
                assertTrue(Integer.signum(ps.get(i).pos().z() - ps.get(i - 1).pos().z()) != -step, "rows in walk order");
        }
        assertTrue(flipped > 0, "some load walks the other way" + on.rounds());
        // the cut rounds carry exactly their cells, flipped or not; the DP's first plan still counts the engine's loads
        assertTrue(on.log().stream().noneMatch(l -> l.contains("short of items")), on.rounds());
        assertEquals(plannedLoads(on), on.trips(), "the first plan's load count" + on.rounds());
        assertEquals(1, on.sim().store.entries("VERIFY"), "cut rounds go back to RESTOCK: " + on.sim().store.phaseLog);
        assertTrue(on.sim().notifier.of("alert").isEmpty(), on.sim().notifier.toString());
    }

    @Test
    void lookLanesPlaceEveryCellWithNoMoreTrips() {
        Run before = run(dithered(7), "LOOK", "GREEDY", false, 31);
        Run after = run(dithered(7), "LOOK", "AUTO", true, 31);
        assertEveryCellOnce(before);
        assertEveryCellOnce(after);
        assertEquals(plannedLoads(after), after.trips(), "the first plan's load count" + after.rounds());
        assertTrue(after.log().stream().noneMatch(l -> l.contains("short of items")), after.rounds());
        assertEquals(1, after.sim().store.entries("VERIFY"), after.sim().store.phaseLog.toString());
    }

    @Test
    void topUpFillsCarriedPartialStacks() {
        Run r = run(dithered(42), "AIRPLACE", "SPLIT", true, 31);
        assertEveryCellOnce(r);
        assertTrue(r.sim().bot.topUps > 0, "partial stacks carried between loads are topped up" + r.rounds());
    }

    @Test
    void theSweepEndsTowardsTheFirstLaneOfTheLoad() {
        Run r = run(dithered(42), "AIRPLACE", "AUTO", true, 31);
        List<Pos> ends = r.sim().bot.planEnds;
        assertFalse(ends.isEmpty(), "restock sweeps planned");
        assertTrue(ends.stream().anyMatch(p -> p != null && p.y() == r.sim().moss.artY), ends.toString());
    }

    @Test
    void theLastLoadBringsOneMapAndOnePane() {
        Run r = run(dithered(42), "AIRPLACE", "AUTO", true, 31);
        List<String> history = r.sim().bot.history;
        assertEquals(1, r.sim().bot.count("withdraw " + Sim.MAPS), String.join("\n", history));
        assertEquals(1, r.sim().bot.count("withdraw " + Sim.PANES), String.join("\n", history));
        assertTrue(r.sim().bot.withdrawModes.contains(WithdrawMode.EXACT), r.sim().bot.withdrawModes.toString());
        assertEquals(0, r.sim().inv.count("minecraft:map"), "the one map was used");
        assertEquals(0, r.sim().inv.count("minecraft:glass_pane"), "the one pane was used");
    }

    @Test
    void greedyWithoutTopUpIsThePreviousBehaviour() {
        Run r = run(dithered(42), "LOOK", "GREEDY", false, 27);
        assertEveryCellOnce(r);
        assertTrue(r.sim().bot.withdrawModes.stream().noneMatch(m -> m == WithdrawMode.TOP_UP));
        // RestockLoadTest's numbers for the same tile and slots
        assertTrue(r.trips() <= 11, r.trips() + " trips" + r.rounds());
    }

    @Test
    void wornBootsAreMendedOnMossToo() {
        Sim sim = new Sim();
        sim.inv.boots = "minecraft:netherite_boots";
        sim.inv.bootsDurability = 0.5; // worn by Soul Speed on the soul soil walkway
        TileArt art = Sim.single(Sim.WHITE, 2);
        Job job = sim.addJob("j0062", "Boots", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.inv.bootsDurability >= 0.95, "boots mended at RENAME on moss: " + sim.inv.bootsDurability);
    }

    @Test
    void carriedBootsAreWornOnMoss() {
        Sim sim = new Sim();
        sim.inv.add("minecraft:netherite_boots", 1);
        TileArt art = Sim.single(Sim.WHITE, 2);
        Job job = sim.addJob("j0061", "Boots", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals("minecraft:netherite_boots", sim.inv.boots, "worn: the slot is free for materials");
        assertEquals(0, sim.inv.count("minecraft:netherite_boots"));
    }

    @Test
    void bootsStayCarriedWhenWearingIsOff() {
        Sim sim = new Sim();
        sim.config.build.wearBoots = false;
        sim.inv.add("minecraft:netherite_boots", 1);
        TileArt art = Sim.single(Sim.WHITE, 2);
        Job job = sim.addJob("j0062", "Boots off", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertNull(sim.inv.boots);
        assertEquals(1, sim.inv.count("minecraft:netherite_boots"));
    }

    /** Twenty materials of the tile's east lanes (live j0002 had partial stacks of many materials carried for later). */
    private static final String[] LATER = {
        "minecraft:orange_carpet", "minecraft:magenta_carpet", "minecraft:light_blue_carpet", "minecraft:pink_carpet",
        "minecraft:gray_carpet", "minecraft:light_gray_carpet", "minecraft:cyan_carpet", "minecraft:purple_carpet",
        "minecraft:brown_carpet", "minecraft:green_carpet", "minecraft:orange_terracotta", "minecraft:magenta_terracotta",
        "minecraft:light_blue_terracotta", "minecraft:yellow_terracotta", "minecraft:lime_terracotta",
        "minecraft:pink_terracotta", "minecraft:gray_terracotta", "minecraft:cyan_terracotta",
        "minecraft:purple_terracotta", "minecraft:blue_terracotta"};

    private static Run crowded(String planner) {
        RecordingLogger log = new RecordingLogger();
        Sim sim = new Sim();
        sim.config.build.restockPlanner = planner;
        sim.bot.wholeStackWithdrawals = true;
        for (String id : LATER) sim.inv.add(id, 10);
        sim.bot.addContainer(new Pos(-100, 61, 108), Map.of(Sim.WHITE, 20_000));
        for (int i = 0; i < LATER.length; i++)
            sim.bot.addContainer(new Pos(-100 + 2 * (i % 10), 61, 102 + 2 * (i / 10)), Map.of(LATER[i], 1_000));
        // white everywhere but the last 20 columns, which take the twenty materials in turn
        TileArt art = new TileArt();
        for (int pz = 0; pz < 128; pz++)
            for (int px = 0; px < 128; px++) art.set(px, pz, BlockSpec.of(px < 108 ? Sim.WHITE : LATER[px - 108]));
        sim.newEngine(new SerpentineBuildPlanner(), new ConfiguredRestockPlanner(() -> sim.config.build.restockPlanner), log);
        Job job = sim.addJob("j0065", "Crowded", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log.lines));
        List<Integer> placed = new ArrayList<>();
        for (String l : log.at(Level.INFO)) {
            if (!l.startsWith("build round:")) continue;
            Matcher m = PLACED.matcher(l);
            assertTrue(m.find(), l);
            placed.add(Integer.parseInt(m.group(1)));
        }
        System.out.printf("crowded inventory, %s: %d trips, placed per trip %s%n", planner, placed.size(), placed);
        return new Run(sim, placed, log.lines);
    }

    @Test
    void materialsCarriedForLaterLanesAreReturnedWhenTheyCrowdTheLoad() {
        for (String planner : List.of("SPLIT", "GREEDY")) {
            Run r = crowded(planner);
            assertEveryCellOnce(r);
            // keepForLoad (the greedy estimate before the drop) or the planner's own check after it: either makes room
            assertTrue(r.log().stream().anyMatch(l -> l.startsWith("INFO making room:") || l.startsWith("INFO crowded load:")),
                String.join("\n", r.log()));
            assertTrue(r.sim().bot.count("deposit " + Sim.RETURN) >= 1, "returned to the return chest: " + r.sim().bot.history);
            // 31 material slots once the twenty partial stacks are gone: the first load is at least a lane, not the 704
            // cells (11 slots) the crowded inventory leaves
            assertTrue(r.placedPerTrip().getFirst() >= 1_100, planner + " " + r.placedPerTrip());
            assertTrue(r.trips() <= 11, planner + ": " + r.trips() + " trips" + r.rounds());
        }
    }

    @Test
    void aShortWithdrawalReLooksAndThePlannerPlansTheLoadAgain() {
        // LazyStockTest's stale "full" chest, with the split planner off the tick thread: the re-look, then a new plan
        Pos whiteA = new Pos(-98, 61, 100); // the nearer white chest: restock tries it first
        Pos whiteB = new Pos(-100, 61, 100);
        RecordingLogger log = new RecordingLogger();
        Sim sim = new Sim();
        sim.config.build.stockMaxAgeMinutes = 0;
        sim.config.build.restockPlanner = "SPLIT";
        sim.newEngine(new SerpentinePlanner(), new ConfiguredRestockPlanner(() -> sim.config.build.restockPlanner), log);
        Job first = sim.addJob("j0063", "First", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.RED, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> first.status == JobStatus.DONE, LONG), sim.state());
        sim.store.stock.update(whiteA, Map.of(Sim.WHITE, 3456), 0, 54, sim.clock.millis() - 5 * 3_600_000L);
        sim.bot.containers.get(whiteA).items.clear();
        sim.clock.advance(3 * 3_600_000L);
        Job job = sim.addJob("j0064", "Lots of white", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 12));
        sim.engine.queueChanged();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log.lines));
        List<String> info = log.at(Level.INFO);
        assertTrue(info.stream().anyMatch(l -> l.contains("withdrawals came up short of [" + Sim.WHITE + "]")), info.toString());
        assertTrue(info.stream().filter(l -> l.startsWith("restock:")).count() >= 2, "planned again: " + info);
        assertTrue(sim.bot.count("scan " + whiteB) >= 2, "the other white chest was looked at again");
        assertEquals(1728 - 1536, sim.bot.containers.get(whiteB).items.getOrDefault(Sim.WHITE, 0), "the load came from B");
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    // ------------------------------------------------------------------------------------------------ the cut lane

    private static final PlatformGeometry GEO = new PlatformGeometry(-64, -64, 64);

    private static Lane lane(boolean southward) {
        List<Placement> ps = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int pz = southward ? i : 127 - i;
            for (int dx : new int[]{0, -1, 1}) ps.add(new Placement(GEO.cell(4 + dx, pz), BlockSpec.of(Sim.WHITE), Sim.WHITE));
        }
        Pos from = GEO.cell(4, southward ? 0 : 127);
        Pos to = GEO.cell(4, southward ? 6 : 121);
        return new Lane(0, from, to, List.copyOf(ps));
    }

    @Test
    void aCutLaneEndsOneRowPastItsLastCell() {
        Lane south = lane(true);
        Lane cut = RestockLoads.cut(south, 7); // rows 0, 1 and the first cell of row 2
        assertEquals(7, cut.placements().size());
        assertEquals(south.from(), cut.from());
        assertEquals(GEO.cell(4, 3), cut.to(), "one row past row 2");
        Lane north = lane(false);
        Lane cutN = RestockLoads.cut(north, 3); // row 127
        assertEquals(GEO.cell(4, 126), cutN.to());
        Lane whole = RestockLoads.cut(south, 18);
        assertEquals(south.to(), whole.to(), "never past the lane's own end");
    }

    @Test
    void cutLanesKeepsTheCoveredLanesAndThePartialOne() {
        List<Lane> lanes = List.of(lane(true), lane(false), lane(true));
        List<Lane> out = RestockLoads.cutLanes(lanes, new RestockPlanner.Plan(Map.of(), 1, 4, true, 3, 10.0));
        assertEquals(2, out.size());
        assertEquals(lanes.get(0), out.get(0));
        assertEquals(4, out.get(1).placements().size());
        List<Lane> all = RestockLoads.cutLanes(lanes, new RestockPlanner.Plan(Map.of(), 3, 0, true, 1, 1.0));
        assertEquals(lanes, all);
        List<Lane> none = RestockLoads.cutLanes(lanes, new RestockPlanner.Plan(Map.of(), 0, 0, true, 0, 0.0));
        assertTrue(none.isEmpty());
        assertNotNull(RestockLoads.cutLanes(lanes, new RestockPlanner.Plan(Map.of(), 2, 99, true, 1, 1.0)).get(2));
    }

    @Test
    void aFlippedPlanCutsTheLanesWalkedTheOtherWay() {
        List<Lane> lanes = List.of(lane(true), lane(false));
        List<Lane> out = RestockLoads.cutLanes(lanes, new RestockPlanner.Plan(Map.of(), 1, 4, true, 3, 10.0, true));
        assertEquals(2, out.size());
        // the first lane rows 5 → 0, then the second lane from its north end: rows 122, 123
        assertEquals(lanes.get(0).to(), out.get(0).from());
        assertEquals(lanes.get(0).from(), out.get(0).to());
        assertEquals(GEO.cell(4, 5).z(), out.get(0).placements().getFirst().pos().z());
        assertEquals(lanes.get(1).to(), out.get(1).from());
        assertEquals(4, out.get(1).placements().size());
        assertEquals(GEO.cell(4, 122).z(), out.get(1).placements().getFirst().pos().z());
        assertEquals(GEO.cell(4, 124), out.get(1).to(), "one row past row 123");
    }
}
