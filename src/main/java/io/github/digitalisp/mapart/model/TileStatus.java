package io.github.digitalisp.mapart.model;

/** CONTRACT (lead-owned). */
public enum TileStatus {
    PENDING,
    ACTIVE,
    /** Locked, renamed, stored in the output chest. */
    STORED,
    FAILED
}
