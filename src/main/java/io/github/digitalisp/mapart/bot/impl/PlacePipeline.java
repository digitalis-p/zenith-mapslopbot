package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-tick decision of the pipelined lane placer. Pure: everything that
 * touches ZenithProxy goes through {@link Env}, so the tick sequence is unit-tested with fakes.
 *
 * <p><b>Pipeline.</b> In the tick that clicks target k (aimed on the previous tick) the rotation for target k+1 is
 * requested as well, so one placement completes per tick:
 * <pre>
 *   tick N    our handler: aim k              Bot: Move(rot k)
 *   tick N+1  our handler: [select] click k, aim k+1   Bot: Move(rot k+1)
 * </pre>
 * The engine ticks at ClientBotTick priority 0, before ZenithProxy's InputManager (-10000) and Bot (-20000), so on
 * the wire the click precedes the movement packet that carries the next rotation. Grim 2.3.73 accepts that:
 * RotationPlace's pre check (at the use-item-on, only after a recent flag) raytraces with the rotation of the last
 * flying packet (rot k); the post check (at the next flying packet) tries (yaw, pitch), (lastYaw, pitch) and, for
 * 1.9+ clients, (lastYaw, lastPitch), and {@code CheckManagerListener.handleFlying} sets lastYaw/lastPitch to rot k
 * before it handles the queued place; both use the position of the flying packet before the click.
 *
 * <p><b>Rules.</b> At most one click per tick. A click needs: the target aimed on the previous tick, that tick's
 * input executed ({@link Env#ownedLastTick}), the rotation in effect still hitting the target
 * ({@link Env#recheck}), its item in the hotbar, the pacer ready and no inventory request in flight. A hotbar slot
 * change is sent directly before the click ({@link Env#select}), never after it. While the pacer waits (interval 2+)
 * or an item move is in flight the aim is held (no new rotation): aim-only ticks. A target whose item is only in the
 * main inventory is reported as {@link Plan#inventoryMove}; the caller moves it in a later tick without a click.
 *
 * <p><b>Airplace targets</b> ({@link Env#method} {@link PlaceMethod#AIRPLACE}) need no aim: in a tick without a LOOK
 * click, the first ranked airplace target whose item is in the hotbar is
 * placed at once ({@link Env#airPlace}: offhand swap, use-item-on the cell itself, swing, swap back), after the slot
 * change if any. They are never aimed, so a lane of airplace targets sends no rotation at all. LOOK targets in the same
 * lane are aimed and clicked as above; still at most one placement per tick.
 */
public final class PlacePipeline<T, S extends PlacePipeline.Rotation> {
    /** Fresh aims (raycasts) tried per tick before giving up for this tick. */
    static final int MAX_AIMS = 6;

    /** The rotation part of a shot. */
    public interface Rotation {
        float yaw();

        float pitch();
    }

    /** World access for one tick (ZenithProxy-backed in {@link PlaceLaneTask}, fakes in tests). */
    interface Env<T, S> {
        /** A fresh shot at the target's support top face from the eye now (line of sight and reach), or null. */
        @Nullable S aim(T target);

        /** The rotation in effect now (sent with the last movement packet) still hits the target, or null. */
        @Nullable S recheck(T target);

        /** Namespaced item id the target needs. */
        String item(T target);

        /** Namespaced item id in the main hand, or null. */
        @Nullable String heldItem();

        /** Selected hotbar index 0-8. */
        int heldSlot();

        /** Hotbar index 0-8 holding {@code item}, or -1 when it is not in the hotbar. */
        int hotbarSlot(String item);

        /** The input request of the previous tick was accepted and executed, and no container is open. */
        boolean ownedLastTick();

        /** An inventory request is queued or executing: ZenithProxy would send it after our click this tick. */
        boolean inventoryBusy();

        /** The adaptive pacer allows a click now. */
        boolean pacerReady();

        /** After this tick's input submit: it was accepted and the previous one executed. */
        boolean canClick();

        /** Sends the hotbar slot change now, before the click. True if {@code slot} is selected afterwards. */
        boolean select(int slot);

        /** Sends the use-item-on (and swing) for the target. True if sent. */
        boolean click(T target, S shot);

        /** How the target is placed this tick: AIRPLACE (no aim) or LOOK (aimed on the previous tick). */
        PlaceMethod method(T target);

        /** Sends the airplace for the target with the selected hotbar item ({@link AirPlace}). True if sent. */
        boolean airPlace(T target);
    }

    /**
     * One tick's decision. {@code click}: target to click this tick (with {@code clickShot}, after selecting
     * {@code select} unless it is -1). {@code next}: target the rotation points at after this tick;
     * {@code nextShot} is the rotation to request, null to keep the current one (hold, or no target).
     * {@code inventoryMove}: item that must first be moved from the main inventory into the hotbar.
     * {@code air}: the click is an airplace ({@code clickShot} null).
     */
    record Plan<T, S>(@Nullable T click, @Nullable S clickShot, int select, @Nullable T next, @Nullable S nextShot,
                      @Nullable String inventoryMove, boolean air) { }

    private final boolean aimInClickTick;
    private @Nullable T aimed;
    private long aimedAt = Long.MIN_VALUE / 4;

    PlacePipeline() {
        this(true);
    }

    /**
     * {@code aimInClickTick} false: the previous, unpipelined cadence: the click tick keeps the rotation and the next
     * target is aimed on the following tick (two ticks per placement). A fallback, see {@code build.pipelinePlacement}.
     */
    PlacePipeline(boolean aimInClickTick) {
        this.aimInClickTick = aimInClickTick;
    }

    /** Target the last requested rotation points at (null if none). */
    @Nullable T aimed() {
        return aimed;
    }

    /**
     * Decides this tick. {@code candidates}: TODO placements that can be placed now and are in reach, in lane order
     * (the pipeline only re-orders them by item: held item first, then hotbar, then main inventory; stable).
     */
    Plan<T, S> plan(long now, List<T> candidates, Env<T, S> env) {
        T click = null;
        S clickShot = null;
        int select = -1;
        String move = null;
        boolean hold = false;
        String heldAfter = env.heldItem();

        T live = aimed != null && aimedAt == now - 1 && candidates.contains(aimed) && env.ownedLastTick() ? aimed : null;
        S liveShot = live == null ? null : env.recheck(live);
        if (liveShot != null) {
            String item = env.item(live);
            int slot = item.equals(heldAfter) ? env.heldSlot() : env.hotbarSlot(item);
            if (slot >= 0 && env.pacerReady() && !env.inventoryBusy()) {
                click = live;
                clickShot = liveShot;
                select = slot == env.heldSlot() ? -1 : slot;
                heldAfter = item;
            } else {
                hold = true; // aim-only tick: pacer gap, item move in flight, or the item must reach the hotbar first
                if (slot < 0) move = item;
            }
        }

        boolean air = false;
        if (click == null && !hold) {
            // airplace: no aim needed, the first ranked target whose item is in the hotbar goes now
            for (T t : rank(candidates, heldAfter, env)) {
                if (env.method(t) != PlaceMethod.AIRPLACE) continue;
                String item = env.item(t);
                int slot = item.equals(heldAfter) ? env.heldSlot() : env.hotbarSlot(item);
                if (slot < 0) {
                    move = item; // ranked by item: no airplace target after it has its item in the hotbar either
                } else if (env.pacerReady() && !env.inventoryBusy()) {
                    click = t;
                    air = true;
                    select = slot == env.heldSlot() ? -1 : slot;
                    heldAfter = item;
                }
                break;
            }
        }

        T next = null;
        S nextShot = null;
        if (hold) {
            next = live;
        } else if (click == null || aimInClickTick) {
            int aims = 0;
            for (T t : rank(candidates, heldAfter, env)) {
                if (t == click || env.method(t) == PlaceMethod.AIRPLACE) continue;
                if (aims++ >= MAX_AIMS) break;
                S s = env.aim(t);
                if (s == null) continue;
                next = t;
                nextShot = s;
                break;
            }
            if (next != null) {
                String item = env.item(next);
                if (!item.equals(heldAfter) && env.hotbarSlot(item) < 0) move = item;
            }
        }
        return new Plan<>(click, clickShot, select, next, nextShot, move, air);
    }

    /**
     * Runs the plan after this tick's input was submitted: at most one click (or airplace), the slot change (if any)
     * directly before it. Returns true if a use-item-on was sent. A failed click leaves its target to be aimed again; the
     * rotation for {@code next} was requested either way.
     */
    boolean execute(long now, Plan<T, S> plan, Env<T, S> env) {
        boolean clicked = false;
        if (plan.click() != null && env.canClick()) {
            if (plan.select() < 0 || env.select(plan.select())) {
                clicked = plan.air() ? env.airPlace(plan.click()) : env.click(plan.click(), plan.clickShot());
            }
        }
        aimed = plan.next();
        aimedAt = now;
        return clicked;
    }

    /** Forgets the aim (the next tick aims afresh). */
    void reset() {
        aimed = null;
        aimedAt = Long.MIN_VALUE / 4;
    }

    /** Stable re-order: 0 = {@code held}, 1 = elsewhere in the hotbar, 2 = main inventory or missing. */
    private List<T> rank(List<T> candidates, @Nullable String held, Env<T, S> env) {
        Map<String, Integer> rank = new HashMap<>();
        List<T> out = new ArrayList<>(candidates);
        out.sort(Comparator.comparingInt(t -> rank.computeIfAbsent(env.item(t),
            id -> id.equals(held) ? 0 : env.hotbarSlot(id) >= 0 ? 1 : 2)));
        return out;
    }
}
