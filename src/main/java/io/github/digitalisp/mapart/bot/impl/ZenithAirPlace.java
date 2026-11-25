package io.github.digitalisp.mapart.bot.impl;

import com.zenith.Proxy;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.network.client.ClientSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.jspecify.annotations.Nullable;

import java.util.Set;

import static com.zenith.Globals.CACHE;

/**
 * {@link AirPlace.Port} and {@link AirPlaceGuard.View} on ZenithProxy 3.7.2 (client event loop only).
 *
 * <ul>
 *   <li>Swap: {@code ServerboundPlayerActionPacket(SWAP_ITEM_WITH_OFFHAND, 0, 0, 0, DOWN, 0)}, as the vanilla client
 *       (BlockPos.ZERO, DOWN, sequence 0). Sent with {@code sendAwait}: ZenithProxy's
 *       {@code PostOutgoingPlayerActionHandler} swaps the cached held slot and offhand inline, so the cache shows the
 *       state the server will have after each packet, and two swaps leave it unchanged.</li>
 *   <li>Use: {@code ServerboundUseItemOnPacket(x, y, z, UP, OFF_HAND, 0.5, 0.5, 0.5, insideBlock true, worldBorder false,
 *       seq)}; {@code seq} from {@code BlockStatePredictionHandler.startPredicting()} (incremented per call, as
 *       ZenithProxy's own PlayerInteractionManager does). ZenithProxy has no handler for it: the count drops when the
 *       server's slot update arrives.</li>
 *   <li>Swing: {@code ServerboundSwingPacket(OFF_HAND)}.</li>
 *   <li>Select: {@link Clicker#selectNow} (SetCarriedItem only if it changes, {@code sendAwait}).</li>
 * </ul>
 * All of it from the engine's ClientBotTick handler (priority 0): before InventoryManager (-5000), InputManager
 * (-10000) and Bot (-20000), so the packets precede this tick's movement packet; the channel keeps call order.
 */
final class ZenithAirPlace implements AirPlace.Port, AirPlaceGuard.View {
    static final ZenithAirPlace INSTANCE = new ZenithAirPlace();

    /** Items that place a block of another name. */
    private static final Set<String> PLACES_OTHER = Set.of("redstone", "string", "wheat_seeds", "beetroot_seeds",
        "melon_seeds", "pumpkin_seeds", "torchflower_seeds", "pitcher_pod", "carrot", "potato", "sweet_berries",
        "glow_berries", "cocoa_beans", "nether_wart");

    private ZenithAirPlace() { }

    private static @Nullable ClientSession client() {
        ClientSession c = Proxy.getInstance().getClient();
        return c != null && c.isConnected() ? c : null;
    }

    // ------------------------------------------------------------------ View

    @Override
    public boolean inGame() {
        return client() != null;
    }

    @Override
    public @Nullable String offhand() {
        return Inv.id(Inv.cache().getPlayerInventory().getItemStack(ItemIds.OFFHAND));
    }

    @Override
    public @Nullable String hotbar(int i) {
        return Inv.id(Inv.stack(ItemIds.HOTBAR_START + i));
    }

    @Override
    public int heldSlot() {
        return Inv.heldSlot();
    }

    @Override
    public boolean containerOpen() {
        return Inv.containerOpen();
    }

    @Override
    public boolean inventoryBusy() {
        return com.zenith.Globals.INVENTORY.hasActiveRequest();
    }

    @Override
    public boolean placesBlock(String itemId) {
        String path = ItemIds.path(itemId);
        return BlockRegistry.REGISTRY.get(path) != null || PLACES_OTHER.contains(path);
    }

    @Override
    public int slotOf(String itemId) {
        return Inv.find(Inv.is(itemId));
    }

    @Override
    public long offhandReports() {
        return InventoryEchoes.offhandReports();
    }

    // ------------------------------------------------------------------ Port

    @Override
    public boolean select(int slot) {
        return Clicker.selectNow(slot);
    }

    @Override
    public boolean swapOffhand() {
        ClientSession c = client();
        if (c == null) return false;
        c.sendAwait(new ServerboundPlayerActionPacket(PlayerAction.SWAP_ITEM_WITH_OFFHAND, 0, 0, 0, Direction.DOWN, 0));
        return true;
    }

    @Override
    public int useItemOnInside(int x, int y, int z) {
        ClientSession c = client();
        if (c == null) return -1;
        try (var prediction = CACHE.getChunkCache().getBlockStatePredictionHandler().startPredicting()) {
            int seq = prediction.currentSequence();
            c.send(new ServerboundUseItemOnPacket(x, y, z, Direction.UP, Hand.OFF_HAND, 0.5f, 0.5f, 0.5f,
                true, false, seq));
            Clicker.lastClickNanos = System.nanoTime();
            return seq;
        }
    }

    @Override
    public int useItemOnInside(int x, int y, int z, String face) {
        ClientSession c = client();
        if (c == null) return -1;
        Direction d = switch (face) {
            case "east" -> Direction.EAST;
            case "south" -> Direction.SOUTH;
            default -> Direction.UP;
        };
        try (var prediction = CACHE.getChunkCache().getBlockStatePredictionHandler().startPredicting()) {
            int seq = prediction.currentSequence();
            c.send(new ServerboundUseItemOnPacket(x, y, z, d, Hand.OFF_HAND, 0.5f, 0.5f, 0.5f, true, false, seq));
            Clicker.lastClickNanos = System.nanoTime();
            return seq;
        }
    }

    @Override
    public void swingOffhand() {
        ClientSession c = client();
        if (c != null) c.send(new ServerboundSwingPacket(Hand.OFF_HAND));
    }
}
