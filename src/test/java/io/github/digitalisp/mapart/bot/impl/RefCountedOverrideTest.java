package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefCountedOverrideTest {
    /** Stand-in for the pathfinder settings. */
    private static final class Settings {
        boolean allowPlace = true;
        boolean allowBreak = true;
    }

    private record Snap(boolean place, boolean brk) { }

    private static RefCountedOverride<Snap> override(Settings s, int[] restores) {
        return new RefCountedOverride<>(
            () -> new Snap(s.allowPlace, s.allowBreak),
            () -> {
                s.allowPlace = false;
                s.allowBreak = false;
            },
            snap -> {
                restores[0]++;
                s.allowPlace = snap.place();
                s.allowBreak = snap.brk();
            });
    }

    @Test
    void singleHolderRestores() {
        Settings s = new Settings();
        int[] restores = {0};
        var o = override(s, restores);
        var h = o.acquire();
        assertFalse(s.allowPlace);
        assertFalse(s.allowBreak);
        h.release();
        assertTrue(s.allowPlace);
        assertTrue(s.allowBreak);
        assertEquals(1, restores[0]);
        assertNull(o.saved());
    }

    @Test
    void overlappingHoldersRestoreOnlyWhenLastReleasesInAnyOrder() {
        Settings s = new Settings();
        s.allowBreak = false; // user's own setting must survive
        int[] restores = {0};
        var o = override(s, restores);
        var a = o.acquire();
        var b = o.acquire();
        assertEquals(2, o.holders());
        a.release();
        assertFalse(s.allowPlace, "still held by b");
        var c = o.acquire();
        b.release();
        assertFalse(s.allowPlace, "still held by c");
        c.release();
        assertTrue(s.allowPlace);
        assertFalse(s.allowBreak, "original false value restored, not forced true");
        assertEquals(1, restores[0]);
        assertEquals(0, o.holders());
    }

    @Test
    void releaseIsIdempotent() {
        Settings s = new Settings();
        int[] restores = {0};
        var o = override(s, restores);
        var a = o.acquire();
        var b = o.acquire();
        a.release();
        a.release();
        a.release();
        assertEquals(1, o.holders(), "double release of a must not release b's claim");
        assertFalse(s.allowPlace);
        b.release();
        assertTrue(s.allowPlace);
        assertTrue(a.isReleased() && b.isReleased());
        assertEquals(1, restores[0]);
    }

    @Test
    void enforceReappliesWhileHeldOnly() {
        Settings s = new Settings();
        int[] restores = {0};
        var o = override(s, restores);
        o.enforce();
        assertTrue(s.allowPlace, "no holder: enforce does nothing");
        var h = o.acquire();
        s.allowPlace = true; // someone flipped it mid-walk
        o.enforce();
        assertFalse(s.allowPlace);
        h.release();
        assertTrue(s.allowPlace);
    }

    @Test
    void reacquireAfterFullReleaseCapturesFreshValues() {
        Settings s = new Settings();
        int[] restores = {0};
        var o = override(s, restores);
        o.acquire().release();
        s.allowPlace = false; // user changed it between walks
        var h = o.acquire();
        h.release();
        assertFalse(s.allowPlace);
        assertEquals(2, restores[0]);
    }
}
