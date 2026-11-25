package io.github.digitalisp.mapart.command;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FmtTest {
    @Test
    void literalKeepsUnderscoresOfPaths() {
        // live: "mapart submit .../C_carpet_flat_2x1_split__mapart.zip" echoed as "Ccarpetflat2x1split__mapart.zip"
        assertEquals("`/path/to/C_carpet_flat_2x1_split__mapart.zip`",
            Fmt.literal("/path/to/C_carpet_flat_2x1_split__mapart.zip"));
        assertEquals("C:\\\\maps\\\\my\\_art.zip", Fmt.literal("C:\\maps\\my_art.zip"));
        assertEquals("", Fmt.literal(""));
        assertEquals("a\\`b", Fmt.literal("a`b"));
    }

    @Test
    void numbersAndPercent() {
        assertEquals("12,345", Fmt.count(12345));
        assertEquals("0", Fmt.count(0));
        assertEquals("45.2%", Fmt.percent(7400, 16384));
        assertEquals("0.0%", Fmt.percent(5, 0));
        assertEquals("100.0%", Fmt.percent(16384, 16384));
    }

    @Test
    void durations() {
        assertEquals("unknown", Fmt.duration(-1));
        assertEquals("0s", Fmt.duration(0));
        assertEquals("59s", Fmt.duration(59));
        assertEquals("1m", Fmt.duration(60));
        assertEquals("59m", Fmt.duration(3599));
        assertEquals("1h 05m", Fmt.duration(3900));
        assertEquals("1d 02h", Fmt.duration(93600));
    }

    @Test
    void textHelpers() {
        assertEquals("white_carpet", Fmt.item("minecraft:white_carpet"));
        assertEquals("mod:thing", Fmt.item("mod:thing"));
        assertEquals("a\\_b\\*c", Fmt.escape("a_b*c"));
        assertEquals("'''x'", Fmt.inCode("```x`"));
        assertEquals("abc", Fmt.truncate("abc", 3));
        assertEquals("ab…", Fmt.truncate("abcd", 3));
        assertEquals("ab ", Fmt.pad("ab", 3));
        assertEquals("ab~", Fmt.pad("abcd", 3));
        assertEquals("  ab", Fmt.padLeft("ab", 4));
        assertEquals("```\na\nb\n```", Fmt.codeBlock(List.of("a", "b")));
    }

    @Test
    void bulletsStopBeforeLimit() {
        assertEquals("- a\n- b", Fmt.bullets(List.of("a", "b"), 100));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) many.add("reason number " + i);
        String s = Fmt.bullets(many, 200);
        assertTrue(s.length() <= 200, s.length() + ": " + s);
        assertTrue(s.matches("(?s).*\n… and \\d+ more"), s);
        String all = Fmt.bullets(many, 10_000);
        assertEquals(50, all.split("\n").length);
    }
}
