package io.github.digitalisp.mapart.bot.impl;

import com.zenith.Proxy;
import com.zenith.mc.block.Direction;
import com.zenith.network.client.ClientSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;

/**
 * Eats one food item where the bot stands; the steps and their anti-cheat reasons are in {@link EatSequence}. Only the
 * foods it is given (the engine's {@code build.foodItems}) are eaten; with none carried it fails.
 *
 * <p>Unlike ZenithProxy's AutoEat (which this replaces while the engine runs) it never clicks the inventory or eats
 * while the bot walks, and it sends the use-item packet itself: ZenithProxy's Bot follows every right click with an
 * arm swing, which a vanilla client does not send when it starts eating. The packet goes out from the task tick,
 * before ZenithProxy's InputManager and Bot, so it precedes the tick's movement packet as in vanilla.
 */
final class EatTask extends ZenithTask {
    private static final int[] FOOD_SLOT = {Hotbar.FOOD_SLOT};
    /** Overall limit: both must pass (the server may run slower than the bot's ticks, see {@link EatSequence}). */
    private static final int TIMEOUT_TICKS = 600;
    private static final long TIMEOUT_MS = 30_000;

    private final List<String> preferred = new ArrayList<>();
    private final EatSequence seq = new EatSequence();
    private @Nullable String foodId;
    private long startMs = -1;

    EatTask(ZenithBotActions ctx, List<String> foods) {
        super(ctx, 0);
        if (foods != null) for (String f : foods) if (f != null && !f.isBlank()) preferred.add(ItemIds.ns(f));
    }

    @Override
    public String name() {
        return "eat" + (foodId != null ? " " + foodId : "");
    }

    @Override
    protected String progress() {
        return " (" + seq.uses() + " uses, hunger " + CACHE.getPlayerCache().getThePlayer().getFood() + ")";
    }

    @Override
    protected TaskStatus step() {
        long ms = System.currentTimeMillis();
        if (startMs < 0) startMs = ms;
        if (now > TIMEOUT_TICKS && ms - startMs >= TIMEOUT_MS) return fail("timed out after " + now + " ticks" + progress());
        holdStill();
        if (!seq.using() && (foodId == null || Inv.count(Inv.is(foodId)) == 0)) foodId = chooseFood();
        Predicate<ItemStack> food = foodId == null ? s -> false : Inv.is(foodId);
        boolean holding = foodId != null && foodId.equals(Inv.id(Inv.mainHand()));
        EatSequence.Seen seen = new EatSequence.Seen(clicker.stillLastTick(now), clicker.canClick(now),
            foodId != null, holding, Inv.count(food), CACHE.getPlayerCache().getThePlayer().getFood());
        switch (seq.next(now, seen)) {
            case WAIT -> { }
            case HOLD -> hotbar.ensureHeld(food, FOOD_SLOT, now);
            case USE -> {
                if (!sendUseItem()) return fail("not connected");
            }
            case RELEASE -> sendRelease();
            case DONE -> {
                return TaskStatus.SUCCESS;
            }
            case FAIL -> {
                return fail(seq.reason());
            }
        }
        return TaskStatus.RUNNING;
    }

    @Override
    protected void onRelease() {
        if (seq.using()) sendRelease();
    }

    /**
     * First of the engine's foods ({@code build.foodItems}) carried in the hotbar or main inventory; null if none.
     * Nothing else is ever eaten: no other food, and never golden apples or other {@link Valuables} (the owner's).
     */
    private @Nullable String chooseFood() {
        for (String id : preferred) {
            if (Valuables.isValuable(id)) continue;
            Predicate<ItemStack> is = Inv.is(id);
            if (Inv.find(s -> is.test(s) && ZenithInventoryView.isFood(s)) >= 0) return id;
        }
        return null;
    }

    /** Use-item with the main hand at the current rotation (block-state prediction sequence), no swing. */
    private static boolean sendUseItem() {
        ClientSession client = Proxy.getInstance().getClient();
        if (client == null || !client.isConnected()) return false;
        try (var prediction = CACHE.getChunkCache().getBlockStatePredictionHandler().startPredicting()) {
            client.send(new ServerboundUseItemPacket(Hand.MAIN_HAND, prediction.currentSequence(), BOT.getYaw(),
                BOT.getPitch()));
        }
        return true;
    }

    /** What a vanilla client sends when the use key is let go before the item is consumed. */
    private static void sendRelease() {
        ClientSession client = Proxy.getInstance().getClient();
        if (client == null || !client.isConnected()) return;
        client.sendAsync(new ServerboundPlayerActionPacket(PlayerAction.RELEASE_USE_ITEM, 0, 0, 0,
            Direction.DOWN.mcpl(), 0));
    }
}
