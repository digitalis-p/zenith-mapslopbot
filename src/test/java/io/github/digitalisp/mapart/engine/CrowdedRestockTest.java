package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.bot.impl.PlaceMode;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.ConfiguredRestockPlanner;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.plan.RestockPlanner;
import io.github.digitalisp.mapart.plan.SerpentineBuildPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live 2b2t, job j0002 "ellen" (build 61d6411): a mixed art (gray terracotta and gray carpet, some 30 odd blocks for
 * the shading), AIRPLACE 9-wide lanes, 15 lanes, ~25 material slots. BUILD → RESTOCK 29 times in 5 lanes, loads of a
 * few cells ("load covers 0 + 36 cells of the next, withdraw {gray_carpet=36}" three times in a row, "load covers 0,
 * withdraw {}"). RESTOCK kept every partial stack a later lane or a cell left to VERIFY still needed; whole-stack
 * withdrawals leave one per odd block, so the slots filled up with colours the next lane does not use and each load
 * got the slot or two left. RESTOCK now gets rid of the partial stacks no lane of the load uses when the load is
 * crowded ({@code BuildEngine.keepForLoad}).
 *
 * <p>Before the fix, the same tiles: 86 restocks (5.73 per lane, 11 with "withdraw {}"); with 27 cells left to VERIFY
 * 137 restocks (9.13 per lane, 20 with "withdraw {}").
 */
class CrowdedRestockTest {
    private static final int LONG = 400_000;
    private static final int MATERIAL_SLOTS = 25;
    static final String GRAY_TERRACOTTA = "minecraft:gray_terracotta";
    static final String GRAY_CARPET = "minecraft:gray_carpet";
    private static final String[] ODD = {
        "minecraft:pointed_dripstone", "minecraft:oak_pressure_plate", "minecraft:light_blue_terracotta",
        "minecraft:cyan_terracotta", "minecraft:prismarine_bricks", "minecraft:blue_carpet", "minecraft:grass_block",
        "minecraft:light_blue_carpet", "minecraft:lime_carpet", "minecraft:yellow_terracotta",
        "minecraft:birch_pressure_plate", "minecraft:spruce_pressure_plate", "minecraft:pale_oak_pressure_plate",
        "minecraft:purple_terracotta", "minecraft:lime_terracotta", "minecraft:glow_lichen",
        "minecraft:stone_pressure_plate", "minecraft:raw_iron_block", "minecraft:magenta_terracotta",
        "minecraft:light_gray_terracotta", "minecraft:white_candle", "minecraft:cyan_carpet", "minecraft:packed_ice",
        "minecraft:warped_nylium", "minecraft:warped_pressure_plate", "minecraft:heavy_weighted_pressure_plate",
        "minecraft:white_carpet", "minecraft:yellow_carpet", "minecraft:jungle_pressure_plate",
        "minecraft:pink_terracotta", "minecraft:clay"};
    private static final Pattern MAP = Pattern.compile("\\{([^}]*)}");

    /**
     * Gray terracotta (2/3) and gray carpet (1/3) everywhere; {@code odd} of the cells are odd blocks, each odd block
     * used in about {@code spread} of the lanes (a few cells per lane), like the shading of a photo: every lane needs
     * a dozen or more odd blocks, and each of them comes back in later lanes.
     */
    static TileArt ellenLike(long seed, double odd, double spread) {
        Random r = new Random(seed);
        int lanes = (128 + 8) / 9;
        List<List<String>> perLane = new ArrayList<>();
        for (int k = 0; k < lanes; k++) perLane.add(new ArrayList<>());
        for (String id : ODD) {
            for (int k = 0; k < lanes; k++) if (r.nextDouble() < spread) perLane.get(k).add(id);
        }
        TileArt a = new TileArt();
        for (int pz = 0; pz < 128; pz++) {
            for (int px = 0; px < 128; px++) {
                List<String> here = perLane.get(px / 9);
                double v = r.nextDouble();
                String id = v < odd && !here.isEmpty() ? here.get(r.nextInt(here.size()))
                    : r.nextInt(3) < 2 ? GRAY_TERRACOTTA : GRAY_CARPET;
                a.set(px, pz, BlockSpec.of(id));
            }
        }
        return a;
    }

    record Run(int lanes, int restocks, int emptyWithdraws, int drops, int itemsDropped, int placed, int verifyPasses,
               List<String> rounds) {
        double restocksPerLane() {
            return (double) restocks / lanes;
        }

        String log() {
            return "\n" + String.join("\n", rounds);
        }
    }

    /**
     * Builds {@code art} on moss until it is verified (FILL_MAP): AIRPLACE 9-wide lanes, whole-stack withdrawals, the
     * live drop point, a totem in the offhand, XP bottles, food and a pickaxe. {@code rejectOnce} odd cells are
     * rejected once with the item in hand (live: rejected airplace clicks), so they are left to VERIFY.
     */
    static Run build(TileArt art, int rejectOnce) {
        return build(art, rejectOnce, new GreedyRestockPlanner(), sim -> { });
    }

    /** {@link #build(TileArt, int)} with {@code planner} and the config changed by {@code tweak}. */
    static Run build(TileArt art, int rejectOnce, RestockPlanner planner, Consumer<Sim> tweak) {
        RecordingLogger log = new RecordingLogger();
        Sim sim = new Sim();
        sim.config.build.placeMode = "AIRPLACE";
        sim.config.build.airPlaceRange = 5.0;
        sim.config.build.airPlaceLaneHalfWidth = -1;
        // 36 slots, pickaxe + carrots + XP bottles inside the reserve: 25 empty slots for materials, like live
        sim.config.build.reservedSlots = 36 - MATERIAL_SLOTS;
        sim.bot.wholeStackWithdrawals = true;
        sim.inv.add("minecraft:experience_bottle", 16);
        sim.inv.offhand.put("minecraft:totem_of_undying", 1);
        // live: no return chest, leftovers go into the water channel to the sorter
        sim.config.site.storage.returnChests.clear();
        MapArtConfig.DropPointCfg drop = new MapArtConfig.DropPointCfg();
        drop.standAt = new Pos(-65, 64, -66);
        drop.target = new Pos(-65, 60, -62);
        sim.config.site.storage.returnDrops.add(drop);
        Map<String, Integer> counts = new TreeMap<>();
        art.blockCounts().forEach((k, v) -> counts.merge(BlockSpec.parse(k).id(), v, Integer::sum));
        int i = 0;
        for (var e : counts.entrySet()) {
            for (int left = e.getValue() + 64; left > 0; left -= 1728, i++)
                sim.bot.addContainer(new Pos(-100 + 2 * (i % 10), 61, 102 + 2 * (i / 10)), Map.of(e.getKey(), 1728));
        }
        assertTrue(i <= 40, i + " chests do not fit the material region");
        Set<Pos> rejected = new HashSet<>();
        Random r = new Random(7);
        while (rejected.size() < rejectOnce) {
            int px = r.nextInt(128), pz = r.nextInt(128);
            String id = art.get(px, pz).id();
            if (!id.equals(GRAY_TERRACOTTA) && !id.equals(GRAY_CARPET)) rejected.add(sim.moss.geometry().cell(px, pz));
        }
        sim.bot.unplaceable = p -> rejected.remove(p.pos()) ? "rejected" : null;
        tweak.accept(sim);
        sim.newEngine(new SerpentineBuildPlanner(), planner, log);
        int lanes = new SerpentineBuildPlanner().plan(art, sim.moss.geometry(), sim.catalog,
            PlaceMode.laneHalfWidth(sim.config.build), p -> false).size();
        sim.addJob("j0002", "ellen", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        boolean built = sim.runUntil(() -> sim.phase() == Phase.FILL_MAP || sim.phase() == Phase.BLOCKED, LONG);

        List<String> rounds = new ArrayList<>();
        int restocks = 0;
        int empty = 0;
        int drops = 0;
        int passes = 0;
        Set<String> madeRoomFor = new HashSet<>();
        for (String l : log.at(Level.INFO)) {
            if (l.startsWith("restock:")) {
                restocks++;
                if (l.contains("withdraw {}")) empty++;
                Set<String> again = new HashSet<>(madeRoomFor);
                again.retainAll(ids(l.substring(l.indexOf("withdraw "))));
                assertEquals(Set.of(), again, "dropped to make room, then withdrawn for the same load: " + l + "\n"
                    + String.join("\n", rounds.subList(Math.max(0, rounds.size() - 6), rounds.size())));
                madeRoomFor.clear();
            }
            // what keepForLoad and the crowded-load check return, both before the same load (without
            // build.crowdKeepReused the crowded return may take what the load withdraws again: the behaviour it fixed)
            if (l.startsWith("making room:") || l.startsWith("crowded load:") && sim.config.build.crowdKeepReused)
                madeRoomFor.addAll(ids(l));
            if (l.startsWith("dropping ")) drops++;
            if (l.startsWith("verify pass")) passes++;
            if (l.startsWith("restock") || l.startsWith("build round") || l.startsWith("dropping ")
                || l.startsWith("making room") || l.startsWith("verify pass") || l.startsWith("crowded load")
                || l.startsWith("leftovers stay")) rounds.add(l);
        }
        String all = "\n" + String.join("\n", rounds);
        assertTrue(built && sim.phase() == Phase.FILL_MAP, sim.state() + all);
        assertTrue(sim.matchesArt(sim.moss, art), "the art is complete");
        // materials only leave the inventory
        assertEquals(1, sim.inv.offhand.get("minecraft:totem_of_undying"), "the offhand totem stays");
        assertEquals(16, sim.inv.count("minecraft:experience_bottle"), "XP bottles are never dropped");
        assertEquals(1, sim.inv.count("minecraft:diamond_pickaxe"), "the pickaxe stays");
        assertTrue(sim.inv.count("minecraft:golden_carrot") > 0, "food stays");
        for (String id : sim.bot.droppedItems.keySet())
            assertTrue(counts.containsKey(id), "dropped a non-material: " + id);
        int itemsDropped = sim.bot.droppedItems.values().stream().mapToInt(Integer::intValue).sum();
        Run run = new Run(lanes, restocks, empty, drops, itemsDropped, sim.bot.placed, passes, rounds);
        System.out.printf("crowded restock (%d cells to VERIFY): %d lanes, %d restocks (%.2f per lane), %d with an "
                + "empty withdrawal, %d drops (%d items), %d verify passes%n", rejectOnce, lanes, restocks,
            run.restocksPerLane(), empty, drops, itemsDropped, passes);
        return run;
    }

    /** Item ids of the first "{id=n, ...}" in {@code line}. */
    private static Set<String> ids(String line) {
        Set<String> out = new HashSet<>();
        Matcher m = MAP.matcher(line);
        if (!m.find() || m.group(1).isBlank()) return out;
        for (String kv : m.group(1).split(", ")) out.add(kv.substring(0, kv.indexOf('=')));
        return out;
    }

    @Test
    void mixedArtFillsTheFreeSlotsEveryLoad() {
        TileArt art = ellenLike(2, 0.07, 0.45);
        Run r = build(art, 0);
        assertEquals(15, r.lanes(), "9-wide airplace lanes");
        assertEquals(art.nonEmptyCount(), r.placed(), "every cell placed once" + r.log());
        assertEquals(0, r.emptyWithdraws(), "no restock with nothing to withdraw" + r.log());
        // 86 restocks (5.73 per lane) before the fix
        assertTrue(r.restocksPerLane() <= 1.5, String.format("%.2f restocks per lane%s", r.restocksPerLane(), r.log()));
        assertFalse(r.rounds().stream().anyMatch(l -> l.matches("restock: .*load covers 0 \\+ [0-9]{1,2} cells.*")),
            "no load of a handful of cells" + r.log());
    }

    /** RESTOCKs with more than one drop (the leftovers, then the crowded load's return). */
    static int restocksWithTwoDrops(Run r) {
        int n = 0;
        int drops = 0;
        for (String l : r.rounds()) {
            if (l.startsWith("dropping ")) drops++;
            if (l.startsWith("restock:")) {
                if (drops >= 2) n++;
                drops = 0;
            }
        }
        return n;
    }

    /**
     * Live (2b2t, 15:31-16:17): 6 of 13 restocks dropped twice, the leftovers and then what crowded the load, the
     * second drop 1-6 s more. With {@code build.mergeDrops} the crowded check is planned first and both go in one drop.
     */
    @Test
    void theLeftoversAndWhatCrowdsTheLoadGoInOneDrop() {
        TileArt art = ellenLike(2, 0.07, 0.45);
        RestockPlanner split = new ConfiguredRestockPlanner(() -> "AUTO");
        Run two = build(art, 0, split, sim -> {
            sim.config.build.mergeDrops = false;
            sim.config.build.topUpStacks = true;
        });
        Run one = build(art, 0, new ConfiguredRestockPlanner(() -> "AUTO"), sim -> {
            sim.config.build.mergeDrops = true;
            sim.config.build.topUpStacks = true;
        });
        assertEquals(art.nonEmptyCount(), one.placed(), "every cell placed once" + one.log());
        assertEquals(0, one.emptyWithdraws(), "no restock with nothing to withdraw" + one.log());
        int before = restocksWithTwoDrops(two);
        int after = restocksWithTwoDrops(one);
        long merged = one.rounds().stream().filter(l -> l.contains("returning them with the leftovers")).count();
        System.out.printf("merged drops: %d restocks with two drops before, %d after (%d merged); drops %d -> %d,"
            + " restocks %d -> %d%n", before, after, merged, two.drops(), one.drops(), two.restocks(), one.restocks());
        assertTrue(before > 0, "the scenario has restocks with two drops" + two.log());
        assertTrue(merged > 0, "crowded loads returned with the leftovers" + one.log());
        assertTrue(after < before, after + " restocks with two drops, " + before + " before" + one.log());
        assertTrue(one.drops() < two.drops(), one.drops() + " drops, " + two.drops() + " before");
        assertTrue(one.restocksPerLane() <= two.restocksPerLane() + 0.2,
            String.format("%.2f restocks per lane, %.2f before", one.restocksPerLane(), two.restocksPerLane()));
    }

    /**
     * Live (2b2t, 8 maps): 52 of 98 restocks dropped, 6-12 s each beyond the walk the sweep needs anyway, mostly one stack
     * of an item the tile no longer used, and 9 drops took items the same restock withdrew again. With
     * {@code build.lazyDrop} the leftovers stay until dropping them pays, with {@code build.crowdKeepReused} a crowded
     * load's return keeps the items the freed load needs again.
     */
    @Test
    void leftoversStayUntilTheyPayAndReturnsKeepWhatTheLoadNeedsAgain() {
        TileArt art = ellenLike(2, 0.07, 0.45);
        Run before = build(art, 0, new ConfiguredRestockPlanner(() -> "AUTO"), sim -> {
            sim.config.build.lazyDrop = false;
            sim.config.build.crowdKeepReused = false;
            sim.config.build.topUpStacks = true;
        });
        Run after = build(art, 0, new ConfiguredRestockPlanner(() -> "AUTO"), sim -> {
            sim.config.build.lazyDrop = true;
            sim.config.build.crowdKeepReused = true;
            sim.config.build.topUpStacks = true;
        });
        long stayed = after.rounds().stream().filter(l -> l.startsWith("leftovers stay")).count();
        System.out.printf("lazy drops: drops %d -> %d, items dropped %d -> %d, restocks %d -> %d (%d times the leftovers"
            + " stayed)%n", before.drops(), after.drops(), before.itemsDropped(), after.itemsDropped(),
            before.restocks(), after.restocks(), stayed);
        assertEquals(art.nonEmptyCount(), after.placed(), "every cell placed once" + after.log());
        assertTrue(after.drops() < before.drops(), after.drops() + " drops, " + before.drops() + " before" + after.log());
        assertTrue(after.restocks() <= before.restocks() + 2, after.restocks() + " restocks, " + before.restocks()
            + " before");
    }

    @Test
    void cellsLeftToVerifyDoNotHoldTheSlots() {
        // live: 27 cells left to VERIFY by lane 5, their items kept as partial stacks (137 restocks here before)
        TileArt art = ellenLike(2, 0.07, 0.45);
        Run r = build(art, 27);
        assertEquals(art.nonEmptyCount(), r.placed(), "every cell placed once" + r.log());
        assertEquals(0, r.emptyWithdraws(), "no restock with nothing to withdraw" + r.log());
        assertTrue(r.restocksPerLane() <= 1.5, String.format("%.2f restocks per lane%s", r.restocksPerLane(), r.log()));
        // VERIFY fetches the items it lacks through RESTOCK: at most one pass more than with them held
        assertTrue(r.verifyPasses() <= 2, r.verifyPasses() + " verify passes" + r.log());
    }
}
