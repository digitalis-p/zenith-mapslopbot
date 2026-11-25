package io.github.digitalisp.mapart.coord;

/**
 * The engine must stop working on {@code unit} now ({@link Coordination#pollAbandon}): within a few ticks it stops the
 * running task (a container task closes its menu and gives its claim back), releases the platform, and parks IDLE where
 * it stands; the tile stays as it is in the world for the next holder.
 *
 * @param unit   the grant to stop (matched by job, key and fence: {@link Unit#sameGrant})
 * @param reason why
 * @param detail the reason text, "" when none
 */
public record Abandon(Unit unit, Reason reason, String detail) {
    public enum Reason {
        /** The coordination took the unit back. */
        REVOKED,
        /** The unit's lease ran out. */
        LEASE_LOST,
        /** The platform claim was lost or refused. */
        CLAIM_LOST
    }

    public Abandon {
        detail = detail == null ? "" : detail;
    }
}
