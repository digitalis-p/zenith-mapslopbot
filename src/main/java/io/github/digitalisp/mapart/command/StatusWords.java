package io.github.digitalisp.mapart.command;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.engine.BuildEngine;
import io.github.digitalisp.mapart.engine.EngineControl.EngineStatus;
import io.github.digitalisp.mapart.engine.Phase;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.SuppressionArt;
import io.github.digitalisp.mapart.model.TileStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one vocabulary for "what is the bot doing and how far is it", shared by the Discord presence
 * ({@code PresenceText}), {@code mapart status}, the queue listing ({@link QueueOps}) and the progress post
 * ({@code DiscordNotifier}). Plain words for a person glancing at Discord: no phase names, no job ids, no tile
 * coordinates. Pure.
 *
 * <ul>
 *   <li>{@link #doing}: the step, "Printing", "Riding the muncher", "Waiting for the muncher to clear the bottom layer
 *   (1,240 blocks left)".</li>
 *   <li>{@link #progress}: how far, as short parts. A colour-suppression job is ONE map printed in passes (owner,
 *   2026-11-14: "The tile stuff is incorrect", "What does pass mean?"): the layer in words and the percent of the whole
 *   map's blocks, never a pass number, a tile or a count of maps; on the ride, the time since the launch against the
 *   expected ride ("41 of ~75 min (54%)"). A job of several maps: "map 5 of 80 (72%)" (its place in the job's build
 *   order) and "4 maps done". A one-map job: its percent. An ETA where the engine has one.</li>
 * </ul>
 * Percentages are rounded down, so 100% only once complete.
 */
public final class StatusWords {
    private StatusWords() { }

    /** ETAs longer than this are not shown (a rate measured over a few blocks). */
    static final long ETA_MAX_SECONDS = 30L * 24 * 3600;

    private static final Pattern BLOCKS_LEFT = Pattern.compile("^(.*): (\\d+) blocks left$");

    // ---------------------------------------------------------------- the step

    /**
     * What the engine does now, in plain words; {@code job} the job {@code s} names (null when not known). Not the
     * pause, BLOCKED or connection states: the callers put those first.
     */
    public static String doing(@Nullable EngineStatus s, @Nullable Job job) {
        Phase phase = s == null || s.phase() == null ? Phase.IDLE : s.phase();
        String note = s == null || s.note() == null ? "" : s.note();
        if (note.startsWith(BuildEngine.BACKOFF_NOTE)) return "Backing off (server setbacks)";
        if (note.startsWith(BuildEngine.EATING_NOTE)) return "Eating";
        if (note.startsWith(BuildEngine.MENDING_NOTE)) return "Mending tools";
        switch (phase) {
            case INSPECT -> {
                if (note.startsWith(BuildEngine.LAYER_WAIT_NOTE)) return blocksLeft(note);
            }
            case STOCK_CHECK -> {
                if (note.startsWith(BuildEngine.MATERIALS_NOTE)) return capital(note);
            }
            case FILL_MAP -> {
                if (note.startsWith(BuildEngine.COVER_NOTE)) return oneLine(note);
                if (note.startsWith(BuildEngine.EYEBLOSSOM_NOTE)) return capital(note);
            }
            case CHECK_MAP -> {
                if (note.startsWith(BuildEngine.MAP_DATA_NOTE)) return "Waiting for map data";
            }
            case RIDE_BOARD -> {
                if (note.startsWith(BuildEngine.GUARD_WAIT_NOTE)) return "Waiting for the machines to come home";
                if (note.startsWith(BuildEngine.RESTART_NOTE)) return "Waiting for the server restart";
            }
            case STORE -> {
                Job.TileState t = currentTile(job, s);
                if (t != null && t.cartReturnDue && !t.cartReturned) return "Returning the cart";
            }
            case WAIT_CLEAR -> {
                if (note.startsWith(BuildEngine.COLLECTING_NOTE)) return "Collecting items";
            }
            default -> { }
        }
        return verb(phase);
    }

    /** The step of a phase without its notes. */
    public static String verb(Phase p) {
        return switch (p) {
            case IDLE -> "Idle";
            case SELECT -> "Starting";
            case INSPECT -> "Inspecting the platform";
            case STOCK_CHECK -> "Checking stock";
            case GEAR -> "Getting tools and food";
            case RESTOCK -> "Restocking";
            case BUILD -> "Printing";
            case VERIFY -> "Checking the art";
            case FILL_MAP -> "Filling the map";
            case CHECK_MAP -> "Checking the map";
            case RIDE_BOARD -> "Pre-ride checks";
            case RIDE_PRELOAD -> "Riding the preload lap";
            case RIDE -> "Riding the muncher";
            case RIDE_END -> "Leaving the cart";
            case RIDE_RESET -> "Waiting for the muncher to reset";
            case LOCK -> "Locking the map";
            case RENAME -> "Naming the map";
            case STORE -> "Storing the map";
            case COUNT -> "Counting the map";
            case NUKE -> "Clearing leftovers";
            case CLEAR -> "Clearing the platform";
            case WAIT_CLEAR -> "Waiting for the platform to clear";
            case FINISH_TILE -> "Finishing";
            case BLOCKED -> "Needs help";
        };
    }

    /** "waiting for the muncher to clear the bottom layer: 1240 blocks left" as "Waiting ... layer (1,240 blocks left)". */
    private static String blocksLeft(String note) {
        Matcher m = BLOCKS_LEFT.matcher(note);
        if (!m.matches()) return capital(note);
        return capital(m.group(1)) + " (" + Fmt.count(Long.parseLong(m.group(2))) + " blocks left)";
    }

    private static String capital(String note) {
        String n = oneLine(note);
        return n.isEmpty() ? n : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\p{Cntrl}]+", " ").strip();
    }

    // ---------------------------------------------------------------- how far

    /**
     * How far the job is, as short parts in reading order (empty when nothing is known), e.g. {@code ["top layer",
     * "62%", "~2h 10m left"]}, {@code ["41 of ~75 min (54%)"]}, {@code ["map 5 of 80 (72%)", "4 maps done", "job ~1d 06h
     * left"]}, {@code ["43%", "~25m left"]}. Without {@code job} (not in the store): the snapshot's own percent.
     *
     * @param now epoch ms, for the ride; 0 = not known (no ride progress)
     */
    public static List<String> progress(@Nullable EngineStatus s, @Nullable Job job, long now) {
        List<String> parts = new ArrayList<>();
        if (s == null) return parts;
        if (job != null && job.isSuppression()) {
            if (s.phase() == Phase.RIDE) {
                String ride = ride(s, now);
                if (!ride.isEmpty()) parts.add(ride);
                return parts;
            }
            if (afterPrint(s.phase())) return parts; // printed: the steps after it say where it is
            Double f = suppressionFraction(job, s);
            if (f == null || f >= 1) return parts;
            String note = s.note() == null ? "" : s.note();
            String layer = layer(job, s.tileX());
            // the wait for the muncher names its layer itself
            if (layer != null && !note.startsWith(BuildEngine.LAYER_WAIT_NOTE)) parts.add(layer);
            parts.add(percent(f) + "%");
            String eta = eta(s.etaSeconds());
            if (!eta.isEmpty()) parts.add(eta);
            return parts;
        }
        Job.TileState current = currentTile(job, s);
        Double map = tileFraction(s, current);
        int maps = job == null ? 1 : mapCount(job);
        if (maps <= 1) {
            if (map != null) parts.add(percent(map) + "%");
            String eta = eta(s.etaSeconds());
            if (!eta.isEmpty()) parts.add(eta);
            return parts;
        }
        int stored = 0;
        for (Job.TileState t : job.tiles) if (t != null && t.status == TileStatus.STORED) stored++;
        if (current != null) {
            String m = "map " + (job.tiles.indexOf(current) + 1) + " of " + maps;
            if (current.status == TileStatus.STORED) m += " (done)";
            else if (map != null) m += " (" + percent(map) + "%)";
            parts.add(m);
        }
        parts.add(stored + (stored == 1 ? " map done" : " maps done") + (current == null ? " of " + maps : ""));
        long eta = s.etaSeconds();
        if (eta >= 0 && current != null && current.status != TileStatus.STORED) {
            long perMap = averageMapSeconds(job);
            int after = maps - stored - 1;
            String e = perMap > 0 ? eta(eta + Math.max(0, after) * perMap) : eta(eta);
            if (!e.isEmpty()) parts.add((perMap > 0 ? "job " : "map ") + e);
        }
        return parts;
    }

    /** The steps of a colour-suppression map after its print, from the fill to the reset: no print percent. */
    private static boolean afterPrint(@Nullable Phase p) {
        if (p == null) return false;
        return switch (p) {
            case FILL_MAP, CHECK_MAP, RIDE_BOARD, RIDE_PRELOAD, RIDE, RIDE_END, LOCK, RENAME, STORE, COUNT, RIDE_RESET -> true;
            default -> false;
        };
    }

    /** {@link #doing} and {@link #progress} on one line: "Printing · top layer · 62% · ~2h 10m left". */
    public static String line(@Nullable EngineStatus s, @Nullable Job job, long now) {
        StringBuilder sb = new StringBuilder(doing(s, job));
        for (String p : progress(s, job, now)) sb.append(" · ").append(p);
        return sb.toString();
    }

    /**
     * The part of the job the engine is on, for a title: "top layer" (a colour-suppression map), "map 5 of 80" (a job
     * of several maps), "" (a one-map job, or not known).
     */
    public static String part(@Nullable Job job, int tileX, int tileZ) {
        if (job == null) return "";
        if (job.isSuppression()) {
            String l = layer(job, tileX);
            return l == null ? "" : l;
        }
        int maps = mapCount(job);
        if (maps <= 1) return "";
        for (int i = 0; i < job.tiles.size(); i++) {
            Job.TileState t = job.tiles.get(i);
            if (t != null && t.x == tileX && t.z == tileZ) return "map " + (i + 1) + " of " + maps;
        }
        return "";
    }

    /** "top layer" / "bottom layer" of the colour-suppression pass stored as tile ({@code tileX}, 0); null when none. */
    public static @Nullable String layer(Job job, int tileX) {
        if (job == null || job.suppression == null || tileX < 0 || tileX >= job.suppression.size()) return null;
        Job.SuppressionPass p = job.suppression.get(tileX);
        if (p == null) return null;
        return p.layer == SuppressionArt.BOTTOM ? "bottom layer" : "top layer";
    }

    /** Blocks placed and blocks in all of a colour-suppression map, over every pass. */
    public record Blocks(long placed, long total) { }

    /**
     * A colour-suppression map's blocks over all its passes: a finished pass counts whole, the pass the engine is on
     * by the live snapshot, the others by their saved state. Null for other jobs.
     */
    public static @Nullable Blocks suppressionBlocks(@Nullable Job job, @Nullable EngineStatus s) {
        if (job == null || !job.isSuppression()) return null;
        return suppressionBlocks(job, s == null || !job.id.equals(s.jobId()) ? -1 : s.tileX(),
            s == null ? 0 : s.placed(), s == null ? 0 : s.total());
    }

    /** {@link #suppressionBlocks(Job, EngineStatus)} with the current pass's live count given ({@code tileX} -1: none). */
    public static @Nullable Blocks suppressionBlocks(@Nullable Job job, int tileX, int placed, int total) {
        if (job == null || !job.isSuppression()) return null;
        long done = 0;
        long all = 0;
        for (Job.TileState t : job.tiles) {
            if (t == null) continue;
            boolean live = t.z == 0 && t.x == tileX && total > 0;
            long tot = live ? total : Math.max(0, t.total);
            // a level-1 pass: what its layer's level-0 sweep placed for it counts before the pass itself runs
            long got = t.status == TileStatus.STORED ? tot : live ? Math.max(placed, t.placedAhead) : t.progress();
            all += tot;
            done += Math.max(0, Math.min(tot, got));
        }
        return new Blocks(done, all);
    }

    /** The placed fraction of a colour-suppression map over all its passes; null when no block count is known. */
    static @Nullable Double suppressionFraction(Job job, @Nullable EngineStatus s) {
        Blocks b = suppressionBlocks(job, s);
        return b == null || b.total() <= 0 ? null : clamp((double) b.placed() / b.total());
    }

    /**
     * The ride's progress, "41 of ~75 min (54%)": minutes since the launch against the expected ride. The route is not
     * even in time (the cart is held at lane entries), so this is time, not distance; a ride that runs over stays at
     * 99%. "" before the launch, or without a clock.
     */
    public static String ride(@Nullable EngineStatus s, long now) {
        if (s == null || s.rideStartedAt() <= 0 || s.rideExpectedSeconds() <= 0 || now <= 0) return "";
        long elapsed = Math.max(0, now - s.rideStartedAt()) / 1000;
        long expected = s.rideExpectedSeconds();
        long pct = Math.min(99, elapsed * 100 / expected);
        return elapsed / 60 + " of ~" + (expected + 30) / 60 + " min (" + pct + "%)";
    }

    /**
     * "~25m left", "~2h 10m left" (to 5 minutes from an hour on), "~1d 06h left"; "" for an unknown (negative) ETA,
     * nothing left, or one beyond {@link #ETA_MAX_SECONDS}.
     */
    public static String eta(long seconds) {
        if (seconds <= 0 || seconds > ETA_MAX_SECONDS) return "";
        long s = seconds < 3600 ? Math.max(60, seconds) : (seconds + 150) / 300 * 300;
        return "~" + Fmt.duration(s) + " left";
    }

    /** Average time a stored map of {@code job} took from its start to its end, in seconds; 0 when none is on record. */
    static long averageMapSeconds(Job job) {
        long sum = 0;
        int n = 0;
        for (Job.TileState t : job.tiles) {
            if (t == null || t.status != TileStatus.STORED || t.startedAt <= 0 || t.finishedAt <= t.startedAt) continue;
            sum += (t.finishedAt - t.startedAt) / 1000;
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    // ---------------------------------------------------------------- counts

    public static int mapCount(Job job) {
        return job.tiles.isEmpty() ? Math.max(0, job.tilesX) * Math.max(0, job.tilesZ) : job.tiles.size();
    }

    /** The job's tile the engine is on, or null. */
    public static Job.@Nullable TileState currentTile(@Nullable Job job, @Nullable EngineStatus s) {
        if (job == null || s == null) return null;
        for (Job.TileState t : job.tiles)
            if (t != null && t.x == s.tileX() && t.z == s.tileZ()) return t;
        return null;
    }

    /**
     * Placed fraction of the current map: 1 once stored, else the live snapshot when it has a total, else the saved
     * tile state; null when nothing is known (0/0).
     */
    public static @Nullable Double tileFraction(@Nullable EngineStatus s, Job.@Nullable TileState t) {
        if (t != null && t.status == TileStatus.STORED) return 1.0;
        if (s != null && s.total() > 0) return clamp((double) s.placed() / s.total());
        if (t != null && t.total > 0) return clamp((double) t.placed / t.total);
        return null;
    }

    /** Whole percent, rounded down (100 only when complete). */
    public static int percent(double fraction) {
        return (int) Math.max(0, Math.min(100, Math.floor(fraction * 100 + 1e-9)));
    }

    private static double clamp(double f) {
        return Double.isNaN(f) ? 0 : Math.max(0, Math.min(1, f));
    }
}
