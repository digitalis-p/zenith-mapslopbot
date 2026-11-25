package io.github.digitalisp.mapart.discord;

import org.slf4j.Logger;
import io.github.digitalisp.mapart.command.Fmt;
import io.github.digitalisp.mapart.command.Names;
import io.github.digitalisp.mapart.command.StatusWords;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.CoordFormat;
import io.github.digitalisp.mapart.engine.Notifier;
import io.github.digitalisp.mapart.model.Job;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@link Notifier} that posts to the map-art channel through the plugin's own Discord client.
 *
 * <p>Callers (the client tick thread) only append to a lock-free queue; a scheduled {@link #pump()}
 * sends from an executor thread. Sending is rate limited by a token bucket ({@value #BURST} messages,
 * one more every {@value #REFILL_MS} ms), so Discord's per-channel limit is never hit. When several
 * {@code info} events pile up they are merged into one message. Progress is coalesced: the first
 * update of a map (a colour-suppression map: of the whole map, whatever its pass), and then one every
 * {@code progressIntervalMinutes}, is posted as a new message ("Printing Sunset, map 2 of 4", "Printing Fox, top
 * layer": the words of {@link StatusWords}, no tile coordinates);
 * in between, the last progress message is edited at most every {@value #EDIT_INTERVAL_MS} ms.
 * Every event is also logged; while Discord is not ready, events stay queued for up to
 * {@value #MAX_AGE_MS} ms and are then dropped (they are already in the log).
 *
 * <p>Verbosity ({@code discord.verbose}, read on every call so {@code !map verbose} acts at once): off, {@code detail}
 * events are logged at INFO only and no progress card is posted or edited; alerts, images and {@code info} events
 * always go out.
 *
 * <p>Coordinates: the log gets the text as composed (real coordinates); the Discord card gets it converted by
 * {@link CoordFormat#discord} ({@code discord.coordinates}), before anything is truncated or merged.
 *
 * <p>Job ids: the log keeps them; the Discord card drops them ({@link #withoutJobIds}): the map's name already says
 * which job it is.
 */
public final class DiscordNotifier implements Notifier {
    /** Outbound transport; implemented over JDA by {@link IntakeBot#sink()} and by fakes in tests. */
    public interface Sink {
        boolean ready();

        /** Posts a new message; calls {@code onPosted} with its id, or null on failure (any thread). */
        void post(Card card, Consumer<String> onPosted);

        /** Edits a message posted earlier; calls {@code onDone} with false if it failed (e.g. deleted). */
        void edit(String messageId, Card card, Consumer<Boolean> onDone);
    }

    enum Kind { INFO, ALERT, IMAGE }

    record Pending(Kind kind, Card card, long createdAt) { }

    /**
     * @param part   which part of the job, in {@link StatusWords}: "top layer", "map 5 of 80", "" for a one-map job
     * @param whole  the counts are a colour-suppression map's, over all its passes
     */
    record ProgressUpdate(long seq, String jobId, String jobName, int tileX, int tileZ, long placed, long total,
                          long etaSeconds, String part, boolean whole) {
        ProgressUpdate(long seq, String jobId, String jobName, int tileX, int tileZ, int placed, int total,
                       long etaSeconds) {
            this(seq, jobId, jobName, tileX, tileZ, placed, total, etaSeconds, "", false);
        }
    }

    static final int BURST = 5;
    static final long REFILL_MS = 1_200;
    static final int MAX_QUEUE = 100;
    static final long MAX_AGE_MS = 10 * 60_000L;
    static final long EDIT_INTERVAL_MS = 60_000L;
    /** Merge queued info events when at least this many are waiting. */
    static final int MERGE_THRESHOLD = 3;
    static final int MERGE_MAX = 10;
    static final long PUMP_MS = 250;

    private final Sink sink;
    private final MapArtConfig.DiscordCfg cfg;
    private final Supplier<CoordFormat> coords;
    private final Logger log;
    private final LongSupplier clock;

    private final ConcurrentLinkedDeque<Pending> queue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicReference<ProgressUpdate> latestProgress = new AtomicReference<>();
    private final AtomicLong progressSeq = new AtomicLong();

    // pump state, guarded by this
    private double tokens = BURST;
    private long lastRefill;
    private long handledProgressSeq;
    private boolean progressInFlight;
    private String progressMessageId;
    private String progressKey;
    private long progressPostedAt;
    private long progressEditedAt;
    private ScheduledFuture<?> task;

    /** @param coords how coordinates are shown in Discord, normally {@link CoordFormat#live} */
    public DiscordNotifier(Sink sink, MapArtConfig.DiscordCfg cfg, Supplier<CoordFormat> coords, Logger log,
                           LongSupplier clock) {
        this.sink = sink;
        this.cfg = cfg;
        this.coords = coords;
        this.log = log;
        this.clock = clock;
        this.lastRefill = clock.getAsLong();
    }

    /** Starts pumping every {@value #PUMP_MS} ms on {@code scheduler}. Idempotent. */
    public synchronized void start(ScheduledExecutorService scheduler) {
        if (task != null && !task.isDone()) return;
        task = scheduler.scheduleWithFixedDelay(() -> {
            try {
                pump();
            } catch (Throwable t) {
                log.warn("Map art Discord notifier pump failed", t);
            }
        }, PUMP_MS, PUMP_MS, TimeUnit.MILLISECONDS);
    }

    /** Stops pumping and drops anything still queued. */
    public synchronized void stop() {
        if (task != null) task.cancel(false);
        task = null;
        queue.clear();
        size.set(0);
    }

    // ---------------------------------------------------------------- Notifier (any thread, non-blocking)

    @Override
    public void info(String title, String body) {
        log.info("[mapart] {}: {}", title, body);
        enqueue(Kind.INFO, card(title, body).color(Card.INFO).build());
    }

    @Override
    public void detail(String title, String body) {
        if (cfg.verbose) info(title, body);
        else log.info("[mapart] {}: {}", title, body);
    }

    @Override
    public void alert(String title, String body) {
        log.warn("[mapart] ALERT {}: {}", title, body);
        String mention = cfg.alertMention == null ? "" : cfg.alertMention.strip();
        enqueue(Kind.ALERT, card(title, body).content(mention.isEmpty() ? null : mention).color(Card.ERROR).build());
    }

    @Override
    public void image(String title, String body, String fileName, byte[] png) {
        log.info("[mapart] {}: {} ({}, {} bytes)", title, body, fileName, png == null ? 0 : png.length);
        Card.Builder b = card(title, body).color(Card.INFO);
        if (png != null) b.attachment(Names.fileName(fileName, ".png"), png);
        enqueue(Kind.IMAGE, b.build());
    }

    @Override
    public void detailImage(String title, String body, String fileName, byte[] png) {
        if (cfg.verbose) image(title, body, fileName, png);
        else log.info("[mapart] {}: {} ({}, {} bytes)", title, body, fileName, png == null ? 0 : png.length);
    }

    @Override
    public void progress(Job job, int tileX, int tileZ, int placed, int total, long etaSeconds) {
        String id = job == null ? "?" : job.id;
        String name = job == null ? "" : job.name;
        log.debug("[mapart] progress {} tile {},{}: {}/{} eta {}s", id, tileX, tileZ, placed, total, etaSeconds);
        // a colour-suppression map is one map: its card counts the blocks of all its passes
        StatusWords.Blocks whole = StatusWords.suppressionBlocks(job, tileX, placed, total);
        latestProgress.set(new ProgressUpdate(progressSeq.incrementAndGet(), id, name, tileX, tileZ,
            whole != null ? whole.placed() : placed, whole != null ? whole.total() : total, etaSeconds,
            StatusWords.part(job, tileX, tileZ), whole != null));
    }

    // ---------------------------------------------------------------- pump (executor thread)

    /** Sends what the rate limit allows. Called by the scheduler; package-visible for tests. */
    synchronized void pump() {
        long now = clock.getAsLong();
        refill(now);
        expire(now);
        if (!sink.ready()) return;
        int d = dropped.getAndSet(0);
        if (d > 0) log.warn("Map art Discord notifier dropped {} message(s) (queue full)", d);
        while (tokens >= 1) {
            Pending p = queue.pollFirst();
            if (p == null) break;
            size.decrementAndGet();
            Card card = p.card();
            if (p.kind() == Kind.INFO && size.get() + 1 >= MERGE_THRESHOLD) card = mergeInfos(p);
            send(card);
        }
        pumpProgress(now);
    }

    private void send(Card card) {
        tokens -= 1;
        try {
            sink.post(card, id -> { });
        } catch (RuntimeException e) {
            log.warn("Map art Discord send failed: {}", e.toString());
        }
    }

    private Card mergeInfos(Pending first) {
        List<Card> group = new ArrayList<>();
        group.add(first.card());
        while (group.size() < MERGE_MAX) {
            Pending next = queue.peekFirst();
            if (next == null || next.kind() != Kind.INFO) break;
            Card c = next.card();
            if (totalLen(group) + Fmt.truncate(c.description(), 600).length() + 64 > 3800) break;
            queue.pollFirst();
            size.decrementAndGet();
            group.add(c);
        }
        if (group.size() == 1) return first.card();
        StringBuilder sb = new StringBuilder();
        for (Card c : group) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("**").append(c.title() == null ? "" : c.title()).append("**");
            if (c.description() != null) sb.append('\n').append(Fmt.truncate(c.description(), 600));
        }
        return Card.builder().title(group.size() + " events").description(sb.toString()).color(Card.INFO).build();
    }

    private static int totalLen(List<Card> cards) {
        int n = 0;
        for (Card c : cards) n += (c.title() == null ? 0 : c.title().length())
            + Fmt.truncate(c.description(), 600).length() + 8;
        return n;
    }

    private void pumpProgress(long now) {
        if (!cfg.verbose) {
            progressKey = null; // verbose on again: the next update is a fresh card, not an edit of an old one
            return;
        }
        ProgressUpdate u = latestProgress.get();
        if (u == null || u.seq() == handledProgressSeq || progressInFlight || tokens < 1) return;
        String key = u.whole() ? u.jobId() : u.jobId() + "@" + u.tileX() + "," + u.tileZ();
        long interval = Math.max(1, cfg.progressIntervalMinutes) * 60_000L;
        boolean fresh = progressMessageId == null || !key.equals(progressKey) || now - progressPostedAt >= interval;
        CoordFormat f = coords.get();
        Card card = progressCard(u).mapText(t -> f.discord(t, "progress", log));
        if (fresh) {
            tokens -= 1;
            handledProgressSeq = u.seq();
            progressInFlight = true;
            progressKey = key;
            progressPostedAt = now;
            progressEditedAt = now;
            try {
                sink.post(card, id -> {
                    synchronized (this) {
                        progressInFlight = false;
                        progressMessageId = id;
                    }
                });
            } catch (RuntimeException e) {
                progressInFlight = false;
                progressMessageId = null;
                log.warn("Map art Discord progress post failed: {}", e.toString());
            }
        } else if (now - progressEditedAt >= EDIT_INTERVAL_MS) {
            tokens -= 1;
            handledProgressSeq = u.seq();
            progressInFlight = true;
            progressEditedAt = now;
            String id = progressMessageId;
            try {
                sink.edit(id, card, ok -> {
                    synchronized (this) {
                        progressInFlight = false;
                        if (!ok && id.equals(progressMessageId)) progressMessageId = null;
                    }
                });
            } catch (RuntimeException e) {
                progressInFlight = false;
                progressMessageId = null;
                log.warn("Map art Discord progress edit failed: {}", e.toString());
            }
        }
    }

    static Card progressCard(ProgressUpdate u) {
        String desc = "**" + Fmt.percent(u.placed(), u.total()) + "** " + (u.whole() ? "of the map placed (" : "placed (")
            + Fmt.count(u.placed()) + " / " + Fmt.count(u.total()) + ")\nETA " + Fmt.duration(u.etaSeconds());
        return Card.builder()
            .title("Printing " + Fmt.escape(u.jobName()) + (u.part() == null || u.part().isEmpty() ? "" : ", " + u.part()))
            .description(desc)
            .color(Card.PROGRESS)
            .build();
    }

    /** Title and body as Discord shows them; the caller has already logged the originals. */
    private Card.Builder card(String title, String body) {
        CoordFormat f = coords.get();
        return Card.builder().title(f.discord(withoutJobIds(title), title, log))
            .description(f.discord(withoutJobIds(body), title, log));
    }

    /** A job id: "j0007", or the "f" form ("f" and base 36 with a digit, a Discord message id). */
    private static final String JOB_ID = "#(?:j\\d{4,}|f(?=[0-9a-z]*[0-9])[0-9a-z]{6,13})(?![0-9a-z])";
    /** "(#id)" on its own, with the line break before it. */
    private static final Pattern ID_ALONE = Pattern.compile("\\n?\\(" + JOB_ID + "\\)");
    /** "(#id, lanes 2-3)": the id and its comma. */
    private static final Pattern ID_FIRST = Pattern.compile("(?<=\\()" + JOB_ID + ", ");
    /** "#id name": the id and its space. */
    private static final Pattern ID_WORD = Pattern.compile("(?<![\\w#])" + JOB_ID + " ?");

    /**
     * {@code text} without job ids: "#fbw204nw4zvwd Snowy Angel [0,7]" is "Snowy Angel [0,7]", "(#j0007, lanes 2-3)" is
     * "(lanes 2-3)", a line "(#j0007)" goes. Map ids, queue positions and entity ids (digits only) stay. Pure.
     */
    static String withoutJobIds(String text) {
        if (text == null || text.indexOf('#') < 0) return text;
        String t = ID_ALONE.matcher(text).replaceAll("");
        t = ID_FIRST.matcher(t).replaceAll("");
        return ID_WORD.matcher(t).replaceAll("");
    }

    private void enqueue(Kind kind, Card card) {
        Pending p = new Pending(kind, card, clock.getAsLong());
        if (size.get() >= MAX_QUEUE) {
            if (kind != Kind.ALERT || !dropOldestNonAlert()) {
                dropped.incrementAndGet();
                return;
            }
        }
        queue.addLast(p);
        size.incrementAndGet();
    }

    private boolean dropOldestNonAlert() {
        for (Iterator<Pending> it = queue.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.kind() != Kind.ALERT && queue.removeFirstOccurrence(p)) {
                size.decrementAndGet();
                dropped.incrementAndGet();
                return true;
            }
        }
        return false;
    }

    private void refill(long now) {
        long dt = now - lastRefill;
        if (dt <= 0) return;
        tokens = Math.min(BURST, tokens + (double) dt / REFILL_MS);
        lastRefill = now;
    }

    private void expire(long now) {
        Pending p;
        while ((p = queue.peekFirst()) != null && now - p.createdAt() > MAX_AGE_MS) {
            if (queue.removeFirstOccurrence(p)) size.decrementAndGet();
        }
    }

    /** Queued (unsent) events; for status displays and tests. */
    public int queued() {
        return size.get();
    }
}
