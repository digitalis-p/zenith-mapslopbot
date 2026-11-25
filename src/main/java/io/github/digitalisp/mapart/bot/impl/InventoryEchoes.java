package io.github.digitalisp.mapart.bot.impl;

import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetPlayerInventoryPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the server last said each hotbar slot holds, with a counter of such reports, for {@link MoveFence}: the
 * player inventory's full contents, a slot update of one of its hotbar slots, or a set-player-inventory for one.
 *
 * <p>Recorded by the map-art module on the client loop behind ZenithProxy's own cache update for the same packet, so a
 * report seen on the tick thread is also in the cache. The cache itself mixes our click predictions (applied when a
 * click goes out) with server updates (applied when they arrive, up to a round trip old): a report says what the
 * server had when it sent it, which is the point of keeping them apart.
 * Speculative waits use the connection tick clock, survive lane replacement, and have bounded recovery.
 *
 * <p>A slot the recovery (or an inventory task's hold) left unknown asks for the full inventory too, from a lane, at most
 * once per {@link #UNKNOWN_RESYNC_INTERVAL} ticks ({@code build.unknownSlotResync}), and after
 * {@code build.unknownSlotTrustTicks} with no report the cache is trusted for it again, in one line. Live 2026-10-15:
 * unknown slots waited for a report nothing asked for.
 */
public final class InventoryEchoes implements MoveFence.Reports {
    public static final InventoryEchoes SESSION = new InventoryEchoes();

    private long count;
    private final long[] at = new long[9];
    private final @Nullable String[] item = new String[9];
    private int quarantined = -1;
    private @Nullable String expected;
    private long quarantineMark;
    private long tick;
    private long quarantineSince;
    private boolean recovering;
    private boolean resyncSent;
    private long resyncMark;
    private @Nullable String event;
    private final boolean[] unknown = new boolean[9];
    /** Second bound, including time spent waiting for a safe resync tick/request. */
    static final int RESYNC_TIMEOUT = 100;
    /**
     * Unknown slots with no quarantine left ({@code build.unknownSlotResync}): a lane asks for the full inventory again at
     * most once per this many ticks, the same no-op click as the recovery's ({@link Hotbar#resyncPacket}).
     */
    static final int UNKNOWN_RESYNC_INTERVAL = MoveFence.TIMEOUT;
    /**
     * {@code build.unknownSlotResync}: unknown slots ask for the full inventory (false: they wait for a report). Set on
     * every bot tick from the config ({@link #configure}); off until then, as before it existed.
     */
    private boolean unknownResync;
    /** {@code build.unknownSlotTrustTicks}: after this many ticks with no report the cache is trusted again; 0 = never. */
    private int trustTicks;
    /** Session tick since which a slot is unknown (-1: none), the last unknown-slot request, and how many went out. */
    private long unknownSince = -1;
    private long unknownResyncAt = Long.MIN_VALUE / 2;
    private int unknownResyncs;

    InventoryEchoes() { }

    /** The unknown-slot switches ({@code build.unknownSlotResync}, {@code build.unknownSlotTrustTicks}), every tick. */
    synchronized void configure(boolean resync, int trustTicks) {
        this.unknownResync = resync;
        this.trustTicks = Math.max(0, trustTicks);
    }

    @Override
    public synchronized long mark() {
        return count;
    }

    /** The server reported hotbar slot {@code hotbar} (0-8) holding {@code material} (null: empty). */
    synchronized void report(int hotbar, @Nullable String material) {
        if (hotbar < 0 || hotbar > 8) return;
        at[hotbar] = ++count;
        item[hotbar] = material;
        unknown[hotbar] = false;
        if (unknownSince >= 0 && !anyUnknown()) {
            unknownSince = -1;
            unknownResyncs = 0;
        }
        if (hotbar == quarantined && at[hotbar] > quarantineMark && Objects.equals(material, expected))
            release(false);
    }

    /** A hotbar slot is unknown to placement (an abandoned speculative move, no server report since). */
    synchronized boolean anyUnknown() {
        for (boolean u : unknown) if (u) return true;
        return false;
    }

    /** The unknown hotbar slots, e.g. "[1, 4]", for log lines. */
    synchronized String unknownSlots() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 9; i++) if (unknown[i]) out.add(i);
        return out.toString();
    }

    /**
     * The hotbar slot the resync's no-op click names: the quarantined slot during a recovery, else the first unknown
     * slot (any slot would do: a same-slot hotbar swap changes nothing); -1 if neither.
     */
    synchronized int resyncSlot() {
        if (quarantined >= 0) return quarantined;
        for (int i = 0; i < 9; i++) if (unknown[i]) return i;
        return -1;
    }

    /** Speculative protection belongs to the connection, so task timeout/cancel cannot trust a cached prediction. */
    synchronized void quarantine(int hotbar, @Nullable String material, long mark) {
        quarantined = hotbar;
        expected = material;
        quarantineMark = mark;
        quarantineSince = tick;
        recovering = false;
        resyncSent = false;
    }

    synchronized int quarantinedSlot() { return quarantined; }

    synchronized boolean known(int hotbar) { return hotbar >= 0 && hotbar < 9 && !unknown[hotbar]; }

    @Override
    public synchronized boolean released(int hotbar, long mark) {
        return quarantined != hotbar || quarantineMark != mark;
    }

    /** Called once per connection bot tick, including while paused, never with a task-relative clock. */
    synchronized void tick(boolean enabled, boolean inventoryNeeded) {
        tick++;
        if (quarantined < 0) {
            // live 2026-10-15: unknown slots used to wait for a report that nothing asked for (counts left them out,
            // the only report a lane gets is a move's answer); the full-inventory requests have their bound here
            if (unknownSince >= 0 && trustTicks > 0 && tick - unknownSince >= trustTicks) {
                event = "Hotbar slots " + unknownSlots() + ": no server report in " + (tick - unknownSince) + " ticks ("
                    + unknownResyncs + " full inventory request" + (unknownResyncs == 1 ? "" : "s")
                    + "); trusting the inventory cache for them again";
                Arrays.fill(unknown, false);
                unknownSince = -1;
                unknownResyncs = 0;
            }
            return;
        }
        if (!enabled || inventoryNeeded) {
            release(true);
        } else if (tick - quarantineSince >= MoveFence.TIMEOUT + RESYNC_TIMEOUT) {
            event = "Hotbar lookahead slot " + quarantined + ": no full inventory answer within "
                + RESYNC_TIMEOUT + " recovery ticks; inventory hold released, slot unknown until a server report";
            release(true);
        } else if (!recovering && tick - quarantineSince >= MoveFence.TIMEOUT) {
            event = "Hotbar lookahead slot " + quarantined + ": no expected answer in " + MoveFence.TIMEOUT
                + " ticks; requesting full inventory on a still tick";
            recovering = true;
        }
    }

    synchronized @Nullable String takeEvent() {
        String e = event;
        event = null;
        return e;
    }

    synchronized boolean recovering() { return recovering; }

    /**
     * A lane should send the full-inventory request ({@link Hotbar#resyncPacket}, on a still tick): a recovery's, once;
     * or, with no quarantine left, one for the unknown slots ({@code build.unknownSlotResync}) at most once per
     * {@link #UNKNOWN_RESYNC_INTERVAL} ticks until a report names them or {@code build.unknownSlotTrustTicks} pass.
     */
    synchronized boolean needsResync() {
        if (recovering) return !resyncSent;
        return unknownResync && quarantined < 0 && unknownSince >= 0 && tick - unknownResyncAt >= UNKNOWN_RESYNC_INTERVAL;
    }

    /** Mark at packet execution, rather than request submission: queued stale reports cannot answer it. */
    synchronized void resyncSent() {
        if (recovering) {
            resyncSent = true;
            resyncMark = count;
        } else if (quarantined < 0 && unknownSince >= 0) {
            unknownResyncAt = tick;
            unknownResyncs++;
        }
    }

    /** Full-inventory requests sent for the unknown slots since they became unknown (status and tests). */
    synchronized int unknownResyncs() { return unknownResyncs; }

    /** Inventory tasks take priority; placement keeps the abandoned slot unknown until a report. */
    synchronized void inventoryNeeded() { release(true); }

    private void release(boolean uncertain) {
        if (uncertain && quarantined >= 0) {
            unknown[quarantined] = true;
            if (unknownSince < 0) {
                unknownSince = tick;
                unknownResyncs = 0;
                // a recovery's request that got no answer counts as the first: the next one a window later
                unknownResyncAt = recovering && resyncSent ? tick : Long.MIN_VALUE / 2;
            }
        }
        quarantined = -1;
        expected = null;
        recovering = false;
        resyncSent = false;
    }

    synchronized void fullReport(@Nullable String[] materials) {
        for (int i = 0; i < 9; i++) report(i, materials[i]);
        // A full answer can show the move was refused. Re-plan from that authoritative cache too.
        if (recovering && resyncSent && count > resyncMark) release(false);
    }

    @Override
    public synchronized boolean reportedSince(int hotbar, long mark, @Nullable String material) {
        return hotbar >= 0 && hotbar <= 8 && at[hotbar] > mark && Objects.equals(item[hotbar], material);
    }

    /** A new connection: the old reports say nothing about it. Marks stay comparable. */
    public synchronized void reset() {
        Arrays.fill(item, null);
        Arrays.fill(at, 0);
        quarantined = -1;
        expected = null;
        recovering = false;
        resyncSent = false;
        Arrays.fill(unknown, false);
        unknownSince = -1;
        unknownResyncs = 0;
        unknownResyncAt = Long.MIN_VALUE / 2;
        event = null;
    }

    // ---- offhand reports: the airplace guard's server confirmation

    /** Server reports naming the offhand: full contents of container 0, or a slot update of slot 45 (40 in SetPlayerInventory). */
    private static final AtomicLong OFFHAND_REPORTS = new AtomicLong();
    /** Full contents of container 0 (the answer to the full-inventory request). */
    private static final AtomicLong CONTENT_REPORTS = new AtomicLong();

    /**
     * Server reports that named the offhand so far, counted behind ZenithProxy's cache update for the same packet: a
     * count above a mark taken when a swap went out means the cache's offhand is the server's answer to it. Never
     * reset, so marks stay comparable across connections.
     */
    public static long offhandReports() {
        return OFFHAND_REPORTS.get();
    }

    /** Full player-inventory contents (container 0) received so far; never reset. */
    public static long contentReports() {
        return CONTENT_REPORTS.get();
    }

    /** The player inventory's whole contents (container 0); other menus are not the hotbar a move waits for. */
    public static void content(ClientboundContainerSetContentPacket p) {
        if (p.getContainerId() != 0) return;
        OFFHAND_REPORTS.incrementAndGet();
        CONTENT_REPORTS.incrementAndGet();
        ItemStack[] items = p.getItems();
        String[] materials = new String[9];
        for (int i = 0; i < 9; i++) {
            int s = ItemIds.HOTBAR_START + i;
            materials[i] = s < items.length ? Inv.id(items[s]) : null;
        }
        SESSION.fullReport(materials);
    }

    /** One slot of the player inventory (container 0). */
    public static void slot(ClientboundContainerSetSlotPacket p) {
        if (p.getContainerId() != 0) return;
        if (p.getSlot() == ItemIds.OFFHAND) OFFHAND_REPORTS.incrementAndGet();
        int i = ItemIds.hotbarIndex(p.getSlot());
        if (i >= 0) SESSION.report(i, Inv.id(p.getItem()));
    }

    /** One slot in the player's own numbering (0-8 hotbar). */
    public static void playerInventory(ClientboundSetPlayerInventoryPacket p) {
        int s = p.getSlot();
        if (s == 40) OFFHAND_REPORTS.incrementAndGet(); // the offhand in the player's own numbering
        if (s >= 0 && s <= 8) SESSION.report(s, Inv.id(p.getContents()));
    }
}
