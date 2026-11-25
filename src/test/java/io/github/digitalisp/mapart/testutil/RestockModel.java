package io.github.digitalisp.mapart.testutil;

import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.FarStorage;
import io.github.digitalisp.mapart.plan.LaneFlip;
import io.github.digitalisp.mapart.plan.RestockLoads;
import io.github.digitalisp.mapart.plan.RestockPlanner;
import io.github.digitalisp.mapart.plan.RestockPlanner.Costs;
import io.github.digitalisp.mapart.plan.RestockPlanner.Plan;
import io.github.digitalisp.mapart.plan.RestockPlanner.Request;
import io.github.digitalisp.mapart.plan.RestockPlanner.Site;
import io.github.digitalisp.mapart.plan.SerpentineBuildPlanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Restock cost model of one tile: the engine's RESTOCK + BUILD loop with the
 * production planners, counting what the restock plan changes (trips, containers, clicks, walking, lane changes) and
 * leaving out what every plan pays the same (placing, in-lane walking over cells that are placed, the end of the
 * tile). Pure, deterministic, test-side only.
 *
 * <p><b>Geometry</b> (tile-relative, the moss base layout, no real coordinates): the tile at x, z 0..127, north =
 * smaller z. Each item's sorter chest is at (column, {@value #ART_Y}, {@value #CHEST_Z}), used from the walkway at
 * z = {@value #STAND_Z}; columns per {@link #FIXED_COLUMNS}, any other item gets the next free column from 44, first
 * come scanning the tile west to east (the same for every lane width). Leftovers are dropped at {@link #DROP}; the map
 * and pane chests are at x 116 (stand {@link #MAP_PANE_STAND}). The tile's first trip starts at {@link #FIRST_START}.
 * That is {@link #MOSS}; {@link #SOUL_SAND} is the soul sand platform's layout ({@link Layout}: the front sorter's
 * columns, the map and pane chests at the station 244 blocks east of the tile).
 *
 * <p><b>Kit</b>: {@link Config#materialSlots} slots for materials; inventory counts are kept per item, a held item
 * taking {@code ceil(count / stack)} slots (compact stacks). A {@link Config#lastLoadReserve} above 0 is the trimmed
 * kit: the map and pane are not carried and are fetched on the tile's last load (the load covering every lane).
 * {@link Config#mapPaneAtStore}: they come with the previous tile's STORE instead (the bot stands by their chests then:
 * two containers, no walk), and the loads carry them (give the config 2 slots fewer when the kit has no free reserve).
 *
 * <p><b>Loop</b>, one RESTOCK + BUILD per trip, as {@code BuildEngine.stepRestock} / {@code stepBuild}:
 * <ol>
 *   <li>lanes = {@link SerpentineBuildPlanner} over the cells not placed yet (the engine's replan);</li>
 *   <li>junk ({@code engine/Leftovers.junk}): a carried item the rest of the tile does not need, or a surplus of at
 *       least one stack, is dropped at {@link #DROP} (walk there first);</li>
 *   <li>the load: {@link RestockPlanner#plan(Request)} with the held counts, compact held slots, the free slots, the
 *       last-load reserve, top-up and a {@link Site} (bot, sorter chests, {@link Costs#DEFAULT});</li>
 *   <li>withdrawals ({@link Withdraw#STACKS}): whole stacks ({@code ceil(w / stack)} clicks), or with top-up, when it
 *       saves a slot, 3 clicks filling the held partial stack and whole stacks for the rest. The exact modes take
 *       exactly the planned count at the clicks {@link ExactClicks} counts (or one per stack started). One container
 *       per item;</li>
 *   <li>the trip walk: bot → (drop) → the withdrawn items' stand points along the walkway → (map and pane chests on the
 *       tile's last load when the kit has a reserve) → the first lane's {@code from}. Sweep direction
 *       {@link Sweep#NEAREST_END} (today) or {@link Sweep#END_AWARE} (the bundle);</li>
 *   <li>BUILD: {@link Plan#stopAtCut()} false = today's greedy BUILD (the loaded lanes walked to their ends placing
 *       every carried cell, then further lanes whose items are all carried); true = the covered lanes and the first
 *       {@code partialCells} placements of the next, the walk ending one row past the last of them.</li>
 * </ol>
 *
 * <p><b>Time</b> ({@link Config#costs}: {@link Costs#DEFAULT}, or {@link #LIVE}): a Baritone leg is {@code legSeconds + d / walkSpeed} (d Euclidean between
 * cell centres); one leg to the drop point, one to the first sorter stand, one more per sorter hop longer than
 * {@value #FREE_HOP_BLOCKS} blocks, one to the map/pane stand unless within that range, one to the lane start;
 * walking is the whole polyline (short hops are walked too, without a leg overhead). The yaw unwind comes before
 * every Baritone walk after a lane (every trip but the tile's first, every lane change). Containers
 * {@code chestSeconds} each, clicks {@code clickSeconds} each, the leftover drop {@value #DROP_BASE_SECONDS} s +
 * {@value #DROP_PER_STACK_SECONDS} s per stack. Lane changes: unwind + leg; every lane (part) run
 * {@code laneRunSeconds}; re-walk (rows of a lane walked without a placement, e.g. greedy BUILD walking the rest of
 * a partial lane, or the holes it left) at {@value #LANE_WALK_SPEED} b/s (in-lane walking, Speed II).
 *
 * <p><b>Bot position</b>: where its walk ends: the lane's {@code to} for a lane walked to its end (greedy BUILD walks
 * a partial lane to its end), one row past the last placement for a lane cut by {@code stopAtCut}.
 *
 * <p><b>Left out</b>: placing and the in-lane walk over placed rows (the same for every plan), hotbar moves, stand
 * spots behind full blocks, food, mending, stock scans, short withdrawals (the sorter always has full stacks), the
 * end of the tile (FILL / LOCK / RENAME / STORE / NUKE / CLEAR), sprinting, TPS.
 */
public final class RestockModel {
    public static final int ART_Y = 64;
    public static final PlatformGeometry GEO = new PlatformGeometry(0, 0, ART_Y);
    public static final int CHEST_Z = -7;
    public static final int STAND_Z = -5;
    public static final Pos FIRST_START = new Pos(63, ART_Y, -4);
    public static final Pos DROP = new Pos(-1, ART_Y, -2);
    public static final Pos MAP_PANE_STAND = new Pos(116, ART_Y, STAND_Z);
    /** Containers (empty map, glass pane) and clicks per container (EXACT: pick up, one right-click, put back). */
    public static final int MAP_PANE_CONTAINERS = 2;
    public static final int MAP_PANE_CLICKS = 3;
    /** A sorter hop within this range is no Baritone leg of its own (goal range 3 around the chest). */
    public static final double FREE_HOP_BLOCKS = 3.0;
    /** Live 2026-10-13 (edge stop, aim, sneak until still, then the drops): about 3 s at the drop point + 0.3 s a stack. */
    public static final double DROP_BASE_SECONDS = 3.0;
    public static final double DROP_PER_STACK_SECONDS = 0.3;
    /** In-lane walking without placing, blocks per second (walk keys under Speed II: 4.317 × 1.4). */
    public static final double LANE_WALK_SPEED = 6.04;
    /** The moss sorter's columns (x of the chest) in order 0, 1, 2, …; any other item: 44 and up, first come. */
    public static final List<String> FIXED_COLUMNS;
    public static final int FIRST_FREE_COLUMN = 44;
    private static final int MAX_ROUNDS = 1000;
    private static final int TOP_UP_CLICKS = 3;

    static {
        List<String> c = new ArrayList<>(SyntheticArt.CARPETS);
        for (String wood : List.of("birch", "heavy_weighted", "jungle", "stone", "oak", "pale_oak", "light_weighted",
            "spruce", "cherry", "crimson", "warped"))
            c.add("minecraft:" + wood + "_pressure_plate");
        for (String id : List.of("resin_clump", "dripstone_block", "mushroom_stem", "terracotta", "crimson_roots",
            "open_eyeblossom", "pink_petals", "birch_leaves", "glow_lichen", "white_terracotta",
            "light_gray_terracotta", "gray_terracotta", "black_terracotta", "brown_terracotta", "red_terracotta",
            "orange_terracotta", "yellow_terracotta"))
            c.add("minecraft:" + id);
        if (c.size() != FIRST_FREE_COLUMN) throw new AssertionError(c.size() + " fixed columns");
        FIXED_COLUMNS = List.copyOf(c);
    }

    /**
     * Where the stock and the stops are, tile-relative (x, z; the tile at 0..127).
     *
     * @param name            label
     * @param columns         item → x of its sorter chest
     * @param firstFreeColumn x of the first chest for an item not in {@code columns} (then one per new item)
     * @param chestZ          z of the sorter chests
     * @param standZ          z of the walkway the sorter is used from
     * @param drop            the leftovers drop point (stand)
     * @param mapPaneStand    where the map and pane chests are used from
     * @param firstStart      where the tile's first trip starts
     */
    public record Layout(String name, Map<String, Integer> columns, int firstFreeColumn, int chestZ, int standZ,
                         Pos drop, Pos mapPaneStand, Pos firstStart) {
        public Layout {
            columns = Map.copyOf(columns);
        }

        /** This layout with the map and pane chests used from {@code stand}. */
        public Layout mapPaneAt(String newName, Pos stand) {
            return new Layout(newName, columns, firstFreeColumn, chestZ, standZ, drop, stand, firstStart);
        }
    }

    /** The moss base layout (the class comment's geometry). */
    public static final Layout MOSS;
    /**
     * The soul sand platform (the owner's base, from the live stock index and site config of 2026-10-23, tile-relative): the
     * front sorter's double chests at z −5 used from the walkway at z −4, one column per item (carpets x 0–30 in
     * steps of 2, pressure plates 32–52, candle 58, the full blocks 54–124); the leftovers drop point at the west end
     * (−1, −2); the map and pane chests at the station (x 372, z −7 and −8), the output chest beside them; the tile's
     * first trip from where the clear leaves the bot (63, 60). Items without a column: from x 126.
     */
    public static final Layout SOUL_SAND;

    static {
        Map<String, Integer> moss = new LinkedHashMap<>();
        for (int i = 0; i < FIXED_COLUMNS.size(); i++) moss.put(FIXED_COLUMNS.get(i), i);
        MOSS = new Layout("moss", moss, FIRST_FREE_COLUMN, CHEST_Z, STAND_Z, DROP, MAP_PANE_STAND, FIRST_START);
        Map<String, Integer> soul = new LinkedHashMap<>();
        String[] carpets = {"white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow", "lime", "green",
            "cyan", "light_blue", "blue", "purple", "magenta", "pink"};
        for (int i = 0; i < carpets.length; i++) soul.put("minecraft:" + carpets[i] + "_carpet", 2 * i);
        String[][] rest = {{"light_weighted_pressure_plate", "32"}, {"heavy_weighted_pressure_plate", "34"},
            {"pale_oak_pressure_plate", "36"}, {"cherry_pressure_plate", "38"}, {"birch_pressure_plate", "40"},
            {"oak_pressure_plate", "42"}, {"jungle_pressure_plate", "44"}, {"spruce_pressure_plate", "46"},
            {"crimson_pressure_plate", "48"}, {"warped_pressure_plate", "50"}, {"stone_pressure_plate", "52"},
            {"decorated_pot", "54"}, {"pointed_dripstone", "56"}, {"white_candle", "58"}, {"resin_clump", "68"},
            {"green_terracotta", "70"}, {"bamboo_block", "72"}, {"netherrack", "74"}, {"grass_block", "76"},
            {"redstone_block", "78"}, {"packed_ice", "80"}, {"clay", "82"}, {"prismarine_bricks", "84"},
            {"lapis_block", "86"}, {"emerald_block", "88"}, {"magenta_terracotta", "90"},
            {"light_blue_terracotta", "92"}, {"yellow_terracotta", "94"}, {"lime_terracotta", "96"},
            {"pink_terracotta", "98"}, {"gray_terracotta", "100"}, {"light_gray_terracotta", "102"},
            {"cyan_terracotta", "104"}, {"purple_terracotta", "106"}, {"blue_terracotta", "108"},
            {"black_terracotta", "110"}, {"crimson_nylium", "112"}, {"crimson_hyphae", "114"}, {"warped_nylium", "116"},
            {"warped_hyphae", "118"}, {"warped_wart_block", "120"}, {"cobbled_deepslate", "122"},
            {"raw_iron_block", "124"}};
        for (String[] r : rest) soul.put("minecraft:" + r[0], Integer.parseInt(r[1]));
        SOUL_SAND = new Layout("soul sand", soul, 126, -5, -4, new Pos(-1, ART_Y, -2), new Pos(372, ART_Y, -6),
            new Pos(63, ART_Y, 60));
    }

    /** Order of the sorter visits along the walkway. */
    public enum Sweep {
        /** Today: start at the end of the visited columns nearer the bot. */
        NEAREST_END,
        /** The bundle: the direction with the shorter bot → columns → next stop walk. */
        END_AWARE
    }

    /**
     * One configuration.
     *
     * @param name            row label
     * @param planner         a fresh planner per run
     * @param materialSlots   slots for materials (28 = today's kit, 31 = the trimmed kit)
     * @param lastLoadReserve slots the tile's last load leaves free; above 0 the map and pane are fetched on it
     * @param topUp           withdrawals top up a held partial stack when that saves a slot
     * @param sweep           sorter visiting order
     * @param returnUnused    carried materials the load does not use go back when they crowd it (the bundle's engine,
     *                        {@code RestockLoads.choose}); false = today's engine
     * @param withdraw        how a planned withdrawal is carried out (clicks, and exact count or whole stacks)
     * @param costs           the time model the run is scored with, and the one the planner's {@link Site} gets
     *                        unless {@code plannerCosts} is set ({@link Costs#DEFAULT}, or {@link #LIVE} for the 2b2t
     *                        fit)
     * @param plannerCosts    the costs the planner's {@link Site} gets when they differ from the scoring ones (the
     *                        engine's {@code build.restockCosts}); null = {@code costs}
     * @param mapPaneAtStore  the map and pane come with the previous tile's STORE ({@code build.mapPaneAtStore}: two
     *                        containers and their clicks, no walk); the loads carry them, so give the config 2 material
     *                        slots fewer and no {@code lastLoadReserve}
     * @param flipLanes       the planner may walk a load's lanes the other way ({@code build.restockLaneFlip},
     *                        {@link Site#flipLanes}); BUILD then runs them flipped ({@link LaneFlip})
     */
    public record Config(String name, Supplier<RestockPlanner> planner, int materialSlots, int lastLoadReserve,
                         boolean topUp, Sweep sweep, boolean returnUnused, Withdraw withdraw, Costs costs,
                         double lazyDropSeconds, boolean keepReused, @org.jspecify.annotations.Nullable Costs plannerCosts,
                         boolean mapPaneAtStore, boolean flipLanes) {
        public Config(String name, Supplier<RestockPlanner> planner, int materialSlots, int lastLoadReserve,
                      boolean topUp, Sweep sweep, boolean returnUnused, Withdraw withdraw, Costs costs,
                      double lazyDropSeconds, boolean keepReused, @org.jspecify.annotations.Nullable Costs plannerCosts,
                      boolean mapPaneAtStore) {
            this(name, planner, materialSlots, lastLoadReserve, topUp, sweep, returnUnused, withdraw, costs,
                lazyDropSeconds, keepReused, plannerCosts, mapPaneAtStore, false);
        }

        public Config(String name, Supplier<RestockPlanner> planner, int materialSlots, int lastLoadReserve,
                      boolean topUp, Sweep sweep, boolean returnUnused, Withdraw withdraw, Costs costs,
                      double lazyDropSeconds, boolean keepReused) {
            this(name, planner, materialSlots, lastLoadReserve, topUp, sweep, returnUnused, withdraw, costs,
                lazyDropSeconds, keepReused, null, false);
        }

        public Config(String name, Supplier<RestockPlanner> planner, int materialSlots, int lastLoadReserve,
                      boolean topUp, Sweep sweep, boolean returnUnused, Withdraw withdraw, Costs costs) {
            this(name, planner, materialSlots, lastLoadReserve, topUp, sweep, returnUnused, withdraw, costs, 0, false);
        }

        public Config(String name, Supplier<RestockPlanner> planner, int materialSlots, int lastLoadReserve,
                      boolean topUp, Sweep sweep, boolean returnUnused, Withdraw withdraw) {
            this(name, planner, materialSlots, lastLoadReserve, topUp, sweep, returnUnused, withdraw, Costs.DEFAULT);
        }

        public Config(String name, Supplier<RestockPlanner> planner, int materialSlots, int lastLoadReserve,
                      boolean topUp, Sweep sweep, boolean returnUnused) {
            this(name, planner, materialSlots, lastLoadReserve, topUp, sweep, returnUnused, Withdraw.STACKS);
        }

        public Config(String name, Supplier<RestockPlanner> planner, int materialSlots, int lastLoadReserve,
                      boolean topUp, Sweep sweep) {
            this(name, planner, materialSlots, lastLoadReserve, topUp, sweep, true);
        }

        /**
         * This configuration with the leftovers drop only when the load without them saves {@code dropSeconds} seconds of restock
         * more ({@code build.lazyDrop}; {@code dropSeconds} what a drop costs, 0 = always dropped) and the crowded-load return keeping the items the freed
         * load needs again ({@code keepReused}, {@code build.crowdKeepReused}).
         */
        public Config restockRules(String newName, double dropSeconds, boolean keepReused) {
            return new Config(newName, planner, materialSlots, lastLoadReserve, topUp, sweep, returnUnused, withdraw,
                costs, dropSeconds, keepReused, plannerCosts, mapPaneAtStore, flipLanes);
        }

        /** This configuration with the planner given {@code planned} instead of the scoring costs. */
        public Config plannedWith(String newName, Costs planned) {
            return new Config(newName, planner, materialSlots, lastLoadReserve, topUp, sweep, returnUnused, withdraw,
                costs, lazyDropSeconds, keepReused, planned, mapPaneAtStore, flipLanes);
        }

        /**
         * This configuration with the map and pane fetched at the previous tile's STORE: {@code slots} material slots
         * (the kit carrying them), no last-load reserve.
         */
        public Config mapPaneAtStore(String newName, int slots) {
            return new Config(newName, planner, slots, 0, topUp, sweep, returnUnused, withdraw, costs, lazyDropSeconds,
                keepReused, plannerCosts, true, flipLanes);
        }

        /** This configuration with the load direction choice ({@code build.restockLaneFlip}) on or off. */
        public Config flipping(String newName, boolean on) {
            return new Config(newName, planner, materialSlots, lastLoadReserve, topUp, sweep, returnUnused, withdraw,
                costs, lazyDropSeconds, keepReused, plannerCosts, mapPaneAtStore, on);
        }

        /** The costs the planner plans with. */
        public Costs planCosts() {
            return plannerCosts != null ? plannerCosts : costs;
        }
    }

    /**
     * The live 2b2t fit of a Baritone leg under the Speed II beacon (66 legs, 2,014
     * blocks in 10 trips): {@code 0.69 s + d / 5.99}; the rest as {@link Costs#DEFAULT}.
     */
    public static final Costs LIVE = new Costs(5.99, 0.69, Costs.DEFAULT.unwindSeconds(), Costs.DEFAULT.chestSeconds(),
        Costs.DEFAULT.clickSeconds(), Costs.DEFAULT.laneRunSeconds());

    /** How a planned withdrawal is carried out. */
    public enum Withdraw {
        /** Whole stacks, or with top-up the held partial stack filled first (the engine today). */
        STACKS,
        /** Exactly the planned count; the remainder below a stack by left clicks and one-item right clicks. */
        EXACT_RIGHT,
        /** As {@link #EXACT_RIGHT}, right clicks also picking up half a stack. */
        EXACT_HALF,
        /** Exactly the planned count at one click per stack started (the routing study's assumption). */
        EXACT_FREE
    }

    /**
     * One RESTOCK + BUILD round. {@code planRounds} / {@code planSeconds}: the planner's own estimate for the rest of
     * the tile ({@link Plan#rounds()}, {@link Plan#seconds()}; 0 = not estimated). {@code flipped}: the load walked its
     * lanes the other way ({@link Plan#flipped()}).
     */
    public record Trip(int round, int lanesLeft, int lanesCovered, int partialCells, boolean stopAtCut, boolean last,
                       Map<String, Integer> withdraw, int chests, int clicks, double walkBlocks, int legs,
                       int dropStacks, double restockSeconds, int slotsAfter, int placed, int laneRuns, int rewalkRows,
                       double laneChangeSeconds, double planMillis, int planRounds, double planSeconds,
                       boolean flipped) { }

    /** Totals of one tile. */
    public static final class Result {
        public final String name;
        public final List<Trip> trips = new ArrayList<>();
        public int chests;
        /** Restock containers whose withdrawal only topped up a carried partial stack (no whole stack after it). */
        public int topUpOnly;
        public int clicks;
        public double walkBlocks;
        public int legs;
        public int unwinds;
        public int drops;
        public int dropStacks;
        /** Leftovers drops skipped because the load gained too few cells ({@code build.lazyDrop}). */
        public int lazySkips;
        /** Loads before which carried materials the load did not use went back (RestockLoads.choose). */
        public int returnedUnused;
        public int mapPaneFetches;
        public double walkSeconds;
        public double legSeconds;
        public double unwindSeconds;
        public double chestSeconds;
        public double clickSeconds;
        public double dropSeconds;
        public int laneRuns;
        public int laneChanges;
        public double laneChangeBlocks;
        public int rewalkRows;
        /** Lane changes inside a round (unwind + leg), lane runs and re-walk. */
        public double laneChangeSeconds;
        /** Cells of a planned range (covered lanes, the first partialCells of the next) not placed for lack of items. */
        public int shortCells;
        public int maxSlots;
        /** Loads that withdrew from far storage ({@link #far}), and RESTOCKs that left far cells out. */
        public int farTrips;
        public int farLeftOut;
        public int cellsPlaced;
        /** Where each load's BUILD ended (the next trip's start): feet z, tile-relative (0 = the sorter side). */
        public final List<Integer> loadEndZ = new ArrayList<>();
        public final List<Double> planMillis = new ArrayList<>();

        Result(String name) {
            this.name = name;
        }

        public int trips() {
            return trips.size();
        }

        /** Walk + legs + unwind + containers + clicks + drop (map and pane fetch included). */
        public double restockSeconds() {
            return walkSeconds + legSeconds + unwindSeconds + chestSeconds + clickSeconds + dropSeconds;
        }

        public double totalSeconds() {
            return restockSeconds() + laneChangeSeconds;
        }

        public double planMeanMillis() {
            return planMillis.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }

        public double planMaxMillis() {
            return planMillis.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        }

        @Override
        public String toString() {
            return String.format("%s: %d trips, %d chests, %d clicks, %.0f walk blocks, restock %.1f s, lane change %.1f s",
                name, trips(), chests, clicks, walkBlocks, restockSeconds(), laneChangeSeconds);
        }
    }

    /** The engine's replan ({@code build.laneEvenWidths} off unless the model is made with even lanes). */
    private final SerpentineBuildPlanner buildPlanner;

    private final TileArt art;
    private final int halfWidth;
    private final FakeCatalog catalog;
    private final Layout layout;
    private final Map<String, Integer> columns = new LinkedHashMap<>();
    private final List<Lane> initialLanes;
    /** Lane index → rows (z) holding art in the lane's columns. */
    private final Map<Integer, Set<Integer>> artRows = new HashMap<>();
    /** Seconds the kept leftovers cost the loads so far ({@code build.lazyDrop}, as the engine's lazyDropDebt). */
    private double lazyDebt;
    /** Far storage for some items; null = all in the walkway sorter. */
    private Far far;

    /**
     * Far storage (the engine's {@code build.farStorage}): {@code items} are only in a far sorter whose chests are used
     * from {@code stand} (tile-relative, e.g. the other platform's sorter 200 blocks away), visited after the walkway
     * sorter. {@code minCells}: the engine's leave-out of a load's few far cells ({@link FarStorage#fewFarCells}, 0 =
     * off); {@code batcher}: the batching planner around the configured one (far items are then never returned to make
     * room), null = no batching.
     */
    public record Far(Set<String> items, Pos stand, int minCells,
                      java.util.function.@org.jspecify.annotations.Nullable BiFunction<RestockPlanner, FarStorage.Stock, RestockPlanner> batcher) {
        public Far {
            items = Set.copyOf(items);
        }

        /** The engine before the far rules. */
        public static Far before(Set<String> items, Pos stand) {
            return new Far(items, stand, 0, null);
        }

        /** The engine with the far rules: the leave-out of fewer than 64 far cells and the production batching. */
        public static Far rules(Set<String> items, Pos stand) {
            return new Far(items, stand, 64, FarStorage.Batching::new);
        }
    }

    /** This model with far storage (null: none). */
    public RestockModel far(Far f) {
        this.far = f;
        return this;
    }

    /** The stock split for the far rules: unlimited near and far stock. */
    private FarStorage.Stock farStock(Set<String> items) {
        Map<String, Integer> near = new HashMap<>();
        Map<String, Map<Integer, Integer>> farItems = new HashMap<>();
        for (String id : items) {
            if (far.items().contains(id)) farItems.put(id, Map.of(0, 1_000_000));
            else near.put(id, 1_000_000);
        }
        return new FarStorage.Stock(near, farItems);
    }

    public RestockModel(TileArt art, int halfWidth, FakeCatalog catalog) {
        this(art, halfWidth, catalog, MOSS);
    }

    public RestockModel(TileArt art, int halfWidth, FakeCatalog catalog, Layout layout) {
        this(art, halfWidth, catalog, layout, false);
    }

    /** {@code evenLanes}: the lanes as {@code build.laneEvenWidths} plans them (live since 2026-10-23). */
    public RestockModel(TileArt art, int halfWidth, FakeCatalog catalog, Layout layout, boolean evenLanes) {
        this.buildPlanner = new SerpentineBuildPlanner(() -> evenLanes);
        this.art = art;
        this.halfWidth = halfWidth;
        this.catalog = catalog;
        this.layout = layout;
        this.initialLanes = buildPlanner.plan(art, GEO, catalog, halfWidth, p -> false);
        columns.putAll(layout.columns());
        // other items: first come, scanning the tile west to east, north to south (the same for every lane width)
        int next = layout.firstFreeColumn();
        for (int x = 0; x < TileArt.SIZE; x++)
            for (int z = 0; z < TileArt.SIZE; z++) {
                var spec = art.get(x, z);
                if (spec == null) continue;
                String item = catalog.placementItem(spec).orElse(spec.id());
                if (!columns.containsKey(item)) columns.put(item, next++);
            }
        for (Lane l : initialLanes) {
            Set<Integer> rows = new HashSet<>();
            for (Placement p : l.placements()) rows.add(p.pos().z());
            artRows.put(l.index(), rows);
        }
    }

    /** The lanes of the untouched tile. */
    public List<Lane> initialLanes() {
        return initialLanes;
    }

    /** Sorter column (chest x) of {@code item}. */
    public int column(String item) {
        Integer c = columns.get(item);
        if (c == null) throw new IllegalArgumentException("no sorter column for " + item);
        return c;
    }

    /** The chest {@code item} is withdrawn from (the planner's {@link Site#depot}). */
    public Pos depot(String item) {
        if (far != null && far.items().contains(item)) return far.stand().offset(0, 0, -2);
        Integer c = columns.get(item);
        return c == null ? null : new Pos(c, ART_Y, layout.chestZ());
    }

    /** Items of the tile with their sorter columns (fixed ones only when the tile uses them). */
    public Map<String, Integer> columnsUsed() {
        Map<String, Integer> m = new TreeMap<>();
        for (Lane l : initialLanes) for (Placement p : l.placements()) m.put(p.itemId(), column(p.itemId()));
        return m;
    }

    /** Builds the whole tile under {@code cfg}. Throws when a sanity check fails. */
    public Result run(Config cfg) {
        RestockPlanner planner = cfg.planner().get();
        lazyDebt = 0;
        Costs k = cfg.costs();
        Result res = new Result(cfg.name());
        Map<String, Integer> inv = new TreeMap<>();
        Set<Pos> placed = new HashSet<>();
        Pos bot = layout.firstStart();
        boolean afterLane = false;
        boolean mapPaneCarried = cfg.mapPaneAtStore();
        if (cfg.mapPaneAtStore()) {
            // fetched at the previous tile's STORE, standing by their chests: the containers and clicks, no walk
            res.chests += MAP_PANE_CONTAINERS;
            res.clicks += MAP_PANE_CONTAINERS * MAP_PANE_CLICKS;
            res.chestSeconds += MAP_PANE_CONTAINERS * k.chestSeconds();
            res.clickSeconds += MAP_PANE_CONTAINERS * MAP_PANE_CLICKS * k.clickSeconds();
            res.mapPaneFetches++;
        }
        int round = 0;
        while (true) {
            List<Lane> lanes = buildPlanner.plan(art, GEO, catalog, halfWidth, p -> placed.contains(p.pos()));
            if (lanes.isEmpty()) break;
            if (++round > MAX_ROUNDS) throw new IllegalStateException(cfg.name() + ": no end after " + MAX_ROUNDS + " rounds");
            Map<String, Integer> needs = new TreeMap<>();
            for (Lane l : lanes) for (Placement p : l.placements()) needs.merge(p.itemId(), 1, Integer::sum);

            int chests = 0, clicks = 0, legs = 0, dropStacks = 0;
            double walk = 0, seconds = 0;
            if (afterLane) {
                res.unwinds++;
                res.unwindSeconds += k.unwindSeconds();
                seconds += k.unwindSeconds();
            }
            // 2. leftovers (Leftovers.junk): no need left, or a surplus of a stack or more
            Map<String, Integer> junk = new TreeMap<>();
            inv.forEach((id, n) -> {
                int need = needs.getOrDefault(id, 0);
                int extra = n - need;
                if (extra > 0 && (need == 0 || extra >= stack(id))) junk.put(id, extra);
            });
            if (!junk.isEmpty() && cfg.lazyDropSeconds() > 0 && lazyKeeps(cfg, planner, lanes, inv, junk, needs, bot, k)) {
                res.lazySkips++;
                junk.clear();
            }
            if (!junk.isEmpty()) {
                double d = dist(bot, layout.drop());
                walk += d;
                legs++;
                for (var e : junk.entrySet()) {
                    dropStacks += ceilDiv(e.getValue(), stack(e.getKey()));
                    int left = inv.get(e.getKey()) - e.getValue();
                    if (left > 0) inv.put(e.getKey(), left);
                    else inv.remove(e.getKey());
                }
                double t = DROP_BASE_SECONDS + DROP_PER_STACK_SECONDS * dropStacks;
                res.drops++;
                res.dropStacks += dropStacks;
                res.dropSeconds += t;
                seconds += t;
                bot = layout.drop();
            }
            // 3. what is held, in compact stacks
            Map<String, Integer> held = new TreeMap<>();
            Map<String, Integer> heldSlots = new TreeMap<>();
            int used = 0;
            for (var e : inv.entrySet()) {
                int s = ceilDiv(e.getValue(), stack(e.getKey()));
                used += s;
                // the engine plans with the tile's items only: a leftover kept (lazy drop) holds slots and nothing else
                if (!needs.containsKey(e.getKey())) continue;
                held.put(e.getKey(), e.getValue());
                heldSlots.put(e.getKey(), s);
            }
            int free = cfg.materialSlots() - used;
            if (free < 0) throw new IllegalStateException(cfg.name() + ": held items take " + used + " slots");
            // 4. the load (RestockLoads.choose, as the engine: carried materials the load does not use go back first
            // when they crowd it)
            Request req = new Request(lanes, held, heldSlots, free, catalog::maxStackSize, cfg.lastLoadReserve(),
                cfg.topUp(), new Site(bot, this::depot, cfg.planCosts(), cfg.flipLanes()));
            long t0 = System.nanoTime();
            // far storage: the planner batches; a load's few far cells wait (the load and BUILD run without them)
            boolean batching = far != null && far.batcher() != null;
            FarStorage.Stock stock = far == null ? null : farStock(needs.keySet());
            RestockPlanner loadPlanner = batching ? far.batcher().apply(planner, stock) : planner;
            RestockLoads.Choice choice = RestockLoads.choose(loadPlanner, req,
                id -> !batching || !far.items().contains(id), cfg.keepReused());
            Plan plan = choice.kept();
            double ms = (System.nanoTime() - t0) / 1e6;
            res.planMillis.add(ms);
            if (cfg.returnUnused() && choice.clogged()) {
                double d = dist(bot, layout.drop());
                walk += d;
                if (d > 0) legs++;
                int stacks = 0;
                for (var e : choice.unused().entrySet()) {
                    stacks += ceilDiv(e.getValue(), stack(e.getKey()));
                    inv.remove(e.getKey());
                    held.remove(e.getKey());
                    free += heldSlots.remove(e.getKey());
                }
                double t = DROP_BASE_SECONDS + DROP_PER_STACK_SECONDS * stacks;
                res.drops++;
                res.dropStacks += stacks;
                res.dropSeconds += t;
                res.returnedUnused++;
                seconds += t;
                bot = layout.drop();
                req = new Request(lanes, held, heldSlots, free, catalog::maxStackSize, cfg.lastLoadReserve(), cfg.topUp(),
                    new Site(bot, this::depot, cfg.planCosts(), cfg.flipLanes()));
                t0 = System.nanoTime();
                plan = loadPlanner.plan(req);
                res.planMillis.add((System.nanoTime() - t0) / 1e6);
            }
            for (int again = 0; far != null && far.minCells() > 0 && again < 4; again++) {
                Map<String, Integer> served = new HashMap<>(FarStorage.cells(RestockLoads.cutLanes(lanes, plan)));
                FarStorage.Batch batch = loadPlanner instanceof FarStorage.Batching b ? b.batchOf(plan) : null;
                if (batch != null) batch.cells().forEach((id, n) -> served.merge(id, n, Math::max));
                FarStorage.LeftOut few = FarStorage.fewFarCells(lanes, served, held, stock, far.minCells());
                if (few == null) break;
                lanes = few.lanes();
                res.farLeftOut++;
                req = new Request(lanes, held, heldSlots, free, catalog::maxStackSize, cfg.lastLoadReserve(), cfg.topUp(),
                    new Site(bot, this::depot, cfg.planCosts(), cfg.flipLanes()));
                plan = loadPlanner.plan(req);
            }
            if (plan.lanesLoaded() == 0)
                throw new IllegalStateException(cfg.name() + ": round " + round + " loads nothing: " + plan);
            boolean last = plan.lanesCovered() >= lanes.size();
            boolean mapPane = last && cfg.lastLoadReserve() > 0 && !mapPaneCarried;
            if (mapPane) mapPaneCarried = true;
            // 5. withdrawals
            Map<String, Integer> withdraw = new TreeMap<>();
            for (var e : plan.withdraw().entrySet()) {
                int w = e.getValue() == null ? 0 : e.getValue();
                if (w <= 0) continue;
                String id = e.getKey();
                int s = stack(id);
                int h = inv.getOrDefault(id, 0);
                int r = h % s;
                int add;
                int c;
                if (cfg.withdraw() != Withdraw.STACKS) {
                    add = w;
                    c = switch (cfg.withdraw()) {
                        case EXACT_RIGHT -> ExactClicks.withdraw(h, w, s, false);
                        case EXACT_HALF -> ExactClicks.withdraw(h, w, s, true);
                        default -> ceilDiv(w, s);
                    };
                } else if (cfg.topUp() && r > 0 && ceilDiv(w + r, s) <= ceilDiv(w, s)) {
                    // WithdrawMode.TOP_UP: fill the partial stack (pick up, left-click, put back), then whole stacks
                    add = s - r;
                    c = TOP_UP_CLICKS;
                    int rest = w - add;
                    if (rest > 0) {
                        int st = ceilDiv(rest, s);
                        add += st * s;
                        c += st;
                    } else {
                        res.topUpOnly++;
                    }
                } else {
                    int st = ceilDiv(w, s);
                    add = st * s;
                    c = st;
                }
                inv.merge(id, add, Integer::sum);
                withdraw.put(id, w);
                chests++;
                clicks += c;
            }
            int slots = 0;
            for (var e : inv.entrySet()) slots += ceilDiv(e.getValue(), stack(e.getKey()));
            int cap = cfg.materialSlots() - (last ? cfg.lastLoadReserve() : 0);
            if (slots > cap)
                throw new IllegalStateException(cfg.name() + ": round " + round + " carries " + slots + " slots > " + cap
                    + " (" + (last ? "last load" : "load") + ", " + plan + ")");
            res.maxSlots = Math.max(res.maxSlots, slots);
            // 6. the trip walk: (drop) → sorter columns → (map and pane) → the first lane (walked as the plan says)
            List<Lane> walked = LaneFlip.walked(lanes, plan);
            Pos laneStart = walked.get(0).from();
            Pos afterSorter = mapPane ? layout.mapPaneStand() : laneStart;
            TreeSet<Integer> cols = new TreeSet<>();
            boolean farStop = false;
            for (String id : withdraw.keySet()) {
                if (far != null && far.items().contains(id)) farStop = true;
                else cols.add(column(id));
            }
            List<Pos> stops = sweep(bot, cols, farStop ? far.stand() : afterSorter, cfg.sweep());
            if (farStop) {
                stops = new ArrayList<>(stops);
                stops.add(far.stand()); // the far sorter after the walkway's (live: its chests are not loaded at the plan)
                res.farTrips++;
            }
            Pos cur = bot;
            for (int i = 0; i < stops.size(); i++) {
                double d = dist(cur, stops.get(i));
                walk += d;
                if (i == 0 || d > FREE_HOP_BLOCKS) legs++;
                cur = stops.get(i);
            }
            if (mapPane) {
                double d = dist(cur, layout.mapPaneStand());
                walk += d;
                if (stops.isEmpty() || d > FREE_HOP_BLOCKS) legs++;
                cur = layout.mapPaneStand();
                chests += MAP_PANE_CONTAINERS;
                clicks += MAP_PANE_CONTAINERS * MAP_PANE_CLICKS;
                res.mapPaneFetches++;
            }
            walk += dist(cur, laneStart);
            legs++;
            double walkS = walk / k.walkSpeed();
            double legS = legs * k.legSeconds();
            double chestS = chests * k.chestSeconds();
            double clickS = clicks * k.clickSeconds();
            seconds += walkS + legS + chestS + clickS;
            res.walkSeconds += walkS;
            res.legSeconds += legS;
            res.chestSeconds += chestS;
            res.clickSeconds += clickS;
            res.walkBlocks += walk;
            res.legs += legs;
            res.chests += chests;
            res.clicks += clicks;
            bot = laneStart;

            // 7. BUILD
            Build b = new Build(inv, placed, laneStart, k);
            if (!plan.stopAtCut()) {
                int loaded = plan.lanesLoaded();
                for (int i = 0; i < walked.size(); i++) {
                    Lane lane = walked.get(i);
                    boolean all = carries(inv, lane, true);
                    if (!all) {
                        if (b.runs > 0 && i >= loaded) break; // the load ran out: restock
                        if (!carries(inv, lane, false)) continue; // nothing for this lane: skipped
                    }
                    int planned = i < plan.lanesCovered() ? lane.placements().size()
                        : i == plan.lanesCovered() ? plan.partialCells() : 0;
                    b.run(lane, lane.placements().size(), planned, true);
                }
            } else {
                for (int i = 0; i < plan.lanesCovered(); i++)
                    b.run(walked.get(i), walked.get(i).placements().size(), walked.get(i).placements().size(), false);
                if (plan.partialCells() > 0 && plan.lanesCovered() < walked.size())
                    b.run(walked.get(plan.lanesCovered()), plan.partialCells(), plan.partialCells(), false);
            }
            if (b.placed == 0) throw new IllegalStateException(cfg.name() + ": round " + round + " placed nothing: " + plan);
            res.laneRuns += b.runs;
            res.laneChanges += b.changes;
            res.laneChangeBlocks += b.changeBlocks;
            res.rewalkRows += b.rewalk;
            res.laneChangeSeconds += b.seconds;
            res.shortCells += b.shortCells;
            res.cellsPlaced += b.placed;
            bot = b.at;
            res.loadEndZ.add(b.at.z());
            afterLane = true;
            res.trips.add(new Trip(round, lanes.size(), plan.lanesCovered(), plan.partialCells(), plan.stopAtCut(), last,
                withdraw, chests, clicks, walk, legs, dropStacks, seconds, slots, b.placed, b.runs, b.rewalk, b.seconds,
                ms, plan.rounds(), plan.seconds(), plan.flipped()));
        }
        int cells = art.nonEmptyCount();
        if (placed.size() != cells || res.cellsPlaced != cells)
            throw new IllegalStateException(cfg.name() + ": placed " + placed.size() + " of " + cells + " cells");
        return res;
    }

    /** One BUILD round: lane runs, lane changes, re-walk; mutates the inventory and the placed set. */
    private final class Build {
        final Map<String, Integer> inv;
        final Set<Pos> placedSet;
        Pos at;
        int runs;
        int changes;
        double changeBlocks;
        int rewalk;
        int placed;
        int shortCells;
        double seconds;

        final Costs k;

        Build(Map<String, Integer> inv, Set<Pos> placedSet, Pos start, Costs k) {
            this.k = k;
            this.inv = inv;
            this.placedSet = placedSet;
            this.at = start;
        }

        /**
         * Runs {@code lane}: its first {@code limit} placements, each placed when its item is carried; the walk goes
         * to the lane's {@code to} ({@code toEnd}) or one row past the last of those placements.
         */
        void run(Lane lane, int limit, int planned, boolean toEnd) {
            double d = dist(at, lane.from());
            if (runs > 0) {
                changes++;
                changeBlocks += d;
                seconds += k.unwindSeconds() + k.legSeconds() + d / k.walkSpeed();
            } else if (d > 0) {
                // the trip ended elsewhere (a skipped first lane): a plain leg, no unwind
                changeBlocks += d;
                seconds += k.legSeconds() + d / k.walkSpeed();
            }
            runs++;
            seconds += k.laneRunSeconds();
            List<Placement> ps = lane.placements();
            int step = Integer.signum(lane.to().z() - lane.from().z());
            Set<Integer> rowsPlaced = new HashSet<>();
            for (int i = 0; i < limit; i++) {
                Placement p = ps.get(i);
                int n = inv.getOrDefault(p.itemId(), 0);
                if (n <= 0) {
                    if (i < planned) shortCells++;
                    continue;
                }
                if (n == 1) inv.remove(p.itemId());
                else inv.put(p.itemId(), n - 1);
                if (!placedSet.add(p.pos())) throw new IllegalStateException("placed twice: " + p.pos());
                rowsPlaced.add(p.pos().z());
                placed++;
            }
            // rows walked (first remaining row → the walk's last row) that hold art but got no placement in this run
            int firstRow = ps.getFirst().pos().z();
            int endRow = toEnd ? ps.getLast().pos().z() : ps.get(limit - 1).pos().z();
            if (endRow != firstRow && Integer.signum(endRow - firstRow) != step)
                throw new IllegalStateException("lane " + lane.index() + " placements against its walk");
            Set<Integer> art = artRows.getOrDefault(lane.index(), Set.of());
            int rows = 0;
            for (int z = Math.min(firstRow, endRow); z <= Math.max(firstRow, endRow); z++)
                if (art.contains(z) && !rowsPlaced.contains(z)) rows++;
            rewalk += rows;
            seconds += rows / LANE_WALK_SPEED;
            at = toEnd ? lane.to() : new Pos(lane.from().x(), ART_Y, clamp(endRow + step));
        }
    }

    /**
     * {@code build.lazyDrop}: true when the leftovers stay: the load planned with {@code junk} dropped covers more
     * cells than the one planned with it carried, and those cells, added up over the restocks that kept it, are still
     * fewer than the config's {@code lazyDropSeconds} (the engine's comparison and ski-rental debt).
     */
    private boolean lazyKeeps(Config cfg, RestockPlanner planner, List<Lane> lanes, Map<String, Integer> inv,
                              Map<String, Integer> junk, Map<String, Integer> needs, Pos bot, Costs k) {
        Plan carried = loadWith(cfg, planner, lanes, inv, Map.of(), needs, bot, k);
        Plan dropped = loadWith(cfg, planner, lanes, inv, junk, needs, bot, k);
        double gain = RestockLoads.gainSeconds(lanes, dropped, carried);
        if (gain < 0 || lazyDebt + gain >= cfg.lazyDropSeconds()) {
            lazyDebt = 0;
            return false;
        }
        lazyDebt += gain;
        return true;
    }

    private Plan loadWith(Config cfg, RestockPlanner planner, List<Lane> lanes, Map<String, Integer> inv,
                          Map<String, Integer> gone, Map<String, Integer> needs, Pos bot, Costs k) {
        Map<String, Integer> held = new TreeMap<>();
        Map<String, Integer> heldSlots = new TreeMap<>();
        int used = 0;
        for (var e : inv.entrySet()) {
            int left = e.getValue() - gone.getOrDefault(e.getKey(), 0);
            if (left <= 0) continue;
            int s = ceilDiv(left, stack(e.getKey()));
            used += s;
            if (!needs.containsKey(e.getKey())) continue;
            held.put(e.getKey(), left);
            heldSlots.put(e.getKey(), s);
        }
        return planner.plan(new Request(lanes, held, heldSlots, cfg.materialSlots() - used, catalog::maxStackSize,
            cfg.lastLoadReserve(), cfg.topUp(), new Site(bot, this::depot, cfg.planCosts(), cfg.flipLanes())));
    }

    private List<Pos> sweep(Pos from, TreeSet<Integer> cols, Pos next, Sweep mode) {
        List<Pos> out = new ArrayList<>();
        if (cols.isEmpty()) return out;
        Pos lo = stand(cols.first());
        Pos hi = stand(cols.last());
        boolean ascending = switch (mode) {
            case NEAREST_END -> dist(from, lo) <= dist(from, hi);
            case END_AWARE -> dist(from, lo) + dist(hi, next) <= dist(from, hi) + dist(lo, next);
        };
        for (int c : ascending ? cols : cols.descendingSet()) out.add(stand(c));
        return out;
    }

    private Pos stand(int column) {
        return new Pos(column, ART_Y, layout.standZ());
    }

    private boolean carries(Map<String, Integer> inv, Lane lane, boolean all) {
        Map<String, Integer> need = new HashMap<>();
        for (Placement p : lane.placements()) need.merge(p.itemId(), 1, Integer::sum);
        for (var e : need.entrySet()) {
            int have = inv.getOrDefault(e.getKey(), 0);
            if (all && have < e.getValue()) return false;
            if (!all && have > 0) return true;
        }
        return all;
    }

    private int stack(String id) {
        int s = catalog.maxStackSize(id);
        return s > 0 ? s : 64;
    }

    static double dist(Pos a, Pos b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    private static int clamp(int z) {
        return Math.max(0, Math.min(TileArt.SIZE - 1, z));
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
