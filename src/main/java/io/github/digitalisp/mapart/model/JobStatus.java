package io.github.digitalisp.mapart.model;

/** CONTRACT (lead-owned). */
public enum JobStatus {
    /** Waiting its turn. */
    QUEUED,
    /** Stock check failed; skipped until materials are restocked (re-checked each time the queue is scanned). */
    WAITING_MATERIALS,
    /** Currently being built. */
    ACTIVE,
    /** All tiles stored. */
    DONE,
    /**
     * A colour-suppression map with both layers printed and verified (docs/COLOR_SUPPRESSION.md): the bot is done with
     * it; the ride, the lock and the reset of the platform are manual.
     */
    READY_FOR_RIDE,
    /** Cancelled by a user. */
    CANCELLED,
    /** Gave up; see statusDetail. */
    FAILED;

    public boolean terminal() {
        return this == DONE || this == READY_FOR_RIDE || this == CANCELLED || this == FAILED;
    }
}
