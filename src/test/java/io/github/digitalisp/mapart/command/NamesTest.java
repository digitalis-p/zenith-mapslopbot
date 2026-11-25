package io.github.digitalisp.mapart.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NamesTest {
    @Test
    void submissionFiles() {
        assertTrue(Names.isSubmissionFile("art.nbt"));
        assertTrue(Names.isSubmissionFile("ART.NBT"));
        assertTrue(Names.isSubmissionFile("tiles.Zip"));
        assertFalse(Names.isSubmissionFile("preview.png"));
        assertFalse(Names.isSubmissionFile("art.nbt.png"));
        assertFalse(Names.isSubmissionFile("nbt"));
        assertFalse(Names.isSubmissionFile(null));
    }

    @Test
    void sanitizeKeepsLettersDigitsAndSafePunctuation() {
        assertEquals("Pine   Tree #2 (v1.5)", Names.sanitize("  Pine   Tree #2 (v1.5)  "));
        // live 2026-10-15: brackets of other scripts and deliberate double spaces stay
        assertEquals("Ａｂａｄｄｏｎ 〘  Last Art  v2〙", Names.sanitize("Ａｂａｄｄｏｎ 〘  Last Art  v2〙"));
        assertEquals("「art」 «v2»", Names.sanitize("「art」 «v2»"));
        assertEquals("〘 Last Art Ⅱ〙 ② ᵛ² ｖ２ ᴠ²", Names.sanitize("〘 Last Art Ⅱ〙 ② ᵛ² ｖ２ ᴠ²"));
        assertEquals("red gold", Names.sanitize("red§cgold".replace("§c", "§ ")));
        assertEquals("sizedreisen_0_0", Names.sanitize("sizedreisen_0_0"));
        assertEquals("Café Ünïcode", Names.sanitize("Café Ünïcode"));
    }

    @Test
    void sanitizeDropsMarkdownMentionsUrlsAndExtraLines() {
        assertEquals("bold art", Names.sanitize("**bold** ~~art~~"));
        assertEquals("for", Names.sanitize("<@123456789012345678> for <@&42> <#99> <:kek:123>"));
        assertEquals("see", Names.sanitize("see https://example.com/x?y=1"));
        assertEquals("first line", Names.sanitize("first line\nsecond line"));
        assertEquals("a b", Names.sanitize("a`@everyone`b".replace("@everyone", " ")));
        assertEquals("", Names.sanitize("```\n"));
        assertEquals("", Names.sanitize(null));
    }

    @Test
    void sanitizeCapsAt50Characters() {
        // the anvil's limit (live 2026-10-16: the old cap of 32 cut "... Jupiter and Io" to "... and I")
        assertEquals("Galileo Probe over Jupiter and Io", Names.sanitize("Galileo Probe over Jupiter and Io"));
        String s = Names.sanitize("abcdefghij abcdefghij abcdefghij abcdefghij abcdefghij");
        assertEquals(50, s.length(), s);
        // a cut that ends in a space is trimmed
        assertEquals("abcdefghij abcdefghij abcdefghij abcdefghij abcde",
            Names.sanitize("abcdefghij abcdefghij abcdefghij abcdefghij abcde xyz"));
        assertTrue(Names.sanitize("x".repeat(100)).length() <= Names.MAX_NAME);
    }

    @Test
    void displayNameFallsBackToBaseName() {
        assertEquals("Pine", Names.displayName("Pine", "base"));
        assertEquals("sizedreisen", Names.displayName("", "sizedreisen"));
        assertEquals("sizedreisen", Names.displayName("<@123>", "sizedreisen"));
        assertEquals("map", Names.displayName(null, "***"));
    }

    @Test
    void nameFromMessageStripsPrefixAndSubmit() {
        assertEquals("Pine Tree", Names.nameFromMessage("!map submit Pine Tree", "!map"));
        assertEquals("Pine Tree", Names.nameFromMessage("!MAP Pine Tree", "!map"));
        assertEquals("", Names.nameFromMessage("!map submit", "!map"));
        assertEquals("submitted art", Names.nameFromMessage("!map submitted art", "!map"));
        assertEquals("", Names.nameFromMessage("!mapfoo", "!map")); // not the prefix, but a !word
        assertEquals("just text", Names.nameFromMessage("  just text ", "!map"));
        assertEquals("", Names.nameFromMessage(null, "!map"));
    }

    @Test
    void nameFromMessageDropsCommandLikeLeadIn() {
        // what the owner typed with uploads, and the names wanted
        assertEquals("verity", Names.nameFromMessage("!map queue verity", "!map"));
        assertEquals("flarpity", Names.nameFromMessage("!queue flarpity", "!map"));
        assertEquals("Sunset Big", Names.nameFromMessage("!MAP submit Sunset Big", "!map"));
        assertEquals("Sunset", Names.nameFromMessage("Sunset", "!map"));
        // each subcommand word that means "submit", any case, once
        for (String w : List.of("queue", "q", "submit", "add", "build", "QUEUE", "Add"))
            assertEquals("verity", Names.nameFromMessage("!map " + w + " verity", "!map"), w);
        assertEquals("verity", Names.nameFromMessage("!map q verity", "!map"));
        assertEquals("verity", Names.nameFromMessage("!queue add verity", "!map"));
        assertEquals("queue verity", Names.nameFromMessage("!map queue queue verity", "!map"));
        // several !words, a newline after the lead-in, a custom prefix
        assertEquals("flarpity", Names.nameFromMessage("!map !q flarpity", "!map"));
        assertEquals("verity", Names.nameFromMessage("!map queue\nverity", "!map"));
        assertEquals("verity", Names.nameFromMessage(".art add verity", ".art"));
        assertEquals("queued art", Names.nameFromMessage("!map queued art", "!map"));
    }

    @Test
    void nameFromMessageEmptyFallsBackToTheFileName() {
        assertEquals("", Names.nameFromMessage("", "!map"));
        assertEquals("", Names.nameFromMessage("   ", "!map"));
        assertEquals("", Names.nameFromMessage("!map", "!map"));
        assertEquals("", Names.nameFromMessage("!MAP queue", "!map"));
        assertEquals("", Names.nameFromMessage("!queue", "!map"));
        assertEquals("sunset_0_0", Names.displayName(Names.nameFromMessage("!map", "!map"), "sunset_0_0"));
        assertEquals("sunset_0_0", Names.displayName(Names.nameFromMessage("!map submit", "!map"), "sunset_0_0"));
        assertEquals("sunset_0_0", Names.displayName(Names.nameFromMessage("", "!map"), "sunset_0_0"));
        assertEquals("flarpity", Names.displayName(Names.nameFromMessage("!queue flarpity", "!map"), "sunset_0_0"));
    }

    @Test
    void nameFromMessageKeepsBangsAndCommandWordsAfterTheStart() {
        assertEquals("Wow! Sunset", Names.nameFromMessage("Wow! Sunset", "!map"));
        assertEquals("Sunset !big", Names.nameFromMessage("!map queue Sunset !big", "!map"));
        assertEquals("verity!", Names.nameFromMessage("verity!", "!map"));
        assertEquals("Sunset queue", Names.nameFromMessage("Sunset queue", "!map"));
        // a submit word only counts after a prefix or !word: titles that start with one stay whole
        assertEquals("Build the Wall", Names.nameFromMessage("Build the Wall", "!map"));
        assertEquals("Add Sunset", Names.nameFromMessage("Add Sunset", "!map"));
        assertEquals("q verity", Names.nameFromMessage("q verity", "!map"));
        assertEquals("queue", Names.nameFromMessage("queue", "!map"));
        assertEquals("the Wall", Names.nameFromMessage("!map build the Wall", "!map"));
        assertEquals("! verity", Names.nameFromMessage("! verity", "!map"));
        assertEquals("Wow! Sunset", Names.displayName(Names.nameFromMessage("!map Wow! Sunset", "!map"), "x"));
    }

    @Test
    void uploadFileNames() {
        assertEquals("map_1_0.png", Names.fileName("map 1,0.png", ".png"));
        assertEquals("diff.png", Names.fileName("diff", ".png"));
        assertEquals("image.png", Names.fileName("", ".png"));
        assertEquals("image.png", Names.fileName(".png", ".png"));
        assertEquals("image.png", Names.fileName(null, ".png"));
        assertEquals(64 + 4, Names.fileName("a".repeat(200), ".png").length());
    }
}
