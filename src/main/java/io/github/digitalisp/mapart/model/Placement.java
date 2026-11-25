package io.github.digitalisp.mapart.model;

/** One block to place: world position, target state, and the item that places it. CONTRACT (lead-owned). */
public record Placement(Pos pos, BlockSpec block, String itemId) { }
