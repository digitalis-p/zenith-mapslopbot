package io.github.digitalisp.mapart.engine;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.chat.SystemChatEvent;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.client.ClientDeathEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.queue.QueueStartEvent;
import com.zenith.event.server.ServerRestartingEvent;
import com.zenith.module.api.Module;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import io.netty.util.concurrent.EventExecutor;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundDamageEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateAttributesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundBlockChangedAckPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetPlayerInventoryPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetCursorItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundMapItemDataPacket;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.Services;
import io.github.digitalisp.mapart.bot.Hit;
import io.github.digitalisp.mapart.bot.impl.BlockAcks;
import io.github.digitalisp.mapart.bot.impl.DamageEvents;
import io.github.digitalisp.mapart.bot.impl.PendingDelayed;
import io.github.digitalisp.mapart.bot.impl.BlockEchoes;
import io.github.digitalisp.mapart.bot.impl.ClickAnswers;
import io.github.digitalisp.mapart.bot.impl.InventoryEchoes;
import io.github.digitalisp.mapart.bot.impl.MapStore;
import io.github.digitalisp.mapart.bot.impl.ZenithBotActions;
import io.github.digitalisp.mapart.bot.impl.SprintLegs;
import io.github.digitalisp.mapart.bot.impl.ZenithSprintSync;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.PlatformGeometry;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.EXECUTOR;
import static com.zenith.Globals.saveConfigAsync;

/**
 * Thin ZenithProxy adapter for {@link BuildEngine}: enabled by {@code mapart.enabled}, forwards the bot
 * tick lifecycle (SimpleEventBus matches exact event classes, so each nested event is subscribed).
 * ClientBotTick only fires while no player controls the account, which gives "pause while a player is
 * in control" for free. Also feeds the {@link TeleportGuard} from server teleport/respawn packets and deaths,
 * {@link BlockAcks} from block-changed-acks and {@link BlockEchoes} from block updates (the placement classifier's
 * proof that the server handled a click, or dropped it), and the plugin's own map store ({@link MapStore#SESSION}) from
 * every map data packet: ZenithProxy's map cache skips new map ids once it is full, so CHECK_MAP must not depend on it.
 * Damage events on the bot go to the engine as {@link Hit}s (resolved on the client loop, queued by
 * {@link BuildEngine#onHit}): self-defence knows which mob hurt the bot, and that a fall or a wall did not.
 *
 * <p>Threads: teleport and respawn packets arrive on the Netty channel thread, ClientDeathEvent on the async event
 * bus. The guard's halts, back-offs and stuck pauses, and every Baritone stop, run on the client tick thread
 * ({@link TickThread}): a channel thread waiting for the engine lock deadlocks with a tick waiting for a send, and
 * Baritone is not thread-safe. The {@code !map stop} kill switch runs its {@code emergencyStop} there too
 * ({@link #onTickThread}). Only the guard's per-setback note to the silent-look monitor runs inline on the channel
 * thread ({@link ZenithBotActions#noteSetback}: it must never block or take the engine lock).
 *
 * <p>Baritone is halted with {@link #haltBaritone()} on every stop (safety stop, {@code !map stop}) and when bot ticks
 * stop (a player takes control, disconnect): ZenithProxy's own handler only cancels Baritone's processes and keeps a
 * path segment that is in the middle of a movement, which would carry on for up to one movement once bot ticks start
 * again.
 */
public class MapArtModule extends Module {
    private static final Set<String> PLATFORM_KINDS = Set.of("MOSS", "SOUL_SAND");

    /** Priority of the short-turn handler: after Baritone (0) and InventoryManager (-5000), before InputManager (-10000). */
    static final int WALK_YAW_PRIORITY = -9000;
    /** Ahead of Baritone's tick (default priority 0), which reads allowSprint. */
    static final int BEFORE_BARITONE_PRIORITY = 100;
    /** Edge stops on Baritone's input: after the short turn (-9000), before InputManager (-10000). */
    static final int EDGE_SNEAK_PRIORITY = -9500;
    /** No stray glide: after every input request (edge stops -9500 included), before InputManager (-10000). */
    static final int GLIDE_GUARD_PRIORITY = -9900;
    /** What a disconnect would drop: after InventoryManager (-5000), so the cursor is as this tick's click left it. */
    static final int DROP_WATCH_PRIORITY = -6000;

    private final MapArtConfig config;
    private final BuildEngine engine;
    private final TeleportGuard guard;

    public MapArtModule(MapArtConfig config, BuildEngine engine) {
        this.config = config;
        this.engine = engine;
        // halt = emergencyStop on the tick thread; it persists paused + reason through the config saver. Every small
        // setback also feeds the silent-look monitor, inline on the channel thread
        this.guard = new TeleportGuard(config.safety, this::platformBases, System::currentTimeMillis,
            TickThread.guardSink(engine, MapArtModule::clientLoop, EXECUTOR,
                (title, body) -> Services.NOTIFIER.detail(title, body), // "Frequent setbacks": verbose only
                d -> {
                    io.github.digitalisp.mapart.bot.impl.SetbackClock.SESSION.setback(System.nanoTime()); // the latch's excuse
                    ZenithBotActions.noteSetback(System.currentTimeMillis());
                    engine.noteSetback(); // the tile summary's count
                }));
        engine.setHaltHook(TickThread.hook(MapArtModule::clientLoop, EXECUTOR, MapArtModule::haltBaritone));
        engine.setConfigSaver(() -> saveConfigAsync());
        // no health or starvation disconnect here: that is ZenithProxy's AutoDisconnect, configured by the owner
    }

    /** The client tick event loop, or null without a client session. */
    private static @Nullable EventExecutor clientLoop() {
        var client = Proxy.getInstance().getClient();
        return client == null ? null : client.getClientEventLoop();
    }

    /**
     * Runs {@code r} on the client tick thread ({@link TickThread#run}): queued after the current tick, inline without a
     * client. The loop keeps running tasks while bot ticks are stopped (a player in control), so this still acts then.
     * An {@link java.util.concurrent.Executor} for the kill switch's {@code emergencyStop}.
     */
    public static void onTickThread(Runnable r) {
        TickThread.run(clientLoop(), EXECUTOR, r);
    }

    /**
     * Wires a module from {@link Services} (call after CONFIG, STORE, COORD, CATALOG, COLORS, RENDERER, PLANNER,
     * RESTOCK, ACTIONS and NOTIFIER are set). Also sets {@code Services.ENGINE}.
     */
    public static MapArtModule fromServices() {
        BuildEngine engine = new BuildEngine(Services.CONFIG, Services.STORE, Services.ACTIONS, Services.PLANNER,
            Services.RESTOCK, Services.CATALOG, Services.RENDERER, Services.COLORS, Services.NOTIFIER,
            Clock.systemUTC(), EXECUTOR, new ZenithModuleSwitch(), Services.LOG, Services.COORD);
        Services.ENGINE = engine;
        // the config's static teammates list, read live
        io.github.digitalisp.mapart.coord.Teammates.shared().configured(
            () -> Services.CONFIG == null ? java.util.List.of() : Services.CONFIG.teammates);
        return new MapArtModule(Services.CONFIG, engine);
    }

    public BuildEngine engine() {
        return engine;
    }

    @Override
    public boolean enabledSetting() {
        return config.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, e -> {
                var pc = CACHE.getPlayerCache();
                guard.rideMode(engine.ridePhase());
                guard.tick(pc.getX(), pc.getY(), pc.getZ(), inQueue());
                // ticks since the bot last sprinted: clicks wait out build.sprintQuietTicks
                ZenithBotActions.tickSprintWatch(config);
                // the bot's own track, walked back by a walk that finds itself boxed in (Walker "Boxed in")
                ZenithBotActions.tickTrack();
                // the sprint state and the sprinting speed modifier as a vanilla client keeps them, before the Bot moves
                ZenithSprintSync.tick((SprintLegs.any(config.build) || config.combat.creeperHitAndRun) && config.build.sprintSync);
                // airplace offhand recovery, before any task
                ZenithBotActions.GuardNews airplace = ZenithBotActions.tickAirPlaceGuard(config);
                if (airplace.airplaceOff() != null) Services.NOTIFIER.alert("Airplace off", airplace.airplaceOff());
                if (airplace.offhand() != null) Services.NOTIFIER.alert("Offhand without its totem", airplace.offhand());
                if (airplace.notice() != null) Services.NOTIFIER.detail("Airplace on", airplace.notice());
                // silent looks switched off for this run because setbacks followed them ("Setback escape")
                String silent = ZenithBotActions.silentLookNotice();
                if (silent != null) Services.NOTIFIER.info("Silent looks off", silent);
                engine.onBotTick();
                ZenithBotActions.tickFlight(); // an elytra flight left in the air by its walk lands (FlightLeg)
            }),
            // after Baritone's input request, before ZenithProxy's InputManager (-10000) applies it: Baritone's turns
            // the short way during the plugin's walks (build.walkShortTurns)
            of(ClientBotTick.class, WALK_YAW_PRIORITY, e -> ZenithBotActions.tickWalkYaw(config)),
            // the walk's one-sprint latch before Baritone reads allowSprint
            of(ClientBotTick.class, BEFORE_BARITONE_PRIORITY, e -> ZenithBotActions.beforeBaritone()),
            // the walk's edge stop on Baritone's keys of this tick: sneak near an edge, none after a stop
            of(ClientBotTick.class, EDGE_SNEAK_PRIORITY, e -> ZenithBotActions.tickEdgeSneak(config)),
            // a jump pressed in the air opens a worn elytra (vanilla): only an elytra flight's press does
            of(ClientBotTick.class, GLIDE_GUARD_PRIORITY, e -> ZenithBotActions.tickGlideGuard()),
            // the stacks a disconnect now would drop: the cursor's, a cartography table's or anvil's
            of(ClientBotTick.class, DROP_WATCH_PRIORITY, e -> ZenithBotActions.tickDropWatch()),
            of(ClientBotTick.Starting.class, e -> {
                guard.botTicksStarted();
                // no elytra take-off until the hold has passed and the gear is checked (a player's control just ended)
                io.github.digitalisp.mapart.bot.impl.TakeoffGuard.SESSION.botTicksStarted(System.currentTimeMillis());
                engine.onBotTickStarting();
            }),
            of(ClientBotTick.Stopped.class, e -> {
                guard.botTicksStopped();
                ZenithBotActions.airPlaceGuardStopped(false); // inline: Stopped is posted on the tick loop
                engine.onBotTickStopped();
                onTickThread(MapArtModule::haltBaritoneQuietly); // inline: Stopped is posted on the tick loop
            }),
            of(ClientDisconnectEvent.class, e -> {
                ZenithBotActions.connectionLost(); // what the server dropped: picked up after the reconnect
                BlockAcks.reset();
                BlockEchoes.reset();
                onTickThread(() -> ZenithBotActions.airPlaceGuardStopped(true));
                MapStore.SESSION.reset(); // map ids are per connection on 2b2t
                guard.botTicksStopped();
                engine.onDisconnect();
            }),
            of(ClientDeathEvent.class, e -> guard.death()),
            // 2b2t's restart warning: system chat only, never a player's
            // line (PublicChatEvent) or a whisper; ZenithProxy's own event reads the same warning from the action bar
            of(SystemChatEvent.class, e -> engine.onServerMessage(e.message())),
            of(ServerRestartingEvent.class, e -> engine.onServerMessage(e.message())),
            // a restart moves a bot that stays connected to the queue: the restart happened
            of(QueueStartEvent.class, e -> engine.onRequeued())
        );
    }

    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("mapart-teleport-guard")
            .setPriority(Integer.MAX_VALUE - 2)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                // the cart push of the colour-suppression ride: ZenithProxy never sends forward in a vehicle
                .outbound(org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket.class,
                    (packet, session) -> io.github.digitalisp.mapart.bot.impl.RideInput.rewrite(packet))
                // tools never break, whoever digs or attacks
                .outbound(org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket.class,
                    (packet, session) -> io.github.digitalisp.mapart.bot.impl.HeldItemGuard.dig(packet, config))
                .outbound(org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket.class,
                    (packet, session) -> io.github.digitalisp.mapart.bot.impl.HeldItemGuard.attack(packet, config))
                .inbound(ClientboundPlayerPositionPacket.class, (packet, session) -> {
                    ZenithSprintSync.teleport(); // ZenithProxy clears the sprint state; vanilla and Grim keep it
                    // a teleport or a setback: no elytra take-off for the hold (live 2026-10-25 08:33)
                    io.github.digitalisp.mapart.bot.impl.TakeoffGuard.SESSION.teleport(System.currentTimeMillis());
                    var rel = packet.getRelatives();
                    guard.teleport(rel.contains(PositionElement.X), packet.getX(),
                        rel.contains(PositionElement.Y), packet.getY(),
                        rel.contains(PositionElement.Z), packet.getZ(), inQueue());
                    return packet;
                })
                // the server's pulse: packets it sends every second or more often; none for 2.5 s is a silent (frozen)
                // server (live 2026-10-25 08:47: 37 s; the client simulated on, an elytra flight's boost with it)
                .inbound(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetTimePacket.class,
                    (packet, session) -> {
                        io.github.digitalisp.mapart.bot.impl.TakeoffGuard.SESSION.timeUpdate(System.currentTimeMillis());
                        return packet;
                    })
                .inbound(org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundKeepAlivePacket.class,
                    (packet, session) -> pulse(packet))
                .inbound(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket.class,
                    (packet, session) -> pulse(packet))
                .inbound(org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityMotionPacket.class,
                    (packet, session) -> pulse(packet))
                .inbound(ClientboundRespawnPacket.class, (packet, session) -> {
                    guard.respawn(inQueue());
                    return packet;
                })
                .inbound(ClientboundLoginPacket.class, (packet, session) -> {
                    BlockAcks.reset(); // a new connection: ZenithProxy's prediction counter starts again
                    PendingDelayed.SESSION.clear(); // and a new server player: no delayed block of ours on it
                    BlockEchoes.reset();
                    onClientLoop(InventoryEchoes.SESSION::reset); // behind the last connection's inventory reports
                    onClientLoop(ClickAnswers.SESSION::reset); // no click of the last connection waits for an answer
                    io.github.digitalisp.mapart.bot.impl.SetbackClock.SESSION.newConnection(); // a new Grim player: latch undone
                    // no elytra take-off until 10 world-time updates came (the TPS estimate, a freeze seen)
                    io.github.digitalisp.mapart.bot.impl.TakeoffGuard.SESSION.login(System.currentTimeMillis());
                    // new map ids (2b2t's queue -> main server transfer is a new login too); on the loop, so it runs
                    // before this connection's map packets and after any still queued from the last one
                    onClientLoop(MapStore.SESSION::reset);
                    return packet;
                })
                .inbound(ClientboundMapItemDataPacket.class, (packet, session) -> {
                    // the plugin's own copy: ZenithProxy's MapDataCache skips new ids past `debug maxCachedMaps`. On the
                    // client loop like ZenithProxy's MapDataHandler, behind the inventory updates sent before it (the
                    // store pins the maps the bot carries)
                    onClientLoop(() -> captureMap(packet));
                    return packet;
                })
                .inbound(ClientboundDamageEventPacket.class, (packet, session) -> {
                    // who hurt the bot: on the client loop, where the
                    // entity cache is kept; the engine queues the hit for its next tick (BuildEngine#onHit)
                    onClientLoop(() -> noteDamage(packet));
                    return packet;
                })
                .inbound(ClientboundUpdateAttributesPacket.class, (packet, session) -> {
                    // the server's sprinting speed modifier, as a vanilla client takes it (MC-69459) ...
                    ZenithSprintSync.attributes(packet);
                    return packet;
                })
                .inbound(ClientboundPingPacket.class, (packet, session) -> {
                    // ... counting from the answer to the ping ahead of it, as Grim counts it. ZenithProxy answers on the
                    // client loop in the task its handler queues right behind this mark (a second hop would land behind
                    // a tick queued meanwhile: one tick late, the live START race)
                    long ping = ZenithSprintSync.ping();
                    onClientLoop(() -> ZenithSprintSync.answered(ping));
                    return packet;
                })
                // what the server says the hotbar holds, for the hotbar move fence (InventoryEchoes, MoveFence): two hops
                // on the client loop, so each report lands behind ZenithProxy's own cache update for the same packet
                // whichever of the two handlers runs first
                .inbound(ClientboundContainerSetContentPacket.class, (packet, session) -> {
                    if (packet.getContainerId() == 0) onClientLoop(() -> onClientLoop(() -> InventoryEchoes.content(packet)));
                    // the answer to a container click (build.containerAnswerWaits, ClickAnswers): two hops as well, so
                    // a tick that sees it also sees the cache it updated
                    else if (config.build.containerAnswerWaits) onClientLoop(() -> onClientLoop(() -> ClickAnswers.content(packet)));
                    return packet;
                })
                .inbound(ClientboundContainerSetSlotPacket.class, (packet, session) -> {
                    if (packet.getContainerId() == 0) onClientLoop(() -> onClientLoop(() -> InventoryEchoes.slot(packet)));
                    else if (config.build.containerAnswerWaits) onClientLoop(() -> onClientLoop(() -> ClickAnswers.slot(packet)));
                    return packet;
                })
                .inbound(ClientboundSetCursorItemPacket.class, (packet, session) -> {
                    if (config.build.containerAnswerWaits) onClientLoop(() -> onClientLoop(() -> ClickAnswers.cursor(packet)));
                    return packet;
                })
                .inbound(ClientboundSetPlayerInventoryPacket.class, (packet, session) -> {
                    onClientLoop(() -> onClientLoop(() -> InventoryEchoes.playerInventory(packet)));
                    return packet;
                })
                .inbound(ClientboundBlockUpdatePacket.class, (packet, session) -> {
                    // inline on the connection's thread: recorded before the ack that follows it on the wire is
                    // queued for the tick loop below (vanilla answers a handled click with two of these, then the ack)
                    var b = packet.getEntry();
                    BlockEchoes.record(b.getX(), b.getY(), b.getZ());
                    return packet;
                })
                .inbound(ClientboundBlockChangedAckPacket.class, (packet, session) -> {
                    // on the client tick loop, queued behind the block updates that preceded the ack on the wire
                    int seq = packet.getSequence();
                    EventExecutor loop = clientLoop();
                    try {
                        if (loop != null) loop.execute(() -> BlockAcks.record(seq));
                        else BlockAcks.record(seq);
                    } catch (java.util.concurrent.RejectedExecutionException ex) {
                        BlockAcks.record(seq);
                    }
                    return packet;
                })
                .build())
            .build();
    }

    @Override
    public void onEnable() {
        MapStore.SESSION.reset(); // packets while the module was off were missed: those maps come from ZenithProxy's cache
    }

    @Override
    public void onDisable() {
        MapStore.SESSION.reset(); // no capture while off: the stored maps would go stale
        guard.botTicksStopped();
        engine.onBotTickStopped();
        onTickThread(MapArtModule::haltBaritoneQuietly);
    }

    /** Runs {@code r} on the client event loop after what is queued there; inline without a loop or when it refuses. */
    private static void onClientLoop(Runnable r) {
        EventExecutor loop = clientLoop();
        if (loop != null) {
            try {
                loop.execute(r);
                return;
            } catch (java.util.concurrent.RejectedExecutionException ex) {
                // the loop is shutting down: run it here
            }
        }
        r.run();
    }

    /** A damage event on the bot to the engine ({@link DamageEvents}); damage to other entities is ignored. */
    private void noteDamage(ClientboundDamageEventPacket packet) {
        try {
            Hit hit = DamageEvents.onBot(packet);
            if (hit != null) engine.onHit(hit);
        } catch (RuntimeException ex) {
            if (Services.LOG != null) Services.LOG.warn("Map art: damage event not read: {}", ex.toString());
        }
    }

    /** The server's pulse for the elytra take-off hold ({@code TakeoffGuard}); the packet goes on unchanged. */
    private static <P> P pulse(P packet) {
        io.github.digitalisp.mapart.bot.impl.TakeoffGuard.SESSION.pulse(System.currentTimeMillis());
        return packet;
    }

    private static void captureMap(ClientboundMapItemDataPacket packet) {
        try {
            MapStore.SESSION.accept(packet);
        } catch (RuntimeException ex) {
            if (Services.LOG != null)
                Services.LOG.warn("Map art: map packet for map {} not stored: {}", packet.getMapId(), ex.toString());
        }
    }

    /**
     * Stops Baritone completely: {@code forceCancel} = {@code stop()} (cancel every process) plus dropping the current
     * path segment and its key presses even when Baritone calls it unsafe to cancel (mid-movement), plus any path
     * calculation. Call it on the client tick thread only ({@link #onTickThread}): Baritone is not thread-safe.
     */
    public static void haltBaritone() {
        BARITONE.getPathingBehavior().forceCancel();
    }

    private static void haltBaritoneQuietly() {
        try {
            haltBaritone();
        } catch (RuntimeException ex) {
            if (Services.LOG != null) Services.LOG.warn("Map art: Baritone halt failed: {}", ex.toString());
        }
    }

    private List<PlatformGeometry> platformBases() {
        List<PlatformGeometry> out = new ArrayList<>();
        for (MapArtConfig.PlatformCfg p : config.site.platforms)
            if (p.enabled && PLATFORM_KINDS.contains(p.kind)) out.add(p.geometry());
        return out;
    }

    private static boolean inQueue() {
        return Proxy.getInstance().isInQueue();
    }
}
