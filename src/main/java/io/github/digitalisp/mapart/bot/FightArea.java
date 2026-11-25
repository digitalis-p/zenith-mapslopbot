package io.github.digitalisp.mapart.bot;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Where a fight happens (pure, unit-tested): the listed mob {@link #types} inside the
 * box (the tile and {@code radius} around it, from below the floor to above the centre structure), or within
 * {@code radius} of where the bot stood when the fight began. A mob elsewhere neither starts nor keeps a fight, and is
 * never chased. {@code chase} false: the bot fights only what comes within reach (it stays where it is).
 * {@code exempt}: never counted, wherever it is: a teammate (the engine sets
 * it, {@link #exempting}), so neither the engine nor the fight task ever targets one.
 */
public record FightArea(Set<String> types, int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                        double cx, double cy, double cz, double radius, boolean chase,
                        Predicate<WorldView.Mob> exempt) {

    /** Nothing exempt. */
    public static final Predicate<WorldView.Mob> NONE = m -> false;

    public FightArea(Set<String> types, int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                     double cx, double cy, double cz, double radius, boolean chase) {
        this(types, minX, maxX, minY, maxY, minZ, maxZ, cx, cy, cz, radius, chase, NONE);
    }

    public FightArea {
        if (exempt == null) exempt = NONE;
    }

    /** This area with {@code exempt} never counted (a teammate). */
    public FightArea exempting(Predicate<WorldView.Mob> exempt) {
        return new FightArea(types, minX, maxX, minY, maxY, minZ, maxZ, cx, cy, cz, radius, chase, exempt);
    }

    /** The mob counts for this fight. */
    public boolean contains(WorldView.Mob m) {
        if (!types.contains(m.type()) || exempt.test(m)) return false;
        int x = (int) Math.floor(m.x()), y = (int) Math.floor(m.y()), z = (int) Math.floor(m.z());
        if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) return true;
        return m.distSq(cx, cy, cz) <= radius * radius;
    }

    /** The mobs of {@code all} that count. */
    public List<WorldView.Mob> filter(List<WorldView.Mob> all) {
        return all.stream().filter(this::contains).toList();
    }

    /** The counted mob nearest to (x, y, z). */
    public Optional<WorldView.Mob> nearest(List<WorldView.Mob> all, double x, double y, double z) {
        return filter(all).stream().min(Comparator.comparingDouble(m -> m.distSq(x, y, z)));
    }

    /**
     * The mob to go for: the counted mob nearest to (x, y, z) among those whose id is in {@code attackers} (they hit
     * the bot, {@link MobHits#attackers}), else the counted mob nearest to it.
     */
    public Optional<WorldView.Mob> target(List<WorldView.Mob> all, java.util.Set<Integer> attackers,
                                          double x, double y, double z) {
        if (!attackers.isEmpty()) {
            Optional<WorldView.Mob> a = nearest(all.stream().filter(m -> attackers.contains(m.id())).toList(), x, y, z);
            if (a.isPresent()) return a;
        }
        return nearest(all, x, y, z);
    }

    /** A type name as the entity cache reports it: lower case, no namespace. */
    public static String typeName(String id) {
        if (id == null) return "";
        return id.substring(id.indexOf(':') + 1).trim().toLowerCase(java.util.Locale.ROOT);
    }
}
