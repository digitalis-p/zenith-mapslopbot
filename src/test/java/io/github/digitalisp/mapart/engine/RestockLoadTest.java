package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.plan.SerpentineBuildPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Load size per restock trip, with the production planners and whole-stack withdrawals (shift-clicks) like the live
 * bot. Live before the fix: loads of about 400 items, "load covers 1, withdraw {}" rounds, about one lane per trip,
 * because the planner counted the held materials against the free slots instead of adding them, and loaded a partial
 * next lane only when not even one lane fit. On the 6-colour tile below that was 24 trips for 26 lanes.
 */
class RestockLoadTest {
    private static final int LONG = 200_000;
    private static final int LANE_CELLS = 5 * 128;
    private static final String[] COLOURS = {Sim.WHITE, Sim.RED, Sim.BLACK, Sim.LIME,
        "minecraft:blue_carpet", "minecraft:yellow_carpet"};
    private static final Pattern PLACED = Pattern.compile(" (\\d+) placed");

    /** Six colours dithered at random over the whole 128x128 tile: every lane needs every colour. */
    private static TileArt dithered(long seed) {
        Random r = new Random(seed);
        TileArt a = new TileArt();
        for (int pz = 0; pz < 128; pz++)
            for (int px = 0; px < 128; px++) a.set(px, pz, BlockSpec.of(COLOURS[r.nextInt(COLOURS.length)]));
        return a;
    }

    /** One BUILD round per trip: cells placed in each, in order. */
    record Run(Sim sim, int lanes, List<Integer> placedPerTrip, int emptyWithdrawRoundsWithoutProgress,
               int maxMaterialSlots, List<String> log) {
        int trips() {
            return placedPerTrip.size();
        }

        double lanesPerTrip() {
            return (double) lanes / trips();
        }

        /** Mean load of the full trips (all but the tile's last, which takes what is left), in lanes of 640 cells. */
        double lanesPerFullTrip() {
            return placedPerTrip.subList(0, trips() - 1).stream().mapToInt(Integer::intValue).average().orElse(0) / LANE_CELLS;
        }

        String rounds() {
            StringBuilder sb = new StringBuilder();
            for (String l : log)
                if (l.startsWith("INFO restock:") || l.startsWith("INFO build round:") || l.startsWith("INFO withdraw from"))
                    sb.append('\n').append(l);
            return sb.toString();
        }
    }

    private static Sim sim(int materialSlots, RecordingLogger log) {
        Sim sim = new Sim();
        // 36 slots - pickaxe - carrots = 34 free; reservedSlots counts those 2 kept stacks
        sim.config.build.reservedSlots = 36 - materialSlots;
        sim.config.build.placeMode = "LOOK"; // the loads below are counted in 5-wide lanes
        sim.bot.wholeStackWithdrawals = true;
        for (int i = 0; i < COLOURS.length; i++) {
            sim.bot.addContainer(new Pos(-100 + 2 * i, 61, 104), Map.of(COLOURS[i], 1728));
            sim.bot.addContainer(new Pos(-100 + 2 * i, 61, 106), Map.of(COLOURS[i], 1728));
        }
        sim.newEngine(new SerpentineBuildPlanner(), new GreedyRestockPlanner(), log);
        return sim;
    }

    /** Slots the carpets take in the (compact) fake inventory. */
    private static int materialSlots(Sim sim) {
        int n = 0;
        for (var e : sim.inv.items.entrySet()) if (e.getKey().endsWith("_carpet")) n += (e.getValue() + 63) / 64;
        return n;
    }

    /** Builds {@code art} on the moss platform with {@code materialSlots} inventory slots for materials. */
    private static Run run(TileArt art, int materialSlots) {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(materialSlots, log);
        int lanes = new SerpentineBuildPlanner().plan(art, sim.moss.geometry(), sim.catalog, io.github.digitalisp.mapart.bot.impl.PlaceMode.laneHalfWidth(sim.config.build),
            p -> false).size();
        Job job = sim.addJob("j0050", "Loads", 1, 1, List.of("moss"), (x, z) -> art);
        int[] maxSlots = {0};
        sim.start();
        assertTrue(sim.runUntil(() -> {
            maxSlots[0] = Math.max(maxSlots[0], materialSlots(sim));
            return job.status == JobStatus.DONE;
        }, LONG), sim.state() + tail(log.lines));

        List<Integer> placed = new ArrayList<>();
        int empty = 0;
        boolean pendingEmpty = false;
        for (String l : log.at(Level.INFO)) {
            if (l.startsWith("restock:")) {
                pendingEmpty = l.contains("withdraw {}");
            } else if (l.startsWith("build round:")) {
                Matcher m = PLACED.matcher(l);
                assertTrue(m.find(), l);
                int n = Integer.parseInt(m.group(1));
                placed.add(n);
                if (pendingEmpty && n == 0) empty++;
                pendingEmpty = false;
            }
        }
        Run r = new Run(sim, lanes, placed, empty, maxSlots[0], log.lines);
        System.out.printf("restock load: %d material slots, %d lanes, %d trips, %.2f lanes per trip, %.2f per full trip, "
            + "placed per trip %s%n", materialSlots, lanes, r.trips(), r.lanesPerTrip(), r.lanesPerFullTrip(), placed);
        return r;
    }

    private static String tail(List<String> l) {
        return "\n" + String.join("\n", l.subList(Math.max(0, l.size() - 60), l.size()));
    }

    @Test
    void fullSixColourTileFillsTheInventoryEveryTrip() {
        Run r = run(dithered(42), 27);
        String rounds = r.rounds();
        assertEquals(26, r.lanes());
        assertEquals(128 * 128, r.sim().bot.placed, "every cell placed once" + rounds);
        // 16384 cells, 1728 per trip at most: 10 trips is the floor. Whole-stack withdrawals leave a partial stack of
        // each colour carried over, so the loads are ~94% full and the tile takes 11 trips (24 before the fix).
        assertTrue(r.trips() <= 11, r.trips() + " trips" + rounds);
        assertTrue(r.lanesPerFullTrip() >= 2.5, String.format("%.2f lanes per full trip%s", r.lanesPerFullTrip(), rounds));
        assertTrue(r.lanesPerTrip() >= 2.3, String.format("%.2f lanes per trip%s", r.lanesPerTrip(), rounds));
        assertEquals(0, r.emptyWithdrawRoundsWithoutProgress(), "\"withdraw {}\" rounds without a placement" + rounds);
        assertTrue(r.placedPerTrip().stream().allMatch(n -> n > 0), "every trip placed something" + rounds);
        assertTrue(r.maxMaterialSlots() <= 27, "reservedSlots kept free: " + r.maxMaterialSlots() + " material slots");
        assertTrue(r.log().stream().noneMatch(l -> l.startsWith("INFO withdraw from")), "no short withdrawal" + rounds);
        assertEquals(1, r.sim().store.entries("VERIFY"), "partial lanes go back to RESTOCK, not VERIFY: " + r.sim().store.phaseLog);
        assertEquals(1, r.sim().store.entries("STOCK_CHECK"), "no no-progress escalation: " + r.sim().store.phaseLog);
        assertTrue(r.sim().notifier.of("alert").isEmpty(), r.sim().notifier.toString());
    }

    @Test
    void loadUsedUpOnAPartialLaneIsProgressNotAStall() {
        // 1 material slot: every load is 64 cells of a 65-cell lane, so every round ends with "load used up"
        TileArt art = Sim.single(Sim.WHITE, 13);
        Run r = run(art, 1);
        String rounds = r.rounds();
        assertEquals(13 * 128, r.sim().bot.placed, "every cell placed once" + rounds);
        assertTrue(r.placedPerTrip().stream().allMatch(n -> n > 0), "every round placed something" + rounds);
        assertEquals(13 * 128 / 64, r.trips(), "a full stack per trip" + rounds);
        assertTrue(r.log().stream().filter(l -> l.startsWith("INFO restock:") && l.contains(" cells of the next")).count()
            >= r.trips() - 2, "partial lanes planned" + rounds);
        assertEquals(1, r.sim().store.entries("VERIFY"), r.sim().store.phaseLog.toString());
        assertEquals(1, r.sim().store.entries("STOCK_CHECK"), "no no-progress escalation: " + r.sim().store.phaseLog);
        assertTrue(r.maxMaterialSlots() <= 1, r.maxMaterialSlots() + " material slots");
    }

    @Test
    void lastLoadLeavesRoomForTheMapAndThePane() {
        // 34 material slots and no reserve beyond the 2 kept stacks; the tile is exactly 34 stacks of white
        TileArt art = Sim.single(Sim.WHITE, 17);
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(34, log);
        Job job = sim.addJob("j0051", "Exact", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log.lines));
        String history = String.join("\n", sim.bot.history);
        assertEquals(1, sim.bot.count("withdraw " + Sim.MAPS), "the map comes with the last load, not in FILL_MAP:\n" + history);
        assertEquals(1, sim.bot.count("withdraw " + Sim.PANES), "the pane comes with the last load:\n" + history);
        assertTrue(log.at(Level.INFO).stream().noneMatch(l -> l.startsWith("withdraw from")), tail(log.lines));
    }
}
