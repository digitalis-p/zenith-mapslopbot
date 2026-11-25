package io.github.digitalisp.mapart.bot.impl;

import com.zenith.Proxy;
import com.zenith.feature.player.InputRequestFuture;
import com.zenith.network.client.ClientSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import io.github.digitalisp.mapart.bot.TaskStatus;

import static com.zenith.Globals.BOT;

/**
 * {@link KeepAliveRun} on ZenithProxy: the turn is this task's {@link Clicker} request (rotation only, the bot's input
 * priority), which ZenithProxy's InputManager hands to its Bot in the same tick; the swing is sent from the next task tick
 * (the engine's ClientBotTick handler runs before ZenithProxy's Bot, so it precedes that tick's movement packet, as a
 * vanilla left click does) once the turn was executed.
 */
final class KeepAliveTask extends ZenithTask {
    private final KeepAliveRun run;
    private final KeepAliveRun.Port port = new KeepAliveRun.Port() {
        @Override
        public boolean containerOpen() {
            return Inv.containerOpen();
        }

        @Override
        public float yaw() {
            return BOT.getYaw();
        }

        @Override
        public boolean requestYaw(float yaw) {
            return clicker.submit(now, yaw, null, null) != InputRequestFuture.rejected;
        }

        @Override
        public boolean executedLastTick() {
            return clicker.ownedLastTick(now);
        }

        @Override
        public void swing() {
            ClientSession client = Proxy.getInstance().getClient();
            if (client != null && client.isConnected()) client.sendAsync(new ServerboundSwingPacket(Hand.MAIN_HAND));
        }
    };

    KeepAliveTask(ZenithBotActions ctx, float yawDelta) {
        super(ctx, 20);
        this.run = new KeepAliveRun(yawDelta);
    }

    @Override
    public String name() {
        return "keepAlive";
    }

    /** Never closes a menu it did not open: with one open it fails instead ({@link KeepAliveRun}). */
    @Override
    protected boolean opensContainers() {
        return true;
    }

    @Override
    protected TaskStatus step() {
        TaskStatus s = run.tick(port);
        if (s == TaskStatus.FAILED) return fail(run.failure());
        return s;
    }
}
