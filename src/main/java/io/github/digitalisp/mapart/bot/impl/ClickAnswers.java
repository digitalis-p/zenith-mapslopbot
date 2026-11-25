package io.github.digitalisp.mapart.bot.impl;

import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetCursorItemPacket;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * The server's answer to the last container click that went out ({@code build.containerAnswerWaits}). A click is noted
 * when it goes out ({@link #sent}) with what it leaves, as ZenithProxy
 * predicted it: per slot it changes whether that slot is then empty, and whether the cursor is. Its answer is the first
 * packet after it that shows the same:
 * <ul>
 *   <li>a slot update of the open menu for a slot the click changed, empty exactly when predicted;</li>
 *   <li>a cursor update, empty exactly when predicted (shift-clicks predict no cursor);</li>
 *   <li>the menu's full contents (the answer to a click with an old state id; ZenithProxy's ShiftClick always sends
 *       one), every changed slot and the cursor as predicted.</li>
 * </ul>
 * Nothing else ends a wait: the sorter's hoppers change chest slots and send slot updates every 250-400 ms while a chest
 * is open, and the answer to an earlier click shows that click's state (a top-up's first click empties the slot its last
 * one fills; an earlier shift-click's contents still hold the last one's stack). The server answers in order, so the
 * answer to the last click means every earlier answer is in too ({@link #answered} counts that way).
 *
 * <p>Recorded by the map-art module on the client loop behind ZenithProxy's own cache update for the same packet (as
 * {@link InventoryEchoes}), only while {@code build.containerAnswerWaits} is on; the clicks are noted on the tick thread
 * when ZenithProxy's InventoryManager builds their packets.
 */
public final class ClickAnswers {
    public static final ClickAnswers SESSION = new ClickAnswers();

    private long tokens;
    private long pending;
    private int pendingMenu;
    private Map<Integer, Boolean> pendingSlots = Map.of();
    private @Nullable Boolean pendingCursor;
    private long answered;
    private long ignored;
    private String lastAnswer = "";

    public ClickAnswers() { }

    /**
     * A click in menu {@code menuId} went out. {@code slotsEmptyAfter}: the slots it changes, each true when it leaves the
     * slot empty; {@code cursorEmptyAfter}: whether it leaves the cursor empty, null when that is not predicted.
     * Returns its token for {@link #answered}.
     */
    public synchronized long sent(int menuId, Map<Integer, Boolean> slotsEmptyAfter, @Nullable Boolean cursorEmptyAfter) {
        pending = ++tokens;
        pendingMenu = menuId;
        pendingSlots = Map.copyOf(slotsEmptyAfter);
        pendingCursor = cursorEmptyAfter;
        return pending;
    }

    /** A slot update of menu {@code menuId}: {@code slot} now holds nothing ({@code empty}) or something. */
    public synchronized void slot(int menuId, int slot, boolean empty) {
        if (pending == 0 || menuId != pendingMenu) return;
        Boolean want = pendingSlots.get(slot);
        if (want != null && want == empty) answer("slot " + slot + (empty ? " empty" : ""));
        else ignored++;
    }

    /** A cursor update. */
    public synchronized void cursor(boolean empty) {
        if (pending == 0) return;
        if (pendingCursor != null && pendingCursor == empty) answer(empty ? "cursor empty" : "cursor");
        else ignored++;
    }

    /** The full contents of menu {@code menuId}: per slot whether it is empty, and the cursor. */
    public synchronized void content(int menuId, boolean[] slotEmpty, boolean cursorEmpty) {
        if (pending == 0 || menuId != pendingMenu) return;
        boolean match = pendingCursor == null || pendingCursor == cursorEmpty;
        for (var e : pendingSlots.entrySet()) {
            int s = e.getKey();
            if (s < 0 || s >= slotEmpty.length || slotEmpty[s] != e.getValue()) match = false;
        }
        if (match && !pendingSlots.isEmpty()) answer("contents");
        else ignored++;
    }

    private void answer(String what) {
        answered = Math.max(answered, pending);
        pending = 0;
        lastAnswer = what;
    }

    /** What the last answer was: "contents", "cursor (empty)", "slot N (empty)"; "" before any. For log lines. */
    public synchronized String lastAnswer() {
        return lastAnswer;
    }

    /** The click {@code token} (or a later one) was answered. */
    public synchronized boolean answered(long token) {
        return token > 0 && answered >= token;
    }

    /** Packets in the menu of a click waiting for its answer that answered nothing (hopper updates, earlier answers). */
    public synchronized long ignored() {
        return ignored;
    }

    /** A new connection: no click waits for its answer (a menu of the last one is gone; its run sees it closed). */
    public synchronized void reset() {
        pending = 0;
        pendingSlots = Map.of();
        pendingCursor = null;
    }

    // ------------------------------------------------------------------------------- the module's packet hooks

    /** A slot update of a menu (container id above 0). */
    public static void slot(ClientboundContainerSetSlotPacket p) {
        SESSION.slot(p.getContainerId(), p.getSlot(), Inv.empty(p.getItem()));
    }

    /** A cursor update. */
    public static void cursor(ClientboundSetCursorItemPacket p) {
        SESSION.cursor(Inv.empty(p.getContents()));
    }

    /** A menu's full contents (container id above 0). */
    public static void content(ClientboundContainerSetContentPacket p) {
        ItemStack[] items = p.getItems();
        boolean[] empty = new boolean[items == null ? 0 : items.length];
        for (int i = 0; i < empty.length; i++) empty[i] = Inv.empty(items[i]);
        SESSION.content(p.getContainerId(), empty, Inv.empty(p.getCarriedItem()));
    }
}
