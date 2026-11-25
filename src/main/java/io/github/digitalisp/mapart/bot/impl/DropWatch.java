package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.DisconnectDrop;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * What a disconnect would drop. The server drops a menu's cursor stack
 * when the player leaves (vanilla {@code AbstractContainerMenu.removed}), and the cartography table and the anvil drop
 * what lies in their input slots too ({@code clearContainer}); nothing gives either back. Every bot tick, after
 * ZenithProxy's InventoryManager sent that tick's click ({@link #look}), the stacks at such risk are noted with the
 * bot's feet: the cursor's while a menu is open or an inventory request runs (ZenithProxy keeps a stale cursor in its
 * cache after a close, which the server has put back), and a cartography table's or anvil's slots 0 and 1, each with
 * how many of its item the inventory held besides it. When the connection ends ({@link #disconnected}), each stack seen
 * in the last {@link #IN_FLIGHT_MS} counts (a click that moved it on may not have reached the server); one still there
 * in the last look is {@code last}. The cache is ZenithProxy's prediction from the clicks sent: a click cut off before
 * the server took it leaves the stack where it was, which the inventory count shows after the reconnect
 * ({@link #back}; local drill 2026-10-25: both the lock's and the rename's map were back). The engine takes them
 * after the reconnect ({@code BotActions.takeDisconnectDrops}) and {@link DropRecoveryTask} picks them up. A restart of
 * ZenithProxy forgets them (the session is gone). Thread-safe: looks on the tick thread, the disconnect on any.
 */
final class DropWatch {
    static final DropWatch SESSION = new DropWatch();

    /**
     * Blocks between the bot's centre and a stack on the ground, on each horizontal axis, within which the server hands
     * it over (vanilla: the player's box grown by 1 sideways and 0.5 up and down touching the item's 0.25 box, so up to
     * 1.42; this leaves a margin).
     */
    static final double REACH = 1.0;

    /** A stack seen at risk at most this long before the last look still counts: its next clicks may not have arrived. */
    static final long IN_FLIGHT_MS = 2_000;
    /** The last look older than this at the disconnect (a player had control, or ticks had stopped): nothing counts. */
    static final long STALE_MS = 5_000;

    /** One stack at risk now: its item, count and where it is. */
    record Risk(String item, int count, String where) { }

    private record Seen(Risk risk, long atMs, Pos feet, int inventory) { }

    /** item + where → the last look that saw it, within {@link #IN_FLIGHT_MS} of the last look. */
    private final Map<String, Seen> recent = new LinkedHashMap<>();
    private List<Risk> lastRisks = List.of();
    private long lastLookMs = Long.MIN_VALUE;
    private List<DisconnectDrop> pending = List.of();

    /**
     * One bot tick's look: the stacks at risk now, how many of an item the inventory (main and hotbar) holds now
     * ({@code inventory}, asked only for the items at risk), and the bot's feet. A table's or anvil's input keeps the
     * least inventory count of the looks it stayed in: the result taken (a SWAP into the hotbar) is in the inventory in
     * ZenithProxy's cache a moment before the server's update empties the inputs there, and the map must not count
     * twice. A cursor stack does not (the next stack on the cursor is another one, the last one put down).
     */
    synchronized void look(List<Risk> risks, ToIntFunction<String> inventory, Pos feet, long nowMs) {
        for (Risk r : risks) {
            String key = r.item() + "|" + r.where();
            int inv = inventory.applyAsInt(r.item());
            Seen before = recent.get(key);
            if (before != null && !DisconnectDrop.CURSOR.equals(r.where())
                && lastRisks.stream().anyMatch(l -> key.equals(l.item() + "|" + l.where()))) {
                inv = Math.min(inv, before.inventory());
            }
            recent.put(key, new Seen(r, nowMs, feet, inv));
        }
        lastLookMs = nowMs;
        lastRisks = List.copyOf(risks);
        recent.values().removeIf(s -> nowMs - s.atMs() > IN_FLIGHT_MS);
    }

    /** The connection ended at {@code nowMs}: what it may have dropped waits for {@link #take}. */
    synchronized void disconnected(long nowMs) {
        List<DisconnectDrop> found = new ArrayList<>();
        if (lastLookMs != Long.MIN_VALUE && nowMs - lastLookMs <= STALE_MS) {
            for (Seen s : recent.values()) {
                if (lastLookMs - s.atMs() > IN_FLIGHT_MS) continue;
                found.add(new DisconnectDrop(s.risk().item(), s.risk().count(), s.feet(), s.risk().where(),
                    lastRisks.contains(s.risk()), s.inventory()));
            }
        }
        recent.clear();
        lastRisks = List.of();
        lastLookMs = Long.MIN_VALUE;
        if (found.isEmpty()) return; // a second disconnect before the engine looked keeps the first one's drops
        List<DisconnectDrop> all = new ArrayList<>(pending);
        all.addAll(found);
        pending = List.copyOf(all);
    }

    /** What the last disconnects may have dropped, once; empty until the next disconnect. */
    synchronized List<DisconnectDrop> take() {
        List<DisconnectDrop> p = pending;
        pending = List.of();
        return p;
    }

    /** Forgets everything (tests). */
    synchronized void reset() {
        recent.clear();
        lastRisks = List.of();
        lastLookMs = Long.MIN_VALUE;
        pending = List.of();
    }

    /**
     * The stacks at risk in one look: the cursor's ({@code cursorLive}: a menu is open or an inventory request runs),
     * and a cartography table's or anvil's input slots ({@code menuName} null: no such menu). Null ids are skipped.
     */
    static List<Risk> risks(@Nullable String cursorItem, int cursorCount, boolean cursorLive,
                            @Nullable String menuName, List<Risk> menuInputs) {
        List<Risk> out = new ArrayList<>(2);
        if (cursorItem != null && cursorCount > 0 && cursorLive) out.add(new Risk(cursorItem, cursorCount, DisconnectDrop.CURSOR));
        if (menuName != null) for (Risk r : menuInputs) if (r.item() != null && r.count() > 0) out.add(r);
        return out;
    }

    /**
     * The stack on the ground to pick up next for {@code item}: the nearest to the bot ({@code x, y, z}) of the item
     * entities of that item within {@code radius} of {@code at} (horizontally and vertically), skipping {@code given}
     * up; null when none. An entity whose stack is not known yet (no metadata) is not taken. Pure.
     */
    static WorldView.@Nullable ItemInFlight next(List<WorldView.ItemInFlight> near, String item, Pos at, double radius,
                                                 double x, double y, double z, Set<Integer> given) {
        WorldView.ItemInFlight best = null;
        double bestD = Double.MAX_VALUE;
        for (WorldView.ItemInFlight e : near) {
            if (!item.equals(e.item()) || given.contains(e.entityId())) continue;
            if (Math.abs(e.x() - (at.x() + 0.5)) > radius || Math.abs(e.z() - (at.z() + 0.5)) > radius
                || Math.abs(e.y() - at.y()) > radius) continue;
            double d = (e.x() - x) * (e.x() - x) + (e.y() - y) * (e.y() - y) + (e.z() - z) * (e.z() - z);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /**
     * No stack of the item found yet near where it dropped: whether to look again next tick. While the chunk there is
     * not loaded (after a reconnect the world comes in first), for {@code look} ticks after it is, and while an item
     * entity near it has no stack in its metadata yet ({@code unread}, up to twice that). Pure.
     */
    static boolean keepLooking(boolean chunkLoaded, long ticksSinceLoaded, boolean unread, int look) {
        return !chunkLoaded || ticksSinceLoaded < look || unread && ticksSinceLoaded < 2L * look;
    }

    /**
     * The drop is in the inventory after the reconnect: the inventory holds {@code inventoryNow} of its item, at least
     * what it held besides the stack then and the stack. The click that took the stack had not reached the server (it
     * never left the inventory), or the server picked it up again when the bot rejoined beside it. Pure.
     */
    static boolean back(DisconnectDrop d, int inventoryNow) {
        return d.inventory() >= 0 && inventoryNow >= d.inventory() + d.count();
    }

    /**
     * The bot (centre {@code bx, by, bz}, feet height) near enough to the stack at {@code ix, iy, iz} for the server to
     * hand it over: {@link #REACH} on both horizontal axes, the stack between half a block under the feet and a block
     * over the head. Pure.
     */
    static boolean inReach(double bx, double by, double bz, double ix, double iy, double iz) {
        return Math.abs(ix - bx) <= REACH && Math.abs(iz - bz) <= REACH && iy >= by - 0.5 && iy <= by + 2.0;
    }

    /**
     * Where a stack on the ground lies from the bot's feet when it dropped, in words without coordinates: "3 blocks
     * below where it dropped" (lower), else "2 blocks from where it dropped" (the largest horizontal distance, rounded;
     * "where it dropped" under half a block). Pure.
     */
    static String whereFrom(Pos at, double x, double y, double z) {
        int below = at.y() - (int) Math.floor(y + 1e-6);
        if (below > 0) return below + (below == 1 ? " block" : " blocks") + " below where it dropped";
        int away = (int) Math.round(Math.max(Math.abs(x - (at.x() + 0.5)), Math.abs(z - (at.z() + 0.5))));
        if (away == 0) return "where it dropped";
        return away + (away == 1 ? " block" : " blocks") + " from where it dropped";
    }

    /** Blocks between the bot's feet and where a stack dropped (the largest axis distance). Pure. */
    static int distance(Pos feet, Pos at) {
        return Math.max(Math.max(Math.abs(feet.x() - at.x()), Math.abs(feet.z() - at.z())), Math.abs(feet.y() - at.y()));
    }
}
