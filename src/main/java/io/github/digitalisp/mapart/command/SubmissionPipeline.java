package io.github.digitalisp.mapart.command;

import org.slf4j.Logger;
import io.github.digitalisp.mapart.bot.impl.TopFaceRules;
import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.catalog.Eyeblossoms;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.EngineControl;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.NamedFile;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.SuppressionArt;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.model.ValidationResult;
import io.github.digitalisp.mapart.plan.DefaultSubmissionService;
import io.github.digitalisp.mapart.plan.SameColourSwap;
import io.github.digitalisp.mapart.plan.SubmissionService;
import io.github.digitalisp.mapart.plan.SubmissionService.SubmissionException;
import io.github.digitalisp.mapart.render.ColorResolver;
import io.github.digitalisp.mapart.render.Renderer;
import io.github.digitalisp.mapart.render.SuppressionMaps;
import io.github.digitalisp.mapart.store.JobStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Intake used by Discord uploads and {@code mapart submit <path>}: parse → validate → create job →
 * preview → {@link EngineControl#queueChanged()}. Blocking (parsing, rendering, file I/O): call it on
 * an executor thread, never on the client tick thread or a JDA event thread.
 */
public final class SubmissionPipeline {
    /** Longest preview edge in pixels; the render scale is chosen to stay at or under it. */
    public static final int PREVIEW_MAX_PX = 1024;
    public static final String PREVIEW_FILE = "preview.png";

    public record Request(List<NamedFile> files, String requestedName, String submitterId, String submitterName,
                          String channelId, String messageId) { }

    public sealed interface Outcome permits Accepted, Rejected { }

    /** @param previewPng null when rendering failed (see warnings) */
    public record Accepted(Job job, int queuePosition, int totalBlocks, List<QueueOps.MaterialRow> materials,
                           boolean stockScanned, List<String> warnings, byte[] previewPng) implements Outcome { }

    public record Rejected(List<String> reasons, List<String> warnings) implements Outcome { }

    private final SubmissionService submissions;
    private final BlockCatalog catalog;
    private final MapArtConfig config;
    private final JobStore store;
    private final Renderer renderer;
    private final ColorResolver colors;
    private final Supplier<EngineControl> engine;
    private final Logger log;

    public SubmissionPipeline(SubmissionService submissions, BlockCatalog catalog, MapArtConfig config, JobStore store,
                              Renderer renderer, ColorResolver colors, Supplier<EngineControl> engine, Logger log) {
        this.submissions = submissions;
        this.catalog = catalog;
        this.config = config;
        this.store = store;
        this.renderer = renderer;
        this.colors = colors;
        this.engine = engine;
        this.log = log;
    }

    /**
     * {@link #submit} for every job the upload stands for, in queue order: one, or for an upload of several
     * colour-suppression files ({@link SubmissionService#split}) one per file, named "&lt;name&gt; &lt;a&gt;,&lt;b&gt;" after the
     * file's "-&lt;a&gt;_&lt;b&gt;" suffix (the name from the upload message, else the zip's). Each part is read, checked and
     * queued by itself: a part that is refused does not hold back the others, and its outcome names its file.
     */
    public List<Outcome> submitAll(Request r) {
        if (r.files() == null || r.files().isEmpty()) return List.of(submit(r));
        SubmissionService.Split split;
        try {
            split = submissions.split(r.files(), config.site);
        } catch (SubmissionException e) {
            return List.of(new Rejected(List.of(e.getMessage()), List.of()));
        } catch (RuntimeException e) {
            log.warn("Map art submission could not be looked at for parts; reading it as one job", e);
            split = null;
        }
        if (split == null) return List.of(submit(r));
        log.info("Map art upload by {}: {} color-suppression maps, one job each", r.submitterName(),
            split.parts().size());
        List<Outcome> out = new ArrayList<>();
        for (SubmissionService.Part p : split.parts())
            out.add(submit(new Request(List.of(p.file()), partName(r.requestedName(), split.base(), p.label()),
                r.submitterId(), r.submitterName(), r.channelId(), r.messageId())));
        return out;
    }

    /**
     * The job name of one map of an upload of several: the upload's name (the message's, else {@code base}), a space
     * and {@code label}; the label alone when there is no name. Within {@link Names#MAX_NAME}: the name is cut, never
     * the label (it is what tells the maps apart in game).
     */
    static String partName(String requested, String base, String label) {
        String l = Names.sanitize(label);
        String n = Names.sanitize(requested);
        if (n.isEmpty()) n = Names.sanitize(base);
        if (n.isEmpty()) return l.isEmpty() ? "map" : l;
        if (l.isEmpty()) return n;
        int room = Names.MAX_NAME - l.codePointCount(0, l.length()) - 1;
        if (room < 1) return l;
        if (n.codePointCount(0, n.length()) > room) n = n.substring(0, n.offsetByCodePoints(0, room)).strip();
        return n + " " + l;
    }

    public Outcome submit(Request r) {
        if (r.files() == null || r.files().isEmpty())
            return new Rejected(List.of("No .nbt or .zip files were attached."), List.of());
        ParsedSubmission parsed;
        try {
            parsed = submissions.parse(r.files(), config.site);
        } catch (SubmissionException e) {
            return new Rejected(List.of(e.getMessage()), List.of());
        } catch (RuntimeException e) {
            log.warn("Map art submission parse failed", e);
            return new Rejected(List.of("Could not read the files (" + e.getClass().getSimpleName() + ": "
                + e.getMessage() + ")."), List.of());
        }
        List<String> warnings = new ArrayList<>(parsed.warnings() == null ? List.of() : parsed.warnings());
        List<Job.Substitution> swaps = List.of();
        if (config.map.substituteSameColour && !parsed.isSuppression()) {
            try {
                SameColourSwap.Result sw = SameColourSwap.apply(parsed, catalog, config.site, store.stock(),
                    config.map.substituteDeny, swapOptions(c -> true));
                parsed = sw.submission();
                swaps = sw.substitutions();
            } catch (RuntimeException e) {
                log.warn("Map art same-color substitution failed; building the art as submitted", e);
            }
        }
        ValidationResult v;
        try {
            v = submissions.validate(parsed, catalog, config.site);
        } catch (RuntimeException e) {
            log.warn("Map art submission validation failed", e);
            return new Rejected(List.of("Validation failed (" + e.getClass().getSimpleName() + ": "
                + e.getMessage() + ")."), warnings);
        }
        if (v.warnings() != null) warnings.addAll(v.warnings());
        if (!v.ok()) return new Rejected(v.errors(), warnings);
        if (v.platformIds() == null || v.platformIds().isEmpty())
            return new Rejected(List.of("No enabled platform can build every block of this art."), warnings);

        String blossoms = eyeblossomNote(parsed, config.map.eyeblossomWaitMinutes);
        if (blossoms != null) warnings.add(blossoms);

        String name = Names.displayName(r.requestedName(), parsed.baseName());
        Job job;
        try {
            job = store.create(parsed, v, name, r.submitterId(), r.submitterName());
            if (parsed.isSuppression()) {
                job.suppression = new ArrayList<>();
                for (SuppressionArt.Pass p : parsed.suppression().passes())
                    job.suppression.add(new Job.SuppressionPass(p.layer(), p.level(), p.noobline()));
            }
            if (r.channelId() != null || !swaps.isEmpty() || parsed.isSuppression()) {
                if (r.channelId() != null) {
                    job.discordChannelId = r.channelId();
                    job.discordMessageId = r.messageId();
                }
                job.substitutions = new ArrayList<>(swaps);
                store.save(job);
            }
        } catch (IOException | RuntimeException e) {
            log.error("Map art job could not be saved", e);
            return new Rejected(List.of("The job could not be saved (" + e.getMessage() + ")."), warnings);
        }
        if (parsed.isSuppression())
            log.info("Map art job {} \"{}\" queued by {}: color suppression, {} blocks in {} passes", job.id, job.name,
                r.submitterName(), parsed.suppression().count(), parsed.tilesX());
        else
            log.info("Map art job {} \"{}\" queued by {}: {}x{} tiles", job.id, job.name, r.submitterName(),
                parsed.tilesX(), parsed.tilesZ());
        String swapped = SameColourSwap.line(job.substitutions, 2000);
        if (swapped != null) log.info("Map art job {}: {}", job.id, swapped);
        EngineControl e = engine.get();
        if (e != null) e.queueChanged();

        int blocks = 0;
        for (TileArt t : parsed.tiles().values()) blocks += t.nonEmptyCount();
        byte[] png = null;
        try {
            png = parsed.isSuppression()
                ? SuppressionMaps.previewPng(parsed.suppression(), catalog, colors, 2)
                : renderer.preview(parsed.tiles(), parsed.tilesX(), parsed.tilesZ(), catalog, colors,
                    previewScale(parsed.tilesX(), parsed.tilesZ()));
        } catch (RuntimeException ex) {
            log.warn("Map art preview render failed for {}", job.id, ex);
            warnings.add("Preview could not be rendered.");
        }
        var stock = store.stock();
        boolean scanned = stock.oldestScan() > 0;
        return new Accepted(job, QueueOps.position(store.list(), job.id), blocks,
            QueueOps.materialRows(v.materials(), stock), scanned, warnings, png);
    }

    private SameColourSwap.Options swapOptions(Predicate<TileCoord> tiles) {
        return new SameColourSwap.Options(config.map.substituteStocked, tiles);
    }

    /**
     * What {@link #resubstitute} found (dry run) or did.
     *
     * @param problem  why nothing was looked at or changed; null when the run went through
     * @param fresh    tiles with nothing placed, the ones that may change
     * @param changed  tiles whose art changes (dry run) or changed, with distinct items and LOOK cells before and after
     * @param swaps    the swaps on those tiles (states of the art as stored before, never eyeblossoms kept)
     */
    public record Resubstitution(String jobId, String jobName, boolean applied, String problem, List<TileCoord> fresh,
                                 Map<TileCoord, TileChange> changed, List<Job.Substitution> swaps) {
        public boolean ok() {
            return problem == null;
        }
    }

    /** Distinct materials and cells placed by a LOOK even in AIRPLACE mode, of one tile, before and after. */
    public record TileChange(int itemsBefore, int itemsAfter, int lookBefore, int lookAfter) { }

    /**
     * {@code mapart resubstitute <job> [apply]}: same-colour substitution again, with today's stock and settings, on the
     * tiles of a queued job that have nothing placed (PENDING, no block placed, not the engine's tile). A dry run unless
     * {@code apply}; applying needs the builder paused or off, so the engine reads no tile while it changes. Applied:
     * the changed tile files, {@link Job#materials} (whole job), {@link Job#substitutions} (the new swaps appended) are
     * saved, and the engine re-reads the queue. Tiles with blocks placed never change. Blocking (file I/O).
     */
    public Resubstitution resubstitute(String jobId, boolean apply) {
        Job job = jobId == null ? null : store.get(jobId).orElse(null);
        if (job == null) return refused(jobId, null, apply, "No job " + jobId + ".");
        if (job.status == null || job.status.terminal())
            return refused(job.id, job.name, apply, "Job " + job.id + " is " + job.status + ": nothing to re-plan.");
        if (job.isSuppression())
            return refused(job.id, job.name, apply, "Job " + job.id + " is a color-suppression map: its blocks are never "
                + "swapped (docs/COLOR_SUPPRESSION.md).");
        if (apply && config.enabled && !config.paused)
            return refused(job.id, job.name, true, "Pause the builder first (`mapart pause`), or turn it off: the engine "
                + "must not read a tile while it changes.");
        EngineControl e = engine.get();
        EngineControl.EngineStatus st = e == null ? null : e.status();
        List<TileCoord> fresh = new ArrayList<>();
        for (Job.TileState t : job.tiles) {
            boolean engineTile = st != null && job.id.equals(st.jobId()) && st.tileX() == t.x && st.tileZ() == t.z;
            if (t.status == TileStatus.PENDING && t.placed <= 0 && t.unlockedMaps <= 0 && !engineTile) fresh.add(t.coord());
        }
        if (fresh.isEmpty()) return refused(job.id, job.name, apply, "Job " + job.id + " has no tile with nothing placed.");
        SortedMap<TileCoord, TileArt> tiles = new TreeMap<>();
        try {
            for (Job.TileState t : job.tiles) tiles.put(t.coord(), store.loadTile(job.id, t.coord()));
        } catch (IOException | RuntimeException ex) {
            log.warn("Map art re-substitution of {}: a tile is unreadable", job.id, ex);
            return refused(job.id, job.name, apply, "A tile is unreadable (" + ex.getMessage() + ").");
        }
        ParsedSubmission before = new ParsedSubmission(job.name, job.tilesX, job.tilesZ,
            DefaultSubmissionService.DATA_VERSION_1_21_4, tiles, List.of());
        Set<TileCoord> eligible = new TreeSet<>(fresh);
        SameColourSwap.Result sw = SameColourSwap.apply(before, catalog, config.site, store.stock(),
            config.map.substituteDeny, swapOptions(eligible::contains));
        List<Job.Substitution> swaps = new ArrayList<>();
        for (Job.Substitution s : sw.substitutions()) if (s.to != null) swaps.add(s);
        Map<TileCoord, TileChange> changed = new LinkedHashMap<>();
        for (TileCoord c : sw.changedTiles(before)) {
            TileArt a = before.tiles().get(c);
            TileArt b = sw.submission().tiles().get(c);
            changed.put(c, new TileChange(items(a), items(b), lookCells(a), lookCells(b)));
        }
        if (changed.isEmpty() || !apply)
            return new Resubstitution(job.id, job.name, false, null, fresh, changed, swaps);
        ValidationResult v;
        try {
            v = submissions.validate(sw.submission(), catalog, config.site);
        } catch (RuntimeException ex) {
            log.warn("Map art re-substitution of {}: validation failed", job.id, ex);
            return refused(job.id, job.name, true, "Validation failed (" + ex.getMessage() + "); nothing changed.");
        }
        if (!v.ok() || v.platformIds() == null || !v.platformIds().containsAll(job.platformIds))
            return refused(job.id, job.name, true, "The swapped art does not validate for the job's platforms ("
                + String.join("; ", v.errors() == null ? List.of() : v.errors()) + "); nothing changed.");
        try {
            for (TileCoord c : changed.keySet()) store.saveTile(job.id, c, sw.submission().tiles().get(c));
            job.materials = new LinkedHashMap<>(v.materials());
            if (job.substitutions == null) job.substitutions = new ArrayList<>();
            job.substitutions.addAll(swaps);
            store.save(job);
        } catch (IOException | RuntimeException ex) {
            log.error("Map art re-substitution of {} could not be saved", job.id, ex);
            return refused(job.id, job.name, true, "Could not save (" + ex.getMessage() + "); tiles written before the "
                + "failure keep the new blocks (same map colors).");
        }
        log.info("Map art job {}: re-substituted {} tiles: {}", job.id, changed.size(),
            SameColourSwap.line(swaps, 2000));
        if (e != null) e.queueChanged();
        return new Resubstitution(job.id, job.name, true, null, fresh, changed, swaps);
    }

    private static Resubstitution refused(String id, String name, boolean apply, String why) {
        return new Resubstitution(id, name, false, why, List.of(), Map.of(), List.of());
    }

    private int items(TileArt t) {
        Set<String> s = new TreeSet<>();
        for (BlockSpec b : distinct(t)) s.add(catalog.placementItem(b).orElse(b.id()));
        return s.size();
    }

    private static int lookCells(TileArt t) {
        int n = 0;
        for (int pz = 0; pz < TileArt.SIZE; pz++)
            for (int px = 0; px < TileArt.SIZE; px++) {
                BlockSpec b = t.get(px, pz);
                if (b != null && TopFaceRules.airPlaceProblem(b) != null) n++;
            }
        return n;
    }

    private static Set<BlockSpec> distinct(TileArt t) {
        Set<BlockSpec> s = new java.util.HashSet<>();
        for (int pz = 0; pz < TileArt.SIZE; pz++)
            for (int px = 0; px < TileArt.SIZE; px++) {
                BlockSpec b = t.get(px, pz);
                if (b != null) s.add(b);
            }
        return s;
    }

    /** The reply text of {@link #resubstitute}. */
    public static String describe(Resubstitution r) {
        if (!r.ok()) return r.problem();
        StringBuilder sb = new StringBuilder();
        if (r.changed().isEmpty()) {
            sb.append("Nothing to swap on the ").append(r.fresh().size()).append(" tile(s) with nothing placed: no stocked "
                + "block of the same map color is better there.");
            return sb.toString();
        }
        String head = r.applied() ? "Re-substituted (identical colors): " : "Would swap (identical colors): ";
        sb.append(SameColourSwap.line(head, r.swaps(), 1200)).append('\n');
        sb.append(r.changed().size()).append(" of ").append(r.fresh().size()).append(" tile(s) with nothing placed ")
            .append(r.applied() ? "changed" : "would change").append(" (distinct items, LOOK cells):");
        int shown = 0;
        for (var e : r.changed().entrySet()) {
            if (shown++ == 30) {
                sb.append(" …");
                break;
            }
            TileChange c = e.getValue();
            sb.append(' ').append(e.getKey()).append(": ").append(c.itemsBefore()).append('→').append(c.itemsAfter());
            if (c.lookBefore() != c.lookAfter()) sb.append(", ").append(c.lookBefore()).append('→').append(c.lookAfter());
            sb.append(';');
        }
        if (sb.charAt(sb.length() - 1) == ';') sb.setLength(sb.length() - 1);
        if (!r.applied()) sb.append("\nDry run: nothing changed. `mapart resubstitute ").append(r.jobId())
            .append(" apply` (builder paused) applies it.");
        return sb.toString();
    }

    /**
     * The warning for art that still holds eyeblossoms after the swaps (their map colour follows the time of day, so
     * the map is filled only when they show the art's state), or null if there are none.
     */
    static String eyeblossomNote(ParsedSubmission parsed, int waitMinutes) {
        boolean open = false, closed = false;
        for (TileArt t : parsed.tiles().values())
            for (int pz = 0; pz < TileArt.SIZE && !(open && closed); pz++)
                for (int px = 0; px < TileArt.SIZE; px++) {
                    BlockSpec s = t.get(px, pz);
                    if (s == null) continue;
                    if (s.id().equals(Eyeblossoms.OPEN)) open = true;
                    else if (s.id().equals(Eyeblossoms.CLOSED)) closed = true;
                }
        if (!open && !closed) return null;
        String when = open && closed ? "maps with closed ones are filled by day and maps with open ones at night"
            : closed ? "maps with them are filled by day, when they are closed"
            : "maps with them are filled at night, when they are open";
        return "Eyeblossoms open at night and close by day, which changes their map color: " + when
            + ". The bot waits at the map up to " + waitMinutes + " min for the flip.";
    }

    /** Pixels per map pixel so the longest preview edge stays ≤ {@link #PREVIEW_MAX_PX} (minimum 1). */
    public static int previewScale(int tilesX, int tilesZ) {
        int edge = TileArt.SIZE * Math.max(1, Math.max(tilesX, tilesZ));
        return Math.max(1, PREVIEW_MAX_PX / edge);
    }

    /**
     * Reads a local submission: one .nbt/.zip file, or every .nbt/.zip directly inside a directory
     * (sorted by name). Each file and the total must be ≤ {@code maxBytes}.
     */
    public static List<NamedFile> readLocal(Path path, long maxBytes) throws IOException {
        List<Path> paths = new ArrayList<>();
        if (Files.isDirectory(path)) {
            try (Stream<Path> s = Files.list(path)) {
                s.filter(Files::isRegularFile).filter(p -> Names.isSubmissionFile(p.getFileName().toString()))
                    .sorted().forEach(paths::add);
            }
            if (paths.isEmpty()) throw new IOException("no .nbt or .zip files in " + path);
        } else if (Files.isRegularFile(path)) {
            if (!Names.isSubmissionFile(path.getFileName().toString()))
                throw new IOException("not a .nbt or .zip file: " + path);
            paths.add(path);
        } else {
            throw new IOException("no such file or directory: " + path);
        }
        long total = 0;
        List<NamedFile> out = new ArrayList<>();
        for (Path p : paths) {
            long size = Files.size(p);
            total += size;
            if (size > maxBytes || total > maxBytes)
                throw new IOException("too large: " + p.getFileName() + " (limit " + maxBytes + " bytes in total)");
            out.add(new NamedFile(p.getFileName().toString(), Files.readAllBytes(p)));
        }
        return out;
    }
}
