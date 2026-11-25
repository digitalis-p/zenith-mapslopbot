package io.github.digitalisp.mapart.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.SimFakes.FakeCatalog;
import io.github.digitalisp.mapart.engine.SimFakes.FakeColors;
import io.github.digitalisp.mapart.engine.SimFakes.FakeInventory;
import io.github.digitalisp.mapart.engine.SimFakes.FakeMaps;
import io.github.digitalisp.mapart.engine.SimFakes.FakeModules;
import io.github.digitalisp.mapart.engine.SimFakes.FakeRenderer;
import io.github.digitalisp.mapart.engine.SimFakes.FakeStore;
import io.github.digitalisp.mapart.engine.SimFakes.FakeWorld;
import io.github.digitalisp.mapart.engine.SimFakes.RecordingNotifier;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.engine.SimFakes.TestClock;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * A whole site in memory: two platforms (moss with a collection lever, soul sand with boots), material
 * chests in a region, role chests, cartography table and anvil, plus the engine wired to fakes.
 */
final class Sim {
    static final String WHITE = "minecraft:white_carpet";
    static final String RED = "minecraft:red_carpet";
    static final String BLACK = "minecraft:black_carpet";
    static final String LIME = "minecraft:lime_carpet";
    static final String TERRACOTTA = "minecraft:white_terracotta";

    static final Pos MOSS_BUTTON = new Pos(-70, 64, 0);
    /** Moss item collection: two note blocks and a lamp (lit = carts on), like the real platform. */
    static final Pos MOSS_CARTS_ON = new Pos(-70, 64, 7);
    static final Pos MOSS_CARTS_OFF = new Pos(-70, 64, 5);
    static final Pos MOSS_LAMP = new Pos(-70, 64, 6);
    static final Pos SOUL_BUTTON = new Pos(190, 64, 0);
    static final Pos RETURN = new Pos(-84, 61, 105);   // inside the material region on purpose
    static final Pos MAPS = new Pos(-58, 61, 100);
    static final Pos PANES = new Pos(-56, 61, 100);
    static final Pos OUTPUT = new Pos(-54, 61, 100);
    static final Pos XP = new Pos(-52, 61, 100);
    static final Pos ANVILS = new Pos(-50, 61, 100);
    static final Pos TOOLS = new Pos(-48, 61, 100);
    static final Pos FOOD = new Pos(-46, 61, 100);
    static final Pos BOOTS = new Pos(-44, 61, 100);
    static final Pos TABLE = new Pos(-40, 61, 100);
    static final Pos ANVIL = new Pos(-38, 61, 100);
    static final Logger LOG = LoggerFactory.getLogger("sim");

    final MapArtConfig config = new MapArtConfig();
    final TestClock clock = new TestClock(1_760_000_000_000L);
    final FakeCatalog catalog = new FakeCatalog();
    final FakeWorld world = new FakeWorld();
    final FakeInventory inv = new FakeInventory(catalog);
    final FakeMaps maps = new FakeMaps();
    final FakeBot bot = new FakeBot(world, inv, maps, catalog);
    final FakeStore store = new FakeStore();
    final RecordingNotifier notifier = new RecordingNotifier();
    final FakeModules modules = new FakeModules();
    final FakeRenderer renderer = new FakeRenderer();
    final MapArtConfig.PlatformCfg moss;
    final MapArtConfig.PlatformCfg soul;
    boolean clearWorks = true;
    int machineBroken;
    /**
     * With entity data on ({@link #entityData}): item entities the fake moss clear drops over the tile when it empties
     * the art layer (3 s after the press).
     */
    int itemsPerClear;
    /** Fake hopper minecarts: while ON, one dropped item on the moss tile goes every this many ticks; 0 = never. */
    int cartTicksPerItem = 10;
    BuildEngine engine;
    long ticks;
    /** Real milliseconds per tick: 50 at normal speed, 5 like the local test server at 10x. */
    long msPerTick = 50;

    Sim() {
        config.enabled = true;
        config.discord.progressIntervalMinutes = 1;
        config.build.stockMaxAgeMinutes = 60;

        moss = new MapArtConfig.PlatformCfg();
        moss.id = "moss";
        moss.kind = "MOSS";
        moss.minX = -64;
        moss.minZ = -64;
        moss.artY = 64;
        moss.floorBlock = "minecraft:moss_block";
        moss.center = new Pos(0, 64, 0);
        moss.clearTimeoutSeconds = 10;
        moss.stayDuringClear = false;
        MapArtConfig.ActuatorCfg mb = new MapArtConfig.ActuatorCfg();
        mb.pos = MOSS_BUTTON;
        moss.clear.add(mb);
        moss.collectionOn = new MapArtConfig.ActuatorCfg();
        moss.collectionOn.pos = MOSS_CARTS_ON;
        moss.collectionOff = new MapArtConfig.ActuatorCfg();
        moss.collectionOff.pos = MOSS_CARTS_OFF;
        moss.collectionIndicator = MOSS_LAMP;
        world.set(MOSS_CARTS_ON, BlockSpec.of("minecraft:note_block", Map.of("instrument", "harp", "note", "0", "powered", "false")));
        world.set(MOSS_CARTS_OFF, BlockSpec.of("minecraft:note_block", Map.of("instrument", "harp", "note", "0", "powered", "false")));
        world.set(MOSS_LAMP, BlockSpec.of("minecraft:redstone_lamp", Map.of("lit", "false")));
        bot.useHandlers.put(MOSS_CARTS_ON, () -> setCarts(true));
        bot.useHandlers.put(MOSS_CARTS_OFF, () -> setCarts(false));
        world.set(MOSS_BUTTON, BlockSpec.of("minecraft:stone_button", Map.of("face", "floor", "facing", "north", "powered", "false")));
        bot.collectionLamp = MOSS_LAMP;

        soul = new MapArtConfig.PlatformCfg();
        soul.id = "soul";
        soul.kind = "SOUL_SAND";
        soul.minX = 192;
        soul.minZ = -64;
        soul.artY = 64;
        soul.floorBlock = "minecraft:soul_sand";
        soul.center = new Pos(256, 64, 0);
        soul.clearTimeoutSeconds = 10;
        soul.stayDuringClear = true;
        soul.soulSpeedBoots = true;
        MapArtConfig.ActuatorCfg sb = new MapArtConfig.ActuatorCfg();
        sb.pos = SOUL_BUTTON;
        soul.clear.add(sb);
        config.site.platforms.add(moss);
        config.site.platforms.add(soul);
        bot.clearMachine(moss, MOSS_BUTTON, 60, () -> clearWorks, () -> machineBroken++);
        bot.clearMachine(soul, SOUL_BUTTON, 60, () -> clearWorks, () -> machineBroken++);
        Runnable mossClear = bot.useHandlers.get(MOSS_BUTTON);
        bot.useHandlers.put(MOSS_BUTTON, () -> {
            mossClear.run();
            setCarts(true); // any middle clear button also switches the carts on
            bot.schedule(60, this::dropClearItems); // runs after the fake machine emptied the art layer (same tick)
        });

        MapArtConfig.RegionCfg region = new MapArtConfig.RegionCfg();
        region.min = new Pos(-100, 60, 100);
        region.max = new Pos(-80, 62, 110);
        config.site.storage.materialRegions.add(region);
        bot.addContainer(new Pos(-100, 61, 100), Map.of(WHITE, 1728));
        bot.addContainer(new Pos(-98, 61, 100), Map.of(WHITE, 1728));
        bot.addContainer(new Pos(-96, 61, 100), Map.of(RED, 1728));
        bot.addContainer(new Pos(-94, 61, 100), Map.of(BLACK, 1728));
        bot.addContainer(new Pos(-92, 61, 100), Map.of(TERRACOTTA, 1728));

        config.site.storage.returnChests.add(RETURN);
        config.site.storage.mapChests.add(MAPS);
        config.site.storage.paneChests.add(PANES);
        config.site.storage.outputChests.add(OUTPUT);
        config.site.storage.xpChests.add(XP);
        config.site.storage.anvilChests.add(ANVILS);
        config.site.storage.toolChests.add(TOOLS);
        config.site.storage.foodChests.add(FOOD);
        config.site.storage.bootChests.add(BOOTS);
        bot.addContainer(RETURN, Map.of());
        bot.addContainer(MAPS, Map.of("minecraft:map", 64));
        bot.addContainer(PANES, Map.of("minecraft:glass_pane", 64));
        bot.addContainer(OUTPUT, Map.of());
        bot.addContainer(XP, Map.of("minecraft:experience_bottle", 640));
        bot.addContainer(ANVILS, Map.of("minecraft:anvil", 4));
        bot.addContainer(TOOLS, Map.of("minecraft:diamond_pickaxe", 4));
        bot.addContainer(FOOD, Map.of("minecraft:golden_carrot", 640));
        bot.addContainer(BOOTS, Map.of("minecraft:netherite_boots", 1));
        config.site.cartographyTable = TABLE;
        config.site.anvil = ANVIL;
        world.set(TABLE, BlockSpec.of("minecraft:cartography_table"));
        world.set(ANVIL, BlockSpec.of("minecraft:anvil", Map.of("facing", "north")));

        inv.add("minecraft:diamond_pickaxe", 1);
        bot.config = config;
        inv.add("minecraft:golden_carrot", 32);
        inv.xpPoints = 0;

        modules.state.put("AntiAFK", true);
        modules.state.put("KillAura", true);
        modules.state.put("AutoEat", true);
        bot.autoEatOn = () -> modules.isEnabled("AutoEat");
        bot.phaseProbe = () -> engine == null ? null : engine.status().phase();
        world.moveTo(new Pos(-60, 61, 90));
        newEngine();
    }

    /** The fake serpentine build planner and the production restock planner (its capacity rules run for real). */
    void newEngine() {
        newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), LOG);
    }

    /** Rebuilds the engine with other planners (e.g. the production ones) and logger. */
    void newEngine(io.github.digitalisp.mapart.plan.BuildPlanner planner, io.github.digitalisp.mapart.plan.RestockPlanner restock, Logger log) {
        engine = new BuildEngine(config, store, bot, planner, restock, catalog,
            renderer, new FakeColors(), notifier, clock, Runnable::run, modules, log);
    }

    /**
     * The engine behind a scripted coordination: jobs, units and claims from {@code coord}, its own {@code store}
     * (a second account has its own queue and checkpoint), the same world and bot.
     */
    void newEngine(io.github.digitalisp.mapart.store.JobStore s, io.github.digitalisp.mapart.coord.Coordination coord) {
        engine = new BuildEngine(config, s, bot, new SerpentinePlanner(), new GreedyRestockPlanner(), catalog,
            renderer, new FakeColors(), notifier, clock, Runnable::run, modules, LOG, coord);
    }

    void start() {
        engine.onBotTickStarting();
    }

    void tick() {
        ticks++;
        clock.advance(msPerTick);
        bot.tick(ticks);
        cartsCollect();
        engine.onBotTick();
        bot.endTick();
    }

    // ---- the server's damage events on the bot (BuildEngine#onHit) ----

    private int projectileIds = 90_000;

    static io.github.digitalisp.mapart.bot.WorldView.Thing thing(io.github.digitalisp.mapart.bot.WorldView.Mob m) {
        return new io.github.digitalisp.mapart.bot.WorldView.Thing(m.id(), m.type(), m.x(), m.y(), m.z());
    }

    /** An arrow {@code shooter} shot hit the bot ("minecraft:arrow", cause the shooter, direct the arrow). */
    void arrowFrom(io.github.digitalisp.mapart.bot.WorldView.Mob shooter) {
        engine.onHit(new io.github.digitalisp.mapart.bot.Hit("minecraft:arrow", thing(shooter),
            new io.github.digitalisp.mapart.bot.WorldView.Thing(projectileIds++, "arrow", world.x, world.y + 1, world.z)));
    }

    /** {@code mob} struck the bot (cause and direct the mob: "minecraft:mob_attack"). */
    void blowFrom(io.github.digitalisp.mapart.bot.WorldView.Mob mob) {
        io.github.digitalisp.mapart.bot.WorldView.Thing t = thing(mob);
        engine.onHit(new io.github.digitalisp.mapart.bot.Hit("minecraft:mob_attack", t, t));
    }

    /** Damage with no entity behind it ("minecraft:fall", "minecraft:fly_into_wall", "minecraft:on_fire"). */
    void damage(String type) {
        engine.onHit(new io.github.digitalisp.mapart.bot.Hit(type, null, null));
    }

    /** Entity data on (no dropped items yet) or off ({@link FakeWorld#itemEntities} empty). */
    void entityData(boolean on) {
        world.items = on ? new java.util.ArrayList<>() : null;
    }

    void dropItem(double x, double y, double z) {
        world.items.add(new double[]{x, y, z});
    }

    /** Dropped items in the moss collection volume: the tile, floor - 2 to art + 3. */
    int itemsOnMoss() {
        return world.items == null ? 0 : (int) world.items.stream().filter(this::onMoss).count();
    }

    private boolean onMoss(double[] i) {
        return FakeWorld.inBox(i, new Pos(moss.minX, moss.artY - 3, moss.minZ), new Pos(moss.minX + 127, moss.artY + 3, moss.minZ + 127));
    }

    private void dropClearItems() {
        if (world.items == null || !clearWorks) return;
        for (int i = 0; i < itemsPerClear; i++)
            dropItem(moss.minX + (i * 37) % 128 + 0.5, moss.artY + 0.1, moss.minZ + (i * 53) % 128 + 0.5);
    }

    private void cartsCollect() {
        if (world.items == null || cartTicksPerItem <= 0 || ticks % cartTicksPerItem != 0 || !cartsOn()) return;
        for (int i = 0; i < world.items.size(); i++) {
            if (onMoss(world.items.get(i))) {
                world.items.remove(i);
                return;
            }
        }
    }

    boolean runUntil(BooleanSupplier cond, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            if (cond.getAsBoolean()) return true;
            tick();
        }
        return cond.getAsBoolean();
    }

    void run(int n) {
        for (int i = 0; i < n; i++) tick();
    }

    Phase phase() {
        return engine.status().phase();
    }

    String state() {
        return SimFakes.describe(engine.status()) + "\n" + notifier;
    }

    /** Adds a queued job whose tiles come from {@code art(x, z)}. */
    Job addJob(String id, String name, int tilesX, int tilesZ, List<String> platforms, BiFunction<Integer, Integer, TileArt> art) {
        Job j = new Job();
        j.id = id;
        j.name = name;
        j.tilesX = tilesX;
        j.tilesZ = tilesZ;
        j.submitterId = "terminal";
        j.platformIds.addAll(platforms);
        Map<String, Integer> mats = new TreeMap<>();
        for (int z = 0; z < tilesZ; z++) {
            for (int x = 0; x < tilesX; x++) {
                Job.TileState t = new Job.TileState();
                t.x = x;
                t.z = z;
                j.tiles.add(t);
                TileArt a = art.apply(x, z);
                store.tiles.put(id + ":" + new TileCoord(x, z), a);
                a.blockCounts().forEach((k, v) -> mats.merge(BlockSpec.parse(k).id(), v, Integer::sum));
            }
        }
        j.materials = mats;
        store.jobs.add(j);
        return j;
    }

    /** Rows {@code 0..rows-1}: columns cycle white/red/black carpet; then {@code terracottaRows} rows of terracotta. */
    static TileArt stripes(int rows, int terracottaRows) {
        TileArt a = new TileArt();
        String[] cols = {WHITE, RED, BLACK};
        for (int pz = 0; pz < rows; pz++)
            for (int px = 0; px < 128; px++) a.set(px, pz, BlockSpec.of(cols[px % 3]));
        for (int pz = rows; pz < rows + terracottaRows; pz++)
            for (int px = 0; px < 128; px++) a.set(px, pz, BlockSpec.of(TERRACOTTA));
        return a;
    }

    static TileArt single(String id, int rows) {
        TileArt a = new TileArt();
        for (int pz = 0; pz < rows; pz++)
            for (int px = 0; px < 128; px++) a.set(px, pz, BlockSpec.of(id));
        return a;
    }

    int artBlocks(MapArtConfig.PlatformCfg p) {
        int n = 0;
        for (int pz = 0; pz < 128; pz++)
            for (int px = 0; px < 128; px++) if (!world.block(new Pos(p.minX + px, p.artY, p.minZ + pz)).isAir()) n++;
        return n;
    }

    boolean matchesArt(MapArtConfig.PlatformCfg p, TileArt art) {
        for (int pz = 0; pz < 128; pz++)
            for (int px = 0; px < 128; px++) {
                BlockSpec want = art.get(px, pz);
                BlockSpec have = world.block(new Pos(p.minX + px, p.artY, p.minZ + pz));
                if (want == null ? !have.isAir() : !want.equals(have)) return false;
            }
        return true;
    }

    FakeBot.Container output() {
        return bot.containers.get(OUTPUT);
    }

    boolean cartsOn() {
        return "true".equals(world.block(MOSS_LAMP).props().get("lit"));
    }

    void setCarts(boolean on) {
        world.set(MOSS_LAMP, BlockSpec.of("minecraft:redstone_lamp", Map.of("lit", String.valueOf(on))));
    }

    static final Pos EAST_BUTTON = new Pos(-62, 70, 0);
    static final Pos WEST_BUTTON = new Pos(-66, 70, 0);
    static final Pos EAST_PROBE = new Pos(70, 64, -63);
    static final Pos WEST_PROBE = new Pos(-71, 64, -63);
    static final String SLIME = "minecraft:slime_block";
    int wrongSidePresses;

    /**
     * Replaces the moss clear button with the real two-sided set-up: the flying machines are parked on one side
     * (slime block at that side's probe); only that side's button launches them, and they end up parked on the
     * other side. A press on the empty side does nothing except switch the carts on.
     */
    void twoSidedClear(boolean machinesEast) {
        moss.clear.clear();
        moss.clear.add(actuator(EAST_BUTTON, EAST_PROBE));
        moss.clear.add(actuator(WEST_BUTTON, WEST_PROBE));
        world.set(machinesEast ? EAST_PROBE : WEST_PROBE, BlockSpec.of(SLIME));
        for (Pos b : List.of(EAST_BUTTON, WEST_BUTTON))
            world.set(b, BlockSpec.of("minecraft:stone_button", Map.of("face", "floor", "facing", "east", "powered", "false")));
        Runnable sweep = bot.useHandlers.get(MOSS_BUTTON);
        bot.useHandlers.remove(MOSS_BUTTON);
        bot.useHandlers.put(EAST_BUTTON, () -> press(EAST_PROBE, WEST_PROBE, sweep));
        bot.useHandlers.put(WEST_BUTTON, () -> press(WEST_PROBE, EAST_PROBE, sweep));
    }

    private void press(Pos here, Pos other, Runnable sweep) {
        setCarts(true);
        if (!world.block(here).id().equals(SLIME)) {
            wrongSidePresses++;
            return;
        }
        world.set(here, BlockSpec.AIR);
        sweep.run(); // schedules the clear
        bot.schedule(60, () -> world.set(other, BlockSpec.of(SLIME)));
    }

    private static MapArtConfig.ActuatorCfg actuator(Pos pos, Pos probe) {
        MapArtConfig.ActuatorCfg a = new MapArtConfig.ActuatorCfg();
        a.pos = pos;
        a.probe = probe;
        a.probeBlock = SLIME;
        return a;
    }
}
