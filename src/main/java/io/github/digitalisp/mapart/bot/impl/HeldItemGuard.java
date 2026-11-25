package io.github.digitalisp.mapart.bot.impl;

import com.zenith.Proxy;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.InteractAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.config.MapArtConfig;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The last line of "tools never break" ({@code build.heldItemGuard}): an
 * outbound filter on the client connection, below every task, module and phase. While the plugin drives the bot (it is
 * enabled and no player is in control), a dig or attack never goes out while the main hand holds a damageable item at
 * or below {@code build.toolSafetyFloor} uses left, whoever sent it: the break tasks (they stop at the floor
 * themselves), the pathfinder's seal-block breaks ({@link BreakGuard}: its own tool choice), KillAura's attacks between
 * two fight steps. A start of a dig and an attack are dropped; the finish of a dig becomes an abort with the same
 * position and sequence (the server ends the dig, the item takes no wear). Logged once per item until it is above the
 * floor again; the engine mends or swaps the tool as it does at the floor. Any thread (the handler runs on the client
 * event loop).
 */
public final class HeldItemGuard {
    /** What the filter does with a packet. */
    enum Verdict { SEND, DROP, ABORT }

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger STOPPED = new AtomicInteger();

    private HeldItemGuard() { }

    /** The dig packet to send instead of {@code p} (null: none). */
    public static @Nullable ServerboundPlayerActionPacket dig(ServerboundPlayerActionPacket p, MapArtConfig cfg) {
        PlayerAction a = p.getAction();
        if (a != PlayerAction.START_DESTROY_BLOCK && a != PlayerAction.STOP_DESTROY_BLOCK) return p;
        if (!active(cfg)) return p;
        ItemStack held = Inv.mainHand();
        Verdict v = digVerdict(a, Wear.damageable(held), Wear.remaining(held), floor(cfg));
        if (v == Verdict.SEND) {
            settled(held);
            return p;
        }
        stopped(held, v == Verdict.DROP ? "a dig start dropped" : "a dig finish sent as an abort", cfg);
        if (v == Verdict.DROP) return null;
        return p.withAction(PlayerAction.ABORT_DESTROY_BLOCK);
    }

    /** The interact packet to send instead of {@code p}: an attack with a held item at the floor is dropped. */
    public static @Nullable ServerboundInteractPacket attack(ServerboundInteractPacket p, MapArtConfig cfg) {
        if (p.getAction() != InteractAction.ATTACK || !active(cfg)) return p;
        ItemStack held = Inv.mainHand();
        if (!atFloor(Wear.damageable(held), Wear.remaining(held), floor(cfg))) {
            settled(held);
            return p;
        }
        stopped(held, "an attack dropped", cfg);
        return null;
    }

    /** Dig starts dropped, finishes aborted and attacks dropped since the plugin loaded. */
    static int stoppedCount() {
        return STOPPED.get();
    }

    /** Pure: what happens to a dig {@code action} with a held item of {@code remaining} uses left. */
    static Verdict digVerdict(PlayerAction action, boolean damageable, int remaining, int floor) {
        if (!atFloor(damageable, remaining, floor)) return Verdict.SEND;
        if (action == PlayerAction.START_DESTROY_BLOCK) return Verdict.DROP;
        if (action == PlayerAction.STOP_DESTROY_BLOCK) return Verdict.ABORT;
        return Verdict.SEND;
    }

    /** Pure: a damageable item with {@code remaining} uses left is at the floor (it would wear at or below it). */
    static boolean atFloor(boolean damageable, int remaining, int floor) {
        return damageable && remaining <= Math.max(0, floor);
    }

    private static int floor(MapArtConfig cfg) {
        return Math.max(0, cfg.build.toolSafetyFloor);
    }

    private static boolean active(MapArtConfig cfg) {
        if (!cfg.enabled || !cfg.build.heldItemGuard) return false;
        Proxy proxy = Proxy.getInstance();
        return proxy != null && !proxy.hasActivePlayer();
    }

    private static void stopped(@Nullable ItemStack held, String what, MapArtConfig cfg) {
        STOPPED.incrementAndGet();
        String id = Inv.id(held);
        if (id == null || !LOGGED.add(id)) return;
        ZenithTask.LOG.warn("tool guard: {} at {}/{} uses is at the safety floor ({}): {}; no more digs or attacks with"
            + " it until it is mended or swapped", id, Wear.remaining(held), Wear.maxUses(held), floor(cfg), what);
    }

    /** The held item is fine (or none): its next stop at the floor is logged again. */
    private static void settled(@Nullable ItemStack held) {
        if (LOGGED.isEmpty()) return;
        String id = Inv.id(held);
        if (id != null) LOGGED.remove(id);
    }
}
