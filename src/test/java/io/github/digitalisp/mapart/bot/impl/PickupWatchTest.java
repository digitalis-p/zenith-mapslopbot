package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.WorldView.ItemInFlight;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decision "are items coming?" with fake item entities. */
class PickupWatchTest {
    private static final String WHITE = "minecraft:white_carpet";

    @Test
    void aNewOrMovingWantedItemIsActivityAStillOneIsNot() {
        PickupWatch w = new PickupWatch(Set.of(WHITE));
        assertEquals(Long.MIN_VALUE, w.lastActive());
        w.update(10, List.of(new ItemInFlight(1, WHITE, 3, 5, 100, 5)));
        assertEquals(10, w.lastActive(), "new");
        w.update(11, List.of(new ItemInFlight(1, WHITE, 3, 5, 99.5, 5)));
        assertEquals(11, w.lastActive(), "falling");
        w.update(12, List.of(new ItemInFlight(1, WHITE, 3, 5, 99.5, 5)));
        w.update(40, List.of(new ItemInFlight(1, WHITE, 3, 5, 99.5, 5)));
        assertEquals(11, w.lastActive(), "lying still: no activity");
    }

    @Test
    void anUnwantedItemDoesNotHoldOffTheStallButAnUnreadableOneDoes() {
        PickupWatch w = new PickupWatch(Set.of(WHITE));
        w.update(5, List.of(new ItemInFlight(2, "minecraft:bone", 1, 0, 0, 0)));
        assertEquals(Long.MIN_VALUE, w.lastActive());
        w.update(6, List.of(new ItemInFlight(3, null, 0, 0, 0, 0)));
        assertEquals(6, w.lastActive());
        assertEquals(1, w.seen().get("?"));
    }

    @Test
    void stacksAreCountedOnceWhenTheyComeIntoView() {
        PickupWatch w = new PickupWatch(Set.of(WHITE));
        w.update(1, List.of(new ItemInFlight(1, WHITE, 4, 0, 10, 0), new ItemInFlight(2, WHITE, 6, 1, 10, 0)));
        w.update(2, List.of(new ItemInFlight(1, WHITE, 4, 0, 9, 0), new ItemInFlight(2, WHITE, 6, 1, 9, 0)));
        assertEquals(10, w.seen().get(WHITE));
        assertTrue(w.lastActive() == 2);
    }
}
