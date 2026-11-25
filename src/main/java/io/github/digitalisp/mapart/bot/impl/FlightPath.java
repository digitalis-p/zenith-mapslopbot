package io.github.digitalisp.mapart.bot.impl;

/**
 * An arc flight's way over the ground ({@code build.elytraArcLegs}): from the
 * take-off spot S a straight line to A, a turn of radius {@link #radius} around centre C to B, then the straight
 * final approach from B into R along {@link #finalX}/{@link #finalZ} (the landing pad's direction). The turn burns
 * the part of a duration-3 boost a straight leg this short has no room for; it lies inside the boost (the planner keeps
 * it within {@link FlightPlanner#ARC_TURN_END}), where the rocket points the velocity along the look within a tick or
 * two, so the glide after the boost flies the final line as a straight leg's does. Pure; horizontal (x, z) only.
 *
 * <p>Distances along the way ({@code s}) run from 0 at S to {@link #length} at R and on past R along the final line.
 * The way may cross itself (a short leg's loop flies over its own take-off spot), so the bot's place on it is
 * tracked ({@link #track}): from the last place, a few blocks back or ahead, never the nearest point overall.
 */
final class FlightPath {
    final double sx;
    final double sz;
    final double ax;
    final double az;
    final double cx;
    final double cz;
    final double radius;
    /** +1: the turn goes from +x toward +z (counter-clockwise in the x-z plane); -1 the other way. */
    final int sense;
    final double bx;
    final double bz;
    final double rx;
    final double rz;
    final double finalX;
    final double finalZ;
    /** Lengths: S to A, the turn, B to R. */
    final double first;
    final double turn;
    final double last;
    private final double angA;

    private FlightPath(double sx, double sz, double ax, double az, double cx, double cz, double radius, int sense,
                       double bx, double bz, double rx, double rz, double finalX, double finalZ, double angA,
                       double sweep) {
        this.sx = sx;
        this.sz = sz;
        this.ax = ax;
        this.az = az;
        this.cx = cx;
        this.cz = cz;
        this.radius = radius;
        this.sense = sense;
        this.bx = bx;
        this.bz = bz;
        this.rx = rx;
        this.rz = rz;
        this.finalX = finalX;
        this.finalZ = finalZ;
        this.angA = angA;
        this.first = Math.hypot(ax - sx, az - sz);
        this.turn = radius * sweep;
        this.last = Math.hypot(rx - bx, rz - bz);
    }

    /**
     * The way from S into R along the final heading ({@code hx}, {@code hz}, a unit vector), its final line
     * {@code finalLen} long, the turn before it of {@code radius} with {@code sense}; null when S lies within the turn's
     * circle (no tangent from S).
     */
    static FlightPath of(double sx, double sz, double rx, double rz, double hx, double hz, double finalLen, double radius,
                         int sense) {
        double bx = rx - hx * finalLen;
        double bz = rz - hz * finalLen;
        // the centre: to the turn's side of the final heading (rot90 = (-hz, hx) for a counter-clockwise turn)
        double cx = bx + sense * radius * -hz;
        double cz = bz + sense * radius * hx;
        double d = Math.hypot(sx - cx, sz - cz);
        if (d <= radius + 1e-6) return null;
        double angS = Math.atan2(sz - cz, sx - cx);
        double beta = Math.acos(radius / d);
        double angA = Double.NaN;
        for (double a : new double[]{angS + beta, angS - beta}) {
            double px = cx + radius * Math.cos(a);
            double pz = cz + radius * Math.sin(a);
            // the way round the circle at A, in the turn's sense: sense * rot90(A - C)
            double tx = sense * -(pz - cz);
            double tz = sense * (px - cx);
            if ((px - sx) * tx + (pz - sz) * tz > 0) {
                angA = a;
                break;
            }
        }
        if (Double.isNaN(angA)) return null;
        double angB = Math.atan2(bz - cz, bx - cx);
        double sweep = sense * (angB - angA);
        sweep = ((sweep % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
        if (sweep > 2 * Math.PI - 1e-6) sweep = 0;
        double ax = cx + radius * Math.cos(angA);
        double az = cz + radius * Math.sin(angA);
        return new FlightPath(sx, sz, ax, az, cx, cz, radius, sense, bx, bz, rx, rz, hx, hz, angA, sweep);
    }

    /** S to R along the way. */
    double length() {
        return first + turn + last;
    }

    /** Where the turn ends (B), along the way. */
    double turnEnd() {
        return first + turn;
    }

    /** The turn in degrees. */
    double turnDegrees() {
        return Math.toDegrees(turn / radius);
    }

    /** The point {@code s} along the way: {x, z}. */
    double[] point(double s) {
        if (s <= first) {
            if (first < 1e-9) return new double[]{sx, sz};
            double f = Math.max(0, s) / first;
            return new double[]{sx + (ax - sx) * f, sz + (az - sz) * f};
        }
        if (s <= first + turn) {
            double a = angA + sense * (s - first) / radius;
            return new double[]{cx + radius * Math.cos(a), cz + radius * Math.sin(a)};
        }
        double u = s - first - turn;
        return new double[]{bx + finalX * u, bz + finalZ * u};
    }

    /** The way's heading {@code s} along it: {x, z}, a unit vector. */
    double[] heading(double s) {
        if (s <= first && first >= 1e-9) return new double[]{(ax - sx) / first, (az - sz) / first};
        if (s <= first + turn && turn > 1e-9) {
            double a = angA + sense * (Math.max(0, s - first)) / radius;
            return new double[]{sense * -Math.sin(a), sense * Math.cos(a)};
        }
        return new double[]{finalX, finalZ};
    }

    /** Blocks back and ahead of the last place that {@link #track} looks for the bot's place on the way. */
    static final double TRACK_BACK = 2;
    static final double TRACK_AHEAD = 10;

    /**
     * The bot's place on the way at (x, z), from its last place {@code last} (NaN: anywhere): the nearest point of the
     * way within {@link #TRACK_BACK} back and {@link #TRACK_AHEAD} ahead, never below 0.
     */
    double track(double x, double z, double last) {
        double lo = Double.isNaN(last) ? 0 : Math.max(0, last - TRACK_BACK);
        double hi = Double.isNaN(last) ? length() + 50 : last + TRACK_AHEAD;
        double best = Double.NaN;
        double bestD = Double.POSITIVE_INFINITY;
        // the first line
        if (first >= 1e-9 && lo <= first) {
            double u = ((x - sx) * (ax - sx) + (z - sz) * (az - sz)) / first;
            double s = clamp(u, Math.max(lo, 0), Math.min(hi, first));
            double d = dist(s, x, z);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        // the turn
        if (turn > 1e-9 && lo <= first + turn && hi >= first) {
            double ang = Math.atan2(z - cz, x - cx);
            double u = sense * (ang - angA);
            u = ((u % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
            double sweep = turn / radius;
            double su;
            if (u <= sweep) su = first + u * radius;
            else su = (u - sweep < 2 * Math.PI - u) ? first + turn : first;
            double s = clamp(su, Math.max(lo, first), Math.min(hi, first + turn));
            double d = dist(s, x, z);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        // the final line, on past R
        if (hi >= first + turn) {
            double u = (x - bx) * finalX + (z - bz) * finalZ;
            double s = clamp(first + turn + u, Math.max(lo, first + turn), hi);
            double d = dist(s, x, z);
            if (d < bestD) {
                best = s;
            }
        }
        return Double.isNaN(best) ? lo : best;
    }

    private double dist(double s, double x, double z) {
        double[] p = point(s);
        return Math.hypot(p[0] - x, p[1] - z);
    }

    private static double clamp(double v, double lo, double hi) {
        return hi < lo ? lo : Math.max(lo, Math.min(hi, v));
    }

    /**
     * The point the yaw aims at for a bot at place {@code s} (pure pursuit, {@link FlightControl}): {@code look} blocks
     * further on, at R at most; past R the final line.
     */
    double[] aim(double s, double look) {
        double l = length();
        if (s >= l - 1.0) {
            double[] p = point(s);
            return new double[]{p[0] + finalX * look, p[1] + finalZ * look};
        }
        return point(Math.min(l, s + look));
    }

    @Override public String toString() {
        return String.format("arc: %.0f blocks along the way (%.0f straight, a turn of %.0f degrees at radius %.0f, %.0f"
            + " straight into R)", length(), first, turnDegrees(), radius, last);
    }
}
