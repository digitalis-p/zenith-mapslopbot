package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.BotActions;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.bot.ContainerSnapshot;
import io.github.digitalisp.mapart.bot.ContainerVisit;
import io.github.digitalisp.mapart.bot.EscapeRequest;
import io.github.digitalisp.mapart.bot.FallRescue;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TransferResult;
import io.github.digitalisp.mapart.bot.WithdrawMode;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.config.SiteRules;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Pos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.INPUTS;

/**
 * {@link BotActions} on ZenithProxy 3.7.2 internals. Holds the adaptive placement pacer (persists across lanes)
 * and the world/inventory/map views. Tasks must be ticked from a ClientBotTick handler whose priority is above
 * ZenithProxy's InventoryManager (-5000) — the default priority 0 is right — see {@link Clicker}.
 */
public final class ZenithBotActions implements BotActions {
    private final MapArtConfig config;
    private final BlockCatalog catalog;
    private final ZenithWorldView world = new ZenithWorldView();
    private final ZenithInventoryView inventory = new ZenithInventoryView();
    private final ZenithMapView maps = new ZenithMapView();
    private final AdaptivePacer pacer;
    private java.util.function.@Nullable Function<Pos, io.github.digitalisp.mapart.model.@Nullable Placement> walkArt;
    private Consumer<Pos> artBroken = p -> { };
    private final java.util.LinkedHashSet<Pos> walkRepairs = new java.util.LinkedHashSet<>();
    /** Map stations the bot could not reach: the range around them it filled the map from ({@link MapTask}). */
    final Map<Pos, Integer> mapStandRange = new java.util.HashMap<>();

    @Override
    public void artWalkContext(java.util.function.@Nullable Function<Pos, io.github.digitalisp.mapart.model.@Nullable Placement> art,
                               Consumer<Pos> broken) {
        walkArt = art;
        artBroken = broken;
        walkRepairs.removeIf(p -> art == null || art.apply(p) == null);
    }

    io.github.digitalisp.mapart.model.@Nullable Placement walkArtAt(Pos p) { return walkArt == null ? null : walkArt.apply(p); }

    void repairWalkCells(List<Pos> cells) {
        // Partial breaks must not make the entrance precede the far end in the persistent queue.
        walkRepairs.removeAll(cells);
        for (Pos p : cells) {
            var target = walkArtAt(p);
            if (target != null && world.isLoaded(p) && !catalog.matches(target.block(), world.block(p))) {
                walkRepairs.add(p);
                artBroken.accept(p);
            }
        }
    }

    List<Pos> walkRepairs() {
        walkRepairs.removeIf(p -> {
            var t = walkArtAt(p);
            return t == null || world.isLoaded(p) && catalog.matches(t.block(), world.block(p));
        });
        return List.copyOf(walkRepairs);
    }
    /**
     * Block used (a container by its canonical position) → the feet cell it was last used from, kept across restarts
     * with {@code build.standSpotMemory}; and the cells from which no face centre of a container was in sight after all.
     */
    private final StandSpotMemory standSpots;
    /**
     * The walks from now until the next lane start are restock legs ({@link #restockLegs}): with
     * {@code build.sprintRestockLegs} they sprint ({@link SprintLegs}).
     */
    private volatile boolean restockLegs;
    /** The engine's phase lets plain walks sprint ({@link #sprintWalks}; not while the clear runs). */
    private volatile boolean walksMaySprint = true;
    /** Asked before every menu open ({@link #menuGate}): the coordination's chest and station claims. */
    private volatile io.github.digitalisp.mapart.bot.MenuGate menuGate = io.github.digitalisp.mapart.bot.MenuGate.OPEN;

    public ZenithBotActions(MapArtConfig config, BlockCatalog catalog) {
        this.config = config;
        this.catalog = catalog;
        this.pacer = new AdaptivePacer(config.build.placeIntervalStartTicks, config.build.placeIntervalMinTicks,
            config.build.placeIntervalMaxTicks, config.build.placeRecoverConfirms, config.build.placeConfirmMaxTicks);
        this.pacer.configure(PlacePacing.of(config.build)); // each lane applies it again (LaneRun.Params)
        Path spots = null;
        if (config.build.standSpotMemory && config.dataDir != null && !config.dataDir.isBlank()) {
            try {
                spots = Path.of(config.dataDir).resolve("stand-spots.txt");
            } catch (RuntimeException e) {
                ZenithTask.LOG.warn("stand spots kept for this session only: data dir {}: {}", config.dataDir, e.toString());
            }
        }
        this.standSpots = new StandSpotMemory(spots, ZenithTask.LOG::warn);
    }

    MapArtConfig config() {
        return config;
    }

    @Override
    public void restockLegs(boolean on) {
        restockLegs = on;
    }

    /** The first lane start after a restock was reached (or its lane task ended): the restock legs are over. */
    void laneStartReached() {
        restockLegs = false;
    }

    @Override
    public void sprintWalks(boolean allowed) {
        walksMaySprint = allowed;
    }

    @Override
    public void menuGate(io.github.digitalisp.mapart.bot.MenuGate gate) {
        menuGate = gate == null ? io.github.digitalisp.mapart.bot.MenuGate.OPEN : gate;
    }

    /** The gate container tasks ask before their open click ({@link ContainerTask}). */
    io.github.digitalisp.mapart.bot.MenuGate menuGate() {
        return menuGate;
    }

    /**
     * The sprint rule of a walk of this kind now ({@link SprintLegs#rule}): a restock leg with
     * {@code build.sprintRestockLegs} while the engine is in a restock, any plain walk with {@code build.sprintWalks}
     * while the engine's phase allows it; else null: no sprint.
     */
    SprintLegs.@Nullable Rule sprintRule(SprintLegs.Walk walk) {
        return SprintLegs.rule(walk, config.build, restockLegs, walksMaySprint);
    }

    /** Whether the engine counts the walks now as restock legs (logs). */
    boolean inRestock() {
        return restockLegs;
    }

    /**
     * The sprint watch's tick ({@link SprintLegs.Watch}): call on every ClientBotTick before the engine, so a click,
     * open, placement, hotbar move or eating can wait until the bot has not sprinted for {@code build.sprintQuietTicks}.
     */
    /** Owner of the short-turn input requests ({@link #tickWalkYaw}). */
    private static final Object WALK_YAW = new Object();
    private static long walkYawFixes;

    /**
     * The short turn during the plugin's walks ({@code build.walkShortTurns}, {@link WalkYaw}): call on every
     * ClientBotTick after Baritone submitted its input and before ZenithProxy's InputManager applies it (priority
     * between 0 and -10000). When Baritone's yaw would make ZenithProxy's Bot turn more than 180° in one tick, the same
     * input goes in again above Baritone's priority with the same heading by the short turn.
     */
    /** Before Baritone's tick: the one-sprint latch of the walk in progress ({@code Walker#beforeBaritone}). */
    public static void beforeBaritone() {
        Walker.beforeBaritone();
    }

    public static void tickWalkYaw(MapArtConfig config) {
        if (!config.build.walkShortTurns || PathingGuard.holders() == 0) return;
        if (!BARITONE.getPathingBehavior().isPathing()) return;
        var rot = BARITONE.getLookBehavior().currentRotation;
        if (rot == null) return;
        Float yaw = WalkYaw.shortTurn(BOT.getYaw(), rot.yaw());
        if (yaw == null) return;
        var b = InputRequest.builder().owner(WALK_YAW).priority(Clicker.PRIORITY).yaw(yaw).pitch(rot.pitch());
        Input in = BARITONE.getInputOverrideHandler().currentInput;
        if (in != null) b.input(in);
        INPUTS.submit(b.build());
        walkYawFixes++;
        ZenithTask.LOG.debug("walk: Baritone's yaw {} from {} would turn {}; turning to {} instead ({} so far)", rot.yaw(),
            BOT.getYaw(), WalkYaw.botTurn(BOT.getYaw(), rot.yaw()), yaw, walkYawFixes);
    }

    /**
     * After Baritone's input request and the short turn, before ZenithProxy's InputManager: the walk's edge stop on
     * Baritone's keys of this tick ({@link EdgeApproach#applyToBaritone}).
     */
    public static void tickEdgeSneak(MapArtConfig config) {
        Walker.checkBeforeInputs(); // a path planned this tick never takes a first step into a no-go zone
        EdgeApproach.applyToBaritone(config.build.walkShortTurns);
    }

    /**
     * After every input request of the tick, before ZenithProxy's InputManager: a fresh jump press in the air with an
     * elytra worn waits unless an elytra flight pressed it ({@link StrayGlideGuard}).
     */
    public static void tickGlideGuard() {
        StrayGlideGuard.tick();
    }

    /** Every bot tick: notes the feet cell in the track a boxed-in walk walks back along (Walker "Boxed in"). */
    public static void tickTrack() {
        Walker.noteTrack();
    }

    /**
     * Every bot tick after the engine: an elytra flight whose walk stopped in the air lands with its controller's
     * bail-out ({@code build.elytraLegs}, {@link FlightLeg#afterEngine}); nothing without one.
     */
    public static void tickFlight() {
        FlightLeg.afterEngine();
    }

    /** Short turns made so far ({@link #tickWalkYaw}; status and tests). */
    public static long walkYawFixes() {
        return walkYawFixes;
    }

    public static void tickSprintWatch(MapArtConfig config) {
        SprintLegs.Watch w = SprintLegs.Watch.SESSION;
        w.quietTicks(config.build.sprintQuietTicks);
        w.tick(BOT.isSprinting());
    }

    BlockCatalog catalog() {
        return catalog;
    }

    /** Adaptive placement pacer shared by all lanes (exposed for status displays). */
    public AdaptivePacer pacer() {
        return pacer;
    }

    @Override
    public boolean airPlaceAvailable() {
        return AirPlaceGuard.shared().airPlaceAvailable(ZenithAirPlace.INSTANCE);
    }

    /**
     * The airplace offhand guard's tick ({@link AirPlaceGuard#tick}): call on every ClientBotTick before the engine, also
     * while paused or stopped, so an airplace cut short by a pause, a stop, a takeover or a disconnect gets the offhand
     * item back on the next tick. Its events go to the log; its alerts and notice are returned for Discord.
     */
    public static GuardNews tickAirPlaceGuard(MapArtConfig config) {
        AirPlaceGuard g = AirPlaceGuard.shared();
        g.configure(config.build.offhandItem, config.build.offhandRepair,
            PlaceMode.of(config.build) == PlaceMode.AIRPLACE);
        InventoryEchoes.SESSION.configure(config.build.unknownSlotResync, config.build.unknownSlotTrustTicks);
        InventoryEchoes.SESSION.tick(config.build.placeHotbarLookahead && config.build.hotbarMoveFence
            && config.build.placeDuringMoveFence && config.build.placeStackSwitch,
            g.busy(ZenithAirPlace.INSTANCE) || (com.zenith.Globals.INVENTORY.hasActiveRequest()
                && !Hotbar.speculativeRequestBusy()));
        String inventoryEvent = InventoryEchoes.SESSION.takeEvent();
        if (inventoryEvent != null) ZenithTask.LOG.warn(inventoryEvent);
        g.tick(ZenithAirPlace.INSTANCE, ZenithAirPlace.INSTANCE);
        String e = g.takeEvent();
        if (e != null) ZenithTask.LOG.warn(e);
        return new GuardNews(g.takeAlert(), g.takeOffhandAlert(), g.takeNotice());
    }

    /**
     * What the airplace guard's tick has for Discord: {@code airplaceOff} and {@code offhand} are always-on alerts
     * (airplace turned off; the offhand without its item while the inventory holds one), {@code notice} a routine one
     * (airplace on again). Null = nothing.
     */
    public record GuardNews(@Nullable String airplaceOff, @Nullable String offhand, @Nullable String notice) { }

    @Override
    public boolean offhandRepairWanted() {
        return config.build.offhandRepair && PlaceMode.of(config.build) == PlaceMode.AIRPLACE
            && (AirPlaceGuard.shared().repairSlot(ZenithAirPlace.INSTANCE) >= 0 || offhandCheckWanted());
    }

    @Override
    public boolean offhandCheckWanted() {
        return config.build.offhandRepair && PlaceMode.of(config.build) == PlaceMode.AIRPLACE
            && AirPlaceGuard.shared().checkWanted(ZenithAirPlace.INSTANCE);
    }

    @Override
    public BotTask restoreOffhand() {
        return new OffhandRepairTask(this);
    }

    /** Bot ticks stopped (a takeover) or the connection dropped: the guard settles before it judges again. */
    public static void airPlaceGuardStopped(boolean disconnected) {
        AirPlaceGuard.shared().stopped(disconnected);
    }

    /**
     * {@link DropWatch}'s look: call on every ClientBotTick after
     * ZenithProxy's InventoryManager (-5000), so the cursor and the open menu are as this tick's click left them. The
     * cursor counts while a menu is open or an inventory request runs; a cartography table's or anvil's slots 0 and 1
     * always; with each, how many of its item the inventory holds besides.
     */
    public static void tickDropWatch() {
        org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack cur = Inv.cursor();
        boolean live = Inv.containerOpen() || com.zenith.Globals.INVENTORY.hasActiveRequest();
        String menu = null;
        List<DropWatch.Risk> inputs = List.of();
        com.zenith.cache.data.inventory.Container c = Inv.openContainer();
        if (c != null) {
            var type = c.getType();
            if (type == org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType.CARTOGRAPHY) {
                menu = "in the cartography table";
            } else if (type == org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType.ANVIL) {
                menu = "in the anvil";
            }
            if (menu != null) {
                inputs = new ArrayList<>(2);
                for (int i = KeySwaps.INPUT; i <= KeySwaps.ADDITION; i++) {
                    var s = c.getItemStack(i);
                    if (!Inv.empty(s)) inputs.add(new DropWatch.Risk(Inv.id(s), s.getAmount(), menu));
                }
            }
        }
        boolean held = !Inv.empty(cur);
        DropWatch.SESSION.look(DropWatch.risks(held ? Inv.id(cur) : null, held ? cur.getAmount() : 0, live, menu, inputs),
            id -> Inv.count(Inv.is(id)), Walker.feet(), System.currentTimeMillis());
    }

    /** ClientDisconnectEvent: what the disconnect may have dropped waits for the reconnect ({@link DropWatch}). Any thread. */
    public static void connectionLost() {
        DropWatch.SESSION.disconnected(System.currentTimeMillis());
    }

    @Override
    public List<io.github.digitalisp.mapart.bot.DisconnectDrop> takeDisconnectDrops() {
        return DropWatch.SESSION.take();
    }

    @Override
    public BotTask recoverDrops(List<io.github.digitalisp.mapart.bot.DisconnectDrop> drops, double radius,
                                Consumer<List<io.github.digitalisp.mapart.bot.DisconnectDrop.Outcome>> result) {
        return new DropRecoveryTask(this, drops, radius, result);
    }

    @Override
    public ZenithWorldView world() {
        return world;
    }

    @Override
    public ZenithInventoryView inventory() {
        return inventory;
    }

    @Override
    public ZenithMapView maps() {
        return maps;
    }

    @Override
    public BotTask walkTo(Pos target, int range) {
        return new WalkTask(this, target, range);
    }

    @Override
    public BotTask walkOnFoot(Pos target, int range) {
        return new FootRouteTask(this, target, range);
    }

    @Override
    public BotTask scan(Pos container, Consumer<ContainerSnapshot> result) {
        return ContainerTransferTask.scan(this, container, result);
    }

    @Override
    public BotTask scan(Pos container, @Nullable Pos standAt, Consumer<ContainerSnapshot> result) {
        ContainerTransferTask t = ContainerTransferTask.scan(this, container, result);
        t.standAt(standAt);
        return t;
    }

    @Override
    public BotTask withdraw(Pos container, Map<String, Integer> wanted, Consumer<TransferResult> result) {
        return ContainerTransferTask.withdraw(this, container, wanted, result);
    }

    @Override
    public BotTask withdraw(Pos container, @Nullable Pos standAt, Map<String, Integer> wanted,
                            Consumer<TransferResult> result) {
        ContainerTransferTask t = ContainerTransferTask.withdraw(this, container, wanted, result);
        t.standAt(standAt);
        return t;
    }

    @Override
    public BotTask withdraw(Pos container, @Nullable Pos standAt, Map<String, Integer> wanted, WithdrawMode mode,
                            Consumer<TransferResult> result) {
        ContainerTransferTask t = ContainerTransferTask.withdraw(this, container, wanted, mode, result);
        t.standAt(standAt);
        return t;
    }

    @Override
    public BotTask planVisits(List<Pos> containers, Consumer<List<ContainerVisit>> result) {
        return new PlanVisitsTask(this, containers, result);
    }

    @Override
    public BotTask planVisits(List<Pos> containers, @Nullable Pos then, Consumer<List<ContainerVisit>> result) {
        return new PlanVisitsTask(this, containers, then, result);
    }

    @Override
    public BotTask deposit(Pos container, Predicate<String> which, Map<String, Integer> keep,
                           Consumer<TransferResult> result) {
        return ContainerTransferTask.deposit(this, container, which, keep, result);
    }

    @Override
    public BotTask depositMap(Pos container, int mapId, Consumer<TransferResult> result) {
        return ContainerTransferTask.depositMap(this, container, mapId, result);
    }

    @Override
    public BotTask returnWorkingItems(Pos container, Map<String, Integer> keep, Consumer<TransferResult> result) {
        return ContainerTransferTask.returnWorking(this, container, keep, result);
    }

    @Override
    public BotTask dropItems(Pos standAt, Pos lookAt, Predicate<String> which, Map<String, Integer> keep,
                             Consumer<Map<String, Integer>> dropped) {
        return new DropItemsTask(this, standAt, lookAt, which, keep, dropped);
    }

    @Override
    public BotTask throwBack(Pos standAt, Pos target, Predicate<String> which, Map<String, Integer> keep,
                             Consumer<Map<String, Integer>> dropped) {
        return new DropItemsTask(this, standAt, target, which, keep, dropped, 1);
    }

    @Override
    public BotTask standForItems(@Nullable Pos prime, Pos standAt, Map<String, Integer> want,
                                 int stallTicks, int maxTicks, int minFree,
                                 Consumer<io.github.digitalisp.mapart.bot.PickupResult> result) {
        return new PickupStandTask(this, prime, standAt, want, stallTicks, maxTicks, minFree, result);
    }

    @Override
    public BotTask placeLane(Lane lane, PlacementListener listener) {
        return placeLane(lane, listener, Set.of());
    }

    @Override
    public BotTask placeLane(Lane lane, PlacementListener listener, Set<Pos> avoid) {
        return new PlaceLaneTask(this, lane, listener, avoid);
    }

    @Override
    public BotTask placeLane(Lane lane, PlacementListener listener, Set<Pos> avoid, List<Lane> load) {
        return new PlaceLaneTask(this, lane, listener, avoid, load);
    }

    @Override
    public BotTask placeLane(Lane lane, PlacementListener listener, Set<Pos> avoid, @Nullable List<Lane> load,
                             java.util.function.Function<Pos, io.github.digitalisp.mapart.model.@Nullable Placement> art) {
        return new PlaceLaneTask(this, lane, listener, avoid, load == null ? List.of() : load, art);
    }

    @Override
    public BotTask placeRepairLane(Lane lane, PlacementListener listener, Set<Pos> avoid, List<Lane> load,
                                   java.util.function.Function<Pos, io.github.digitalisp.mapart.model.@Nullable Placement> art) {
        return new PlaceLaneTask(this, lane, listener, avoid, load, art, true);
    }

    /**
     * A small server setback (the teleport guard's judgement), at {@code nowMillis}: the silent-look monitor's input
     * ({@link SilentLookGuard#SESSION}). Any thread (the guard sees setbacks on the Netty thread). Logs a WARN once,
     * when this setback turns silent looks off for the run.
     */
    public static void noteSetback(long nowMillis) {
        SilentLookGuard g = SilentLookGuard.SESSION;
        if (g.setback(nowMillis)) ZenithTask.LOG.warn("Silent looks off for this run: " + g.reason());
    }

    /** Once, when silent looks were just turned off for this run: the alert text; else null. */
    public static @Nullable String silentLookNotice() {
        return SilentLookGuard.SESSION.notice();
    }

    @Override
    public BotTask escape(EscapeRequest request) {
        return new EscapeTask(this, request);
    }

    @Override
    public BotTask rescueFall(FallRescue request) {
        return new FallRescueTask(this, request);
    }

    @Override
    public io.github.digitalisp.mapart.bot.@Nullable BoxedInDrop takeBoxedInDrop() {
        io.github.digitalisp.mapart.bot.BoxedInDrop d = Walker.boxedDrop;
        if (d == null) return null;
        Walker.boxedDrop = null;
        return d.at().equals(Walker.feet()) ? d : null;
    }

    @Override
    public BotTask retreat(List<WorldView.Mob> threats) {
        Pos at = StandSpots.feetCell(BOT.getX(), BOT.getY(), BOT.getZ());
        List<double[]> xz = new ArrayList<>(threats.size());
        for (WorldView.Mob m : threats) xz.add(new double[]{m.x(), m.z()});
        Pos to = TrackBack.retreatCell(Walker.TRACK.newestFirst(), at, xz);
        if (to == null) {
            ZenithTask.LOG.warn("retreat from " + threats.size() + " mob(s): no track cell further away; leaving " + at
                + " for open floor nearby");
            return escape(new EscapeRequest(BOT.getX(), BOT.getY(), BOT.getZ(), 3, Set.of(at)));
        }
        ZenithTask.LOG.warn("retreat from " + threats.size() + " mob(s): walking back along the track from " + at + " to "
            + to);
        return new WalkTask(this, to, 0).noFlight();
    }

    @Override
    public BotTask resync(Pos at) {
        return new ResyncTask(this, at);
    }

    @Override
    public BotTask breakBlocks(List<Pos> targets, Consumer<Pos> broken) {
        return new BreakBlocksTask(this, targets, broken);
    }

    @Override
    public BotTask breakBlocks(List<Pos> targets, Consumer<Pos> broken,
                               java.util.function.Supplier<@Nullable String> pause) {
        return new BreakBlocksTask(this, targets, broken, pause, true);
    }

    @Override
    public BotTask breakBlocks(List<Pos> targets, Consumer<Pos> broken,
                               java.util.function.@Nullable Supplier<@Nullable String> pause, boolean artCleared) {
        return new BreakBlocksTask(this, targets, broken, pause, artCleared);
    }

    @Override
    public BotTask placeBlock(Pos pos, String itemId) {
        return new PlaceBlockTask(this, pos, itemId);
    }

    @Override
    public BotTask useBlock(Pos pos, Pos standAt) {
        return new UseBlockTask(this, pos, standAt);
    }

    @Override
    public BotTask pressButton(Pos pos, @Nullable Pos standAt) {
        return UseBlockTask.pressButton(this, pos, standAt);
    }

    @Override
    public BotTask boardCart(Pos rail, Pos standAt, Consumer<Integer> cartId) {
        return new BoardCartTask(this, rail, standAt, cartId);
    }

    @Override
    public BotTask placeCart(Pos rail, Pos standAt, Consumer<Boolean> placed) {
        return BoardCartTask.placeOnly(this, rail, standAt, placed);
    }

    @Override
    public BotTask boardOwnCart(Pos rail, Pos standAt, Consumer<Integer> cartId) {
        return new BoardCartTask(this, rail, standAt, cartId, false);
    }

    @Override
    public BotTask preloadRide(@Nullable Pos button, Pos end, @Nullable Float pushYaw,
                               io.github.digitalisp.mapart.bot.RideWatch watch, Runnable pressed) {
        return new RideTask(this, button, end, null, pushYaw, watch, pressed);
    }

    @Override
    public BotTask ride(@Nullable Pos button, Pos brake, int mapId, float pushYaw, io.github.digitalisp.mapart.bot.RideWatch watch,
                        Runnable pressed) {
        return new RideTask(this, button, brake, mapId, pushYaw, watch, pressed);
    }

    @Override
    public BotTask ride(@Nullable Pos button, Pos brake, int mapId, float pushYaw, io.github.digitalisp.mapart.bot.RideWatch watch,
                        Runnable pressed, RideOptions opts) {
        return new RideTask(this, button, brake, mapId, pushYaw, watch, pressed, opts);
    }

    @Override
    public BotTask holdMapExactly(Pos standAt, int mapId, int ticks) {
        return MapTask.holdExactly(this, standAt, mapId, ticks);
    }

    @Override
    public BotTask leaveCart() {
        return new LeaveCartTask(this);
    }

    @Override
    public BotTask breakCart(Pos near) {
        return new BreakCartTask(this, near);
    }

    @Override
    public BotTask createMap(Pos standAt, int holdTicks, Consumer<Integer> mapId) {
        return MapTask.create(this, standAt, holdTicks, mapId);
    }

    @Override
    public BotTask holdMap(Pos standAt, int mapId, int ticks) {
        return MapTask.hold(this, standAt, mapId, ticks);
    }

    @Override
    public BotTask createMap(Pos standAt, int holdTicks, Consumer<Integer> mapId, io.github.digitalisp.mapart.bot.MapHold hold) {
        return MapTask.create(this, standAt, holdTicks, mapId, hold);
    }

    @Override
    public BotTask holdMap(Pos standAt, int mapId, int ticks, io.github.digitalisp.mapart.bot.MapHold hold) {
        return MapTask.hold(this, standAt, mapId, ticks, hold);
    }

    @Override
    public BotTask lockMap(Pos cartographyTable, int mapId, Consumer<Integer> lockedMapId) {
        return new LockMapTask(this, cartographyTable, mapId, lockedMapId);
    }

    @Override
    public BotTask renameMap(Pos anvil, int mapId, String name) {
        return new RenameMapTask(this, anvil, mapId, name);
    }

    @Override
    public BotTask throwXp(int bottles) {
        return new ThrowXpTask(this, bottles);
    }

    @Override
    public BotTask mendTool(String toolId, int bottles, double targetFraction) {
        return ThrowXpTask.mend(this, toolId, bottles, targetFraction);
    }

    @Override
    public BotTask exchangeTool(Pos chest, String kind, int floor, Predicate<String> returnable,
                               Consumer<TransferResult> result) {
        return new ToolExchangeTask(this, chest, kind, floor, returnable, result);
    }

    @Override
    public BotTask exchangeElytra(Pos chest, int floor, Consumer<TransferResult> result) {
        return new ToolExchangeTask(this, chest, ToolExchangeTask.ELYTRA, floor, id -> true, result);
    }

    @Override
    public BotTask equip(String itemId) {
        return new EquipTask(this, itemId);
    }

    @Override
    public BotTask unequipBoots() {
        return new UnequipBootsTask(this);
    }

    @Override
    public BotTask unequipArmor(int armorSlot) {
        return new UnequipBootsTask(this, armorSlot);
    }

    @Override
    public BotTask equip(String itemId, int fromSlot) {
        return new EquipTask(this, itemId, fromSlot);
    }

    @Override
    public BotTask waitTicks(int ticks) {
        return new WaitTask(this, ticks);
    }

    @Override
    public BotTask hopDown(Pos landing, int maxDrop) {
        return new HopDownTask(this, landing, maxDrop);
    }

    @Override
    public BotTask fight(io.github.digitalisp.mapart.bot.FightArea area, int weaponFloor, int quietTicks) {
        return new CombatTask(this, area, weaponFloor, quietTicks, m -> { }, io.github.digitalisp.mapart.bot.MobHits.NONE);
    }

    @Override
    public BotTask fight(io.github.digitalisp.mapart.bot.FightArea area, int weaponFloor, int quietTicks,
                         Consumer<List<WorldView.Mob>> unreachable, io.github.digitalisp.mapart.bot.MobHits hits) {
        return new CombatTask(this, area, weaponFloor, quietTicks, unreachable, hits);
    }

    @Override
    public BotTask eat(List<String> foods) {
        return new EatTask(this, foods);
    }

    @Override
    public BotTask keepAlive(float yawDelta) {
        return new KeepAliveTask(this, yawDelta);
    }

    /**
     * A container menu is open, or Baritone walks (a path someone else asked for, e.g. a ZenithProxy command while
     * paused): no keep-alive then. The double-break branch's delayed block still on the server belongs here as well.
     */
    @Override
    public boolean isBusy() {
        // a delayed block of a cancelled double break the server may still break (PendingDelayed): no keep-alive
        return Inv.containerOpen() || BARITONE.isActive()
            || PendingDelayed.SESSION.pending(System.currentTimeMillis(),
                p -> world().isLoaded(p) && world().block(p).isAir());
    }

    @Override
    public @Nullable String edgeGuardTrouble() {
        return EdgeApproach.trouble();
    }

    /** Block ids never broken: site.protectedBlocks, the machinery defaults and every platform floor block. */
    Set<String> protectedIds() {
        return SiteRules.protectedIds(config.site);
    }

    /**
     * The configured no-go zones ({@code site.noGoZones}) without the run-time {@code site.openings} (a
     * colour-suppression job's art volumes and access boxes), read afresh for each task.
     */
    NoGoZones noGoZones() {
        NoGoZones z = NoGoZones.of(config.site.noGoZones, config.site.openings, config.site.hazards);
        // near a colour-suppression platform: no long drops, not even into water (docs/COLOR_SUPPRESSION.md)
        return config.site.openings.isEmpty() ? z
            : z.withMaxDrop(com.zenith.Globals.CONFIG.client.extra.pathfinder.maxFallHeightNoWater);
    }

    /**
     * Walk budget plus a minute for opening and clicking, plus the walks to further stand spots when the first ones
     * fail ({@link Approach#MAX_SPOTS}).
     */
    long containerTimeout(Pos pos) {
        return WalkTask.walkTimeout(pos) + 1800 + 400L * (Approach.MAX_SPOTS - 1);
    }

    /**
     * Feet cell from which the block at {@code pos} (a container: either half) was last used, this session or (with
     * {@code build.standSpotMemory}) before a restart, or null. Client tick thread.
     */
    @Nullable Pos standSpot(Pos pos) {
        return standSpots.good(canonicalContainerPos(pos));
    }

    /** Remembers the feet cell the block at {@code pos} was used from (tried first next time). */
    void rememberStandSpot(Pos pos, Pos feet) {
        standSpots.opened(canonicalContainerPos(pos), feet);
    }

    /**
     * Cells from which no face centre of the container at {@code pos} was in sight after all this session (with
     * {@code build.standSpotMemory}; else none): neither the approach nor a restock sweep stands there for it again.
     */
    Set<Pos> failedStandSpots(Pos pos) {
        return config.build.standSpotMemory ? standSpots.failed(canonicalContainerPos(pos)) : Set.of();
    }

    /** The approach to the container at {@code pos} found no face centre in sight from {@code cells} after all. */
    void missedStandSpots(Pos pos, List<Pos> cells) {
        if (cells.isEmpty() || !config.build.standSpotMemory) return;
        Pos key = canonicalContainerPos(pos);
        for (Pos c : cells) standSpots.missed(key, c);
    }

    /**
     * Blocks that open the container at {@code pos}: the position itself and, for a double chest, its other half
     * (the stock index keys a double chest by one half, which may be hidden behind the other one).
     */
    List<Pos> containerHalves(Pos pos) {
        List<Pos> out = new java.util.ArrayList<>(List.of(pos));
        if (!world.isLoaded(pos)) return out;
        BlockSpec spec = world.block(pos);
        if (!spec.id().equals("minecraft:chest") && !spec.id().equals("minecraft:trapped_chest")) return out;
        int[] d = Aim.chestNeighbour(spec.props().get("facing"), spec.props().get("type"));
        if (d == null) return out;
        Pos other = pos.offset(d[0], 0, d[1]);
        if (world.block(other).id().equals(spec.id())) out.add(other);
        return out;
    }

    /**
     * Stable position for a container: for a double chest the half with the smaller x (then z), so both halves map
     * to the same StockIndex key. Other blocks: the position itself.
     */
    Pos canonicalContainerPos(Pos pos) {
        if (!world.isLoaded(pos)) return pos;
        BlockSpec spec = world.block(pos);
        if (!spec.id().equals("minecraft:chest") && !spec.id().equals("minecraft:trapped_chest")) return pos;
        int[] d = Aim.chestNeighbour(spec.props().get("facing"), spec.props().get("type"));
        if (d == null) return pos;
        Pos other = pos.offset(d[0], 0, d[1]);
        if (!world.block(other).id().equals(spec.id())) return pos;
        if (other.x() < pos.x() || (other.x() == pos.x() && other.z() < pos.z())) return other;
        return pos;
    }
}
