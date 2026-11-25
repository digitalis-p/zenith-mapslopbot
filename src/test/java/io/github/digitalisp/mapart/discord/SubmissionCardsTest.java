package io.github.digitalisp.mapart.discord;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.command.Fakes;
import io.github.digitalisp.mapart.command.QueueOps.MaterialRow;
import io.github.digitalisp.mapart.command.Reply;
import io.github.digitalisp.mapart.command.SubmissionPipeline.Accepted;
import io.github.digitalisp.mapart.command.SubmissionPipeline.Rejected;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubmissionCardsTest {
    static Job job() {
        Job j = Fakes.job("j0007", "Pine_Tree", JobStatus.QUEUED, 2, 1, Map.of());
        j.platformIds = new ArrayList<>(List.of("moss", "soul"));
        j.submitterName = "jared2013";
        return j;
    }

    @Test
    void acceptedCardShowsJobTilesBlocksPlatformsPositionMaterialsAndPreview() {
        List<MaterialRow> rows = List.of(new MaterialRow("minecraft:white_carpet", 8192, 12000),
            new MaterialRow("minecraft:black_carpet", 4000, 1200));
        Accepted a = new Accepted(job(), 2, 12192, rows, true, List.of("Ignored 1.21.5 data version"), new byte[]{1});
        Card c = SubmissionCards.of(a, List.of("Ignored attachments that are not .nbt/.zip: x.png"));
        assertEquals("Queued j0007: Pine\\_Tree", c.title());
        assertEquals(Card.WARN, c.color());
        assertTrue(c.description().startsWith("**Pine\\_Tree** is #2 in the queue.\n1 material(s) are short (marked !)."),
            c.description());
        // the facts one per line in the description, no inline fields: readable on a phone (the owner, 2026-10-28)
        assertTrue(c.description().contains("""

            **Job:** `j0007`
            **Tiles:** 2x1 (2 maps)
            **Blocks:** 12,192
            **Queue position:** #2
            **Platforms:** moss, soul
            **Submitted by:** jared2013

            **Materials** (in stock / needed):
            **white carpet**: 12,000 / 8,192
            ! **black carpet**: 1,200 / 4,000, 2,800 missing"""), c.description());
        assertTrue(c.description().endsWith("2,800 missing"), c.description());
        assertEquals(List.of("Warnings"), c.fields().stream().map(Card.Field::name).toList());
        assertFalse(c.fields().getFirst().inline());
        assertEquals("- Ignored 1.21.5 data version\n- Ignored attachments that are not .nbt/.zip: x.png",
            c.fields().getFirst().value());
        assertEquals("preview.png", c.attachment().fileName());
        assertTrue(c.length() <= Card.TOTAL_MAX);
    }

    @Test
    void fullyStockedIsGreenAndUnscannedSaysSo() {
        Accepted ok = new Accepted(job(), 1, 10, List.of(new MaterialRow("minecraft:stone", 10, 64)), true, List.of(), null);
        Card c = SubmissionCards.of(ok, List.of());
        assertEquals(Card.SUCCESS, c.color());
        assertTrue(c.description().contains("All materials are in stock."));
        assertNull(c.attachment());
        assertTrue(c.fields().stream().noneMatch(f -> f.name().equals("Warnings")));

        Accepted unscanned = new Accepted(job(), 1, 10, List.of(new MaterialRow("minecraft:stone", 10, 0)), false, List.of(), null);
        assertTrue(SubmissionCards.of(unscanned, List.of()).description().contains("Stock has not been scanned yet"));
    }

    @Test
    void noChestMaterialsAreListedApartFromShortOnes() {
        // the owner's j0005: no chest at the base for three items, one ordinary shortage, one in stock
        List<MaterialRow> rows = List.of(new MaterialRow("minecraft:white_carpet", 8192, 12000),
            new MaterialRow("minecraft:black_carpet", 4000, 1200),
            new MaterialRow("minecraft:pointed_dripstone", 40, 0, true),
            new MaterialRow("minecraft:white_candle", 12, 0, true),
            new MaterialRow("minecraft:decorated_pot", 4, 0, true));
        Card c = SubmissionCards.of(new Accepted(job(), 3, 12248, rows, true, List.of(), null), List.of());
        assertEquals(Card.WARN, c.color());
        assertTrue(c.description().startsWith("**Pine\\_Tree** is #3 in the queue.\n"
            + "✗ No chest at this base for: decorated\\_pot, pointed\\_dripstone, white\\_candle. "
            + "This job can't be built until these are stocked.\n"
            + "1 other material(s) are short (marked !). The job waits for a restock when its turn comes.\n\n**Job:**"),
            c.description());
        assertTrue(c.description().contains("\n! **black carpet**: 1,200 / 4,000, 2,800 missing\n"), c.description());
        assertTrue(c.description().contains("\n✗ **decorated pot**: 0 / 4, 4 missing"), c.description());
        assertTrue(c.description().contains("\n**white carpet**: 12,000 / 8,192\n"), c.description());
    }

    @Test
    void onlyNoChestMaterialsSayNothingAboutARestock() {
        List<MaterialRow> rows = List.of(new MaterialRow("minecraft:stone", 10, 64),
            new MaterialRow("minecraft:decorated_pot", 4, 0, true));
        Card c = SubmissionCards.of(new Accepted(job(), 1, 14, rows, true, List.of(), null), List.of());
        assertEquals(Card.WARN, c.color());
        String d = c.description();
        assertTrue(d.contains("✗ No chest at this base for: decorated\\_pot. This job can't be built until these are stocked.\n\n**Job:**"), d);
        assertFalse(d.contains("short (marked !)"), d);
        assertFalse(d.contains("All materials are in stock"), d);
    }

    @Test
    void manyNoChestMaterialsStayWithinDiscordLimits() {
        List<MaterialRow> rows = new ArrayList<>();
        for (int i = 0; i < 300; i++) rows.add(new MaterialRow("minecraft:some_really_long_block_name_" + i, 1000 + i, 0, true));
        Card c = SubmissionCards.of(new Accepted(job(), 1, 1, rows, true, List.of(), null), List.of());
        assertTrue(c.length() <= Card.TOTAL_MAX, "length " + c.length());
        assertTrue(c.description().length() <= Card.DESCRIPTION_MAX);
        assertTrue(c.description().contains(" more. This job can't be built until these are stocked."), c.description());
        assertTrue(c.description().matches("(?s).*\n… and \\d+ more"), "the list ends counted, not cut: " + c.description());
    }

    @Test
    void hugeMaterialListStaysWithinDiscordLimits() {
        List<MaterialRow> rows = new ArrayList<>();
        for (int i = 0; i < 300; i++) rows.add(new MaterialRow("minecraft:some_really_long_block_name_" + i, 1000 + i, 0));
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < 100; i++) warnings.add("warning number " + i + " with some explanation text");
        Card c = SubmissionCards.of(new Accepted(job(), 1, 1, rows, true, warnings, null), List.of());
        assertTrue(c.length() <= Card.TOTAL_MAX, "length " + c.length());
        assertTrue(c.description().length() <= Card.DESCRIPTION_MAX);
        assertTrue(c.description().matches("(?s).*\n… and \\d+ more"), "the list ends counted, not cut: " + c.description());
        for (Card.Field f : c.fields()) assertTrue(f.value().length() <= Card.FIELD_VALUE_MAX);
    }

    @Test
    void rejectedCardListsReasons() {
        Card c = SubmissionCards.of(new Rejected(List.of("Not flat: 3 top heights", "minecraft:foo is not in 1.21.4"),
            List.of("w")), List.of());
        assertEquals("Submission rejected", c.title());
        assertEquals(Card.ERROR, c.color());
        assertEquals("- Not flat: 3 top heights\n- minecraft:foo is not in 1.21.4", c.description());
        assertEquals("- w", c.fields().get(0).value());
        assertEquals("- Unknown error.", SubmissionCards.rejected(List.of()).description());
    }

    @Test
    void replyCardsUseToneColours() {
        assertEquals(Card.ERROR, Card.of(Reply.error("t", "b")).color());
        assertEquals(Card.SUCCESS, Card.of(Reply.ok("t", "b")).color());
        assertEquals(Card.WARN, Card.of(Reply.warn("t", "b")).color());
        assertEquals(Card.INFO, Card.of(Reply.info("t", "")).color());
        assertNull(Card.of(Reply.info("t", "")).description());
    }

    @Test
    void cardBuilderEnforcesLimits() {
        Card.Builder b = Card.builder().title("t".repeat(300)).description("d".repeat(5000)).content("c".repeat(2500));
        for (int i = 0; i < 30; i++) b.field("f" + i, "v".repeat(1100), false);
        Card c = b.build();
        assertEquals(Card.TITLE_MAX, c.title().length());
        assertEquals(Card.CONTENT_MAX, c.content().length());
        assertTrue(c.fields().size() <= Card.FIELDS_MAX);
        assertTrue(c.length() <= Card.TOTAL_MAX, "length " + c.length());
        b = Card.builder().title("x").field("empty", " ", true);
        assertTrue(b.build().fields().isEmpty());
    }
}
