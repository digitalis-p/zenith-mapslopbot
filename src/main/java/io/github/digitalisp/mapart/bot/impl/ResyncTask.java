package io.github.digitalisp.mapart.bot.impl;

import com.zenith.Proxy;
import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.Direction;
import com.zenith.network.client.ClientSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BLOCK_DATA;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;

/**
 * {@code resync}: {@link ResyncRun} on ZenithProxy. The look is
 * this task's input request (a real rotation through ZenithProxy's Bot, {@link Aiming#topFace}: the real block raycast
 * hits the top face). The click, on the next tick with the look held, is
 * {@code ServerboundUseItemOnPacket(block, UP, OFF_HAND, cursor where the ray hits, inside false)} with the next block
 * prediction sequence and no swing (a vanilla client swings only when the click did something), sent from the engine's
 * ClientBotTick handler, so before this tick's movement packet. Only with an empty or totem off-hand: vanilla's
 * {@code ServerPlayerGameMode.useItemOn} runs a block's own use action ({@code useWithoutItem}) for the main hand only,
 * and a totem has no use on a block, so the server only answers.
 */
final class ResyncTask extends ZenithTask {
    /** The local resync, the looks and clicks (at most 25, two ticks each, waiting for the input) and the settle. */
    static final long TIMEOUT_TICKS = 200;
    /** Off-hand items that do nothing when used on a block. */
    private static final String TOTEM = "minecraft:totem_of_undying";

    private final Pos at;
    private final ResyncRun run;
    private final ResyncRun.Port port = new ResyncRun.Port() {
        @Override
        public void resyncLocal() {
            ResyncTask.resyncLocal(walker, name());
        }

        @Override
        public void holdStill() {
            ResyncTask.this.holdStill();
        }

        @Override
        public void look(float yaw, float pitch) {
            clicker.submit(now, yaw, pitch, null);
        }

        @Override
        public boolean containerOpen() {
            return Inv.containerOpen();
        }

        @Override
        public @Nullable String offhandProblem() {
            String id = Inv.id(Inv.cache().getPlayerInventory().getItemStack(ItemIds.OFFHAND));
            return id == null || TOTEM.equals(id) ? null : "the off-hand holds " + id;
        }

        @Override
        public boolean air(Pos p) {
            return ctx.world().block(p).isAir();
        }

        @Override
        public @Nullable String skip(Pos c) {
            if (!ctx.world().isLoaded(c)) return "not loaded";
            int state = World.getBlockStateId(c.x(), c.y(), c.z());
            Block data = BLOCK_DATA.getBlockDataFromBlockStateId(state);
            if (data == null || data.blockEntityType() != null) return "a block entity: " + BlockStates.spec(state).id();
            if (protectedRegionRefusal(c, "click") != null) return "protected region";
            return null;
        }

        @Override
        public float @Nullable [] aim(Pos c) {
            Aiming.Shot shot = Aiming.topFace(c.x(), c.y(), c.z(), reach());
            return shot == null ? null : new float[]{shot.yaw(), shot.pitch()};
        }

        @Override
        public boolean lookExecuted() {
            return clicker.canClick(now);
        }

        @Override
        public boolean click(Pos c) {
            ClientSession client = Proxy.getInstance().getClient();
            if (client == null || !client.isConnected()) return false;
            // the rotation in effect: the look sent with the last movement packet
            Aiming.Shot shot = Aiming.check(c.x(), c.y(), c.z(), Direction.UP, BOT.getYaw(), BOT.getPitch(), reach());
            if (shot == null || shot.ray().intersection() == null) return false;
            var hit = shot.ray().intersection();
            float[] cursor = Aim.cursor(hit.x(), hit.y(), hit.z(), c.x(), c.y(), c.z());
            try (var prediction = CACHE.getChunkCache().getBlockStatePredictionHandler().startPredicting()) {
                client.send(new ServerboundUseItemOnPacket(c.x(), c.y(), c.z(), shot.ray().direction().mcpl(),
                    Hand.OFF_HAND, cursor[0], cursor[1], cursor[2], false, false, prediction.currentSequence()));
            }
            return true;
        }

        @Override
        public String block(Pos p) {
            return ctx.world().block(p).toString();
        }

        @Override
        public long mark() {
            return BlockEchoes.mark();
        }

        @Override
        public boolean updatedSince(Pos p, long mark) {
            return BlockEchoes.since(p, mark);
        }
    };

    ResyncTask(ZenithBotActions ctx, Pos at) {
        super(ctx, TIMEOUT_TICKS);
        this.at = at;
        this.run = new ResyncRun(at);
    }

    @Override
    public String name() {
        return "resync at " + at.key();
    }

    @Override
    protected String progress() {
        return " (" + run.clicked().size() + " of " + run.columns().size() + " blocks re-requested)";
    }

    @Override
    protected TaskStatus step() {
        TaskStatus s = run.tick(port);
        if (s == TaskStatus.FAILED) return fail(run.failure());
        if (s == TaskStatus.SUCCESS) LOG.info(run.summary());
        return s;
    }

    /**
     * Baritone stopped, Bot's movement state resynced the way ZenithProxy does when bot ticks start
     * ({@code Bot.syncFromCache(false)}), and any block prediction ZenithProxy's own interactions left open ended
     * ({@code endPredictionsUpTo}: the cache falls back to the server's states); each step on its own. Shared with the
     * setback escape ({@link EscapeTask}).
     */
    static void resyncLocal(Walker walker, String tag) {
        try {
            walker.stop();
            BARITONE.getPathingBehavior().forceCancel();
        } catch (RuntimeException e) {
            LOG.warn(tag + ": stopping Baritone failed: " + e);
        }
        try {
            BOT.syncFromCache(false);
        } catch (RuntimeException e) {
            LOG.warn(tag + ": movement state resync failed: " + e);
        }
        try {
            var predictions = CACHE.getChunkCache().getBlockStatePredictionHandler();
            predictions.endPredictionsUpTo(predictions.currentSequence());
        } catch (RuntimeException e) {
            LOG.warn(tag + ": block prediction resync failed: " + e);
        }
    }
}
