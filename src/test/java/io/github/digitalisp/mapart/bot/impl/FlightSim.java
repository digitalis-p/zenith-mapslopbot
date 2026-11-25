package io.github.digitalisp.mapart.bot.impl;

import java.util.HashSet;
import java.util.Set;

/**
 * A small model of the soul sand platform and of ZenithProxy's Bot in flight, for the elytra flight leg's tests
 * ({@link FlightControl}, {@link FlightPlanner}). Tile-relative coordinates: the tile's floor is soul sand at y 172
 * (top 172.875, feet level 173) for x 0..127, z 0..127; the walkway north of it is glass at y 173 (feet level 174)
 * for z -4..-1; the sorter wall stands at z -5 and beyond, from y 174 to 180; the side canals are 1 wide at x -1 and
 * x 128 (glass at y 169, water at y 170); a glass wall 2 high runs south of the tile at z 128 (y 173, 174).
 * Extra blocks: {@link #block}; the end rods: {@link #lights}; a built art layer of mixed heights: {@link #art}.
 *
 * <p>The Bot steps up (0.6) only on the ground; in the air any block top above the box's bottom stops it (vanilla's
 * collide: the step-up needs onGround or a downward collision in the same move). A carpet's edge is a wall to a glide
 * 0.1 over the soul sand (fly3).
 */
final class FlightSim {
    /** Full blocks put in by a test (x, y, z keys) and pointed dripstone cells. */
    final Set<String> blocks = new HashSet<>();
    final Set<String> tall = new HashSet<>();
    final Set<String> forbidden = new HashSet<>();
    final Set<String> unloaded = new HashSet<>();
    /**
     * The server's view distance in chunks around ({@link #loadX}, {@link #loadZ}) (a test moves it with the bot): the
     * chunks farther off are not loaded; -1: everything loaded but {@link #unloaded}.
     */
    int loadChunks = -1;
    double loadX;
    double loadZ;
    /**
     * The server's glide sinks this much more a tick than the controller's model (blocks/tick; 0: the same): the
     * look-ahead's error on a live server (its packets, its own physics).
     */
    double sinkBias;
    /** Water cells put in by a test (x, y, z keys), besides the canals'. */
    final Set<String> water = new HashSet<>();
    /**
     * Columns of the tile (x, z keys) without their soul sand: a shaft down to a floor at {@link #SHAFT_FLOOR} (the stasis
     * chunk loaders under the platform, live 2026-10-25: a bot fell 34 blocks into one).
     */
    final Set<String> holes = new HashSet<>();
    static final int SHAFT_FLOOR = 140;
    /** Cells (x, y, z keys) cleared of the model's blocks (the clear stand's model cuts its line). */
    final Set<String> cleared = new HashSet<>();
    /** Players' feet (x, y, z) for the controller and the planner (teammates on the art layer). */
    final java.util.List<double[]> players = new java.util.ArrayList<>();
    /** The flight tick from which the controller sees {@link #players} (0: from the take-off). */
    int playersFrom;
    /** The last {@link #flyPlan}'s height over the landing's floor and speed across when the players came (NaN: never). */
    double lastHeightAtPlayers = Double.NaN;
    double lastSpeedAtPlayers = Double.NaN;
    /** Bot ticks [from, to) the server is silent ({@link FlightControl#serverSilent}); -1: never. */
    int silentFrom = -1;
    int silentTo = -1;
    /** The bot tick from which the controller plans for boosts of up to {@link #raiseTo} ticks (a TPS dip in flight); -1: never. */
    int raiseAt = -1;
    int raiseTo;
    /** The art layer at y 173 ({@link #art}). */
    boolean art;
    /** The last {@link #fly}'s glide contacts ({@link Bot#contacts}). */
    int lastContacts;

    void block(int x, int y, int z) {
        blocks.add(x + "," + y + "," + z);
    }

    /**
     * The live platform's blocks over the tile (the world download's soul sand platform): end rods at y 178 (5 above the
     * floor) in rows every 9 blocks from z 7, every 16 along x from x -1 and x 7 in turn, and the counter's line across
     * the tile at y 178-179 (z 64 west of x 66, z 63 east of it). Full blocks here (an end rod is a quarter wide).
     */
    FlightSim lights() {
        for (int k = 0; k < 14; k++) {
            int z = 7 + 9 * k;
            for (int x = k % 2 == 0 ? -1 : 7; x <= 127; x += 16) block(x, 178, z);
        }
        for (int x = -2; x <= 129; x++) {
            int z = x < 66 ? 64 : 63;
            block(x, 178, z);
            block(x, 179, z);
        }
        return this;
    }

    /**
     * The clear stand over the end rods ({@link #lights} first), as the live platform's base copy has it: the counter's
     * line is one glass block at y 178 (its redstone wire on y 179 has no collision), z 64 west of x 65 and z 63 east of
     * x 62 (both at x 63-64), the stand's glass 2 x 4 at x 63-64, z 62-65, y 178 (its top 179.0, feet level 179; the
     * buttons beside it at y 179 have no collision); the walk's goal is (63, 179, 63). Open sky over it.
     */
    FlightSim stand() {
        for (int x = -2; x <= 129; x++) {
            int z = x < 66 ? 64 : 63;
            blocks.remove(x + ",178," + z);
            blocks.remove(x + ",179," + z);
        }
        for (int x = -2; x <= 129; x++) {
            if (x <= 64) block(x, 178, 64);
            if (x >= 63) block(x, 178, 63);
        }
        for (int x = 63; x <= 64; x++) {
            for (int z = 62; z <= 65; z++) block(x, 178, z);
        }
        return this;
    }

    /**
     * A built art layer at y 173 over the tile: fields of 16 x 16 cells of carpet (top 0.0625; three in four) or of
     * pressure plates (no collision: the soul sand's 0.875 is the floor), a few candles (0.375) and cobbled deepslate
     * blocks (1.0) anywhere. Deterministic.
     */
    FlightSim art() {
        art = true;
        return this;
    }

    /**
     * {@link #art} with {@code candles} and {@code blocks} cells in 1024 (the default 4 and 2; a map like harbor
     * with candles and cobbled deepslate in its palette has many more: a glide low over it meets walls everywhere).
     */
    FlightSim art(int candles, int blocks) {
        art = true;
        artCandles = candles;
        artBlocks = blocks;
        return this;
    }

    private int artCandles = 4;
    private int artBlocks = 2;

    /** The art layer's collision top at (x, 173, z) ({@link #art}). */
    double artTop(int x, int z) {
        int h = (((x * 73856093) ^ (z * 19349663)) >>> 4) & 0x3ff;
        if (h < artCandles) return 0.375;
        if (h < artCandles + artBlocks) return 1.0;
        int field = ((((x >> 4) * 83492791) ^ ((z >> 4) * 19349663)) >>> 3) & 3;
        return field == 0 ? 0 : 0.0625;
    }

    /** The world as {@link FlightPlanner.Terrain}. */
    FlightPlanner.Terrain terrain() {
        return new FlightPlanner.Terrain() {
            @Override public boolean loaded(int x, int z) {
                if (loadChunks >= 0 && (Math.abs((x >> 4) - ((int) Math.floor(loadX) >> 4)) > loadChunks
                    || Math.abs((z >> 4) - ((int) Math.floor(loadZ) >> 4)) > loadChunks)) return false;
                return !unloaded.contains(x + "," + z);
            }

            @Override public double top(int x, int y, int z) {
                return FlightSim.this.top(x, y, z);
            }

            @Override public boolean fluid(int x, int y, int z) {
                return !open && (x == -1 || x == 128) && z >= -4 && z <= 128 && y == 170 || water.contains(x + "," + y + "," + z);
            }

            @Override public boolean forbidden(int x, int y, int z) {
                return forbidden.contains(x + "," + y + "," + z);
            }

            @Override public boolean playerNear(int x, int y, int z) {
                for (double[] f : players) {
                    if (Math.abs(f[1] - y) <= FlightControl.PLAYER_DY
                        && FlightControl.cellDist(f[0], f[2], x, z) < FlightControl.PLAYER_MARGIN) return true;
                }
                return false;
            }
        };
    }

    /** A no-go box (every cell from x0, y0, z0 to x1, y1, z1). */
    FlightSim noGo(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) forbidden.add(x + "," + y + "," + z);
            }
        }
        return this;
    }

    /** A shaft through the tile's floor ({@link #holes}) over cells x0..x1, z0..z1. */
    FlightSim shaft(int x0, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) holes.add(x + "," + z);
        }
        return this;
    }

    /**
     * An open field instead of the platform: soul sand at y 172 everywhere, no walkway, wall, canal or tile edge (legs
     * longer than the tile: far storage, two-rocket flights).
     */
    FlightSim open() {
        open = true;
        return this;
    }

    private boolean open;

    double top(int x, int y, int z) {
        String k = x + "," + y + "," + z;
        if (blocks.contains(k)) return 1.0;
        if (tall.contains(k)) return 1.0;
        if (!cleared.isEmpty() && cleared.contains(k)) return 0;
        if (!holes.isEmpty() && holes.contains(x + "," + z)) return y == SHAFT_FLOOR ? 1.0 : 0;
        if (open) return y == 172 ? 0.875 : 0;
        boolean canal = (x == -1 || x == 128) && z >= -4 && z <= 128;
        if (canal) return y == 169 ? 1.0 : 0;
        if (x < -1 || x > 128) return 0;
        if (z >= 0 && z <= 127) return y == 172 ? 0.875 : y == 173 && art ? artTop(x, z) : 0;
        if (z == 128) return y == 172 || y == 173 || y == 174 ? 1.0 : 0;
        if (z >= -4 && z <= -1) return y == 173 ? 1.0 : 0;
        if (z <= -5 && z >= -9) return y >= 173 && y <= 180 ? 1.0 : 0;
        return 0;
    }

    /**
     * The floor height under the bot's footprint at (x, z) for feet at {@code y}: the highest top at or below y + 0.6
     * over the four corners of its box.
     */
    double floorAt(double x, double y, double z) {
        double best = Double.NEGATIVE_INFINITY;
        for (double ox : new double[]{-0.3, 0.3}) {
            for (double oz : new double[]{-0.3, 0.3}) {
                int bx = (int) Math.floor(x + ox);
                int bz = (int) Math.floor(z + oz);
                for (int cy = (int) Math.floor(y + 0.6); cy >= (int) Math.floor(y) - (holes.isEmpty() ? 3 : 40); cy--) {
                    double t = top(bx, cy, bz);
                    if (t > 0 && cy + t <= y + 0.6 + 1e-9) {
                        best = Math.max(best, cy + t);
                        break;
                    }
                }
            }
        }
        return best;
    }

    /**
     * A block in the bot's footprint at height {@code y} .. y + h whose top stands more than {@code step} over the
     * feet: a wall the move runs into (step 0.6 on the ground, 0 in the air).
     */
    boolean wallAt(double x, double y, double z, double h, double step) {
        for (double ox : new double[]{-0.3, 0.3}) {
            for (double oz : new double[]{-0.3, 0.3}) {
                int bx = (int) Math.floor(x + ox);
                int bz = (int) Math.floor(z + oz);
                for (int cy = (int) Math.floor(y) - 1; cy < y + h; cy++) {
                    double t = top(bx, cy, bz);
                    if (t > 0 && cy + t > y + step + 1e-9) return true;
                }
            }
        }
        return false;
    }

    /**
     * The box's move from (x0, z0) to (x1, z1) at feet {@code y} as vanilla makes it ({@code Entity.collide}): the longer
     * axis first, then the other; a wall ({@link #wallAt}'s rule) in any cell the box covers on the way.
     */
    boolean wallOnMove(double x0, double z0, double x1, double z1, double y, double h, double step) {
        boolean xFirst = Math.abs(x1 - x0) >= Math.abs(z1 - z0);
        double cx = xFirst ? x1 : x0;
        double cz = xFirst ? z0 : z1;
        return sweptWall(x0, z0, cx, cz, y, h, step) || sweptWall(cx, cz, x1, z1, y, h, step);
    }

    /**
     * The bottom of the lowest block over the head of the box at feet (x, y, z), {@code h} high, within 4 blocks
     * (infinity for none): a climb stops under it.
     */
    double roofOver(double x, double y, double z, double h) {
        double best = Double.POSITIVE_INFINITY;
        for (double ox : new double[]{-0.3, 0.3}) {
            for (double oz : new double[]{-0.3, 0.3}) {
                int bx = (int) Math.floor(x + ox);
                int bz = (int) Math.floor(z + oz);
                for (int cy = (int) Math.ceil(y + h - 1e-9); cy <= (int) Math.ceil(y + h) + 4; cy++) {
                    if (top(bx, cy, bz) > 0) {
                        best = Math.min(best, cy);
                        break;
                    }
                }
            }
        }
        return best;
    }

    /** {@link #wallOnMove}'s one axis: every cell the box covers from (ax, az) to (bx, bz). */
    boolean sweptWall(double ax, double az, double bx, double bz, double y, double h, double step) {
        int x0 = (int) Math.floor(Math.min(ax, bx) - 0.3);
        int x1 = (int) Math.floor(Math.max(ax, bx) + 0.3);
        int z0 = (int) Math.floor(Math.min(az, bz) - 0.3);
        int z1 = (int) Math.floor(Math.max(az, bz) + 0.3);
        for (int bxc = x0; bxc <= x1; bxc++) {
            for (int bzc = z0; bzc <= z1; bzc++) {
                for (int cy = (int) Math.floor(y) - 1; cy < y + h; cy++) {
                    double t = top(bxc, cy, bzc);
                    if (t > 0 && cy + t > y + step + 1e-9) return true;
                }
            }
        }
        return false;
    }

    /**
     * {@link Bot#step}: the old wall check, the box only where the move ends and at the higher of the two heights (it
     * missed a corner the box met on its way and a block top it sank past in the tick: local 10-25 14:44).
     */
    boolean endBoxOnly;

    /** The bot's box at feet (x, y, z), {@code h} high, meets a no-go cell. */
    boolean inNoGo(double x, double y, double z, double h) {
        if (forbidden.isEmpty()) return false;
        for (double ox : new double[]{-0.3, 0.3}) {
            for (double oz : new double[]{-0.3, 0.3}) {
                int bx = (int) Math.floor(x + ox);
                int bz = (int) Math.floor(z + oz);
                for (int cy = (int) Math.floor(y); cy < y + h; cy++) {
                    if (forbidden.contains(bx + "," + cy + "," + bz)) return true;
                }
            }
        }
        return false;
    }

    /**
     * A block met losing at most this (b/t) is no contact: the last crawl of a touch-down beside a block (a spiral's
     * descent at 0.01 b/t onto the art).
     */
    static final double CRAWL = 0.05;

    /** The Bot in the model. */
    static final class Bot {
        double x, y, z, vx, vy, vz;
        boolean onGround = true;
        boolean fallFlying;
        boolean wasJump;
        int rockets = 16;
        /** The server's view: ticks from a rocket's use to its boost, and how long it boosts. */
        int rocketDelay = 4;
        int rocketLife = 25;
        /** Ticks after a touch-down until the server's answer clears the glide flag. */
        int flagDelay = 3;
        int boostFrom = -1;
        int boostTo = -1;
        int tick;
        int groundSince = -1;
        boolean hitWall;
        /**
         * Moves a block stopped while gliding (a wall, a roof's edge, an end rod, a bump of the head), losing more than
         * {@link #CRAWL} b/t (endBoxOnly: at any speed).
         */
        int contacts;
        /** ... of them losing over 0.3 b/t across (the server's fly_into_wall damage; endBoxOnly: moving faster). */
        int fastContacts;
        /** Ticks the box stood in a no-go cell ({@link FlightSim#forbidden}: feet or head). */
        int noGoTicks;
        /** The most the bot moved in a tick on the ground while the glide was on (the slide's speed). */
        double maxGroundSpeed;
        double maxFall;
        double fallDistance;
        float yaw;
        float pitch;
        final FlightSim world;

        Bot(FlightSim world, double x, double y, double z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean boosting() {
            return tick >= boostFrom && tick < boostTo && fallFlying;
        }

        FlightControl.In in() {
            return new FlightControl.In(x, y, z, vx, vy, vz, onGround, fallFlying, boosting(), rockets, pitch, rockets > 0);
        }

        /** One Bot tick with these keys (rotation first, as ZenithProxy with botRotateBeforeInteract). */
        void step(FlightControl.Keys k) {
            tick++;
            yaw = k.yaw();
            pitch = k.pitch();
            if (k.rocket() && fallFlying && rockets > 0) {
                rockets--;
                boostFrom = tick + rocketDelay;
                boostTo = boostFrom + rocketLife;
            }
            if (k.jump() && !wasJump && !onGround && !fallFlying) fallFlying = true;
            if (k.jump() && onGround && !fallFlying) vy = Math.max(vy, ElytraPhysics.JUMP);
            double nx;
            double ny;
            double nz;
            if (fallFlying) {
                double[] v = ElytraPhysics.glideVelocity(vx, vy, vz, yaw, pitch);
                vx = v[0];
                vy = v[1];
                vz = v[2];
                if (!onGround) vy -= world.sinkBias;
                if (vy > -0.5 && fallDistance > 1) fallDistance = 1; // LivingEntity.checkSlowFallDistance
            } else {
                if (Math.abs(vx) < 0.003) vx = 0;
                if (Math.abs(vy) < 0.003) vy = 0;
                if (Math.abs(vz) < 0.003) vz = 0;
            }
            nx = x + vx;
            ny = y + vy;
            nz = z + vz;
            // the step-up: on the ground, or landing in this very move (the floor where the bot is catches the fall)
            double startFloor = world.floorAt(x, y, z);
            boolean landing = ny <= startFloor;
            double step = onGround || landing ? 0.6 : 0;
            double boxH = fallFlying ? 0.6 : 1.8;
            if (world.endBoxOnly) {
                // the old check: the box where the move ends, at the higher of the two heights; a full stop
                if (world.wallAt(nx, Math.max(ny, y), nz, boxH, step)) {
                    if (Math.hypot(vx, vz) > 0.3) hitWall = true;
                    if (fallFlying && !onGround) contacts++;
                    if (fallFlying && !onGround && Math.hypot(vx, vz) > 0.3) fastContacts++;
                    nx = x;
                    nz = z;
                    vx = 0;
                    vz = 0;
                }
            } else {
                // vanilla's move (Entity.collide): the height first (down onto the floor where the bot is, up to the
                // first block over the head there: a bump stops the climb), then the longer horizontal axis, then the
                // other, each at that height and up to the face of the first block it would enter: the box sweeps an
                // L. A stopped axis loses its speed; the server's fly_into_wall damage takes the horizontal speed lost
                // over 0.3 b/t (LivingEntity.travelFallFlying)
                if (vy > 0) {
                    double head = world.roofOver(x, y, z, boxH);
                    if (ny + boxH > head) {
                        ny = Math.max(y, head - boxH);
                        if (fallFlying && !onGround && vy > CRAWL) contacts++;
                        vy = 0;
                    }
                }
                double wy = landing ? Math.max(ny, startFloor) : ny;
                double h0 = Math.hypot(vx, vz);
                boolean xFirst = Math.abs(vx) >= Math.abs(vz);
                boolean hitX = false;
                boolean hitZ = false;
                double mx = x;
                double mz = z;
                for (int leg = 0; leg < 2; leg++) {
                    boolean alongX = (leg == 0) == xFirst;
                    double d = alongX ? vx : vz;
                    if (d == 0) continue;
                    if (!world.sweptWall(mx, mz, alongX ? mx + d : mx, alongX ? mz : mz + d, wy, boxH, step)) {
                        if (alongX) mx += d;
                        else mz += d;
                        continue;
                    }
                    double lo = 0;
                    double hi = 1;
                    for (int it = 0; it < 14; it++) {
                        double f = (lo + hi) / 2;
                        if (world.sweptWall(mx, mz, alongX ? mx + d * f : mx, alongX ? mz : mz + d * f, wy, boxH, step)) hi = f;
                        else lo = f;
                    }
                    if (alongX) {
                        mx += d * lo;
                        hitX = true;
                    } else {
                        mz += d * lo;
                        hitZ = true;
                    }
                }
                nx = mx;
                nz = mz;
                if (hitX || hitZ) {
                    if (hitX) vx = 0;
                    if (hitZ) vz = 0;
                    double lost = h0 - Math.hypot(vx, vz);
                    if (fallFlying && !onGround && lost > CRAWL) contacts++;
                    if (lost > 0.3) {
                        hitWall = true;
                        if (fallFlying && !onGround) fastContacts++;
                    }
                }
            }
            double floor = world.floorAt(nx, Math.max(ny, y), nz);
            boolean wasGround = onGround;
            if (ny <= floor) {
                if (!wasGround) maxFall = Math.max(maxFall, fallDistance);
                ny = floor;
                vy = 0;
                onGround = true;
                fallDistance = 0;
            } else {
                onGround = false;
                if (ny < y) fallDistance += y - ny;
            }
            if (onGround && fallFlying) maxGroundSpeed = Math.max(maxGroundSpeed, Math.hypot(nx - x, nz - z));
            x = nx;
            y = ny;
            z = nz;
            if (world.inNoGo(x, y, z, fallFlying && !onGround ? 0.6 : 1.8)) noGoTicks++;
            if (!fallFlying) {
                double f = onGround ? 0.6 * 0.91 : 0.91;
                vy = (vy - ElytraPhysics.GRAVITY) * 0.98;
                vx *= f;
                vz *= f;
                if (onGround) vy = 0;
            }
            if (boosting()) {
                double[] v = ElytraPhysics.boost(vx, vy, vz, yaw, pitch);
                vx = v[0];
                vy = v[1];
                vz = v[2];
            }
            if (onGround && fallFlying) {
                if (groundSince < 0) groundSince = tick;
                if (tick - groundSince >= flagDelay) fallFlying = false;
            } else if (!onGround) {
                groundSince = -1;
            }
            wasJump = k.jump();
        }
    }

    /** A flown leg: the result line of the test output and its numbers. */
    record Flight(String why, int ticks, double restErr, double across, double touchSpeed, double touchVy, int rockets,
                  boolean wall, double maxFall, double restX, double restZ) { }

    /** Durations 2 and 3: flights take off only past {@link FlightControl#preflight}, as {@link FlightLeg} flies them. */
    FlightSim gated() {
        gate = true;
        return this;
    }

    private boolean gate;
    /** The plan of the last {@link #fly} that flew; whether it flew with two rockets ({@link FlightControl#relay}). */
    FlightPlanner.Plan lastPlan;
    boolean lastRelay;
    /**
     * {@code build.elytraShortLandings} ({@link #gated} worlds): a flight onto a spot within this range of the goal
     * first ({@link FlightLeg#withSpot}, from {@link #spotMin} blocks); -1: off. The last {@link #fly}'s short landing's
     * refusal (null: none tried or it flew), and whether it flew onto a spot.
     */
    int spotRange = -1;
    double spotMin = 30;
    String lastSpotWhy;
    boolean lastSpot;

    /** {@link #spotRange} on: short landings within {@code range} of the goal. */
    FlightSim spots(int range) {
        spotRange = range;
        return this;
    }

    /**
     * Plans and flies from feet (sx, sy, sz) toward goal cell (gx, gy, gz); why "refused: ..." when the planner refuses,
     * "walked: ..." when {@link #gated} and the pre-flight check does.
     */
    Flight fly(double sx, double sy, double sz, int gx, int gy, int gz, FlightPlanner.Cfg pc, FlightControl.Cfg cc,
               int rocketDelay, int rocketLife, int flagDelay) {
        lastRelay = false;
        lastArc = null;
        lastMaxOff = 0;
        lastMaxOffTurn = 0;
        lastBoostEndAt = Double.NaN;
        simAt = 0;
        double leg = Math.hypot(gx + 0.5 - sx, gz + 0.5 - sz);
        boolean arcs = !Double.isNaN(arcMin) && cc.boostTicks() > FlightControl.boostMin(1) && leg >= arcMin;
        lastSpot = false;
        lastSpotWhy = null;
        if (spotRange >= 0 && gate && cc.boostTicks() > FlightControl.boostMin(1) && leg >= spotMin) {
            FlightLeg.Landing l = FlightLeg.withSpot(sx, sy, sz, gx, gy, gz, spotRange, terrain(), pc, cc, spotMin,
                !Double.isNaN(arcMin), Double.isNaN(arcMin) ? 0 : arcMin, 3);
            if (l.result().plan() != null) {
                lastSpot = true;
                lastRelay = l.relay();
                lastArc = l.result().plan().profile().path;
                return flyPlan(l.result().plan(), sx, sy, sz, cc.withRockets(lastRelay ? 2 : 1), rocketDelay, rocketLife,
                    flagDelay);
            }
            lastSpotWhy = l.result().refusal();
        }
        FlightPlanner.Result r;
        String straight = null;
        if (arcs && leg < FlightControl.minLegBlocks(3, 60, cc.boostWorst())) {
            r = null; // build.elytraArcLegs: under the straight flight's shortest leg, as FlightLeg
            straight = "under the straight flight's shortest leg";
        } else {
            r = FlightPlanner.plan(sx, sy, sz, gx, gy, gz, terrain(), pc);
            if (r.plan() == null) {
                straight = "refused: " + r.refusal();
                r = null;
            }
        }
        if (r != null && gate && cc.boostTicks() > FlightControl.boostMin(1)) {
            FlightLeg.Landing l = FlightLeg.withLanding(r, sx, sy, sz, gx, gy, gz, terrain(), pc, cc);
            r = l.result();
            if (r.plan() == null) {
                straight = "walked: " + r.refusal();
                r = null;
            } else {
                lastRelay = l.relay();
                if (lastRelay) cc = cc.withRockets(2);
            }
        }
        if (r == null && arcs) {
            FlightLeg.Landing l = FlightLeg.withArc(sx, sy, sz, gx, gy, gz, terrain(), pc, cc, arcMin);
            if (l.result().plan() != null) {
                r = l.result();
                cc = cc.withRockets(1);
                lastArc = r.plan().profile().path;
            } else {
                straight = straight + "; arc: " + l.result().refusal();
            }
        }
        if (r == null) {
            String why = straight.startsWith("refused: ") || straight.startsWith("walked: ") ? straight : "walked: " + straight;
            return new Flight(why, 0, 0, 0, 0, 0, 0, false, 0, 0, 0);
        }
        return flyPlan(r.plan(), sx, sy, sz, cc, rocketDelay, rocketLife, flagDelay);
    }

    /** Flies {@code plan} from feet (sx, sy, sz) as planned, no pre-flight check ({@link #fly}'s second half). */
    Flight flyPlan(FlightPlanner.Plan plan, double sx, double sy, double sz, FlightControl.Cfg cc, int rocketDelay,
                   int rocketLife, int flagDelay) {
        lastPlan = plan;
        lastArc = plan.profile().path;
        lastMaxOff = 0;
        lastMaxOffTurn = 0;
        lastBoostEndAt = Double.NaN;
        simAt = 0;
        FlightPlanner.Profile p = plan.profile();
        FlightControl fc = new FlightControl(p, cc, 0f);
        if (lastRelay) fc.relay();
        Bot b = new Bot(this, sx, sy, sz);
        b.rocketDelay = rocketDelay;
        b.rocketLife = rocketLife;
        b.flagDelay = flagDelay;
        int startRockets = b.rockets;
        String why = "timeout";
        lastHeightAtPlayers = Double.NaN;
        lastSpeedAtPlayers = Double.NaN;
        double[][] was = new double[2000][];
        lastTeleportPhase = null;
        for (int i = 0; i < 2000; i++) {
            was[i] = new double[]{b.x, b.y, b.z};
            if (i == teleportAt) {
                // the server moves the bot: back to where it was (a setback) or by an offset (a teleport)
                if (teleportBack > 0) {
                    double[] q = was[Math.max(0, i - teleportBack)];
                    b.x = q[0];
                    b.y = q[1];
                    b.z = q[2];
                } else if (tpTowardMiddle) {
                    b.x += Math.signum(64 - b.x) * Math.abs(tpDx);
                    b.y += tpDy;
                    b.z += Math.signum(64 - b.z) * Math.abs(tpDz);
                } else {
                    b.x += tpDx;
                    b.y += tpDy;
                    b.z += tpDz;
                }
                if (teleportStops) {
                    b.vx = 0;
                    b.vy = 0;
                    b.vz = 0;
                }
                double fl = floorAt(b.x, b.y + 0.6, b.z);
                b.onGround = b.y <= fl + 1e-6;
                if (b.onGround) b.y = fl;
                if (teleportSaysGround) b.onGround = true; // the packet's flag, wherever the bot is (local 10x 2026-10-25)
                lastTeleportPhase = fc.phase().name();
                fc.teleported();
            }
            if (i == playersFrom && !players.isEmpty()) {
                fc.players(players); // a teammate walks onto the landing
                lastHeightAtPlayers = b.onGround ? 0 : b.y - p.surface;
                lastSpeedAtPlayers = Math.hypot(b.vx, b.vz);
            }
            if (i == bailAt) fc.endInTheAir("forced in the test"); // as a walk that stops in the air
            fc.serverSilent(i >= silentFrom && i < silentTo);
            if (raiseAt >= 0 && i >= raiseAt) fc.raiseWorst(raiseTo);
            FlightControl.Keys k = fc.tick(b.in());
            if (k.abort() != null && !k.done()) {
                why = "abort: " + k.abort();
                if (teleportAt >= 0) {
                    // the walk takes over: no jump, the look kept; the jump's fall (and any glide on) comes down
                    for (int j = 0; j < 400 && !(b.onGround && !b.fallFlying); j++) {
                        b.step(new FlightControl.Keys(k.yaw(), k.pitch(), false, false, false, null));
                    }
                }
                break;
            }
            if (k.done()) {
                why = fc.bailReason() == null ? "landed" : "bailed: " + fc.bailReason();
                break;
            }
            boolean boosted = b.boosting();
            int before = b.contacts;
            b.step(k);
            if (trace != null) {
                trace.append(String.format("%3d %7.2f %7.2f %7.2f v %5.2f %5.2f yaw %6.1f pitch %5.1f%s%s%s at %.1f top %.2f%s score %.1f short %.1f " + fc.phase() + "%n",
                    i, b.x, b.y, b.z, Math.hypot(b.vx, b.vz), b.vy, k.yaw(), k.pitch(), b.onGround ? " ground" : "",
                    b.boosting() ? " boost" : "", k.rocket() ? " rocket" : "", simAt,
                    p.top((int) Math.floor(b.x), (int) Math.floor(b.z)), b.contacts > before ? " CONTACT" : "",
                    fc.lastChoice() == null ? Double.NaN : fc.lastChoice().score(),
                    fc.lastChoice() == null ? Double.NaN : fc.lastChoice().shortBy()));
            }
            if (lastArc != null) {
                simAt = lastArc.track(b.x, b.z, simAt);
                double[] q = lastArc.point(simAt);
                double off = Math.hypot(b.x - q[0], b.z - q[1]);
                if (!b.onGround) {
                    if (simAt <= lastArc.turnEnd() + 2) lastMaxOffTurn = Math.max(lastMaxOffTurn, off);
                    else lastMaxOff = Math.max(lastMaxOff, off);
                }
                if (boosted && !b.boosting()) lastBoostEndAt = simAt;
            }
        }
        double err = p.along(b.x, b.z) - p.length();
        lastContacts = b.contacts;
        lastFastContacts = b.fastContacts;
        lastNoGo = b.noGoTicks;
        lastGroundSpeed = b.maxGroundSpeed;
        lastBurn = fc.burnWords();
        lastBrakeTick = fc.brakeTick();
        lastGroundBoost = fc.groundBoostTicks();
        lastPlayerWhy = fc.playerWhy();
        lastOffT = Math.hypot(b.x - p.restX, b.z - p.restZ);
        lastTouchX = fc.touchX();
        lastTouchZ = fc.touchZ();
        lastMoving = Math.hypot(b.vx, b.vz);
        FlightPlanner.Terrain tw = terrain();
        int fx = (int) Math.floor(b.x);
        int fz = (int) Math.floor(b.z);
        lastWet = tw.fluid(fx, (int) Math.floor(b.y), fz) || tw.fluid(fx, (int) Math.floor(b.y + 0.2), fz);
        lastY = b.y;
        return new Flight(why, fc.ticks(), err, p.across(b.x, b.z), fc.touchSpeed(), fc.touchVy(),
            startRockets - b.rockets, b.hitWall, b.maxFall, b.x, b.z);
    }

    /**
     * A leg with further flights ({@code build.elytraHops}, as {@link FlightLeg} flies them): after a landing at least
     * {@code minLeg} blocks short of the goal, the next flight from the rest point, up to {@code hops} more. Each flight
     * in turn (the last one may be "walked: ..." or "refused: ..."); {@link #lastContacts} and {@link #lastRelay} are
     * then the last flight's, {@code contactsAll} the sum and {@code relays} the count.
     */
    java.util.List<Flight> leg(double sx, double sy, double sz, int gx, int gy, int gz, FlightPlanner.Cfg pc,
                               FlightControl.Cfg cc, int rocketDelay, int rocketLife, int flagDelay, int hops, double minLeg) {
        java.util.List<Flight> out = new java.util.ArrayList<>();
        contactsAll = 0;
        relays = 0;
        double x = sx;
        double y = sy;
        double z = sz;
        for (int i = 0; i <= hops; i++) {
            Flight f = fly(x, y, z, gx, gy, gz, pc, cc, rocketDelay, rocketLife, flagDelay);
            out.add(f);
            contactsAll += lastContacts;
            if (!f.why().equals("landed")) break;
            if (lastRelay) relays++;
            x = f.restX();
            z = f.restZ();
            y = floorAt(x, Math.max(y, gy) + 1.0, z);
            if (Math.hypot(gx + 0.5 - x, gz + 0.5 - z) < minLeg) break;
        }
        return out;
    }

    int contactsAll;
    int relays;
    /**
     * {@code build.elytraArcLegs} in {@link #fly}: legs from this many blocks fly an arc when the straight flight is too
     * short or refused (NaN: off); the last flight's arc (null: straight) and the most it strayed off its final line in
     * the air (blocks, from its place on the way tracked as {@link FlightPath#track}).
     */
    double arcMin = Double.NaN;
    FlightPath lastArc;
    double lastMaxOff;
    /** ... the most it strayed in the air up to 2 blocks past the turn, and its place on the way when the boost ended. */
    double lastMaxOffTurn;
    double lastBoostEndAt;
    private double simAt;
    /** Not null: {@link #flyPlan} writes a line a tick into it (the tests' traces). */
    StringBuilder trace;
    /** {@link #fly}: the controller's tick at which the flight is made to bail out ({@link FlightControl#endInTheAir}), or -1. */
    int bailAt = -1;
    /**
     * {@link #flyPlan}: the controller's tick at which the server moves the bot ({@link FlightControl#teleported}), or -1:
     * with {@code teleportBack} over 0 back to where it was that many ticks before (a setback), else by (tpDx, tpDy,
     * tpDz) (a teleport); {@code teleportStops}: the move ends its speed. The controller's phase then, or null.
     */
    int teleportAt = -1;
    int teleportBack;
    double tpDx;
    double tpDy;
    double tpDz;
    boolean teleportStops = true;
    /** The bot's ground flag is set by the teleport for the next tick, in the air or not. */
    boolean teleportSaysGround;
    /** The offset's x and z toward the tile's middle (a teleport off the corridor that stays over the tile). */
    boolean tpTowardMiddle;
    String lastTeleportPhase;
    /** The last {@link #fly} came to rest in water (feet cell or the one 0.2 above it), and its feet height. */
    boolean lastWet;
    double lastY;
    /**
     * The last {@link #flyPlan}'s numbers: glide stops faster than 0.3 b/t, ticks in a no-go cell, the fastest tick on the
     * ground with the glide on, the burn in words, the brake's tick, ticks on the ground under a boost, a landing given up
     * for a player, the rest's distance from R (T), the touch-down, the speed across the ground when it ended.
     */
    int lastFastContacts;
    int lastNoGo;
    double lastGroundSpeed;
    String lastBurn = "";
    int lastBrakeTick = -1;
    int lastGroundBoost;
    String lastPlayerWhy;
    double lastOffT;
    double lastTouchX;
    double lastTouchZ;
    double lastMoving;
}
