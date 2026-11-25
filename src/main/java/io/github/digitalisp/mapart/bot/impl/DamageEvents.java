package io.github.digitalisp.mapart.bot.impl;

import com.zenith.cache.data.entity.Entity;
import com.zenith.cache.data.entity.EntityCache;
import com.zenith.mc.damage_type.DamageType;
import com.zenith.mc.damage_type.DamageTypeRegistry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundDamageEventPacket;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.Hit;
import io.github.digitalisp.mapart.bot.WorldView;

import static com.zenith.Globals.CACHE;

/**
 * The server's damage events ({@code ClientboundDamageEventPacket}) as {@link Hit}s on the bot: the damage type named
 * from the server's damage type registry, the cause and direct entities looked up in
 * the entity cache. Call on the client event loop, where ZenithProxy keeps that cache.
 */
public final class DamageEvents {
    private DamageEvents() {
    }

    /** The damage event as a hit on the bot, or null when it is about another entity. */
    public static @Nullable Hit onBot(ClientboundDamageEventPacket p) {
        Hit h = Hit.of(p.getEntityId(), CACHE.getPlayerCache().getEntityId(), p.getSourceTypeId(),
            p.getSourceCauseId(), p.getSourceDirectId(), DamageEvents::typeName, DamageEvents::entity);
        if (h != null) noteWall(h.type(), System.currentTimeMillis());
        return h;
    }

    /**
     * The newest fly_into_wall damage the server dealt the bot (wall ms; -1: none since the flight leg took it): the
     * flight leg's collision signal where the client saw none (live 2026-10-28 06:50: an arc's touch-down slid
     * into a block's side at 0.48 b/t and the server dealt the damage, while ZenithProxy's Bot met no block in the air,
     * so no "ran into a block" line). Any thread.
     */
    static final java.util.concurrent.atomic.AtomicLong WALL_MS = new java.util.concurrent.atomic.AtomicLong(-1);

    /** {@link #WALL_MS} for a damage type that is flying into a wall. */
    static void noteWall(@Nullable String type, long nowMs) {
        if (type != null && type.endsWith("fly_into_wall")) WALL_MS.set(nowMs);
    }

    /** {@link #WALL_MS}'s time, cleared (-1: none). */
    static long takeWall() {
        return WALL_MS.getAndSet(-1);
    }

    private static @Nullable String typeName(int id) {
        try {
            DamageType t = DamageTypeRegistry.REGISTRY.get(id);
            return t == null ? null : t.name();
        } catch (RuntimeException e) {
            return null; // no registry from this server yet, or an id past its end
        }
    }

    /** The entity as a {@link WorldView.Thing}; a player with its UUID and tab-list name (the teammate check). */
    private static WorldView.@Nullable Thing entity(int id) {
        EntityCache cache = CACHE.getEntityCache();
        Entity e = cache == null ? null : cache.get(id);
        if (e == null) return null;
        return ZenithWorldView.thing(e);
    }
}
