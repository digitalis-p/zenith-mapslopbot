package io.github.digitalisp.mapart.bot;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Damage the server reported on the bot (its {@code damage_event} packet), with the
 * entities resolved against the client's entity cache when it arrived: {@code type} the damage type
 * ("minecraft:arrow", "minecraft:fall"), {@code cause} the entity behind it (the pillager that shot), {@code direct}
 * the entity that struck (the arrow; the mob itself for a blow). Either is null when the damage has none (a fall,
 * flying into a wall, fire, drowning, starving); an entity the cache does not know has the type "" and NaN for its
 * position. Pure.
 */
public record Hit(String type, WorldView.@Nullable Thing cause, WorldView.@Nullable Thing direct) {

    /**
     * The hit a damage event describes, or null when it is about another entity than the bot ({@code self}): the
     * server sends every tracked entity's damage events. {@code causeId} and {@code directId} are entity ids, -1 for
     * none (MCProtocolLib reads the wire's id + 1 and takes the 1 off). {@code typeName} names a damage type registry
     * id (null when unknown), {@code entity} looks an id up in the entity cache (null when it is not there).
     */
    public static @Nullable Hit of(int entityId, int self, int typeId, int causeId, int directId,
                                   IntFunction<@Nullable String> typeName,
                                   IntFunction<WorldView.@Nullable Thing> entity) {
        if (entityId != self) return null;
        String t = typeName.apply(typeId);
        String type = t == null || t.isBlank() ? "damage type #" + typeId
            : t.indexOf(':') >= 0 ? t : "minecraft:" + t.toLowerCase(Locale.ROOT);
        return new Hit(type, thing(causeId, entity), thing(directId, entity));
    }

    private static WorldView.@Nullable Thing thing(int id, IntFunction<WorldView.@Nullable Thing> entity) {
        if (id < 0) return null;
        WorldView.Thing t = entity.apply(id);
        return t != null ? t : new WorldView.Thing(id, "", Double.NaN, Double.NaN, Double.NaN);
    }

    /** "minecraft:arrow from pillager #412 (arrow #977)", "minecraft:fall" for log lines. */
    public String describe() {
        StringBuilder sb = new StringBuilder(type);
        if (cause != null) sb.append(" from ").append(name(cause));
        if (direct != null && (cause == null || direct.id() != cause.id()))
            sb.append(cause == null ? " by " : " (").append(name(direct)).append(cause == null ? "" : ")");
        return sb.toString();
    }

    private static String name(WorldView.Thing t) {
        return (t.type().isEmpty() ? "entity" : t.type()) + " #" + t.id();
    }
}
