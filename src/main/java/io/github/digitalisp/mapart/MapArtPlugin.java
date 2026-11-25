package io.github.digitalisp.mapart;

import com.zenith.Proxy;
import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import io.github.digitalisp.mapart.bot.impl.ZenithBotActions;
import io.github.digitalisp.mapart.catalog.ZenithBlockCatalog;
import io.github.digitalisp.mapart.command.MapArtCommand;
import io.github.digitalisp.mapart.command.QueueOps;
import io.github.digitalisp.mapart.command.SubmissionPipeline;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.coord.LocalCoordination;
import io.github.digitalisp.mapart.discord.DiscordCommands;
import io.github.digitalisp.mapart.discord.DiscordNotifier;
import io.github.digitalisp.mapart.discord.IntakeBot;
import io.github.digitalisp.mapart.discord.PresenceText;
import io.github.digitalisp.mapart.discord.PresenceUpdater;
import io.github.digitalisp.mapart.engine.CoordFormat;
import io.github.digitalisp.mapart.engine.MapArtModule;
import io.github.digitalisp.mapart.engine.ZenithMapCacheLimit;
import io.github.digitalisp.mapart.engine.ZenithServerLink;
import io.github.digitalisp.mapart.plan.DefaultSubmissionService;
import io.github.digitalisp.mapart.plan.ConfiguredRestockPlanner;
import io.github.digitalisp.mapart.plan.SerpentineBuildPlanner;
import io.github.digitalisp.mapart.render.PngRenderer;
import io.github.digitalisp.mapart.render.ZenithColorResolver;
import io.github.digitalisp.mapart.store.FileJobStore;

import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Supplier;

import static com.zenith.Globals.EXECUTOR;
import static com.zenith.Globals.MODULE;
import static com.zenith.Globals.saveConfigAsync;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Autonomous map art builder: Discord queue -> build -> lock -> store",
    url = "https://github.com/digitalis-p/zenith-mapslopbot",
    authors = {"digitalis-p"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class MapArtPlugin implements ZenithProxyPlugin {
    public static ComponentLogger LOG;

    /**
     * Wires everything once: config (plugins/config/mapart.json), the services, the Discord client and notifier,
     * the engine module and the {@code mapart} command. Nothing here touches the game connection; the module starts
     * working on the first bot tick when {@code mapart.enabled} is on.
     */
    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        MapArtConfig config = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, MapArtConfig.class);
        Services.LOG = LOG;
        Services.CONFIG = config;
        Services.STORE = new FileJobStore(Path.of(config.dataDir), null, Clock.systemUTC(),
            problem -> LOG.warn("Map art job store: {}", problem));
        // the local queue, claims granted at once, stock.json
        Services.COORD = new LocalCoordination(Services.STORE);
        Services.CATALOG = new ZenithBlockCatalog();
        Services.COLORS = new ZenithColorResolver();
        Services.RENDERER = new PngRenderer();
        Services.SUBMISSIONS = new DefaultSubmissionService();
        Services.PLANNER = new SerpentineBuildPlanner(() -> config.build.laneEvenWidths);
        Services.RESTOCK = new ConfiguredRestockPlanner(() -> config.build.restockPlanner,
            () -> config.build.restockCarriedGuard);
        Services.ACTIONS = new ZenithBotActions(config, Services.CATALOG);
        // ZenithProxy's map cache skips new map ids once full (128 by default; the base's framed maps fill it)
        ZenithMapCacheLimit.applyToZenith(config.map.raiseZenithMapCache, LOG::info);

        // kill switch: emergencyStop on the client tick thread; `stop disconnect` disconnects like ZenithProxy's
        // `disconnect` (no auto-reconnect)
        QueueOps ops = new QueueOps(Services.STORE, () -> Services.ENGINE, System::currentTimeMillis,
            new ZenithServerLink(), MapArtModule::onTickThread);
        SubmissionPipeline pipeline = new SubmissionPipeline(Services.SUBMISSIONS, Services.CATALOG, config,
            Services.STORE, Services.RENDERER, Services.COLORS, () -> Services.ENGINE, LOG);
        // one view of discord.coordinates for every Discord path (warns once if RELATIVE has no platform)
        Supplier<CoordFormat> coords = CoordFormat.live(config, LOG);
        // `!map verbose on|off` saves the config like the engine's pause does
        DiscordCommands commands = new DiscordCommands(ops, config.discord, () -> saveConfigAsync(), coords, LOG);
        IntakeBot discord = new IntakeBot(config, pipeline, commands, coords, EXECUTOR, LOG,
            () -> Services.COORD.discordIntake());
        DiscordNotifier notifier = new DiscordNotifier(discord.sink(), config.discord, coords, LOG,
            System::currentTimeMillis);
        notifier.start(EXECUTOR);
        Services.NOTIFIER = notifier; // logs every event; posts to Discord only while the bot is connected
        new PresenceUpdater(discord.presence(), () -> presenceInput(config), config.discord, LOG,
            System::currentTimeMillis).start(EXECUTOR); // the bot's custom status: step, map, progress

        pluginAPI.registerModule(MapArtModule.fromServices());
        pluginAPI.registerCommand(new MapArtCommand(config, ops, pipeline, discord,
            () -> MODULE.get(MapArtModule.class).syncEnabledFromConfig(), EXECUTOR, () -> Services.NOTIFIER, coords,
            LOG));
        discord.start();
        BuildInfo build = BuildInfo.current();
        LOG.info("Map art plugin {} loaded (built {}; builder {}, data in {})", build.label(), build.builtText(),
            config.enabled ? "on" : "off", config.dataDir);
    }

    /** Engine snapshot, job list and where the account is, for the Discord presence (executor thread). */
    private static PresenceText.Input presenceInput(MapArtConfig config) {
        Proxy proxy = Proxy.getInstance();
        PresenceText.Link link = !proxy.isConnected() ? PresenceText.Link.OFFLINE
            : proxy.isInQueue() ? PresenceText.Link.IN_QUEUE
            : proxy.hasActivePlayer() ? PresenceText.Link.PLAYER_IN_CONTROL
            : PresenceText.Link.IN_GAME;
        int position = link == PresenceText.Link.IN_QUEUE ? proxy.getQueuePosition() : 0;
        var status = Services.ENGINE == null ? null : Services.ENGINE.status();
        long now = System.currentTimeMillis(); // the ride's progress is counted from its launch to now
        return PresenceText.input(status, Services.STORE.list(), config.enabled, link, position).at(now);
    }
}
