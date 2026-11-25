package io.github.digitalisp.mapart.command;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.BuildInfo;
import io.github.digitalisp.mapart.engine.EngineControl;
import io.github.digitalisp.mapart.engine.EngineControl.EngineStatus;
import io.github.digitalisp.mapart.engine.Phase;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.stock.StockIndex;
import io.github.digitalisp.mapart.stock.Unstocked;
import io.github.digitalisp.mapart.store.JobStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Queue and status logic shared by the Discord prefix commands and the ZenithProxy {@code mapart}
 * command. Works only against {@link JobStore}, {@link EngineControl} and, for {@code stop disconnect},
 * {@link ServerLink}; all formatting is pure.
 * Users see 1-based queue positions; {@link JobStore#move} takes 0-based indices.
 */
public final class QueueOps {
    /** Max jobs or stock rows in one reply (keeps embeds under Discord's 4096-char description limit). */
    public static final int MAX_ROWS = 40;
    /** Finished jobs listed under the queue. */
    public static final int RECENT = 3;
    /** Longest a {@link #stop} waits for the engine, and again for the disconnect, before it replies anyway. */
    public static final long STOP_WAIT_MS = 3_000;

    private final JobStore store;
    private final Supplier<EngineControl> engine;
    private final LongSupplier clock;
    private final ServerLink server;
    private final Executor stopThread;
    private final long stopWaitMs;

    public QueueOps(JobStore store, Supplier<EngineControl> engine, LongSupplier clock) {
        this(store, engine, clock, ServerLink.NONE, Runnable::run);
    }

    /**
     * @param server     what {@code stop disconnect} disconnects, normally {@code ZenithServerLink}
     * @param stopThread where {@link EngineControl#emergencyStop} runs for {@link #stop}: the client tick thread in
     *                   production ({@code MapArtModule::onTickThread}), so the task cancel, Baritone stop and module
     *                   restore never race a tick; {@code Runnable::run} in tests
     */
    public QueueOps(JobStore store, Supplier<EngineControl> engine, LongSupplier clock, ServerLink server,
                    Executor stopThread) {
        this(store, engine, clock, server, stopThread, STOP_WAIT_MS);
    }

    QueueOps(JobStore store, Supplier<EngineControl> engine, LongSupplier clock, ServerLink server,
             Executor stopThread, long stopWaitMs) {
        this.store = store;
        this.engine = engine;
        this.clock = clock;
        this.server = server == null ? ServerLink.NONE : server;
        this.stopThread = stopThread == null ? Runnable::run : stopThread;
        this.stopWaitMs = stopWaitMs;
    }

    // ---------------------------------------------------------------- rows

    /**
     * @param printed a colour-suppression map (one map printed on two layers): the whole percent of its blocks placed,
     *                rounded down; -1 for every other job
     */
    public record QueueRow(int position, String id, String name, int tilesX, int tilesZ, JobStatus status,
                           int tilesDone, int tilesTotal, String detail, String submitter, int printed) {
        public QueueRow(int position, String id, String name, int tilesX, int tilesZ, JobStatus status,
                        int tilesDone, int tilesTotal, String detail, String submitter) {
            this(position, id, name, tilesX, tilesZ, status, tilesDone, tilesTotal, detail, submitter, -1);
        }
    }

    /**
     * One material of a submission: needed by the job vs. currently in stock. {@code unstocked}: the base has
     * no chest for it (no material container has ever held it, {@link StockIndex#unstocked}); such a row is
     * also short, but no restock will fix it.
     */
    public record MaterialRow(String item, int needed, int stock, boolean unstocked) {
        public MaterialRow(String item, int needed, int stock) {
            this(item, needed, stock, false);
        }

        public boolean isShort() {
            return stock < needed;
        }

        public int shortBy() {
            return Math.max(0, needed - stock);
        }
    }

    /** One item of the stock index: in stock vs. needed by all pending jobs. */
    public record StockRow(String item, long stock, long queued) {
        public boolean isShort() {
            return stock < queued;
        }

        public long shortBy() {
            return Math.max(0, queued - stock);
        }
    }

    // ---------------------------------------------------------------- lookups

    /** Non-terminal jobs in queue order. */
    public List<Job> pending() {
        List<Job> out = new ArrayList<>();
        for (Job j : store.list()) if (!j.status.terminal()) out.add(j);
        return out;
    }

    /** 1-based queue position among non-terminal jobs, or 0 if {@code id} is not pending. */
    public static int position(List<Job> all, String id) {
        int pos = 0;
        for (Job j : all) {
            if (j.status.terminal()) continue;
            pos++;
            if (j.id.equals(id)) return pos;
        }
        return 0;
    }

    public int position(String id) {
        return position(store.list(), id);
    }

    /** Finds a job by exact id, case-insensitive id, or bare number ("7" → "j0007"). */
    public Optional<Job> resolve(String token) {
        if (token == null) return Optional.empty();
        String t = token.strip().replace("`", "");
        if (t.isEmpty()) return Optional.empty();
        Optional<Job> exact = store.get(t);
        if (exact.isPresent()) return exact;
        List<Job> all = store.list();
        for (Job j : all) if (j.id.equalsIgnoreCase(t)) return Optional.of(j);
        if (t.matches("\\d{1,9}")) {
            int n = Integer.parseInt(t);
            Job found = null;
            for (Job j : all) {
                String digits = j.id.replaceAll("\\D", "");
                if (!digits.isEmpty() && digits.length() <= 9 && Integer.parseInt(digits) == n) {
                    if (found != null) return Optional.empty(); // ambiguous
                    found = j;
                }
            }
            return Optional.ofNullable(found);
        }
        return Optional.empty();
    }

    public List<QueueRow> queueRows() {
        EngineStatus s = engineStatus();
        List<QueueRow> rows = new ArrayList<>();
        int pos = 0;
        for (Job j : store.list()) {
            if (j.status.terminal()) continue;
            pos++;
            int done = 0;
            for (Job.TileState t : j.tiles) if (t.status == TileStatus.STORED) done++;
            int total = j.tiles.isEmpty() ? j.tilesX * j.tilesZ : j.tiles.size();
            String detail = j.statusDetail == null ? "" : j.statusDetail;
            boolean current = s != null && j.id.equals(s.jobId());
            if (current) {
                // the same words as the Discord presence (StatusWords), then the platform
                String live = StatusWords.line(s, j, clock.getAsLong())
                    + (s.platformId() != null ? " · on " + s.platformId() : "");
                detail = detail.isEmpty() ? live : live + "; " + detail;
            }
            StatusWords.Blocks b = StatusWords.suppressionBlocks(j, current ? s : null);
            int printed = b == null ? -1 : b.total() <= 0 ? 0 : StatusWords.percent((double) b.placed() / b.total());
            rows.add(new QueueRow(pos, j.id, j.name, j.tilesX, j.tilesZ, j.status, done, total, detail,
                j.submitterName, printed));
        }
        return rows;
    }

    /** The last {@code n} finished jobs (most recent last), from the tail of {@link JobStore#list()}. */
    public List<Job> recentFinished(int n) {
        List<Job> done = new ArrayList<>();
        for (Job j : store.list()) if (j.status.terminal()) done.add(j);
        return done.subList(Math.max(0, done.size() - n), done.size());
    }

    /** Needed (whole job) vs. in-stock rows, largest need first; items the base has no chest for are flagged. */
    public static List<MaterialRow> materialRows(Map<String, Integer> needed, StockIndex stock) {
        List<MaterialRow> rows = new ArrayList<>();
        Set<String> noChest = stock == null ? Set.of() : stock.unstocked(needed.keySet());
        needed.forEach((item, n) -> rows.add(new MaterialRow(item, n, stock == null ? 0 : stock.total(item),
            noChest.contains(item))));
        rows.sort(Comparator.comparingInt(MaterialRow::needed).reversed().thenComparing(MaterialRow::item));
        return rows;
    }

    /** Stock rows matching {@code filter} (substring, case-insensitive); shortages first, then by name. */
    public List<StockRow> stockRows(String filter) {
        Map<String, Long> queued = queuedMaterials();
        Map<String, Integer> totals = store.stock().totals();
        String f = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        TreeSet<String> items = new TreeSet<>(totals.keySet());
        items.addAll(queued.keySet());
        List<StockRow> rows = new ArrayList<>();
        for (String item : items) {
            if (!f.isEmpty() && !item.toLowerCase(Locale.ROOT).contains(f)) continue;
            rows.add(new StockRow(item, totals.getOrDefault(item, 0), queued.getOrDefault(item, 0L)));
        }
        rows.sort(Comparator.comparing((StockRow r) -> !r.isShort())
            .thenComparing(Comparator.comparingLong(StockRow::shortBy).reversed())
            .thenComparing(StockRow::item));
        return rows;
    }

    // ---------------------------------------------------------------- actions

    public Reply queue() {
        return Reply.info("Map art queue", formatQueue(queueRows(), recentFinished(RECENT)));
    }

    public Reply status() {
        EngineControl e = engine.get();
        if (e == null) return Reply.warn("Map art status", "Engine not loaded.\n" + queueSummary() + "\n" + buildLine());
        EngineStatus s;
        try {
            s = e.status();
        } catch (RuntimeException ex) {
            return Reply.error("Map art status", "Engine status failed: " + ex);
        }
        Reply.Tone tone = s == null || s.paused() || s.phase() == Phase.BLOCKED || !s.enabled()
            ? Reply.Tone.WARN : Reply.Tone.INFO;
        Job job = s == null || s.jobId() == null ? null : store.get(s.jobId()).orElse(null);
        return new Reply(tone, "Map art status", formatStatus(s, job, clock.getAsLong()) + "\n" + queueSummary() + "\n"
            + buildLine());
    }

    /** Which build runs ({@link BuildInfo}): "**Build:** 0.1.0 (a1b2c3d), built 2026-10-11 03:57 UTC". */
    static String buildLine() {
        return "**Build:** " + Fmt.escape(BuildInfo.current().describe());
    }

    public Reply stock(String filter) {
        StockIndex stock = store.stock();
        List<StockRow> rows = stockRows(filter);
        String header;
        int containers;
        long oldest;
        synchronized (stock) {
            containers = stock.containers.size();
        }
        oldest = stock.oldestScan();
        boolean filtered = filter != null && !filter.isBlank();
        String title = filtered ? "Material stock: " + filter.strip() : "Material stock";
        if (containers == 0) return Reply.info(title, "No chests scanned yet. Use `scan`.");
        String footer = "\n_" + containers + " chests indexed; the oldest was last looked into "
            + Fmt.duration(Math.max(0, (clock.getAsLong() - oldest) / 1000)) + " ago._";
        if (rows.isEmpty()) return Reply.info(title, "No matching items." + footer);
        List<StockRow> shorts = rows.stream().filter(StockRow::isShort).toList();
        List<StockRow> enough = rows.stream().filter(r -> !r.isShort() && r.queued() > 0).toList();
        List<StockRow> unused = rows.stream().filter(r -> r.queued() == 0).toList();
        StringBuilder b = new StringBuilder();
        if (pending().isEmpty()) {
            b.append("Nothing is queued.\n");
        } else {
            // live 2026-10-15: the owner could not read the table of every item with a "per map" column
            b.append("Each map's blocks come back after it is cleared, so the chests only need enough for the"
                + " biggest map left.\n");
            // by default only what needs more stock (the owner, 2026-10-15); a filter shows every matching item
            if (shorts.isEmpty()) {
                b.append(filtered ? "**Nothing matching is short.**\n" : "**Nothing is short.** Every material the queued"
                    + " maps use is in stock.\n");
            } else {
                b.append("**Short (").append(shorts.size()).append("):** in stock / needed\n").append(stockTable(shorts, true, MAX_ROWS))
                    .append('\n');
            }
            if (filtered && !enough.isEmpty()) {
                b.append("**Enough (").append(enough.size()).append("):** in stock / needed\n").append(stockTable(enough, false, MAX_ROWS))
                    .append('\n');
            }
        }
        if (filtered && !unused.isEmpty()) {
            b.append("**In stock, not used by the queued maps (").append(unused.size()).append("):** ");
            List<String> names = new ArrayList<>();
            for (StockRow r : unused) names.add(itemName(r.item()) + " " + Fmt.count(r.stock()));
            b.append(String.join(", ", names)).append('\n');
        }
        if (!filtered) b.append("`stock <name>` shows any item, e.g. `stock terracotta`.\n");
        return new Reply(shorts.isEmpty() ? Reply.Tone.INFO : Reply.Tone.WARN, title, b.toString().strip() + footer);
    }

    /** "gray terracotta" for "minecraft:gray_terracotta". */
    static String itemName(String id) {
        return Fmt.item(id).replace('_', ' ');
    }

    /**
     * The stock command's rows, one item per line for a phone's width: "**black carpet**: 10 / 300, 290 missing" (in
     * stock / needed, what the biggest map left needs, and for short items how many are missing: the owner's wording,
     * 2026-10-15). The section header says which number is which.
     */
    static String stockTable(List<StockRow> rows, boolean missing, int maxRows) {
        List<String> lines = new ArrayList<>();
        int shown = 0;
        for (StockRow r : rows) {
            if (shown++ >= maxRows) {
                lines.add("… and " + (rows.size() - maxRows) + " more; add a filter, e.g. `stock carpet`");
                break;
            }
            lines.add("**" + Fmt.escape(itemName(r.item())) + "**: " + Fmt.count(r.stock()) + " / " + Fmt.count(r.queued())
                + (missing ? ", " + Fmt.count(r.shortBy()) + " missing" : ""));
        }
        return String.join("\n", lines);
    }

    public Reply cancel(String token) {
        Optional<Job> o = resolve(token);
        if (o.isEmpty()) return unknown(token);
        Job j = o.get();
        if (j.status.terminal()) return Reply.error("Cannot cancel " + j.id, j.id + " is already " + j.status + ".");
        boolean wasActive = j.status == JobStatus.ACTIVE;
        EngineStatus s = engineStatus();
        boolean current = s != null && j.id.equals(s.jobId());
        if (!store.cancel(j.id)) return Reply.error("Cannot cancel " + j.id, "The job store refused the change.");
        queueChanged();
        String note;
        if (current && s.paused()) {
            boolean safety = s.pauseReason() != null && s.pauseReason().startsWith("SAFETY STOP");
            note = "\nThe engine is " + (safety ? "safety-stopped" : "paused") + ": it will clear the platform after resume.";
        } else if (current && s.phase() == Phase.BLOCKED) {
            note = "\nThe job was BLOCKED: the engine leaves BLOCKED and clears the platform now (no resume needed).";
        } else {
            note = wasActive ? "\nThe job was active; the engine stops it at the next safe point." : "";
        }
        return Reply.ok("Cancelled " + j.id, Fmt.escape(j.name) + note);
    }

    /** Moves a job to 1-based {@code position} among pending jobs (clamped to the queue length). */
    public Reply move(String token, int position) {
        Optional<Job> o = resolve(token);
        if (o.isEmpty()) return unknown(token);
        Job j = o.get();
        if (j.status.terminal()) return Reply.error("Cannot move " + j.id, j.id + " is already " + j.status + ".");
        if (position < 1) return Reply.error("Cannot move " + j.id, "Position must be 1 or more (1 = next).");
        int count = pending().size();
        int target = Math.min(position, Math.max(1, count));
        if (!store.move(j.id, target - 1)) return Reply.error("Cannot move " + j.id, "The job store refused the change.");
        queueChanged();
        String note = target == 1 && hasActiveOther(j.id)
            ? "\nThe active job keeps building until it is finished; this one is next after it." : "";
        return Reply.ok("Moved " + j.id + " to #" + position(j.id), Fmt.escape(j.name) + note);
    }

    /**
     * Gives a job a new display name ({@link Names#sanitize}); maps renamed from now on carry it. Live 2026-10-15 the
     * owner wanted a queued job renamed without uploading it again.
     */
    public Reply rename(String token, String name) {
        Optional<Job> o = resolve(token);
        if (o.isEmpty()) return unknown(token);
        Job j = o.get();
        if (j.status.terminal()) return Reply.error("Cannot rename " + j.id, j.id + " is already " + j.status + ".");
        String n = Names.sanitize(name);
        if (n.isEmpty()) return Reply.error("Cannot rename " + j.id, "Nothing of the name is left once cleaned.");
        String old = j.name == null ? "" : j.name;
        j.name = n;
        store.save(j);
        return Reply.ok("Renamed " + j.id, Fmt.escape(old) + " → " + Fmt.escape(n));
    }

    /** FAILED or WAITING_MATERIALS → QUEUED; failed tiles go back to PENDING. */
    public Reply retry(String token) {
        Optional<Job> o = resolve(token);
        if (o.isEmpty()) return unknown(token);
        Job j = o.get();
        if (j.status != JobStatus.FAILED && j.status != JobStatus.WAITING_MATERIALS)
            return Reply.error("Cannot retry " + j.id,
                j.id + " is " + j.status + "; only FAILED or WAITING_MATERIALS jobs can be retried.");
        JobStatus before = j.status;
        j.status = JobStatus.QUEUED;
        j.statusDetail = "";
        int reset = 0;
        for (Job.TileState t : j.tiles) {
            if (t.status == TileStatus.FAILED) {
                t.status = TileStatus.PENDING;
                reset++;
            }
        }
        store.save(j);
        queueChanged();
        return Reply.ok("Requeued " + j.id, Fmt.escape(j.name) + " was " + before + ", now QUEUED at #" + position(j.id)
            + (reset > 0 ? " (" + reset + " failed tile(s) reset)" : "") + ".");
    }

    public Reply pause(String reason) {
        return pause(reason, "");
    }

    /** @param cmd the command prefix as the caller's users type it ({@code !map}, {@code mapart}); "" for none */
    public Reply pause(String reason, String cmd) {
        EngineControl e = engine.get();
        if (e == null) return Reply.error("Cannot pause", "Engine not loaded.");
        String r = reason == null || reason.isBlank() ? "paused by command" : reason.strip();
        e.pause(r);
        return Reply.warn("Pausing", "Reason: " + Fmt.escape(r)
            + "\nThe bot pauses after its current action, typically within one tick (1/20 s).\nFor an immediate halt use `"
            + command(cmd, "stop") + "`. `" + command(cmd, "resume") + "` continues.");
    }

    /**
     * The kill switch, open to everyone: {@link EngineControl#emergencyStop} on the stop thread (the client tick thread:
     * after the current tick, before the next; its loop keeps running while bot ticks are stopped, and without a client
     * it runs at once), then, once the stop is applied, with {@code disconnect} the server connection is dropped through
     * {@link ServerLink} without auto-reconnect. Each step is started from a short-lived daemon thread (never a Netty
     * thread) and waited for at most {@link #STOP_WAIT_MS}: a stuck engine can neither hold the reply nor keep the
     * disconnect from happening.
     *
     * @param by  who stopped it, e.g. "jared2013 (Discord)"; ends up in the pause reason
     * @param cmd the command prefix as the caller's users type it ({@code !map}, {@code mapart}); "" for none
     */
    public Reply stop(String by, boolean disconnect, String cmd) {
        String who = by == null || by.isBlank() ? "command" : Fmt.truncate(by.strip(), 100);
        String reason = "manual stop by " + who;
        List<String> problems = new ArrayList<>();
        EngineControl e = engine.get();
        if (e == null) {
            problems.add("Engine not loaded; there was no bot to halt.");
        } else {
            Bounded<String> halted = bounded("mapart-stop", () -> {
                CompletableFuture<Void> done = new CompletableFuture<>();
                stopThread.execute(() -> {
                    try {
                        e.emergencyStop(reason);
                        done.complete(null);
                    } catch (Throwable t) {
                        done.completeExceptionally(t);
                    }
                });
                try {
                    done.get();
                } catch (ExecutionException ex) {
                    throw ex.getCause() instanceof Exception c ? c : ex;
                }
                return "";
            });
            if (halted.timedOut()) problems.add("The engine did not confirm the halt within " + seconds(stopWaitMs)
                + " (a tick may be stuck); the halt still runs once the engine is free.");
            else if (halted.error() != null) problems.add("Engine stop failed: " + halted.error());
        }
        String link = null;
        if (disconnect) {
            Bounded<String> d = bounded("mapart-disconnect", server::disconnect);
            if (d.timedOut()) problems.add("Disconnect requested but not confirmed within " + seconds(stopWaitMs) + ".");
            else if (d.error() != null) problems.add("Disconnect failed: " + d.error());
            else link = d.value();
        }
        String resume = "`" + command(cmd, "resume") + "` to continue.";
        if (problems.isEmpty()) {
            String body = "Bot halted, Baritone stopped, pause saved. " + resume + "\nBy " + Fmt.escape(who) + ".";
            if (link != null) body += "\n" + Fmt.escape(link) + " Reconnect with ZenithProxy `connect`; the bot stays "
                + "paused until resumed.";
            return Reply.warn("STOPPED", body);
        }
        String escalate = disconnect
            ? "Next: ZenithProxy `disconnect`, or kill the ZenithProxy process."
            : "Next: `" + command(cmd, "stop disconnect") + "`, ZenithProxy `disconnect`, or kill the ZenithProxy process.";
        return Reply.error("STOP INCOMPLETE", String.join("\n", problems)
            + (link != null ? "\n" + Fmt.escape(link) : "") + "\n" + escalate);
    }

    public Reply resume() {
        EngineControl e = engine.get();
        if (e == null) return Reply.error("Cannot resume", "Engine not loaded.");
        e.resume();
        return Reply.ok("Resuming", "Pause and BLOCKED state cleared.");
    }

    public Reply scan() {
        EngineControl e = engine.get();
        if (e == null) return Reply.error("Cannot scan", "Engine not loaded.");
        e.requestStockScan();
        return Reply.ok("Stock scan requested", "All material containers are rescanned between tasks.");
    }

    // ---------------------------------------------------------------- formatting

    static String statusLabel(JobStatus s) {
        return s == JobStatus.WAITING_MATERIALS ? "WAITING" : s.name();
    }

    /**
     * The local queue for a phone's width: one short block per job, "**1. Pine Tree** (jared2013)", then
     * "`j0002` · 2x1 · ACTIVE · 1/2 maps" (a colour-suppression map, which is one map: "`j0004` · colour suppression ·
     * ACTIVE · 62% printed"), then what the engine does on it in {@link StatusWords}, one part per line; then the totals
     * and the recently finished, one per line. No table: Discord's phone app wraps a code block after ~32 characters.
     */
    public static String formatQueue(List<QueueRow> rows, List<Job> recent) {
        StringBuilder sb = new StringBuilder();
        if (rows.isEmpty()) {
            sb.append("Queue is empty.");
        } else {
            List<String> blocks = new ArrayList<>();
            for (QueueRow r : rows) {
                boolean cs = r.printed() >= 0;
                blocks.add(entry(r.position(), r.name(), r.submitter(), r.id(),
                    cs ? "color suppression" : r.tilesX() + "x" + r.tilesZ(), statusLabel(r.status()),
                    cs ? (r.printed() == 0 ? "not started" : r.printed() + "% printed")
                        : r.tilesDone() + "/" + r.tilesTotal() + (r.tilesTotal() == 1 ? " map" : " maps"), r.detail()));
            }
            sb.append(entries(blocks));
            long active = rows.stream().filter(r -> r.status() == JobStatus.ACTIVE).count();
            long waiting = rows.stream().filter(r -> r.status() == JobStatus.WAITING_MATERIALS).count();
            sb.append("\n\n").append(rows.size()).append(" pending");
            if (active > 0) sb.append(", ").append(active).append(" active");
            if (waiting > 0) sb.append(", ").append(waiting).append(" waiting for materials");
            sb.append('.');
        }
        if (recent != null && !recent.isEmpty()) {
            sb.append("\n**Recently finished:**");
            for (int i = recent.size() - 1; i >= 0; i--) {
                Job j = recent.get(i);
                sb.append("\n`").append(Fmt.inCode(j.id)).append("` ").append(Fmt.escape(j.name))
                    .append(" · ").append(j.status);
            }
        }
        return sb.toString();
    }

    /** Characters of the queue's job blocks: with the totals and the recently finished under Discord's 4096. */
    static final int MAX_QUEUE_BLOCK = 3_300;
    /** A job's name (and its submitter) in a queue block. */
    static final int NAME_MAX = 40;
    /** One part of what the engine does on a job, a line of its own. */
    static final int DETAIL_MAX = 80;

    /**
     * One job of a queue: "**1. Pine Tree** (jared2013)", "`j0002` · 2x1 · ACTIVE · 1/2 maps", and each "; " part of
     * {@code detail} on a line of its own.
     */
    static String entry(int position, @Nullable String name, @Nullable String submitter, String id, String size,
                        String status, String progress, @Nullable String detail) {
        StringBuilder b = new StringBuilder("**").append(position).append(". ")
            .append(Fmt.escape(Fmt.truncate(name == null || name.isBlank() ? id : name, NAME_MAX))).append("**");
        if (submitter != null && !submitter.isBlank())
            b.append(" (").append(Fmt.escape(Fmt.truncate(submitter, NAME_MAX))).append(')');
        b.append("\n`").append(Fmt.inCode(id)).append("` · ").append(size).append(" · ").append(status).append(" · ")
            .append(progress);
        if (detail != null) for (String part : detail.split("; ")) {
            if (!part.isBlank()) b.append('\n').append(Fmt.escape(Fmt.truncate(part.strip(), DETAIL_MAX)));
        }
        return b.toString();
    }

    /** The job blocks, a blank line apart, cut with "… and N more" at {@link #MAX_ROWS} or {@link #MAX_QUEUE_BLOCK}. */
    static String entries(List<String> blocks) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String block : blocks) {
            int size = block.length() + (sb.isEmpty() ? 0 : 2);
            if (shown >= MAX_ROWS || (shown > 0 && sb.length() + size > MAX_QUEUE_BLOCK)) {
                sb.append("\n\n… and ").append(blocks.size() - shown).append(" more");
                break;
            }
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(block);
            shown++;
        }
        return sb.toString();
    }

    public static String formatStatus(EngineStatus s, long now) {
        return formatStatus(s, null, now);
    }

    /**
     * The status reply: the state, what the bot does and how far it is in the words of the Discord presence
     * ({@link StatusWords}: "**Doing:** Printing · top layer · 62% · ~2h 10m left"), the job, the block count, the
     * engine's phase and note. A colour-suppression job is one map: no tile and no count of maps, the blocks of the
     * whole map. A job of several maps: "map 5 of 80" in the Doing line.
     *
     * @param job the job {@code s} names, null when not known (the tile's own count is shown then)
     */
    public static String formatStatus(EngineStatus s, @Nullable Job job, long now) {
        if (s == null) return "**State:** unknown (engine returned no status)";
        StringBuilder sb = new StringBuilder();
        String state;
        if (!s.enabled()) state = "disabled (builder off; uploads are still queued)";
        else if (s.paused()) state = "paused" + (s.pauseReason() != null && !s.pauseReason().isBlank()
            ? ": " + Fmt.escape(s.pauseReason()) : "");
        else if (s.phase() == Phase.BLOCKED) state = "BLOCKED, needs a human (use `resume` when fixed)";
        else if (s.phase() == null || s.phase() == Phase.IDLE) state = "idle";
        else state = "running";
        sb.append("**State:** ").append(state);
        if (s.jobId() != null || (s.phase() != null && s.phase() != Phase.IDLE && s.phase() != Phase.BLOCKED))
            sb.append("\n**Doing:** ").append(Fmt.escape(StatusWords.line(s, job, now)));
        if (s.phase() != null) {
            sb.append("\n**Phase:** ").append(s.phase().name());
            if (s.phaseSince() > 0) sb.append(" for ").append(Fmt.duration(Math.max(0, (now - s.phaseSince()) / 1000)));
        }
        if (s.jobId() != null) {
            boolean cs = job != null && job.isSuppression();
            sb.append("\n**Job:** `").append(Fmt.inCode(s.jobId())).append("` ")
                .append(Fmt.escape(s.jobName() == null ? "" : s.jobName()));
            if (cs) sb.append(", color suppression (one map)");
            if (s.platformId() != null) sb.append(" on ").append(Fmt.escape(s.platformId()));
            StatusWords.Blocks b = StatusWords.suppressionBlocks(job, s);
            if (b != null && b.total() > 0) sb.append("\n**Placed:** ").append(Fmt.count(b.placed())).append(" / ")
                .append(Fmt.count(b.total())).append(" (").append(Fmt.percent(b.placed(), b.total()))
                .append(" of the whole map)");
            else if (s.total() > 0) sb.append("\n**Placed:** ").append(Fmt.count(s.placed())).append(" / ")
                .append(Fmt.count(s.total())).append(" (").append(Fmt.percent(s.placed(), s.total())).append(')');
        }
        if (s.note() != null && !s.note().isBlank()) sb.append("\n**Note:** ").append(Fmt.escape(s.note()));
        return sb.toString();
    }

    private String queueSummary() {
        List<Job> p = pending();
        long waiting = p.stream().filter(j -> j.status == JobStatus.WAITING_MATERIALS).count();
        return "**Queue:** " + p.size() + " pending" + (waiting > 0 ? " (" + waiting + " waiting for materials)" : "");
    }

    /**
     * A submission's materials for a phone's width, at most {@code maxChars}: a header saying which number is which,
     * then one item per line, "! **black carpet**: 1,200 / 4,000, 2,800 missing" (in stock / needed; short items marked
     * "!", or {@link Unstocked#MARK} when the base has no chest for them), cut with "… and N more".
     */
    public static String formatMaterials(List<MaterialRow> rows, int maxChars) {
        String header = "**Materials** (in stock / needed):";
        StringBuilder sb = new StringBuilder(header);
        for (int i = 0; i < rows.size(); i++) {
            MaterialRow r = rows.get(i);
            String mark = r.unstocked() ? Unstocked.MARK + " " : r.isShort() ? "! " : "";
            String line = mark + "**" + Fmt.escape(itemName(r.item())) + "**: " + Fmt.count(r.stock()) + " / "
                + Fmt.count(r.needed()) + (r.isShort() ? ", " + Fmt.count(r.shortBy()) + " missing" : "");
            String more = "… and " + (rows.size() - i) + " more";
            boolean last = i == rows.size() - 1;
            if (sb.length() + 1 + line.length() + (last ? 0 : 1 + more.length()) > maxChars) {
                sb.append('\n').append(more);
                break;
            }
            sb.append('\n').append(line);
        }
        return sb.toString();
    }

    /** item → count summed over every pending job. */
    public Map<String, Long> queuedMaterials() {
        List<Job> jobs = pending();
        // each map's blocks come back after its clear: the chests must hold what the largest map left needs, not the
        // sum (live 2026-10-15, the owner: "it only matters what one map needs at a time because everything is reused")
        Map<String, Integer> peak = null;
        try {
            EngineControl e = engine.get();
            if (e != null) peak = e.largestMapNeeds(jobs);
        } catch (RuntimeException ex) {
            peak = null;
        }
        Map<String, Long> m = new LinkedHashMap<>();
        if (peak != null && !peak.isEmpty()) {
            peak.forEach((k, v) -> m.put(k, (long) v));
            return m;
        }
        for (Job j : jobs) j.materials.forEach((k, v) -> m.merge(k, (long) v, Long::sum));
        return m;
    }

    /**
     * The map counter button the engine found in {@code site.mapCounterColumn} this run, for {@code mapart site}; null
     * without an engine, before it was found, or when the engine throws.
     */
    public @Nullable Pos mapCounterButton() {
        try {
            EngineControl e = engine.get();
            return e == null ? null : e.mapCounterButton();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The engine's {@link EngineControl#rocketChestReport}; "" without an engine or when it throws. */
    public String rocketChestReport() {
        try {
            EngineControl e = engine.get();
            String r = e == null ? null : e.rocketChestReport();
            return r == null ? "" : r;
        } catch (RuntimeException ex) {
            return "";
        }
    }

    // ---------------------------------------------------------------- internals

    private EngineStatus engineStatus() {
        EngineControl e = engine.get();
        if (e == null) return null;
        try {
            return e.status();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean hasActiveOther(String id) {
        for (Job j : pending()) if (j.status == JobStatus.ACTIVE && !j.id.equals(id)) return true;
        return false;
    }

    private void queueChanged() {
        EngineControl e = engine.get();
        if (e != null) e.queueChanged();
    }

    private static Reply unknown(String token) {
        return Reply.error("Unknown job", "No job matches `" + Fmt.inCode(token == null ? "" : token) + "`. See `queue`.");
    }

    /** "!map stop", "mapart resume", or just "stop" without a prefix. */
    private static String command(String prefix, String sub) {
        return prefix == null || prefix.isBlank() ? sub : prefix.strip() + " " + sub;
    }

    private static String seconds(long ms) {
        return (ms % 1000 == 0 ? String.valueOf(ms / 1000) : String.format(Locale.ROOT, "%.1f", ms / 1000.0)) + " s";
    }

    /** Result of {@link #bounded}: a value, an error, or neither when the wait ran out. */
    private record Bounded<T>(T value, String error, boolean timedOut) { }

    /**
     * Runs {@code work} on a new daemon thread and waits at most {@link #stopWaitMs} for it. On a timeout the thread
     * keeps going (the work is not interrupted: a stop that is late is still wanted).
     */
    private <T> Bounded<T> bounded(String threadName, Callable<T> work) {
        FutureTask<T> f = new FutureTask<>(work);
        Thread t = new Thread(f, threadName);
        t.setDaemon(true);
        t.start();
        try {
            return new Bounded<>(f.get(stopWaitMs, TimeUnit.MILLISECONDS), null, false);
        } catch (TimeoutException ex) {
            return new Bounded<>(null, null, true);
        } catch (ExecutionException ex) {
            Throwable c = ex.getCause() != null ? ex.getCause() : ex;
            return new Bounded<>(null, c.toString(), false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Bounded<>(null, null, true);
        }
    }
}
