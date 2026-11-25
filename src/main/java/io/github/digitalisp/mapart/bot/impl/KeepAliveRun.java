package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;

/**
 * The keep-alive's two ticks. Pure: {@link KeepAliveTask} feeds it ZenithProxy, the
 * tests a simulated client and Bot.
 * <ol>
 *   <li>A rotation request through ZenithProxy's input path ({@code INPUTS} → {@code Bot}): the yaw turned by the given
 *       degrees, pitch and keys unchanged. ZenithProxy's Bot applies it in the same tick and sends it with its own
 *       movement packet (MovePlayerRot, or PosRot with the 20-tick position reminder). Never the silent look
 *       ({@link SilentRotation}) and never a movement packet of ours: Grim's TickTimer sets the bot back for a second
 *       flying packet before the client's tick end.</li>
 *   <li>Once Bot executed that request (no other module owned the input: AutoEat eating, KillAura), one arm swing: the
 *       normal swing packet (main hand), as a vanilla left click into the air. Never while a menu is open.</li>
 * </ol>
 * The {@link Port} has no way to move, click a block or touch the inventory, so the keep-alive cannot either.
 */
public final class KeepAliveRun {
    /** What the keep-alive needs from ZenithProxy. */
    public interface Port {
        /** A container menu is open. */
        boolean containerOpen();

        /** The bot's real (continuous) yaw: the one its last movement packet carried. */
        float yaw();

        /**
         * Submits this tick's input request with the bot's own priority: rotation only ({@code yaw}; pitch and keys as
         * they are). False if it was rejected (a module of higher priority owns the input this tick).
         */
        boolean requestYaw(float yaw);

        /** The request submitted on the previous tick was executed by ZenithProxy's Bot. */
        boolean executedLastTick();

        /** One arm swing: ServerboundSwingPacket(MAIN_HAND). */
        void swing();
    }

    private final float yawDelta;
    private int ticks;
    private @Nullable String failure;

    public KeepAliveRun(float yawDelta) {
        this.yawDelta = yawDelta;
    }

    /** One tick: RUNNING after the turn, SUCCESS after the swing, FAILED ({@link #failure}) with nothing more sent. */
    public TaskStatus tick(Port p) {
        ticks++;
        if (p.containerOpen()) return fail("a container menu is open");
        if (ticks == 1) {
            if (!p.requestYaw(p.yaw() + yawDelta)) return fail("another module owns the input");
            return TaskStatus.RUNNING;
        }
        if (!p.executedLastTick()) return fail("the turn was not executed (another module took the input)");
        p.swing();
        return TaskStatus.SUCCESS;
    }

    /** Why the keep-alive failed, or null. */
    public @Nullable String failure() {
        return failure;
    }

    private TaskStatus fail(String why) {
        failure = why;
        return TaskStatus.FAILED;
    }
}
