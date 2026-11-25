package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Map names within the anvil's 50 characters: the job part is shortened, the tile stays whole. */
class MapNameFitTest {
    @Test
    void aNameThatFitsIsUntouched() {
        assertEquals("Galileo Probe over Jupiter and Io 2,3",
            BuildEngine.fitName("{job} {x},{z}", "Galileo Probe over Jupiter and Io", 2, 3, "j0008", 50));
    }

    @Test
    void aLongJobNameIsShortenedNotTheTile() {
        String job = "abcdefghij abcdefghij abcdefghij abcdefghij abcdefghij"; // 54
        String s = BuildEngine.fitName("{job} {x},{z}", job, 10, 7, "j0001", 50);
        assertEquals(50, s.length(), s);
        assertTrue(s.endsWith(" 10,7"), s);
        assertEquals("abcdefghij abcdefghij abcdefghij abcdefghij a 10,7", s);
    }

    @Test
    void aSurrogatePairIsNeverCutInHalf() {
        String job = "a".repeat(44) + "\uD83D\uDE00x"; // 47 chars, an emoji at 44-45
        String s = BuildEngine.fitName("{job} {x},{z}", job, 1, 1, "j0001", 50);
        assertTrue(s.length() <= 50, s);
        assertTrue(!Character.isHighSurrogate(s.charAt(s.length() - " 1,1".length() - 1)), s);
    }
}
