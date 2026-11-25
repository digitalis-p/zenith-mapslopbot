package io.github.digitalisp.mapart.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.requests.CloseCode;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import io.github.digitalisp.mapart.command.CommandLine;
import io.github.digitalisp.mapart.command.Fmt;
import io.github.digitalisp.mapart.command.Names;
import io.github.digitalisp.mapart.command.Reply;
import io.github.digitalisp.mapart.command.SubmissionPipeline;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.CoordFormat;
import io.github.digitalisp.mapart.model.NamedFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The plugin's own Discord client (separate bot token, JDA from ZenithProxy's classpath). Listens in
 * {@code discord.channelId} only and ignores bots and webhooks. Messages with .nbt/.zip attachments
 * are submissions; messages starting with {@code discord.commandPrefix} are commands. All handling
 * runs one message at a time on the injected executor, never on JDA's event thread, with one exception:
 *
 * <p><b>Kill switch.</b> {@code !map stop} (aliases {@code kill}, {@code halt}) is handled at once on the thread that
 * received it, ahead of the submission queue and without waiting for a bot tick;
 * {@link io.github.digitalisp.mapart.command.QueueOps#stop} bounds every wait. A stop wins over attachments in the same message.
 *
 * <p>Lifecycle: {@link #start()} and {@link #reload()} build the client on the executor (JDA logs in
 * asynchronously and reconnects by itself); {@link #stop()} shuts it down gracefully. The token is
 * never logged. {@link #presence()} lets {@link PresenceUpdater} set the bot's custom status.
 *
 * <p>Every card that leaves for Discord (replies, and notifier posts and edits through {@link #sink()}) passes
 * {@link CoordFormat#discord} once more as the last line of defence; the composers have already converted.
 */
public final class IntakeBot {
    static final long DOWNLOAD_TIMEOUT_SECONDS = 60;

    private final MapArtConfig config;
    private final SubmissionPipeline pipeline;
    private final DiscordCommands commands;
    private final Supplier<CoordFormat> coords;
    private final SerialExecutor intake;
    /** start/stop/reload in call order (the shared executor has several threads). */
    private final SerialExecutor lifecycle;
    private final Logger log;
    /** Uploads are submissions ({@code Coordination.discordIntake}). */
    private final BooleanSupplier intakeOn;
    private final ChannelSink sink = new ChannelSink();
    private final PresenceSink presence = new PresenceSink();
    private final AtomicInteger generation = new AtomicInteger();

    private volatile JDA jda;
    private volatile String channelId = "";
    private volatile String state = "not started";

    /** @param coords how coordinates are shown in Discord, normally {@link CoordFormat#live} */
    public IntakeBot(MapArtConfig config, SubmissionPipeline pipeline, DiscordCommands commands,
                     Supplier<CoordFormat> coords, Executor executor, Logger log) {
        this(config, pipeline, commands, coords, executor, log, () -> true);
    }

    /**
     * {@code intakeOn}: whether uploads to the channel are taken as submissions ({@code Coordination.discordIntake}).
     * Commands are handled either way.
     */
    public IntakeBot(MapArtConfig config, SubmissionPipeline pipeline, DiscordCommands commands,
                     Supplier<CoordFormat> coords, Executor executor, Logger log, BooleanSupplier intakeOn) {
        this.intakeOn = intakeOn;
        this.config = config;
        this.pipeline = pipeline;
        this.commands = commands;
        this.coords = coords;
        this.intake = new SerialExecutor(executor);
        this.lifecycle = new SerialExecutor(executor);
        this.log = log;
    }

    // ---------------------------------------------------------------- lifecycle

    /** Starts the client on the executor if {@code discord.enabled} and token/channel are set. No-op if running. */
    public void start() {
        lifecycle.execute(this::startNow);
    }

    /**
     * Graceful shutdown on the executor (queued sends still go out). Never blocks the caller, which may
     * be the client tick thread or an in-game command. Falls back to the caller if the executor is gone.
     */
    public void stop() {
        generation.incrementAndGet(); // ignore events from now on
        try {
            lifecycle.execute(this::stopNow);
        } catch (RuntimeException e) {
            stopNow();
        }
    }

    /** Stops and starts again with the current config (new token, channel or prefix). */
    public void reload() {
        lifecycle.execute(() -> {
            stopNow();
            startNow();
        });
    }

    synchronized void startNow() {
        JDA current = jda;
        if (current != null && current.getStatus() != JDA.Status.SHUTDOWN
            && current.getStatus() != JDA.Status.FAILED_TO_LOGIN) return;
        MapArtConfig.DiscordCfg d = config.discord;
        if (!d.enabled) {
            state = "disabled (discord.enabled is off)";
            log.info("Map art Discord bot is disabled");
            return;
        }
        String token = d.token == null ? "" : d.token.strip();
        if (token.isEmpty()) {
            state = "not started: discord.token is not set";
            log.warn("Map art Discord bot not started: discord.token is not set");
            return;
        }
        String channel = d.channelId == null ? "" : d.channelId.strip();
        if (!channel.matches("\\d{5,25}")) {
            state = "not started: discord.channelId is not a channel id";
            log.warn("Map art Discord bot not started: discord.channelId is not set or not a numeric id");
            return;
        }
        int gen = generation.incrementAndGet();
        channelId = channel;
        state = "logging in";
        try {
            jda = JDABuilder.createLight(token, EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                .setStatus(OnlineStatus.ONLINE)
                .addEventListeners(new Listener(gen))
                .build();
            log.info("Map art Discord bot starting for channel {}", channel);
        } catch (InvalidTokenException e) {
            jda = null;
            state = "failed: the token was rejected";
            log.error("Map art Discord bot: Discord rejected discord.token");
        } catch (RuntimeException e) {
            jda = null;
            state = "failed: " + scrub(e.toString(), token);
            log.error("Map art Discord bot failed to start: {}", scrub(e.toString(), token));
        }
    }

    synchronized void stopNow() {
        generation.incrementAndGet();
        JDA j = jda;
        jda = null;
        state = "stopped";
        if (j == null) return;
        try {
            j.shutdown();
            log.info("Map art Discord bot stopped");
        } catch (RuntimeException e) {
            log.warn("Map art Discord bot shutdown failed: {}", e.toString());
        }
    }

    /** Connected and the configured channel is visible. */
    public boolean ready() {
        JDA j = jda;
        return j != null && j.getStatus() == JDA.Status.CONNECTED && channel(j) != null;
    }

    /** One-line state for status displays. */
    public String describe() {
        JDA j = jda;
        if (j == null) return state;
        MessageChannel ch = channel(j);
        return j.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ')
            + (ch != null ? ", channel #" + ch.getName() : ", channel " + channelId + " not visible");
    }

    /** Transport for {@link DiscordNotifier}. */
    public DiscordNotifier.Sink sink() {
        return sink;
    }

    /** Transport for {@link PresenceUpdater}. */
    public PresenceUpdater.Target presence() {
        return presence;
    }

    private MessageChannel channel(JDA j) {
        String id = channelId;
        if (j == null || id.isEmpty()) return null;
        try {
            return j.getChannelById(MessageChannel.class, id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- events (JDA thread: hand off fast)

    private final class Listener implements EventListener {
        private final int gen;

        Listener(int gen) {
            this.gen = gen;
        }

        @Override
        public void onEvent(GenericEvent event) {
            if (gen != generation.get()) return; // stale client after stop/reload
            try {
                if (event instanceof MessageReceivedEvent e) onMessage(e);
                else if (event instanceof ReadyEvent e) onReady(e.getJDA());
                else if (event instanceof ShutdownEvent e) onShutdown(e);
            } catch (Throwable t) {
                log.warn("Map art Discord event handling failed", t);
            }
        }
    }

    private void onReady(JDA j) {
        MessageChannel ch = channel(j);
        if (ch == null) {
            state = "connected, but channel " + channelId + " is not visible";
            log.warn("Map art Discord bot connected as {} but cannot see channel {} (wrong id or missing permission)",
                j.getSelfUser().getName(), channelId);
        } else {
            state = "connected";
            log.info("Map art Discord bot connected as {} in #{}", j.getSelfUser().getName(), ch.getName());
        }
    }

    private void onShutdown(ShutdownEvent e) {
        CloseCode code = e.getCloseCode();
        if (code == CloseCode.DISALLOWED_INTENTS) {
            state = "failed: MESSAGE CONTENT intent not enabled";
            log.error("Map art Discord bot: enable the MESSAGE CONTENT intent for the bot in the Discord developer portal");
        } else if (code == CloseCode.AUTHENTICATION_FAILED) {
            state = "failed: the token was rejected";
            log.error("Map art Discord bot: Discord rejected discord.token");
        } else if (code != null) {
            state = "shut down: " + code.getMeaning();
            log.warn("Map art Discord bot shut down: {}", code.getMeaning());
        }
    }

    private void onMessage(MessageReceivedEvent e) {
        if (!channelId.equals(e.getChannel().getId())) return;
        User author = e.getAuthor();
        if (author.isBot() || author.isSystem() || e.isWebhookMessage()) return;
        Message msg = e.getMessage();
        String content = msg.getContentRaw();
        String prefix = config.discord.commandPrefix;
        String userId = author.getId();
        String userName = author.getEffectiveName();

        var parsed = CommandLine.parse(content, prefix);
        if (parsed.isPresent() && DiscordCommands.isStop(parsed.get())) {
            // kill switch: right here, not behind submissions in the intake queue
            log.warn("Map art STOP requested in Discord by {}: {}", userName, Fmt.truncate(content, 80));
            handleCommand(msg, parsed.get(), prefix, userName);
            return;
        }

        List<Message.Attachment> atts = msg.getAttachments();
        List<AttachmentFilter.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < atts.size(); i++)
            candidates.add(new AttachmentFilter.Candidate(i, atts.get(i).getFileName(), atts.get(i).getSize()));
        AttachmentFilter.Selection sel = AttachmentFilter.select(candidates, config.discord.maxAttachmentBytes);
        if (sel.isSubmission() && !intakeOn.getAsBoolean()) {
            return; // the intake is off: uploads are left alone
        }
        if (sel.isSubmission()) {
            // files win over the prefix: "!map queue verity" with a .nbt is a submission named "verity"
            String name = Names.nameFromMessage(content, prefix);
            intake.execute(() -> handleSubmission(msg, atts, sel, name, userId, userName));
            return;
        }
        parsed.ifPresent(cmd -> intake.execute(() -> handleCommand(msg, cmd, prefix, userName)));
    }

    // ---------------------------------------------------------------- handlers (executor)

    private void handleCommand(Message msg, CommandLine cmd, String prefix, String userName) {
        Reply r;
        try {
            log.info("Map art Discord command by {}: {}", userName, cmd.name());
            r = commands.handle(cmd, prefix, userName);
        } catch (RuntimeException e) {
            log.warn("Map art Discord command failed: {}", cmd.name(), e);
            r = Reply.error("Command failed", e.toString());
        }
        reply(msg, Card.of(r));
    }

    private void handleSubmission(Message msg, List<Message.Attachment> atts, AttachmentFilter.Selection sel,
                                  String name, String userId, String userName) {
        try {
            if (!sel.errors().isEmpty()) {
                reply(msg, SubmissionCards.rejected(sel.errors()));
                return;
            }
            log.info("Map art submission by {}: {} file(s)", userName, sel.accepted().size());
            try {
                msg.getChannel().sendTyping().queue(ok -> { }, err -> { });
            } catch (RuntimeException ignored) {
                // typing indicator is cosmetic
            }
            long max = config.discord.maxAttachmentBytes;
            List<NamedFile> files = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            for (AttachmentFilter.Candidate c : sel.accepted()) {
                Message.Attachment a = atts.get(c.index());
                try {
                    files.add(new NamedFile(a.getFileName(), download(a, max)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    problems.add("Could not download `" + a.getFileName() + "`: " + ex.getMessage());
                }
            }
            if (!problems.isEmpty()) {
                reply(msg, SubmissionCards.rejected(problems));
                return;
            }
            List<String> extra = sel.ignored().isEmpty() ? List.of()
                : List.of("Ignored attachments that are not .nbt/.zip: " + String.join(", ", sel.ignored()));
            // several colour-suppression maps in one upload: one job and one reply each, in queue order
            List<SubmissionPipeline.Outcome> outcomes = pipeline.submitAll(new SubmissionPipeline.Request(
                files, name, userId, userName, channelId, msg.getId()));
            for (int i = 0; i < outcomes.size(); i++) {
                reply(msg, SubmissionCards.of(outcomes.get(i), i == 0 ? extra : List.of()));
            }
        } catch (Throwable t) {
            log.error("Map art submission handling failed", t);
            reply(msg, SubmissionCards.rejected(List.of("Internal error: " + t.getClass().getSimpleName())));
        }
    }

    private static byte[] download(Message.Attachment a, long max) throws Exception {
        if (a.getSize() > max) throw new IllegalStateException("larger than the limit");
        CompletableFuture<InputStream> f = a.getProxy().download();
        try (InputStream in = f.get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return AttachmentFilter.readBounded(in, max);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new TimeoutException("timed out after " + DOWNLOAD_TIMEOUT_SECONDS + " s");
        }
    }

    private void reply(Message msg, Card card) {
        try {
            msg.reply(JdaMessages.create(outgoing(card)))
                .mentionRepliedUser(false)
                .failOnInvalidReply(false)
                .queue(ok -> { }, err -> log.warn("Map art Discord reply failed: {}", err.toString()));
        } catch (RuntimeException e) {
            log.warn("Map art Discord reply failed: {}", e.toString());
        }
    }

    /** {@code card} as it may leave for Discord (coordinates per {@code discord.coordinates}). */
    private Card outgoing(Card card) {
        CoordFormat f = coords.get();
        if (!f.relative()) return card;
        String title = card.title();
        return card.mapText(t -> f.discord(t, title, log));
    }

    private static String scrub(String s, String token) {
        if (s == null) return "";
        return token.isEmpty() ? s : s.replace(token, "<token>");
    }

    // ---------------------------------------------------------------- notifier transport

    private final class ChannelSink implements DiscordNotifier.Sink {
        @Override
        public boolean ready() {
            return IntakeBot.this.ready();
        }

        @Override
        public void post(Card card, Consumer<String> onPosted) {
            MessageChannel ch = channel(jda);
            if (ch == null) {
                onPosted.accept(null);
                return;
            }
            try {
                ch.sendMessage(JdaMessages.create(outgoing(card))).queue(m -> onPosted.accept(m.getId()), err -> {
                    log.warn("Map art Discord post failed: {}", err.toString());
                    onPosted.accept(null);
                });
            } catch (RuntimeException e) {
                log.warn("Map art Discord post failed: {}", e.toString());
                onPosted.accept(null);
            }
        }

        @Override
        public void edit(String messageId, Card card, Consumer<Boolean> onDone) {
            MessageChannel ch = channel(jda);
            if (ch == null || messageId == null) {
                onDone.accept(false);
                return;
            }
            try {
                ch.editMessageById(messageId, JdaMessages.edit(outgoing(card))).queue(m -> onDone.accept(true), err -> onDone.accept(false));
            } catch (RuntimeException e) {
                onDone.accept(false);
            }
        }
    }

    // ---------------------------------------------------------------- presence transport

    private final class PresenceSink implements PresenceUpdater.Target {
        @Override
        public Object connection() {
            JDA j = jda;
            return j != null && j.getStatus() == JDA.Status.CONNECTED ? j : null;
        }

        @Override
        public void apply(OnlineStatus status, String text) {
            JDA j = jda;
            if (j == null) return;
            // queued on JDA's gateway sender; custom status = activity type 4 with the text as its state
            j.getPresence().setPresence(status, text == null ? null : Activity.customStatus(text));
        }
    }
}
