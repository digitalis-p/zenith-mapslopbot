package io.github.digitalisp.mapart.bot;

import io.github.digitalisp.mapart.model.Pos;

import java.util.Map;

/** Contents of a container as seen when the bot had it open. CONTRACT (lead-owned). */
public record ContainerSnapshot(Pos pos, String containerType, Map<String, Integer> items,
                                int freeSlots, int totalSlots) { }
