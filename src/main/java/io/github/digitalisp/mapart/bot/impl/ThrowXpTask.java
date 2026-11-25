package io.github.digitalisp.mapart.bot.impl;

import com.zenith.cache.data.entity.Entity;
import com.zenith.feature.player.ClickResult;
import com.zenith.feature.player.InputRequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;

import java.util.function.Predicate;

import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;

/**
 * Throws bottles o' enchanting straight down (pitch 90) with the main hand, one use at a time through ZenithProxy's
 * Bot, standing still.
 *
 * <p><b>Plain</b> ({@link #ThrowXpTask(ZenithBotActions, int)}, boots and the rename level): one use every
 * {@link #INTERVAL} ticks, then waits until no XP orb is near the bot (at most {@link #ORB_WAIT} ticks after the last
 * throw). Mending repairs whatever damaged Mending item is equipped (boots).
 *
 * <p><b>Mend a tool</b> ({@link #mend}): Mending only repairs an equipped item (main hand, offhand, armour), so the
 * worn tool must be in the main hand when the orbs are picked up. Per bottle: hold the bottles (hotbar 7), throw, and
 * on the very next tick select the tool (hotbar 8; a slot change after a use in the same tick is Grim PacketOrderE).
 * A bottle thrown at pitch 90 from the eye needs about 2 server ticks to reach the ground and break into orbs, so the
 * tool is in hand before the orbs exist. Then hold still with the tool until the orbs are gone, and repeat until the
 * tool is at the target or the budget/bottles run out. The offhand is never touched: ZenithProxy's AutoTotem keeps
 * forcing a totem into it (health threshold 20 by default), so bottles there would be swapped out mid-mend.
 */
final class ThrowXpTask extends ZenithTask {
    static final String BOTTLE = "minecraft:experience_bottle";
    static final int INTERVAL = 4;
    static final int ORB_WAIT = 60;
    /** Mend: least ticks between a throw and the next bottle (the orbs need ~2 ticks to appear, then 2 ticks each). */
    static final int MEND_MIN_TICKS = 6;
    /** Mend: give up waiting for the orbs of one bottle after this many ticks. */
    static final int MEND_ORB_WAIT = 40;
    /** Mend: still ticks before the first slot change or throw. */
    static final int SETTLE_TICKS = 3;

    private enum Step { SETTLE, BOTTLE, THROWN, TOOL, ORBS, DONE }

    private final int bottles;
    private final @Nullable String toolId;
    private final double target;
    private int thrown;
    private long lastThrow = Long.MIN_VALUE / 4;
    private @Nullable InputRequestFuture useFuture;
    private boolean throwing = true;
    private Step step = Step.SETTLE;
    private int stillTicks;
    private int useTries;
    private long useSentAt;

    ThrowXpTask(ZenithBotActions ctx, int bottles) {
        this(ctx, bottles, null, 1.0, 600L + Math.max(0, bottles) * (INTERVAL * 3L) + ORB_WAIT);
    }

    private ThrowXpTask(ZenithBotActions ctx, int bottles, @Nullable String toolId, double target, long timeout) {
        super(ctx, timeout);
        this.bottles = Math.max(0, bottles);
        this.toolId = toolId == null ? null : ItemIds.ns(toolId);
        this.target = target;
    }

    /** Mend {@code toolId} (Mending) up to {@code target} of its max durability with at most {@code bottles} bottles. */
    static ThrowXpTask mend(ZenithBotActions ctx, String toolId, int bottles, double target) {
        long timeout = 600L + Math.max(0, bottles) * (long) (MEND_ORB_WAIT + 20);
        return new ThrowXpTask(ctx, bottles, toolId, Math.max(0.0, Math.min(1.0, target)), timeout);
    }

    @Override
    public String name() {
        return toolId == null ? "throwXp " + bottles : "mendTool " + toolId + " " + bottles;
    }

    @Override
    protected String progress() {
        return " (" + thrown + "/" + bottles + " thrown" + (toolId != null ? ", step " + step : "") + ")";
    }

    @Override
    protected TaskStatus step() {
        return toolId == null ? plainStep() : mendStep();
    }

    private TaskStatus plainStep() {
        float yaw = BOT.getYaw();
        if (useFuture != null && useFuture.isCompleted()) {
            if (usedItem(useFuture)) {
                thrown++;
                lastThrow = now - 1;
            }
            useFuture = null;
        }
        if (throwing) {
            if (thrown >= bottles) {
                throwing = false;
            } else if (Inv.count(Inv.is(BOTTLE)) == 0) {
                if (thrown == 0 && bottles > 0) return fail("no experience bottles in inventory");
                throwing = false;
            }
        }
        if (throwing) {
            boolean holding = hotbar.ensureHeld(Inv.is(BOTTLE), Hotbar.SPECIAL_SLOT, now);
            if (holding && useFuture == null && now - lastThrow >= INTERVAL && clicker.ownedLastTick(now)) {
                useFuture = clicker.submitUseItem(now, yaw, 90f);
            } else {
                clicker.submit(now, yaw, 90f, null);
            }
            return TaskStatus.RUNNING;
        }
        clicker.submit(now, yaw, 90f, null);
        if (now - lastThrow >= ORB_WAIT || (now - lastThrow > 10 && !orbsNearby())) return TaskStatus.SUCCESS;
        return TaskStatus.RUNNING;
    }

    /** A carried {@code toolId} with Mending below the target: the stacks this task repairs. */
    private Predicate<ItemStack> worn() {
        Predicate<ItemStack> is = Inv.is(toolId);
        return s -> is.test(s) && Wear.damageable(s) && Wear.mending(s) && Wear.fraction(s) < target;
    }

    private TaskStatus mendStep() {
        float yaw = BOT.getYaw();
        Predicate<ItemStack> bottle = Inv.is(BOTTLE);
        Predicate<ItemStack> tool = worn();
        switch (step) {
            case SETTLE -> {
                clicker.submit(now, yaw, 90f, null);
                stillTicks = clicker.stillLastTick(now) ? stillTicks + 1 : 0;
                if (Inv.find(tool) < 0) return fail("no " + toolId + " with Mending below " + Math.round(target * 100) + "%");
                if (Inv.count(bottle) == 0) return fail("no experience bottles in inventory");
                // the tool goes to hotbar 8 first, so the switch after each throw is a plain slot change
                if (stillTicks >= SETTLE_TICKS && hotbar.ensureHeld(tool, Hotbar.TOOL_SLOT, now)) step = Step.BOTTLE;
            }
            case BOTTLE -> {
                if (thrown >= bottles || Inv.count(bottle) == 0 || Inv.find(tool) < 0) {
                    step = Step.DONE;
                    clicker.submit(now, yaw, 90f, null);
                    return thrown > 0 || Inv.find(tool) < 0 ? TaskStatus.SUCCESS : fail("no experience bottles in inventory");
                }
                if (Hotbar.needsClick(tool)) {
                    // the next worn stack is in the main inventory: into hotbar 8 now, so the switch after the
                    // throw is a plain slot change
                    clicker.submit(now, yaw, 90f, null);
                    hotbar.ensureHeld(tool, Hotbar.TOOL_SLOT, now);
                    return TaskStatus.RUNNING;
                }
                boolean holding = hotbar.ensureHeld(bottle, Hotbar.SPECIAL_SLOT, now);
                boolean clear = !orbsNearby() || now - lastThrow >= MEND_ORB_WAIT;
                if (holding && clear && clicker.ownedLastTick(now)) {
                    useFuture = clicker.submitUseItem(now, yaw, 90f);
                    useSentAt = now;
                    step = Step.THROWN;
                } else {
                    clicker.submit(now, yaw, 90f, null);
                }
            }
            case THROWN -> {
                clicker.submit(now, yaw, 90f, null);
                boolean used = false;
                if (useFuture != null) {
                    if (!useFuture.isCompleted() && now - useSentAt < 10) return TaskStatus.RUNNING;
                    used = useFuture.isCompleted() && usedItem(useFuture);
                }
                useFuture = null;
                if (!used) {
                    if (++useTries > 20) return fail("the bottle could not be thrown (input not executed)");
                    step = Step.BOTTLE;
                    return TaskStatus.RUNNING;
                }
                thrown++;
                lastThrow = now - 1;
                step = Step.TOOL;
                // the next tick: tool into the main hand before the bottle breaks into orbs
                if (Inv.find(tool) >= 0) hotbar.ensureHeld(tool, Hotbar.TOOL_SLOT, now);
            }
            case TOOL -> {
                clicker.submit(now, yaw, 90f, null);
                if (Inv.find(tool) < 0 || hotbar.ensureHeld(tool, Hotbar.TOOL_SLOT, now)) step = Step.ORBS;
                else if (now - lastThrow > MEND_ORB_WAIT) step = Step.ORBS;
            }
            case ORBS -> {
                clicker.submit(now, yaw, 90f, null);
                long since = now - lastThrow;
                if (since >= MEND_ORB_WAIT || (since >= MEND_MIN_TICKS && !orbsNearby())) step = Step.BOTTLE;
            }
            case DONE -> {
                clicker.submit(now, yaw, 90f, null);
                return TaskStatus.SUCCESS;
            }
        }
        return TaskStatus.RUNNING;
    }

    private static boolean usedItem(InputRequestFuture f) {
        return f.getNow() && f.getClickResult() instanceof ClickResult.RightClickResult r
            && r.getType() == ClickResult.RightClickResult.RightClickType.USE_ITEM;
    }

    private static boolean orbsNearby() {
        for (Entity e : CACHE.getEntityCache().getEntities().values()) {
            if (e.getEntityType() != EntityType.EXPERIENCE_ORB || e.isRemoved()) continue;
            double dx = e.getX() - BOT.getX();
            double dy = e.getY() - BOT.getY();
            double dz = e.getZ() - BOT.getZ();
            if (dx * dx + dy * dy + dz * dz < 8 * 8) return true;
        }
        return false;
    }
}
