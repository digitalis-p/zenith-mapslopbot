package io.github.digitalisp.mapart.bot.impl;

import com.zenith.Proxy;
import com.zenith.feature.player.Input;
import com.zenith.network.client.ClientSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.WorldView;

import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;

/**
 * Gets out of the cart at the end of the colour-suppression ride (docs/COLOR_SUPPRESSION.md): first a hotbar slot
 * without a filled map is selected, so the unlocked map stops updating before the bot moves at all, then the sneak key
 * goes down (the vanilla dismount) until the bot rides nothing, then a few still ticks on the ground. Where the server
 * puts it is the caller's check. SUCCESS when out and standing; FAILED after {@link #SNEAK_TICKS} ticks still in the
 * cart.
 *
 * <p><b>The sneak key in a vehicle.</b> A vanilla 1.21.4 client whose sneak key goes down sends two things: the
 * player-command packet "press shift key" ({@code ServerboundPlayerCommandPacket}, {@code START_SNEAKING}) and the
 * player-input packet with its shift flag; the 1.21.4 server dismounts on the first (the player's shift state, read by
 * its ride tick) and only keeps the second as the "last client input". From 1.21.6 on the command is gone and the
 * server takes shift from the input packet (ViaVersion's 1.21.5 to 1.21.6 protocol makes exactly that command from
 * the input packet's flag for older servers). ZenithProxy 3.7.2's Bot sends, in a vehicle, only the input packet (its
 * tick's vehicle branch sends rotation and input and never the command, which it sends on foot only): enough for the
 * local Paper 1.21.11 test server, nothing on 2b2t (1.21.4), where the first live ride sat in the cart for the whole
 * 60 ticks until the owner pressed sneak by hand (2026-11-13). So this task sends the command itself, once per press
 * ({@link DismountKey}), keeps the input's sneak flag set as before, and releases the key with the "release shift key"
 * command once it is out (or gives up, or is cancelled): never a press without its release.
 */
final class LeaveCartTask extends ZenithTask {
    static final int SNEAK_TICKS = 100;
    private static final int SETTLE_TICKS = 10;

    private final DismountKey key = new DismountKey();
    private long sneakSince = -1;
    private long outSince = -1;

    LeaveCartTask(ZenithBotActions ctx) {
        super(ctx, 400);
    }

    @Override
    public String name() {
        return "leaveCart";
    }

    @Override
    protected String progress() {
        return " (" + key.presses() + " sneak presses" + (key.down() ? ", key down" : "") + ")";
    }

    /** The bot is still in the cart inside the muncher's zones when this starts. */
    @Override
    protected boolean checksNoGoZones() {
        return false;
    }

    /** Cancelled or ended with the key down: it goes up (the server must never be left with shift pressed). */
    @Override
    protected void onRelease() {
        if (key.down()) send(key.release());
    }

    @Override
    protected TaskStatus step() {
        WorldView w = ctx.world();
        if (Inv.mapId(Inv.mainHand()) != null) {
            int slot = mapFreeSlot();
            if (slot < 0) return fail("every hotbar slot holds a filled map: none to put in the hand instead");
            holdStill();
            Clicker.selectNow(slot);
            return TaskStatus.RUNNING;
        }
        if (w.vehicle() != null) {
            if (sneakSince < 0) sneakSince = now;
            if (now - sneakSince > SNEAK_TICKS) {
                send(key.release());
                return fail("still in the cart after " + SNEAK_TICKS + " ticks of sneaking (" + key.presses()
                    + " presses of the sneak key)");
            }
            // the input packet's sneak flag (what a 1.21.6+ server reads), every tick as before
            clicker.submit(now, null, null, Input.builder().sneaking(true).build());
            // the key itself (what a 1.21.4 server reads): down once, up and down again if the server did not react
            send(key.inVehicle(now));
            return TaskStatus.RUNNING;
        }
        holdStill();
        if (outSince < 0) outSince = now;
        // out: the key goes up, once the Bot's own sneak state is off (it never sends a release for a press of ours)
        if (key.down() && now - outSince >= 2 && !BOT.isSneaking()) send(key.release());
        if (now - outSince < SETTLE_TICKS || !BOT.isOnGround() || key.down()) {
            if (now - outSince > 100) {
                send(key.release());
                return fail("not on the ground after getting out at " + Walker.feet().key());
            }
            return TaskStatus.RUNNING;
        }
        return TaskStatus.SUCCESS;
    }

    /** Sends the player command for {@code a} (nothing for {@link DismountKey.Action#NONE}). */
    private static void send(DismountKey.Action a) {
        if (a == DismountKey.Action.NONE) return;
        ClientSession client = Proxy.getInstance().getClient();
        if (client == null || !client.isConnected()) return;
        PlayerState state = a == DismountKey.Action.PRESS ? PlayerState.START_SNEAKING : PlayerState.STOP_SNEAKING;
        client.sendAsync(new ServerboundPlayerCommandPacket(CACHE.getPlayerCache().getEntityId(), state));
        ZenithTask.LOG.info("leaveCart: sneak key {} (player command {})", a == DismountKey.Action.PRESS ? "down" : "up", state);
    }

    /** A hotbar slot whose item is not a filled map (an empty one first), or -1. */
    static int mapFreeSlot() {
        int fallback = -1;
        for (int i = 0; i < 9; i++) {
            var s = Inv.stack(ItemIds.HOTBAR_START + i);
            if (Inv.empty(s)) return i;
            if (fallback < 0 && Inv.mapId(s) == null) fallback = i;
        }
        return fallback;
    }
}
