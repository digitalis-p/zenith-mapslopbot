package io.github.digitalisp.mapart.bot.impl;

/**
 * The elytra's flight physics as ZenithProxy 3.7.2's {@code Bot} runs them (vanilla 1.21.4 client), pure, for the
 * flight leg's planning and its controller's look-ahead ({@link FlightControl}): {@code Bot.travelFallFlying} (the
 * velocity update while the fall-flying flag is set, then the move), the firework boost of {@code Bot.tickEntities}
 * (after the move, every tick while a rocket attached to the bot exists), the jump and the plain fall of
 * {@code travelInAir} without keys. The bot itself moves only by ZenithProxy's own simulation; this copy only predicts.
 *
 * <p>Order of one fall-flying tick in the Bot: velocity components under 0.003 set to 0, the glide update with this
 * tick's rotation, the move (collisions), then the rocket boost. Coordinates: feet position, blocks; velocity in blocks
 * per tick; yaw/pitch in degrees, pitch negative = looking up (ZenithProxy's {@code MathHelper.calculateViewVector}).
 */
final class ElytraPhysics {
    private ElytraPhysics() { }

    /** The Bot's gravity attribute default. */
    static final double GRAVITY = 0.08;
    /** The jump's vertical speed (no jump boost, a floor with jump factor 1). */
    static final double JUMP = 0.42;
    /** The fall-flying pose's box: 0.6 wide and 0.6 high ({@code Pose.FALL_FLYING}). */
    static final double BOX_HALF = 0.3;
    static final double BOX_HEIGHT = 0.6;

    /**
     * Position and velocity of the bot at the start of a tick; {@code along}: an arc flight's place on its way
     * ({@link FlightPath#track}, NaN for a straight one), which the look-ahead carries from tick to tick;
     * {@code braking}: a short landing's brake is on ({@link FlightBrake}), {@code yaw} its last look (the turn's limit
     * a tick).
     */
    record State(double x, double y, double z, double vx, double vy, double vz, double along, boolean braking, float yaw) {
        State(double x, double y, double z, double vx, double vy, double vz) {
            this(x, y, z, vx, vy, vz, Double.NaN);
        }

        State(double x, double y, double z, double vx, double vy, double vz, double along) {
            this(x, y, z, vx, vy, vz, along, false, Float.NaN);
        }

        double hSpeed() {
            return Math.hypot(vx, vz);
        }

        State at(double nx, double ny, double nz) {
            return new State(nx, ny, nz, vx, vy, vz, along, braking, yaw);
        }

        State along(double a) {
            return new State(x, y, z, vx, vy, vz, a, braking, yaw);
        }

        /** This state with a short landing's brake on, its look's yaw {@code look} this tick. */
        State braked(float look) {
            return new State(x, y, z, vx, vy, vz, along, true, look);
        }
    }

    /** ZenithProxy's {@code MathHelper.calculateViewVector}: the unit look vector. */
    static double[] look(double yaw, double pitch) {
        double pr = Math.toRadians(pitch);
        double yr = Math.toRadians(-yaw);
        double pc = Math.cos(pr);
        return new double[]{Math.sin(yr) * pc, -Math.sin(pr), Math.cos(yr) * pc};
    }

    /** The yaw that faces from (x, z) toward (tx, tz), in (-180, 180]. */
    static float yawTo(double x, double z, double tx, double tz) {
        return (float) Math.IEEEremainder(Math.toDegrees(Math.atan2(tz - z, tx - x)) - 90, 360);
    }

    /** {@code Bot.travelFallFlying}'s velocity update (without the move): the new velocity. */
    static double[] glideVelocity(double vx, double vy, double vz, double yaw, double pitch) {
        if (Math.abs(vx) < 0.003) vx = 0;
        if (Math.abs(vy) < 0.003) vy = 0;
        if (Math.abs(vz) < 0.003) vz = 0;
        double[] l = look(yaw, pitch);
        double pitchRad = Math.toRadians((float) pitch);
        double hLook = Math.sqrt(l[0] * l[0] + l[2] * l[2]);
        double hVel = Math.sqrt(vx * vx + vz * vz);
        double lookLen = Math.sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2]);
        double cos = Math.cos(pitchRad);
        cos = cos * cos * Math.min(1.0, lookLen / 0.4);
        vy += GRAVITY * (-1.0 + cos * 0.75);
        if (vy < 0 && hLook > 0) {
            double m = vy * -0.1 * cos;
            vx += l[0] * m / hLook;
            vy += m;
            vz += l[2] * m / hLook;
        }
        if (pitchRad < 0 && hLook > 0) {
            double m = hVel * -Math.sin(pitchRad) * 0.04;
            vx += -l[0] * m / hLook;
            vy += m * 3.2;
            vz += -l[2] * m / hLook;
        }
        if (hLook > 0) {
            vx += (l[0] / hLook * hVel - vx) * 0.1;
            vz += (l[2] / hLook * hVel - vz) * 0.1;
        }
        return new double[]{vx * 0.99, vy * 0.98, vz * 0.99};
    }

    /** The rocket boost of {@code Bot.tickEntities} added to {@code v} with the look of this tick. */
    static double[] boost(double vx, double vy, double vz, double yaw, double pitch) {
        double[] l = look(yaw, pitch);
        return new double[]{
            vx + l[0] * 0.1 + (l[0] * 1.5 - vx) * 0.5,
            vy + l[1] * 0.1 + (l[1] * 1.5 - vy) * 0.5,
            vz + l[2] * 0.1 + (l[2] * 1.5 - vz) * 0.5};
    }

    /** One fall-flying tick in free space: glide update, move, then the boost when a rocket is attached. */
    static State fly(State s, double yaw, double pitch, boolean boosting) {
        double[] v = glideVelocity(s.vx(), s.vy(), s.vz(), yaw, pitch);
        double nx = s.x() + v[0];
        double ny = s.y() + v[1];
        double nz = s.z() + v[2];
        if (boosting) v = boost(v[0], v[1], v[2], yaw, pitch);
        return new State(nx, ny, nz, v[0], v[1], v[2]);
    }

    /** One tick of a plain fall without keys ({@code travelInAir} in the air: gravity, then drag 0.91 / 0.98). */
    static State fall(State s) {
        double vx = Math.abs(s.vx()) < 0.003 ? 0 : s.vx();
        double vy = Math.abs(s.vy()) < 0.003 ? 0 : s.vy();
        double vz = Math.abs(s.vz()) < 0.003 ? 0 : s.vz();
        double nx = s.x() + vx;
        double ny = s.y() + vy;
        double nz = s.z() + vz;
        return new State(nx, ny, nz, vx * 0.91, (vy - GRAVITY) * 0.98, vz * 0.91);
    }

    /** The ground's friction a tick without the glide: slipperiness 0.6 × 0.91. */
    static final double GROUND_FRICTION = 0.546;

    /**
     * Blocks along the flight a bot that touched down at {@code hSpeed} slides on, with the flight leg's landing: the
     * client keeps the fall-flying physics on the ground for {@code flagTicks} more ticks (the server clears the flag
     * when it sees the feet on the ground; the client keeps it until that metadata arrives, about a round trip). In
     * those ticks a rocket that still boosts ({@code boostTicks} left) pushes on along the look (the bot looks ahead
     * then); after it the bot turns its back to the flight (90 degrees, then 180), and the glide's turn of the velocity
     * toward the look ({@code (look × speed - v) × 0.1}) brakes it to 0.9, then 0.8 of the speed a tick (× 0.99 drag).
     * Then the ground's friction: the full speed one more tick, × 0.546 a tick after (about 2.2 times the speed). The
     * brake bounds the slide to about 4.3 times the speed however late the server's answer comes. Pure.
     */
    static double slide(double hSpeed, int flagTicks, int boostTicks) {
        double l = Math.cos(Math.toRadians(FlightControl.GROUND_PITCH));
        double v = hSpeed;
        double d = 0;
        int turned = 0;
        for (int i = 0; i < flagTicks; i++) {
            if (i < boostTicks) {
                v *= 0.99;
                d += v;
                v = v + l * 0.1 + (l * 1.5 - v) * 0.5;
            } else {
                turned++;
                v *= (turned == 1 ? 0.9 : 0.8) * 0.99;
                d += v;
            }
        }
        return d + v / (1 - GROUND_FRICTION);
    }
}
