package io.github.digitalisp.mapart.discord;

import org.slf4j.Logger;
import io.github.digitalisp.mapart.command.CommandLine;
import io.github.digitalisp.mapart.command.Fmt;
import io.github.digitalisp.mapart.command.Names;
import io.github.digitalisp.mapart.command.QueueOps;
import io.github.digitalisp.mapart.command.Reply;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.CoordFormat;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The {@code !map} prefix commands of the map-art channel. Anyone who can post in the channel may
 * use them. Pure dispatch onto {@link QueueOps} (and {@code verbose} onto the Discord settings); blocking
 * store I/O, so call it off the JDA event thread, except the stop commands ({@link #isStop}), which are meant to run
 * at once on whatever thread received them. Replies come back with coordinates as Discord shows them
 * ({@code discord.coordinates}).
 */
public final class DiscordCommands {
    /** {@code !map stop} and its aliases. */
    public static final Set<String> STOP_NAMES = Set.of("stop", "kill", "halt");

    private final QueueOps ops;
    private final MapArtConfig.DiscordCfg cfg;
    private final Runnable saveConfig;
    private final Supplier<CoordFormat> coords;
    private final Logger log;

    /** Without the Discord settings: {@code verbose} answers that it is not available. */
    public DiscordCommands(QueueOps ops, Supplier<CoordFormat> coords, Logger log) {
        this(ops, null, () -> { }, coords, log);
    }

    /**
     * @param cfg        the settings {@code verbose} shows and switches (read live by the notifier)
     * @param saveConfig persists the plugin config after {@code verbose} changed it (ZenithProxy's async save)
     * @param coords     how coordinates are shown in Discord, normally {@link CoordFormat#live}
     */
    public DiscordCommands(QueueOps ops, MapArtConfig.DiscordCfg cfg, Runnable saveConfig, Supplier<CoordFormat> coords,
                           Logger log) {
        this.ops = ops;
        this.cfg = cfg;
        this.saveConfig = saveConfig == null ? () -> { } : saveConfig;
        this.coords = coords;
        this.log = log;
    }

    /** True for {@code !map stop}, {@code kill} and {@code halt}: the intake bot runs these ahead of everything. */
    public static boolean isStop(CommandLine cmd) {
        return cmd != null && STOP_NAMES.contains(cmd.name());
    }

    /** @param prefix the configured command prefix, only used in help/usage text */
    public Reply handle(CommandLine cmd, String prefix, String userName) {
        return forDiscord(dispatch(cmd, prefix, userName));
    }

    private Reply forDiscord(Reply r) {
        CoordFormat f = coords.get();
        return f.relative() ? r.mapText(t -> f.discord(t, r.title(), log)) : r;
    }

    private static String prefixOf(String prefix) {
        return prefix == null || prefix.isBlank() ? "!map" : prefix.strip();
    }

    private Reply dispatch(CommandLine cmd, String prefix, String userName) {
        String p = prefixOf(prefix);
        return switch (cmd.name()) {
            case "stop", "kill", "halt" -> stop(cmd, p, userName);
            case "help", "?" -> help(p);
            case "queue", "q", "list" -> ops.queue();
            case "status", "s" -> ops.status();
            case "cancel" -> cmd.arg(0).map(ops::cancel).orElseGet(() -> usage(p, "cancel <id>"));
            case "move" -> {
                OptionalInt pos = cmd.intArg(1);
                if (cmd.arg(0).isEmpty() || pos.isEmpty()) yield usage(p, "move <id> <position>");
                yield ops.move(cmd.args().get(0), pos.getAsInt());
            }
            case "pause" -> ops.pause(cmd.rest().isBlank()
                ? "paused by " + userName + " (Discord)"
                : Fmt.truncate(cmd.rest(), 200) + " (" + userName + ")", p);
            case "resume" -> ops.resume();
            case "stock" -> ops.stock(Fmt.truncate(cmd.rest(), 64));
            case "scan" -> ops.scan();
            case "retry" -> cmd.arg(0).map(ops::retry).orElseGet(() -> usage(p, "retry <id>"));
            case "submit" -> Reply.info("How to submit", submitHint(p));
            case "verbose" -> verbose(cmd, p);
            default -> Reply.error("Unknown command", "`" + Fmt.inCode(cmd.name()) + "` is not a command. Try `" + p + " help`.");
        };
    }

    /**
     * {@code stop [disconnect]}. Anything else after "stop" still stops (never refuse a stop over a typo); only the
     * word {@code disconnect} (or {@code dc}) also drops the server connection.
     */
    private Reply stop(CommandLine cmd, String p, String userName) {
        String opt = cmd.arg(0).map(a -> a.toLowerCase(Locale.ROOT)).orElse("");
        boolean disconnect = opt.equals("disconnect") || opt.equals("dc");
        Reply r = ops.stop(userName + " (Discord)", disconnect, p);
        return opt.isEmpty() || disconnect ? r
            : r.append("(`" + Fmt.inCode(Fmt.truncate(cmd.rest(), 40)) + "` ignored; `" + p
                + " stop disconnect` also disconnects.)");
    }

    /** {@code verbose [on|off]}: show or switch {@code discord.verbose}; a change is saved with the config. */
    private Reply verbose(CommandLine cmd, String p) {
        if (cfg == null) return Reply.error("Verbose posts", "The Discord settings are not available here.");
        String a = cmd.arg(0).map(s -> s.toLowerCase(Locale.ROOT)).orElse("");
        if (a.isEmpty()) return verboseReply(cfg.verbose, false, p);
        boolean on;
        switch (a) {
            case "on", "true" -> on = true;
            case "off", "false" -> on = false;
            default -> {
                return usage(p, "verbose [on|off]");
            }
        }
        cfg.verbose = on;
        try {
            saveConfig.run();
        } catch (RuntimeException e) {
            log.warn("Map art config save failed: {}", e.toString());
        }
        return verboseReply(on, true, p);
    }

    /**
     * What {@code verbose} replies, here and from {@code mapart verbose}: the state, whether it was just switched, and
     * what it means.
     */
    public static Reply verboseReply(boolean on, boolean changed, String p) {
        String title = "Verbose posts " + (on ? "on" : "off");
        String body = on
            ? "Routine steps (back-offs, frequent setbacks, tile built, map locked, clearing, next map) and the"
                + " periodic progress card are posted to the map art channel too.\n`" + p + " verbose off` keeps them"
                + " in the log only."
            : "The map art channel gets alerts, job started/done/cancelled, pause/resume, waits that may need a human,"
                + " and one \"Map done\" per stored map.\nRoutine steps and progress cards go to the log only; `" + p
                + " verbose on` posts them too.";
        return changed ? Reply.ok(title, body) : Reply.info(title, body);
    }

    public static Reply help(String p) {
        String body = String.join("\n",
            "`" + p + " stop` **halt NOW**: Baritone stopped, task cancelled, pause saved. Also `" + p + " kill` or `"
                + p + " halt`. Anyone may use it.",
            "`" + p + " stop disconnect` halt and disconnect from the server (no auto-reconnect)",
            "**Submit:** attach the MapArtCraft `.nbt` files or a `.zip` of them; optional text = job name (max "
                + Names.MAX_NAME + " chars), e.g. `verity`. A leading `" + p + " queue` is not part of the name.",
            "`" + p + " queue` show the queue",
            "`" + p + " status` engine status and progress",
            "`" + p + " cancel <id>` cancel a job",
            "`" + p + " move <id> <pos>` move a job to a queue position (1 = next)",
            "`" + p + " pause [reason]` pause after the current action (typically within one tick)",
            "`" + p + " resume` resume (also clears BLOCKED and a stop)",
            "`" + p + " stock [filter]` material stock vs. queued needs",
            "`" + p + " scan` rescan all material chests",
            "`" + p + " retry <id>` requeue a FAILED or WAITING job",
            "`" + p + " verbose [on|off]` routine steps and progress cards in this channel too (default off)");
        return Reply.info("Map art commands", body);
    }

    /** What {@code !map submit} without attachments replies: short lines, readable on a phone. */
    static String submitHint(String p) {
        return "Attach the MapArtCraft `.nbt` files or a `.zip` of them to a message in this channel; no command needed.\n"
            + "Optional text = job name (max " + Names.MAX_NAME + " chars), e.g. `verity`. Without text the file name is used.\n"
            + "A leading `" + p + " queue` or `!queue` is not part of the name.";
    }

    private static Reply usage(String p, String usage) {
        return Reply.error("Missing argument", "Usage: `" + p + " " + usage + "`");
    }
}
