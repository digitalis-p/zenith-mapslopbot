package io.github.digitalisp.mapart.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSource;
import com.zenith.command.api.CommandUsage;
import com.zenith.command.api.DiscordCommandContext;
import com.zenith.command.api.DiscordCommandSource;
import com.zenith.discord.Embed;
import org.slf4j.Logger;
import io.github.digitalisp.mapart.command.SubmissionPipeline.Accepted;
import io.github.digitalisp.mapart.command.SubmissionPipeline.Rejected;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.discord.DiscordCommands;
import io.github.digitalisp.mapart.discord.IntakeBot;
import io.github.digitalisp.mapart.engine.CoordFormat;
import io.github.digitalisp.mapart.engine.Notifier;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.NamedFile;
import io.github.digitalisp.mapart.stock.Unstocked;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;

/**
 * ZenithProxy command {@code mapart} (terminal, in-game, ZenithProxy's Discord). Same controls as
 * the {@code !map} channel commands plus builder on/off, local submission, Discord bot control and a
 * site check. Subcommands that change anything require the account owner
 * ({@link Command#validateAccountOwner}); status, queue, stock and site are open, and so is the kill switch
 * {@code stop [disconnect]} (aliases {@code kill}, {@code halt}): stopping must never need a permission. Replies to
 * ZenithProxy's Discord show coordinates per {@code discord.coordinates}; terminal and in-game replies keep real ones.
 */
public class MapArtCommand extends Command {
    private final MapArtConfig config;
    private final QueueOps ops;
    private final SubmissionPipeline pipeline;
    private final IntakeBot discord;
    private final Runnable syncEngineModule;
    private final Executor executor;
    private final Supplier<Notifier> notifier;
    private final Supplier<CoordFormat> coords;
    private final Logger log;
    private final Predicate<CommandContext> owner;

    /**
     * @param discord          the plugin's Discord client; null if not constructed
     * @param syncEngineModule called after {@code config.enabled} changes, e.g.
     *                         {@code () -> MODULE.get(MapArtModule.class).syncEnabledFromConfig()}
     * @param executor         background executor for {@code submit} (parsing, rendering, file I/O)
     * @param notifier         where local submission results are reported (normally {@code Services.NOTIFIER})
     * @param coords           how coordinates are shown in replies to ZenithProxy's Discord
     */
    public MapArtCommand(MapArtConfig config, QueueOps ops, SubmissionPipeline pipeline, IntakeBot discord,
                         Runnable syncEngineModule, Executor executor, Supplier<Notifier> notifier,
                         Supplier<CoordFormat> coords, Logger log) {
        this(config, ops, pipeline, discord, syncEngineModule, executor, notifier, coords, log,
            Command::validateAccountOwner);
    }

    /** {@code owner} gates the changing subcommands; tests pass a predicate that avoids ZenithProxy globals. */
    MapArtCommand(MapArtConfig config, QueueOps ops, SubmissionPipeline pipeline, IntakeBot discord,
                  Runnable syncEngineModule, Executor executor, Supplier<Notifier> notifier,
                  Supplier<CoordFormat> coords, Logger log, Predicate<CommandContext> owner) {
        this.owner = owner;
        this.config = config;
        this.ops = ops;
        this.pipeline = pipeline;
        this.discord = discord;
        this.syncEngineModule = syncEngineModule;
        this.executor = executor;
        this.notifier = notifier;
        this.coords = coords;
        this.log = log;
    }

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("mapart")
            .category(CommandCategory.MODULE)
            .description("""
                Autonomous flat map art builder. Uploads in the map art Discord channel, or `submit`, are queued, \
                built, locked, renamed and stored. Positions are 1-based (1 = next). Changing subcommands need \
                the account owner, except `stop`: it halts the bot at once (Baritone stopped, task cancelled, pause \
                saved) for anyone; `stop disconnect` also disconnects from the server without auto-reconnect.
                """)
            .usageLines(
                "stop [disconnect]  (halt NOW; also kill, halt; open to everyone)",
                "on/off",
                "status",
                "queue",
                "pause [reason]",
                "resume",
                "cancel <id>",
                "move <id> <position>",
                "rename <id> <name>",
                "retry <id>",
                "scan",
                "stock [filter]",
                "submit <path to .nbt/.zip or a directory>",
                "resubstitute <id> [apply]  (same-color swaps again on tiles with nothing placed; dry run without apply)",
                "discord on/off",
                "discord reload",
                "verbose [on/off]  (routine steps and progress cards in the map art channel too; default off)",
                "elytra [on/off]  (build.elytraLegs: restock legs fly, and the walk kinds switched on; off at once is the kill switch)",
                "elytra short on/off  (build.elytraShortLandings: land on a small spot at the goal, hover over it, over the end rods onto the clear stand)",
                "elytra hops on/off  (build.elytraLongHops: a goal not loaded yet: fly toward it in hops)",
                "elytra kittrip on/off  (build.elytraKitFarTrip: a color-suppression job goes to a far rocket chest by its platform's access path)",
                "site"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("mapart")
            .executes(c -> {
                return show(c.getSource(), statusReply());
            })
            .then(stopNode("stop"))
            .then(stopNode("kill"))
            .then(stopNode("halt"))
            .then(argument("toggle", toggle()).requires(owner).executes(c -> {
                return show(c.getSource(), setEnabled(getToggle(c, "toggle")));
            }))
            .then(literal("status").executes(c -> {
                return show(c.getSource(), statusReply());
            }))
            .then(literal("queue").executes(c -> {
                return show(c.getSource(), ops.queue());
            }))
            .then(literal("pause").requires(owner)
                .executes(c -> {
                    return show(c.getSource(), ops.pause("paused from " + c.getSource().getSource().name(), "mapart"));
                })
                .then(argument("reason", greedyString()).executes(c -> {
                    return show(c.getSource(), ops.pause(Fmt.truncate(getString(c, "reason"), 200), "mapart"));
                })))
            .then(literal("resume").requires(owner).executes(c -> {
                return show(c.getSource(), ops.resume());
            }))
            .then(literal("cancel").requires(owner)
                .then(argument("id", word()).executes(c -> {
                    return show(c.getSource(), ops.cancel(getString(c, "id")));
                })))
            .then(literal("move").requires(owner)
                .then(argument("id", word()).then(argument("position", integer(1)).executes(c -> {
                    return show(c.getSource(), ops.move(getString(c, "id"), getInteger(c, "position")));
                }))))
            .then(literal("rename").requires(owner)
                .then(argument("id", word()).then(argument("name", greedyString()).executes(c -> {
                    return show(c.getSource(), ops.rename(getString(c, "id"), getString(c, "name")));
                }))))
            .then(literal("retry").requires(owner)
                .then(argument("id", word()).executes(c -> {
                    return show(c.getSource(), ops.retry(getString(c, "id")));
                })))
            .then(literal("scan").requires(owner).executes(c -> {
                return show(c.getSource(), ops.scan());
            }))
            .then(literal("stock")
                .executes(c -> {
                    return show(c.getSource(), ops.stock(null));
                })
                .then(argument("filter", greedyString()).executes(c -> {
                    return show(c.getSource(), ops.stock(getString(c, "filter")));
                })))
            .then(literal("submit").requires(owner)
                .then(argument("path", greedyString()).executes(c -> {
                    return show(c.getSource(), submit(getString(c, "path")));
                })))
            .then(literal("resubstitute").requires(owner)
                .then(argument("id", word())
                    .executes(c -> {
                        return show(c.getSource(), resubstitute(getString(c, "id"), false));
                    })
                    .then(literal("apply").executes(c -> {
                        return show(c.getSource(), resubstitute(getString(c, "id"), true));
                    }))))
            .then(literal("discord").requires(owner)
                .executes(c -> {
                    return show(c.getSource(), Reply.info("Map art Discord bot", discordLine()));
                })
                .then(literal("reload").executes(c -> {
                    return show(c.getSource(), reloadDiscord());
                }))
                .then(argument("toggle", toggle()).executes(c -> {
                    return show(c.getSource(), setDiscord(getToggle(c, "toggle")));
                })))
            .then(literal("verbose").requires(owner)
                .executes(c -> {
                    return show(c.getSource(), DiscordCommands.verboseReply(config.discord.verbose, false, "mapart"));
                })
                .then(argument("toggle", toggle()).executes(c -> {
                    return show(c.getSource(), setVerbose(getToggle(c, "toggle")));
                })))
            .then(literal("elytra").requires(owner)
                .executes(c -> {
                    return show(c.getSource(), elytraReply(config.build, false));
                })
                .then(literal("short").then(argument("toggle", toggle()).executes(c -> {
                    return show(c.getSource(), setElytraShort(getToggle(c, "toggle")));
                })))
                .then(literal("hops").then(argument("toggle", toggle()).executes(c -> {
                    return show(c.getSource(), setElytraLongHops(getToggle(c, "toggle")));
                })))
                .then(literal("kittrip").then(argument("toggle", toggle()).executes(c -> {
                    return show(c.getSource(), setElytraKitFarTrip(getToggle(c, "toggle")));
                })))
                .then(argument("toggle", toggle()).executes(c -> {
                    return show(c.getSource(), setElytra(getToggle(c, "toggle")));
                })))
            .then(literal("site").executes(c -> {
                return show(c.getSource(), SiteCheck.reply(config, ops.mapCounterButton(), ops.rocketChestReport()));
            }));
    }

    /**
     * {@code mapart stop [disconnect]} under {@code name}; no owner check on purpose. Any other text after it still
     * stops (never refuse a stop over a typo), without disconnecting.
     */
    private LiteralArgumentBuilder<CommandContext> stopNode(String name) {
        return literal(name)
            .executes(c -> {
                return show(c.getSource(), ops.stop(who(c.getSource()), false, "mapart"));
            })
            .then(literal("disconnect").executes(c -> {
                return show(c.getSource(), ops.stop(who(c.getSource()), true, "mapart"));
            }))
            .then(argument("ignored", greedyString()).executes(c -> {
                return show(c.getSource(), ops.stop(who(c.getSource()), false, "mapart")
                    .append("(`" + Fmt.inCode(Fmt.truncate(getString(c, "ignored"), 40))
                        + "` ignored; `mapart stop disconnect` also disconnects.)"));
            }));
    }

    // ---------------------------------------------------------------- handlers

    /** Who ran a command, for the stop reason: the Discord user on ZenithProxy's Discord, else the source name. */
    static String who(CommandContext ctx) {
        if (ctx instanceof DiscordCommandContext d) {
            try {
                return d.getMessageReceivedEvent().getAuthor().getEffectiveName() + " (ZenithProxy Discord)";
            } catch (RuntimeException e) {
                // fall back to the source name
            }
        }
        String source = ctx.getSource() == null ? "" : ctx.getSource().name();
        return (source == null || source.isBlank() ? "mapart" : source) + " (mapart)";
    }

    Reply statusReply() {
        return ops.status()
            .append("**Builder:** " + (config.enabled ? "on" : "off") + (config.paused ? " (paused)" : ""))
            .append("**Discord bot:** " + discordLine());
    }

    Reply setEnabled(boolean on) {
        config.enabled = on;
        String hook = runHook();
        Reply r = on
            ? Reply.ok("Map art builder on", "The engine picks up the queue at the next tick.")
            : Reply.warn("Map art builder off", "Uploads are still queued; nothing is built.");
        return hook == null ? r : r.append("Module sync failed: " + hook);
    }

    Reply setDiscord(boolean on) {
        config.discord.enabled = on;
        if (discord == null) return Reply.error("Map art Discord bot", "The Discord client is not available.");
        if (on) discord.start();
        else discord.stop();
        return on
            ? Reply.ok("Map art Discord bot on", "Connecting in the background. Check `mapart discord` for the state.")
            : Reply.warn("Map art Discord bot off", "The bot disconnects; notifications go to the log only.");
    }

    /** {@code discord.verbose}; ZenithProxy saves the config after every command. */
    Reply setVerbose(boolean on) {
        config.discord.verbose = on;
        return DiscordCommands.verboseReply(on, true, "mapart");
    }

    /**
     * {@code build.elytraLegs} at runtime; ZenithProxy saves the
     * config after every command. Off is the kill switch: no further take-off or rocket chest visit; a flight in the
     * air finishes its planned landing, the chestplate goes back on, and the elytra and the rockets stay in the
     * inventory.
     */
    Reply setElytra(boolean on) {
        config.build.elytraLegs = on;
        return elytraReply(config.build, true);
    }

    /**
     * {@code build.elytraShortLandings} at runtime: the next take-off plans onto
     * a spot at the goal; a flight in the air keeps its plan. {@code mapart elytra off} still stops every flight.
     */
    Reply setElytraShort(boolean on) {
        config.build.elytraShortLandings = on;
        return elytraReply(config.build, true);
    }

    /** {@code build.elytraLongHops} at runtime, as {@link #setElytraShort}. */
    Reply setElytraLongHops(boolean on) {
        config.build.elytraLongHops = on;
        return elytraReply(config.build, true);
    }

    /**
     * {@code build.elytraKitFarTrip} at runtime (the far trip): the next RESTOCK of a
     * colour-suppression job with too few rockets goes to a far rocket chest by the platform's access path, or (off)
     * makes no trip. A trip under way goes on to its end either way (the way back goes by where the bot stands; with
     * the switch off the RESTOCK's own walks bring it back).
     */
    Reply setElytraKitFarTrip(boolean on) {
        config.build.elytraKitFarTrip = on;
        return elytraReply(config.build, true);
    }

    static Reply elytraReply(MapArtConfig.BuildCfg b, boolean changed) {
        boolean on = b.elytraLegs;
        String title = "Elytra legs " + (on ? "on" : "off");
        return on
            ? Reply.ok(title, (changed ? "Switched on. " : "") + "Restock legs of build.elytraMinBlocks or more fly"
                + " when the elytra, flight rockets and a landing pad are there; the rocket chest tops them up in RESTOCK."
                + " Outside the restock: " + outsideKinds(b) + ". Further flights a leg: " + b.elytraHops + "."
                + " Planning again on the way: " + (b.elytraOnTheWayBlocks > 0 ? "every " + b.elytraOnTheWayBlocks
                + " blocks on foot" : "off") + ". Over a canal: " + (b.elytraOverWater ? "on" : "off") + "."
                + " Arcs: " + (b.elytraArcLegs ? "on, from " + b.elytraArcMinBlocks + " blocks" : "off") + "."
                + " Short landings: " + (b.elytraShortLandings ? "on, from " + b.elytraShortMinBlocks + " blocks" : "off")
                + ". Long hops: " + (b.elytraLongHops ? "on" : "off") + "."
                + " Kit trip to a far rocket chest from a color-suppression job: " + (b.elytraKitFarTrip ? "on" : "off")
                + ". `mapart elytra off` stops it at once.")
            : Reply.ok(title, (changed ? "Switched off. " : "") + "Every leg walks. A flight in the air finishes its"
                + " landing and the chestplate goes back on; the elytra and the rockets stay in the inventory (never"
                + " deposited or dropped). Short landings: " + (b.elytraShortLandings ? "on" : "off") + ", long hops: "
                + (b.elytraLongHops ? "on" : "off") + " (no flight while the legs are off).");
    }

    /** The walks that fly outside the restock ({@code build.elytraWalkLegs} and the others), or "none". */
    static String outsideKinds(MapArtConfig.BuildCfg b) {
        List<String> on = new ArrayList<>();
        if (b.elytraWalkLegs) on.add("walkTo");
        if (b.elytraContainerLegs) on.add("containers");
        if (b.elytraLaneStartLegs) on.add("lane starts");
        if (b.elytraStandSpotLegs) on.add("stand spots");
        if (b.elytraMapStationLegs) on.add("the map station");
        return on.isEmpty() ? "none" : String.join(", ", on);
    }

    Reply reloadDiscord() {
        if (discord == null) return Reply.error("Map art Discord bot", "The Discord client is not available.");
        discord.reload();
        return Reply.ok("Map art Discord bot reloading", "Reconnecting with the current token, channel and prefix.");
    }

    Reply submit(String rawPath) {
        String p = unquote(rawPath.strip());
        Path path;
        try {
            path = Path.of(p);
        } catch (InvalidPathException e) {
            return Reply.error("Cannot submit", "Invalid path: " + e.getMessage());
        }
        executor.execute(() -> submitLocal(path));
        // the path as typed: the terminal reads markdown, and "a_b_c" would lose its underscores to italics
        return Reply.info("Submitting " + Fmt.literal(p), "Parsing in the background; the result is logged and posted"
            + " to the map art channel.");
    }

    /** Runs {@link SubmissionPipeline#resubstitute} in the background; the result is logged and posted. */
    Reply resubstitute(String token, boolean apply) {
        Job j = ops.resolve(token).orElse(null);
        if (j == null) return Reply.error("Cannot re-substitute", "No job " + Fmt.literal(token) + ".");
        if (apply && config.enabled && !config.paused)
            return Reply.error("Cannot re-substitute " + j.id, "Pause the builder first (`mapart pause`), or turn it "
                + "off: the engine must not read a tile while it changes. `mapart resubstitute " + j.id
                + "` without apply is a dry run.");
        String id = j.id;
        executor.execute(() -> resubstituteNow(id, apply));
        return Reply.info((apply ? "Re-substituting " : "Dry run: re-substitution of ") + id, "Working in the background; "
            + "the result is logged and posted to the map art channel.");
    }

    void resubstituteNow(String id, boolean apply) {
        try {
            SubmissionPipeline.Resubstitution r = pipeline.resubstitute(id, apply);
            String body = SubmissionPipeline.describe(r);
            log.info("Map art re-substitution of {} ({}): {}", id, apply ? "apply" : "dry run", body.replace('\n', ' '));
            Notifier n = notifier.get();
            if (n == null) return;
            String title = (r.applied() ? "Re-substituted " : apply ? "Re-substitution not applied: " : "Re-substitution dry run: ")
                + id + (r.jobName() == null ? "" : " " + Fmt.escape(r.jobName()));
            n.info(title, Fmt.escape(body));
        } catch (RuntimeException e) {
            log.error("Map art re-substitution of {} failed", id, e);
        }
    }

    void submitLocal(Path path) {
        try {
            List<NamedFile> files = SubmissionPipeline.readLocal(path, config.discord.maxAttachmentBytes);
            Notifier n = notifier.get();
            for (SubmissionPipeline.Outcome o : pipeline.submitAll(new SubmissionPipeline.Request(
                files, null, "terminal", "terminal", null, null))) switch (o) {
                case Accepted a -> {
                    Job j = a.job();
                    String body = localSummary(a);
                    log.info("Map art local submission queued as {} \"{}\" (#{})", j.id, j.name, a.queuePosition());
                    if (n == null) continue;
                    String title = "Queued " + j.id + ": " + Fmt.escape(j.name);
                    if (a.previewPng() != null) n.image(title, body, SubmissionPipeline.PREVIEW_FILE, a.previewPng());
                    else n.info(title, body);
                }
                case Rejected r -> {
                    log.warn("Map art local submission {} rejected: {}", path, String.join("; ", r.reasons()));
                    if (n != null) n.info("Local submission rejected", Fmt.bullets(r.reasons(), 3500));
                }
            }
        } catch (IOException e) {
            log.warn("Map art local submission failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Map art local submission failed", e);
        }
    }

    static String localSummary(Accepted a) {
        Job j = a.job();
        List<String> noChest = a.materials().stream().filter(QueueOps.MaterialRow::unstocked)
            .map(QueueOps.MaterialRow::item).sorted().toList();
        long shorts = a.materials().stream().filter(r -> r.isShort() && !r.unstocked()).count();
        String swapped = io.github.digitalisp.mapart.plan.SameColourSwap.line(j.substitutions, 600);
        String shape = j.isSuppression() ? "color suppression, " + j.tiles.size() + " passes"
            : j.tilesX + "x" + j.tilesZ + " tiles";
        return "Submitted from the terminal: " + shape + ", " + Fmt.count(a.totalBlocks())
            + " blocks, queue #" + a.queuePosition() + ", platforms " + String.join(", ", j.platformIds) + "."
            + (swapped == null ? "" : "\n" + Fmt.escape(swapped))
            + (noChest.isEmpty() ? "" : "\n" + Unstocked.MARK + " " + Fmt.escape(Unstocked.line(noChest, 600)))
            + (shorts > 0 ? "\n" + shorts + (noChest.isEmpty() ? "" : " other") + " material(s) short (marked !)." : "")
            + (a.warnings().isEmpty() ? "" : "\n" + Fmt.bullets(a.warnings(), 800))
            + "\n" + QueueOps.formatMaterials(a.materials(), 2500);
    }

    private String discordLine() {
        if (discord == null) return "not available";
        return config.discord.enabled ? discord.describe() : "off";
    }

    private String runHook() {
        if (syncEngineModule == null) return null;
        try {
            syncEngineModule.run();
            return null;
        } catch (RuntimeException e) {
            log.warn("Map art module sync failed", e);
            return e.toString();
        }
    }

    static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))))
            return s.substring(1, s.length() - 1);
        return s;
    }

    /** {@code r} as {@code source} may see it: converted for ZenithProxy's Discord, unchanged elsewhere. */
    Reply forSource(CommandSource source, Reply r) {
        if (!(source instanceof DiscordCommandSource)) return r;
        CoordFormat f = coords.get();
        return f.relative() ? r.mapText(t -> f.discord(t, r.title(), log)) : r;
    }

    private int show(CommandContext ctx, Reply reply) {
        Reply r = forSource(ctx.getSource(), reply);
        Embed e = ctx.getEmbed().title(r.title());
        if (!r.body().isEmpty()) e.description(r.body());
        switch (r.tone()) {
            case SUCCESS -> e.successColor();
            case WARN -> e.inQueueColor();
            case ERROR -> e.errorColor();
            default -> e.primaryColor();
        }
        return r.isError() ? ERROR : OK;
    }
}
