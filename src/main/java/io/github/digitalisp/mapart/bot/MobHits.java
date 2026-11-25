package io.github.digitalisp.mapart.bot;

import java.util.Set;

/**
 * The hits on the bot by listed mobs ({@code combat.mobs}) as the engine counts them from the server's damage events
 * ({@link Hit}): a mob's blow, or a projectile a mob shot. A fall, flying into a wall,
 * fire, drowning or a player's hit is none. Read on the client tick thread.
 */
public interface MobHits {
    /** Hits by listed mobs so far: a fight compares the count with the last one it saw to notice a new hit. */
    long count();

    /** Entity ids of the listed mobs that hit the bot lately (the engine's hurt window): fought first. */
    Set<Integer> attackers();

    /** No hits at all. */
    MobHits NONE = new MobHits() {
        @Override
        public long count() {
            return 0;
        }

        @Override
        public Set<Integer> attackers() {
            return Set.of();
        }
    };
}
