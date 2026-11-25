package io.github.digitalisp.mapart.bot;

import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.OptionalInt;

/**
 * Read-only view of the client's cached world (ZenithProxy chunk cache). Only chunks the server has
 * sent are known. Call from the client tick thread. CONTRACT (lead-owned).
 */
public interface WorldView {
    boolean isLoaded(Pos pos);

    /** Full block state; {@link BlockSpec#AIR} for air or unloaded positions (check {@link #isLoaded}). */
    BlockSpec block(Pos pos);

    /** Water/lava source or flowing, or a waterlogged block. */
    boolean hasFluid(Pos pos);

    /**
     * Highest y in the column of {@code column} (its x and z) that may hold a non-air block: every block above it is
     * air. Only meaningful for a loaded column ({@link #isLoaded}). Upward scans stop here. The default is the
     * overworld build limit (319); the ZenithProxy view answers from the loaded chunk's sections (the top of the
     * highest one holding any block).
     */
    default int topY(Pos column) {
        return 319;
    }

    /**
     * Dropped-item entities (ItemEntity: one per stack on the ground) whose block position lies in the box from
     * {@code min} to {@code max} (inclusive, any order), from the client's entity cache. Empty when there is no entity
     * data for the box: a chunk of it is not loaded, or no entity cache. The server streams entities only within its
     * entity tracking range of the player (never beyond the view distance), so a 0 says nothing about farther ones.
     * The default has no entity data.
     */
    default OptionalInt itemEntities(Pos min, Pos max) {
        return OptionalInt.empty();
    }

    /**
     * A dropped-item entity near a point: its network id, the stack it holds ({@code item} null when the stack cannot be
     * read from the entity's metadata, {@code count} 0 then), and its position.
     */
    record ItemInFlight(int entityId, @org.jspecify.annotations.Nullable String item, int count, double x, double y, double z) { }

    /** The dropped-item entities within {@code radius} blocks (horizontally and vertically) of {@code at}; none by default. */
    default java.util.List<ItemInFlight> itemsNear(Pos at, double radius) {
        return java.util.List.of();
    }

    /**
     * A sign (standing, wall or hanging) from the chunk cache's block entities: its position, its block state (the
     * {@code facing} of a wall sign) and the text of both sides, every line of the front then the back joined by
     * newlines, as the server sent it (a line may be a JSON text component).
     */
    record Sign(Pos pos, BlockSpec block, String text) { }

    /**
     * The signs in the loaded chunks within the server's view distance of the player ({@code build.elytraLegs}: the
     * rocket chest's sign). The default knows none.
     */
    default java.util.List<Sign> signs() {
        return java.util.List.of();
    }

    /**
     * What {@link #signs} looked at, for {@code mapart site} ("49 chunks, 120 block entities, 3 signs"); "" when not
     * known.
     */
    default String blockEntityCensus() {
        return "";
    }

    /**
     * A living mob from the client's entity cache: its type ("pillager", no namespace), feet position and network id
     * ({@code -1}: not known, as in tests that do not care which mob hit the bot). {@code fusing}: a creeper whose swell
     * direction is on or that is ignited (its entity metadata); {@code health}: the cached health, NaN when unknown.
     * {@code uuid} and {@code name}: a player's identity (its entity UUID, its tab-list name), for the teammate check
     * ({@code coord.Teammates}); null for any other mob or when not known.
     */
    record Mob(String type, double x, double y, double z, int id, boolean fusing, float health,
               java.util.@org.jspecify.annotations.Nullable UUID uuid, @org.jspecify.annotations.Nullable String name) {
        public Mob(String type, double x, double y, double z, int id, boolean fusing, float health) {
            this(type, x, y, z, id, fusing, health, null, null);
        }

        public Mob(String type, double x, double y, double z, int id) {
            this(type, x, y, z, id, false, Float.NaN);
        }

        public Mob(String type, double x, double y, double z) {
            this(type, x, y, z, -1);
        }

        /** A player ({@code "player"}) with its identity. */
        public static Mob player(double x, double y, double z, int id, java.util.@org.jspecify.annotations.Nullable UUID uuid,
                                 @org.jspecify.annotations.Nullable String name) {
            return new Mob("player", x, y, z, id, false, Float.NaN, uuid, name);
        }

        public double distSq(double px, double py, double pz) {
            double dx = x - px, dy = y - py, dz = z - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /**
     * Living mobs whose type ({@link Mob#type}) is in {@code types}, from the client's entity cache (only those the
     * server tracks for the player). The default knows none.
     */
    default java.util.List<Mob> mobs(java.util.Set<String> types) {
        return java.util.List.of();
    }

    /**
     * Any entity from the client's entity cache: its network id, type ("minecart", no namespace) and position;
     * {@code uuid} and {@code name} a player's identity (entity UUID, tab-list name) for the teammate check,
     * null for any other entity or when not known.
     */
    record Thing(int id, String type, double x, double y, double z,
                 java.util.@org.jspecify.annotations.Nullable UUID uuid, @org.jspecify.annotations.Nullable String name) {
        public Thing(int id, String type, double x, double y, double z) {
            this(id, type, x, y, z, null, null);
        }

        public double horizontalDistSq(double px, double pz) {
            double dx = x - px, dz = z - pz;
            return dx * dx + dz * dz;
        }
    }

    /**
     * Entities whose type ({@link Thing#type}) is in {@code types}, living or not (a minecart, an ender pearl), from
     * the client's entity cache: only those the server tracks for the player (its entity tracking range). The default
     * knows none.
     */
    default java.util.List<Thing> entities(java.util.Set<String> types) {
        return java.util.List.of();
    }

    /** The entity the player rides, or null when it rides nothing (or the vehicle is not in the cache). Default null. */
    default @org.jspecify.annotations.Nullable Thing vehicle() {
        return null;
    }

    /** Player feet position (exact). */
    double playerX();

    double playerY();

    double playerZ();

    /** Block position containing the player's feet. */
    Pos playerBlock();

    /** Server view distance in chunks as last reported (for map-fill coverage). */
    int viewDistance();

    /**
     * Server simulation distance in chunks as last reported (login, set_simulation_distance); 0 = not known. The clear's
     * presence rule (engine {@code ClearPresence}) checks the flying machines' sweep against it.
     */
    default int simulationDistance() {
        return 0;
    }
}
