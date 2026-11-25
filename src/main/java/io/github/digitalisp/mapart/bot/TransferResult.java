package io.github.digitalisp.mapart.bot;

import java.util.Map;

/**
 * Outcome of a withdraw/deposit. {@code moved} is item id → count actually transferred (positive).
 * {@code after} is the container as last seen (feed it to the StockIndex). {@code problem} is null or
 * a short reason the request was not fully satisfied ("container empty", "inventory full", ...).
 * CONTRACT (lead-owned).
 */
public record TransferResult(Map<String, Integer> moved, ContainerSnapshot after, String problem) { }
