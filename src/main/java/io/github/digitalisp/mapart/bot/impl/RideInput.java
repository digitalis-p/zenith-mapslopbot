package io.github.digitalisp.mapart.bot.impl;

import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;

import static com.zenith.Globals.CACHE;

/**
 * The forward key while riding a minecart (docs/COLOR_SUPPRESSION.md). ZenithProxy 3.7.2's Bot sends, every tick
 * in a vehicle, a {@code ServerboundPlayerInputPacket} with only jump and sneak taken from its input (forward, back,
 * left and right always false), so a bot in a cart can never push it the way a player pressing W does: the vanilla
 * server gives a cart standing still a push in the rider's look direction ({@code getLastClientMoveIntent}) only from
 * that packet's forward key. {@link RideTask} sets {@link #forward} for the few ticks the push takes, and the outbound
 * handler the module registers ({@link #rewrite}) sends ZenithProxy's packet with forward pressed while it is set and
 * the bot rides something. Any thread (the handler runs on the client event loop).
 */
public final class RideInput {
    private static volatile boolean forward;

    private RideInput() { }

    /** Press (true) or release forward while riding. */
    static void forward(boolean on) {
        forward = on;
    }

    static boolean forward() {
        return forward;
    }

    /** The packet to send instead of {@code p}: forward pressed while {@link #forward} is set in a vehicle. */
    public static ServerboundPlayerInputPacket rewrite(ServerboundPlayerInputPacket p) {
        if (!forward || p.isForward()) return p;
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player == null || !player.isInVehicle()) return p;
        return p.withForward(true).withBackward(false);
    }
}
