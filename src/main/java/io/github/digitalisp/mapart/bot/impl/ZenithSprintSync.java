package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.player.Bot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.Attribute;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeModifier;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.ModifierOperation;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateAttributesPacket;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;

/**
 * {@link SprintSync} on ZenithProxy 3.7.2's Bot: the sprinting speed modifier and the sprint state across teleports as a
 * vanilla client keeps them, timed as Grim times them.
 *
 * <p><b>When an attribute update counts.</b> Grim sends a transaction (a ping) as it sends a movement-speed update for
 * the player, just ahead of it on the wire, and applies the update for the movements after the client's answer to that
 * ping ({@code PacketEntityReplication}: {@code sendTransaction}, then a task at {@code lastTransactionSent}). A vanilla
 * client handles the ping and the update in one batch before its next movement. ZenithProxy handles each packet as its
 * own task on the client loop, and applies an update whenever its task runs. Here an update counts from the tick after
 * ZenithProxy answered the last ping that came before it ({@link #ping} counts pings on the connection's thread, the
 * mark {@link #answered} runs on the client loop just ahead of ZenithProxy's answer), or from its arrival when no ping came
 * before it, or after {@link #NO_PING_MILLIS} at the latest.
 *
 * <p>The Bot's fields are private ({@code isSprinting}, {@code lastSprinting}, {@code speedAttribute}, {@code speed}):
 * reflection, as for {@link SilentRotation}; without it nothing changes and {@link #available} is false.
 */
public final class ZenithSprintSync {
    private ZenithSprintSync() { }

    public static final SprintSync SESSION = new SprintSync();
    private static final String SPRINTING = "minecraft:sprinting";
    /** An update whose ping's answer was not seen within this counts anyway. */
    static final long NO_PING_MILLIS = 1000;

    private static final @Nullable Field IS_SPRINTING = field("isSprinting");
    private static final @Nullable Field LAST_SPRINTING = field("lastSprinting");
    private static final @Nullable Field SPEED_ATTRIBUTE = field("speedAttribute");
    private static final @Nullable Field SPEED = field("speed");

    /** Pings received (connection thread). */
    private static final AtomicLong PINGS = new AtomicLong();
    /** Ping number → the tick (SESSION.now()) at which ZenithProxy's answer to it was out. */
    private static final Map<Long, Long> ANSWERED = new ConcurrentHashMap<>();

    private record Pending(boolean sprint, long ping, long arrivedTick, long arrivedMillis) { }

    private static final Queue<Pending> PENDING = new ConcurrentLinkedQueue<>();
    private static long restores;
    private static long modifierFixes;

    private static @Nullable Field field(String name) {
        try {
            Field f = Bot.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static boolean available() {
        return IS_SPRINTING != null && LAST_SPRINTING != null && SPEED_ATTRIBUTE != null && SPEED != null;
    }

    /** Inline on the connection's thread: a movement-speed update for the bot (with its sprinting modifier or not). */
    public static void attributes(ClientboundUpdateAttributesPacket packet) {
        if (packet.getEntityId() != CACHE.getPlayerCache().getEntityId()) return;
        for (Attribute a : packet.getAttributes()) {
            if (a.getType() != AttributeType.Builtin.MOVEMENT_SPEED) continue;
            boolean sprint = false;
            for (AttributeModifier m : a.getModifiers()) {
                if (SPRINTING.equals(m.getId())) {
                    sprint = true;
                    break;
                }
            }
            PENDING.add(new Pending(sprint, PINGS.get(), SESSION.now(), System.currentTimeMillis()));
            return;
        }
    }

    /**
     * Inline on the connection's thread: a ping. Returns its number, to be marked {@link #answered} on the client loop
     * behind ZenithProxy's answer.
     */
    public static long ping() {
        return PINGS.incrementAndGet();
    }

    /** On the client loop, just ahead of ZenithProxy's answer to ping {@code n} (its task follows this one). */
    public static void answered(long n) {
        ANSWERED.put(n, SESSION.now());
        if (ANSWERED.size() > 256) ANSWERED.keySet().removeIf(k -> k < n - 128);
    }

    /** Inline on the connection's thread: a server teleport. */
    public static void teleport() {
        SESSION.teleport();
    }

    /**
     * Every ClientBotTick before the Bot moves: restores the sprint state a teleport cleared and sets the sprinting
     * modifier as a vanilla client would have it ({@code enabled} false: only keeps count).
     */
    public static void tick(boolean enabled) {
        Boolean restore = SESSION.tick(BOT.isSprinting(), CACHE.getPlayerCache().isSprinting());
        long now = SESSION.now();
        long millis = System.currentTimeMillis();
        for (Pending p; (p = PENDING.peek()) != null; ) {
            long from;
            if (p.ping() == 0) {
                from = p.arrivedTick() + 1;
            } else {
                Long t = ANSWERED.get(p.ping());
                if (t != null) from = t + 1;
                else if (millis - p.arrivedMillis() > NO_PING_MILLIS) from = now;
                else break; // its ping not answered yet; the later ones follow it
            }
            SESSION.update(p.sprint(), Math.min(from, now));
            PENDING.poll();
        }
        if (!enabled || !available()) return;
        try {
            if (restore != null) {
                IS_SPRINTING.setBoolean(BOT, restore);
                LAST_SPRINTING.setBoolean(BOT, restore);
                restores++;
                ZenithTask.LOG.info("Sprint state after a server teleport restored to {} (what the server last heard;"
                    + " ZenithProxy had cleared it)", restore);
            }
            Object attr = SPEED_ATTRIBUTE.get(BOT);
            if (!(attr instanceof Attribute speedAttribute)) return;
            boolean want = SESSION.modifierWanted(BOT.isSprinting());
            List<AttributeModifier> mods = speedAttribute.getModifiers();
            boolean has = false;
            for (AttributeModifier m : mods) {
                if (SPRINTING.equals(m.getId())) {
                    has = true;
                    break;
                }
            }
            if (has == want) return;
            if (want) mods.add(new AttributeModifier(SPRINTING, 0.3f, ModifierOperation.ADD_MULTIPLIED_TOTAL));
            else mods.removeIf(m -> SPRINTING.equals(m.getId()));
            SPEED.setFloat(BOT, BOT.getLocalAttributeValue(speedAttribute, 0.10000000149011612f));
            modifierFixes++;
            ZenithTask.LOG.info("Sprinting speed modifier {} as the server's attribute update says (bot sprinting {};"
                + " {} corrections)", want ? "on" : "off", BOT.isSprinting(), modifierFixes);
        } catch (ReflectiveOperationException | RuntimeException e) {
            ZenithTask.LOG.warn("sprint sync failed: {}", e.toString());
        }
    }

    /** Teleport restores and modifier corrections so far (logs, tests). */
    public static String counts() {
        return restores + " sprint restores, " + modifierFixes + " modifier corrections";
    }
}
