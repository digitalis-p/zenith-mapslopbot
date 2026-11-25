package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.bot.InventoryView;
import io.github.digitalisp.mapart.bot.MapView;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.bot.impl.TopFaceRules;
import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.engine.EngineControl.EngineStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.ValidationResult;
import io.github.digitalisp.mapart.plan.BuildPlanner;
import io.github.digitalisp.mapart.render.ColorResolver;
import io.github.digitalisp.mapart.render.Renderer;
import io.github.digitalisp.mapart.stock.StockIndex;
import io.github.digitalisp.mapart.store.EngineCheckpoint;
import io.github.digitalisp.mapart.store.JobStore;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/** In-memory stand-ins for everything the engine talks to. */
final class SimFakes {
    private SimFakes() { }

    static final Set<String> TOOLS_SUFFIX = Set.of("_pickaxe", "_axe", "_shovel");
    static final Set<String> FOODS = Set.of("minecraft:golden_carrot", "minecraft:bread", "minecraft:cooked_beef");
    static final int FLOOR_COLOR = 49;

    // ------------------------------------------------------------------------------------------
    static final class TestClock extends Clock {
        long millis;

        TestClock(long start) {
            millis = start;
        }

        void advance(long ms) {
            millis += ms;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    // ------------------------------------------------------------------------------------------
    static class FakeCatalog implements BlockCatalog {
        static final Map<String, Integer> COLORS = Map.ofEntries(
            Map.entry("minecraft:white_carpet", 8), Map.entry("minecraft:red_carpet", 28),
            Map.entry("minecraft:black_carpet", 29), Map.entry("minecraft:lime_carpet", 19),
            Map.entry("minecraft:white_terracotta", 36), Map.entry("minecraft:moss_block", 49),
            Map.entry("minecraft:soul_sand", 26),
            // 1.21.4 (2b2t): open = COLOR_ORANGE, closed = TERRACOTTA_GREEN (the 1.21.11 test server shows closed as 6)
            Map.entry("minecraft:open_eyeblossom", 15), Map.entry("minecraft:closed_eyeblossom", 49),
            // MapColor NONE, invisible on maps like in vanilla (OcclusionTest checks the 1.21.4 catalog): the
            // colour-suppression platform's glass, buttons and levers above the art
            Map.entry("minecraft:glass", 0), Map.entry("minecraft:glass_pane", 0), Map.entry("minecraft:stone_button", 0),
            Map.entry("minecraft:lever", 0));

        @Override public boolean isKnownBlock(String blockId) { return true; }
        @Override public boolean isKnownItem(String itemId) { return true; }
        @Override public Optional<String> placementItem(BlockSpec spec) { return Optional.of(spec.id()); }

        @Override
        public int mapColorId(BlockSpec spec) {
            if (spec.isAir()) return 0;
            return COLORS.getOrDefault(spec.id(), 1 + Math.floorMod(spec.id().hashCode(), 60));
        }

        @Override public boolean placeableOnTopFace(BlockSpec spec) { return true; }
        @Override public boolean matches(BlockSpec target, BlockSpec actual) { return TopFaceRules.equivalent(target, actual); }
        @Override public boolean isGravityBlock(String blockId) { return false; }

        @Override
        public int maxStackSize(String itemId) {
            for (String s : TOOLS_SUFFIX) if (itemId.endsWith(s)) return 1;
            if (itemId.endsWith("_boots") || itemId.endsWith("_sword") || itemId.endsWith("shulker_box")
                || itemId.equals("minecraft:totem_of_undying") || FakeInventory.armorSlot(itemId) > 0) return 1;
            return 64;
        }
    }

    static final class FakeColors implements ColorResolver {
        @Override
        public int argb(int mapColorId, int brightness) {
            return 0xFF000000 | (mapColorId * 4 + brightness);
        }
    }

    static final class FakeRenderer implements Renderer {
        int previews, mapData, diffs;

        @Override
        public byte[] preview(Map<TileCoord, TileArt> tiles, int tilesX, int tilesZ, BlockCatalog catalog, ColorResolver colors, int scale) {
            previews++;
            return new byte[]{1};
        }

        @Override
        public byte[] mapData(byte[] packedColors, ColorResolver colors, int scale) {
            mapData++;
            return new byte[]{2};
        }

        @Override
        public byte[] mapDiff(byte[] actualPacked, byte[] expectedPacked, ColorResolver colors, int scale) {
            diffs++;
            return new byte[]{3};
        }
    }

    // ------------------------------------------------------------------------------------------
    static final class FakeWorld implements WorldView {
        final Map<Pos, BlockSpec> blocks = new HashMap<>();
        final Set<Pos> fluid = new HashSet<>();
        /** Column (x, z) → the y levels holding a block there ({@link #topY}); kept by {@link #set}. */
        private final Map<Long, java.util.TreeSet<Integer>> columns = new HashMap<>();
        Predicate<Pos> loaded = p -> true;
        double x, y = 64, z;
        /** Dropped-item entities by exact position; null = no entity data ({@link #itemEntities} empty, the default). */
        List<double[]> items;
        int viewDistance = 10;
        /** Living mobs ({@link #mobs}); a fake fight removes them. */
        final List<Mob> mobList = new ArrayList<>();
        /** Signs in the loaded chunks ({@link #signs}: the rocket chest's, {@code build.elytraLegs}). */
        final List<Sign> signList = new ArrayList<>();

        @Override
        public List<Sign> signs() {
            return List.copyOf(signList);
        }

        @Override
        public List<Mob> mobs(Set<String> types) {
            return mobList.stream().filter(m -> types.contains(m.type())).toList();
        }

        void set(Pos p, BlockSpec b) {
            long key = column(p);
            if (b == null || b.isAir()) {
                if (blocks.remove(p) == null) return;
                java.util.TreeSet<Integer> ys = columns.get(key);
                ys.remove(p.y());
                if (ys.isEmpty()) columns.remove(key);
            } else {
                blocks.put(p, b);
                columns.computeIfAbsent(key, k -> new java.util.TreeSet<>()).add(p.y());
            }
        }

        private static long column(Pos p) {
            return ((long) p.x() << 32) | (p.z() & 0xFFFFFFFFL);
        }

        /** The highest block of the column (exact, unlike a chunk section top); MIN_VALUE for an empty column. */
        @Override
        public int topY(Pos column) {
            java.util.TreeSet<Integer> ys = columns.get(column(column));
            return ys == null ? Integer.MIN_VALUE : ys.last();
        }

        @Override public boolean isLoaded(Pos pos) { return loaded.test(pos); }

        @Override
        public BlockSpec block(Pos pos) {
            if (!loaded.test(pos)) return BlockSpec.AIR;
            return blocks.getOrDefault(pos, BlockSpec.AIR);
        }

        @Override public boolean hasFluid(Pos pos) { return fluid.contains(pos); }
        @Override public double playerX() { return x; }
        @Override public double playerY() { return y; }
        @Override public double playerZ() { return z; }
        @Override public Pos playerBlock() { return new Pos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)); }
        @Override public int viewDistance() { return viewDistance; }
        int simulationDistance;
        @Override public int simulationDistance() { return simulationDistance; }

        /** Empty without entity data or when a corner of the box is not loaded. */
        @Override
        public OptionalInt itemEntities(Pos min, Pos max) {
            if (items == null || !loaded.test(min) || !loaded.test(max)) return OptionalInt.empty();
            int n = 0;
            for (double[] i : items) if (inBox(i, min, max)) n++;
            return OptionalInt.of(n);
        }

        /** The block position of {@code p} (x, y, z) lies in the box from {@code min} to {@code max} (inclusive). */
        static boolean inBox(double[] p, Pos min, Pos max) {
            int bx = (int) Math.floor(p[0]), by = (int) Math.floor(p[1]), bz = (int) Math.floor(p[2]);
            return bx >= Math.min(min.x(), max.x()) && bx <= Math.max(min.x(), max.x())
                && by >= Math.min(min.y(), max.y()) && by <= Math.max(min.y(), max.y())
                && bz >= Math.min(min.z(), max.z()) && bz <= Math.max(min.z(), max.z());
        }

        void moveTo(Pos p) {
            x = p.x() + 0.5;
            y = p.y();
            z = p.z() + 0.5;
        }

        /** The vehicle the bot rides (the colour-suppression ride's cart); null = none. */
        Thing vehicle;
        /** Other entities ({@link #entities}): ender pearls in stasis cells, say. */
        final List<Thing> things = new ArrayList<>();

        @Override
        public Thing vehicle() {
            return vehicle;
        }

        @Override
        public List<Thing> entities(Set<String> types) {
            return things.stream().filter(t -> types.contains(t.type())).toList();
        }
    }

    // ------------------------------------------------------------------------------------------
    static final class FakeInventory implements InventoryView {
        final Map<String, Integer> items = new TreeMap<>();
        final List<Integer> filledMaps = new ArrayList<>();
        /** Anvil names of filled maps by id. */
        final Map<Integer, String> mapNames = new HashMap<>();
        final FakeCatalog catalog;
        String boots;
        double bootsDurability = 1.0;
        /** The worn boots have Mending (what {@link #armor} reports; the fakes' XP mends worn boots either way). */
        boolean bootsMending = true;
        /** Worn head, chest and legs pieces by armour slot (5, 6, 7); the feet are {@link #boots}. */
        final Map<Integer, FakeArmor> worn = new HashMap<>();
        /**
         * Carried armour pieces with a known durability (taken off, or put there by a test), in slot order (reported at
         * slots 9, 10, ...); each is also counted in {@link #items}. Carried armour items beyond these read as new.
         */
        final List<FakeArmor> carriedArmor = new ArrayList<>();
        /** Whether carried armour items without a record (new ones) have Mending. */
        boolean freshArmorMending = true;
        int xpPoints;
        int foodLevel = 20;
        float health = 20f;
        /** Per-stack wear of carried tools; stacks of a tool id beyond this list are fresh (see {@link #stacksOf}). */
        final List<FakeTool> toolStacks = new ArrayList<>();
        /** Whether fresh tool stacks (Sim start, withdrawn from a chest) have Mending. */
        boolean freshToolsMending = true;
        /**
         * The offhand stack (id → count), outside the 36 slots like the production view: not in counts, contents or
         * free slots; reported by {@link #offhand()} only. Nothing in the fakes moves it. A totem by default, as live
         * ({@code safety.requireTotem} holds every walking task without one).
         */
        final Map<String, Integer> offhand = new TreeMap<>(Map.of("minecraft:totem_of_undying", 1));

        FakeInventory(FakeCatalog catalog) {
            this.catalog = catalog;
        }

        @Override
        public Map<String, Integer> offhand() {
            return Map.copyOf(offhand);
        }

        @Override
        public Map<String, Integer> slotsUsed() {
            Map<String, Integer> m = new TreeMap<>();
            for (var e : items.entrySet()) {
                int max = catalog.maxStackSize(e.getKey());
                m.put(e.getKey(), (e.getValue() + max - 1) / max);
            }
            if (!filledMaps.isEmpty()) m.put("minecraft:filled_map", filledMaps.size());
            return m;
        }

        /** One armour piece: id, uses left, maximum, Mending. */
        static final class FakeArmor {
            final String id;
            int remaining;
            final int max;
            boolean mending;

            FakeArmor(String id, int remaining, boolean mending) {
                this.id = id;
                this.max = armorMax(id);
                this.remaining = Math.max(0, Math.min(max, remaining));
                this.mending = mending;
            }

            @Override
            public String toString() {
                return id + " " + remaining + "/" + max + (mending ? " mending" : "");
            }
        }

        /** Vanilla maximum durability of an armour piece (300 for anything else). */
        static int armorMax(String id) {
            String p = id.substring(id.indexOf(':') + 1);
            return switch (p) {
                case "netherite_boots" -> 481;
                case "diamond_boots" -> 429;
                case "iron_boots" -> 195;
                case "netherite_helmet" -> 407;
                case "diamond_helmet" -> 363;
                case "netherite_chestplate" -> 592;
                case "diamond_chestplate" -> 528;
                case "netherite_leggings" -> 555;
                case "diamond_leggings" -> 495;
                case "elytra" -> 432;
                default -> 300;
            };
        }

        /** Armour slot of an armour item id (5 head .. 8 feet), or -1. */
        static int armorSlot(String id) {
            if (id.endsWith("_helmet")) return 5;
            if (id.endsWith("_chestplate") || id.endsWith(":elytra")) return 6;
            if (id.endsWith("_leggings")) return 7;
            if (id.endsWith("_boots")) return 8;
            return -1;
        }

        /** Uses left of the worn boots ({@link #bootsDurability} of their maximum). */
        int bootsUses() {
            return boots == null ? 0 : (int) Math.round(bootsDurability * armorMax(boots));
        }

        /** Wears {@code a} in its armour slot (the slot must be empty). */
        void wear(FakeArmor a) {
            int slot = armorSlot(a.id);
            if (slot == 8) {
                boots = a.id;
                bootsDurability = (double) a.remaining / a.max;
                bootsMending = a.mending;
            } else {
                worn.put(slot, a);
            }
        }

        /** Puts {@code a} into the inventory as a carried piece with its durability. */
        void carry(FakeArmor a) {
            carriedArmor.add(a);
            add(a.id, 1);
        }

        @Override
        public List<Armor> armor() {
            List<Armor> out = new ArrayList<>();
            for (int slot = Armor.HEAD; slot < Armor.FEET; slot++) {
                FakeArmor a = worn.get(slot);
                if (a != null) out.add(new Armor(slot, a.id, a.remaining, a.max, a.mending));
            }
            if (boots != null) out.add(new Armor(Armor.FEET, boots, bootsUses(), armorMax(boots), bootsMending));
            int i = 0;
            Map<String, Integer> recorded = new HashMap<>();
            for (FakeArmor a : carriedArmor) {
                out.add(new Armor(ItemIdsSlots.MAIN_START + i++, a.id, a.remaining, a.max, a.mending));
                recorded.merge(a.id, 1, Integer::sum);
            }
            int fresh = 0;
            for (var e : items.entrySet()) {
                if (armorSlot(e.getKey()) < 0) continue;
                int n = e.getValue() - recorded.getOrDefault(e.getKey(), 0);
                for (int k = 0; k < n; k++) {
                    int max = armorMax(e.getKey());
                    out.add(new Armor(ItemIdsSlots.FRESH_START + fresh++, e.getKey(), max, max, freshArmorMending));
                }
            }
            return out;
        }

        /** Synthetic player slots of {@link #armor}: records from 9, new pieces from 30. */
        static final class ItemIdsSlots {
            static final int MAIN_START = 9;
            static final int FRESH_START = 30;
        }

        /** One tool stack: uses left, maximum, Mending. */
        static final class FakeTool {
            final String id;
            int remaining;
            final int max;
            final boolean mending;

            FakeTool(String id, int remaining, int max, boolean mending) {
                this.id = id;
                this.remaining = remaining;
                this.max = max;
                this.mending = mending;
            }

            double fraction() {
                return (double) remaining / max;
            }

            @Override
            public String toString() {
                return id + " " + remaining + "/" + max + (mending ? " mending" : "");
            }
        }

        static int maxUses(String id) {
            if (id.startsWith("minecraft:netherite_")) return 2031;
            if (id.startsWith("minecraft:diamond_")) return 1561;
            if (id.startsWith("minecraft:iron_")) return 250;
            if (id.startsWith("minecraft:stone_")) return 131;
            if (id.startsWith("minecraft:golden_")) return 32;
            if (id.equals("minecraft:shears")) return 238;
            return 59;
        }

        /** The stacks of tool {@code id}, reconciled with its item count (missing ones are fresh). */
        List<FakeTool> stacksOf(String id) {
            int n = items.getOrDefault(id, 0);
            List<FakeTool> out = new ArrayList<>();
            for (FakeTool t : toolStacks) if (t.id.equals(id)) out.add(t);
            while (out.size() < n) {
                FakeTool t = new FakeTool(id, maxUses(id), maxUses(id), freshToolsMending);
                toolStacks.add(t);
                out.add(t);
            }
            while (out.size() > n) toolStacks.remove(out.remove(out.size() - 1));
            return out;
        }

        /** Every carried tool stack of {@code kind} ("pickaxe", ...). */
        List<FakeTool> toolsOfKind(String kind) {
            List<FakeTool> out = new ArrayList<>();
            for (String id : new ArrayList<>(items.keySet())) {
                if (kind.equals(InventoryView.toolKind(id))) out.addAll(stacksOf(id));
            }
            return out;
        }

        /** Carries one more {@code id} with the given wear. */
        FakeTool addTool(String id, int remaining, boolean mending) {
            stacksOf(id);
            FakeTool t = new FakeTool(id, remaining, maxUses(id), mending);
            toolStacks.add(t);
            items.merge(id, 1, Integer::sum);
            return t;
        }

        void removeTool(FakeTool t) {
            stacksOf(t.id);
            toolStacks.remove(t);
            remove(t.id, 1);
        }

        /** The one carried stack of {@code id} (tests with a single tool of that id). */
        FakeTool tool(String id) {
            List<FakeTool> l = stacksOf(id);
            if (l.size() != 1) throw new IllegalStateException(l.size() + " stacks of " + id);
            return l.getFirst();
        }

        void add(String id, int n) {
            if (n > 0) items.merge(id, n, Integer::sum);
        }

        boolean remove(String id, int n) {
            int have = items.getOrDefault(id, 0);
            if (have < n) return false;
            if (have == n) items.remove(id);
            else items.put(id, have - n);
            return true;
        }

        int room(String id) {
            int max = catalog.maxStackSize(id);
            int have = items.getOrDefault(id, 0);
            int partial = have % max == 0 ? 0 : max - have % max;
            return freeSlots() * max + partial;
        }

        @Override
        public int count(String itemId) {
            if (itemId.equals("minecraft:filled_map")) return filledMaps.size();
            return items.getOrDefault(itemId, 0);
        }

        @Override
        public Map<String, Integer> contents() {
            Map<String, Integer> m = new TreeMap<>(items);
            if (!filledMaps.isEmpty()) m.put("minecraft:filled_map", filledMaps.size());
            return m;
        }

        @Override
        public int freeSlots() {
            int used = filledMaps.size();
            for (var e : items.entrySet()) {
                int max = catalog.maxStackSize(e.getKey());
                used += (e.getValue() + max - 1) / max;
            }
            return Math.max(0, 36 - used);
        }

        @Override public int xpLevel() { return xpPoints / 7; }
        @Override public List<Integer> filledMapIds() { return List.copyOf(filledMaps); }

        @Override
        public Optional<String> mapName(int mapId) {
            return filledMaps.contains(mapId) ? Optional.ofNullable(mapNames.get(mapId)) : Optional.empty();
        }
        @Override public String bootsItem() { return boots; }
        @Override public double bootsDurability() { return boots == null ? 1.0 : bootsDurability; }

        @Override
        public boolean hasTools() {
            for (String id : items.keySet()) for (String s : TOOLS_SUFFIX) if (id.endsWith(s)) return true;
            return false;
        }

        @Override
        public List<Tool> tools() {
            List<Tool> out = new ArrayList<>();
            for (String id : new ArrayList<>(items.keySet())) {
                if (InventoryView.toolKind(id) == null) continue;
                for (FakeTool t : stacksOf(id)) out.add(new Tool(id, t.remaining, t.max, t.mending));
            }
            return out;
        }

        @Override
        public int foodCount() {
            int n = 0;
            for (var e : items.entrySet()) if (FOODS.contains(e.getKey())) n += e.getValue();
            return n;
        }

        @Override public int foodLevel() { return foodLevel; }
        @Override public float health() { return health; }
    }

    // ------------------------------------------------------------------------------------------
    static final class FakeMaps implements MapView {
        /** Every map the fake server draws (id, pixels): a test may forward them as map packets (e.g. into a MapStore). */
        java.util.function.BiConsumer<Integer, byte[]> onDraw = (id, px) -> { };
        final Map<Integer, byte[]> colors = new HashMap<>() {
            @Override
            public byte[] put(Integer id, byte[] px) {
                byte[] old = super.put(id, px);
                onDraw.accept(id, px);
                return old;
            }
        };
        final Set<Integer> locked = new HashSet<>();
        /** Maps whose data never reaches the bot (e.g. ZenithProxy's cache full): {@link #colors(int)} is empty. */
        java.util.function.IntPredicate blind = id -> false;
        Integer held;
        int nextId = 100;

        @Override public Optional<byte[]> colors(int mapId) {
            return blind.test(mapId) ? Optional.empty() : Optional.ofNullable(colors.get(mapId));
        }
        @Override public boolean isLocked(int mapId) { return locked.contains(mapId); }
        @Override public Optional<Integer> heldMapId() { return Optional.ofNullable(held); }
        @Override public byte expectedPacked(int mapColorId) { return (byte) ((mapColorId << 2) | 1); }
    }

    // ------------------------------------------------------------------------------------------
    static final class FakeStore implements JobStore {
        final List<Job> jobs = new ArrayList<>();
        final Map<String, TileArt> tiles = new HashMap<>();
        final StockIndex stock = new StockIndex();
        EngineCheckpoint checkpoint = new EngineCheckpoint();
        final List<String> phaseLog = new ArrayList<>();
        java.util.function.Consumer<EngineCheckpoint> onSave = cp -> { };
        int jobSaves, checkpointSaves, stockSaves;

        /** Number of times the engine entered {@code phase} (consecutive saves of the same phase count once). */
        synchronized int entries(String phase) {
            int n = 0;
            String prev = null;
            for (String p : phaseLog) {
                if (p.equals(phase) && !p.equals(prev)) n++;
                prev = p;
            }
            return n;
        }

        @Override
        public Job create(ParsedSubmission submission, ValidationResult validation, String displayName,
                          String submitterId, String submitterName) {
            throw new UnsupportedOperationException();
        }

        @Override public synchronized List<Job> list() { return List.copyOf(jobs); }

        @Override
        public synchronized Optional<Job> get(String id) {
            for (Job j : jobs) if (j.id.equals(id)) return Optional.of(j);
            return Optional.empty();
        }

        @Override public synchronized void save(Job job) { jobSaves++; }

        @Override
        public TileArt loadTile(String jobId, TileCoord coord) throws IOException {
            TileArt t = tiles.get(jobId + ":" + coord);
            if (t == null) throw new IOException("no tile " + jobId + " " + coord);
            return t;
        }

        /** The engine's blobs, kept only once a test sets a map here (null: none kept, as the store's default). */
        public Map<String, byte[]> blobs;

        @Override
        public byte[] loadBlob(String name) {
            return blobs == null ? null : blobs.get(name);
        }

        @Override
        public void saveBlob(String name, byte[] data) {
            if (blobs == null) return;
            if (data == null) blobs.remove(name);
            else blobs.put(name, data);
        }

        /** Tiles replaced through {@link #saveTile}, in order ("j0001:2,0"); a test may make one fail ({@link #failTileSave}). */
        public final List<String> tileSaves = new ArrayList<>();
        public String failTileSave;

        @Override
        public void saveTile(String jobId, TileCoord coord, TileArt art) throws IOException {
            String key = jobId + ":" + coord;
            if (!tiles.containsKey(key)) throw new IOException("no tile " + jobId + " " + coord);
            if (key.equals(failTileSave)) throw new IOException("disk full at " + key);
            tiles.put(key, art);
            tileSaves.add(key);
        }

        @Override
        public synchronized boolean cancel(String id) {
            Optional<Job> j = get(id);
            if (j.isEmpty() || j.get().status.terminal()) return false;
            j.get().status = JobStatus.CANCELLED;
            return true;
        }

        @Override public boolean move(String id, int index) { return false; }

        @Override
        public synchronized Optional<Job> next() {
            for (JobStatus s : List.of(JobStatus.ACTIVE, JobStatus.QUEUED, JobStatus.WAITING_MATERIALS))
                for (Job j : jobs) if (j.status == s) return Optional.of(j);
            return Optional.empty();
        }

        @Override
        public synchronized EngineCheckpoint loadCheckpoint() {
            return copy(checkpoint);
        }

        @Override
        public synchronized void saveCheckpoint(EngineCheckpoint cp) {
            checkpointSaves++;
            phaseLog.add(cp.phase);
            checkpoint = copy(cp);
            onSave.accept(cp);
        }

        @Override public StockIndex stock() { return stock; }
        @Override public void saveStock() { stockSaves++; }

        static EngineCheckpoint copy(EngineCheckpoint c) {
            EngineCheckpoint o = new EngineCheckpoint();
            o.jobId = c.jobId;
            o.tileX = c.tileX;
            o.tileZ = c.tileZ;
            o.platformId = c.platformId;
            o.phase = c.phase;
            o.phaseSince = c.phaseSince;
            o.clearAttempts = c.clearAttempts;
            o.repairPasses = c.repairPasses;
            o.fillAttempts = c.fillAttempts;
            o.stored = c.stored;
            o.blockedFrom = c.blockedFrom;
            o.note = c.note;
            return o;
        }
    }

    // ------------------------------------------------------------------------------------------
    /** Serpentine N→S/S→N lanes of width 2w+1 (stand-in for the real planner from agent core). */
    static final class SerpentinePlanner implements BuildPlanner {
        @Override
        public List<Lane> plan(TileArt art, PlatformGeometry geo, BlockCatalog catalog, int laneHalfWidth, Predicate<Placement> done) {
            int w = 2 * laneHalfWidth + 1;
            List<Lane> out = new ArrayList<>();
            int idx = 0;
            for (int start = 0, li = 0; start < TileArt.SIZE; start += w, li++) {
                boolean south = li % 2 == 0;
                List<Placement> ps = new ArrayList<>();
                for (int k = 0; k < TileArt.SIZE; k++) {
                    int pz = south ? k : TileArt.SIZE - 1 - k;
                    for (int px = start; px < Math.min(start + w, TileArt.SIZE); px++) {
                        BlockSpec s = art.get(px, pz);
                        if (s == null) continue;
                        Placement p = new Placement(geo.cell(px, pz), s, catalog.placementItem(s).orElseThrow());
                        if (!done.test(p)) ps.add(p);
                    }
                }
                if (ps.isEmpty()) continue;
                int c = Math.min(start + laneHalfWidth, TileArt.SIZE - 1);
                out.add(new Lane(idx++, geo.cell(c, south ? 0 : 127), geo.cell(c, south ? 127 : 0), ps));
            }
            return out;
        }
    }

    // ------------------------------------------------------------------------------------------
    static final class RecordingNotifier implements Notifier {
        record Msg(String kind, String title, String body) { }

        final List<Msg> messages = new ArrayList<>();
        int progressCalls;
        long lastEta = Long.MIN_VALUE;

        @Override public synchronized void info(String title, String body) { messages.add(new Msg("info", title, body)); }
        /** Verbose-only steps ({@code discord.verbose}), recorded apart from the always-posted {@code info}. */
        @Override public synchronized void detail(String title, String body) { messages.add(new Msg("detail", title, body)); }
        @Override public synchronized void alert(String title, String body) { messages.add(new Msg("alert", title, body)); }

        @Override
        public synchronized void image(String title, String body, String fileName, byte[] png) {
            messages.add(new Msg("image", title, body + " [" + fileName + "]"));
        }

        /** A verbose-only image: recorded as a {@code detail} (its file name in the body, as for {@code image}). */
        @Override
        public synchronized void detailImage(String title, String body, String fileName, byte[] png) {
            messages.add(new Msg("detail", title, body + " [" + fileName + "]"));
        }

        @Override
        public synchronized void progress(Job job, int tileX, int tileZ, int placed, int total, long etaSeconds) {
            progressCalls++;
            lastEta = etaSeconds;
        }

        synchronized List<Msg> of(String kind) {
            return messages.stream().filter(m -> m.kind().equals(kind)).toList();
        }

        synchronized boolean has(String kind, String titlePart) {
            return messages.stream().anyMatch(m -> m.kind().equals(kind) && m.title().contains(titlePart));
        }

        @Override
        public synchronized String toString() {
            StringBuilder sb = new StringBuilder();
            for (Msg m : messages) sb.append(m.kind()).append(": ").append(m.title()).append(" | ").append(m.body().replace('\n', ' ')).append('\n');
            return sb.toString();
        }
    }

    static final class FakeModules implements ModuleSwitch {
        final Map<String, Boolean> state = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final List<String> log = new ArrayList<>();

        @Override public boolean isEnabled(String moduleName) { return state.getOrDefault(moduleName, false); }

        @Override
        public void setEnabled(String moduleName, boolean enabled) {
            if (!state.containsKey(moduleName)) return;
            state.put(moduleName, enabled);
            log.add(moduleName + "=" + enabled);
        }
    }

    static String describe(EngineStatus s) {
        return s.phase() + " job=" + s.jobId() + " tile=" + s.tileX() + "," + s.tileZ() + " placed=" + s.placed() + "/" + s.total()
            + " paused=" + s.paused() + " note=" + s.note();
    }
}
