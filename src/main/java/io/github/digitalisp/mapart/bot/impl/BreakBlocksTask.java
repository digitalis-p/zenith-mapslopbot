package io.github.digitalisp.mapart.bot.impl;

import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.player.ClickTarget;
import com.zenith.feature.player.raycast.BlockOrEntityRaycastResult;
import com.zenith.feature.player.raycast.RaycastHelper;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.Direction;
import com.zenith.network.client.ClientSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.BotActions;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.config.SiteRules;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static com.zenith.Globals.BLOCK_DATA;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;

/**
 * Breaks blocks one at a time, nearest first: walk until a face is visible within reach, put the right item in the
 * main hand ({@link ToolChoice}: an empty hand or a non-tool item for blocks that break near-instantly by hand such as
 * carpets, else the fastest tool above {@code build.toolSafetyFloor}), then hold left click on it through
 * ZenithProxy's Bot, which sends start/continue/stop destroy with vanilla timing (its 5-tick delay between blocks
 * avoids Grim FastBreak). The click target validates the real raycast hits the target block. A target whose id is
 * protected ({@link SiteRules#protectedIds}) or whose position lies in a protected region
 * ({@code site.protectedRegions}) is never touched; it and any target that could not be
 * reached/broken are listed in the failure reason after the other targets are done. When every tool that would matter
 * for the next block is at the safety floor the task stops at once and fails with {@link BotActions#TOOL_AT_FLOOR}:
 * tools never break.
 *
 * <p>Tools: before the first break, standing still, every tool the targets need is moved into its own hotbar slot
 * ({@link ToolRack#arrange}, one inventory click per tool at most); switching tools afterwards is a hotbar select.
 * Among the targets in reach, those that want what is in the hand go first ({@link ToolRack#next}).
 *
 * <p>Parks (the engine eats or stands still) between two blocks, never in the middle of one.
 *
 * <p>{@code build.breakMode} FAST (default) runs the targets through {@link NukeRun}: up to
 * {@code build.nukeStartsPerTick} instant STARTs per tick, the vanilla timing for other blocks, and a lane sweep that
 * breaks while walking with the bot's own keys ({@link ZenithNukeIo}).
 *
 * <p>{@code build.breakMode} DOUBLE runs the targets through {@link BreakRun} instead: the 2b2t double break
 * (two same-tool blocks at once, one of them left to the server's delayed destroy), with the dig packets sent by this
 * task ({@link ZenithBreakIo}) and ZenithProxy's Bot never mining meanwhile. Walking, the tool choice, the tool rack,
 * the protection rules and parking are the same; it parks only between rounds with no delayed block pending.
 * SINGLE keeps ZenithProxy's left click as described above.
 */
final class BreakBlocksTask extends ZenithTask {
    private static final int MAX_ATTEMPTS = 2;

    private final List<Pos> remaining;
    private final Consumer<Pos> broken;
    private final Set<String> protectedIds;
    private final List<String> refused = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();
    private @Nullable Pos current;
    private long currentSince;
    private long budget;
    private int attempts;
    private int done;
    private final int total;
    /** The tools the targets need are in their hotbar slots (or nothing had to move). */
    private boolean arranged;
    /** Block state id → {@link ToolRack#next} group: a tool kind, {@link ToolRack#HAND}, or "" (neither). */
    private final Map<Integer, String> groupByState = new HashMap<>();
    private boolean parkRequested;
    private boolean parked;
    /** DOUBLE mode's run, null in SINGLE mode. */
    private final @Nullable BreakRun<Aiming.Shot> run;
    private final @Nullable ZenithBreakIo io;
    /** DOUBLE, FAST: the tool rack's moves are done and the run has started. */
    private boolean runStarted;
    /** FAST mode's run, null otherwise. */
    private final @Nullable NukeRun<Aiming.Shot> nuke;
    /**
     * SINGLE, DOUBLE: the watchdog ({@code build.breakWatchdogSeconds}; FAST has its own in {@link NukeRun}): the tick of
     * the last block broken or of the bot moving a block from ({@link #wdX}, {@link #wdZ}), the blocks broken then, trips.
     */
    private long wdTick;
    private double wdX = Double.NaN;
    private double wdZ;
    private int wdDone = -1;
    private int wdTrips;

    /**
     * A protected block type ({@link SiteRules#protectedIds}) at {@code p}, unless a colour-suppression job may repair
     * it there ({@link SiteRules#repairable}: a wrong moss_block in its art cells).
     */
    private boolean isProtected(Pos p, BlockSpec spec) {
        return SiteRules.protectedAt(cfg.site, protectedIds, p, spec.id());
    }

    BreakBlocksTask(ZenithBotActions ctx, List<Pos> targets, Consumer<Pos> broken) {
        this(ctx, targets, broken, null, false);
    }

    /**
     * {@code pause}: FAST mode asks it at each lane end of the sweep and stops there with
     * {@link BotActions#BREAK_PAUSED} when it gives a reason ({@link NukeRun}); SINGLE and DOUBLE ignore it.
     */
    BreakBlocksTask(ZenithBotActions ctx, List<Pos> targets, Consumer<Pos> broken,
                    java.util.function.@Nullable Supplier<@Nullable String> pause) {
        this(ctx, targets, broken, pause, pause != null);
    }

    /**
     * {@code artCleared}: the art layer is cleared afterwards (the NUKE): blocks that are no target may be broken where
     * they wall the bot in or off ({@link NukeRun}, "Pockets"); otherwise they are art to re-place, broken only where
     * the bot must get through.
     */
    BreakBlocksTask(ZenithBotActions ctx, List<Pos> targets, Consumer<Pos> broken,
                    java.util.function.@Nullable Supplier<@Nullable String> pause, boolean artCleared) {
        super(ctx, 1200L + targets.size() * 400L);
        this.remaining = new ArrayList<>(new LinkedHashSet<>(targets));
        // a target in a protected region is never walked to, aimed at or touched, whatever the block
        remaining.removeIf(p -> {
            MapArtConfig.RegionCfg r = SiteRules.protectedRegionAt(cfg.site, p);
            if (r != null) refused.add("block at " + p + " in protected region " + SiteRules.label(cfg.site.protectedRegions, r));
            return r != null;
        });
        this.total = remaining.size();
        this.broken = broken;
        this.protectedIds = ctx.protectedIds();
        BreakMode mode = BreakMode.of(cfg.build);
        if (mode == BreakMode.FAST) {
            ZenithNukeIo nio = new ZenithNukeIo();
            this.io = nio;
            this.run = null;
            var b = cfg.build;
            double nukeReach = b.nukeReach > 0 ? b.nukeReach : reach();
            double[] end = artCleared ? nukeEnd(cfg.site, remaining) : new double[]{Double.NaN, Double.NaN};
            this.nuke = new NukeRun<>(new ArrayList<>(remaining), new NukeRun.Params(b.nukeStartsPerTick,
                b.nukeMaxDigsPerSecond, nukeReach, Math.max(0, b.toolSafetyFloor), b.nukeStopDelayTicks, b.nukeSweep,
                b.nukeLaneHalfWidth, b.nukeSweepMinTargets, NukeSweep.Axis.of(b.nukeLaneAxis), b.nukeSwingEachStart,
                hasteCovered(cfg.site.hasteZones), watchdogTicks(b.breakWatchdogSeconds), b.nukePairSlow,
                b.nukePocketGuard, artCleared, b.nukeSweepBreaksWay, b.nukeMineMoving, end[0], end[1],
                b.nukeTour && artCleared, b.nukeTourMaxDensity, b.nukeLeftoverRoute,
                b.nukeInstantAckTicks, b.nukeHoldAtSpeed, new NukeRun.Lambda(b.nukeLambda, b.nukeLambdaStartsPerTick,
                b.nukeLambdaFakeStarts, b.nukeLambdaBurst, b.nukeLambdaDoubleBreak)), nio, broken, pause);
            // Baritone never leaves the bot in a pocket walled in by blocks that are no target (build.nukePocketGuard)
            walker.pathCheck(nuke::pathCheck);
            nio.reach = nukeReach;
            nio.hasteMargin = Math.max(0, b.nukeHasteMarginTicks);
            nuke.serverClock(1.0 / Math.max(1.0, CONFIG.client.tickRate));
            PendingDelayed.Handoff h = PendingDelayed.SESSION.take(System.currentTimeMillis());
            if (h != null) {
                long left = h.untilMillis() - System.currentTimeMillis();
                if (left > 0) nuke.lingering(h.pos(), (long) Math.ceil(left / clientTickMillis()));
            }
        } else if (mode == BreakMode.DOUBLE) {
            this.nuke = null;
            this.io = new ZenithBreakIo();
            // START, STOP for the delayed block, no ABORT between: upstream Grim no longer forgets its FastBreak target
            // on ABORT, and its PositionBreakB flags an ABORT on a face other than DOWN
            this.run = new BreakRun<>(new ArrayList<>(remaining), true, reach(),
                Math.max(0, cfg.build.toolSafetyFloor), false, io, broken);
            // Paper's break progress follows the wall clock: with ZenithProxy's client ticking faster (the local speed
            // mode, client.tickRate 10) it advances 1/tickRate per client tick
            run.serverClock(1.0 / Math.max(1.0, CONFIG.client.tickRate));
            PendingDelayed.Handoff h = PendingDelayed.SESSION.take(System.currentTimeMillis());
            if (h != null) {
                long left = h.untilMillis() - System.currentTimeMillis();
                if (left > 0) run.lingering(h.pos(), (long) Math.ceil(left / clientTickMillis()));
            }
        } else {
            this.io = null;
            this.run = null;
            this.nuke = null;
        }
    }

    @Override
    public String name() {
        return "breakBlocks x" + total;
    }

    @Override
    protected String progress() {
        return " (" + (nuke != null ? nuke.done() : run != null ? run.done() : done) + "/" + total + " broken)";
    }

    @Override
    public boolean requestPark() {
        if (!parked) parkRequested = true;
        return true;
    }

    @Override
    public boolean parked() {
        return parked;
    }

    @Override
    public void resume() {
        parked = false;
        parkRequested = false;
        clicker.reset();
        if (run != null) run.resume();
        if (nuke != null) nuke.resume();
    }

    @Override
    protected void onRelease() {
        if (nuke != null) {
            nuke.release();
            var d = nuke.pendingDelayed(now);
            if (d != null) {
                // a slow block in the server's delayed slot (build.nukePairSlow): as for the double break below
                PendingDelayed.SESSION.set(d.getKey(),
                    System.currentTimeMillis() + (long) (d.getValue() * clientTickMillis()));
                LOG.info("Break task ended with the delayed block at {} still on the server: it breaks within {} ticks"
                    + " with the item held then", d.getKey(), d.getValue());
            }
            return;
        }
        if (run == null) return;
        run.release(); // ABORT a block being mined, as the vanilla client does on release
        var d = run.pendingDelayed(now);
        if (d != null) {
            // cancelled (setback escape, stop) with a delayed block on the server: nothing can take it back, the server
            // breaks it with the item held then; the next break task waits for it before it pairs or switches
            PendingDelayed.SESSION.set(d.getKey(),
                System.currentTimeMillis() + (long) (d.getValue() * clientTickMillis()));
            LOG.info("Break task ended with the delayed block at {} still on the server: it breaks within {} ticks with"
                + " the item held then", d.getKey(), d.getValue());
        }
    }

    /** {@code build.breakWatchdogSeconds} in client ticks (ZenithProxy's client.tickRate: 10 in the speed mode). */
    static long watchdogTicks(int seconds) {
        if (seconds <= 0) return 0;
        return (long) Math.ceil(seconds * 20.0 * Math.max(1.0, CONFIG.client.tickRate));
    }

    /**
     * Where the NUKE's sweep ends near ({@code site.platforms[].nukeEndCorner}): that corner (feet x, z) of the 128 ×
     * 128 map of the platform holding the first target; NaN, NaN when it has none (or no platform holds it).
     */
    static double[] nukeEnd(MapArtConfig.SiteCfg site, List<Pos> targets) {
        double[] none = {Double.NaN, Double.NaN};
        if (targets.isEmpty() || site.platforms == null) return none;
        Pos t = targets.getFirst();
        for (MapArtConfig.PlatformCfg pc : site.platforms) {
            if (pc == null || pc.isColorSuppression() || t.y() != pc.artY) continue;
            if (t.x() < pc.minX || t.x() >= pc.minX + 128 || t.z() < pc.minZ || t.z() >= pc.minZ + 128) continue;
            double[] c = NukeSweep.corner(pc.nukeEndCorner, pc.minX, pc.minZ, 128);
            return c != null ? c : none;
        }
        return none;
    }

    /** {@code site.hasteZones} as a test of the feet cell; null when none are configured. */
    static java.util.function.@Nullable Predicate<Pos> hasteCovered(@Nullable List<MapArtConfig.RegionCfg> zones) {
        if (zones == null || zones.isEmpty()) return null;
        List<MapArtConfig.RegionCfg> z = List.copyOf(zones);
        return p -> {
            for (MapArtConfig.RegionCfg r : z) if (r != null && r.contains(p)) return true;
            return false;
        };
    }

    /** Real milliseconds per client tick (ZenithProxy's client.tickRate: 10 in the local speed mode). */
    private static double clientTickMillis() {
        return 50.0 / Math.max(1.0, CONFIG.client.tickRate);
    }

    /** Between two blocks: stop walking, hold still, park once no hotbar request is in flight and on the ground. */
    private TaskStatus park() {
        walker.stop();
        holdStill();
        if (!parked && !hotbar.busy(now) && BOT.isOnGround()) {
            parked = true;
            parkRequested = false;
            current = null; // picked again from wherever the bot is on resume
            budget = 0;
        }
        return TaskStatus.RUNNING;
    }

    /** Its walks go between break positions; nothing is broken while Baritone walks (the nuke's sweep uses its own keys). */
    @Override
    protected SprintLegs.Walk walkKind() {
        return SprintLegs.Walk.BREAK_TRANSIT;
    }

    @Override
    protected TaskStatus step() {
        if (nuke != null) return stepFast(nuke);
        TaskStatus wd = watchdog();
        if (wd != null) return wd;
        if (run != null) return stepDouble(run);
        if (parked || (parkRequested && budget == 0)) return park(); // budget > 0: mining a block, finish it first
        if (!arranged && !arrangeTools()) return TaskStatus.RUNNING;
        if (current == null) {
            current = nextTarget();
            if (current == null) return finish();
            currentSince = now;
            attempts = 0;
            budget = 0;
        }
        Pos pos = current;
        var world = ctx.world();
        if (!world.isLoaded(pos)) {
            TaskStatus s = walkNear(pos, 3, 4);
            if (s == TaskStatus.FAILED) giveUp("cannot walk to " + pos + ": " + walker.error());
            return TaskStatus.RUNNING;
        }
        BlockSpec spec = world.block(pos);
        if (spec.isAir()) {
            next(true);
            return TaskStatus.RUNNING;
        }
        if (spec.id().equals("minecraft:water") || spec.id().equals("minecraft:lava")) {
            giveUp(spec.id() + " at " + pos + " (fluids cannot be broken)");
            return TaskStatus.RUNNING;
        }
        if (isProtected(pos, spec)) {
            refused.add(spec.id() + " at " + pos);
            next(false);
            return TaskStatus.RUNNING;
        }
        Block block = BLOCK_DATA.getBlockDataFromBlockStateId(world.stateId(pos));
        if (block == null || block.destroySpeed() < 0) {
            refused.add("unbreakable " + spec.id() + " at " + pos);
            next(false);
            return TaskStatus.RUNNING;
        }
        Aiming.Shot shot = Aiming.anyFace(pos.x(), pos.y(), pos.z(), reach());
        if (shot == null) {
            TaskStatus s = walkUntil(approachGoals(pos), () -> Aiming.anyFace(pos.x(), pos.y(), pos.z(), reach()) != null);
            if (s == TaskStatus.FAILED) giveUp("cannot reach " + spec.id() + " at " + pos + ": " + walker.error());
            return TaskStatus.RUNNING;
        }
        walker.stop();

        int floor = Math.max(0, cfg.build.toolSafetyFloor);
        ToolChoice.Choice choice = chooseTool(block, floor);
        switch (choice.kind()) {
            case WORN -> {
                // never break with a tool at the floor: stop now, the engine mends or swaps it
                return fail(BotActions.TOOL_AT_FLOOR + ": " + choice.wornReason(floor) + " (" + done + "/" + total
                    + " broken; next " + spec.id() + " at " + pos + ")");
            }
            case HAND -> {
                int bare = Hotbar.bareHandIndex();
                if (bare < 0) return fail("every hotbar slot holds a tool or weapon: no free hand to break " + spec.id());
                if (bare != Inv.heldSlot()) {
                    clicker.submit(now, shot.yaw(), shot.pitch(), null);
                    hotbar.ensureSlotHeld(ItemIds.HOTBAR_START + bare, Hotbar.TOOL_SLOT, now);
                    return TaskStatus.RUNNING;
                }
            }
            case TOOL -> {
                if (!hotbar.ensureSlotHeld(choice.tool().slot(), rackTarget(), now)) {
                    clicker.submit(now, shot.yaw(), shot.pitch(), null);
                    return TaskStatus.RUNNING;
                }
            }
        }
        ItemStack held = Inv.mainHand();
        if (Wear.damageable(held) && Wear.remaining(held) <= floor) {
            // defensive: the cache shows the held item at the floor (e.g. it wore during the last block)
            clicker.submit(now, shot.yaw(), shot.pitch(), null);
            return TaskStatus.RUNNING;
        }
        if (budget == 0) {
            double speed = Math.max(1e-4, BOT.getInteractions().blockBreakSpeed(block, Inv.mainHand()));
            budget = Math.min(1200, (long) Math.ceil(1.0 / speed) * 2 + 60);
            currentSince = now;
        }
        if (now - currentSince > budget) {
            if (++attempts >= MAX_ATTEMPTS) {
                giveUp("could not break " + spec.id() + " at " + pos);
                return TaskStatus.RUNNING;
            }
            currentSince = now;
        }
        clicker.submitMine(now, shot.yaw(), shot.pitch(), new TargetBlock(pos.x(), pos.y(), pos.z(), reach()));
        return TaskStatus.RUNNING;
    }

    /** DOUBLE mode: the tool rack first, then {@link BreakRun} rounds; parks only where the run allows it. */
    private TaskStatus stepDouble(BreakRun<Aiming.Shot> r) {
        if (parked || (parkRequested && r.canPark())) return park();
        if (!arranged && !arrangeTools()) return TaskStatus.RUNNING;
        if (!runStarted) {
            // the tool rack's clicks go one by one through ZenithProxy's InventoryManager: none may land in the main
            // hand once a delayed block is on the server, and none while walking (Grim MultiActionsC)
            if (hotbar.busy(now)) {
                holdStill();
                return TaskStatus.RUNNING;
            }
            runStarted = true;
        }
        r.holdForPark(parkRequested);
        TaskStatus s = r.tick(now);
        ZenithBreakIo z = io;
        if (z != null && z.fatal != null) return fail(z.fatal);
        if (s == TaskStatus.RUNNING) return s;
        List<String> problems = new ArrayList<>();
        if (!refused.isEmpty()) problems.add("refused (protected): " + summary(refused));
        if (s == TaskStatus.FAILED) problems.add(r.failure());
        if (problems.isEmpty()) return TaskStatus.SUCCESS;
        return fail(String.join("; ", problems));
    }

    /**
     * SINGLE and DOUBLE: no block broken and the bot not a block from where it was for {@code build.breakWatchdogSeconds}
     * (parked: the engine's choice, not counted): one log line, and the target the task is stuck on is left for later
     * (listed in the failure, so the engine runs the step again with the rest). After 4 trips the task fails at once.
     * Null while all is well.
     */
    private @Nullable TaskStatus watchdog() {
        long limit = watchdogTicks(cfg.build.breakWatchdogSeconds);
        int d = run != null ? run.done() : done;
        double x = BOT.getX();
        double z = BOT.getZ();
        if (parked || d != wdDone || Double.isNaN(wdX) || Math.hypot(x - wdX, z - wdZ) >= 1.0) {
            wdDone = d;
            wdX = x;
            wdZ = z;
            wdTick = now;
            return null;
        }
        if (limit <= 0 || now - wdTick < limit) return null;
        wdTrips++;
        String head = String.format(java.util.Locale.ROOT, "Break watchdog (%s): no block broken and no movement for %d"
            + " ticks at %.1f,%.1f", run != null ? "DOUBLE" : "SINGLE", now - wdTick, x, z);
        wdTick = now;
        if (wdTrips >= 4) {
            LOG.info("{}; stopping this round after {} trips", head, wdTrips);
            walker.stop();
            return fail("no progress (break watchdog tripped " + wdTrips + " times; " + d + "/" + total + " broken)");
        }
        Pos stuck;
        if (run != null) {
            stuck = run.giveUpStuck("break watchdog");
        } else {
            stuck = current;
            if (stuck != null) {
                var w = ctx.world();
                giveUp("could not break " + (w.isLoaded(stuck) ? w.block(stuck).id() : "block") + " at " + stuck
                    + " (break watchdog)");
            }
        }
        LOG.info("{}; {}", head, stuck != null ? "leaving " + stuck + " for later" : "nothing to leave");
        return null;
    }

    /** FAST mode: the tool rack first, then {@link NukeRun} ticks; parks only where the run allows it. */
    private TaskStatus stepFast(NukeRun<Aiming.Shot> r) {
        if (parked || (parkRequested && r.canPark())) return park();
        if (!arranged && !arrangeTools()) return TaskStatus.RUNNING;
        if (!runStarted) {
            // the tool rack's clicks go one by one through ZenithProxy's InventoryManager: none while walking
            // (Grim MultiActionsC), none landing after a START
            if (hotbar.busy(now)) {
                holdStill();
                return TaskStatus.RUNNING;
            }
            runStarted = true;
        }
        r.holdForPark(parkRequested);
        TaskStatus s = r.tick(now);
        ZenithBreakIo z = io;
        if (z != null && z.fatal != null) return fail(z.fatal);
        if (s == TaskStatus.RUNNING) return s;
        List<String> problems = new ArrayList<>();
        if (!refused.isEmpty()) problems.add("refused (protected): " + summary(refused));
        if (s == TaskStatus.FAILED) problems.add(r.failure());
        if (problems.isEmpty()) return TaskStatus.SUCCESS;
        return fail(String.join("; ", problems));
    }

    private void next(boolean wasBroken) {
        if (wasBroken) {
            done++;
            broken.accept(current);
        }
        remaining.remove(current);
        current = null;
        budget = 0;
    }

    private void giveUp(String why) {
        failed.add(why);
        walker.stop();
        remaining.remove(current);
        current = null;
        budget = 0;
    }

    private TaskStatus finish() {
        // the same kind of line as DOUBLE's (BreakRun.summaryLine), so the two modes compare in the log
        LOG.info("Break round (SINGLE) done: {}/{} broken in {} ticks{}", done, total, now,
            done > 0 ? String.format(java.util.Locale.ROOT, " (%.1f/block)", (double) now / done) : "");
        List<String> problems = new ArrayList<>();
        if (!refused.isEmpty()) problems.add("refused (protected): " + summary(refused));
        if (!failed.isEmpty()) problems.add(summary(failed));
        if (problems.isEmpty()) return TaskStatus.SUCCESS;
        return fail(String.join("; ", problems));
    }

    private static String summary(List<String> items) {
        if (items.size() <= 5) return String.join(", ", items);
        return String.join(", ", items.subList(0, 5)) + " and " + (items.size() - 5) + " more";
    }

    /** Nearest target; among those in reach, one that wants what is in the hand first ({@link ToolRack#next}). */
    private @Nullable Pos nextTarget() {
        double ex = BOT.getX();
        double ey = BOT.getEyeY();
        double ez = BOT.getZ();
        return ToolRack.next(remaining, p -> p.distSq(ex, ey, ez), this::group, heldGroup(), reach());
    }

    /** What breaking the block at {@code p} holds: a tool kind, {@link ToolRack#HAND}, "" (neither), null (unloaded). */
    private @Nullable String group(Pos p) {
        var world = ctx.world();
        if (!world.isLoaded(p)) return null;
        int floor = Math.max(0, cfg.build.toolSafetyFloor);
        return groupByState.computeIfAbsent(world.stateId(p), id -> {
            Block b = BLOCK_DATA.getBlockDataFromBlockStateId(id);
            if (b == null || b.destroySpeed() < 0) return "";
            ToolChoice.Choice c = chooseTool(b, floor);
            return switch (c.kind()) {
                case HAND -> ToolRack.HAND;
                case TOOL -> c.tool() == null ? "" : c.tool().kind();
                case WORN -> "";
            };
        });
    }

    /** The {@link #group} the main hand serves now. */
    private static String heldGroup() {
        ItemStack s = Inv.mainHand();
        if (!Wear.damageable(s)) return ToolRack.HAND;
        String k = Wear.kind(s);
        return k == null ? "" : k;
    }

    /**
     * Once, before the first break, standing still: every tool some target needs goes to its own hotbar slot
     * ({@link ToolRack#arrange}) in one inventory request, so later switches are hotbar selects. True when nothing (more)
     * has to move; false while this tick was spent on it.
     */
    private boolean arrangeTools() {
        int floor = Math.max(0, cfg.build.toolSafetyFloor);
        var world = ctx.world();
        Set<Integer> wanted = new LinkedHashSet<>();
        Set<Integer> states = new HashSet<>();
        for (Pos p : remaining) {
            if (!world.isLoaded(p)) continue;
            int id = world.stateId(p);
            if (!states.add(id)) continue;
            Block block = BLOCK_DATA.getBlockDataFromBlockStateId(id);
            if (block == null || block.destroySpeed() < 0) continue;
            ToolChoice.Choice c = chooseTool(block, floor);
            if (c.kind() == ToolChoice.Kind.TOOL && c.tool() != null) wanted.add(c.tool().slot());
        }
        List<ToolRack.Move> moves = ToolRack.arrange(wanted, BreakBlocksTask::holds);
        if (moves.isEmpty()) {
            arranged = true;
            return true;
        }
        walker.stop();
        holdStill(); // an inventory click only after a still tick (Grim MultiActionsC)
        if (Inv.containerOpen()) return false;
        List<InventoryAction> actions = new ArrayList<>();
        List<String> what = new ArrayList<>();
        for (ToolRack.Move m : moves) {
            actions.add(new MoveToHotbarSlot(m.from(), MoveToHotbarAction.from(m.toHotbar())));
            what.add(Inv.id(Inv.stack(m.from())) + " -> hotbar " + m.toHotbar());
        }
        if (hotbar.submit(actions, now) == null) return false; // busy, or not still last tick yet
        arranged = true;
        LOG.info("Breaking {} blocks: {} tool(s) to their own hotbar slots ({})", total, moves.size(),
            String.join(", ", what));
        return false;
    }

    /** What hotbar index {@code i} holds, for {@link ToolRack}. */
    private static ToolRack.Holds holds(int i) {
        ItemStack s = Inv.stack(ItemIds.HOTBAR_START + i);
        if (Inv.empty(s)) return ToolRack.Holds.EMPTY;
        String kind = Wear.kind(s);
        if ("pickaxe".equals(kind) || "axe".equals(kind) || "shovel".equals(kind)) return ToolRack.Holds.TOOL;
        if (ZenithInventoryView.edible(s)) return ToolRack.Holds.KEEP;
        return ToolRack.Holds.OTHER;
    }

    /** Hotbar index for a tool that is not in the hotbar: a rack slot holding no other tool (slot 8 if all do). */
    private static int[] rackTarget() {
        int t = ToolRack.target(BreakBlocksTask::holds, Set.of());
        return t < 0 ? Hotbar.TOOL_SLOT : new int[]{t};
    }

    /**
     * What to hold for {@code block} ({@link ToolChoice}): the hand when it breaks the block (near-)instantly or no
     * tool is faster, else the fastest tool above the safety floor, else WORN.
     */
    static ToolChoice.Choice chooseTool(Block block, int floor) {
        var interactions = BOT.getInteractions();
        double hand = interactions.blockBreakSpeed(block, Container.EMPTY_STACK);
        int held = ItemIds.HOTBAR_START + Inv.heldSlot();
        List<ToolChoice.Candidate> tools = new ArrayList<>();
        for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) {
            ItemStack s = Inv.stack(i);
            if (Inv.empty(s) || Wear.kind(s) == null) continue;
            int max = Wear.maxUses(s);
            int rem = Wear.remaining(s);
            tools.add(new ToolChoice.Candidate(i, Inv.id(s), interactions.blockBreakSpeed(block, s), rem,
                Wear.damageable(s) ? max : rem, Wear.mending(s), ItemIds.hotbarIndex(i) >= 0, i == held));
        }
        return ToolChoice.choose(hand, tools, floor);
    }

    /**
     * {@link BreakRun.Io} on ZenithProxy: the cached world (the server's state; nothing is predicted), ZenithProxy's
     * vanilla break-speed formula with the item in the main hand, {@link ToolChoice} over the carried tools, the hotbar
     * select through ZenithProxy's InventoryManager, the walk and the real look of this task, and the dig packets.
     *
     * <p>Dig packets: {@code ServerboundPlayerActionPacket} sent from the client tick (before ZenithProxy's Bot sends
     * this tick's movement packet, so the rotation in effect is the one sent on the previous tick). START and STOP take a
     * sequence from ZenithProxy's block-prediction counter without predicting anything (the server acks it; the cache
     * keeps showing the block until the server's own update). ABORT carries sequence 0 and face DOWN, as the vanilla
     * client sends it when the button is released (Grim PositionBreakB flags another face unless a START on that face
     * follows). A swing goes after the dig packets, as the vanilla client's.
     */
    class ZenithBreakIo implements BreakRun.Io<Aiming.Shot> {
        /** Something that stops the task (no free hotbar slot for breaking by hand). */
        @Nullable String fatal;
        /** The last {@link ToolChoice} per tool group, for {@link #hold}. */
        final Map<String, ToolChoice.Choice> choiceByGroup = new HashMap<>();

        ZenithWorldView world() {
            return ctx.world();
        }

        int floor() {
            return Math.max(0, cfg.build.toolSafetyFloor);
        }

        @Override
        public int send(DigClient.Action a, Pos p, Aiming.Shot shot) {
            ClientSession client = Proxy.getInstance().getClient();
            if (client == null || !client.isConnected()) return -1;
            PlayerAction action = switch (a) {
                case START -> PlayerAction.START_DESTROY_BLOCK;
                case ABORT -> PlayerAction.ABORT_DESTROY_BLOCK;
                case STOP -> PlayerAction.STOP_DESTROY_BLOCK;
            };
            if (a == DigClient.Action.ABORT) {
                client.send(new ServerboundPlayerActionPacket(action, p.x(), p.y(), p.z(), Direction.DOWN.mcpl(), 0));
                return 0;
            }
            try (var prediction = CACHE.getChunkCache().getBlockStatePredictionHandler().startPredicting()) {
                int seq = prediction.currentSequence();
                client.send(new ServerboundPlayerActionPacket(action, p.x(), p.y(), p.z(),
                    shot.ray().direction().mcpl(), seq));
                return seq;
            }
        }

        @Override
        public void swing() {
            ClientSession client = Proxy.getInstance().getClient();
            if (client != null && client.isConnected()) client.sendAsync(new ServerboundSwingPacket(Hand.MAIN_HAND));
        }

        @Override
        public double speedNow(Pos p) {
            if (!world().isLoaded(p)) return 0;
            Block b = BLOCK_DATA.getBlockDataFromBlockStateId(world().stateId(p));
            if (b == null || b.destroySpeed() < 0) return 0;
            if (b.destroySpeed() == 0) return 1.0;
            return BOT.getInteractions().blockBreakSpeed(b, Inv.mainHand());
        }

        @Override
        public boolean isAir(Pos p) {
            return world().isLoaded(p) && world().block(p).isAir();
        }

        @Override
        public int latestAck() {
            return BlockAcks.latest();
        }

        @Override
        public boolean isLoaded(Pos p) {
            return world().isLoaded(p);
        }

        @Override
        public BlockSpec block(Pos p) {
            return world().block(p);
        }

        @Override
        public @Nullable String refusal(Pos p) {
            BlockSpec spec = world().block(p);
            if (isProtected(p, spec)) return spec.id() + " at " + p;
            Block b = BLOCK_DATA.getBlockDataFromBlockStateId(world().stateId(p));
            if (b == null || b.destroySpeed() < 0) return "unbreakable " + spec.id() + " at " + p;
            return null;
        }

        /**
         * Computed afresh (the tools wear, effects come and go), as on the ground: ZenithProxy's speed divides by 5 in
         * the air, and a cached in-air speed would make a 0.78/tick block look like a delayed one (0.16).
         */
        @Override
        public BreakPairs.@Nullable Info info(Pos p) {
            if (!world().isLoaded(p)) return null;
            BlockSpec spec = world().block(p);
            if (spec.isAir() || spec.id().equals("minecraft:water") || spec.id().equals("minecraft:lava")
                || isProtected(p, spec)) {
                return null;
            }
            Block b = BLOCK_DATA.getBlockDataFromBlockStateId(world().stateId(p));
            if (b == null || b.destroySpeed() < 0) return null;
            if (b.destroySpeed() == 0) return new BreakPairs.Info(ToolRack.HAND, 1.0);
            double ground = BOT.isOnGround() ? 1.0 : 5.0;
            ToolChoice.Choice c = chooseTool(b, floor());
            return switch (c.kind()) {
                case HAND -> new BreakPairs.Info(ToolRack.HAND,
                    BOT.getInteractions().blockBreakSpeed(b, Container.EMPTY_STACK) * ground);
                case TOOL -> c.tool() == null ? null : new BreakPairs.Info(c.tool().kind(), c.tool().speed() * ground);
                case WORN -> null;
            };
        }

        @Override
        public BreakRun.Tool tool(Pos p) {
            Block b = world().isLoaded(p) ? BLOCK_DATA.getBlockDataFromBlockStateId(world().stateId(p)) : null;
            if (b == null) return BreakRun.Tool.hand();
            ToolChoice.Choice c = chooseTool(b, floor());
            return switch (c.kind()) {
                case HAND -> BreakRun.Tool.hand();
                case TOOL -> {
                    ToolChoice.Candidate t = c.tool();
                    if (t == null) yield BreakRun.Tool.hand();
                    choiceByGroup.put(t.kind(), c);
                    yield new BreakRun.Tool(ToolChoice.Kind.TOOL, t.kind(), t.remaining(), "");
                }
                case WORN -> new BreakRun.Tool(ToolChoice.Kind.WORN, c.tool() == null ? "" : c.tool().kind(), 0,
                    c.wornReason(floor()));
            };
        }

        @Override
        public boolean hold(BreakRun.Tool t) {
            // a select or click of ours still queued in ZenithProxy's InventoryManager could land after the next START
            if (hotbar.busy(now)) return false;
            if (t.kind() == ToolChoice.Kind.HAND) {
                int bare = Hotbar.bareHandIndex();
                if (bare < 0) {
                    fatal = "every hotbar slot holds a tool or weapon: no free hand to break by hand";
                    return false;
                }
                if (bare == Inv.heldSlot()) return true;
                hotbar.ensureSlotHeld(ItemIds.HOTBAR_START + bare, Hotbar.TOOL_SLOT, now);
                return false;
            }
            ToolChoice.Choice c = choiceByGroup.get(t.group());
            if (c == null || c.tool() == null) return false;
            if (!hotbar.ensureSlotHeld(c.tool().slot(), rackTarget(), now)) return false;
            ItemStack held = Inv.mainHand();
            // defensive: the cache shows the held item at the floor (it wore during the last block)
            return !(Wear.damageable(held) && Wear.remaining(held) <= floor());
        }

        @Override
        public String heldGroup() {
            return BreakBlocksTask.heldGroup();
        }

        @Override
        public double distSq(Pos p) {
            return p.distSq(BOT.getX(), BOT.getEyeY(), BOT.getZ());
        }

        @Override
        public TaskStatus approach(Pos p) {
            if (!world().isLoaded(p)) return walkNear(p, 3, 4);
            return walkUntil(approachGoals(p), () -> Aiming.anyFace(p.x(), p.y(), p.z(), reach()) != null);
        }

        @Override
        public TaskStatus approachPair(Pos a, Pos b) {
            Pos mid = new Pos(Math.floorDiv(a.x() + b.x(), 2), a.y(), Math.floorDiv(a.z() + b.z(), 2));
            return walkUntil(List.of(new GoalNearXZ(mid.x(), mid.y(), mid.z(), 2, 2),
                    new GoalNearXZ(mid.x(), mid.y(), mid.z(), 1, 2)),
                () -> aim(a) != null && aim(b) != null);
        }

        @Override
        public String walkError() {
            return walker.error();
        }

        @Override
        public void stopWalking() {
            walker.stop();
        }

        @Override
        public Aiming.@Nullable Shot aim(Pos p) {
            return Aiming.anyFace(p.x(), p.y(), p.z(), reach());
        }

        @Override
        public Aiming.@Nullable Shot inEffect(Aiming.Shot shot) {
            var r = shot.ray();
            return Aiming.check(r.x(), r.y(), r.z(), null, BOT.getYaw(), BOT.getPitch(), reach());
        }

        @Override
        public void look(Aiming.@Nullable Shot shot) {
            if (shot == null) holdStill();
            else clicker.submit(now, shot.yaw(), shot.pitch(), null);
        }

        @Override
        public boolean canAct() {
            return clicker.canClick(now);
        }

        @Override
        public void log(String line) {
            LOG.info(line);
        }
    }

    /**
     * {@link NukeRun.Io} on ZenithProxy, on top of {@link ZenithBreakIo} (the dig packets, the world, the tool choice):
     * aiming at the nearest point of a face turned to the eye (reach counted to it, as the server and Grim FarBreak count
     * to the block's box), a hotbar select sent at once (before the tick's STARTs, the vanilla order), this tick's input
     * with movement keys towards a direction, the floor test for the sweep, Baritone walks to a cell, and Haste only
     * while its effect has {@link #hasteMargin} ticks left (estimated from the last effect update).
     */
    final class ZenithNukeIo extends ZenithBreakIo implements NukeRun.Io<Aiming.Shot> {
        double reach = BreakBlocksTask.this.reach();
        int hasteMargin = 40;
        /** Per tick: block state id → info / tool choice (the speeds do not change within a tick). */
        private long cacheTick = -1;
        private final Map<Integer, BreakPairs.@Nullable Info> infoCache = new HashMap<>();
        private final Map<Integer, BreakRun.Tool> toolCache = new HashMap<>();
        private @Nullable Object hasteSeen;
        private long hasteSeenAt;

        private void fresh() {
            if (cacheTick == now) return;
            cacheTick = now;
            infoCache.clear();
            toolCache.clear();
        }

        /** Ticks the Haste effect has left (estimated), -1 without it; MAX_VALUE for an infinite one. */
        @Override
        public int hasteTicksLeft() {
            var h = CACHE.getPlayerCache().getThePlayer().getPotionEffectMap()
                .get(org.geysermc.mcprotocollib.protocol.data.game.entity.Effect.HASTE);
            if (h == null) {
                hasteSeen = null;
                return -1;
            }
            long t = System.currentTimeMillis();
            if (h != hasteSeen) { // each effect update is a new object
                hasteSeen = h;
                hasteSeenAt = t;
            }
            if (h.getDuration() < 0) return Integer.MAX_VALUE;
            // the server counts effects down per server tick (the local speed mode ticks it client.tickRate times
            // faster); a server below 20 TPS only makes this an underestimate
            return (int) (h.getDuration() - (t - hasteSeenAt) / clientTickMillis());
        }

        @Override
        public boolean hasteUncertain() {
            int left = hasteTicksLeft();
            return left >= 0 && left < hasteMargin;
        }

        /** Divides out Haste when it is about to run out (the server may have dropped it already). */
        private double hasteAdjusted(double speed) {
            int left = hasteTicksLeft();
            if (left < 0 || left >= hasteMargin) return speed;
            var h = CACHE.getPlayerCache().getThePlayer().getPotionEffectMap()
                .get(org.geysermc.mcprotocollib.protocol.data.game.entity.Effect.HASTE);
            if (h == null) return speed;
            return speed / (1.0 + (h.getAmplifier() + 1) * 0.2);
        }

        @Override
        public double speedNow(Pos p) {
            return hasteAdjusted(super.speedNow(p));
        }

        @Override
        public BreakPairs.@Nullable Info info(Pos p) {
            fresh();
            if (!world().isLoaded(p)) return null;
            int id = world().stateId(p);
            if (infoCache.containsKey(id)) return infoCache.get(id);
            BreakPairs.Info i = super.info(p);
            if (i != null) i = new BreakPairs.Info(i.group(), hasteAdjusted(i.speed()));
            infoCache.put(id, i);
            return i;
        }

        @Override
        public BreakRun.Tool tool(Pos p) {
            fresh();
            int id = world().isLoaded(p) ? world().stateId(p) : -1;
            BreakRun.Tool t = toolCache.get(id);
            if (t == null) {
                t = super.tool(p);
                toolCache.put(id, t);
            }
            return t;
        }

        /** A tool in the hotbar is selected at once (before this tick's STARTs); from the main inventory: a click. */
        @Override
        public boolean hold(BreakRun.Tool t) {
            if (hotbar.busy(now)) return false;
            if (t.kind() == ToolChoice.Kind.HAND) {
                int bare = Hotbar.bareHandIndex();
                if (bare < 0) {
                    fatal = "every hotbar slot holds a tool or weapon: no free hand to break by hand";
                    return false;
                }
                return bare == Inv.heldSlot() || Clicker.selectNow(bare);
            }
            ToolChoice.Choice c = choiceByGroup.get(t.group());
            if (c == null || c.tool() == null) return false;
            int hb = ItemIds.hotbarIndex(c.tool().slot());
            if (hb >= 0) {
                if (!Clicker.selectNow(hb)) return false;
            } else {
                hotbar.ensureSlotHeld(c.tool().slot(), rackTarget(), now);
                return false;
            }
            ItemStack held = Inv.mainHand();
            return !(Wear.damageable(held) && Wear.remaining(held) <= floor());
        }

        @Override
        public double feetX() {
            return BOT.getX();
        }

        @Override
        public double feetY() {
            return BOT.getY();
        }

        @Override
        public double feetZ() {
            return BOT.getZ();
        }

        @Override
        public double eyeY() {
            return BOT.getEyeY();
        }

        @Override
        public boolean onGround() {
            return BOT.isOnGround();
        }

        @Override
        public Aiming.@Nullable Shot aim(Pos p) {
            Aiming.Shot s = Aiming.nearestFace(p.x(), p.y(), p.z(), reach);
            return s != null ? s : Aiming.anyFace(p.x(), p.y(), p.z(), reach);
        }

        /** From an eye elsewhere (after this tick's movement): full blocks only; null leaves the run to {@link #aim}. */
        @Override
        public Aiming.@Nullable Shot aimFrom(Pos p, double ex, double ey, double ez) {
            return Aiming.nearestFaceFrom(ex, ey, ez, p.x(), p.y(), p.z(), reach);
        }

        @Override
        public Aiming.@Nullable Shot inEffect(Aiming.Shot shot) {
            var r = shot.ray();
            return Aiming.check(r.x(), r.y(), r.z(), null, BOT.getYaw(), BOT.getPitch(), reach);
        }

        @Override
        public void input(Aiming.@Nullable Shot look, double dx, double dz) {
            int forward = 0;
            int strafe = 0;
            if (dx != 0 || dz != 0) {
                int[] keys = Aim.movementKeys(look != null ? look.yaw() : BOT.getYaw(), dx, dz);
                forward = keys[0];
                strafe = keys[1];
            }
            com.zenith.feature.player.Input in = com.zenith.feature.player.Input.builder()
                .pressingForward(forward > 0)
                .pressingBack(forward < 0)
                .pressingLeft(strafe > 0)
                .pressingRight(strafe < 0)
                .build();
            clicker.submit(now, look == null ? null : look.yaw(), look == null ? null : look.pitch(), in);
        }

        @Override
        public boolean walkable(Pos cell) {
            var w = world();
            if (!w.isLoaded(cell) || !w.isLoaded(cell.below()) || zones.at(cell) != null) return false;
            if (w.hasFluid(cell)) return false;
            BlockSpec b = w.block(cell);
            if (!b.isAir() && BlockStates.collisionTop(b) > 0.6) return false; // above the step height
            BlockSpec head = w.block(cell.above());
            if (!head.isAir() && BlockStates.collisionTop(head) > 0) return false;
            return standsOn(w.block(cell.below()));
        }

        private EdgeDrop.@Nullable Cells edgeCells;

        /** {@link EdgeDrop#at} on the cached world and the no-go zones; null with {@code build.nukeEdgeGuard} off. */
        @Override
        public @Nullable String hazard(Pos cell) {
            if (!cfg.build.nukeEdgeGuard) return null;
            if (edgeCells == null) edgeCells = EdgeApproach.cells(world(), zones);
            return EdgeDrop.at(cell, edgeCells);
        }

        /** {@link #input} chooses its keys for the look's yaw, or the bot's without one. */
        @Override
        public double keyYaw(Aiming.@Nullable Shot look) {
            return look != null ? look.yaw() : BOT.getYaw();
        }

        @Override
        public double[] velocity() {
            var v = BOT.getVelocity();
            return new double[]{v.getX(), v.getZ()};
        }

        @Override
        public boolean climbable(Pos c) {
            var w = world();
            if (!w.isLoaded(c) || !w.isLoaded(c.above()) || zones.at(c.above()) != null) return false;
            BlockSpec b = w.block(c);
            if (b.isAir() || BlockStates.collisionTop(b) <= LaneRun.TALL || !BlockStates.walkOnTop(b)) return false;
            BlockSpec head = w.block(c.above());
            BlockSpec top = w.block(c.above().above());
            return (head.isAir() || BlockStates.collisionTop(head) == 0) && (top.isAir() || BlockStates.collisionTop(top) == 0);
        }

        @Override
        public boolean escapable(Pos c) {
            var w = world();
            if (!w.isLoaded(c) || !w.isLoaded(c.below()) || !w.isLoaded(c.above())) return false;
            BlockSpec spec = w.block(c);
            // a wall: taller than a step (carpets, plates, candles and lichen never: the box passes them)
            if (spec.isAir() || BlockStates.collisionTop(spec) <= LaneRun.TALL || refusal(c) != null || w.hasFluid(c)) {
                return false;
            }
            if (SiteRules.protectedRegionAt(cfg.site, c) != null || zones.at(c) != null) return false;
            if (!clearedAnyway(c, spec.id())) return false;
            BlockSpec head = w.block(c.above());
            if (!head.isAir() && BlockStates.collisionTop(head) > 0) return false;
            return standsOn(w.block(c.below()));
        }

        @Override
        public TaskStatus walkTo(Pos goal, int range) {
            return walkNear(goal, range, 3);
        }
    }

    /**
     * {@code id} at {@code c} is one of the clearable blocks of a platform whose art layer holds {@code c} (the 128 ×
     * 128 map and its noobline row): the platform's clear removes it anyway.
     */
    private boolean clearedAnyway(Pos c, String id) {
        return WallRoute.clearedAnyway(cfg.site, c, id);
    }

    /** Lowest top of a floor block the fast nuke counts as standing ground ({@link #standsOn}). */
    static final double FLOOR_MIN_TOP = 0.8;

    /**
     * The bot can stand on {@code floor} for the fast nuke's walk: a block whose collision reaches {@link #FLOOR_MIN_TOP}.
     * Live 2026-10-15 (Galileo [0,0], soul sand platform): the test asked for 0.9, soul sand is 0.875 (14/16), so every
     * cell of the art read as no floor: each sweep step met "a block that is no target in the way", the lanes were
     * skipped (26 of 5,794 broken on the way) and 1,301 Baritone walks to a target took 17 minutes. Soul sand and mud
     * (0.875) stand; slabs and lower do not.
     */
    static boolean standsOn(BlockSpec floor) {
        return !floor.isAir() && BlockStates.collisionTop(floor) >= FLOOR_MIN_TOP;
    }

    /** Left-click target that uses the normal (line-of-sight) raycast and accepts only the wanted block. */
    record TargetBlock(int x, int y, int z, double reach) implements ClickTarget {
        @Override
        public BlockOrEntityRaycastResult raycast(double blockReachDistance, double entityInteractDistance) {
            return BlockOrEntityRaycastResult.wrap(RaycastHelper.playerBlockRaycast(Math.min(reach, blockReachDistance), false));
        }

        @Override
        public boolean validateRaycast(BlockOrEntityRaycastResult r) {
            return r.hit() && r.isBlock() && r.block().x() == x && r.block().y() == y && r.block().z() == z;
        }
    }
}
