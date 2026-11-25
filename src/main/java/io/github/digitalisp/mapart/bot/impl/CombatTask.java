package io.github.digitalisp.mapart.bot.impl;

import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.FightArea;
import io.github.digitalisp.mapart.bot.MobHits;
import io.github.digitalisp.mapart.bot.MobMemory;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static com.zenith.Globals.BOT;

/**
 * {@code fight}: ZenithProxy's KillAura, switched on by the engine for the fight,
 * attacks what is in reach; this task holds the weapon ({@link Weapons}: the best sword, then axe, above the
 * durability floor; else a bare hand, never a worn weapon or a tool), keeps KillAura's own weapon switch off
 * ({@link KillAuraGuard}), and with {@code area.chase()} walks within reach of the mob of the area that stays out of
 * reach, the nearest of those that hit the bot first ({@link MobHits#attackers}), else the nearest (Baritone with the
 * no-go zones; a walk that fails is retried after a pause, the bot standing meanwhile). Ends once no mob of the area
 * has been seen for {@code quietTicks}, after selecting the hotbar slot it found selected. "Hurt" here is a hit by a
 * listed mob ({@link MobHits}, from the server's damage events), never a fall or any other health drop.
 *
 * <p>Creepers ({@code combat.creeperHitAndRun}, {@link CreeperFight}): KillAura is allowed to attack only while the bot
 * stands within reach of a creeper with a full attack ready; one hit, then the bot walks away to more than
 * {@code combat.creeperRetreatBlocks} from it ({@link CreeperRetreat}: loaded floor, no-go zones kept, never past it or
 * off the floor), waits {@code combat.creeperWaitSeconds}, and goes again. While a creeper is within 7 blocks it comes
 * before every other mob, unless the walk could not reach it and it is neither fusing nor within
 * {@link #CREEPER_CLOSE} ({@link #priorityCreeper}); the hit is seen as a fall of the creeper's health.
 */
final class CombatTask extends ZenithTask {
    /** KillAura reaches about 3 blocks (entity interaction range); closer than this the bot stops walking. */
    static final double REACH = 2.6;
    /** After a failed chase walk the bot stands this many ticks before it tries again. */
    static final int CHASE_PAUSE = 40;
    /**
     * Only mobs it cannot walk to left, and no listed mob hit the bot for this long (10 s; a pillager shoots every 2-3
     * s): the fight ends and reports them as unreachable.
     */
    static final int UNREACHABLE_QUIET_TICKS = 200;
    /**
     * Only mobs it cannot walk to left, and a listed mob hit the bot this long (2 s: an arrow already in flight lands
     * sooner) after that began: standing there is no defence, so the fight fails (the engine retreats).
     */
    static final int STUCK_HURT_GRACE_TICKS = 40;

    private final FightArea area;
    private final int floor;
    private final int quietTicks;
    private RefCountedOverride.@Nullable Handle aura;
    private int prevSlot = -1;
    private int quiet;
    private long chasePauseUntil;
    private @Nullable String held = "";
    private int seen;
    /** Mobs a chase walk failed to reach: not chased again; with only them left the fight ends (see below). */
    private final MobMemory unreachable = new MobMemory();
    private final Consumer<List<WorldView.Mob>> reportUnreachable;
    /** The engine's count of hits by listed mobs ({@link MobHits}). */
    private final MobHits hits;
    /** The hit count last seen, or -1 before the first step. */
    private long hitsSeen = -1;
    /** Tick a listed mob last hit the bot during the fight, or the tick a mob was first found unreachable. */
    private long quietFrom = -1;
    private boolean ending;
    /** Tick a listed mob last hit the bot during the fight, or -1. */
    private long hurtAt = -1;
    /** Tick since which only unreachable mobs are left, or -1. */
    private long stuckSince = -1;
    /** The attacker last named in the log ("going for"), or -1. */
    private int attackerLogged = -1;
    /** The hit and run against creepers, created with the first creeper. */
    private @Nullable CreeperFight creeperFight;
    private CreeperFight.@Nullable Action creeperAction;
    /** The walk is a retreat (a plain walk, sprint as the walks allow) rather than the chase. */
    private boolean retreating;
    /** The retreat's candidate cells, the one walked to, and the creepers' x, z it walks away from. */
    private List<CreeperRetreat.Candidate> retreatCells = List.of();
    private int retreatNext;
    private int retreatWalks;
    private @Nullable Pos retreatTo;
    private List<double[]> retreatFrom = List.of();
    /** The last {@link #holdWeapon} found the weapon (or a bare hand) in the main hand. */
    private boolean weaponHeld;
    /** Tick the main-hand item last changed: a new weapon starts with an empty attack strength. */
    private long heldSince;
    private @Nullable String heldId = "";
    /** Last health seen per creeper, to notice a hit. */
    private final Map<Integer, Float> creeperHealth = new HashMap<>();
    /** Retreat walks tried before the fight fails (the engine then retreats along the bot's own track). */
    static final int MAX_RETREAT_WALKS = 3;
    /** A creeper this close (feet to feet) comes before every other mob. */
    static final double CREEPER_PRIORITY = 7.0;
    /**
     * A creeper this close (feet to feet) is handled even when the walk cannot reach it (it swells within 3; the hit
     * and run backs off from a fuse within {@link CreeperFight#WATCH}). Farther off, an unreachable one is set aside.
     */
    static final double CREEPER_CLOSE = CreeperFight.WATCH;

    CombatTask(ZenithBotActions ctx, FightArea area, int weaponFloor, int quietTicks,
               Consumer<List<WorldView.Mob>> reportUnreachable, MobHits hits) {
        super(ctx, 0);
        this.area = area;
        this.floor = weaponFloor;
        this.quietTicks = Math.max(1, quietTicks);
        this.reportUnreachable = reportUnreachable;
        this.hits = hits;
    }

    @Override
    public String name() {
        return "fight " + area.types() + (area.chase() ? "" : " (in reach only)");
    }

    /** The chase: KillAura's hits as before; hotbar moves wait for the quiet window. */
    @Override
    protected SprintLegs.Walk walkKind() {
        return retreating ? SprintLegs.Walk.RETREAT : SprintLegs.Walk.CHASE;
    }

    @Override
    protected TaskStatus step() {
        if (aura == null) {
            aura = KillAuraGuard.acquire();
            prevSlot = Inv.heldSlot();
        } else {
            KillAuraGuard.enforce();
        }
        WorldView w = ctx.world();
        long hitCount = hits.count();
        if (hitsSeen >= 0 && hitCount > hitsSeen) {
            quietFrom = now;
            hurtAt = now;
        }
        hitsSeen = hitCount;
        // never a teammate, not even for the out-of-reach memory's matching
        List<WorldView.Mob> all = w.mobs(area.types()).stream().filter(m -> !area.exempt().test(m)).toList();
        List<WorldView.Mob> mobs = area.filter(all);
        if (mobs.isEmpty() || ending) {
            walker.stop();
            if (!ending && ++quiet < quietTicks) return TaskStatus.RUNNING;
            // the fight is over: back to the slot the bot held before it
            if (prevSlot >= 0 && Inv.heldSlot() != prevSlot
                && !hotbar.ensureSlotHeld(ItemIds.HOTBAR_START + prevSlot, Hotbar.TOOL_SLOT, now)) return TaskStatus.RUNNING;
            if (!ending) LOG.info("fight over: no {} in the area for {} ticks; {} seen", area.types(), quietTicks, seen);
            return TaskStatus.SUCCESS;
        }
        quiet = 0;
        seen = Math.max(seen, mobs.size());
        // every mob the client sees goes to the memory: a record goes when its mob is gone, not when it left the area
        List<WorldView.Mob> notUnreachable = unreachable.without(all);
        List<WorldView.Mob> reachable = area.filter(notUnreachable);
        if (cfg.combat.creeperHitAndRun) {
            // a creeper within 7 blocks comes before everything (retreat > other targets), unless it is one the walk
            // could not reach and it is neither close nor fusing: that one is out of reach like any other mob (live
            // 2026-10-30: a creeper 5 blocks under the floor held the hit and run for 120 s, round after round)
            WorldView.Mob c = priorityCreeper(mobs, notUnreachable, BOT.getX(), BOT.getY(), BOT.getZ());
            if (c != null) return creeperStep(c, mobs);
            WorldView.Mob far = nearestCreeper(reachable);
            if (far != null && reachable.stream().allMatch(CombatTask::creeper)) return creeperStep(far, mobs);
            leaveCreeperMode();
            if (far != null) reachable = reachable.stream().filter(m -> !creeper(m)).toList();
        }
        if (reachable.isEmpty()) {
            // only mobs it cannot walk to: KillAura still hits one that comes into reach; once none has hit the bot
            // for a while they are left alone (the engine ignores them until a mob hits the bot again)
            walker.stop();
            if (stuckSince < 0) stuckSince = now;
            if (hurtAt >= stuckSince + STUCK_HURT_GRACE_TICKS) {
                String n = mobs.size() == 1 ? "1 mob" : mobs.size() + " mobs";
                LOG.info("fight: hurt with only {} out of reach left; giving up the fight", n);
                return fail("hurt by " + n + " out of reach");
            }
            if (now - quietFrom >= UNREACHABLE_QUIET_TICKS) {
                LOG.info("fight over: only {} out of reach left and no mob hit the bot for {} ticks; ignored until"
                    + " a mob hits the bot again", mobs.size() == 1 ? "1 mob" : mobs.size() + " mobs",
                    UNREACHABLE_QUIET_TICKS);
                reportUnreachable.accept(List.copyOf(mobs));
                ending = true;
            }
            return TaskStatus.RUNNING;
        }
        stuckSince = -1;
        if (!holdWeapon(true)) return TaskStatus.RUNNING;
        java.util.Set<Integer> attackers = hits.attackers();
        Optional<WorldView.Mob> t = area.target(reachable, attackers, BOT.getX(), BOT.getY(), BOT.getZ());
        if (t.isEmpty()) return TaskStatus.RUNNING;
        WorldView.Mob m = t.get();
        if (attackers.contains(m.id()) && m.id() != attackerLogged) {
            LOG.info("fight: going for the {} #{} that hit the bot", m.type(), m.id());
            attackerLogged = m.id();
        }
        double d = Math.sqrt(m.distSq(BOT.getX(), BOT.getY() + 1.0, BOT.getZ()));
        if (!area.chase() || d <= REACH || now < chasePauseUntil) {
            walker.stop();
            return TaskStatus.RUNNING;
        }
        Pos goal = new Pos((int) Math.floor(m.x()), (int) Math.floor(m.y()), (int) Math.floor(m.z()));
        TaskStatus s = walkNear(goal, 2, 3);
        if (s == TaskStatus.FAILED) {
            LOG.info("fight: cannot walk to the {} at {}: {}; not chased again", m.type(), goal.key(), walker.error());
            walker.stop();
            chasePauseUntil = now + CHASE_PAUSE;
            if (unreachable.isEmpty()) quietFrom = Math.max(quietFrom, now);
            unreachable.add(m);
        }
        return TaskStatus.RUNNING;
    }

    /**
     * The creeper the hit and run deals with now, or null: the nearest of {@code mobs} within {@link #CREEPER_PRIORITY}
     * (feet to feet from the bot's feet at {@code bx, by, bz}) that still counts. One the chase walk failed to reach
     * (not in {@code notUnreachable}) counts only while it is fusing or within {@link #CREEPER_CLOSE}: a creeper that
     * close is always handled (struck or backed off from), never left alone; one farther off is out of reach like
     * any other mob ({@link #UNREACHABLE_QUIET_TICKS}, then reported and set aside by the engine).
     */
    static WorldView.@Nullable Mob priorityCreeper(List<WorldView.Mob> mobs, List<WorldView.Mob> notUnreachable,
                                                   double bx, double by, double bz) {
        WorldView.Mob best = null;
        double bd = Double.MAX_VALUE;
        for (WorldView.Mob m : mobs) {
            if (!creeper(m)) continue;
            double d = Math.sqrt(m.distSq(bx, by, bz));
            if (d > CREEPER_PRIORITY || d >= bd) continue;
            if (!m.fusing() && d > CREEPER_CLOSE && !notUnreachable.contains(m)) continue;
            bd = d;
            best = m;
        }
        return best;
    }

    private static boolean creeper(WorldView.Mob m) {
        return m.type().equals("creeper");
    }

    private static WorldView.@Nullable Mob nearestCreeper(List<WorldView.Mob> mobs) {
        WorldView.Mob best = null;
        double bd = Double.MAX_VALUE;
        for (WorldView.Mob m : mobs) {
            if (!creeper(m)) continue;
            double d = feetDist(m);
            if (d < bd) {
                bd = d;
                best = m;
            }
        }
        return best;
    }

    /** Feet to feet, as vanilla's swell goal measures. */
    private static double feetDist(WorldView.Mob m) {
        return Math.sqrt(m.distSq(BOT.getX(), BOT.getY(), BOT.getZ()));
    }

    /** No creeper to deal with now: the walk is the chase again, the retreat's path rule off, the state machine new. */
    private void leaveCreeperMode() {
        if (creeperAction == null) return;
        if (retreating) endRetreat();
        creeperAction = null;
        creeperFight = null;
        creeperHealth.clear();
    }

    private static String blockKey(WorldView.Mob c) {
        return new Pos((int) Math.floor(c.x()), (int) Math.floor(c.y()), (int) Math.floor(c.z())).key();
    }

    /**
     * One tick against {@code c}, the nearest creeper ({@link CreeperFight}): approach, strike (KillAura's attack on),
     * retreat (a walk away), wait. The weapon is held as in any fight, but not by a hotbar move with a creeper within 4
     * blocks and no weapon held (then it is no moment to hit, only to back off).
     */
    private TaskStatus creeperStep(WorldView.Mob c, List<WorldView.Mob> mobs) {
        if (creeperFight == null) {
            creeperFight = new CreeperFight(cfg.combat.creeperRetreatBlocks,
                (int) Math.round(Math.max(0, cfg.combat.creeperWaitSeconds) * 20));
            LOG.info("fight: creeper #{} at {}: hit and run (a full attack, back off beyond {} blocks, wait {} s)",
                c.id(), blockKey(c), creeperFight.safe(), cfg.combat.creeperWaitSeconds);
        }
        double d = feetDist(c);
        boolean hit = false;
        for (WorldView.Mob m : mobs) {
            if (!creeper(m) || Float.isNaN(m.health())) continue;
            Float before = creeperHealth.put(m.id(), m.health());
            if (m.id() == c.id() && before != null && m.health() < before) hit = true;
        }
        ItemStack in = Inv.stack(ItemIds.HOTBAR_START + Inv.heldSlot());
        String id = Inv.empty(in) ? null : Inv.id(in);
        if (!java.util.Objects.equals(id, heldId)) {
            heldId = id;
            heldSince = now;
        }
        int cooldown = Weapons.cooldownTicks(id);
        boolean holding = weaponHeld && now - heldSince >= cooldown;
        CreeperFight.Action a = creeperFight.step(now, d, c.fusing(), holding, cooldown, hit);
        if (a != creeperAction) {
            LOG.info("fight: creeper #{} {} blocks away{}{}: {} (fuse ~{}, {} hits)", c.id(), String.format("%.1f", d),
                c.fusing() ? ", fusing" : "", hit ? ", hit seen" : "", a.name().toLowerCase(), creeperFight.fuse(),
                creeperFight.hits());
            if (a == CreeperFight.Action.RETREAT) startRetreat(mobs);
            else if (creeperAction == CreeperFight.Action.RETREAT) endRetreat();
            creeperAction = a;
        }
        if (a != CreeperFight.Action.RETREAT && (d > CreeperFight.WATCH || weaponHeld)) {
            weaponHeld = holdWeapon(a == CreeperFight.Action.STRIKE);
        } else {
            KillAuraGuard.attack(false);
        }
        switch (a) {
            case STRIKE, WAIT -> walker.stop();
            case APPROACH -> {
                if (!area.chase() || now < chasePauseUntil) {
                    walker.stop();
                    break;
                }
                Pos goal = new Pos((int) Math.floor(c.x()), (int) Math.floor(c.y()), (int) Math.floor(c.z()));
                if (walkNear(goal, 2, 3) == TaskStatus.FAILED) {
                    LOG.info("fight: cannot walk to the creeper at {}: {}; not chased again", goal.key(), walker.error());
                    walker.stop();
                    chasePauseUntil = now + CHASE_PAUSE;
                    if (unreachable.isEmpty()) quietFrom = Math.max(quietFrom, now);
                    unreachable.add(c);
                }
            }
            case RETREAT -> {
                return retreatStep(c, mobs);
            }
        }
        return TaskStatus.RUNNING;
    }

    /** The retreat begins: the cells away from the creepers, best first; the walk starts at the first. */
    private void startRetreat(List<WorldView.Mob> mobs) {
        retreating = true;
        retreatWalks = 0;
        walker.stop();
        planRetreat(mobs);
    }

    private void endRetreat() {
        retreating = false;
        retreatTo = null;
        walker.stop();
        walker.pathCheck(null);
    }

    /** Plans the cells from where the bot and the creepers are now (the nearest creeper first). */
    private void planRetreat(List<WorldView.Mob> mobs) {
        List<WorldView.Mob> cs = new ArrayList<>(mobs.stream().filter(CombatTask::creeper).toList());
        cs.sort(java.util.Comparator.comparingDouble(CombatTask::feetDist));
        retreatFrom = cs.stream().map(m -> new double[]{m.x(), m.z()}).toList();
        double dist = creeperFight.safe() + 1.5;
        retreatCells = CreeperRetreat.plan(new WorldCells(), Walker.feet(), retreatFrom, dist);
        retreatNext = 0;
        retreatTo = null;
        walker.pathCheck(cells -> CreeperRetreat.pathRefusal(cells, retreatFrom));
        LOG.info("fight: retreat from {} creeper(s): {} cells {}+ blocks away{}", cs.size(), retreatCells.size(), dist,
            retreatCells.isEmpty() ? "" : ", first " + retreatCells.getFirst().feet().key()
                + (retreatCells.getFirst().farSide() ? " (far side)" : ""));
    }

    /** The retreat walk: to the planned cell, until the creeper is farther than the safe distance. */
    private TaskStatus retreatStep(WorldView.Mob c, List<WorldView.Mob> mobs) {
        if (retreatTo != null && !CreeperRetreat.clear(retreatTo, retreatFrom, creeperFight.safe() + 1.0)) {
            walker.stop();
            planRetreat(mobs); // the creeper follows: new cells from where it is now
        }
        if (retreatTo == null) {
            if (retreatNext >= retreatCells.size() || retreatWalks >= MAX_RETREAT_WALKS) {
                return fail("no way to back off from the creeper at " + blockKey(c) + " (" + retreatCells.size()
                    + " cells, " + retreatWalks + " walks)");
            }
            retreatTo = retreatCells.get(retreatNext).feet();
            retreatWalks++;
            LOG.info("fight: retreat walk {} to {}", retreatWalks, retreatTo.key());
        }
        Pos t = retreatTo;
        TaskStatus s = walkUntil(List.of(new GoalNearXZ(t.x(), t.y(), t.z(), 0, 1)),
            () -> feetDist(c) > creeperFight.safe() || Walker.feet().equals(t));
        if (s == TaskStatus.FAILED) {
            LOG.info("fight: retreat walk to {} failed: {}; next cell", t.key(), walker.error());
            walker.stop();
            retreatNext++;
            retreatTo = null;
        } else if (s == TaskStatus.SUCCESS) {
            // far enough: the machine moves on to the wait next tick; at the cell and still too near, the next cell
            if (feetDist(c) <= creeperFight.safe()) {
                retreatNext++;
                retreatTo = null;
            }
            walker.stop();
        }
        return TaskStatus.RUNNING;
    }

    /** {@link EscapePlanner.Cells} over the cached world and the task's no-go zones (as the escape reads them). */
    private final class WorldCells implements EscapePlanner.Cells {
        @Override
        public boolean isLoaded(Pos p) {
            return ctx.world().isLoaded(p);
        }

        @Override
        public double collisionTop(Pos p) {
            BlockSpec b = ctx.world().block(p);
            return b.isAir() ? 0 : BlockStates.collisionTop(b);
        }

        @Override
        public boolean hasFluid(Pos p) {
            return ctx.world().hasFluid(p);
        }

        @Override
        public boolean hazard(Pos p) {
            return EscapeTask.HAZARDS.contains(ctx.world().block(p).id());
        }

        @Override
        public boolean allowed(Pos feet) {
            return zones.at(feet) == null;
        }
    }

    /**
     * The main hand holds the weapon to fight with (or a bare hand); false while a hotbar move is under way.
     * {@code attackOn} false: KillAura attacks nothing this tick (a creeper not to be hit now).
     */
    private boolean holdWeapon(boolean attackOn) {
        List<Weapons.Item> items = new ArrayList<>();
        for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) {
            ItemStack s = Inv.stack(i);
            if (Inv.empty(s) || Weapons.kind(Inv.id(s)) == 0) continue;
            items.add(new Weapons.Item(i, Inv.id(s), Wear.damageable(s) ? Wear.remaining(s) : Integer.MAX_VALUE,
                Wear.damageable(s)));
        }
        int slot = Weapons.pick(items, floor);
        String want = slot < 0 ? null : Inv.id(Inv.stack(slot));
        if (!java.util.Objects.equals(want, held)) {
            LOG.info("fight: holding {}", want == null ? "a bare hand (no sword or axe above " + floor + " uses)" : want);
            held = want;
        }
        if (slot < 0) {
            int bare = Hotbar.bareHandIndex();
            // every hotbar slot holds something that wears: no hit at all rather than with a tool
            KillAuraGuard.attack(bare >= 0 && attackOn);
            if (bare < 0) return true;
            return hotbar.ensureSlotHeld(ItemIds.HOTBAR_START + bare, Hotbar.TOOL_SLOT, now);
        }
        KillAuraGuard.attack(attackOn);
        if (slot == ItemIds.HOTBAR_START + Inv.heldSlot()) return true;
        if (ItemIds.hotbarIndex(slot) < 0) {
            // an inventory click: stand still this tick (no keys), then move it into the tool slot
            walker.stop();
            holdStill();
        }
        return hotbar.ensureSlotHeld(slot, Hotbar.TOOL_SLOT, now);
    }

    @Override
    protected void onRelease() {
        walker.pathCheck(null);
        KillAuraGuard.attack(true);
        if (aura != null) aura.release();
        aura = null;
    }
}
