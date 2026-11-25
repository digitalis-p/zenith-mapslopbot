package io.github.digitalisp.mapart.bot.impl;

/**
 * A short landing's brake ({@code build.elytraShortLandings}): the last part of
 * a flight onto a small spot at T, as a player lands precisely. Once the boost is over and the bot is within
 * {@link #trigger} of T, it looks against its way and comes down slowly onto T: each tick the look's yaw points along
 * {@code v_d - 0.9 v} (horizontal), where {@code v} is the velocity and {@code v_d} the velocity wanted, toward T at
 * {@link #K} times the distance and at most {@link #V_MAX} a tick. Vanilla's glide turns the velocity a tenth of the way
 * toward the look each tick at the same speed ({@code v + (look * |v| - v) * 0.1}), so the velocity after the tick is the
 * point of the circle around {@code 0.9 v} (radius {@code 0.1 |v|}) nearest {@code v_d}: with the look against the way
 * the speed falls by a fifth a tick (1.6 to 0.1 blocks a tick in 10 ticks over about 6 blocks); slow, the glide's lift
 * pushes along the look (about 0.017 blocks a tick each tick), so the bot drifts toward T and holds over it while it
 * sinks at about 0.15 blocks a tick (pitch 0: the glide's least sink; vanilla keeps the fall distance at 1 while the
 * sink is under 0.5). The yaw turns at most {@link #YAW_RATE} a tick (the landing's quarter turn, as on the ground).
 * Pure; the look-ahead ({@link FlightControl}) flies the same rule.
 */
final class FlightBrake {
    private FlightBrake() { }

    /** The brake starts this many times the horizontal speed (blocks a tick) from T: about 7 blocks at 1.6. */
    static final double LEAD = 4.4;
    /** The velocity wanted: toward T, this times the distance a tick ... */
    static final double K = 0.15;
    /** ... at most this many blocks a tick. */
    static final double V_MAX = 0.4;
    /** The most the brake's yaw turns in a tick, degrees. */
    static final float YAW_RATE = 90f;
    /**
     * Over T, still and high: down faster. More than {@link #FAST_HEIGHT} over the spot's floor, within
     * {@link #FAST_NEAR} of T and slower than {@link #FAST_SLOW}: pitch {@link #FAST_PITCH} (a sink of about 0.25 blocks a
     * tick, under vanilla's 0.5 that counts as a fall).
     */
    static final double FAST_HEIGHT = 2.5;
    static final double FAST_NEAR = 1.0;
    static final double FAST_SLOW = 0.25;
    static final float FAST_PITCH = 15f;

    /** How far from T (horizontally) the brake starts at horizontal speed {@code h}. */
    static double trigger(double h) {
        return LEAD * h;
    }

    /** The brake's yaw at (x, z) with velocity (vx, vz) toward T (tx, tz), from the last look {@code last} (NaN: any). */
    static float yaw(double x, double z, double vx, double vz, double tx, double tz, float last) {
        double dx = tx - x;
        double dz = tz - z;
        double d = Math.hypot(dx, dz);
        double s = Math.min(V_MAX, K * d);
        double wx = (d > 1e-9 ? dx / d * s : 0) - 0.9 * vx;
        double wz = (d > 1e-9 ? dz / d * s : 0) - 0.9 * vz;
        if (Math.hypot(wx, wz) < 1e-6) return Float.isNaN(last) ? 0f : last;
        float want = ElytraPhysics.yawTo(0, 0, wx, wz);
        if (Float.isNaN(last)) return want;
        float turn = (float) Math.IEEEremainder(want - last, 360);
        turn = Math.max(-YAW_RATE, Math.min(YAW_RATE, turn));
        return (float) Math.IEEEremainder(last + turn, 360);
    }

    /** The brake's pitch: 0 (the least sink), or {@link #FAST_PITCH} over T, still and high ({@code surface}: the spot's floor). */
    static float pitch(double x, double y, double z, double vx, double vz, double tx, double tz, double surface) {
        boolean high = y - surface > FAST_HEIGHT;
        boolean over = Math.hypot(tx - x, tz - z) < FAST_NEAR;
        boolean slow = Math.hypot(vx, vz) < FAST_SLOW;
        return high && over && slow ? FAST_PITCH : 0f;
    }
}
