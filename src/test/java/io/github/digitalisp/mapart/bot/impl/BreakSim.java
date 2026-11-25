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

/**
 * The client side of a {@link BreakRun} against a {@link DigServer}: what ZenithProxy's caches show and what the bot
 * sends ({@link BreakRun.Io}). A flat art layer at y = 64 (on a floor at y = 63) in a 128×128 tile; the bot's feet are
 * at y = 64 (collisions ignored), the eye 1.62 above; it walks in a straight line at {@link #WALK} blocks per tick.
 *
 * <ul>
 *   <li><b>World</b>: the client's copy of the server's blocks, changed only by block updates after the downlink
 *       latency (nothing predicted).</li>
 *   <li><b>Aim</b>: the top face of a block within reach (eye to the nearest point of the face, 0.1 inset, ≤ 4.5)
 *       when the block above is air and the eye above the face.</li>
 *   <li><b>Rotation</b>: {@link #look} is this tick's request; the movement packet at the end of the tick carries
 *       it (unless the tick's input is lost, {@link #lostTicks}). {@link #inEffect} is the rotation of the last
 *       movement packet if it was aimed at the same block and the bot has not moved since. {@link #canAct}: last tick's
 *       input was executed and this tick's is pending.</li>
 *   <li><b>Hotbar</b> (as after {@link ToolRack#arrange}): food in slot 0, empty slots for the hand, each tool in its
 *       own slot. The held slot changes on the client at once and on the server when its select arrives;
 *       {@link #hold} answers true from the tick after the select. Tool wear shows after the downlink latency.</li>
 *   <li><b>Tools</b>: {@link ToolChoice#choose} over the hotbar tools with the vanilla speeds of
 *       {@link DigServer#progress}.</li>
 * </ul>
 * Per tick: {@link #beginTick} (the server's packets that arrived), {@code BreakRun.tick}, {@link #endTick} (the
 * movement packet, the per-tick rule counters), then {@link DigServer#tick}.
 */
final class BreakSim implements BreakRun.Io<BreakSim.Shot> {
    static final double WALK = 0.2158;
    static final double EYE = 1.62;
    static final double REACH = 4.5;
    static final int UP = 1;

    /** A rotation aimed at {@code block}'s {@code face}. */
    record Shot(Pos block, int face) { }

    final DigServer server;
    private final int floor;
    private final Set<String> protectedIds;
    private final Random rnd;
    final double feetY;

    // client view
    private final Map<Pos, BlockType> view = new HashMap<>();
    private final Item[] hotbar = new Item[9];
    private int selected;
    double x;
    double z;
    private int latestAck = -1;
    private int seq;
    private long now;

    // input
    private @Nullable Shot requested;
    private boolean inputThisTick;
    private boolean walkedThisTick;
    private @Nullable Shot sentRot;
    private double sentX = Double.NaN;
    private double sentZ = Double.NaN;
    private boolean prevExecuted = true;
    /** Share of ticks whose input request is not executed (the rotation is not sent). */
    double lostTicks;
    /**
     * How far (blocks, horizontally) the walk goes past the reach boundary of its target before {@link #aim} answers
     * for it: 0 is production (stop as soon as a face is in reach); more stands deeper in a cluster, so more partners
     * are in reach (an experiment, not production behaviour).
     */
    double approachDepth;
    private @Nullable Pos approaching;
    /**
     * Distance drift: {@link #distSq} of the blocks with x &gt; z is this much larger on even ticks and smaller on odd ones,
     * the others the other way round, as a bot that sways by a hair makes two targets at the same distance swap places
     * tick by tick.
     */
    double distanceDrift;
    /**
     * Baritone's start: a walk to a new target moves only after this many ticks on that target (a path search, the
     * first movement). 0: at once (the default).
     */
    int pathStartTicks;
    private @Nullable Pos walkGoal;
    private long walkGoalSince;
    /** Times the walk's target changed. */
    int goalSwitches;
    private final Map<BreakRun.Tool, Integer> slotFor = new HashMap<>();
    private int switchedAt = -1;
    private long switchTick = Long.MIN_VALUE;

    // this tick's packets
    private int tickStarts;
    private int tickSwings;
    private final List<DigServer.UpPacket> tickDigs = new ArrayList<>();

    // statistics
    /** Client ticks the run took (until it ended or was stopped). */
    long ticks;
    int walkTicks;
    int selects;
    int startViolations;
    int noSwingTicks;
    int multiBreakTicks;
    int lostTickCount;
    final Map<Pos, Integer> brokenCalls = new HashMap<>();
    final List<String> log = new ArrayList<>();
    boolean echoLog;

    BreakSim(DigServer server, double startX, double startZ, int floor, Set<String> protectedIds, long seed) {
        this.server = server;
        this.floor = floor;
        this.protectedIds = protectedIds;
        this.rnd = new Random(seed);
        this.feetY = server.feetY;
        this.x = startX;
        this.z = startZ;
        server.px = startX;
        server.pz = startZ;
        view.putAll(server.world);
        for (int i = 0; i < 9; i++) hotbar[i] = server.hotbar[i] == null ? null : server.hotbar[i].copy();
        selected = server.selected;
    }

    /** The callback for {@link BreakRun}'s broken blocks (counts calls per block). */
    void broken(Pos p) {
        brokenCalls.merge(p, 1, Integer::sum);
    }

    // ------------------------------------------------------------------------------------------------------ ticks

    void beginTick(long t) {
        now = t;
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
    }

    void endTick(long t) {
        if (tickStarts > 1) startViolations++;
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
        boolean executed = !(lostTicks > 0 && rnd.nextDouble() < lostTicks);
        if (!executed) lostTickCount++;
        if (executed) {
            if (walkedThisTick) sentRot = null; // the walk turns the bot along its path
            else if (inputThisTick && requested != null) sentRot = requested;
        }
        server.send(DigServer.Up.MOVE, t, null, null, 0, 0, 0, x, z);
        sentX = x;
        sentZ = z;
        prevExecuted = executed;
        requested = null;
        inputThisTick = false;
        walkedThisTick = false;
        tickStarts = 0;
        tickSwings = 0;
        tickDigs.clear();
    }

    /** Runs {@code run} until it ends or {@code maxTicks}; then lets the connection settle. */
    TaskStatus run(BreakRun<Shot> run, long maxTicks) {
        TaskStatus s = TaskStatus.RUNNING;
        long t = 0;
        for (; t < maxTicks && s == TaskStatus.RUNNING; t++) {
            beginTick(t);
            s = run.tick(t);
            endTick(t);
            server.tick(t);
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

    // ------------------------------------------------------------------------------------------------------ tools

    private ToolChoice.Choice choice(BlockType b) {
        double hand = DigServer.progress(null, b);
        List<ToolChoice.Candidate> tools = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Item it = hotbar[i];
            if (it == null || it.kind == null || !it.damageable()) continue;
            tools.add(new ToolChoice.Candidate(ItemIds.HOTBAR_START + i, it.id, DigServer.progress(it, b),
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
            case HAND -> new BreakPairs.Info(ToolRack.HAND, DigServer.progress(null, b));
            case TOOL -> new BreakPairs.Info(c.tool().kind(), c.tool().speed());
            case WORN -> null;
        };
    }

    @Override
    public BreakRun.Tool tool(Pos p) {
        BlockType b = view.get(p);
        if (b == null) return BreakRun.Tool.hand();
        ToolChoice.Choice c = choice(b);
        return switch (c.kind()) {
            case HAND -> BreakRun.Tool.hand();
            case TOOL -> {
                BreakRun.Tool t = new BreakRun.Tool(ToolChoice.Kind.TOOL, c.tool().kind(), c.tool().remaining(), "");
                slotFor.put(t, ItemIds.hotbarIndex(c.tool().slot()));
                yield t;
            }
            case WORN -> new BreakRun.Tool(ToolChoice.Kind.WORN, c.tool() == null ? "" : c.tool().kind(), 0,
                c.wornReason(floor));
        };
    }

    @Override
    public boolean hold(BreakRun.Tool t) {
        int want = switch (t.kind()) {
            case HAND -> handSlot();
            case TOOL -> slotFor.getOrDefault(t, -1);
            case WORN -> -1;
        };
        if (want < 0) return false;
        if (want == selected) {
            if (switchedAt == want && now == switchTick) return false; // the select went out this tick
            Item h = hotbar[selected];
            return h == null || !h.damageable() || h.remaining() > floor;
        }
        selected = want;
        selects++;
        switchedAt = want;
        switchTick = now;
        server.send(DigServer.Up.SELECT, now, null, null, 0, 0, want, x, z);
        return false;
    }

    /** A slot holding no tool: the held one if it holds none, else the first empty one, else food. */
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
        return b == null ? 0 : DigServer.progress(hotbar[selected], b);
    }

    // ---------------------------------------------------------------------------------------------- walk and aim

    private double eyeY() {
        return feetY + EYE;
    }

    @Override
    public double distSq(Pos p) {
        double d = p.distSq(x, eyeY(), z);
        if (distanceDrift > 0) d += ((now + (p.x() > p.z() ? 1 : 0)) % 2 == 0 ? 1 : -1) * distanceDrift;
        return d;
    }

    @Override
    public TaskStatus approach(Pos p) {
        walkTicks++;
        walkedThisTick = true;
        inputThisTick = true;
        if (!p.equals(walkGoal)) {
            walkGoal = p;
            walkGoalSince = now;
            goalSwitches++;
        }
        if (now - walkGoalSince < pathStartTicks) return TaskStatus.RUNNING;
        double tx = p.x() + 0.5 - x;
        double tz = p.z() + 0.5 - z;
        double d = Math.hypot(tx, tz);
        if (d > 1e-9) {
            double step = Math.min(WALK, d);
            x += tx / d * step;
            z += tz / d * step;
        }
        approaching = p;
        return aim(p) != null ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
    }

    /** Pair stands walked to, and the ticks spent walking to them. */
    int pairWalks;
    int pairWalkTicks;

    /** Straight toward the point between the two blocks until both are in sight; FAILED once there without it. */
    @Override
    public TaskStatus approachPair(Pos a, Pos b) {
        if (aim(a) != null && aim(b) != null) return TaskStatus.SUCCESS;
        walkTicks++;
        pairWalkTicks++;
        walkedThisTick = true;
        inputThisTick = true;
        double mx = (a.x() + b.x()) / 2.0 + 0.5;
        double mz = (a.z() + b.z()) / 2.0 + 0.5;
        double tx = mx - x;
        double tz = mz - z;
        double d = Math.hypot(tx, tz);
        if (d < 0.05) return TaskStatus.FAILED;
        double step = Math.min(WALK, d);
        x += tx / d * step;
        z += tz / d * step;
        if (aim(a) != null && aim(b) != null) {
            pairWalks++;
            return TaskStatus.SUCCESS;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public String walkError() {
        return "";
    }

    @Override
    public void stopWalking() {
    }

    @Override
    public @Nullable Shot aim(Pos p) {
        if (!view.containsKey(p) || view.containsKey(p.above())) return null;
        double ey = eyeY();
        if (ey <= p.y() + 1) return null;
        double cx = Math.max(p.x() + 0.1, Math.min(p.x() + 0.9, x));
        double cz = Math.max(p.z() + 0.1, Math.min(p.z() + 0.9, z));
        double dx = cx - x;
        double dy = p.y() + 1 - ey;
        double dz = cz - z;
        if (dx * dx + dy * dy + dz * dz > REACH * REACH) return null;
        if (approachDepth > 0 && p.equals(approaching)) {
            double r = REACH - approachDepth;
            if (dx * dx + dy * dy + dz * dz > r * r) return null;
            approaching = null;
        }
        return new Shot(p, UP);
    }

    @Override
    public @Nullable Shot inEffect(Shot shot) {
        Shot r = sentRot;
        if (r == null || !r.block().equals(shot.block())) return null;
        if (x != sentX || z != sentZ) return null;
        return r;
    }

    @Override
    public void look(@Nullable Shot shot) {
        requested = shot;
        inputThisTick = true;
    }

    @Override
    public boolean canAct() {
        return prevExecuted && inputThisTick;
    }

    // ------------------------------------------------------------------------------------------------------- send

    @Override
    public int send(DigClient.Action action, Pos pos, Shot shot) {
        int s = action == DigClient.Action.ABORT ? 0 : ++seq;
        if (action == DigClient.Action.START) tickStarts++;
        server.send(DigServer.Up.DIG, now, action, pos, shot.face(), s, 0, x, z);
        tickDigs.add(new DigServer.UpPacket(DigServer.Up.DIG, now, now, action, pos, shot.face(), s, 0, x, z));
        return s;
    }

    @Override
    public void swing() {
        tickSwings++;
        server.send(DigServer.Up.SWING, now, null, null, 0, 0, 0, x, z);
    }

    @Override
    public void log(String line) {
        log.add(line);
        if (echoLog) System.out.println(line);
    }

    // ----------------------------------------------------------------------------------------------------- report

    /** Client-side tool view of hotbar slot {@code i}. */
    @Nullable Item clientItem(int i) {
        return hotbar[i];
    }

    int selected() {
        return selected;
    }

    String rules() {
        GrimBreakModel g = server.grim;
        return String.format(Locale.ROOT, "client: startViolations %d noSwing %d multiBreak %d; grim: FastBreak max"
                + " break %.0f delay %.0f (flags %d/%d) WrongBreak %d AirLiquid %d PositionBreakB %d MultiBreak %d"
                + " NoSwing %d", startViolations, noSwingTicks, multiBreakTicks, g.maxBreakBalance, g.maxDelayBalance,
            g.fastBreakBreakFlags, g.fastBreakDelayFlags, g.wrongBreakFlags, g.airLiquidFlags, g.positionBreakBFlags,
            g.multiBreakFlags, g.noSwingFlags);
    }
}
