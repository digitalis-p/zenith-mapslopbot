package io.github.digitalisp.mapart.bot;

import java.util.Map;

/**
 * What a stand at the sorter's pickup spot brought: {@code gained} (item id → count picked up), why it ended
 * ({@code covered}, {@code stalled}, {@code time}, {@code slots}) and the carried counts of the wanted items then.
 */
public record PickupResult(Map<String, Integer> gained, String reason, Map<String, Integer> carried) { }
