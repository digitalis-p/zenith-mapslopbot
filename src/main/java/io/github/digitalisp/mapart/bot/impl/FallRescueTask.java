package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.player.Input;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.FallRescue;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.zenith.Globals.BLOCK_DATA;
import static com.zenith.Globals.BOT;

/**
 * {@code rescueFall}: out of water or a pit, back onto dry ground at the floor's
 * level.
 *
 * <p><b>The climb</b> ({@code build.fallRescuePillar}, first): once the bot has come to rest, {@link FallClimb} plans
 * the way up with blocks of its own ({@link FallClimbBlocks}: {@code build.fallRescueBlocks} first) and which of them
 * to break again from where. Each move: centred in its column with its own keys, the block in hand (hotbar slot 8),
 * a look at the support's face and one click (checked on the next tick against the rotation sent, as every click of the
 * bot), the block seen in the world, then the jump onto it; a pillar is a jump straight up looking down with the click
 * once the feet are above the cell. Its own blocks are broken from the stands the plan names (never with a tool at
 * {@code build.toolSafetyFloor}: {@link BreakBlocksTask#chooseTool}; the hand when every tool is at the floor and the
 * block breaks by hand within {@link #HAND_BREAK_MAX_TICKS}), the last ones from the exit cell. A slip, a click that
 * shows no block, a jump that falls short: planned again from where the bot is (at most {@link #MAX_PLANS} plans).
 * During the climb the per-tick no-go check is off (its head is in the parked machines' zone over the channel): the
 * planner checks every cell it passes free of collision, and its blocks go only into the cells
 * {@link FallClimbGrid#placeAllowed} allows. Blocks it could not break again are logged and told to the engine
 * ({@link FallRescue#leftBlocks}).
 *
 * <p><b>Without the climb</b> (off, no plan, no block to climb with, or it gave up): the exits are {@link FallExit}'s dry
 * cells, nearest first. An exit next to the bot's column is reached with the bot's own keys: facing it, forward and
 * jump. A farther exit goes to Baritone (it walks or swims along a channel). Each exit gets {@link #OWN_KEYS_TICKS} or
 * one Baritone walk with one retry; then the next one.
 *
 * <p>SUCCESS once on the ground, dry, feet at the floor's level or above (and the climb's blocks dealt with).
 */
final class FallRescueTask extends ZenithTask {
    /** Ticks the own keys try one exit beside the bot. */
    static final int OWN_KEYS_TICKS = 100;
    /** Exits tried at most. */
    static final int MAX_EXITS = 4;
    /** Ticks the bot may take to come to rest (on the channel's floor) before the climb is planned. */
    static final int SETTLE_TICKS = 20;
    /** Plans of the climb at most (the first one and one after each slip or failed step). */
    static final int MAX_PLANS = 10;
    /** Ticks a placed block has to show up in the world. */
    static final int CONFIRM_TICKS = 12;
    /** Clicks for one block before its cell is given up. */
    static final int PLACE_TRIES = 3;
    /** Ticks for a centring, a hop onto a block or the exit. */
    static final int MOVE_TICKS = 60;
    /** Jumps for one pillar block before its cell is given up. */
    static final int PILLAR_JUMPS = 3;
    /** A pillar's click goes once the feet are this far above the cell's bottom (its top plus a margin). */
    static final double PILLAR_CLICK_ABOVE = 1.02;
    /** An own block is broken by hand (every tool at the floor) only if that takes at most this long. */
    static final int HAND_BREAK_MAX_TICKS = 600;
    /**
     * An own block broken counts once it has stayed gone this long: the cache shows the break at once, and a break the
     * server refused (it kept the block: local run 2026-10-23, the trench check found two of them) comes back a tick or
     * two later.
     */
    static final int BREAK_CONFIRM_TICKS = 10;
    /** Times an own block may come back before the climb gives it up. */
    static final int MAX_COMEBACKS = 3;
    /** Off a column's centre at most this far before a click into the column beside (the box is 0.3 wide each way). */
    static final double STAIR_CENTRE = 0.15;
    static final double PILLAR_CENTRE = 0.12;
    /** While it aims and clicks for a stair the bot keeps within this of its column's centre ({@link #keepOn}). */
    static final double KEEP_ON = 0.06;
    /** Out on the tile the bot steps (sneaking) to within this of its cell's centre, away from the edge it came over. */
    static final double EXIT_CENTRE = 0.2;

    private final FallRescue request;
    private final EdgeDrop.Cells cells;
    private final Set<Pos> tried = new HashSet<>();
    private @Nullable Pos exit;
    private boolean ownKeys;
    private long exitSince;
    private String lastProblem = "";
    private boolean holdLogged;

    private enum Stage { SETTLE, CENTRE, HOLD, AIM, CLICK, CONFIRM, HOP, PILLAR, EXIT, BREAK, DONE }

    private boolean climbing;
    private boolean climbTried;
    private FallClimb.@Nullable Plan plan;
    private @Nullable Pos planStart;
    private @Nullable Pos startFeet;
    private int moveIndex;
    private Stage stage = Stage.SETTLE;
    private long stageSince;
    private int plans;
    private int tries;
    private int jumps;
    private boolean leftGround;
    /** The highest the feet got in the pillar jump under way. */
    private double jumpTop;
    /** The climb's own blocks in the world: cell → block id. */
    private final Map<Pos, String> own = new LinkedHashMap<>();
    /** Cells the climb gave up placing into. */
    private final Set<Pos> banned = new HashSet<>();
    /** Own blocks a break gave up on (out of sight, out of reach, no tool): tried again from the exit. */
    private final Set<Pos> unbroken = new HashSet<>();
    private @Nullable String blockId;
    private Aiming.@Nullable Shot aimed;
    private final Deque<Pos> breaks = new ArrayDeque<>();
    private @Nullable Pos breaking;
    private long breakSince;
    private long breakBudget;
    private boolean finalSweep;
    private boolean ownTold;
    /** The own block that looks broken, and since when (it counts after {@link #BREAK_CONFIRM_TICKS}). */
    private @Nullable Pos goneAt;
    private long goneSince;
    /** Times own blocks came back after a break. */
    private final Map<Pos, Integer> comebacks = new HashMap<>();
    /** The highest feet level the bot stood at during the try ({@link FallRescue.Ended#standY}). */
    private int standY = FallRescue.ANY_LEVEL;
    private int placed;
    private final Map<String, Integer> blocksUsed = new HashMap<>();

    FallRescueTask(ZenithBotActions ctx, FallRescue request) {
        super(ctx, request.maxTicks() + Math.max(0, request.holdTicks()));
        this.request = request;
        this.cells = EdgeApproach.cells(ctx.world(), zones);
        own.putAll(request.own());
    }

    /**
     * However the try ends: its own blocks still standing (the next try takes them over) and the highest level it stood
     * at go to the engine.
     */
    @Override
    protected void onRelease() {
        if (ownTold) return;
        ownTold = true;
        if (!own.isEmpty()) {
            LOG.warn("Fall rescue: ends with {} own block(s) still standing ({}); the next try takes them over", own.size(),
                String.join("; ", own.entrySet().stream().map(e -> e.getKey().key() + " " + e.getValue()).toList()));
        }
        request.ended().accept(new FallRescue.Ended(own, standY));
    }

    @Override
    public String name() {
        return "rescueFall from " + request.at().key();
    }

    @Override
    protected String progress() {
        if (climbing && plan != null) {
            return " (climb: " + stage + " in move " + (moveIndex + 1) + " of " + plan.moves().size() + ", plan " + plans
                + ", " + own.size() + " own block(s) in place" + (lastProblem.isEmpty() ? "" : "; " + lastProblem) + ")";
        }
        return exit == null ? (lastProblem.isEmpty() ? "" : " (" + lastProblem + ")") : " (exit " + exit.key()
            + (ownKeys ? ", own keys" : ", Baritone") + "; " + tried.size() + " tried"
            + (lastProblem.isEmpty() ? "" : "; " + lastProblem) + ")";
    }

    /**
     * The climb moves through the parked machines' zone over a channel: its own fence instead ({@link FallClimb}). So
     * does an own-key exit into the cell beside (the feet pass the zone's bottom over the channel: live 2026-10-19,
     * "entered no-go zone #6"); the exit cell itself is never in a zone ({@link FallExit}). Baritone's exits keep it.
     */
    @Override
    protected boolean checksNoGoZones() {
        return !climbing && !(exit != null && ownKeys);
    }

    /** Back on dry ground at the floor's level. */
    private boolean out() {
        if (!BOT.isOnGround() || BOT.isTouchingWater()) return false;
        Pos feet = Walker.feet();
        // the engine's water test cell (0.2 above the feet: FallWatch.WET_ABOVE)
        if (cells.fluid(new Pos(feet.x(), (int) Math.floor(BOT.getY() + 0.2), feet.z()))) return false;
        return request.floorY() == FallRescue.ANY_LEVEL || feet.y() >= request.floorY();
    }

    @Override
    protected TaskStatus step() {
        if (BOT.isOnGround()) standY = Math.max(standY, Walker.feet().y());
        if (now <= request.holdTicks() && !(out() && own.isEmpty())) {
            // (out during the hold: the owner got the bot out by hand; it carries on at once)
            if (!holdLogged) {
                holdLogged = true;
                LOG.info("Fall rescue: holding its place {} s at {} first (a clear's flying machines may still move over"
                    + " the channels, or the wait before another try)", request.holdTicks() / 20, Walker.feet().key());
            }
            still();
            return TaskStatus.RUNNING;
        }
        if (climbing) return climb();
        if (out() && !own.isEmpty() && !climbTried && cfg.build.fallRescuePillar) {
            // out already, but an earlier try's blocks still stand: they go first, the highest first
            climbTried = true;
            climbing = true;
            finalSweep = true;
            List<Pos> rest = new ArrayList<>(own.keySet());
            rest.sort((a, b) -> Integer.compare(b.y(), a.y()));
            breaks.clear();
            breaks.addAll(rest);
            setStage(Stage.BREAK);
            return TaskStatus.RUNNING;
        }
        if (out()) {
            walker.stop();
            LOG.info("Fall rescue: back on dry ground at {} after {} ticks{}", Walker.feet().key(), now,
                exit == null ? "" : " (exit " + exit.key() + ")");
            reportLeft();
            return TaskStatus.SUCCESS;
        }
        if (!climbTried && cfg.build.fallRescuePillar) {
            if (!settled() && now - request.holdTicks() < SETTLE_TICKS) {
                still();
                return TaskStatus.RUNNING;
            }
            climbTried = true;
            startFeet = Walker.feet();
            if (newPlan("start")) return TaskStatus.RUNNING;
            climbing = false;
        }
        if (exit == null && !nextExit()) {
            return fail("no way out from " + Walker.feet().key() + ": " + (tried.isEmpty()
                ? "no dry cell at the floor's level within " + FallExit.RADIUS + " blocks" : tried.size()
                + " exits tried") + (lastProblem.isEmpty() ? "" : " (last: " + lastProblem + ")"));
        }
        Pos x = exit;
        if (ownKeys) {
            if (now - exitSince > OWN_KEYS_TICKS) {
                lastProblem = "swimming or climbing to " + x.key() + " took over " + OWN_KEYS_TICKS + " ticks";
                exit = null;
                return TaskStatus.RUNNING;
            }
            clicker.submit(now, yawTo(x), 0f, Input.builder().pressingForward(true).jumping(true).build());
            return TaskStatus.RUNNING;
        }
        TaskStatus s = walkRecover(List.of(new GoalNearXZ(x.x(), x.y(), x.z(), 0, 1)), this::out, 1);
        if (s == TaskStatus.FAILED) {
            lastProblem = "Baritone to " + x.key() + ": " + walker.error();
            exit = null;
        }
        return TaskStatus.RUNNING;
    }

    /** The nearest exit not tried yet; false when none is left (or {@link #MAX_EXITS} were tried). */
    private boolean nextExit() {
        if (tried.size() >= MAX_EXITS) return false;
        Pos feet = Walker.feet();
        int lo = request.floorY() == FallRescue.ANY_LEVEL ? feet.y() : request.floorY();
        int hi = request.floorY() == FallRescue.ANY_LEVEL ? feet.y() + 2 : request.floorY() + 1;
        for (Pos c : FallExit.exits(feet, lo, hi, cells, MAX_EXITS + tried.size())) {
            if (tried.contains(c)) continue;
            tried.add(c);
            exit = c;
            ownKeys = FallExit.beside(feet, c);
            exitSince = now;
            walker.stop();
            LOG.info("Fall rescue: from {} to {} ({}, {} of at most {})", feet.key(), c.key(),
                ownKeys ? "own keys: forward and jump" : "Baritone", tried.size(), MAX_EXITS);
            return true;
        }
        return false;
    }

    // ---- the climb ----

    /** On the ground, or in water and hardly moving up or down (resting on a channel's floor, or floating). */
    private static boolean settled() {
        if (BOT.isOnGround()) return true;
        return BOT.isTouchingWater() && Math.abs(BOT.getVelocity().getY()) < 0.03;
    }

    /** The feet's height for the planner: a block top's whole number when within 0.01 of it. */
    private static double feetHeight() {
        double y = BOT.getY();
        double r = Math.rint(y);
        return Math.abs(y - r) < 0.01 ? r : y;
    }

    /**
     * Plans the climb from where the bot is now. False when there is none (or no block to climb with, or too many
     * plans): the climb is over and the swim-and-step exits follow.
     */
    private boolean newPlan(String why) {
        if (++plans > MAX_PLANS) {
            lastProblem = "the climb gave up after " + MAX_PLANS + " plans (last: " + why + ")";
            LOG.warn("Fall rescue: {}", lastProblem);
            climbing = false;
            return false;
        }
        walker.stop();
        Pos feet = Walker.feet();
        double h = feetHeight();
        FallClimbGrid g = new FallClimbGrid(ctx.world(), cfg, zones, startFeet != null ? startFeet : feet,
            request.floorY(), banned);
        FallClimb.Plan p = FallClimb.plan(feet, h, own.keySet(), g, reach() - 0.2);
        if (p == null) {
            lastProblem = "no climb from " + feet.key() + " within " + FallClimb.MAX_BLOCKS + " blocks";
            LOG.warn("Fall rescue: {} ({})", lastProblem, why);
            climbing = false;
            return false;
        }
        int have = p.blocks() > 0 ? FallClimbBlocks.available(carried(), cfg.build.fallRescueBlocks,
            cfg.build.fallRescueAnyBlock, FallClimbBlocks::suitable) : 0;
        if (p.blocks() > 0 && (have < p.blocks() || !chooseBlock())) {
            // never begun with too few: a block left half way in a channel holds up its water and items
            lastProblem = "the climb needs " + p.blocks() + " block(s) and the bot carries " + (have == 0 ? "none" : "only "
                + have) + " that suit" + (have == 1 ? "s" : "");
            LOG.warn("Fall rescue: {} (build.fallRescueBlocks {}, any block {})", lastProblem, cfg.build.fallRescueBlocks,
                cfg.build.fallRescueAnyBlock);
            climbing = false;
            return false;
        }
        plan = p;
        planStart = feet;
        moveIndex = 0;
        climbing = true;
        breaks.clear();
        setStage(Stage.CENTRE);
        LOG.info("Fall rescue: climb from {} (feet at {}; {}), plan {}: {} block(s){}: {}", feet.key(),
            String.format(java.util.Locale.ROOT, "%.3f", BOT.getY()), why, plans, p.blocks(),
            p.blocks() > 0 ? " of " + blockId + " (" + Inv.count(Inv.is(blockId)) + " carried)" : "", p);
        return true;
    }

    /** The block to climb with ({@link FallClimbBlocks#choose}); false when the bot carries none. */
    private boolean chooseBlock() {
        if (blockId != null && Inv.count(Inv.is(blockId)) > 0) return true;
        blockId = FallClimbBlocks.choose(carried(), cfg.build.fallRescueBlocks, cfg.build.fallRescueAnyBlock,
            FallClimbBlocks::suitable);
        return blockId != null;
    }

    /** The inventory's items (main and hotbar): id → count. */
    private static Map<String, Integer> carried() {
        Map<String, Integer> carried = new HashMap<>();
        for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) {
            ItemStack s = Inv.stack(i);
            if (Inv.empty(s)) continue;
            carried.merge(Inv.id(s), s.getAmount(), Integer::sum);
        }
        return carried;
    }

    private void setStage(Stage s) {
        stage = s;
        stageSince = now;
    }

    private long inStage() {
        return now - stageSince;
    }

    /** The current move's stand: where the bot is before it. */
    private Pos stand() {
        return moveIndex == 0 ? planStart : plan.moves().get(moveIndex - 1).to();
    }

    private TaskStatus climb() {
        FallClimb.Plan p = plan;
        if (stage == Stage.DONE) return finishClimb();
        if (stage == Stage.BREAK) return breakStep();
        if (stage == Stage.SETTLE) {
            if (!settled() && inStage() < SETTLE_TICKS) {
                still();
                return TaskStatus.RUNNING;
            }
            if (!newPlan(lastProblem)) return TaskStatus.RUNNING;
            return TaskStatus.RUNNING;
        }
        FallClimb.Move m = p.moves().get(moveIndex);
        Pos stand = stand();
        Pos feet = Walker.feet();
        boolean atStand = feet.equals(stand);
        // a channel's current may carry the bot a little over the side of its stand before anything is placed: back
        boolean drifted = stage == Stage.CENTRE && beside(feet, stand) && inStage() <= MOVE_TICKS;
        // the fence: before the move leaves its stand the bot stays in it
        if (stage != Stage.HOP && stage != Stage.PILLAR && stage != Stage.EXIT && !(stage == Stage.CONFIRM
            && m.kind() == FallClimb.Kind.PILLAR) && !atStand && !drifted && settled()) {
            return slipped("left " + stand.key() + " for " + feet.key() + " in " + stage);
        }
        switch (stage) {
            case CENTRE -> {
                if (m.kind() == FallClimb.Kind.EXIT || m.kind() == FallClimb.Kind.WALK) {
                    setStage(Stage.EXIT);
                    return TaskStatus.RUNNING;
                }
                double tol = m.kind() == FallClimb.Kind.PILLAR ? PILLAR_CENTRE : STAIR_CENTRE;
                if (offCentre(stand) <= tol && settled()) {
                    clicker.submit(now, null, null, sneakInput(false));
                    setStage(Stage.HOLD);
                    return TaskStatus.RUNNING;
                }
                if (inStage() > MOVE_TICKS) return slipped("could not center on " + stand.key());
                centreOn(stand);
                return TaskStatus.RUNNING;
            }
            case HOLD -> {
                if (!chooseBlock()) return giveUpClimb("ran out of blocks to climb with");
                if (hotbar.ensureHeld(Inv.is(blockId), Hotbar.SPECIAL_SLOT, now)) {
                    tries = 0;
                    jumps = 0;
                    leftGround = false;
                    setStage(m.kind() == FallClimb.Kind.PILLAR ? Stage.PILLAR : Stage.AIM);
                    return TaskStatus.RUNNING;
                }
                if (inStage() > MOVE_TICKS) return giveUpClimb("could not take " + blockId + " in hand");
                clicker.submit(now, null, null, keepOn(stand, BOT.getYaw()));
                return TaskStatus.RUNNING;
            }
            case AIM -> {
                if (offCentre(stand) > STAIR_CENTRE + 0.1) {
                    setStage(Stage.CENTRE);
                    return TaskStatus.RUNNING;
                }
                Pos s = m.support();
                Aiming.Shot shot = Aiming.face(s.x(), s.y(), s.z(), Direction.values()[m.face()], reach());
                if (shot == null) {
                    banned.add(m.block());
                    return slipped("the " + FallClimb.FACE_NAMES[m.face()] + " of " + s.key() + " is not in sight");
                }
                clicker.submit(now, shot.yaw(), shot.pitch(), keepOn(stand, shot.yaw()));
                aimed = shot;
                setStage(Stage.CLICK);
                return TaskStatus.RUNNING;
            }
            case CLICK -> {
                Pos s = m.support();
                Aiming.Shot a = aimed;
                Aiming.Shot ok = a == null ? null
                    : Aiming.check(s.x(), s.y(), s.z(), Direction.values()[m.face()], BOT.getYaw(), BOT.getPitch(), reach());
                if (ok == null) {
                    setStage(Stage.AIM);
                    return TaskStatus.RUNNING;
                }
                clicker.submit(now, a.yaw(), a.pitch(), keepOn(stand, a.yaw()));
                if (!Inv.is(blockId).test(Inv.mainHand())) {
                    setStage(Stage.HOLD);
                    return TaskStatus.RUNNING;
                }
                if (clicker.canClick(now) && Clicker.sendUseItemOn(ok.ray())) {
                    tries++;
                    setStage(Stage.CONFIRM);
                    return TaskStatus.RUNNING;
                }
                if (inStage() > 10) setStage(Stage.AIM);
                return TaskStatus.RUNNING;
            }
            case CONFIRM -> {
                if (m.kind() == FallClimb.Kind.PILLAR) return pillarConfirm(m);
                clicker.submit(now, null, null, keepOn(stand, BOT.getYaw()));
                if (placedNow(m.block())) {
                    setStage(Stage.HOP);
                    return TaskStatus.RUNNING;
                }
                if (inStage() > CONFIRM_TICKS) {
                    if (tries < PLACE_TRIES) {
                        setStage(Stage.AIM);
                        return TaskStatus.RUNNING;
                    }
                    banned.add(m.block());
                    return slipped("no block at " + m.block().key() + " after " + tries + " clicks");
                }
                return TaskStatus.RUNNING;
            }
            case HOP -> {
                return hop(m, stand);
            }
            case PILLAR -> {
                return pillar(m);
            }
            case EXIT -> {
                return exitMove(m, stand);
            }
            default -> {
                return TaskStatus.RUNNING;
            }
        }
    }

    /** The block a click put into {@code cell} is in the world now: it is the climb's own. */
    private boolean placedNow(Pos cell) {
        var w = ctx.world();
        if (!w.isLoaded(cell)) return false;
        BlockSpec b = w.block(cell);
        if (b == null || b.isAir() || !BlockStates.blocksPlacement(w.stateId(cell))) return false;
        if (!own.containsKey(cell)) {
            own.put(cell, b.id());
            placed++;
            blocksUsed.merge(b.id(), 1, Integer::sum);
            LOG.info("Fall rescue: placed {} at {} ({} own block(s) in place)", b.id(), cell.key(), own.size());
        }
        return true;
    }

    /** STAIR: the jump from the stand onto the block just placed, then centred on it sneaking. */
    private TaskStatus hop(FallClimb.Move m, Pos stand) {
        Pos to = m.to();
        Pos feet = Walker.feet();
        if (feet.equals(to) && BOT.isOnGround()) {
            if (offCentre(to) <= 0.2) return moveDone(m);
            if (inStage() > MOVE_TICKS) return slipped("could not center on " + to.key());
            centreOn(to);
            return TaskStatus.RUNNING;
        }
        if (inStage() > MOVE_TICKS) return slipped("the jump onto " + m.block().key() + " did not land");
        if (!feet.equals(stand) && !feet.equals(to) && BOT.isOnGround() && inStage() > 3) {
            return slipped("the jump onto " + m.block().key() + " ended at " + feet.key());
        }
        boolean above = BOT.getY() >= m.feet() - 0.02;
        boolean over = offCentre(to) < 0.45;
        // forward and jump until over the block's top; then no keys but sneak (vanilla keeps a sneaking bot on its block)
        boolean push = !(above && over);
        clicker.submit(now, yawTo(to), 0f, Input.builder().pressingForward(push).jumping(push && !above)
            .sneaking(!push).build());
        return TaskStatus.RUNNING;
    }

    /** PILLAR: jump looking straight down; the click once the feet are above the cell. */
    private TaskStatus pillar(FallClimb.Move m) {
        Pos cell = m.block();
        Pos s = m.support();
        if (placedNow(cell)) {
            setStage(Stage.CONFIRM);
            return TaskStatus.RUNNING;
        }
        if (!BOT.isOnGround()) {
            if (!leftGround) jumpTop = BOT.getY();
            leftGround = true;
            jumpTop = Math.max(jumpTop, BOT.getY());
        }
        if (leftGround && BOT.isOnGround() && BOT.getY() < cell.y() + 0.5) {
            // landed in the cell again without the block: another jump, or give the cell up
            leftGround = false;
            LOG.info("Fall rescue: pillar jump {} at {}: the feet rose to {} (the click wants {}){}", jumps + 1, cell.key(),
                String.format(java.util.Locale.ROOT, "%.3f", jumpTop), String.format(java.util.Locale.ROOT, "%.2f",
                    cell.y() + PILLAR_CLICK_ABOVE), BOT.isTouchingWater() ? ", in water" : "");
            if (++jumps >= PILLAR_JUMPS) {
                banned.add(cell);
                return slipped("the pillar jumps at " + cell.key() + " did not rise above it");
            }
        }
        if (offCentre(cell) > PILLAR_CENTRE + 0.15 && BOT.isOnGround()) {
            setStage(Stage.CENTRE);
            return TaskStatus.RUNNING;
        }
        float yaw = BOT.getYaw();
        if (BOT.getY() >= cell.y() + PILLAR_CLICK_ABOVE && BOT.getPitch() > 85f) {
            Aiming.Shot ok = Aiming.check(s.x(), s.y(), s.z(), Direction.UP, BOT.getYaw(), BOT.getPitch(), reach());
            clicker.submit(now, yaw, 90f, keepOn(cell, yaw, true));
            if (ok != null && clicker.canClick(now) && Inv.is(blockId).test(Inv.mainHand()) && Clicker.sendUseItemOn(ok.ray())) {
                tries++;
                setStage(Stage.CONFIRM);
            }
            return TaskStatus.RUNNING;
        }
        if (inStage() > MOVE_TICKS * 2) return slipped("the pillar at " + cell.key() + " took over " + MOVE_TICKS * 2 + " ticks");
        clicker.submit(now, yaw, 90f, keepOn(cell, yaw, true));
        return TaskStatus.RUNNING;
    }

    /** PILLAR after the click: the block in the world and the bot landed on it. */
    private TaskStatus pillarConfirm(FallClimb.Move m) {
        Pos cell = m.block();
        boolean there = placedNow(cell);
        Pos feet = Walker.feet();
        if (there && BOT.isOnGround() && feet.equals(m.to())) {
            clicker.submit(now, null, null, sneakInput(true));
            return moveDone(m);
        }
        if (!there && inStage() > CONFIRM_TICKS) {
            if (tries < PLACE_TRIES) {
                leftGround = false;
                setStage(Stage.PILLAR);
                return TaskStatus.RUNNING;
            }
            banned.add(cell);
            return slipped("no block at " + cell.key() + " after " + tries + " clicks");
        }
        if (inStage() > MOVE_TICKS) return slipped("the pillar at " + cell.key() + " did not land on its block");
        // jumping on until the server's block shows (no local prediction for a raw click): the bot is not back in the
        // cell when it arrives; then down onto it, sneaking
        clicker.submit(now, null, 90f, keepOn(m.to(), BOT.getYaw(), !there));
        return TaskStatus.RUNNING;
    }

    /** EXIT (and WALK): to the move's cell with the own keys, forward (and jump when the plan says). */
    private TaskStatus exitMove(FallClimb.Move m, Pos stand) {
        Pos to = m.to();
        Pos feet = Walker.feet();
        boolean arrived = feet.x() == to.x() && feet.z() == to.z() && feet.y() >= to.y() && BOT.isOnGround();
        if (arrived) {
            if (m.kind() == FallClimb.Kind.WALK && offCentre(to) > 0.25 && inStage() <= MOVE_TICKS) {
                centreOn(to);
                return TaskStatus.RUNNING;
            }
            clicker.submit(now, null, null, sneakInput(true));
            return moveDone(m);
        }
        boolean walk = m.kind() == FallClimb.Kind.WALK;
        // a walk along the bottom may go against the current: slower, and it may be carried back over the stand's side
        int budget = walk ? MOVE_TICKS * 2 : MOVE_TICKS;
        if (inStage() > budget) return slipped("the " + m.kind() + " to " + to.key() + " took over " + budget + " ticks");
        if (!feet.equals(stand) && !feet.equals(to) && !(walk && beside(feet, stand)) && BOT.isOnGround() && inStage() > 3) {
            return slipped("the " + m.kind() + " to " + to.key() + " ended at " + feet.key());
        }
        if (walk) {
            // along the bottom: the keys toward the cell, no turn first (the current would carry the bot off meanwhile)
            clicker.submit(now, null, null, keepOn(to, BOT.getYaw()));
            return TaskStatus.RUNNING;
        }
        boolean above = BOT.getY() >= m.feet() - 0.02;
        clicker.submit(now, yawTo(to), 0f, Input.builder().pressingForward(true)
            .jumping(m.jump() && !above).build());
        return TaskStatus.RUNNING;
    }

    /** A move ended where it should: its breaks, then the next move (or the end of the climb). */
    private TaskStatus moveDone(FallClimb.Move m) {
        breaks.clear();
        breaks.addAll(m.breaks());
        if (moveIndex == plan.moves().size() - 1) {
            // from the exit: whatever of its own the bot can still see goes too, the highest first
            finalSweep = true;
            List<Pos> rest = new ArrayList<>(own.keySet());
            rest.sort((a, b) -> Integer.compare(b.y(), a.y()));
            for (Pos r : rest) if (!breaks.contains(r)) breaks.add(r);
        }
        setStage(Stage.BREAK);
        return TaskStatus.RUNNING;
    }

    /** Breaks the queued own blocks one by one; then on to the next move. */
    private TaskStatus breakStep() {
        while (!breaks.isEmpty()) {
            Pos b = breaks.peekFirst();
            if (!own.containsKey(b)) {
                breaks.pollFirst();
                continue;
            }
            Boolean done = breakOne(b);
            if (done == null) return TaskStatus.RUNNING;
            breaks.pollFirst();
            breaking = null;
            if (!done) unbroken.add(b);
        }
        if (finalSweep) {
            setStage(Stage.DONE);
            return TaskStatus.RUNNING;
        }
        moveIndex++;
        setStage(Stage.CENTRE);
        clicker.submit(now, null, null, sneakInput(true));
        return TaskStatus.RUNNING;
    }

    /**
     * One own block: null while it is being broken; true once it is gone; false when the bot gives it up (out of sight
     * or reach from here, no tool above the floor and too slow by hand, or not broken within its budget).
     */
    private @Nullable Boolean breakOne(Pos b) {
        var world = ctx.world();
        BlockSpec spec = world.block(b);
        String mine = own.get(b);
        if (spec == null || spec.isAir() || !BlockStates.blocksPlacement(world.stateId(b))) {
            if (!b.equals(goneAt)) {
                goneAt = b;
                goneSince = now;
            }
            if (now - goneSince < BREAK_CONFIRM_TICKS) {
                // the server may still refuse the break and send the block back
                holdStill();
                return null;
            }
            goneAt = null;
            own.remove(b);
            unbroken.remove(b);
            LOG.info("Fall rescue: own block at {} broken ({} left)", b.key(), own.size());
            return true;
        }
        if (b.equals(goneAt)) {
            goneAt = null;
            breaking = null;
            int n = comebacks.merge(b, 1, Integer::sum);
            LOG.info("Fall rescue: own block at {} came back (the server kept it){}", b.key(),
                n > MAX_COMEBACKS ? "; left" : "; breaking it again");
            if (n > MAX_COMEBACKS) return false;
        }
        if (mine == null || !ItemIds.material(spec.id()).equals(ItemIds.material(mine))) {
            // not the block the climb placed any more: never touched
            LOG.warn("Fall rescue: {} at {} is not the climb's own {} any more; left alone", spec.id(), b.key(), mine);
            own.remove(b);
            return false;
        }
        String refusal = protectedRegionRefusal(b, "break");
        if (refusal != null) {
            LOG.warn("Fall rescue: {}", refusal);
            return false;
        }
        Block block = BLOCK_DATA.getBlockDataFromBlockStateId(world.stateId(b));
        if (block == null || block.destroySpeed() < 0) return false;
        Aiming.Shot shot = Aiming.nearestFace(b.x(), b.y(), b.z(), reach());
        if (shot == null) shot = Aiming.anyFace(b.x(), b.y(), b.z(), reach());
        if (shot == null) {
            LOG.info("Fall rescue: own block at {} not in sight from {}", b.key(), Walker.feet().key());
            return false;
        }
        int floor = Math.max(0, cfg.build.toolSafetyFloor);
        ToolChoice.Choice choice = BreakBlocksTask.chooseTool(block, floor);
        boolean hand = choice.kind() != ToolChoice.Kind.TOOL;
        if (!hand) {
            if (!hotbar.ensureSlotHeld(choice.tool().slot(), Hotbar.TOOL_SLOT, now)) {
                clicker.submit(now, shot.yaw(), shot.pitch(), null);
                return null;
            }
            ItemStack held = Inv.mainHand();
            // defensive: never on with a tool at the floor (it may have worn during this block)
            if (Wear.damageable(held) && Wear.remaining(held) <= floor) hand = true;
        }
        if (hand) {
            double speed = BOT.getInteractions().blockBreakSpeed(block, com.zenith.cache.data.inventory.Container.EMPTY_STACK);
            if (speed <= 0 || Math.ceil(1.0 / speed) > HAND_BREAK_MAX_TICKS) {
                LOG.warn("Fall rescue: own {} at {} left: {}", spec.id(), b.key(), choice.kind() == ToolChoice.Kind.WORN
                    ? "every tool for it is at the safety floor (" + choice.wornReason(floor) + ") and by hand it takes over "
                    + HAND_BREAK_MAX_TICKS / 20 + " s" : "no tool and too slow by hand");
                return false;
            }
            int bare = Hotbar.bareHandIndex();
            if (bare < 0) {
                LOG.warn("Fall rescue: own {} at {} left: every hotbar slot holds a tool or weapon", spec.id(), b.key());
                return false;
            }
            if (bare != Inv.heldSlot()) {
                clicker.submit(now, shot.yaw(), shot.pitch(), null);
                hotbar.ensureSlotHeld(ItemIds.HOTBAR_START + bare, Hotbar.TOOL_SLOT, now);
                return null;
            }
        }
        if (!b.equals(breaking)) {
            breaking = b;
            breakSince = now;
            double speed = Math.max(1e-4, BOT.getInteractions().blockBreakSpeed(block, Inv.mainHand()));
            breakBudget = Math.min(1200, (long) Math.ceil(1.0 / speed) * 2 + 60);
        }
        if (now - breakSince > breakBudget) {
            LOG.warn("Fall rescue: own {} at {} not broken within {} ticks", spec.id(), b.key(), breakBudget);
            return false;
        }
        clicker.submitMine(now, shot.yaw(), shot.pitch(), new BreakBlocksTask.TargetBlock(b.x(), b.y(), b.z(), reach()));
        return null;
    }

    /** After the exit's breaks: out, and what is left of the climb's blocks told. */
    private TaskStatus finishClimb() {
        Pos feet = Walker.feet();
        // on a dry cell at the floor's level, maybe with the box's side still in the channel's water: off the edge
        boolean dryCell = BOT.isOnGround() && (request.floorY() == FallRescue.ANY_LEVEL || feet.y() >= request.floorY())
            && !cells.fluid(new Pos(feet.x(), (int) Math.floor(BOT.getY() + 0.2), feet.z()));
        if (dryCell && (offCentre(feet) > EXIT_CENTRE || !out()) && inStage() <= MOVE_TICKS) {
            centreOn(feet);
            return TaskStatus.RUNNING;
        }
        if (!out()) {
            climbing = true;
            return slipped("not on dry ground at the floor's level after the exit (" + feet.key() + ")");
        }
        walker.stop();
        LOG.info("Fall rescue: climbed out to {} after {} ticks: {} block(s) placed ({}), {} plan(s){}", Walker.feet().key(),
            now, placed, blocksUsed, plans, own.isEmpty() ? ", every one broken again" : "; " + own.size() + " left");
        reportLeft();
        return TaskStatus.SUCCESS;
    }

    /** The climb's blocks still in the world: logged and told to the engine. */
    private void reportLeft() {
        if (own.isEmpty()) return;
        List<String> out = new ArrayList<>();
        own.forEach((p, id) -> out.add(p.key() + " " + id));
        String text = String.join("; ", out);
        LOG.warn("Fall rescue: left {} block(s) of its own it could not break again: {}", own.size(), text);
        request.leftBlocks().accept(text);
    }

    /** A step went wrong: plan again from where the bot is, once it has come to rest. */
    private TaskStatus slipped(String why) {
        lastProblem = why;
        LOG.info("Fall rescue: {}; planning again", why);
        aimed = null;
        breaking = null;
        finalSweep = false;
        setStage(Stage.SETTLE);
        holdStill();
        return TaskStatus.RUNNING;
    }

    /** The climb cannot go on: the swim-and-step exits follow. */
    private TaskStatus giveUpClimb(String why) {
        lastProblem = why;
        LOG.warn("Fall rescue: the climb stops: {}", why);
        climbing = false;
        holdStill();
        return TaskStatus.RUNNING;
    }

    /**
     * Own keys toward the centre of {@code c}'s column, sneaking on a block (vanilla keeps the bot on it). The rotation
     * stays: the keys nearest to the way ({@link #keepOn}), so no turn first lets the current carry the bot off.
     */
    private void centreOn(Pos c) {
        clicker.submit(now, null, null, keepOn(c, BOT.getYaw()));
    }

    /**
     * Keys that hold the bot on the centre of {@code c}'s column against a channel's current while it aims and clicks
     * (the rotation stays the aim's, {@code yaw}): the movement keys whose direction relative to the yaw is nearest to
     * the way back, none within {@link #KEEP_ON} of the centre. Sneaking on a block.
     */
    private static Input keepOn(Pos c, float yaw) {
        return keepOn(c, yaw, false);
    }

    /** {@link #keepOn(Pos, float)}, jumping too when {@code jump} (a pillar's jumps). */
    private static Input keepOn(Pos c, float yaw, boolean jump) {
        boolean sneak = BOT.isOnGround() && !BOT.isTouchingWater();
        double dx = c.x() + 0.5 - BOT.getX();
        double dz = c.z() + 0.5 - BOT.getZ();
        double d = Math.hypot(dx, dz);
        if (d < KEEP_ON) return Input.builder().sneaking(sneak).jumping(jump).build();
        int[] keys = Aim.movementKeys(yaw, dx, dz);
        return Input.builder().pressingForward(keys[0] > 0).pressingBack(keys[0] < 0).pressingLeft(keys[1] > 0)
            .pressingRight(keys[1] < 0).sneaking(sneak).jumping(jump).build();
    }

    /** No keys; in water the keys that hold the bot in its cell against the current ({@link #keepOn}). */
    private void still() {
        if (BOT.isTouchingWater()) clicker.submit(now, null, null, keepOn(Walker.feet(), BOT.getYaw()));
        else holdStill();
    }

    private static Input sneakInput(boolean sneak) {
        return Input.builder().sneaking(sneak).build();
    }

    /** {@code a} is a side neighbour of {@code b} at the same level. */
    private static boolean beside(Pos a, Pos b) {
        return a.y() == b.y() && Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) == 1;
    }

    /** Horizontal distance of the bot from the centre of {@code c}'s column. */
    private static double offCentre(Pos c) {
        return Math.hypot(BOT.getX() - (c.x() + 0.5), BOT.getZ() - (c.z() + 0.5));
    }

    private static float yawTo(Pos c) {
        return (float) Math.IEEEremainder(
            Math.toDegrees(Math.atan2(c.z() + 0.5 - BOT.getZ(), c.x() + 0.5 - BOT.getX())) - 90, 360);
    }
}
