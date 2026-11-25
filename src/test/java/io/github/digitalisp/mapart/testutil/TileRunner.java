package io.github.digitalisp.mapart.testutil;

import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.AdaptivePacer;
import io.github.digitalisp.mapart.bot.impl.LaneRun;
import io.github.digitalisp.mapart.bot.impl.PlaceMode;
import io.github.digitalisp.mapart.bot.impl.TopFaceRules;
import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.LevelMerge;
import io.github.digitalisp.mapart.plan.RestockLoads;
import io.github.digitalisp.mapart.plan.SerpentineBuildPlanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.jspecify.annotations.Nullable;

/**
 * Builds a tile with the production planner and {@link LaneRun} on a {@link LaneSim} platform, like the engine's
 * BUILD and VERIFY (repair passes re-plan from the world) without restocking: the inventory never runs out.
 */
public final class TileRunner {
    public final Map<Pos, BlockSpec> world = new HashMap<>();
    public final PlatformGeometry geo = new PlatformGeometry(-64, -64, 64);
    public final MapArtConfig.BuildCfg build = new MapArtConfig.BuildCfg();
    public final LaneSim sim;
    public final List<String> laneLog = new ArrayList<>();
    public int passes;
    public int lanesRun;
    public long ticks;
    /**
     * The walk a carpet-only tile of this shape needs: the lane lengths of the first (BUILD) pass plus the straight
     * walks from each lane's end to the next lane's start.
     */
    public double baseline;
    public int failures;
    private boolean started;
    /** Items the inventory does not have (a material that ran out). */
    public final java.util.Set<String> missingItems = new java.util.HashSet<>();
    /** No stand area: the walk alone (cells it leaves are skipped as before). */
    public boolean noSpots;
    /**
     * A finite load ({@link #stock}): items count down as the "server" accepts placements, and an item not in the map
     * is missing. Off (default): every item but {@link #missingItems} is there without limit.
     */
    public boolean finiteStock;
    public final Map<String, Integer> stock = new HashMap<>();
    /**
     * Loads: lanes in order, whole lanes, at most this many cells each (at least one lane); 0 = the whole pass is one
     * load. With the in-reach greedy ({@code build.placeInReachGreedy}) a lane run gets the later lanes of its load
     * (their cells still to place) and starts without its cells already placed, as the engine does.
     */
    public int loadCells;
    /** Where the bot stands at each load's start (a restock trip, not timed): x, z off the tile; null = no trips. */
    public double @Nullable [] sorter;
    /** Loads started. */
    public int loads;

    private static final BlockCatalog CATALOG = new BlockCatalog() {
        @Override public boolean isKnownBlock(String blockId) { return true; }
        @Override public boolean isKnownItem(String itemId) { return true; }
        @Override public Optional<String> placementItem(BlockSpec spec) { return Optional.of(spec.id()); }
        @Override public int mapColorId(BlockSpec spec) { return 1; }
        @Override public boolean placeableOnTopFace(BlockSpec spec) { return true; }
        @Override public boolean matches(BlockSpec target, BlockSpec actual) { return TopFaceRules.equivalent(target, actual); }
        @Override public boolean isGravityBlock(String blockId) { return false; }
        @Override public int maxStackSize(String itemId) { return 64; }
    };

    public TileRunner(long seed) {
        sim = new LaneSim(new LaneSim.Blocks() {
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
                if (missingItems.contains(item)) return 0;
                return finiteStock ? stock.getOrDefault(item, 0) : 10_000;
            }

            @Override
            public boolean take(String item) {
                if (finiteStock) stock.merge(item, -1, Integer::sum);
                return true;
            }
        }, geo.minX(), geo.minZ(), geo.artY(), new AdaptivePacer(2, 1, 8), seed);
        sim.teleport(geo.minX() - 3.5, geo.artY(), geo.minZ() - 3.5);
        // the LOOK path with the real rotation and reach 4.3, as these simulations were written for (the defaults
        // before placement modes); the placement-mode tests set their own
        build.placeMode = "LOOK";
        build.rotatePlayer = true;
        build.reach = 4.3;
    }

    /** Lane parameters from {@link #build} (the simulator's silent looks follow it too). */
    public LaneRun.Params params() {
        sim.silent = PlaceMode.silentRotation(build);
        return LaneRun.Params.of(build, noSpots ? null : sim.tileArea());
    }

    /** BUILD pass plus up to {@code repairPasses} repair passes; true when the art layer matches. */
    public boolean build(TileArt art, int repairPasses) {
        SerpentineBuildPlanner planner = new SerpentineBuildPlanner(() -> build.laneEvenWidths);
        PlacementListener listener = new PlacementListener() {
            @Override public void placed(Placement placement) { }
            @Override public void skipped(Placement placement, String reason) { }
        };
        for (passes = 0; passes <= repairPasses; passes++) {
            List<Lane> lanes = planner.plan(art, geo, CATALOG, PlaceMode.laneHalfWidth(build),
                p -> TopFaceRules.equivalent(p.block(), world.getOrDefault(p.pos(), BlockSpec.AIR)));
            if (lanes.isEmpty()) return true;
            if (!started) {
                // the bot arrives from the chests: start it at the first lane, so walking counts only the tile
                started = true;
                Pos f = lanes.getFirst().from();
                sim.teleport(f.x() + 0.5, f.y(), f.z() + 0.5);
            }
            Pos prevTo = null;
            int loadEnd = 0;
            for (int li = 0; li < lanes.size(); li++) {
                Lane l = lanes.get(li);
                if (li >= loadEnd) { // the next load: whole lanes up to loadCells
                    int cells = 0;
                    loadEnd = li;
                    while (loadEnd < lanes.size() && (loadEnd == li || loadCells <= 0
                        || cells + lanes.get(loadEnd).placements().size() <= loadCells)) {
                        cells += lanes.get(loadEnd).placements().size();
                        loadEnd++;
                    }
                    loads++;
                    if (sorter != null) sim.teleport(sorter[0], geo.artY(), sorter[1]);
                }
                if (passes == 0) {
                    baseline += Math.abs(l.to().z() - l.from().z()) + Math.abs(l.to().x() - l.from().x());
                    if (prevTo != null) baseline += Math.hypot(l.from().x() - prevTo.x(), l.from().z() - prevTo.z());
                    prevTo = l.to();
                }
                int budget = 4000 + 400 * l.placements().size();
                TaskStatus s;
                if (build.placeInReachGreedy) {
                    Lane open = unplaced(l);
                    if (open.placements().isEmpty()) continue;
                    List<Lane> load = new ArrayList<>();
                    for (int j = li + 1; j < loadEnd; j++) {
                        Lane u = unplaced(lanes.get(j));
                        if (!u.placements().isEmpty()) load.add(u);
                    }
                    s = sim.runLane(open, params(), listener, budget, load);
                } else {
                    s = sim.runLane(l, params(), listener, budget);
                }
                lanesRun++;
                ticks += sim.lastTicks;
                if (s != TaskStatus.SUCCESS) failures++;
                laneLog.add("pass " + passes + " " + sim.log.getLast());
            }
        }
        return matches(art);
    }

    /**
     * Both levels of a colour-suppression layer in one sweep ({@code build.suppressionMergeLevels}, {@link LevelMerge}):
     * {@code level1} one block above {@code level0}, on {@link LaneSim#raisedLevel}. Rounds as the engine's restock
     * rounds: the lanes planned from the world, a load of whole lanes up to {@code loadCells} cells and the start of the
     * next one ({@link RestockLoads#cut}), each round re-planned, until nothing is left, a round places nothing, or
     * {@code maxRounds}. No repair pass. Returns the cells left (both levels). The run is told about the raised level
     * when {@link LaneSim#raisedRun} is set (the caller's).
     */
    public int buildLevels(TileArt level0, TileArt level1, int loadCells, int maxRounds) {
        sim.raisedLevel = true;
        SerpentineBuildPlanner planner = new SerpentineBuildPlanner(() -> build.laneEvenWidths);
        PlacementListener listener = new PlacementListener() {
            @Override public void placed(Placement placement) { }
            @Override public void skipped(Placement placement, String reason) { }
        };
        java.util.function.Predicate<Placement> built = p -> !world.getOrDefault(p.pos(), BlockSpec.AIR).isAir();
        for (int round = 0; round < maxRounds; round++) {
            List<Lane> lanes = LevelMerge.lanes(planner, level0, level1, geo, CATALOG, PlaceMode.laneHalfWidth(build),
                built, built);
            if (lanes.isEmpty()) break;
            if (!started) {
                started = true;
                Pos f = lanes.getFirst().from();
                sim.teleport(f.x() + 0.5, f.y(), f.z() + 0.5);
            }
            List<Lane> load = new ArrayList<>();
            int cells = 0;
            for (Lane l : lanes) {
                int n = l.placements().size();
                if (loadCells > 0 && cells + n > loadCells) { // the planner cuts a lane at the load's last cell
                    if (loadCells - cells > 0) load.add(RestockLoads.cut(l, loadCells - cells));
                    break;
                }
                load.add(l);
                cells += n;
            }
            loads++;
            if (sorter != null) sim.teleport(sorter[0], geo.artY(), sorter[1]);
            long acceptedBefore = sim.accepted;
            for (Lane l : load) {
                Lane open = unplaced(l);
                if (open.placements().isEmpty()) continue;
                TaskStatus s = sim.runLane(open, params(), listener, 4000 + 400 * open.placements().size());
                lanesRun++;
                ticks += sim.lastTicks;
                if (s != TaskStatus.SUCCESS) failures++;
                laneLog.add("round " + round + " " + sim.log.getLast());
            }
            if (sim.accepted == acceptedBefore) break;
        }
        return levelMismatches(level0, level1).size();
    }

    /** Cells of both levels that differ from the art (want → have); level 1 one block above level 0. */
    public List<String> levelMismatches(TileArt level0, TileArt level1) {
        List<String> out = new ArrayList<>();
        for (int pz = 0; pz < TileArt.SIZE; pz++) {
            for (int px = 0; px < TileArt.SIZE; px++) {
                for (int lv = 0; lv <= 1; lv++) {
                    BlockSpec want = (lv == 0 ? level0 : level1).get(px, pz);
                    BlockSpec have = world.getOrDefault(lv == 0 ? geo.cell(px, pz) : geo.cell(px, pz).above(), BlockSpec.AIR);
                    boolean ok = want == null ? have.isAir() : TopFaceRules.equivalent(want, have);
                    if (!ok) out.add(px + "," + pz + " y+" + lv + " " + (want == null ? "air" : want.id()) + " -> " + have.id());
                }
            }
        }
        return out;
    }

    /** VERIFY from the current world and bot position, including the repair load's seal-out refill. */
    public boolean verify(TileArt art, int repairPasses) {
        PlacementListener listener = new PlacementListener() {
            @Override public void placed(Placement placement) { }
            @Override public void skipped(Placement placement, String reason) { }
        };
        sim.art = c -> {
            int col = c.x() - geo.minX(), row = c.z() - geo.minZ();
            if (c.y() != geo.artY() || col < 0 || row < 0 || col >= TileArt.SIZE || row >= TileArt.SIZE) return null;
            BlockSpec b = art.get(col, row);
            return b == null ? null : new Placement(c, b, b.id());
        };
        for (passes = 0; passes < repairPasses && !matches(art); passes++) {
            List<Lane> lanes = plan(art);
            for (int i = 0; i < lanes.size(); i++) {
                Lane lane = unplaced(lanes.get(i));
                if (lane.placements().isEmpty()) continue;
                List<Lane> load = lanes.subList(i + 1, lanes.size());
                int cells = lane.placements().size() + load.stream().mapToInt(l -> l.placements().size()).sum();
                TaskStatus status = sim.runLane(lane, params().withBreakIn(build.verifyBreakIn)
                    .withSealOut(build.verifySealOut), listener, 4000 + 400 * cells, load);
                lanesRun++;
                ticks += sim.lastTicks;
                if (status != TaskStatus.SUCCESS) failures++;
            }
        }
        return matches(art);
    }

    /** {@code l} without the cells whose target the world already shows (the engine's in-reach greedy rule). */
    public Lane unplaced(Lane l) {
        List<Placement> ps = new ArrayList<>();
        for (Placement p : l.placements()) {
            if (!TopFaceRules.equivalent(p.block(), world.getOrDefault(p.pos(), BlockSpec.AIR))) ps.add(p);
        }
        return ps.size() == l.placements().size() ? l : new Lane(l.index(), l.from(), l.to(), List.copyOf(ps));
    }

    /** Lanes of {@code art} still to build, planned against the world like BUILD and VERIFY. */
    public List<Lane> plan(TileArt art) {
        return new SerpentineBuildPlanner(() -> build.laneEvenWidths).plan(art, geo, CATALOG,
            PlaceMode.laneHalfWidth(build),
            p -> TopFaceRules.equivalent(p.block(), world.getOrDefault(p.pos(), BlockSpec.AIR)));
    }

    public boolean matches(TileArt art) {
        return mismatches(art).isEmpty();
    }

    /** Cells that differ from the art (want → have). */
    public List<String> mismatches(TileArt art) {
        List<String> out = new ArrayList<>();
        for (int pz = 0; pz < TileArt.SIZE; pz++) {
            for (int px = 0; px < TileArt.SIZE; px++) {
                BlockSpec want = art.get(px, pz);
                BlockSpec have = world.getOrDefault(geo.cell(px, pz), BlockSpec.AIR);
                boolean ok = want == null ? have.isAir() : TopFaceRules.equivalent(want, have);
                if (!ok) out.add(px + "," + pz + " " + (want == null ? "air" : want.id()) + " -> " + have.id());
            }
        }
        return out;
    }

    /** Lanes that placed cells from stand spots (the walk left some, or the walking line was walled). */
    public long spotLanes() {
        return sim.log.stream().filter(l -> l.contains("cells to place from stand spots")
            || l.contains("walking line walled")).count();
    }

    /** Total walking (lane walking plus Baritone) over the baseline. */
    public double walkOverhead() {
        return (sim.walked + sim.baritoneDistance) / Math.max(1, baseline);
    }

    public String report(TileArt art) {
        List<String> mm = mismatches(art);
        return "passes=" + passes + " lanes=" + lanesRun + " spotLanes=" + spotLanes() + " ticks=" + ticks + " walked=" + Math.round(sim.walked)
            + " baritone=" + Math.round(sim.baritoneDistance) + " (" + sim.baritoneWalks + " walks) baseline="
            + Math.round(baseline) + " jumps=" + sim.jumps + " clicks=" + sim.clicks + " accepted=" + sim.accepted
            + " boxRejects=" + sim.boxRejects + " occupied=" + sim.occupiedRejects + " wrongClicks=" + sim.wrongClicks
            + " pocketTicks=" + sim.pocketTicks + " fellOff=" + sim.fellOff + " failures=" + failures
            + "\npockets=" + sim.pockets + "\nskips=" + sim.skipReasons + "\nmismatches(" + mm.size() + ")=" + mm.subList(0, Math.min(20, mm.size()))
            + "\nlanes:\n" + String.join("\n", laneLog.subList(Math.max(0, laneLog.size() - 40), laneLog.size()))
            + "\nlog tail:\n" + String.join("\n", sim.log.subList(Math.max(0, sim.log.size() - 30), sim.log.size()));
    }

    // ------------------------------------------------------------------------------------------ layouts

    public static final String[] LIVE_MATS = {
        "minecraft:birch_leaves", "minecraft:oak_pressure_plate", "minecraft:stone_pressure_plate",
        "minecraft:heavy_weighted_pressure_plate", "minecraft:crimson_roots", "minecraft:dripstone_block",
        "minecraft:glow_lichen[down=true]", "minecraft:mushroom_stem", "minecraft:open_eyeblossom",
        "minecraft:pink_petals", "minecraft:resin_clump[down=true]", "minecraft:red_terracotta",
        "minecraft:yellow_terracotta"};

    /** The live 10x test art (gen_complex.py): 2-wide columns of 13 materials, 16 rows, black carpet frame. */
    public static TileArt liveStripes() {
        TileArt a = new TileArt();
        int x0 = 50;
        int z0 = 4;
        int rows = 16;
        int wid = 2 * LIVE_MATS.length + 2;
        for (int i = 0; i < wid; i++) {
            for (int j = 0; j < rows + 2; j++) {
                String s = i == 0 || i == wid - 1 || j == 0 || j == rows + 1 ? "minecraft:black_carpet" : LIVE_MATS[(i - 1) / 2];
                a.set(x0 + i, z0 + j, BlockSpec.parse(s));
            }
        }
        return a;
    }

    /** Checkerboard of a full block and carpet over [x0, x1) × [z0, z1). */
    public static TileArt checkerboard(int x0, int z0, int x1, int z1) {
        TileArt a = new TileArt();
        for (int x = x0; x < x1; x++)
            for (int z = z0; z < z1; z++)
                a.set(x, z, BlockSpec.of((x + z) % 2 == 0 ? "minecraft:white_terracotta" : "minecraft:red_carpet"));
        return a;
    }

    public static TileArt fill(String id, int x0, int z0, int x1, int z1) {
        TileArt a = new TileArt();
        for (int x = x0; x < x1; x++) for (int z = z0; z < z1; z++) a.set(x, z, BlockSpec.of(id));
        return a;
    }

    /** A {@code width}-wide ring of full blocks with carpet inside and outside, over [x0, x1) × [z0, z1). */
    public static TileArt ring(int x0, int z0, int x1, int z1, int ringX0, int ringZ0, int ringX1, int ringZ1, int width) {
        TileArt a = new TileArt();
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                boolean inOuter = x >= ringX0 && x < ringX1 && z >= ringZ0 && z < ringZ1;
                boolean inInner = x >= ringX0 + width && x < ringX1 - width && z >= ringZ0 + width && z < ringZ1 - width;
                a.set(x, z, BlockSpec.of(inOuter && !inInner ? "minecraft:yellow_terracotta"
                    : inInner ? "minecraft:white_carpet" : "minecraft:black_carpet"));
            }
        }
        return a;
    }

    /** Random pixels: full block, carpet, roots, plate, empty. */
    public static TileArt noise(long seed, int x0, int z0, int x1, int z1, double solidShare) {
        Random r = new Random(seed);
        String[] low = {"minecraft:white_carpet", "minecraft:black_carpet", "minecraft:crimson_roots",
            "minecraft:stone_pressure_plate", "minecraft:pink_petals"};
        String[] full = {"minecraft:white_terracotta", "minecraft:mushroom_stem", "minecraft:birch_leaves"};
        TileArt a = new TileArt();
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                double v = r.nextDouble();
                if (v < 0.05) continue;
                if (v < 0.05 + solidShare) a.set(x, z, BlockSpec.of(full[r.nextInt(full.length)]));
                else a.set(x, z, BlockSpec.of(low[r.nextInt(low.length)]));
            }
        }
        return a;
    }
}
