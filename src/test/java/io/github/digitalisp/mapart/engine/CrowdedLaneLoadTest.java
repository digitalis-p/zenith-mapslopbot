package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.PlaceMode;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.ConfiguredRestockPlanner;
import io.github.digitalisp.mapart.plan.SerpentineBuildPlanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live 2026-10-31 17:45-17:49 (map "haruhi", tile 1,2, lane band 6-7): a palette of many items (carpets, every
 * wood pressure plate, decorated pots, resin clumps, pointed dripstone) in short runs of whole lane rows, partial stacks
 * carried for further on in the band. Six loads in a row covered one run each ("load covers 0 + 9 cells of the next,
 * withdraw {birch_pressure_plate=64}", then 9, 9, 27, 108, 72 cells, "planned: 3 loads left" every time, 15-20 s a
 * trip), where the load as carried covered 650-780 cells (keepForLoad's estimate). The split DP's later loads count only
 * their own cells, so "one run now, the rest of the lane next load" looked cheapest; the next load had the same carried
 * stacks and the same plan ({@code build.restockCarriedGuard}). And the crowded-load return
 * ({@code RestockLoads.choose}) worked at lane granularity: with every unused carried item gone the load reached the
 * whole band, every one counted as needed again, and nothing went back ({@code build.crowdLaneCells}: the carried items
 * used furthest go back, Belady).
 */
class CrowdedLaneLoadTest {
    private static final Pattern LOAD = Pattern.compile("load covers (\\d+) \\+ (\\d+) cells");

    /** 40 materials (one chest each): carpets, pressure plates, terracotta and the odd blocks of the live palette. */
    static final List<String> PALETTE = palette();

    private static List<String> palette() {
        List<String> p = new ArrayList<>();
        for (String c : new String[] {"white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow", "lime",
            "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink"}) {
            p.add("minecraft:" + c + "_carpet");
            if (p.size() < 20) p.add("minecraft:" + c + "_terracotta");
        }
        for (String w : new String[] {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
            "pale_oak", "bamboo", "crimson", "warped"}) {
            p.add("minecraft:" + w + "_pressure_plate");
        }
        p.add("minecraft:pointed_dripstone");
        p.add("minecraft:packed_ice");
        return List.copyOf(p);
    }

    /**
     * The first {@code lanes} 9-wide lanes (the rest of the tile empty: a band, as the live one) in runs of 1 to
     * {@code maxRun} whole rows (9 to 9·maxRun cells) of one material drawn from {@link #PALETTE}: some 25-30 materials
     * a lane, each coming back a few times.
     */
    static TileArt haruhiLike(long seed, int maxRun, int lanes) {
        Random r = new Random(seed);
        TileArt a = new TileArt();
        for (int lane = 0; lane < lanes; lane++) {
            int pz = 0;
            while (pz < 128) {
                int run = 1 + r.nextInt(maxRun);
                String id = PALETTE.get(r.nextInt(PALETTE.size()));
                for (int z = pz; z < Math.min(128, pz + run); z++) {
                    for (int px = lane * 9; px < Math.min(128, lane * 9 + 9); px++) a.set(px, z, BlockSpec.of(id));
                }
                pz += run;
            }
        }
        return a;
    }

    /**
     * The live inventory: partial stacks (20-59) of the materials the first lane uses after its first {@code early}
     * materials (in the order they first come), at most {@code max}; 25 material slots. With {@code tight} their
     * chests hold only 5 more than the band needs beyond the carried stack: such a stack stays until the band has used
     * most of it (live: rare pressure plates, pots, resin).
     */
    static void lateStacks(Sim sim, TileArt art, int early, int max, boolean tight) {
        List<Lane> lanes = new SerpentineBuildPlanner().plan(art, sim.moss.geometry(), sim.catalog,
            PlaceMode.laneHalfWidth(sim.config.build), p -> false);
        List<Placement> first = lanes.getFirst().placements();
        Map<String, Integer> firstUse = new LinkedHashMap<>();
        for (int i = 0; i < first.size(); i++) firstUse.putIfAbsent(first.get(i).itemId(), i);
        Map<String, Integer> cells = new HashMap<>();
        for (Lane l : lanes) for (Placement p : l.placements()) cells.merge(p.itemId(), 1, Integer::sum);
        List<String> late = new ArrayList<>(firstUse.keySet());
        late = late.subList(Math.min(early, late.size()), Math.min(early + max, late.size()));
        for (int i = 0; i < late.size(); i++) {
            String id = late.get(i);
            int held = 20 + (i * 7) % 40;
            sim.inv.add(id, held);
            if (!tight) continue;
            for (FakeBot.Container c : sim.bot.containers.values()) {
                if (c.items.containsKey(id)) c.items.put(id, Math.max(0, cells.get(id) - held) + 5);
            }
        }
        System.out.printf("late stacks: %d of the %d materials of the first lane (%d cells), first used from cell %d"
            + " on%s%n", late.size(), firstUse.size(), first.size(), firstUse.get(late.getFirst()),
            tight ? "; their stock covers the band only with them" : "");
    }

    /**
     * The band built from the late stacks ({@code early} 3, at most 24), with the carried-stacks guard and the
     * within-lane return on or off.
     */
    static CrowdedRestockTest.Run run(TileArt art, boolean tight, boolean guard, boolean laneCells) {
        AtomicBoolean guardOn = new AtomicBoolean(guard);
        return CrowdedRestockTest.build(art, 0, new ConfiguredRestockPlanner(() -> "AUTO", guardOn::get), sim -> {
            sim.config.build.topUpStacks = true;
            sim.config.build.restockCarriedGuard = guard;
            sim.config.build.crowdLaneCells = laneCells;
            lateStacks(sim, art, 3, 24, tight);
        });
    }

    /** Cells of each restock's load that ends inside a lane ("load covers 0 + N cells"). */
    static List<Integer> loads(CrowdedRestockTest.Run run) {
        List<Integer> out = new ArrayList<>();
        for (String l : run.rounds()) {
            if (!l.startsWith("restock:")) continue;
            Matcher m = LOAD.matcher(l);
            if (m.find() && m.group(1).equals("0")) out.add(Integer.parseInt(m.group(2)));
        }
        return out;
    }

    static long small(CrowdedRestockTest.Run run, int under) {
        return loads(run).stream().filter(n -> n < under).count();
    }

    private static String summary(String name, CrowdedRestockTest.Run r) {
        int cells = r.placed();
        return String.format("%s: %d restocks, %.0f cells a load, %d loads under 64 cells, %d drops; loads %s", name,
            r.restocks(), (double) cells / Math.max(1, r.restocks()), small(r, 64), r.drops(), loads(r));
    }

    /**
     * The live pattern: carried stacks that cannot go back (their stock covers the band only with them), loads of one
     * run, "planned: N loads left" the same each time. With the carried guard the DP's first load is the longest that
     * fits.
     */
    @Test
    void aBandOfShortRunsIsNotBuiltOneRunALoad() {
        for (long seed : new long[] {14, 1}) {
            TileArt art = haruhiLike(seed, 3, 2);
            CrowdedRestockTest.Run before = run(art, true, false, false);
            CrowdedRestockTest.Run after = run(art, true, true, true);
            System.out.println(summary("seed " + seed + " before", before));
            System.out.println(summary("seed " + seed + " after", after));
            assertEquals(art.nonEmptyCount(), after.placed(), "every cell placed once" + after.log());
            assertTrue(small(before, 64) >= 5, "the live pattern: loads of a few rows" + before.log());
            assertTrue(after.restocks() + 3 <= before.restocks(), after.restocks() + " restocks, " + before.restocks()
                + " before" + after.log());
            assertTrue(small(after, 64) * 2 <= small(before, 64), small(after, 64) + " loads under 64 cells, "
                + small(before, 64) + " before" + after.log());
        }
    }

    /**
     * The same band with stock enough for every carried stack: the freed load (all of them gone) reached the whole
     * band, so the crowded check kept every one; now the stacks used furthest go back, as many as let the load cover
     * the most cells.
     */
    @Test
    void carriedStacksThatCanGoBackGoFurthestFirst() {
        TileArt art = haruhiLike(14, 3, 2);
        CrowdedRestockTest.Run before = run(art, false, false, false);
        CrowdedRestockTest.Run lane = run(art, false, false, true);
        CrowdedRestockTest.Run after = run(art, false, true, true);
        System.out.println(summary("returnable before", before));
        System.out.println(summary("returnable, lane return only", lane));
        System.out.println(summary("returnable after", after));
        assertEquals(art.nonEmptyCount(), lane.placed(), "every cell placed once" + lane.log());
        assertTrue(small(before, 64) >= 2, "loads of a few rows" + before.log());
        assertTrue(lane.restocks() < before.restocks(), lane.restocks() + " restocks, " + before.restocks() + " before"
            + lane.log());
        assertEquals(0, small(lane, 64), "no load of a few rows" + lane.log());
        assertTrue(after.restocks() <= lane.restocks(), after.restocks() + " restocks with both" + after.log());
    }

    /**
     * A whole tile of short runs, from an empty inventory: keepForLoad's estimate counts top-up as the load does (it
     * dropped two items "not used by the next lane" that the same load then withdrew again); nothing returned to make
     * room is withdrawn for the same load ({@link CrowdedRestockTest#build} checks every restock).
     */
    @Test
    void aTileOfShortRunsNeverWithdrawsWhatItReturned() {
        TileArt art = haruhiLike(31, 6, 15);
        CrowdedRestockTest.Run r = CrowdedRestockTest.build(art, 0, new ConfiguredRestockPlanner(() -> "AUTO"),
            sim -> sim.config.build.topUpStacks = true);
        System.out.println(summary("tile", r));
        assertEquals(art.nonEmptyCount(), r.placed(), "every cell placed once" + r.log());
        assertTrue(r.restocksPerLane() <= 2.0, String.format("%.2f restocks per lane%s", r.restocksPerLane(), r.log()));
    }
}
