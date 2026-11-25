package io.github.digitalisp.mapart.bot.impl;

import com.zenith.Proxy;
import com.zenith.feature.player.Input;
import com.zenith.network.client.ClientSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.InteractAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.model.Pos;

import java.util.Set;
import java.util.function.Consumer;

/**
 * The start of the colour-suppression ride (docs/COLOR_SUPPRESSION.md): walk to {@code standAt}, put a minecart on
 * the start rail ({@code rail}) unless one stands there already, and get in. Every click is a vanilla one: the rotation
 * is sent on one tick ({@link Clicker}) and the click on the next, a use-item-on with the minecart on the rail's
 * interaction box (the hit point as the cursor) and, to get in, an interact-at plus interact on the cart with the hit
 * point on its box, each followed by a swing. SUCCESS once the bot rides the cart standing on the rail (its entity id
 * goes to {@code cartId}). Placing is tried 3 times, getting in 3 times, each with a 40-tick wait for the server.
 * With {@code place} false (the preload lap's cart, the platform's own) nothing is ever placed: no cart on the rail
 * {@link #OWN_CART_WAIT} ticks after the bot stood at {@code standAt} FAILS.
 * With {@code board} false ({@code BotActions.placeCart}: the cart put back after the ride) the bot does not get in:
 * SUCCESS once a minecart stands on the rail and has not moved for {@link #REST_TICKS} ticks; {@code placed} hears
 * whether the bot put it there (false: one stood there already and nothing was clicked).
 */
final class BoardCartTask extends ZenithTask {
    static final String CART_ITEM = "minecraft:minecart";
    private static final Set<String> CARTS = Set.of("minecart");
    private static final int WAIT = 40;
    private static final int TRIES = 3;
    /** Ticks a cart that is not placed by the bot is waited for on its rail (entities arrive after the chunk). */
    static final int OWN_CART_WAIT = 100;
    /** Place-only: the cart on the rail must stand still this long. */
    static final int REST_TICKS = 20;

    /** Closing in on a cart out of reach inside the stand's cell: until the click's point is this near, at most this long. */
    static final double CLOSE_ENOUGH = 2.75;
    static final int CLOSE_TICKS = 60;
    static final double CLOSE_TOLERANCE = 0.06;

    private enum Step { WALK, PLACE, MOUNT }

    private int closeTicks;
    private boolean closing;

    private final Pos rail;
    private final Pos standAt;
    private final Consumer<Integer> cartId;
    private final boolean place;
    private final boolean board;
    private final Consumer<Boolean> placed;
    private long waitSince = -1;
    private long restSince = -1;
    private double restX = Double.NaN;
    private double restY;
    private double restZ;
    private Step step = Step.WALK;
    private int placeClicks;
    private int mountClicks;
    private long clickedAt = -1;
    private float aimYaw;
    private float aimPitch;
    private boolean aimed;

    BoardCartTask(ZenithBotActions ctx, Pos rail, Pos standAt, Consumer<Integer> cartId) {
        this(ctx, rail, standAt, cartId, true);
    }

    BoardCartTask(ZenithBotActions ctx, Pos rail, Pos standAt, Consumer<Integer> cartId, boolean place) {
        super(ctx, WalkTask.walkTimeout(standAt) + 1200);
        this.rail = rail;
        this.standAt = standAt;
        this.cartId = cartId;
        this.place = place;
        this.board = true;
        this.placed = p -> { };
    }

    /** Place-only: a carried minecart onto {@code rail}, the bot stays out of it. */
    static BoardCartTask placeOnly(ZenithBotActions ctx, Pos rail, Pos standAt, Consumer<Boolean> placed) {
        return new BoardCartTask(ctx, rail, placed, standAt);
    }

    private BoardCartTask(ZenithBotActions ctx, Pos rail, Consumer<Boolean> placed, Pos standAt) {
        super(ctx, WalkTask.walkTimeout(standAt) + 1200);
        this.rail = rail;
        this.standAt = standAt;
        this.cartId = id -> { };
        this.place = true;
        this.board = false;
        this.placed = placed;
    }

    @Override
    public String name() {
        return (board ? "boardCart at " : "placeCart on ") + rail;
    }

    @Override
    protected String progress() {
        return " (" + step + ", " + placeClicks + " placing clicks, " + mountClicks + " boarding clicks)";
    }

    /** The walk to the cart rail; the cart's placement waits for the quiet window. */
    @Override
    protected SprintLegs.Walk walkKind() {
        return SprintLegs.Walk.CART;
    }

    @Override
    protected TaskStatus step() {
        WorldView w = ctx.world();
        WorldView.Thing riding = w.vehicle();
        if (riding != null && !board) return fail("riding a " + riding.type() + ": a cart is put back from the ground");
        if (riding != null) {
            if (!"minecart".equals(riding.type())) return fail("riding a " + riding.type() + ", not a minecart");
            if (!onRail(riding)) return fail("riding a minecart away from the start rail " + rail);
            cartId.accept(riding.id());
            return TaskStatus.SUCCESS;
        }
        switch (step) {
            case WALK -> {
                TaskStatus s = walkNear(standAt, 0, 1);
                if (s == TaskStatus.FAILED) return fail("walking to " + standAt + ": " + walker.error());
                if (s == TaskStatus.SUCCESS) {
                    walker.stop();
                    step = Step.PLACE;
                }
                return TaskStatus.RUNNING;
            }
            case PLACE -> {
                WorldView.Thing cart = cartOnRail(w);
                if (cart != null && !board) {
                    // place-only: done once it stands still on the rail
                    holdStill();
                    boolean still = !Double.isNaN(restX) && Math.abs(cart.x() - restX) < 0.01
                        && Math.abs(cart.y() - restY) < 0.01 && Math.abs(cart.z() - restZ) < 0.01;
                    if (!still) {
                        restX = cart.x();
                        restY = cart.y();
                        restZ = cart.z();
                        restSince = now;
                        return TaskStatus.RUNNING;
                    }
                    if (now - restSince < REST_TICKS) return TaskStatus.RUNNING;
                    placed.accept(placeClicks > 0);
                    return TaskStatus.SUCCESS;
                }
                restX = Double.NaN;
                if (cart != null) {
                    step = Step.MOUNT;
                    aimed = false;
                    clickedAt = -1;
                    holdStill();
                    return TaskStatus.RUNNING;
                }
                if (!place) {
                    // the platform's own cart: never placed by the bot
                    holdStill();
                    if (waitSince < 0) waitSince = now;
                    if (now - waitSince < OWN_CART_WAIT) return TaskStatus.RUNNING;
                    return fail("no minecart stands on the rail " + rail + " (the platform's own cart is expected there;"
                        + " none is placed by the bot)");
                }
                if (clickedAt >= 0 && now - clickedAt < WAIT) {
                    holdStill();
                    return TaskStatus.RUNNING;
                }
                if (placeClicks >= TRIES) return fail("no minecart appeared on the start rail " + rail + " after "
                    + placeClicks + " clicks");
                if (Inv.count(Inv.is(CART_ITEM)) < 1) return fail(io.github.digitalisp.mapart.bot.BotActions.NO_CART_CARRIED);
                if (!hotbar.ensureHeld(Inv.is(CART_ITEM), Hotbar.SPECIAL_SLOT, now)) {
                    holdStill();
                    return TaskStatus.RUNNING;
                }
                if (!aimed) {
                    Aiming.Shot shot = Aiming.anyFace(rail.x(), rail.y(), rail.z(), reach());
                    if (shot == null) return fail("the start rail " + rail + " is not in reach and sight from "
                        + Walker.feet().key());
                    aimYaw = shot.yaw();
                    aimPitch = shot.pitch();
                    aimed = true;
                }
                clicker.submit(now, aimYaw, aimPitch, null);
                if (!clicker.canClick(now)) return TaskStatus.RUNNING;
                Aiming.Shot hit = Aiming.check(rail.x(), rail.y(), rail.z(), null, aimYaw, aimPitch, reach());
                if (hit == null) {
                    aimed = false; // look again next tick
                    return TaskStatus.RUNNING;
                }
                if (!Clicker.sendUseItemOn(hit.ray())) return fail("not connected");
                placeClicks++;
                clickedAt = now;
                aimed = false;
                return TaskStatus.RUNNING;
            }
            case MOUNT -> {
                WorldView.Thing cart = cartOnRail(w);
                if (cart == null) {
                    step = Step.PLACE; // gone (someone took it): place another
                    clickedAt = -1;
                    holdStill();
                    return TaskStatus.RUNNING;
                }
                if (clickedAt >= 0 && now - clickedAt < WAIT) {
                    holdStill();
                    return TaskStatus.RUNNING;
                }
                if (mountClicks >= TRIES) return fail("could not get into the minecart on " + rail + " after "
                    + mountClicks + " clicks");
                // aim at the middle of the cart's box (0.98 x 0.7 x 0.98, feet at its position); from a stand whose eye
                // is more than 3 blocks from the middle (a paused ride's lane end: the cart stands 3 rails down the next
                // lane) at the point of the box nearest the eye, 0.1 inside it: the server measures to the box
                double tx = cart.x(), ty = cart.y() + 0.35, tz = cart.z();
                if (Aim.distSq(Aiming.eyeX(), Aiming.eyeY(), Aiming.eyeZ(), tx, ty, tz) > 3.0 * 3.0) {
                    double[] p = Aim.cartNearestPoint(Aiming.eyeX(), Aiming.eyeY(), Aiming.eyeZ(), cart.x(), cart.y(), cart.z());
                    tx = p[0];
                    ty = p[1];
                    tz = p[2];
                    // the walk ends anywhere in the stand's cell (local 2026-11-15: 0.14 inside its far edge, the point
                    // 3.1 away): first to the point of that cell nearest the cart, with the bot's own keys
                    if (closeIn(cart, Aim.distSq(Aiming.eyeX(), Aiming.eyeY(), Aiming.eyeZ(), tx, ty, tz))) return TaskStatus.RUNNING;
                }
                float[] rot = Aim.rotationTo(Aiming.eyeX(), Aiming.eyeY(), Aiming.eyeZ(), tx, ty, tz);
                float pitch = Clicker.effectivePitch(rot[1]);
                boolean same = aimed && Math.abs(Aim.yawDelta(rot[0], aimYaw)) < 0.5 && Math.abs(pitch - aimPitch) < 0.5;
                aimYaw = same ? aimYaw : rot[0];
                aimPitch = same ? aimPitch : pitch;
                aimed = true;
                clicker.submit(now, aimYaw, aimPitch, null);
                if (!same || !clicker.canClick(now)) return TaskStatus.RUNNING;
                double eyeDistSq = Aim.distSq(Aiming.eyeX(), Aiming.eyeY(), Aiming.eyeZ(), tx, ty, tz);
                if (eyeDistSq > 3.0 * 3.0) return fail("the minecart is " + Math.round(Math.sqrt(eyeDistSq) * 10) / 10.0
                    + " blocks away: out of reach to get in");
                if (!interact(cart.id(), (float) (tx - cart.x()), (float) (ty - cart.y()), (float) (tz - cart.z())))
                    return fail("not connected");
                mountClicks++;
                clickedAt = now;
                return TaskStatus.RUNNING;
            }
        }
        return TaskStatus.RUNNING;
    }

    /**
     * One tick of the step towards the cart inside the stand's own block column, sneaking (no step off an edge), until
     * the feet are at the point of that column nearest the cart ({@link Aim#nearestInCell}: the whole box stays in the
     * column) or the click's point is {@link #CLOSE_ENOUGH} away. False when there is nothing (more) to do: the mount
     * goes on from where the bot stands.
     */
    private boolean closeIn(WorldView.Thing cart, double aimDistSq) {
        if (aimDistSq <= CLOSE_ENOUGH * CLOSE_ENOUGH || closeTicks >= CLOSE_TICKS) return closeSettle();
        Pos feet = Walker.feet();
        if (feet.x() != standAt.x() || feet.z() != standAt.z()) return closeSettle();
        double[] p = Aim.nearestInCell(standAt.x(), standAt.z(), cart.x(), cart.z());
        double dx = p[0] - Aiming.eyeX(), dz = p[1] - Aiming.eyeZ();
        if (dx * dx + dz * dz <= CLOSE_TOLERANCE * CLOSE_TOLERANCE) return closeSettle();
        if (closeTicks == 0) LOG.info("boardCart: the cart on {} is {} blocks from the eye; closing in within {} first", rail,
            Math.round(Math.sqrt(aimDistSq) * 100) / 100.0, standAt);
        closeTicks++;
        closing = true;
        aimed = false;
        float yaw = Aim.rotationTo(Aiming.eyeX(), 0, Aiming.eyeZ(), p[0], 0, p[1])[0];
        clicker.submit(now, yaw, 0f, Input.builder().pressingForward(true).sneaking(true).build());
        return true;
    }

    /** The tick after the last key of {@link #closeIn}: keys up, standing again (the eye back at its height). */
    private boolean closeSettle() {
        if (!closing) return false;
        closing = false;
        aimed = false;
        holdStill();
        return true;
    }

    /** A minecart on the start rail's block (feet within the rail's cell, the slope's half block above it allowed). */
    private WorldView.@Nullable Thing cartOnRail(WorldView w) {
        for (WorldView.Thing t : w.entities(CARTS)) if (onRail(t)) return t;
        return null;
    }

    private boolean onRail(WorldView.Thing t) {
        return t.horizontalDistSq(rail.x() + 0.5, rail.z() + 0.5) <= 0.8 * 0.8 && t.y() >= rail.y() - 0.5
            && t.y() <= rail.y() + 1.2;
    }

    /** The vanilla right-click on an entity: interact-at (hit point relative to the entity), interact, swing. */
    static boolean interact(int entityId, float hx, float hy, float hz) {
        ClientSession client = Proxy.getInstance().getClient();
        if (client == null || !client.isConnected()) return false;
        client.send(new ServerboundInteractPacket(entityId, InteractAction.INTERACT_AT, hx, hy, hz, Hand.MAIN_HAND, false));
        client.send(new ServerboundInteractPacket(entityId, InteractAction.INTERACT, Hand.MAIN_HAND, false));
        client.sendAsync(new ServerboundSwingPacket(Hand.MAIN_HAND));
        return true;
    }

    /** The vanilla left-click on an entity: attack, swing. */
    static boolean attack(int entityId) {
        ClientSession client = Proxy.getInstance().getClient();
        if (client == null || !client.isConnected()) return false;
        client.send(new ServerboundInteractPacket(entityId, InteractAction.ATTACK, false));
        client.sendAsync(new ServerboundSwingPacket(Hand.MAIN_HAND));
        return true;
    }
}
