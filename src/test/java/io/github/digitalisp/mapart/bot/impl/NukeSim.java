package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.impl.DigServer.BlockType;
import io.github.digitalisp.mapart.bot.impl.DigServer.Item;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The client side of a {@link NukeRun} against a {@link DigServer} ({@link NukeRun.Io}): what ZenithProxy's caches show
 * and what the bot sends. A flat art layer at the server's feet level on a floor one below, the eye 1.62 above the
 * feet.
 *
 * <ul>
 *   <li><b>World</b>: the client's copy of the server's blocks, changed only by block updates after the downlink
 *       latency (nothing predicted).</li>
 *   <li><b>Walking</b>: {@link #input} moves the feet {@link #WALK} blocks per tick towards (dx, dz) in the same tick
 *       (ZenithProxy's Bot moves after the task's tick), blocked per axis by any block of the client's view in a cell
 *       the box would overlap (no stepping, the floor is flat); the movement packet at the end of the tick carries the
 *       position. Baritone walks ({@link #approach}, {@link #walkTo}) go straight, ignoring blocks. With
 *       {@link #topSpeed} set the walk accelerates as the client's physics do (velocity × 0.546 + keys × top speed ×
 *       0.454, a wall zeroes that axis): Soul Speed on soul sand or plain walking. The task's reads of the feet
 *       ({@link #feetX}, {@link #aim}) give the position at the tick's start (the one the server knows), since
 *       ZenithProxy's Bot moves after the task's tick.</li>
 *   <li><b>Keys</b>: with {@link #keysFollowLook} the movement keys are the 8 directions relative to the look's yaw
 *       ({@link NukeGuard#keyMotion}), up to 22.5° off the asked walk, as the real client.</li>
 *   <li><b>Aim</b>: the top face of a block within reach (eye to the nearest point of the face, 0.06 inset), when the
 *       block above is air and the eye above the face.</li>
 *   <li><b>Hotbar</b>: tools in their own slots, the select goes out at once (before the tick's STARTs), as
 *       ZenithProxy's {@code Clicker.selectNow}. Tool wear shows after the downlink latency.</li>
 *   <li><b>Haste</b>: the server's {@link DigServer#speedFactor} follows {@link #serverHasteUntil}; inside
 *       {@link #hasteZone} a beacon pulse every 80 ticks renews it for 340 ticks, the client learns of it a downlink
 *       later. The client's speeds use Haste only while {@link #hasteTicksLeft} ≥ {@link #hasteMargin} (the plugin's
 *       {@code build.nukeHasteMarginTicks}).</li>
 * </ul>
 * Per tick: {@link #beginTick} (the server's packets that arrived), {@code NukeRun.tick}, {@link #endTick} (the
 * movement packet, the per-tick rule counters, the beacon), then {@link DigServer#tick}.
 */
final class NukeSim implements NukeRun.Io<NukeSim.Shot> {
    static final double WALK = 0.2158;
    static final double EYE = 1.62;
    static final int UP = 1;
    static final float HASTE_II = 1.4f;

    /** A rotation aimed at {@code block}'s {@code face}. */
    record Shot(Pos block, int face, double ex, double ey, double ez, double px, double py, double pz) {
        Shot(Pos block, int face) {
            this(block, face, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    /**
     * The bot slides on after the movement keys are released (vanilla ground friction, ×0.546 per tick) instead of
     * stopping at once. Off by default.
     */
    boolean slide;
    /**
     * {@link #inEffect} checks the look geometrically: the ray of the rotation sent (towards the point aimed at, from the
     * eye of that tick) must hit the block's top face from the eye now within reach. Off by default (any look at the
     * block counts).
     */
    boolean geometricLook;
    /**
     * With {@link #slide}: the keys accelerate the bot as vanilla does (velocity × 0.546 plus a tick's push, which
     * levels off at this many blocks a tick: 0.49 with Soul Speed on soul sand, 0.2158 walking) instead of moving
     * {@link #WALK} at once; Baritone's walks go this fast too. 0 (default): {@link #WALK} at once.
     */
    double topSpeed;
    private double vx;
    private double vz;
    /** Feet at the start of this tick: what the server last heard (the movement packet goes out at its end). */
    private double tickX;
    private double tickZ;

    final DigServer server;
    private final int floor;
    private final Set<String> protectedIds;
    private final Random rnd;
    final double feetY;
    final double reach;
    /** Cells with a floor under them (the bot may stand there); null = everywhere. */
    @Nullable Predicate<Pos> floorAt;
    /**
     * Cells that stop the box although {@link #walkable} passes them (a pointed dripstone with its random offset, local
     * 10x run 2026-10-11); null = none. Baritone walks ignore them, as they ignore every block here.
     */
    @Nullable Predicate<Pos> hiddenObstacle;
    /** Stopped along z by a hidden obstacle, the feet slide this far along x, back and forth (corrections). */
    double bounce;
    private int bounceSign = 1;
    /** Block ids the box walks through and stands in (carpets, plates, candles: within a step); none by default. */
    Set<String> lowIds = Set.of();
    /** Block ids the run may break to get out of a pocket ({@link #escapable}); none by default. */
    Set<String> clearableIds = Set.of();
    /**
     * Block ids the pathfinder neither walks through nor onto (pointed dripstone, pots: ZenithProxy's MovementHelper);
     * with {@link #pathing}.
     */
    Set<String> wallIds = Set.of();
    /**
     * Baritone plans on the client's view ({@link #plan}): steps between the cells of the art level and the tops of its
     * blocks, never through or onto a {@link #wallIds} block, diagonally only between two tops or across open floor; no
     * way: the walk FAILS. Off (default): straight towards the goal, blocks ignored.
     */
    boolean pathing;
    /** The walker's path check ({@link NukeRun#pathCheck}) for {@link #pathing} walks; null: none. */
    java.util.function.@Nullable Function<List<Pos>, @Nullable String> pathCheck;
    /**
     * The bot stands on top of the art level's blocks (feet one higher) after a walk over them, and drops to the floor
     * once none is under its box (a block broken under it, or walked off). Off by default.
     */
    boolean elevation;
    /** No face of a block under the bot's box can be aimed at (a stand where the hole's rim hides it). Off by default. */
    boolean hideUnderBox;
    /**
     * A {@link #lowIds} block is aimed at on its own top face, 1/16 above its cell, by a ray that must pass the full
     * blocks around it ({@link #pitAim}): a carpet among kept full blocks is seen only from close by or from above.
     * Off by default (every block's top face at its cell's top, nothing in the way).
     */
    boolean pitSight;
    /**
     * Approaches as ZenithTask.approachGoals on Baritone: with the feet block already within 1 of the target (the
     * tightest goal) no step is taken and the approach fails ("the arrival test still fails"). Off by default.
     */
    boolean inGoalNoStep;
    /**
     * Ticks a new Baritone walk ({@link #approach} to another target, {@link #walkTo} another goal, any walk after
     * {@link #stopWalking}) stands before its first step, as live: Baritone asked, pathing 2 ticks later, the first
     * step a tick after that (j0024's walks to a target). 0 (default): it steps at once.
     */
    int baritoneStartTicks;
    /** Baritone walks begun so far ({@link #baritoneStartTicks}). */
    int baritoneWalks;
    private @Nullable Object startingFor;
    private int startWaited;
    boolean elevated;
    private @Nullable List<Pos> route;
    private int routeAt;
    private @Nullable Object routeFor;
    private String walkError = "";
    /** Called after each tick of {@link #runFrom} (tests watching the bot). */
    @Nullable Runnable afterTick;
    /** While true the bot does not move at all, by its own keys or Baritone's (wedged); null: never. */
    java.util.function.@Nullable BooleanSupplier wedged;
    /**
     * Cells (feet level) the box may move into but must not ({@link #hazard}: "no-go zone"; never walkable): a zone
     * over open air or a gap. Null: none. {@link #hazardTicks} counts the ticks the box ended in one.
     */
    @Nullable Predicate<Pos> hazardAt;
    /** {@code build.nukeEdgeGuard} off: {@link #hazard} answers null (the cells stay unwalkable, Baritone avoids them). */
    boolean guardOff;
    int hazardTicks;
    @Nullable Pos firstHazard;
    /**
     * The movement keys are chosen for this yaw ({@link NukeGuard#keyMotion}: the nearest of 8 directions, up to 22.5°
     * off the wanted one), as ZenithProxy's input; NaN (default): the walk goes exactly towards (dx, dz).
     */
    double keyYaw = Double.NaN;
    /**
     * The movement keys are chosen for the yaw of the look sent with them (the last one sent without a look), as
     * ZenithProxy's input does ({@link NukeGuard#keyMotion}); off (default): {@link #keyYaw}.
     */
    boolean keysFollowLook;

    /** A block of the client's view at feet level within {@code grow} of the box (at the feet now). */
    boolean blockNearBox(double grow, Predicate<BlockType> which) {
        double h = 0.3 + grow;
        int y = (int) Math.floor(feetY);
        for (int cx = (int) Math.floor(x - h); cx <= (int) Math.floor(x + h); cx++) {
            for (int cz = (int) Math.floor(z - h); cz <= (int) Math.floor(z + h); cz++) {
                BlockType b = view.get(new Pos(cx, y, cz));
                if (b != null && which.test(b)) return true;
            }
        }
        return false;
    }

    // client view
    private final Map<Pos, BlockType> view = new HashMap<>();
    private final Item[] hotbar = new Item[9];
    private int selected;
    double x;
    double z;
    private int latestAck = -1;
    private int seq;
    private long now;

    // haste
    /** Server tick until which the player has Haste II (exclusive); Long.MIN_VALUE: none. */
    long serverHasteUntil = Long.MIN_VALUE;
    /** What the client last heard: the effect's end tick. */
    private long clientHasteUntil = Long.MIN_VALUE;
    private final List<long[]> hasteUpdates = new ArrayList<>();
    /** Feet cells inside a Haste beacon's range, or null (no beacon). */
    @Nullable Predicate<Pos> hasteZone;
    int hasteMargin = 40;
    /** Uniform Haste II all the time (as /effect infinite). */
    boolean hasteForever;

    // input
    private @Nullable Shot requested;
    private boolean inputThisTick;
    private @Nullable Shot sentRot;
    private boolean prevExecuted = true;
    /** Share of ticks whose input request is not executed. */
    double lostTicks;

    // this tick's packets
    private int tickStarts;
    private int tickSwings;
    private final List<DigServer.UpPacket> tickDigs = new ArrayList<>();
    private long lastStopTick = Long.MIN_VALUE / 2;

    // statistics
    long ticks;
    long walkTicks;
    double walked;
    int maxStartsInTick;
    int startsWithin6OfStop;
    int noSwingTicks;
    int multiBreakTicks;
    int selects;
    int airStarts;
    /** Lambda's fake STARTs (out of the world), apart from the STARTs counted above. */
    int fakeStarts;
    /** Fake STARTs at a position that is not {@link DigClient#outOfWorld} (must stay 0). */
    int fakeInWorld;
    /** STARTs of blocks instant with the item held sent less than 6 ticks after a STOP (Lambda's rule: none). */
    int instantWithin6OfStop;
    /** Ticks with more than one swing. */
    int multiSwingTicks;
    /** STARTs sent while not on the ground (the sim is always on the ground unless {@link #airborne}). */
    int airborneStarts;
    /** The bot counts as in the air (no ground): tests of the "no START in the air" rule. */
    java.util.function.@Nullable BooleanSupplier airborne;
    /** Every packet sent, as "tick ACTION x,y,z" or "tick swing" or "tick select n", when {@link #recordPackets}. */
    final List<String> packets = new ArrayList<>();
    boolean recordPackets;
    final Map<Pos, Integer> brokenCalls = new HashMap<>();
    final List<String> log = new ArrayList<>();
    boolean echoLog;

    NukeSim(DigServer server, double startX, double startZ, int floor, Set<String> protectedIds, double reach,
            long seed) {
        this.server = server;
        this.floor = floor;
        this.protectedIds = protectedIds;
        this.reach = reach;
        this.rnd = new Random(seed);
        this.feetY = server.feetY;
        this.x = startX;
        this.z = startZ;
        this.tickX = startX;
        this.tickZ = startZ;
        server.px = startX;
        server.pz = startZ;
        view.putAll(server.world);
        for (int i = 0; i < 9; i++) hotbar[i] = server.hotbar[i] == null ? null : server.hotbar[i].copy();
        selected = server.selected;
    }

    void broken(Pos p) {
        brokenCalls.merge(p, 1, Integer::sum);
    }

    /** The client's copy of hotbar slot {@code i} (wear shows a downlink after the server's). */
    @Nullable Item clientSlot(int i) {
        return hotbar[i];
    }

    // ------------------------------------------------------------------------------------------------------ ticks

    void beginTick(long t) {
        now = t;
        tickX = x;
        tickZ = z;
        for (DigServer.DownPacket d : server.receive(t)) {
            switch (d.type()) {
                case BLOCK -> {
                    if (d.block() == null) view.remove(d.pos());
                    else view.put(d.pos(), d.block());
                }
                case SLOT -> hotbar[d.slot()] = d.item();
                case ACK -> latestAck = Math.max(latestAck, d.seq());
            }
        }
        while (!hasteUpdates.isEmpty() && hasteUpdates.getFirst()[0] <= t) clientHasteUntil = hasteUpdates.removeFirst()[1];
        if (elevated && !tallUnder(x, z)) elevated = false; // the block under it broke: it drops
    }

    void endTick(long t) {
        maxStartsInTick = Math.max(maxStartsInTick, tickStarts);
        if (tickSwings > 1) multiSwingTicks++;
        boolean startOrStop = false;
        for (DigServer.UpPacket p : tickDigs) if (p.action() != DigClient.Action.ABORT) startOrStop = true;
        if (startOrStop && tickSwings == 0) noSwingTicks++;
        DigServer.UpPacket first = null;
        for (DigServer.UpPacket p : tickDigs) {
            if (p.action() == DigClient.Action.ABORT) continue;
            if (first == null) first = p;
            else if (!first.pos().equals(p.pos()) || first.face() != p.face()) {
                multiBreakTicks++;
                break;
            }
        }
        for (DigServer.UpPacket p : tickDigs) {
            if (p.action() == DigClient.Action.STOP) lastStopTick = t;
        }
        boolean executed = !(lostTicks > 0 && rnd.nextDouble() < lostTicks);
        if (executed && inputThisTick && requested != null) sentRot = requested;
        server.send(DigServer.Up.MOVE, t, null, null, 0, 0, 0, x, z);
        Pos inHazard = boxHazard();
        if (inHazard != null) {
            hazardTicks++;
            if (firstHazard == null) firstHazard = inHazard;
        }
        prevExecuted = executed;
        requested = null;
        inputThisTick = false;
        tickStarts = 0;
        tickSwings = 0;
        tickDigs.clear();
        // the server's Haste: a beacon pulse every 80 ticks renews it in range; the client hears a downlink later
        if (hasteForever) {
            server.speedFactor = HASTE_II;
            clientHasteUntil = Long.MAX_VALUE;
        } else {
            Predicate<Pos> zone = hasteZone;
            if (zone != null && t % 80 == 0 && zone.test(feet())) {
                serverHasteUntil = t + 340;
                hasteUpdates.add(new long[]{t + server.downlink, serverHasteUntil});
            }
            server.speedFactor = t < serverHasteUntil ? HASTE_II : 1f;
        }
    }

    private Pos feet() {
        return new Pos((int) Math.floor(x), (int) Math.floor(feetY), (int) Math.floor(z));
    }

    /** A {@link #hazardAt} cell the box overlaps now, or null. */
    @Nullable Pos boxHazard() {
        Predicate<Pos> h = hazardAt;
        if (h == null) return null;
        double hw = 0.3 - 1e-3;
        int y = (int) Math.floor(feetY);
        for (int cx = (int) Math.floor(x - hw); cx <= (int) Math.floor(x + hw); cx++) {
            for (int cz = (int) Math.floor(z - hw); cz <= (int) Math.floor(z + hw); cz++) {
                Pos c = new Pos(cx, y, cz);
                if (h.test(c)) return c;
            }
        }
        return null;
    }

    /** Runs {@code run} until it ends or {@code maxTicks}; then lets the connection settle. */
    TaskStatus run(NukeRun<Shot> run, long maxTicks) {
        return runFrom(run, 0, maxTicks);
    }

    /**
     * {@link #run} from tick {@code from} on (a later run after an earlier one ended: the server's clock goes on);
     * {@link #ticks} is then the tick the run ended at.
     */
    TaskStatus runFrom(NukeRun<Shot> run, long from, long maxTicks) {
        TaskStatus s = TaskStatus.RUNNING;
        long t = from;
        for (; t < from + maxTicks && s == TaskStatus.RUNNING; t++) {
            beginTick(t);
            s = run.tick(t);
            endTick(t);
            server.tick(t);
            if (afterTick != null) afterTick.run();
        }
        if (s == TaskStatus.RUNNING) {
            beginTick(t);
            run.release();
            endTick(t);
            server.tick(t);
            t++;
        }
        ticks = t;
        for (long end = t + 200; t < end && !server.idle(); t++) {
            beginTick(t);
            server.tick(t);
        }
        return s;
    }

    // ------------------------------------------------------------------------------------------------- the world

    @Override
    public boolean isLoaded(Pos p) {
        return true;
    }

    @Override
    public BlockSpec block(Pos p) {
        BlockType b = view.get(p);
        return b == null ? BlockSpec.AIR : b.spec();
    }

    @Override
    public boolean isAir(Pos pos) {
        return !view.containsKey(pos);
    }

    @Override
    public @Nullable String refusal(Pos p) {
        BlockType b = view.get(p);
        if (b == null) return null;
        if (protectedIds.contains(b.id())) return b.id() + " at " + p;
        if (b.hardness() < 0) return "unbreakable " + b.id() + " at " + p;
        return null;
    }

    @Override
    public int latestAck() {
        return latestAck;
    }

    @Override
    public @Nullable String hazard(Pos cell) {
        return !guardOff && inHazard(cell) ? "no-go zone" : null;
    }

    private boolean inHazard(Pos cell) {
        Predicate<Pos> h = hazardAt;
        return h != null && h.test(cell);
    }

    @Override
    public double keyYaw(@Nullable Shot look) {
        if (!keysFollowLook) return keyYaw;
        Shot l = look != null ? look : sentRot;
        if (l == null || Double.isNaN(l.px())) return keyYaw;
        return Aim.rotationTo(l.ex(), l.ey(), l.ez(), l.px(), l.py(), l.pz())[0];
    }

    /** With {@link #slide}: what the next tick without keys moves (×0.546); else nothing (it stops at once). */
    @Override
    public double[] velocity() {
        return slide ? new double[]{vx * 0.546, vz * 0.546} : new double[]{0, 0};
    }

    @Override
    public boolean walkable(Pos cell) {
        if (inHazard(cell)) return false;
        if (tall(cell) || view.containsKey(cell.above())) return false;
        Predicate<Pos> f = floorAt;
        return f == null || f.test(cell);
    }

    /** A block of the client's view at {@code c} the box cannot stand in (not one of {@link #lowIds}). */
    boolean tall(Pos c) {
        BlockType b = view.get(c);
        return b != null && !lowIds.contains(b.id());
    }

    private boolean wall(Pos c) {
        BlockType b = view.get(c);
        return b != null && wallIds.contains(b.id());
    }

    @Override
    public boolean climbable(Pos c) {
        return tall(c) && !wall(c) && !view.containsKey(c.above());
    }

    @Override
    public boolean escapable(Pos c) {
        BlockType b = view.get(c);
        if (b == null || !clearableIds.contains(b.id()) || refusal(c) != null || view.containsKey(c.above())) return false;
        Predicate<Pos> f = floorAt;
        return f == null || f.test(c);
    }

    /** Some cell of the art level under the box at feet (fx, fz) holds a block the bot can stand on top of. */
    private boolean tallUnder(double fx, double fz) {
        double h = 0.3 - 1e-3;
        int y = (int) Math.floor(feetY);
        for (int cx = (int) Math.floor(fx - h); cx <= (int) Math.floor(fx + h); cx++) {
            for (int cz = (int) Math.floor(fz - h); cz <= (int) Math.floor(fz + h); cz++) {
                Pos c = new Pos(cx, y, cz);
                if (tall(c) && (!wall(c) || onColumn(fx, fz, c))) return true;
            }
        }
        return false;
    }

    /**
     * A {@link #wallIds} block is a thin column (pointed dripstone: 6/16 wide in the middle of its cell): the box at
     * feet (fx, fz) overlaps it.
     */
    private static boolean onColumn(double fx, double fz, Pos c) {
        double lo = 5.0 / 16;
        double hi = 11.0 / 16;
        double h = 0.3 - 1e-3;
        return fx + h > c.x() + lo && fx - h < c.x() + hi && fz + h > c.z() + lo && fz - h < c.z() + hi;
    }

    // ------------------------------------------------------------------------------------------------------ haste

    @Override
    public int hasteTicksLeft() {
        if (clientHasteUntil == Long.MAX_VALUE) return Integer.MAX_VALUE;
        long left = clientHasteUntil - now;
        return left <= 0 ? -1 : (int) left;
    }

    @Override
    public boolean hasteUncertain() {
        int left = hasteTicksLeft();
        return left >= 0 && left < hasteMargin;
    }

    /** The client's mining speed factor: Haste II while the effect has the margin left. */
    private double clientFactor() {
        int left = hasteTicksLeft();
        return left >= hasteMargin ? HASTE_II : 1.0;
    }

    // ------------------------------------------------------------------------------------------------------ tools

    private ToolChoice.Choice choice(BlockType b) {
        double f = clientFactor();
        double hand = DigServer.progress(null, b) * f;
        List<ToolChoice.Candidate> tools = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Item it = hotbar[i];
            if (it == null || it.kind == null || !it.damageable()) continue;
            tools.add(new ToolChoice.Candidate(ItemIds.HOTBAR_START + i, it.id, DigServer.progress(it, b) * f,
                it.remaining(), it.maxUses, it.mending, true, i == selected));
        }
        return ToolChoice.choose(hand, tools, floor);
    }

    @Override
    public BreakPairs.@Nullable Info info(Pos p) {
        BlockType b = view.get(p);
        if (b == null || refusal(p) != null) return null;
        ToolChoice.Choice c = choice(b);
        return switch (c.kind()) {
            case HAND -> new BreakPairs.Info(ToolRack.HAND, DigServer.progress(null, b) * clientFactor());
            case TOOL -> new BreakPairs.Info(c.tool().kind(), c.tool().speed());
            case WORN -> null;
        };
    }

    private final Map<String, Integer> slotFor = new HashMap<>();

    @Override
    public BreakRun.Tool tool(Pos p) {
        BlockType b = view.get(p);
        if (b == null) return BreakRun.Tool.hand();
        ToolChoice.Choice c = choice(b);
        return switch (c.kind()) {
            case HAND -> BreakRun.Tool.hand();
            case TOOL -> {
                slotFor.put(c.tool().kind(), ItemIds.hotbarIndex(c.tool().slot()));
                yield new BreakRun.Tool(ToolChoice.Kind.TOOL, c.tool().kind(), c.tool().remaining(), "");
            }
            case WORN -> new BreakRun.Tool(ToolChoice.Kind.WORN, c.tool() == null ? "" : c.tool().kind(), 0,
                c.wornReason(floor));
        };
    }

    /** Selects at once (the packet goes before this tick's STARTs); true when the hand holds it afterwards. */
    @Override
    public boolean hold(BreakRun.Tool t) {
        int want = switch (t.kind()) {
            case HAND -> handSlot();
            case TOOL -> slotFor.getOrDefault(t.group(), -1);
            case WORN -> -1;
        };
        if (want < 0) return false;
        if (want != selected) {
            selected = want;
            selects++;
            if (recordPackets) packets.add(now + " select " + want);
            server.send(DigServer.Up.SELECT, now, null, null, 0, 0, want, x, z);
        }
        Item h = hotbar[selected];
        return h == null || !h.damageable() || h.remaining() > floor;
    }

    private int handSlot() {
        if (!isTool(hotbar[selected])) return selected;
        for (int i = 0; i < 9; i++) if (hotbar[i] == null) return i;
        for (int i = 0; i < 9; i++) if (!isTool(hotbar[i])) return i;
        return -1;
    }

    private static boolean isTool(@Nullable Item i) {
        return i != null && i.kind != null && i.damageable();
    }

    @Override
    public String heldGroup() {
        Item h = hotbar[selected];
        return isTool(h) ? h.kind : ToolRack.HAND;
    }

    @Override
    public double speedNow(Pos pos) {
        BlockType b = view.get(pos);
        return b == null ? 0 : DigServer.progress(hotbar[selected], b) * clientFactor();
    }

    // ---------------------------------------------------------------------------------------------- walk and aim

    /** The feet the client knows during a tick: where the last movement packet put them (its own goes out at the end). */
    @Override
    public double feetX() {
        return tickX;
    }

    @Override
    public double feetY() {
        return feetY + (elevated ? 1 : 0);
    }

    @Override
    public double feetZ() {
        return tickZ;
    }

    @Override
    public double eyeY() {
        return feetY() + EYE;
    }

    @Override
    public boolean onGround() {
        java.util.function.BooleanSupplier a = airborne;
        return a == null || !a.getAsBoolean();
    }

    @Override
    public void input(@Nullable Shot look, double dx, double dz) {
        requested = look;
        inputThisTick = true;
        java.util.function.BooleanSupplier w = wedged;
        if (w != null && w.getAsBoolean()) return; // every step cancelled
        double l = Math.hypot(dx, dz);
        if (l < 1e-9) {
            if (!slide || (vx == 0 && vz == 0)) return;
            vx *= 0.546;
            vz *= 0.546;
            if (Math.hypot(vx, vz) < 0.003) {
                vx = 0;
                vz = 0;
                return;
            }
            double ox = x;
            double oz = z;
            if (free(x + vx, z)) x += vx;
            else vx = 0;
            if (free(x, z + vz)) z += vz;
            else vz = 0;
            walked += Math.hypot(x - ox, z - oz);
            return;
        }
        walkTicks++;
        double[] k = NukeGuard.keyMotion(keyYaw(look), dx, dz);
        double mx = k[0] * WALK;
        double mz = k[1] * WALK;
        if (slide && topSpeed > 0) {
            // vanilla: last tick's velocity after friction plus a tick's push; a wall stops that axis
            double push = topSpeed * (1 - 0.546);
            vx = vx * 0.546 + k[0] * push;
            vz = vz * 0.546 + k[1] * push;
            mx = vx;
            mz = vz;
        } else if (slide) {
            vx = mx;
            vz = mz;
        }
        double ox = x;
        double oz = z;
        if (free(x + mx, z)) x += mx;
        else if (topSpeed > 0) vx = 0;
        if (free(x, z + mz)) {
            z += mz;
        } else if (topSpeed > 0) {
            vz = 0;
        } else if (bounce > 0 && hidden(x, z + mz) && free(x + bounce * bounceSign, z)) {
            x += bounce * bounceSign;
            bounceSign = -bounceSign;
        }
        walked += Math.hypot(x - ox, z - oz);
        if (elevated && !tallUnder(x, z)) elevated = false; // stepped off the last block under it
    }

    /** The box at feet (fx, fz) overlaps a {@link #hiddenObstacle} cell. */
    private boolean hidden(double fx, double fz) {
        Predicate<Pos> hid = hiddenObstacle;
        if (hid == null) return false;
        double h = 0.3 - 1e-3;
        int y = (int) Math.floor(feetY);
        for (int cx = (int) Math.floor(fx - h); cx <= (int) Math.floor(fx + h); cx++) {
            for (int cz = (int) Math.floor(fz - h); cz <= (int) Math.floor(fz + h); cz++) {
                if (hid.test(new Pos(cx, y, cz))) return true;
            }
        }
        return false;
    }

    /**
     * The box at feet (fx, fz) overlaps no block of the client's view at feet level (on top of the blocks: any), and
     * every cell has a floor.
     */
    private boolean free(double fx, double fz) {
        double h = 0.3 - 1e-3;
        int y = (int) Math.floor(feetY);
        for (int cx = (int) Math.floor(fx - h); cx <= (int) Math.floor(fx + h); cx++) {
            for (int cz = (int) Math.floor(fz - h); cz <= (int) Math.floor(fz + h); cz++) {
                Pos c = new Pos(cx, y, cz);
                if (!elevated && tall(c) && (!wall(c) || onColumn(fx, fz, c))) return false;
                Predicate<Pos> hid = hiddenObstacle;
                if (hid != null && hid.test(c)) return false;
                Predicate<Pos> f = floorAt;
                if (f != null && !f.test(c)) return false;
            }
        }
        return true;
    }

    int approaches;

    @Override
    public TaskStatus approach(Pos p) {
        // as ZenithTask.walkUntil: arrived (a face in reach and sight) before any step
        if (arrived(p)) {
            route = null;
            startingFor = null;
            return TaskStatus.SUCCESS;
        }
        approaches++;
        if (starting(p)) return TaskStatus.RUNNING;
        if (inGoalNoStep) {
            int dx = (int) Math.floor(x) - p.x();
            int dz = (int) Math.floor(z) - p.z();
            if (dx * dx + dz * dz <= 1) {
                route = null;
                walkError = "reached the goal but the arrival test still fails (target out of reach or sight)";
                return TaskStatus.FAILED;
            }
        }
        if (pathing) {
            TaskStatus s = follow(p, c -> Math.abs(c.x() - p.x()) <= 2 && Math.abs(c.z() - p.z()) <= 2);
            if (s != TaskStatus.RUNNING) return s;
        } else {
            straight(p.x() + 0.5, p.z() + 0.5);
        }
        return arrived(p) ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
    }

    /**
     * The approach's arrival test: a face of {@code p} in reach and sight ({@link #aim}); a cell already air in the
     * client's view by its full cube, as Aiming.anyFace does for a block without a shape (live: the walk to a target
     * whose air came after it was chosen ends in reach of it, and the run then counts it broken).
     */
    private boolean arrived(Pos p) {
        if (aim(p) != null) return true;
        if (view.containsKey(p)) return false;
        double ey = eyeY();
        if (ey <= p.y() + 1) return false;
        double cx = Math.max(p.x() + 0.06, Math.min(p.x() + 0.94, tickX));
        double cz = Math.max(p.z() + 0.06, Math.min(p.z() + 0.94, tickZ));
        double dx = cx - tickX;
        double dy = p.y() + 1 - ey;
        double dz = cz - tickZ;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    @Override
    public TaskStatus walkTo(Pos goal, int range) {
        if (Math.abs(Math.floor(x) - goal.x()) <= range && Math.abs(Math.floor(z) - goal.z()) <= range) {
            route = null;
            startingFor = null;
            return TaskStatus.SUCCESS;
        }
        if (starting(List.of(goal, range))) return TaskStatus.RUNNING;
        if (pathing) {
            TaskStatus s = follow(List.of(goal, range), c -> Math.abs(c.x() - goal.x()) <= range
                && Math.abs(c.z() - goal.z()) <= range);
            return s == TaskStatus.SUCCESS ? TaskStatus.RUNNING : s; // arrival is tested on the next call
        }
        straight(goal.x() + 0.5, goal.z() + 0.5);
        return TaskStatus.RUNNING;
    }

    /**
     * One tick of a planned walk for {@code key} ({@link #pathing}): plans once (FAILED without a way or when the path
     * check refuses it), then steps along the cells; SUCCESS at the end of the path.
     */
    private TaskStatus follow(Object key, Predicate<Pos> goal) {
        if (route == null || !key.equals(routeFor)) {
            routeFor = key;
            routeAt = 0;
            route = plan(goal);
            if (route == null) {
                walkError = String.format(java.util.Locale.ROOT, "No path found from %.2f,%.2f%s", x, z, elevated ? " (on top)" : "");
                routeFor = null;
                return TaskStatus.FAILED;
            }
            java.util.function.Function<List<Pos>, String> check = pathCheck;
            String no = check == null ? null : check.apply(route);
            if (no != null) {
                walkError = "the path " + no + "; not walking it";
                route = null;
                routeFor = null;
                return TaskStatus.FAILED;
            }
        }
        List<Pos> r = route;
        while (routeAt < r.size()) {
            Pos c = r.get(routeAt);
            if (Math.hypot(c.x() + 0.5 - x, c.z() + 0.5 - z) > 0.05) break;
            routeAt++;
        }
        if (routeAt >= r.size()) {
            route = null;
            routeFor = null;
            return TaskStatus.SUCCESS;
        }
        Pos c = r.get(routeAt);
        straight(c.x() + 0.5, c.z() + 0.5);
        elevated = elevation && (c.y() > (int) Math.floor(feetY) || tallUnder(x, z));
        return TaskStatus.RUNNING;
    }

    /**
     * Baritone's way (feet cells: the art level, one higher on top of a block) from the feet to the nearest cell
     * {@code goal} accepts: through every cell but {@link #wallIds} blocks and {@link #hazardAt} cells (onto a block, or
     * down off one), diagonally between two tops or across open floor, never past a hazard's corner; null if none.
     */
    private @Nullable List<Pos> plan(Predicate<Pos> goal) {
        int y = (int) Math.floor(feetY);
        Pos start = new Pos((int) Math.floor(x), y, (int) Math.floor(z));
        Map<Pos, Pos> parent = new HashMap<>();
        java.util.ArrayDeque<Pos> queue = new java.util.ArrayDeque<>();
        parent.put(start, start);
        queue.add(start);
        Predicate<Pos> f = floorAt;
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        while (!queue.isEmpty()) {
            Pos c = queue.poll();
            if (!c.equals(start) && goal.test(c)) {
                List<Pos> out = new ArrayList<>();
                for (Pos q = c; !q.equals(start); q = parent.get(q)) out.add(feetCell(q));
                out.add(feetCell(start));
                java.util.Collections.reverse(out);
                return out;
            }
            for (int[] d : steps) {
                Pos n = new Pos(c.x() + d[0], y, c.z() + d[1]);
                if (parent.containsKey(n) || wall(n) || (f != null && !f.test(n)) || inHazard(n)) continue;
                if (Math.abs(n.x() - start.x()) > 160 || Math.abs(n.z() - start.z()) > 160) continue;
                if (d[0] != 0 && d[1] != 0) {
                    Pos a = new Pos(c.x() + d[0], y, c.z());
                    Pos b = new Pos(c.x(), y, c.z() + d[1]);
                    if (inHazard(a) || inHazard(b)) continue;
                    boolean tops = tall(c) && tall(n);
                    boolean floor = !tall(c) && !tall(n) && !tall(a) && !tall(b);
                    if (!tops && !floor) continue;
                }
                parent.put(n, c);
                queue.add(n);
            }
        }
        return null;
    }

    private Pos feetCell(Pos c) {
        return tall(c) ? c.above() : c;
    }

    /** The Baritone walk for {@code key} has not taken its first step yet ({@link #baritoneStartTicks}). */
    private boolean starting(Object key) {
        if (!key.equals(startingFor)) {
            startingFor = key;
            startWaited = 0;
            baritoneWalks++;
        }
        if (startWaited >= baritoneStartTicks) return false;
        startWaited++;
        return true;
    }

    /** A Baritone tick: straight towards (tx, tz), blocks ignored (it would walk over them). */
    private void straight(double tx, double tz) {
        walkTicks++;
        inputThisTick = true;
        java.util.function.BooleanSupplier w = wedged;
        if (w != null && w.getAsBoolean()) return; // "MovementTraverse has taken too long", cancelled, again
        double dx = tx - x;
        double dz = tz - z;
        double d = Math.hypot(dx, dz);
        if (d < 1e-9) return;
        double step = Math.min(Math.max(WALK, topSpeed), d);
        x += dx / d * step;
        z += dz / d * step;
        walked += step;
    }

    @Override
    public String walkError() {
        return walkError;
    }

    @Override
    public void stopWalking() {
        route = null;
        routeFor = null;
        startingFor = null;
    }

    @Override
    public @Nullable Shot aim(Pos p) {
        return aimFrom(p, tickX, eyeY(), tickZ);
    }

    /** {@link #aim} from an eye at (ex, ey, ez) (the run's look ahead: where the eye is after this tick's movement). */
    @Override
    public @Nullable Shot aimFrom(Pos p, double ex, double ey, double ez) {
        if (!view.containsKey(p) || view.containsKey(p.above())) return null;
        if (hideUnderBox) {
            double h = 0.3 - 1e-3;
            if (p.x() >= Math.floor(x - h) && p.x() <= Math.floor(x + h) && p.z() >= Math.floor(z - h)
                && p.z() <= Math.floor(z + h)) {
                return null;
            }
        }
        if (pitSight && lowIds.contains(view.get(p).id())) return pitAim(p, ex, ey, ez);
        if (ey <= p.y() + 1) return null;
        double cx = Math.max(p.x() + 0.06, Math.min(p.x() + 0.94, ex));
        double cz = Math.max(p.z() + 0.06, Math.min(p.z() + 0.94, ez));
        double dx = cx - ex;
        double dy = p.y() + 1 - ey;
        double dz = cz - ez;
        if (dx * dx + dy * dy + dz * dz > reach * reach) return null;
        return new Shot(p, UP, ex, ey, ez, cx, p.y() + 1, cz);
    }

    /**
     * {@link #pitSight}: the top face of a low block (1/16 high) at points 0.06 inset (corners, edge middles, centre),
     * nearest first, the first one within reach whose ray from the eye passes no full block of the 8 cells around it
     * (live: Aiming's raycast; a carpet among kept full blocks lies in a pit 15/16 deep).
     */
    private @Nullable Shot pitAim(Pos p, double ex, double ey, double ez) {
        double fy = p.y() + LOW_TOP;
        if (ey <= fy) return null;
        double[] at = {0.06, 0.5, 0.94};
        List<double[]> pts = new ArrayList<>(9);
        for (double u : at) for (double v : at) pts.add(new double[]{p.x() + u, p.z() + v});
        pts.sort((a, b) -> Double.compare(Math.hypot(a[0] - ex, a[1] - ez), Math.hypot(b[0] - ex, b[1] - ez)));
        for (double[] q : pts) {
            double dx = q[0] - ex;
            double dy = fy - ey;
            double dz = q[1] - ez;
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
            boolean clear = true;
            for (int ox = -1; ox <= 1 && clear; ox++) {
                for (int oz = -1; oz <= 1 && clear; oz++) {
                    if (ox == 0 && oz == 0) continue;
                    Pos n = new Pos(p.x() + ox, p.y(), p.z() + oz);
                    if (tall(n) && segmentHitsCell(ex, ey, ez, q[0], fy, q[1], n)) clear = false;
                }
            }
            if (clear) return new Shot(p, UP, ex, ey, ez, q[0], fy, q[1]);
        }
        return null;
    }

    /** Top of a low block (a carpet) above its cell's floor. */
    static final double LOW_TOP = 0.0625;

    /** The segment from (ax, ay, az) to (bx, by, bz) passes through the unit cell {@code c} (slab test). */
    static boolean segmentHitsCell(double ax, double ay, double az, double bx, double by, double bz, Pos c) {
        double t0 = 0;
        double t1 = 1;
        double[] a = {ax, ay, az};
        double[] d = {bx - ax, by - ay, bz - az};
        double[] lo = {c.x(), c.y(), c.z()};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (a[i] <= lo[i] || a[i] >= lo[i] + 1) return false;
                continue;
            }
            double ta = (lo[i] - a[i]) / d[i];
            double tb = (lo[i] + 1 - a[i]) / d[i];
            t0 = Math.max(t0, Math.min(ta, tb));
            t1 = Math.min(t1, Math.max(ta, tb));
            if (t0 >= t1) return false;
        }
        return true;
    }

    @Override
    public @Nullable Shot inEffect(Shot shot) {
        Shot r = sentRot;
        if (r == null || !r.block().equals(shot.block())) return null;
        if (!geometricLook || Double.isNaN(r.px())) return r;
        // the rotation sent: towards the point aimed at from the eye of that tick; cast it from the eye the server
        // knows (the feet of the last movement packet: this tick's own goes out after the dig packets)
        double dx = r.px() - r.ex();
        double dy = r.py() - r.ey();
        double dz = r.pz() - r.ez();
        double ey = eyeY();
        double sx = tickX;
        double sz = tickZ;
        Pos b = r.block();
        if (dy >= 0) return null;
        double fy = r.py(); // the face aimed at: the block's top (a low block's with pitSight)
        double t = (fy - ey) / dy;
        double hx = sx + t * dx;
        double hz = sz + t * dz;
        if (hx < b.x() || hx > b.x() + 1 || hz < b.z() || hz > b.z() + 1) return null;
        double dist = Math.sqrt((hx - sx) * (hx - sx) + (fy - ey) * (fy - ey) + (hz - sz) * (hz - sz));
        return dist <= reach ? r : null;
    }

    @Override
    public boolean canAct() {
        return prevExecuted && inputThisTick;
    }

    // ------------------------------------------------------------------------------------------------------- send

    @Override
    public int send(DigClient.Action action, Pos pos, Shot shot) {
        int s = action == DigClient.Action.ABORT ? 0 : ++seq;
        if (recordPackets) packets.add(now + " " + action + " " + pos.x() + "," + pos.y() + "," + pos.z());
        if (action == DigClient.Action.START && !onGround()) airborneStarts++;
        if (action == DigClient.Action.START && pos.y() > feetY + 100) {
            // a fake START (Lambda): far above the art layer
            fakeStarts++;
            if (!DigClient.outOfWorld(pos)) fakeInWorld++;
            server.send(DigServer.Up.DIG, now, action, pos, shot.face(), s, 0, x, z);
            tickDigs.add(new DigServer.UpPacket(DigServer.Up.DIG, now, now, action, pos, shot.face(), s, 0, x, z));
            return s;
        }
        if (action == DigClient.Action.START) {
            BlockType held = view.get(pos);
            if (held != null && now - lastStopTick < 6 && DigServer.progress(hotbar[selected], held) * clientFactor() >= 1.0) {
                instantWithin6OfStop++;
            }
            tickStarts++;
            if (now - lastStopTick < 6) startsWithin6OfStop++;
            if (!server.world.containsKey(pos)) airStarts++;
        }
        server.send(DigServer.Up.DIG, now, action, pos, shot.face(), s, 0, x, z);
        tickDigs.add(new DigServer.UpPacket(DigServer.Up.DIG, now, now, action, pos, shot.face(), s, 0, x, z));
        if (action == DigClient.Action.STOP) lastStopTick = now;
        return s;
    }

    @Override
    public void swing() {
        tickSwings++;
        if (recordPackets) packets.add(now + " swing");
        server.send(DigServer.Up.SWING, now, null, null, 0, 0, 0, x, z);
    }

    @Override
    public void log(String line) {
        log.add(line);
        if (echoLog) System.out.println(line);
    }

    // ----------------------------------------------------------------------------------------------------- report

    @Nullable Item clientItem(int i) {
        return hotbar[i];
    }

    String rules() {
        GrimBreakModel g = server.grim;
        return String.format(Locale.ROOT, "client: max STARTs/tick %d, STARTs <6 ticks after a STOP %d, noSwing %d,"
                + " multiBreak ticks %d, air STARTs %d; grim: FastBreak max break %.0f delay %.0f (flags %d/%d)"
                + " WrongBreak %d AirLiquid %d PositionBreakB %d MultiBreak %d NoSwing %d FarBreak %d (farthest %.2f);"
            + " server tooFar %d",
            maxStartsInTick, startsWithin6OfStop, noSwingTicks, multiBreakTicks, airStarts, g.maxBreakBalance,
            g.maxDelayBalance, g.fastBreakBreakFlags, g.fastBreakDelayFlags, g.wrongBreakFlags, g.airLiquidFlags,
            g.positionBreakBFlags, g.multiBreakFlags, g.noSwingFlags, server.farBreak, server.farthestDig,
            server.tooFar);
    }
}
