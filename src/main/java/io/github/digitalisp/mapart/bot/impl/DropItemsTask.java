package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.DropItem;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.zenith.Globals.BOT;
import static com.zenith.Globals.INVENTORY;

/**
 * Gets rid of leftovers without a chest: walk onto {@code standAt}, turn to the aim {@link ThrowPlanner} computes
 * for {@code target} (items must land in it, e.g. a water channel feeding the sorter), hold still, then drop whole
 * stacks (player-inventory THROW clicks, {@link #DROP_INTERVAL} ticks apart) while keeping the rotation. Never drops
 * config keepItems, maps, filled maps, glass panes, XP bottles or {@link Valuables}, and only from the 36 hotbar + main
 * slots (never the offhand or armour, {@link OffhandGuard}). The engine passes only materials as {@code which}.
 * Reports what left the inventory.
 */
final class DropItemsTask extends ZenithTask {
    static final int DROP_INTERVAL = 4;
    private static final Set<String> NEVER = Set.of("minecraft:map", "minecraft:filled_map", "minecraft:glass_pane",
        "minecraft:experience_bottle");

    enum Phase { WALK, AIM, DROP, SETTLE }

    private final Pos standAt;
    private final Pos target;
    /** Blocks (horizontally) within which the walk to {@link #standAt} is done: 0 = the cell itself (the drop point). */
    private final int walkRange;
    private final Predicate<String> which;
    private final Map<String, Integer> keep;
    private final Consumer<Map<String, Integer>> dropped;
    private Phase phase = Phase.WALK;
    private long phaseAt;
    private ThrowPlanner.@Nullable Aim aim;
    private int steadyTicks;
    private @Nullable RequestFuture request;
    private long requestDeadline;
    private Map<String, Integer> before = Map.of();
    private int stacks;

    DropItemsTask(ZenithBotActions ctx, Pos standAt, Pos target, Predicate<String> which, Map<String, Integer> keep,
                  Consumer<Map<String, Integer>> dropped) {
        this(ctx, standAt, target, which, keep, dropped, 0);
    }

    DropItemsTask(ZenithBotActions ctx, Pos standAt, Pos target, Predicate<String> which, Map<String, Integer> keep,
                  Consumer<Map<String, Integer>> dropped, int walkRange) {
        super(ctx, WalkTask.walkTimeout(standAt) + 1200);
        this.walkRange = walkRange;
        this.standAt = standAt;
        this.target = target;
        this.which = which;
        this.keep = keep == null ? Map.of() : keep;
        this.dropped = dropped;
    }

    @Override
    public String name() {
        return "dropItems at " + standAt + " into " + target;
    }

    /** The walk to the drop point: plain, and a restock leg in a restock ({@link SprintLegs.Walk#DROP_POINT}). */
    @Override
    protected SprintLegs.Walk walkKind() {
        return SprintLegs.Walk.DROP_POINT;
    }

    @Override
    protected String progress() {
        return " (phase " + phase + ", " + stacks + " stacks)";
    }

    @Override
    protected TaskStatus step() {
        switch (phase) {
            case WALK -> {
                if (plan().isEmpty()) {
                    dropped.accept(Map.of());
                    return TaskStatus.SUCCESS;
                }
                TaskStatus s = walkNear(standAt, walkRange, 1);
                if (s == TaskStatus.FAILED) return fail("walking to " + standAt + ": " + walker.error());
                if (s == TaskStatus.RUNNING) return s;
                walker.stop();
                enter(Phase.AIM);
                holdStill();
                return TaskStatus.RUNNING;
            }
            case AIM -> {
                if (aim == null) {
                    if (now - phaseAt < 5) { // let the walk settle
                        holdStill();
                        return TaskStatus.RUNNING;
                    }
                    var world = ctx.world();
                    if (target.y() >= standAt.y() - 1) {
                        // a point out in the open, level with the stand (the junk spot over the ocean): face it and throw,
                        // nothing has to land in a block
                        double dx = target.x() + 0.5 - BOT.getX();
                        double dz = target.z() + 0.5 - BOT.getZ();
                        double dy = target.y() + 0.5 - BOT.getEyeY();
                        aim = new ThrowPlanner.Aim((float) Math.toDegrees(Math.atan2(-dx, dz)),
                            (float) Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz))), 0);
                        LOG.info("Drop aim at {}: yaw {} pitch {} (level throw)", target, aim.yaw(), aim.pitch());
                        clicker.submit(now, aim.yaw(), aim.pitch(), null);
                        return TaskStatus.RUNNING;
                    }
                    if (!world.isLoaded(target)) return fail("drop target " + target + " is not loaded");
                    aim = ThrowPlanner.plan(BOT.getX(), BOT.getEyeY(), BOT.getZ(), target, new ThrowPlanner.Cells() {
                        @Override
                        public boolean solid(int x, int y, int z) {
                            BlockSpec b = world.block(new Pos(x, y, z));
                            return !b.isAir() && BlockStates.collisionTop(b) > 0;
                        }

                        @Override
                        public boolean water(int x, int y, int z) {
                            Pos p = new Pos(x, y, z);
                            return world.hasFluid(p) && !world.block(p).id().equals("minecraft:lava");
                        }
                    });
                    if (aim == null) return fail("no throw from " + standAt + " lands in " + target);
                    LOG.info("Drop aim at {}: yaw {} pitch {} (worst miss {})", target, aim.yaw(), aim.pitch(),
                        String.format("%.2f", aim.miss()));
                }
                clicker.submit(now, aim.yaw(), aim.pitch(), null);
                boolean steady = Math.abs(wrap(BOT.getYaw() - aim.yaw())) < 0.5f && Math.abs(BOT.getPitch() - aim.pitch()) < 0.5f;
                steadyTicks = steady ? steadyTicks + 1 : 0;
                if (steadyTicks >= 3) enter(Phase.DROP);
                else if (now - phaseAt > 100) return fail("could not turn to the drop aim");
                return TaskStatus.RUNNING;
            }
            case DROP -> {
                clicker.submit(now, aim.yaw(), aim.pitch(), null);
                if (request == null) {
                    if (Inv.containerOpen()) {
                        closeContainerNow();
                        return TaskStatus.RUNNING;
                    }
                    if (INVENTORY.hasActiveRequest()) {
                        if (now - phaseAt > 200) return fail("inventory manager busy");
                        return TaskStatus.RUNNING;
                    }
                    List<TransferPlanner.Stack> clicks = plan();
                    if (clicks.isEmpty()) {
                        enter(Phase.SETTLE);
                        return TaskStatus.RUNNING;
                    }
                    before = counts();
                    List<InventoryAction> actions = new ArrayList<>();
                    for (TransferPlanner.Stack s : clicks) {
                        if (OffhandGuard.movable(s.slot())) actions.add(new DropItem(s.slot(), DropItemAction.DROP_SELECTED_STACK));
                    }
                    if (actions.isEmpty()) {
                        enter(Phase.SETTLE);
                        return TaskStatus.RUNNING;
                    }
                    RequestFuture f = INVENTORY.submit(InventoryActionRequest.builder()
                        .owner(this)
                        .priority(Clicker.PRIORITY)
                        .actionDelayTicks(DROP_INTERVAL)
                        .actions(actions)
                        .build());
                    if (f == RequestFuture.rejected) {
                        if (now - phaseAt > 200) return fail("inventory request rejected (higher priority module busy)");
                        return TaskStatus.RUNNING;
                    }
                    request = f;
                    stacks = clicks.size();
                    requestDeadline = now + (long) clicks.size() * DROP_INTERVAL + 100;
                    return TaskStatus.RUNNING;
                }
                if (request.isCompleted() || now > requestDeadline) enter(Phase.SETTLE);
                return TaskStatus.RUNNING;
            }
            case SETTLE -> {
                clicker.submit(now, aim.yaw(), aim.pitch(), null);
                if (now - phaseAt < 5) return TaskStatus.RUNNING;
                Map<String, Integer> after = counts();
                Map<String, Integer> out = new TreeMap<>();
                before.forEach((id, n) -> {
                    int d = n - after.getOrDefault(id, 0);
                    if (d > 0) out.put(id, d);
                });
                LOG.info("Dropped {} into {}", out, target);
                dropped.accept(out);
                if (request != null && !request.isAccepted() && out.isEmpty()) return fail("drop request was replaced");
                return TaskStatus.SUCCESS;
            }
        }
        return fail("bad phase " + phase);
    }

    /** Whole stacks to drop (player-inventory slot numbers), keeping {@code keep}. */
    private List<TransferPlanner.Stack> plan() {
        Set<String> never = new HashSet<>(NEVER);
        for (String k : cfg.build.keepItems) never.add(ItemIds.ns(k));
        Map<String, Integer> keepNs = new TreeMap<>();
        keep.forEach((k, v) -> keepNs.merge(ItemIds.ns(k), v == null ? 0 : v, Integer::sum));
        Predicate<String> mayDrop = id -> which.test(id) && (!Valuables.isValuable(id) || Valuables.junkWhenPlain(id));
        // a bow is junk only plain: an enchanted one is never dropped (a pillager's crossbow goes either way)
        var stacks = Inv.playerStacks(0, s -> Valuables.keptWhenEnchanted(Inv.id(s)) && Inv.enchanted(s));
        var p = TransferPlanner.planDeposit(stacks, mayDrop, keepNs, never, List.of(), 36);
        return p.clicks();
    }

    private static Map<String, Integer> counts() {
        Map<String, Integer> m = new TreeMap<>();
        for (int i = ItemIds.MAIN_START; i <= ItemIds.OFFHAND; i++) {
            var s = Inv.stack(i);
            String id = Inv.id(s);
            if (id != null) m.merge(id, s.getAmount(), Integer::sum);
        }
        return m;
    }

    private void enter(Phase p) {
        phase = p;
        phaseAt = now;
    }

    private static float wrap(float deg) {
        float d = deg % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
