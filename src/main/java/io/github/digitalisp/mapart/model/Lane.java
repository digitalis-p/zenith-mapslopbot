package io.github.digitalisp.mapart.model;

import java.util.List;

/**
 * A straight walking line across the platform plus the placements made from it, in placement order.
 * The bot walks from {@code from} to {@code to} (both standing positions at art level, i.e. feet Y =
 * artY when walking on placed art, or the floor otherwise — the executor decides) and places every
 * placement within reach as it passes. {@code index} is the lane's column number across the tile
 * (stable when the tile is re-planned), not its position in the returned list. CONTRACT (lead-owned).
 */
public record Lane(int index, Pos from, Pos to, List<Placement> placements) { }
