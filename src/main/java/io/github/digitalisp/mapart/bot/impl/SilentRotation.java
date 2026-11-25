package io.github.digitalisp.mapart.bot.impl;

import com.zenith.Proxy;
import com.zenith.feature.player.Bot;
import com.zenith.feature.player.Input;
import com.zenith.network.client.ClientSession;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;

import static com.zenith.Globals.BOT;

/**
 * The printer's grimRotate ({@code build.rotatePlayer} false, {@code build.grimRotate} true), reimplemented on
 * ZenithProxy 3.7.2. A look is sent without turning the bot:
 * <pre>
 *   ServerboundPlayerInputPacket(the keys ZenithProxy's Bot last sent)     the printer: player.input.playerInput
 *   ServerboundMovePlayerPosRotPacket(Bot's position, onGround, horizontalCollision, target yaw/pitch)
 *   ... then this tick's movement packet from Bot, with the REAL rotation
 * </pre>
 * The printer overwrites the client's lastYaw/lastPitch with the target so vanilla re-sends the real rotation at the
 * end of the tick. ZenithProxy's Bot sends a rotation only when {@code yaw - lastYaw} or {@code pitch - lastPitch} is
 * non-zero ({@code Bot.java:462-506}); its {@code lastYaw} is private, so it is set to NaN by reflection: the
 * difference is then NaN, which is "non-zero", and Bot sends Rot (or PosRot when it moved) with its real yaw/pitch and
 * stores them again. Called from the engine's tick handler (priority 0), before Bot (-20000).
 *
 * <p>The target yaw is the equivalent closest to the bot's continuous yaw ({@link Aim#nearestYaw}), so the target and
 * the real rotation never differ by a whole turn (Grim AimModulo360); the real rotation is not touched. The keys are the
 * ones the server already has, so the PlayerInput never changes the server's movement state
 * ({@code Bot.lastSentMovementInput}, read by reflection; all keys up if Bot has sent none yet).
 *
 * <p>If the reflection is unavailable (another ZenithProxy build) nothing is sent and {@link #available} is false:
 * the caller then turns the real rotation instead. The same happens for new lane tasks once {@link SilentLookGuard}
 * saw server setbacks follow silent looks ({@link #usable}).
 */
final class SilentRotation {
    private SilentRotation() { }

    /** The packets and Bot state of one silent look (ZenithProxy in {@link #ZENITH}; fakes in tests). */
    interface Port {
        boolean connected();

        /** Keys Bot last sent {forward, back, left, right, jump, sneak, sprint}, or null if none yet. */
        boolean @Nullable [] lastSentKeys();

        /** Bot's position, ground and collision flags as of its last movement packet, and its real (continuous) yaw. */
        double x();

        double y();

        double z();

        boolean onGround();

        boolean horizontalCollision();

        float realYaw();

        void sendInput(boolean[] keys);

        void sendPosRot(double x, double y, double z, float yaw, float pitch, boolean onGround, boolean horizontalCollision);

        /** Makes Bot send its real rotation with this tick's movement packet even if it did not change. */
        boolean forceRotationResend();
    }

    /**
     * Sends the silent look: PlayerInput (Bot's last keys), PosRot (same position, target yaw nearest the real yaw,
     * pitch clamped), then marks the real rotation for re-sending. False if nothing was sent.
     */
    static boolean send(Port port, float yaw, float pitch) {
        if (!port.connected()) return false;
        boolean[] keys = port.lastSentKeys();
        port.sendInput(keys == null ? new boolean[7] : keys);
        port.sendPosRot(port.x(), port.y(), port.z(), Aim.nearestYaw(port.realYaw(), yaw),
            Math.max(-90f, Math.min(90f, pitch)), port.onGround(), port.horizontalCollision());
        port.forceRotationResend();
        return true;
    }

    /** The reflective handles into ZenithProxy's Bot resolved. */
    static boolean available() {
        return LAST_YAW != null && LAST_SENT_INPUT != null;
    }

    /**
     * Silent looks may be used for a new lane task: {@link #available} and not turned off for this run because server
     * setbacks followed them ({@link SilentLookGuard#SESSION}). A lane task
     * asks once, when it is made; one already running keeps its silent looks until it ends.
     */
    static boolean usable() {
        return usable(available(), SilentLookGuard.SESSION);
    }

    /** {@link #usable()} with the reflection's state and the guard given (unit tests). */
    static boolean usable(boolean available, SilentLookGuard guard) {
        return available && !guard.disabled();
    }

    /**
     * {@link #send} on ZenithProxy; false if the reflection is unavailable. A look that went out is reported to
     * {@link SilentLookGuard#SESSION}, which watches for server setbacks following silent looks.
     */
    static boolean send(float yaw, float pitch) {
        return available() && send(ZENITH, yaw, pitch, SilentLookGuard.SESSION, System.currentTimeMillis());
    }

    /** {@link #send(Port, float, float)}, then {@link SilentLookGuard#sent} at {@code nowMillis} if the look went out. */
    static boolean send(Port port, float yaw, float pitch, SilentLookGuard guard, long nowMillis) {
        if (!send(port, yaw, pitch)) return false;
        guard.sent(nowMillis);
        return true;
    }

    private static final @Nullable Field LAST_YAW = field("lastYaw");
    private static final @Nullable Field LAST_SENT_INPUT = field("lastSentMovementInput");

    private static @Nullable Field field(String name) {
        try {
            Field f = Bot.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static @Nullable ClientSession client() {
        ClientSession c = Proxy.getInstance().getClient();
        return c != null && c.isConnected() ? c : null;
    }

    /** ZenithProxy 3.7.2: packets through the client session, Bot's state through its getters and reflection. */
    static final Port ZENITH = new Port() {
        @Override
        public boolean connected() {
            return client() != null;
        }

        @Override
        public boolean @Nullable [] lastSentKeys() {
            try {
                Input in = LAST_SENT_INPUT == null ? null : (Input) LAST_SENT_INPUT.get(BOT);
                if (in == null) return null;
                return new boolean[]{in.pressingForward, in.pressingBack, in.pressingLeft, in.pressingRight, in.jumping,
                    in.sneaking, in.sprinting};
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        @Override public double x() { return BOT.getX(); }
        @Override public double y() { return BOT.getY(); }
        @Override public double z() { return BOT.getZ(); }
        @Override public boolean onGround() { return BOT.isOnGround(); }
        @Override public boolean horizontalCollision() { return BOT.isHorizontalCollision(); }
        @Override public float realYaw() { return BOT.getYaw(); }

        @Override
        public void sendInput(boolean[] k) {
            ClientSession c = client();
            if (c != null) c.send(new ServerboundPlayerInputPacket(k[0], k[1], k[2], k[3], k[4], k[5], k[6]));
        }

        @Override
        public void sendPosRot(double x, double y, double z, float yaw, float pitch, boolean onGround, boolean hc) {
            ClientSession c = client();
            if (c != null) c.send(new ServerboundMovePlayerPosRotPacket(onGround, hc, x, y, z, yaw, pitch));
        }

        @Override
        public boolean forceRotationResend() {
            try {
                if (LAST_YAW == null) return false;
                LAST_YAW.setFloat(BOT, Float.NaN);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }
    };
}
