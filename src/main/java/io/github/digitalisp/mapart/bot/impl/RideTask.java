package io.github.digitalisp.mapart.bot.impl;

import com.zenith.Proxy;
import com.zenith.network.client.ClientSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.BotActions;
import io.github.digitalisp.mapart.bot.RideWatch;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.model.Pos;

/**
 * The colour-suppression ride itself (docs/COLOR_SUPPRESSION.md), from the seat of the cart on the start rail to
 * the brake in the booth: map {@code mapId} in the main hand the whole way (an unlocked map updates only there or in
 * the offhand), then, with {@code button} non-null, one press of the launch button from the seat (rotation on one
 * tick, use-item-on with the hit point on the next; confirmed by the button showing pressed), then the push: when the
 * cart has not started by itself within {@link #START_WAIT} ticks, forward is held ({@link RideInput}) looking along
 * {@code pushYaw} until it moved a block. From then on the task only keeps the map in hand, owns the input with a still
 * request every tick (no other module turns the bot, jumps or sneaks: sneaking would get it out), swings the arm every
 * {@link RideWatch#swingTicks} ticks, and watches: out of the cart, health below {@link RideWatch#minHealth}, the cart
 * not moving for {@link RideWatch#stallTicks} or the ride longer than {@link RideWatch#maxTicks} each FAIL with the
 * reason and where; the engine goes BLOCKED on any failure (no retry). SUCCESS once the cart stands still within
 * {@link #BRAKE_RANGE} blocks of {@code brake} for {@link #ARRIVED_TICKS} ticks. Never parks (no meal mid-ride).
 *
 * <p>{@link BotActions.RideOptions}: with {@code stop} the task asks it every tick once the cart has stood still for
 * {@link #STOP_STILL_TICKS} and ends with SUCCESS, seated, when it says so (a ride paused at a lane end for a server
 * restart). With {@code resumePushYaw} and no button the cart was put back on a lane by the bot: the push comes first,
 * forward held along that yaw after {@link #RESUME_WAIT} ticks until the cart moved a block.
 *
 * <p>The preload lap ({@code BotActions.preloadRide}, {@code mapId} null) is the same task with three differences: no
 * filled map is in the main hand (a slot without one is selected before anything else, so the map does not update on
 * the lap); with {@code pushYaw} null forward is never pressed and a cart that has not moved a block
 * {@link #START_WAIT} + {@link #PUSH_TICKS} ticks after the press fails the task; and with {@code button} null (pressed
 * before a restart) a cart already standing still at {@code brake} is the lap's end. A loop's {@code brake} is its start
 * rail: the arrival only counts after the cart moved a block away ({@link Step#START}). The lap's button may be all but
 * hidden from the seat (the owner's: behind the block it is on), so its press falls back to {@link Aiming#anyFaceFine}.
 */
final class RideTask extends ZenithTask {
    /** Ticks the cart is given to start by itself after the press before forward is pressed. */
    static final int START_WAIT = 60;
    /** Forward is held at most this long; the cart not having moved a block by then fails the task. */
    static final int PUSH_TICKS = 200;
    static final double BRAKE_RANGE = 1.5;
    static final int ARRIVED_TICKS = 40;
    /** The cart must have stood still this long before {@link BotActions.RideStop} is asked. */
    static final int STOP_STILL_TICKS = 10;
    /** Ticks a cart put back on a lane is given before forward is pressed. */
    static final int RESUME_WAIT = 20;
    private static final int PRESS_WAIT = 20;
    private static final int PRESS_TRIES = 3;

    private enum Step { HOLD, PRESS, START, RIDE }

    private final @Nullable Pos button;
    private final Pos brake;
    private final @Nullable Integer mapId;
    private final @Nullable Float pushYaw;
    private final RideWatch watch;
    private final Runnable pressedCallback;
    private final BotActions.@Nullable RideStop stop;
    /** The push of a cart put back on a lane (no button, the map in hand); null = none. */
    private final @Nullable Float resumePushYaw;
    private Step step = Step.HOLD;
    private int pressClicks;
    private long pressedAt = -1;
    private boolean aimed;
    private float aimYaw;
    private float aimPitch;
    private double startX = Double.NaN;
    private double startZ;
    private long startStepAt;
    private long pushSince = -1;
    private double lastX = Double.NaN;
    private double lastY;
    private double lastZ;
    private long lastMoveAt;
    private long stillSince = -1;
    private long lastSwing;
    private float startHealth = -1;
    /** Preload lap: the farthest the cart has been from its end rail (horizontal blocks). */
    private double farthest;
    /** Preload lap on a loop: the cart this far from its start rail once has been round the lap. */
    static final double LAP_AWAY = 16.0;

    RideTask(ZenithBotActions ctx, @Nullable Pos button, Pos brake, @Nullable Integer mapId, @Nullable Float pushYaw,
             RideWatch watch, Runnable pressed) {
        this(ctx, button, brake, mapId, pushYaw, watch, pressed, null);
    }

    RideTask(ZenithBotActions ctx, @Nullable Pos button, Pos brake, @Nullable Integer mapId, @Nullable Float pushYaw,
             RideWatch watch, Runnable pressed, BotActions.@Nullable RideOptions opts) {
        super(ctx, Math.max(1, watch.maxTicks()) + 2400L);
        this.stop = opts == null ? null : opts.stop();
        this.resumePushYaw = opts == null || button != null || mapId == null ? null : opts.resumePushYaw();
        this.pressedCallback = pressed;
        this.button = button;
        this.brake = brake;
        this.mapId = mapId;
        this.pushYaw = pushYaw;
        this.watch = watch;
    }

    @Override
    public String name() {
        return (mapId == null ? "preload lap to " : "ride to ") + brake;
    }

    @Override
    protected String progress() {
        WorldView.Thing v = ctx.world().vehicle();
        return " (" + step + (v == null ? "" : String.format(java.util.Locale.ROOT, " at %.1f,%.1f,%.1f", v.x(), v.y(), v.z()))
            + ", still for " + (now - lastMoveAt) + " ticks)";
    }

    /** The bot rides through the muncher's no-go zone in the cart; the cart, not the bot, moves it. */
    @Override
    protected boolean checksNoGoZones() {
        return false;
    }

    @Override
    public boolean requestPark() {
        return false;
    }

    @Override
    protected void onRelease() {
        RideInput.forward(false);
    }

    @Override
    protected TaskStatus step() {
        WorldView w = ctx.world();
        WorldView.Thing cart = w.vehicle();
        if (cart == null) {
            RideInput.forward(false);
            return fail("not in the cart (at " + Walker.feet().key() + ", " + step + ")");
        }
        float health = ctx.inventory().health();
        if (startHealth < 0) startHealth = health;
        if (health < watch.minHealth()) {
            RideInput.forward(false);
            return fail("health fell to " + Math.round(health) + " in the cart at " + where(cart) + " (it was "
                + Math.round(startHealth) + " at the start)");
        }
        if (Double.isNaN(lastX)) {
            lastX = cart.x();
            lastY = cart.y();
            lastZ = cart.z();
            lastMoveAt = now;
            startX = cart.x();
            startZ = cart.z();
        }
        boolean moved = Math.abs(cart.x() - lastX) > 0.05 || Math.abs(cart.y() - lastY) > 0.05
            || Math.abs(cart.z() - lastZ) > 0.05;
        if (moved) {
            lastX = cart.x();
            lastY = cart.y();
            lastZ = cart.z();
            lastMoveAt = now;
            stillSince = -1;
        }
        if (mapId == null) {
            // the preload lap: no filled map in the hand (it would update on the way)
            if (Inv.mapId(Inv.mainHand()) != null) {
                int slot = LeaveCartTask.mapFreeSlot();
                if (slot < 0) {
                    RideInput.forward(false);
                    return fail("every hotbar slot holds a filled map: none to put in the hand for the preload lap");
                }
                holdStill();
                Clicker.selectNow(slot);
                return TaskStatus.RUNNING;
            }
        } else if (!hotbar.ensureHeld(Inv.isMap(mapId), Hotbar.SPECIAL_SLOT, now)) {
            if (Inv.find(Inv.isMap(mapId)) < 0) {
                RideInput.forward(false);
                return fail("map " + mapId + " is not in the inventory any more");
            }
            holdStill();
            return TaskStatus.RUNNING;
        }
        switch (step) {
            case HOLD -> {
                holdStill();
                // the main ride launched before (the bot got back into its cart mid-ride, or a restart in the cart): the
                // machines move the cart, so no push and no start limit; a cart held at a lane entry is the ride
                // (unless the bot itself put the cart back on a lane: then the push first)
                step = button != null ? Step.PRESS : mapId != null && resumePushYaw == null ? Step.RIDE : Step.START;
                startStepAt = now;
                return TaskStatus.RUNNING;
            }
            case PRESS -> {
                return press();
            }
            case START -> {
                return start(cart);
            }
            case RIDE -> {
                return ride(cart);
            }
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus press() {
        Pos b = button;
        if (b == null) {
            step = Step.START;
            return TaskStatus.RUNNING;
        }
        if (pressedAt >= 0) {
            holdStill();
            if (pressed(b)) {
                step = Step.START;
                startStepAt = now;
                pressedCallback.run();
                return TaskStatus.RUNNING;
            }
            if (now - pressedAt < PRESS_WAIT) return TaskStatus.RUNNING;
            pressedAt = -1;
        }
        if (pressClicks >= PRESS_TRIES) return fail("the launch button " + b + " did not show pressed after "
            + pressClicks + " clicks from the cart");
        if (pressed(b)) { // someone else's press still showing: wait for it to pop before ours
            holdStill();
            return TaskStatus.RUNNING;
        }
        if (!aimed) {
            Aiming.Shot shot = Aiming.anyFace(b.x(), b.y(), b.z(), reach());
            // a button all but hidden behind its own block shows no face centre: look for any point of it in sight
            if (shot == null) shot = Aiming.anyFaceFine(b.x(), b.y(), b.z(), reach());
            if (shot == null) return fail("the launch button " + b + " is not in reach and sight from the cart");
            aimYaw = shot.yaw();
            aimPitch = shot.pitch();
            aimed = true;
        }
        clicker.submit(now, aimYaw, aimPitch, null);
        if (!clicker.canClick(now)) return TaskStatus.RUNNING;
        Aiming.Shot hit = Aiming.check(b.x(), b.y(), b.z(), null, aimYaw, aimPitch, reach());
        if (hit == null) {
            aimed = false;
            return TaskStatus.RUNNING;
        }
        if (!Clicker.sendUseItemOn(hit.ray())) return fail("not connected");
        pressClicks++;
        pressedAt = now;
        aimed = false;
        return TaskStatus.RUNNING;
    }

    private boolean pressed(Pos b) {
        return "true".equals(ctx.world().block(b).props().get("powered"));
    }

    private TaskStatus start(WorldView.Thing cart) {
        double gone = Math.hypot(cart.x() - startX, cart.z() - startZ);
        if (mapId == null && button == null && gone < 1.0 && atBrake(cart) && now - lastMoveAt >= ARRIVED_TICKS) {
            // the preload lap was launched before (a restart in the cart) and the cart is back at its end already
            RideInput.forward(false);
            return TaskStatus.SUCCESS;
        }
        if (gone >= 1.0) {
            RideInput.forward(false);
            step = Step.RIDE;
            ZenithTask.LOG.info("the cart started{} ({} ticks after {})", pushSince >= 0 ? " after a push" : " by itself",
                now - startStepAt, resumePushYaw != null ? "the bot got back in" : "the press");
            holdStill();
            return TaskStatus.RUNNING;
        }
        Float push = resumePushYaw != null ? resumePushYaw : pushYaw;
        if (now - startStepAt < (resumePushYaw != null ? RESUME_WAIT : START_WAIT)) {
            holdStill();
            return TaskStatus.RUNNING;
        }
        if (pushSince < 0) pushSince = now;
        if (now - pushSince > PUSH_TICKS) {
            RideInput.forward(false);
            return fail(push == null ? "the cart did not start within " + (START_WAIT + PUSH_TICKS) + " ticks of the"
                + " press (no push is configured)"
                : "the cart did not start: forward held " + PUSH_TICKS + " ticks looking along yaw " + push);
        }
        if (push == null) {
            holdStill();
            return TaskStatus.RUNNING;
        }
        // look along the rail the cart is to take, forward pressed (the server pushes a standing cart that way)
        clicker.submit(now, push, 0f, null);
        RideInput.forward(clicker.ownedLastTick(now));
        return TaskStatus.RUNNING;
    }

    private TaskStatus ride(WorldView.Thing cart) {
        holdStill();
        if (watch.swingTicks() > 0 && now - lastSwing >= watch.swingTicks() && clicker.ownedLastTick(now)) {
            lastSwing = now;
            ClientSession client = Proxy.getInstance().getClient();
            if (client != null && client.isConnected()) client.sendAsync(new ServerboundSwingPacket(Hand.MAIN_HAND));
        }
        if (mapId == null) {
            farthest = Math.max(farthest, Math.sqrt(cart.horizontalDistSq(brake.x() + 0.5, brake.z() + 0.5)));
            if (farthest >= LAP_AWAY && atBrake(cart) && now - lastMoveAt < 1) {
                // the lap's cart is back at its start rail and rolls on (the stop there does not hold it): the lap is
                // ridden; the bot gets out here. Live 2026-11-24 04:09, j0040 shrimp [6,0]: a new cart on the lap
                // went round it for 10 min without stopping at the start rail, BLOCKED
                ZenithTask.LOG.warn("the preload cart passes its start rail {} without stopping (it was {} blocks away):"
                    + " the lap is ridden; the bot gets out", where(cart), String.format("%.0f", farthest));
                return TaskStatus.SUCCESS;
            }
        }
        if (atBrake(cart) && now - lastMoveAt >= 1) {
            if (stillSince < 0) stillSince = now;
            if (now - stillSince >= ARRIVED_TICKS) return TaskStatus.SUCCESS;
        } else {
            stillSince = -1;
        }
        if (stop != null && now - lastMoveAt >= STOP_STILL_TICKS && stop.stopNow(cart, now - lastMoveAt)) {
            ZenithTask.LOG.info("the ride stops at {} on the caller's word (the cart stood for {} ticks)", where(cart),
                now - lastMoveAt);
            return TaskStatus.SUCCESS;
        }
        if (watch.stallTicks() > 0 && now - lastMoveAt > watch.stallTicks())
            return fail("the cart has not moved for " + (now - lastMoveAt) / 20 + " s at " + where(cart));
        if (watch.maxTicks() > 0 && now > watch.maxTicks())
            return fail("the ride is taking longer than " + watch.maxTicks() / 20 / 60 + " min; the cart is at " + where(cart));
        return TaskStatus.RUNNING;
    }

    private boolean atBrake(WorldView.Thing cart) {
        return cart.horizontalDistSq(brake.x() + 0.5, brake.z() + 0.5) <= BRAKE_RANGE * BRAKE_RANGE
            && Math.abs(cart.y() - brake.y()) <= 1.5;
    }

    private static String where(WorldView.Thing cart) {
        return (int) Math.floor(cart.x()) + "," + (int) Math.floor(cart.y()) + "," + (int) Math.floor(cart.z());
    }
}
