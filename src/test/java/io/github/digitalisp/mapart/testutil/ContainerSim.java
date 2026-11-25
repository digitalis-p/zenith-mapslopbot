package io.github.digitalisp.mapart.testutil;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.Aim;
import io.github.digitalisp.mapart.bot.impl.Approach;
import io.github.digitalisp.mapart.bot.impl.ContainerRun;
import io.github.digitalisp.mapart.bot.impl.GoalNearXZ;
import io.github.digitalisp.mapart.bot.impl.InputGate;
import io.github.digitalisp.mapart.bot.impl.PlacePipeline;
import io.github.digitalisp.mapart.bot.impl.PlaceRun;
import io.github.digitalisp.mapart.bot.impl.StandSpots;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A block world around containers for {@link Approach} and {@link StandSpots} without ZenithProxy. Blocks have
 * collision and interaction (raycast) boxes as in ZenithProxy's 1.21.4 data: chests 1/16 inset and 14/16 high (a
 * double chest's half reaches its neighbour), wall signs a 2/16 board from 4.5/16 to 12.5/16 high against the block
 * they hang on with NO collision, carpets 1/16, everything else a full cube. The raycast is a voxel walk from the eye
 * that stops at the first interaction box, like ZenithProxy's {@code RaycastHelper}: a wall sign hides the face behind
 * it. The player stands on the floor (eye 1.62 above the feet), turns with the rotation of the last movement packet and
 * moves with its own movement keys (centring); Baritone is a shortest path over standable cells (a step up costs a
 * jump) that takes walking time and leaves the bot up to 0.2 off the goal cell's centre. The "server" opens a target
 * container on a click that hits it, and applies a use as vanilla 1.21 does: a button is pressed for 20 ticks, a lever
 * flips, a note block's pitch cycles (0..24) and every observer facing it fires; nothing else changes. A placement
 * ({@link PlaceRun}): a click on the support face of the cell places {@link #placeItem}, except that a plain click on a
 * block with a right-click action (a crafting table) opens it instead; only a sneak-click places there. Sneaking
 * lowers the eye to 1.27.
 *
 * <p><b>Menus, as ZenithProxy 3.7.2 handles them.</b> A click that hits a container ({@link #opensMenu}: chests,
 * barrels, shulker boxes, hoppers, the cartography table, anvils) opens its menu from the next tick on. While a menu
 * is open ZenithProxy's Bot resets the movement input, keeps the rotation and completes every input request FALSE
 * ({@code Bot.tick}: "stop movement and interaction inputs while a container is open"): no key moves the bot, nothing
 * counts as executed ({@link #canClick} is false). The menu's inventory request ({@link ContainerRun.Io}) runs one
 * click per {@link #clickDelay} ticks, and with {@link #ncpStrict} ZenithProxy closes the menu once it completed.
 * A request submitted while the last movement packet pressed keys or moved the bot counts in {@link #multiActions}
 * (Grim MultiActionsC; must stay 0).
 *
 * <p>Per tick: {@link #beginTick}, {@code Approach.tick} / {@code ContainerRun.tick}, {@link #endTick} (the inventory
 * request, then the movement packet).
 */
public final class ContainerSim implements PlaceRun.Io<ContainerSim.Shot>, ContainerRun.Io {
    public static final double WALK = 0.2158;
    public static final BlockSpec STONE = BlockSpec.of("minecraft:stone");
    public static final BlockSpec GLASS = BlockSpec.of("minecraft:glass");

    /** A rotation, the block and face it was aimed at, and the hit point. */
    public record Shot(float yaw, float pitch, Pos block, int face, double hx, double hy, double hz)
        implements PlacePipeline.Rotation { }

    /** First interaction box a ray hits: block, face code ({@link Aim#DOWN} ..), point. */
    public record Hit(Pos block, int face, double x, double y, double z) { }

    private final Map<Pos, BlockSpec> blocks = new HashMap<>();
    private final Random rnd;
    /** Blocks the approach is for (both halves of a double chest). */
    public List<Pos> targets = List.of();
    /** Shapes, block-local {minX, minY, minZ, maxX, maxY, maxZ}; replaceable (e.g. by ZenithProxy's real data). */
    public Function<BlockSpec, List<double[]>> interactionShape = ContainerSim::defaultInteraction;
    public Function<BlockSpec, List<double[]>> collisionShape = ContainerSim::defaultCollision;

    // player
    public double x;
    public double y;
    public double z;
    private float yaw;
    private float pitch = 20;
    private boolean onGround = true;

    // this tick's input
    private long now;
    private boolean submitted;
    private boolean prevSubmitted;
    private @Nullable Float reqYaw;
    private @Nullable Float reqPitch;
    private int fwd;
    private int strafe;

    // Baritone
    private @Nullable GoalNearXZ walkGoal;
    private double @Nullable [] walkDest;
    private int walkTicksLeft;
    private String walkError = "";
    /** Goal cells Baritone finds no path to (as if blocked by something the search cannot see). */
    public final Set<Pos> unpathable = new HashSet<>();
    /** Floors the stand-spot search takes ({@link StandSpots.World#walksOn}; default: any, as without the check). */
    public Predicate<BlockSpec> walksOn = b -> true;
    /** Failed Baritone path searches to {@link #unpathable} goals, one per attempt (a walk retries a failed path). */
    public int failedPaths;
    /** The Baritone stand-in searches this far from the bot (horizontally). */
    public int routeRadius = 48;
    /**
     * Momentum (off by default): Baritone arrives at the goal cell's near edge at {@link #arriveSpeed} blocks per tick
     * along its walk and goes on at that speed for {@link #arriveLateTicks} more ticks whatever the bot presses (live,
     * 2b2t replay: it enters the cell at sprint speed, 0.30 per tick, and the next ticks still move it as fast); then
     * the bot keeps {@link #FRICTION} of its speed each tick and movement keys add 0.1 per tick (walking speed 0.22).
     * Off: Baritone stops up to 0.2 off the cell centre, standing, and every tick with keys moves it 0.1.
     */
    public boolean momentum;
    /** A cell the stand-spot search sees a face centre from, but from which the bot's own aim finds none (live misses). */
    public @Nullable Pos hideAfterSearch;
    public double arriveSpeed = 0.30;
    public int arriveLateTicks = 1;
    public static final double FRICTION = 0.546;
    private double vx;
    private double vz;
    private int lateLeft;
    /** Where the bot may stand ({@code StandSpots.World.allowed}). */
    public Predicate<Pos> allowedCells = p -> true;

    // placing (PlaceRun.Io)
    /** The face the approach may use per target (face codes), or null for any face. */
    public @Nullable Map<Pos, Integer> onlyFace;
    /** Every input sneaks (set by {@link #aimAt}). */
    public boolean sneakToClick;
    /** The bot crouches (the input of the last movement packet sneaked). */
    public boolean sneaking;
    private boolean sneakReq;
    /** The cell a placement fills: no stand cell covers it, no click while the box touches it. */
    public @Nullable Pos keepClear;
    /** What a click on a support face of {@link #keepClear} places (null: no placing). */
    public @Nullable BlockSpec placeItem;
    /** Items of {@link #placeItem} carried. */
    public int carried = 1;
    /** {@link #hold} answers false this many times (the item is moved into the hand meanwhile). */
    public int holdDelay;
    public int placed;
    /** Plain clicks on a support with a right-click action: the server opened it instead of placing. */
    public int openedInstead;
    /** Clicks while the bot's box touched the cell (the server refuses the placement). */
    public int boxRejects;
    /** Whether the last click was made sneaking. */
    public boolean clickSneaking;

    // the server's side of a use
    /** Clicks on a target the server drops (lost packets) before it applies one. */
    public int dropClicks;
    /** Observer pulses caused by uses. */
    public int observerFires;
    /** Uses applied, as "id at [x,y,z] -> new state". */
    public final List<String> uses = new ArrayList<>();
    private final Map<Pos, Long> buttonReleaseAt = new HashMap<>();

    // records
    public int clicks;
    public int baritoneWalks;
    /** Blocks Baritone walked in all (the route lengths of {@link #baritoneWalks}; a jump counts one more) ... */
    public double walked;
    /** ... and each walk's. */
    public final List<Double> walkLengths = new ArrayList<>();
    /** Clicks in a tick that also requested a rotation change (must stay 0: look first, click on the next tick). */
    public int rotateAndClick;
    /** Clicks in a tick with movement keys pressed (must stay 0). */
    public int clickWhileMoving;
    /** Clicks whose rotation was not the one in effect (must stay 0). */
    public int clickOffRotation;
    public @Nullable Shot lastClick;
    public double @Nullable [] clickEye;
    public long clickTick = -1;
    /** Tick whose movement packet sent the rotation the last click was made with. */
    public long aimedTick = -1;
    private long rotationTick = -1;
    /** The container the last click opened (server side), or null. */
    public @Nullable Pos opened;
    public final List<String> log = new ArrayList<>();

    // menus (ZenithProxy's Bot drops every input request while one is open)
    /** Blocks whose menu a click opens. */
    public Predicate<BlockSpec> opensMenu = ContainerSim::menuBlock;
    /** Clicks of the next inventory request in a menu (0 = nothing to click, a scan) ... */
    public int menuClicks;
    /** ... and ticks between them. */
    public int clickDelay = 1;
    /** ZenithProxy closes the menu after every completed request ({@code client.inventory.ncpStrict}). */
    public boolean ncpStrict = true;
    /** Ticks the bot keeps sliding after a menu opened (speed left over from walking). */
    public int slideAfterOpen;
    /** Another module's inventory request executes for this many more ticks. */
    public int otherInventoryTicks;
    /** Requests the InventoryManager rejects (a higher-priority one is queued) before it accepts one. */
    public int rejectRequests;
    /** Menus opened / inventory requests accepted in a menu / of them while the bot moved (must stay 0). */
    public int menusOpened;
    public int clickRequests;
    public int multiActions;
    /** Ticks a menu was open in all, and our input requests completed false in them. */
    public int menuTicks;
    public int droppedInputs;
    private @Nullable Pos menuPending;
    private @Nullable Pos menuAt;
    private int menuId;
    private int slideLeft;
    private int requestLeft;
    /** {@link ContainerRun.Io#answerWait}: ticks the run may wait for the answers (0 = the fixed wait). */
    public int answerWait;
    /** Ticks after the request completed that the server's answers arrive in the cache; never: a large number. */
    public int answerDelay = 3;
    private long requestDoneAt = Long.MIN_VALUE;
    /** Our input requests: the previous tick's and this tick's (as ZenithProxy completed them). */
    private InputGate.@Nullable Sent prevSent;
    private double subX;
    private double subY;
    private double subZ;
    /** The last movement packet pressed keys / moved the bot. */
    private boolean lastPacketKeys;
    private boolean lastPacketMoved;

    public ContainerSim(long seed) {
        this.rnd = new Random(seed);
    }

    // ---------------------------------------------------------------------------------------------- building

    public ContainerSim set(Pos p, BlockSpec b) {
        if (b.isAir()) blocks.remove(p);
        else blocks.put(p, b);
        return this;
    }

    public ContainerSim set(int bx, int by, int bz, BlockSpec b) {
        return set(new Pos(bx, by, bz), b);
    }

    /** Fills the box (inclusive) with {@code b}. */
    public ContainerSim fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockSpec b) {
        for (int bx = Math.min(x0, x1); bx <= Math.max(x0, x1); bx++)
            for (int by = Math.min(y0, y1); by <= Math.max(y0, y1); by++)
                for (int bz = Math.min(z0, z1); bz <= Math.max(z0, z1); bz++) set(bx, by, bz, b);
        return this;
    }

    public static BlockSpec chest(String facing, String type) {
        return BlockSpec.of("minecraft:chest", Map.of("facing", facing, "type", type, "waterlogged", "false"));
    }

    public static BlockSpec wallSign(String facing) {
        return BlockSpec.of("minecraft:oak_wall_sign", Map.of("facing", facing, "waterlogged", "false"));
    }

    /** A stone button on a {@code face} (floor, wall, ceiling) facing {@code facing}, not pressed. */
    public static BlockSpec button(String face, String facing) {
        return BlockSpec.of("minecraft:stone_button", Map.of("face", face, "facing", facing, "powered", "false"));
    }

    public static BlockSpec lever(String face, String facing) {
        return BlockSpec.of("minecraft:lever", Map.of("face", face, "facing", facing, "powered", "false"));
    }

    public static BlockSpec noteBlock(int note) {
        return BlockSpec.of("minecraft:note_block", Map.of("instrument", "harp", "note", String.valueOf(note),
            "powered", "false"));
    }

    /** An observer whose face looks {@code facing} (it watches the block on that side). */
    public static BlockSpec observer(String facing) {
        return BlockSpec.of("minecraft:observer", Map.of("facing", facing, "powered", "false"));
    }

    /** Puts the bot's feet at (fx, fy, fz), standing still on the ground. */
    public void teleport(double fx, double fy, double fz) {
        x = fx;
        y = fy;
        z = fz;
        vx = 0;
        vz = 0;
        lateLeft = 0;
        onGround = true;
    }

    // ---------------------------------------------------------------------------------------------- shapes

    static List<double[]> defaultInteraction(BlockSpec b) {
        if (b == null || b.isAir()) return List.of();
        String id = b.id();
        if (id.equals("minecraft:water") || id.equals("minecraft:lava")) return List.of(); // fluids: no shape
        double p = 1.0 / 16;
        if (id.endsWith("_wall_sign") && !id.endsWith("hanging_sign")) {
            String f = b.props().getOrDefault("facing", "north");
            double lo = 4.5 * p;
            double hi = 12.5 * p;
            return List.of(switch (f) {
                case "south" -> new double[]{0, lo, 0, 1, hi, 2 * p};
                case "west" -> new double[]{14 * p, lo, 0, 1, hi, 1};
                case "east" -> new double[]{0, lo, 0, 2 * p, hi, 1};
                default -> new double[]{0, lo, 14 * p, 1, hi, 1};
            });
        }
        if (id.endsWith("_button")) return List.of(attached(b, 5, 6, 11, 10, 2));
        if (id.equals("minecraft:lever")) return List.of(attached(b, 5, 4, 11, 12, 6));
        if (id.endsWith("_sign") || id.endsWith("torch")) return List.of();
        if (id.endsWith("_wall")) return List.of(new double[]{4 * p, 0, 4 * p, 12 * p, 1, 12 * p}); // the post
        if (id.endsWith("_carpet")) return List.of(new double[]{0, 0, 0, 1, p, 1});
        if (id.endsWith("chest")) {
            double[] box = {p, 0, p, 1 - p, 14 * p, 1 - p};
            int[] d = Aim.chestNeighbour(b.props().get("facing"), b.props().get("type"));
            if (d != null) {
                if (d[0] > 0) box[3] = 1;
                if (d[0] < 0) box[0] = 0;
                if (d[1] > 0) box[5] = 1;
                if (d[1] < 0) box[2] = 0;
            }
            return List.of(box);
        }
        return List.of(new double[]{0, 0, 0, 1, 1, 1});
    }

    static List<double[]> defaultCollision(BlockSpec b) {
        if (b == null || b.isAir()) return List.of();
        String id = b.id();
        if (id.endsWith("_sign") || id.endsWith("_button") || id.endsWith("torch") || id.equals("minecraft:lever")) {
            return List.of();
        }
        if (id.endsWith("_wall")) return List.of(new double[]{0.25, 0, 0.25, 0.75, 1.5, 0.75});
        return defaultInteraction(b);
    }

    /**
     * Box of a button or lever (vanilla FaceAttachedHorizontalDirectionalBlock shapes, in 16ths): on a wall it is
     * {@code w0..w1} wide, {@code h0..h1} high and {@code depth} deep against the block it hangs on; on the floor or
     * ceiling the same footprint lies flat, {@code depth} high.
     */
    private static double[] attached(BlockSpec b, double w0, double h0, double w1, double h1, double depth) {
        String face = b.props().getOrDefault("face", "wall");
        String f = b.props().getOrDefault("facing", "north");
        boolean zAxis = f.equals("north") || f.equals("south");
        double[] v;
        if (face.equals("floor") || face.equals("ceiling")) {
            double y0 = face.equals("floor") ? 0 : 16 - depth;
            double y1 = face.equals("floor") ? depth : 16;
            v = zAxis ? new double[]{w0, y0, h0, w1, y1, h1} : new double[]{h0, y0, w0, h1, y1, w1};
        } else {
            v = switch (f) {
                case "south" -> new double[]{w0, h0, 0, w1, h1, depth};
                case "west" -> new double[]{16 - depth, h0, w0, 16, h1, w1};
                case "east" -> new double[]{0, h0, w0, depth, h1, w1};
                default -> new double[]{w0, h0, 16 - depth, w1, h1, 16};
            };
        }
        for (int i = 0; i < 6; i++) v[i] /= 16;
        return v;
    }

    // ---------------------------------------------------------------------------------------------- raycast

    /** First interaction box the ray from the eye with this rotation hits within {@code reach}, or null. */
    public @Nullable Hit raycast(double ex, double ey, double ez, float ryaw, float rpitch, double reach) {
        double[] d = Aim.viewVector(ryaw, rpitch);
        int cx = (int) Math.floor(ex);
        int cy = (int) Math.floor(ey);
        int cz = (int) Math.floor(ez);
        int[] step = new int[3];
        double[] tMax = new double[3];
        double[] tDelta = new double[3];
        double[] o = {ex, ey, ez};
        int[] c = {cx, cy, cz};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                step[a] = 0;
                tMax[a] = Double.POSITIVE_INFINITY;
                tDelta[a] = Double.POSITIVE_INFINITY;
            } else {
                step[a] = d[a] > 0 ? 1 : -1;
                double next = d[a] > 0 ? c[a] + 1 - o[a] : o[a] - c[a];
                tMax[a] = next / Math.abs(d[a]);
                tDelta[a] = 1 / Math.abs(d[a]);
            }
        }
        double t = 0;
        while (t <= reach) {
            Hit h = hitBlock(new Pos(c[0], c[1], c[2]), ex, ey, ez, d, reach);
            if (h != null) return h;
            int a = tMax[0] < tMax[1] ? (tMax[0] < tMax[2] ? 0 : 2) : (tMax[1] < tMax[2] ? 1 : 2);
            t = tMax[a];
            c[a] += step[a];
            tMax[a] += tDelta[a];
        }
        return null;
    }

    private @Nullable Hit hitBlock(Pos p, double ex, double ey, double ez, double[] d, double reach) {
        BlockSpec b = blocks.get(p);
        if (b == null) return null;
        Hit best = null;
        double bestT = Double.POSITIVE_INFINITY;
        for (double[] box : interactionShape.apply(b)) {
            double[] r = slab(ex, ey, ez, d, p.x() + box[0], p.y() + box[1], p.z() + box[2], p.x() + box[3],
                p.y() + box[4], p.z() + box[5]);
            if (r == null || r[0] > reach || r[0] >= bestT) continue;
            bestT = r[0];
            best = new Hit(p, (int) r[1], ex + d[0] * r[0], ey + d[1] * r[0], ez + d[2] * r[0]);
        }
        return best;
    }

    /** {entry distance, face code} of the ray into the box, or null (also when the eye is inside it). */
    private static double @Nullable [] slab(double ex, double ey, double ez, double[] d, double x0, double y0, double z0,
                                            double x1, double y1, double z1) {
        double[] o = {ex, ey, ez};
        double[] lo = {x0, y0, z0};
        double[] hi = {x1, y1, z1};
        double tmin = Double.NEGATIVE_INFINITY;
        double tmax = Double.POSITIVE_INFINITY;
        int axis = -1;
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] > hi[a]) return null;
                continue;
            }
            double t1 = (lo[a] - o[a]) / d[a];
            double t2 = (hi[a] - o[a]) / d[a];
            if (t1 > t2) {
                double t = t1;
                t1 = t2;
                t2 = t;
            }
            if (t1 > tmin) {
                tmin = t1;
                axis = a;
            }
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return null;
        }
        if (axis < 0 || tmin < 0) return null;
        int face = switch (axis) {
            case 0 -> d[0] > 0 ? Aim.WEST : Aim.EAST;
            case 1 -> d[1] > 0 ? Aim.DOWN : Aim.UP;
            default -> d[2] > 0 ? Aim.NORTH : Aim.SOUTH;
        };
        return new double[]{tmin, face};
    }

    /** Face centres of the targets turned towards the eye, nearest first: {x, y, z, face, target index}. */
    private List<double[]> faceCentres(double ex, double ey, double ez) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Pos t = targets.get(i);
            Integer want = onlyFace == null ? null : onlyFace.get(t);
            for (double[] box : interactionShape.apply(block(t))) {
                for (double[] cnt : Aim.faceCentres(ex, ey, ez, t.x() + box[0], t.y() + box[1], t.z() + box[2],
                    t.x() + box[3], t.y() + box[4], t.z() + box[5])) {
                    if (want == null || (int) cnt[3] == want) out.add(new double[]{cnt[0], cnt[1], cnt[2], cnt[3], i});
                }
            }
        }
        out.sort((a, b) -> Double.compare(Aim.distSq(ex, ey, ez, a[0], a[1], a[2]), Aim.distSq(ex, ey, ez, b[0], b[1], b[2])));
        return out;
    }

    /** Shot at a target face centre in reach and sight from the eye, nearest first (as {@code Aiming.faceCentre}). */
    public @Nullable Shot shoot(double ex, double ey, double ez, double reach) {
        for (double[] p : faceCentres(ex, ey, ez)) {
            if (Aim.distSq(ex, ey, ez, p[0], p[1], p[2]) > reach * reach) break;
            Pos t = targets.get((int) p[4]);
            float[] rot = Aim.rotationTo(ex, ey, ez, p[0], p[1], p[2]);
            Hit h = raycast(ex, ey, ez, rot[0], rot[1], reach);
            if (h == null || !h.block().equals(t) || h.face() != (int) p[3]) continue;
            return new Shot(rot[0], rot[1], t, h.face(), h.x(), h.y(), h.z());
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------- tick protocol

    public void beginTick(long tick) {
        now = tick;
        for (var it = buttonReleaseAt.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue() > now) continue;
            it.remove();
            BlockSpec b = block(e.getKey());
            TreeMap<String, String> props = new TreeMap<>(b.props());
            props.put("powered", "false");
            set(e.getKey(), BlockSpec.of(b.id(), props));
        }
        // the server's answer to last tick's click arrived: its menu is open from now on (unless a test said that the
        // server did not open it, by clearing opened)
        if (menuPending != null && opened != null) {
            menuAt = menuPending;
            menuId++;
            menusOpened++;
            slideLeft = slideAfterOpen;
        }
        menuPending = null;
        prevSubmitted = submitted;
        submitted = false;
        sneakReq = false;
        reqYaw = null;
        reqPitch = null;
        fwd = 0;
        strafe = 0;
    }

    /**
     * ZenithProxy after the task's tick: the InventoryManager runs the menu's request, then the Bot sends the movement
     * packet with this tick's rotation and keys, or, while a menu is open, resets the movement input, keeps the rotation
     * and completes our input request false.
     */
    public void endTick() {
        if (otherInventoryTicks > 0) otherInventoryTicks--;
        if (requestLeft > 0 && --requestLeft == 0) {
            requestDoneAt = now;
            if (ncpStrict) menuAt = null; // ZenithProxy closes after the request
        }
        boolean moving = fwd != 0 || strafe != 0;
        if (menuAt != null) {
            menuTicks++;
            if (submitted) {
                prevSent = new InputGate.Sent(now, moving, InputGate.Outcome.DROPPED, subX, subY, subZ);
                droppedInputs++;
            }
            sneaking = false;
            lastPacketKeys = false;
            lastPacketMoved = slideLeft > 0;
            if (slideLeft > 0) {
                slideLeft--;
                x += 0.05;
            }
            return;
        }
        if (submitted) prevSent = new InputGate.Sent(now, moving, InputGate.Outcome.EXECUTED, subX, subY, subZ);
        sneaking = submitted && sneakReq;
        if (reqYaw != null || reqPitch != null) rotationTick = now;
        if (reqYaw != null) yaw = reqYaw;
        if (reqPitch != null) pitch = reqPitch;
        lastPacketMoved = false;
        if (walkDest != null && !submitted) { // Baritone owns the movement
            lastPacketKeys = true;
            return;
        }
        lastPacketKeys = moving;
        if (momentum && lateLeft > 0) {
            lateLeft--; // Baritone's last input: as fast as before, whatever the keys
            x += vx;
            z += vz;
            lastPacketMoved = true;
        } else if (momentum) {
            vx *= FRICTION;
            vz *= FRICTION;
            if (moving) {
                double[] w = Aim.keysToWorld(yaw, fwd, strafe);
                double l = Math.hypot(w[0], w[1]);
                vx += w[0] / l * 0.1;
                vz += w[1] / l * 0.1;
            }
            if (Math.abs(vx) < 0.003) vx = 0;
            if (Math.abs(vz) < 0.003) vz = 0;
            x += vx;
            z += vz;
            lastPacketMoved = vx != 0 || vz != 0;
        } else if (moving) {
            double[] w = Aim.keysToWorld(yaw, fwd, strafe);
            double l = Math.hypot(w[0], w[1]);
            x += w[0] / l * 0.1;
            z += w[1] / l * 0.1;
            lastPacketMoved = true;
        }
    }

    /** Runs the approach until it clicks or fails (at most {@code maxTicks}). */
    public TaskStatus run(Approach<Shot> a, int maxTicks) {
        for (long t = 1; t <= maxTicks; t++) {
            beginTick(t);
            TaskStatus s = a.tick(t);
            endTick();
            if (s != TaskStatus.RUNNING) return s;
        }
        log.add("TIMEOUT after " + maxTicks + " ticks" + a.progress());
        return TaskStatus.FAILED;
    }

    // ---------------------------------------------------------------------------------------------- Approach.Io

    @Override public double x() { return x; }
    @Override public double y() { return y; }
    @Override public double z() { return z; }
    @Override public boolean onGround() { return onGround; }
    @Override public float yaw() { return yaw; }
    @Override public boolean isLoaded(Pos pos) { return true; }

    @Override
    public BlockSpec block(Pos pos) {
        return blocks.getOrDefault(pos, BlockSpec.AIR);
    }

    @Override
    public boolean hasFluid(Pos pos) {
        String id = block(pos).id();
        return id.equals("minecraft:water") || id.equals("minecraft:lava");
    }

    @Override
    public boolean allowed(Pos feet) {
        return allowedCells.test(feet) && (keepClear == null || !StandSpots.covers(feet, keepClear));
    }

    @Override
    public boolean walksOn(BlockSpec floor) {
        return walksOn.test(floor);
    }

    @Override
    public String excludedBecause(Pos feet) {
        if (allowedCells.test(feet)) return StandSpots.IN_THE_WAY;
        return allowedCells instanceof StandSpots.CellRule r ? r.refused() : "not allowed";
    }

    @Override
    public double eyeHeight() {
        return sneakToClick ? Aim.SNEAK_EYE_HEIGHT : Aim.EYE_HEIGHT;
    }

    /** The eye now: lower while crouched. */
    private double eyeY() {
        return y + (sneaking ? Aim.SNEAK_EYE_HEIGHT : Aim.EYE_HEIGHT);
    }

    @Override
    public boolean readyToClick() {
        if (sneakToClick && !sneaking) return false;
        return keepClear == null || !Aim.cellOverlaps(keepClear.x(), keepClear.y(), keepClear.z(), Aim.playerBox(x, y, z, 0.05));
    }

    // ---------------------------------------------------------------------------------------------- PlaceRun.Io

    @Override
    public boolean solidSupport(Pos p) {
        List<double[]> boxes = collisionShape.apply(block(p));
        if (boxes.size() != 1) return false;
        double[] b = boxes.getFirst();
        return b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 1 && b[4] == 1 && b[5] == 1;
    }

    @Override
    public boolean interactive(Pos p) {
        return PlaceRun.interactive(block(p));
    }

    @Override
    public boolean occupied(Pos p) {
        return !block(p).isAir();
    }

    @Override
    public int count(String item) {
        return placeItem != null && placeItem.id().equals(item) ? carried : 0;
    }

    @Override
    public boolean hold(String item) {
        if (holdDelay > 0) {
            holdDelay--;
            return false;
        }
        return count(item) > 0;
    }

    @Override
    public void aimAt(Map<Pos, Integer> faces, boolean sneak, Pos clear) {
        targets = List.copyOf(faces.keySet());
        onlyFace = Map.copyOf(faces);
        sneakToClick = sneak;
        keepClear = clear;
    }

    @Override
    public double collisionTop(BlockSpec spec) {
        double top = 0;
        for (double[] b : collisionShape.apply(spec)) top = Math.max(top, b[4]);
        return top;
    }

    @Override
    public boolean visibleFrom(double ex, double ey, double ez, double reach) {
        return shoot(ex, ey, ez, reach) != null;
    }

    @Override
    public String hiddenFrom(double ex, double ey, double ez, double reach) {
        List<double[]> pts = faceCentres(ex, ey, ez);
        if (pts.isEmpty()) return "no face turned towards the eye";
        double[] p = pts.getFirst();
        String face = Aim.faceName((int) p[3]) + " face center";
        double dist = Math.sqrt(Aim.distSq(ex, ey, ez, p[0], p[1], p[2]));
        if (dist > reach) return String.format(Locale.ROOT, "the nearest %s is %.2f away, reach %.2f", face, dist, reach);
        float[] rot = Aim.rotationTo(ex, ey, ez, p[0], p[1], p[2]);
        Hit h = raycast(ex, ey, ez, rot[0], rot[1], reach);
        if (h == null) return "the ray to the " + face + " hits nothing";
        if (!h.block().equals(targets.get((int) p[4]))) return block(h.block()).id() + " at " + h.block() + " hides the " + face;
        return "the " + face + " is covered by the block's own shape";
    }

    @Override
    public @Nullable Shot aim(double reach) {
        if (hideAfterSearch != null && StandSpots.feetCell(x, y, z).equals(hideAfterSearch)) return null;
        return shoot(x, eyeY(), z, reach);
    }

    @Override
    public @Nullable Shot recheck(Shot aimed, double reach) {
        double ey = eyeY();
        Hit h = raycast(x, ey, z, yaw, pitch, reach);
        if (h == null || !h.block().equals(aimed.block()) || h.face() != aimed.face()) return null;
        return new Shot(yaw, pitch, h.block(), h.face(), h.x(), h.y(), h.z());
    }

    @Override
    public TaskStatus walk(GoalNearXZ goal, BooleanSupplier arrived) {
        if (arrived.getAsBoolean()) {
            stopWalking();
            return TaskStatus.SUCCESS;
        }
        if (!goal.equals(walkGoal)) {
            walkGoal = goal;
            walkDest = null;
        }
        if (walkDest == null) {
            if (unpathable.contains(new Pos(goal.x(), goal.y(), goal.z()))) {
                failedPaths += 4; // ZenithTask's Walker: 3 retries
                walkError = "pathing to " + goal + " failed 4 times";
                stopWalking();
                return TaskStatus.FAILED;
            }
            double[] d = route(goal);
            if (d == null) {
                walkError = "no path to " + goal;
                stopWalking();
                return TaskStatus.FAILED;
            }
            walkDest = d;
            walkTicksLeft = (int) Math.ceil(d[3] / WALK) + 2 * (int) d[4];
            baritoneWalks++;
            walked += d[3];
            walkLengths.add(d[3]);
        }
        if (--walkTicksLeft > 0) return TaskStatus.RUNNING;
        double[] d = walkDest;
        stopWalking();
        if (momentum) {
            // at the near edge of the goal cell, still at walking speed, heading for its centre
            double cx = Math.floor(d[0]) + 0.5;
            double cz = Math.floor(d[2]) + 0.5;
            double dx = cx - x;
            double dz = cz - z;
            double l = Math.hypot(dx, dz);
            double ux = l < 1e-9 ? 1 : dx / l;
            double uz = l < 1e-9 ? 0 : dz / l;
            teleport(cx - ux * 0.45, d[1], cz - uz * 0.45);
            vx = ux * arriveSpeed;
            vz = uz * arriveSpeed;
            lateLeft = arriveLateTicks;
        } else {
            teleport(d[0], d[1], d[2]);
        }
        if (arrived.getAsBoolean()) return TaskStatus.SUCCESS;
        walkError = "reached " + goal + " but the arrival test still fails";
        return TaskStatus.FAILED;
    }

    /** The walk with a retry count: an {@link #unpathable} goal fails after {@code retries} + 1 path searches. */
    @Override
    public TaskStatus walk(GoalNearXZ goal, BooleanSupplier arrived, int retries) {
        if (!arrived.getAsBoolean() && (walkDest == null || !goal.equals(walkGoal))
            && unpathable.contains(new Pos(goal.x(), goal.y(), goal.z()))) {
            int attempts = Math.max(0, retries) + 1;
            failedPaths += attempts;
            walkError = "pathing to " + goal + " failed " + (attempts == 1 ? "once" : attempts + " times");
            stopWalking();
            return TaskStatus.FAILED;
        }
        return walk(goal, arrived);
    }

    @Override
    public String walkError() {
        return walkError;
    }

    @Override
    public void stopWalking() {
        walkGoal = null;
        walkDest = null;
    }

    /**
     * Baritone stand-in: cheapest standable feet cell inside the goal (4-neighbour steps, up 1 or down up to 3; a step
     * up costs one more), within {@link #routeRadius} blocks; straight there from a cell that is not standable. Returns
     * {x, feetY, z, distance, climbs}: feet up to 0.2 off the cell centre.
     */
    private double @Nullable [] route(GoalNearXZ g) {
        Pos start = StandSpots.feetCell(x, y, z);
        double ox = (rnd.nextDouble() * 2 - 1) * 0.2;
        double oz = (rnd.nextDouble() * 2 - 1) * 0.2;
        if (Double.isNaN(StandSpots.feetY(this, start.x(), start.y(), start.z()))) {
            Pos c = new Pos(g.x(), g.y(), g.z());
            double fy = StandSpots.feetY(this, c.x(), c.y(), c.z());
            if (Double.isNaN(fy)) return null;
            return new double[]{c.x() + 0.5 + ox, fy, c.z() + 0.5 + oz, Math.hypot(c.x() - x, c.z() - z), 0};
        }
        Map<Pos, Double> best = new HashMap<>();
        Map<Pos, Integer> climbs = new HashMap<>();
        PriorityQueue<Object[]> q = new PriorityQueue<>((a, b) -> Double.compare((double) a[0], (double) b[0]));
        q.add(new Object[]{0.0, start});
        best.put(start, 0.0);
        climbs.put(start, 0);
        while (!q.isEmpty()) {
            Object[] e = q.poll();
            double cost = (double) e[0];
            Pos c = (Pos) e[1];
            if (cost > best.getOrDefault(c, Double.POSITIVE_INFINITY)) continue;
            if (g.isInGoal(c.x(), c.y(), c.z())) {
                double fy = StandSpots.feetY(this, c.x(), c.y(), c.z());
                return new double[]{c.x() + 0.5 + ox, fy, c.z() + 0.5 + oz, cost, climbs.get(c)};
            }
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int dy = 1; dy >= -3; dy--) {
                    Pos n = new Pos(c.x() + d[0], c.y() + dy, c.z() + d[1]);
                    if (Math.abs(n.x() - start.x()) > routeRadius || Math.abs(n.z() - start.z()) > routeRadius) continue;
                    if (Double.isNaN(StandSpots.feetY(this, n.x(), n.y(), n.z()))) continue;
                    double nc = cost + 1 + (dy > 0 ? 1 : 0);
                    if (nc >= best.getOrDefault(n, Double.POSITIVE_INFINITY)) break;
                    best.put(n, nc);
                    climbs.put(n, climbs.get(c) + (dy > 0 ? 1 : 0));
                    q.add(new Object[]{nc, n});
                    break; // the highest standable cell of the column is the one walked onto
                }
            }
        }
        return null;
    }

    @Override
    public void submit(@Nullable Float ryaw, @Nullable Float rpitch, int forward, int str) {
        submitted = true;
        subX = x;
        subY = y;
        subZ = z;
        sneakReq = sneakToClick;
        reqYaw = ryaw;
        reqPitch = rpitch;
        fwd = forward;
        strafe = str;
    }

    @Override
    public void holdStill() {
        submit(null, null, 0, 0); // sneaks while sneakToClick, like the ZenithProxy Io
    }

    /** This tick's request submitted, the previous tick's executed (not dropped by an open menu), no menu open. */
    @Override
    public boolean canClick() {
        return submitted && prevSubmitted && prevSent != null && prevSent.tick() == now - 1
            && prevSent.outcome() == InputGate.Outcome.EXECUTED && menuAt == null;
    }

    @Override
    public boolean click(Shot shot) {
        clicks++;
        if (reqYaw != null || reqPitch != null) rotateAndClick++;
        if (fwd != 0 || strafe != 0) clickWhileMoving++;
        if (shot.yaw() != yaw || shot.pitch() != pitch) clickOffRotation++;
        lastClick = shot;
        clickEye = new double[]{x, eyeY(), z};
        clickTick = now;
        aimedTick = rotationTick;
        clickSneaking = sneaking;
        Hit h = raycast(x, eyeY(), z, yaw, pitch, 6);
        if (h != null && placeItem != null && keepClear != null && targets.contains(h.block())) {
            place(h);
            return true;
        }
        if (h != null && targets.contains(h.block())) {
            opened = h.block();
            if (dropClicks > 0) {
                dropClicks--;
            } else {
                if (opensMenu.test(block(h.block()))) menuPending = h.block();
                use(h.block());
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------- ContainerRun.Io

    /** Chests, barrels, shulker boxes, hoppers, dispensers, the cartography table and anvils open a menu. */
    public static boolean menuBlock(BlockSpec b) {
        String id = b.id();
        return id.endsWith("chest") || id.equals("minecraft:barrel") || id.endsWith("shulker_box")
            || id.equals("minecraft:hopper") || id.equals("minecraft:dispenser") || id.equals("minecraft:dropper")
            || id.equals("minecraft:cartography_table") || id.endsWith("anvil");
    }

    /** The block whose menu is open, or null. */
    public @Nullable Pos menuAt() {
        return menuAt;
    }

    @Override
    public int openMenuId() {
        return menuAt != null ? menuId : 0;
    }

    @Override
    public InputGate.@Nullable Sent previousInput(long tick) {
        return prevSent;
    }

    @Override
    public boolean inventoryBusy() {
        return otherInventoryTicks > 0 || requestLeft > 0;
    }

    @Override
    public int submitClicks(int id) {
        if (menuAt == null || id != menuId || menuClicks <= 0) return 0;
        if (rejectRequests > 0) {
            rejectRequests--;
            return -1;
        }
        if (lastPacketKeys || lastPacketMoved) multiActions++;
        clickRequests++;
        requestDoneAt = Long.MIN_VALUE;
        // + the wait and the snapshot action; with an answer wait the run waits itself and the request has neither
        requestLeft = (menuClicks + (answerWait > 0 ? 0 : 2)) * Math.max(1, clickDelay);
        return requestLeft;
    }

    @Override
    public @Nullable Boolean clicksDone() {
        return requestLeft > 0 ? null : Boolean.TRUE;
    }

    @Override
    public void closeMenu() {
        menuAt = null;
        requestLeft = 0;
    }

    @Override
    public int answerWait() {
        return answerWait;
    }

    @Override
    public boolean answersIn() {
        return requestDoneAt != Long.MIN_VALUE && now - requestDoneAt >= answerDelay;
    }

    /** The server's handling of a click on a support face while holding {@link #placeItem}. */
    private void place(Hit h) {
        int[][] normals = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        int[] n = normals[h.face()];
        Pos cell = h.block().offset(n[0], n[1], n[2]);
        if (interactive(h.block()) && !sneaking) {
            openedInstead++;
            return;
        }
        if (dropClicks > 0) {
            dropClicks--;
            return;
        }
        if (!cell.equals(keepClear) || !block(cell).isAir() || carried <= 0) return;
        if (Aim.cellOverlaps(cell.x(), cell.y(), cell.z(), Aim.playerBox(x, y, z, 0))) {
            boxRejects++;
            return;
        }
        set(cell, placeItem);
        carried--;
        placed++;
        placedWith = lastClick;
        placedSneaking = clickSneaking;
        placedTick = clickTick;
        placedAimedTick = aimedTick;
    }

    /** The click of the last placement, whether it sneaked, its tick and the tick that sent its rotation. */
    public @Nullable Shot placedWith;
    public boolean placedSneaking;
    public long placedTick = -1;
    public long placedAimedTick = -1;

    /** The server's handling of a right-click on {@code p} (vanilla 1.21 useWithoutItem). */
    private void use(Pos p) {
        BlockSpec b = block(p);
        String id = b.id();
        TreeMap<String, String> props = new TreeMap<>(b.props());
        if (id.endsWith("_button")) {
            if ("true".equals(props.get("powered"))) return; // still pressed: nothing happens
            props.put("powered", "true");
            buttonReleaseAt.put(p, now + 20);
        } else if (id.equals("minecraft:lever")) {
            props.put("powered", String.valueOf(!"true".equals(props.get("powered"))));
        } else if (id.equals("minecraft:note_block")) {
            props.put("note", String.valueOf((Integer.parseInt(props.getOrDefault("note", "0")) + 1) % 25));
            fireObservers(p);
        } else {
            return;
        }
        BlockSpec after = BlockSpec.of(id, props);
        set(p, after);
        uses.add(id + " at " + p + " -> " + after);
    }

    /** Every observer whose face looks at {@code p} sees its state change and pulses. */
    private void fireObservers(Pos p) {
        int[][] dirs = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        String[] names = {"north", "south", "west", "east", "up", "down"};
        for (int[] d : dirs) {
            Pos o = p.offset(d[0], d[1], d[2]);
            BlockSpec ob = block(o);
            if (!ob.id().equals("minecraft:observer")) continue;
            String f = ob.props().get("facing");
            for (int i = 0; i < 6; i++) {
                if (names[i].equals(f) && o.offset(dirs[i][0], dirs[i][1], dirs[i][2]).equals(p)) observerFires++;
            }
        }
    }

    @Override
    public void log(String line) {
        log.add(line);
    }
}
