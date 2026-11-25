package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.model.Pos;

import java.util.Set;

/**
 * After the colour-suppression ride: takes the cart off the brake so the next ride's cart does not run into it
 * (docs/COLOR_SUPPRESSION.md). Standing where it is, the bot looks at the minecart within {@link #RANGE} blocks of
 * {@code near} and attacks it (a vanilla left click: attack plus swing): with a sword, axe, pickaxe or shovel in hand
 * every {@link #HIT_EVERY} ticks (a full-strength hit or two breaks a cart), else bare-handed every
 * {@link #BARE_HAND_EVERY} ticks (the cart heals between hits), until the cart is gone, then waits for the
 * dropped minecart to be picked up. SUCCESS when no cart is there (nothing to do, or broken and the item picked up or
 * not seen within the wait); FAILED after {@link #HITS} hits with the cart still there.
 */
final class BreakCartTask extends ZenithTask {
    static final double RANGE = 2.5;
    static final int HIT_EVERY = 20;
    static final int HITS = 8;
    static final int BARE_HAND_EVERY = 5;
    static final int BARE_HAND_HITS = 24;
    /** Swords, axes, pickaxes and shovels of any tier: enough attack damage for a cart in one or two hits. */
    private static final java.util.function.Predicate<org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack> WEAPON =
        s -> {
            String id = Inv.id(s);
            return id != null && (id.endsWith("_sword") || id.endsWith("_axe") || id.endsWith("_pickaxe")
                || id.endsWith("_shovel"));
        };
    private static final int PICKUP_WAIT = 60;
    private static final Set<String> CARTS = Set.of("minecart");

    private final Pos near;
    private int hits;
    private long hitAt = -1000;
    private long goneAt = -1;
    private int cartsBefore = -1;
    private boolean aimed;
    private float aimYaw;
    private float aimPitch;

    BreakCartTask(ZenithBotActions ctx, Pos near) {
        super(ctx, 600);
        this.near = near;
    }

    @Override
    public String name() {
        return "breakCart near " + near;
    }

    /** It runs where the ride ended, inside the muncher's zone; it does not walk. */
    @Override
    protected boolean checksNoGoZones() {
        return false;
    }

    @Override
    protected TaskStatus step() {
        WorldView w = ctx.world();
        if (w.vehicle() != null) return fail("still in a vehicle");
        if (cartsBefore < 0) cartsBefore = Inv.count(Inv.is(BoardCartTask.CART_ITEM));
        WorldView.Thing cart = cart(w);
        if (cart == null) {
            holdStill();
            if (hits == 0) return TaskStatus.SUCCESS;
            if (goneAt < 0) goneAt = now;
            if (Inv.count(Inv.is(BoardCartTask.CART_ITEM)) > cartsBefore || now - goneAt > PICKUP_WAIT) return TaskStatus.SUCCESS;
            return TaskStatus.RUNNING;
        }
        // a weapon or tool breaks a cart in one or two full-strength hits; a bare hand needs a quick series (each hit adds
        // 10 damage, the cart heals 1 per tick, it breaks above 40), one hit per full attack cooldown (5 ticks)
        java.util.function.Predicate<org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack> weapon = WEAPON;
        boolean armed = Inv.find(weapon) >= 0;
        int every = armed ? HIT_EVERY : BARE_HAND_EVERY;
        int max = armed ? HITS : BARE_HAND_HITS;
        if (hits >= max && now - hitAt >= every) return fail("the minecart at " + where(cart) + " is still there after "
            + hits + " hits" + (armed ? "" : " with a bare hand"));
        if (armed && !hotbar.ensureHeld(weapon, Hotbar.TOOL_SLOT, now)) {
            holdStill();
            return TaskStatus.RUNNING;
        }
        double tx = cart.x(), ty = cart.y() + 0.35, tz = cart.z();
        float[] rot = Aim.rotationTo(Aiming.eyeX(), Aiming.eyeY(), Aiming.eyeZ(), tx, ty, tz);
        float pitch = Clicker.effectivePitch(rot[1]);
        boolean same = aimed && Math.abs(Aim.yawDelta(rot[0], aimYaw)) < 0.5 && Math.abs(pitch - aimPitch) < 0.5;
        aimYaw = same ? aimYaw : rot[0];
        aimPitch = same ? aimPitch : pitch;
        aimed = true;
        clicker.submit(now, aimYaw, aimPitch, null);
        if (!same || !clicker.canClick(now) || now - hitAt < every) return TaskStatus.RUNNING;
        double d = Math.sqrt(Aim.distSq(Aiming.eyeX(), Aiming.eyeY(), Aiming.eyeZ(), tx, ty, tz));
        if (d > 3.0) return fail("the minecart at " + where(cart) + " is " + Math.round(d * 10) / 10.0 + " blocks away");
        if (!BoardCartTask.attack(cart.id())) return fail("not connected");
        hits++;
        hitAt = now;
        return TaskStatus.RUNNING;
    }

    private WorldView.@Nullable Thing cart(WorldView w) {
        WorldView.Thing best = null;
        double bestD = RANGE * RANGE;
        for (WorldView.Thing t : w.entities(CARTS)) {
            double d = t.horizontalDistSq(near.x() + 0.5, near.z() + 0.5);
            if (d <= bestD && Math.abs(t.y() - near.y()) <= 2) {
                best = t;
                bestD = d;
            }
        }
        return best;
    }

    private static String where(WorldView.Thing t) {
        return (int) Math.floor(t.x()) + "," + (int) Math.floor(t.y()) + "," + (int) Math.floor(t.z());
    }
}
