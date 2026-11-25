package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The bot's own recent track (the feet cells it stood on, newest last) and the way back out of a spot it is boxed in
 * at ({@link LocalReach}): the track led in, so walking it backwards leads out, unless the way in was a drop.
 * Pure; {@link Walker#TRACK} holds the session's track.
 */
final class TrackBack {
    /** Cells kept. */
    static final int MAX_CELLS = 64;
    /** At most this many steps are walked back. */
    static final int MAX_STEPS = 16;

    private final ArrayDeque<Pos> cells = new ArrayDeque<>();

    /** Notes the feet cell of this tick; only cells stood on count, and a cell repeated in a row once. */
    void note(Pos feet, boolean onGround) {
        if (!onGround || feet.equals(cells.peekLast())) return;
        cells.addLast(feet);
        if (cells.size() > MAX_CELLS) cells.removeFirst();
    }

    /** The track, newest first. */
    List<Pos> newestFirst() {
        List<Pos> out = new ArrayList<>(cells.size());
        for (Iterator<Pos> it = cells.descendingIterator(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    void clear() {
        cells.clear();
    }

    /**
     * @param steps   feet cells to walk through, in order, the last one outside the pocket; null when there is no way back
     * @param problem why not (with {@code steps} null)
     * @param dropFrom the way in was a drop of two blocks or more: the track cell it dropped from (the level to climb
     *                 back to: the engine's fall rescue does that); null otherwise
     */
    record Plan(@Nullable List<Pos> steps, @Nullable String problem, @Nullable Pos dropFrom) {
        Plan(@Nullable List<Pos> steps, @Nullable String problem) {
            this(steps, problem, null);
        }
    }

    /** {@link #plan(List, Pos, Predicate)} up to the first cell outside {@code pocket}. */
    static Plan plan(List<Pos> newestFirst, Pos at, Set<Pos> pocket) {
        return plan(newestFirst, at, c -> !pocket.contains(c));
    }

    /**
     * The track walked backwards from {@code at} until the first cell where the pathfinder can take over
     * ({@code resume}: outside the cells it reaches from {@code at}, and a start it can path from). Each step must be one
     * block across at most (diagonals included) and at most one block up (a jump): the reverse of a drop of two blocks
     * or more cannot be walked.
     */
    static Plan plan(List<Pos> newestFirst, Pos at, Predicate<Pos> resume) {
        List<Pos> steps = new ArrayList<>();
        Pos from = at;
        for (Pos c : newestFirst) {
            if (c.equals(from)) continue;
            int up = c.y() - from.y();
            if (up > 1) {
                return new Plan(null, "the way in was a drop of " + up + " blocks from " + c.key()
                    + "; walking back would mean climbing it", c);
            }
            int across = Math.max(Math.abs(c.x() - from.x()), Math.abs(c.z() - from.z()));
            if (across > 1) {
                return new Plan(null, "its track jumps " + across + " blocks from " + c.key() + " to " + from.key()
                    + " (moved, not walked); no way back along it");
            }
            steps.add(c);
            if (resume.test(c)) return new Plan(List.copyOf(steps), null);
            if (steps.size() >= MAX_STEPS) {
                return new Plan(null, "its track stays in the boxed-in spot for " + MAX_STEPS + " steps back");
            }
            from = c;
        }
        return new Plan(null, newestFirst.isEmpty() ? "no track recorded"
            : "its whole track (" + newestFirst.size() + " cells) lies in the boxed-in spot");
    }
    /** A retreat gains at least this much distance (horizontal, to the nearest threat) or is not worth walking. */
    static final double RETREAT_MIN_GAIN = 4;
    /** A track cell this far from every threat is far enough: the newest such cell is taken (the shortest walk back). */
    static final double RETREAT_FAR = 24;

    /**
     * Where to retreat from {@code threats} (mob feet positions x, z) along the bot's own track (newest first, cells it
     * stood on, so walkable): the newest cell at least {@link #RETREAT_FAR} from every threat, else the one farthest
     * from them; null when no cell gains {@link #RETREAT_MIN_GAIN} over {@code at}, or without threats.
     */
    static @Nullable Pos retreatCell(List<Pos> newestFirst, Pos at, List<double[]> threats) {
        if (threats.isEmpty()) return null;
        double here = nearest(at, threats);
        Pos best = null;
        double bestD = here + RETREAT_MIN_GAIN - 1e-9;
        for (Pos c : newestFirst) {
            double d = nearest(c, threats);
            if (d >= RETREAT_FAR && d >= here + RETREAT_MIN_GAIN) return c;
            if (d > bestD) {
                best = c;
                bestD = d;
            }
        }
        return best;
    }

    private static double nearest(Pos c, List<double[]> threats) {
        double min = Double.MAX_VALUE;
        for (double[] t : threats) min = Math.min(min, Math.hypot(c.x() + 0.5 - t[0], c.z() + 0.5 - t[1]));
        return min;
    }
}
