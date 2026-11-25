package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * The fast nuke's own-key walk never takes the box into a hazard cell (the
 * edge guard): a no-go zone, water or lava, or open with a drop below ({@link EdgeDrop#at}). Each tick's movement
 * is played forward with the keys the walk would press for it and the slide after them, before it is pressed. Pure,
 * unit-tested.
 *
 * <p>Live 2026-10-18 (soul sand, Galileo [0,7] NUKE, from the replay's track): the sweep walked beside the platform's
 * east edge column at Soul Speed (0.49 blocks a tick). Its keys are the combination nearest the wanted direction for
 * the yaw of the block being broken, here 8° off towards the edge. A row of decorated pots stopped the walk: their box
 * is inset 1/16, so the bot's box already stood in their cells, which the walkable test leaves out, and nothing saw a
 * block in the way. Pressed against them, the keys slid the bot east along the pots, 0.9 blocks in 20 ticks, into the
 * no-go zone over the item trench, and it fell in.
 */
final class NukeGuard {
    /** Ground friction × 0.91 per tick (vanilla, slipperiness 0.6: soul sand, stone, terracotta, moss). */
    static final double FRICTION = 0.546;
    /** Least velocity a tick of movement keys adds (walking: 0.1 × 0.98). */
    static final double ACCEL_MIN = 0.098;
    /** Speeds above this in one tick are teleports, not walking. */
    static final double TOP_SPEED_MAX = 0.7;
    /** Other movement keys within this angle of the wanted direction are tried (50°: the two beside it). */
    static final double TURN_COS = Math.cos(Math.toRadians(50));
    /** The slide is played forward this many ticks at most (it is below 0.003 a tick after about 10). */
    static final int SLIDE_TICKS = 60;

    private NukeGuard() { }

    /**
     * What to press instead: (mx, mz) (a unit direction the keys produce, or 0, 0: no movement key), whether the walk is
     * held back (no keys, or braking), the hazard ("x,y,z: what") and what was done ("turned", "holding back",
     * "braking").
     */
    record Decision(double mx, double mz, boolean held, String hazard, String how) { }

    /**
     * The velocity a tick of movement keys adds, from the fastest the feet moved in one tick ({@code topSpeed}: a
     * steady walk adds what friction takes, so Soul Speed's 0.49 means 0.22), at least {@link #ACCEL_MIN}.
     */
    static double accel(double topSpeed) {
        return Math.max(ACCEL_MIN, Math.min(topSpeed, TOP_SPEED_MAX) * (1 - FRICTION));
    }

    /**
     * Null if moving towards (mx, mz) (0, 0: no key) is safe: with the keys for it at {@code yaw} ({@link #keyMotion})
     * for one tick on top of velocity (vx, vz) and the slide after it, the box at feet (fx, fz) goes into no hazard
     * cell at feet level {@code level} deeper than it is now ({@link #slide}). Else the keys nearest the direction
     * within {@link #TURN_COS} that are safe (turned), else no key (holding back), else the keys against the velocity
     * (braking). {@code hazard}: why the box must not go into a cell, or null.
     */
    static @Nullable Decision decide(double fx, double fz, int level, double vx, double vz, double accel, double yaw,
                                     double mx, double mz, Function<Pos, @Nullable String> hazard) {
        return decide(fx, fz, level, vx, vz, accel, yaw, mx, mz, hazard, false);
    }

    /**
     * {@link #decide}; with {@code feetPoint} the feet point is played forward instead of the box: it must go into no
     * hazard cell other than the one it is in ({@link #slideFeet}; the Baritone walks' holes, {@link EdgeApproach}).
     */
    static @Nullable Decision decide(double fx, double fz, int level, double vx, double vz, double accel, double yaw,
                                     double mx, double mz, Function<Pos, @Nullable String> hazard, boolean feetPoint) {
        boolean moving = mx != 0 || mz != 0;
        double[] keys = moving ? keyMotion(yaw, mx, mz) : new double[]{0, 0};
        String why = slide(fx, fz, level, vx, vz, accel, keys, hazard, feetPoint);
        if (why == null) return null;
        if (moving) {
            for (double[] k : nearKeys(yaw, mx, mz, keys)) {
                if (slide(fx, fz, level, vx, vz, accel, k, hazard, feetPoint) == null) {
                    return new Decision(k[0], k[1], false, why, "turned");
                }
            }
            if (slide(fx, fz, level, vx, vz, accel, new double[]{0, 0}, hazard, feetPoint) == null) {
                return new Decision(0, 0, true, why, "holding back");
            }
        }
        if (Math.hypot(vx, vz) < 1e-3) return new Decision(0, 0, true, why, "holding back");
        double[] brake = keyMotion(yaw, -vx, -vz);
        return new Decision(brake[0], brake[1], true, why, "braking");
    }

    /**
     * The first hazard cell ("x,y,z: what") the box at feet (fx, fz) goes into deeper than it is now (more of the box
     * in the cell), moving one tick by velocity (vx, vz) plus {@code accel} along unit direction {@code keys}, then
     * sliding on (× {@link #FRICTION} a tick); null if none. Walls are left out (the box is played through them: it
     * moves no farther than that).
     */
    static @Nullable String slide(double fx, double fz, int level, double vx, double vz, double accel, double[] keys,
                                  Function<Pos, @Nullable String> hazard) {
        return slide(fx, fz, level, vx, vz, accel, keys, hazard, false);
    }

    /** {@link #slide}, with {@code feetPoint} the feet point's way ({@link #slideFeet}) instead of the box. */
    static @Nullable String slide(double fx, double fz, int level, double vx, double vz, double accel, double[] keys,
                                  Function<Pos, @Nullable String> hazard, boolean feetPoint) {
        double sx = vx + accel * keys[0];
        double sz = vz + accel * keys[1];
        double px = fx;
        double pz = fz;
        for (int i = 0; i < SLIDE_TICKS && Math.hypot(sx, sz) > 0.003; i++) {
            if (feetPoint) {
                String h = slideFeet(fx, fz, px, pz, px + sx, pz + sz, level, hazard);
                if (h != null) return h;
            }
            px += sx;
            pz += sz;
            String h = feetPoint ? null : deeper(fx, fz, px, pz, level, hazard);
            if (h != null) return h;
            sx *= FRICTION;
            sz *= FRICTION;
        }
        return null;
    }

    /** Steps along the feet point's way within a tick: fine enough not to skip a cell's corner. */
    static final double FEET_STEP = 0.02;

    /** The feet point keeps this far outside a hazard cell ({@link #slideFeet}): a creep up to it never ends in it. */
    static final double FEET_MARGIN = 0.05;

    /**
     * A hazard cell ("x,y,z: what") the feet point comes nearer than {@link #FEET_MARGIN} to (or into) on its way from
     * (ax, az) to (bx, bz), nearer than it was at (fx, fz) where it starts; null if none. Looked at every
     * {@link #FEET_STEP}, so a corner is not skipped. The cell the feet start in, if it is a hazard (a login there, a
     * slide in), counts only when more of the box goes into it: the way out is free.
     */
    static @Nullable String slideFeet(double fx, double fz, double ax, double az, double bx, double bz, int level,
                                      Function<Pos, @Nullable String> hazard) {
        int sx = (int) Math.floor(fx);
        int sz = (int) Math.floor(fz);
        Pos start = new Pos(sx, level, sz);
        String startWhy = hazard.apply(start);
        double startIn = startWhy == null ? 0 : overlap(fx, fz, sx, sz);
        int n = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, bz - az) / FEET_STEP));
        double m = FEET_MARGIN;
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            double px = ax + (bx - ax) * t;
            double pz = az + (bz - az) * t;
            if (startWhy != null && overlap(px, pz, sx, sz) > startIn + 1e-9) return start.key() + ": " + startWhy;
            for (int cx = (int) Math.floor(px - m); cx <= (int) Math.floor(px + m); cx++) {
                for (int cz = (int) Math.floor(pz - m); cz <= (int) Math.floor(pz + m); cz++) {
                    if (cx == sx && cz == sz) continue;
                    double d = rectDistance(px, pz, cx, cz);
                    if (d >= m || d >= rectDistance(fx, fz, cx, cz) - 1e-9) continue;
                    Pos c = new Pos(cx, level, cz);
                    String why = hazard.apply(c);
                    if (why != null) return c.key() + ": " + why;
                }
            }
        }
        return null;
    }

    /** Distance from point (x, z) to column (cx, cz): 0 inside it. */
    static double rectDistance(double x, double z, int cx, int cz) {
        double dx = Math.max(0, Math.max(cx - x, x - (cx + 1)));
        double dz = Math.max(0, Math.max(cz - z, z - (cz + 1)));
        return Math.hypot(dx, dz);
    }

    /** A hazard cell the box at feet (px, pz) is deeper in than at (fx, fz), as "x,y,z: what"; null if none. */
    private static @Nullable String deeper(double fx, double fz, double px, double pz, int level,
                                           Function<Pos, @Nullable String> hazard) {
        double h = Aim.PLAYER_HALF_WIDTH;
        for (int cx = (int) Math.floor(px - h); cx <= (int) Math.floor(px + h); cx++) {
            for (int cz = (int) Math.floor(pz - h); cz <= (int) Math.floor(pz + h); cz++) {
                double in = overlap(px, pz, cx, cz);
                if (in <= 1e-9 || in <= overlap(fx, fz, cx, cz) + 1e-9) continue;
                Pos c = new Pos(cx, level, cz);
                String why = hazard.apply(c);
                if (why != null) return c.key() + ": " + why;
            }
        }
        return null;
    }

    /** Area of the box (feet at x, z) inside column (cx, cz). */
    static double overlap(double x, double z, int cx, int cz) {
        double h = Aim.PLAYER_HALF_WIDTH;
        double ox = Math.min(x + h, cx + 1) - Math.max(x - h, cx);
        double oz = Math.min(z + h, cz + 1) - Math.max(z - h, cz);
        return ox <= 0 || oz <= 0 ? 0 : ox * oz;
    }

    /**
     * The unit direction the keys for (dx, dz) at {@code yaw} move the bot ({@link Aim#movementKeys}: the nearest of the
     * 8 key combinations, up to 22.5° off); NaN yaw: (dx, dz) itself. (0, 0) for no motion.
     */
    static double[] keyMotion(double yaw, double dx, double dz) {
        double l = Math.hypot(dx, dz);
        if (l < 1e-9) return new double[]{0, 0};
        if (Double.isNaN(yaw)) return new double[]{dx / l, dz / l};
        int[] k = Aim.movementKeys(yaw, dx, dz);
        return unit(Aim.keysToWorld(yaw, k[0], k[1]));
    }

    /**
     * The other key directions within {@link #TURN_COS} of (dx, dz), nearest first, without {@code except}; NaN yaw:
     * (dx, dz) turned 45° either way.
     */
    static List<double[]> nearKeys(double yaw, double dx, double dz, double[] except) {
        double l = Math.hypot(dx, dz);
        List<double[]> out = new ArrayList<>(4);
        if (l < 1e-9) return out;
        double ux = dx / l;
        double uz = dz / l;
        if (Double.isNaN(yaw)) {
            double c = Math.cos(Math.PI / 4);
            out.add(new double[]{(ux - uz) * c, (ux + uz) * c});
            out.add(new double[]{(ux + uz) * c, (uz - ux) * c});
            return out;
        }
        for (int f = -1; f <= 1; f++) {
            for (int s = -1; s <= 1; s++) {
                if (f == 0 && s == 0) continue;
                double[] w = unit(Aim.keysToWorld(yaw, f, s));
                if (w[0] * ux + w[1] * uz < TURN_COS) continue;
                if (Math.abs(w[0] - except[0]) < 1e-6 && Math.abs(w[1] - except[1]) < 1e-6) continue;
                out.add(w);
            }
        }
        out.sort(Comparator.comparingDouble(w -> -(w[0] * ux + w[1] * uz)));
        return out;
    }

    private static double[] unit(double[] w) {
        double l = Math.hypot(w[0], w[1]);
        return l < 1e-9 ? new double[]{0, 0} : new double[]{w[0] / l, w[1] / l};
    }
}
