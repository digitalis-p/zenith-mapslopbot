package io.github.digitalisp.mapart.bot;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MapHold#done}: the early end of a map hold ({@code map.holdUntilMatch}). */
class MapHoldTest {
    @Test
    void plainHoldsTheFullTime() {
        assertFalse(MapHold.PLAIN.done(99, 100, 7));
        assertTrue(MapHold.PLAIN.done(100, 100, 7));
    }

    @Test
    void endsOnceTheMapShowsTheArtAfterTheMinimum() {
        AtomicInteger asked = new AtomicInteger();
        MapHold h = new MapHold(null, id -> {
            asked.incrementAndGet();
            return id == 7;
        }, 20);
        assertFalse(h.done(19, 100, 7), "not before the minimum");
        assertEquals(0, asked.get(), "the map is not even looked at before the minimum");
        assertTrue(h.done(20, 100, 7));
        assertFalse(h.done(50, 100, 8), "another map does not show the art: the hold goes on");
        assertTrue(h.done(100, 100, 8), "and ends at the hold time as before");
    }
}
