package io.github.digitalisp.mapart.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;
import io.github.digitalisp.mapart.catalog.ZenithBlockCatalog;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.discord.Card;
import io.github.digitalisp.mapart.discord.SubmissionCards;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.NamedFile;
import io.github.digitalisp.mapart.model.SuppressionArt;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.DefaultSubmissionService;
import io.github.digitalisp.mapart.render.PngRenderer;
import io.github.digitalisp.mapart.store.FileJobStore;
import io.github.digitalisp.mapart.testutil.Fixtures;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Colour-suppression upload end to end: the real parser, validation and 1.21.4 registry, the file store, and the
 * job read back from disk.
 */
class SuppressionSubmissionTest {
    @TempDir
    Path dir;
    MapArtConfig config;
    FileJobStore store;
    SubmissionPipeline pipeline;

    @BeforeEach
    void setUp() {
        config = new MapArtConfig();
        MapArtConfig.PlatformCfg moss = new MapArtConfig.PlatformCfg();
        moss.id = "moss";
        config.site.platforms.add(moss);
        MapArtConfig.PlatformCfg cs = new MapArtConfig.PlatformCfg();
        cs.id = "cs";
        cs.kind = "COLOR_SUPPRESSION";
        cs.glassY = List.of(200, 214);
        config.site.platforms.add(cs);
        store = new FileJobStore(dir, null, null);
        pipeline = new SubmissionPipeline(new DefaultSubmissionService(), new ZenithBlockCatalog(), config, store,
            new PngRenderer(), (id, b) -> 0xFF000000 | id << 8 | b, () -> null, NOPLogger.NOP_LOGGER);
    }

    SubmissionPipeline.Outcome submit(String fixture) {
        return pipeline.submit(new SubmissionPipeline.Request(List.of(Fixtures.file(fixture)), null, "42", "jared2013",
            "100", "200"));
    }

    @Test
    void crubTechUploadIsQueuedAsOneMapInPasses() throws Exception {
        var a = assertInstanceOf(SubmissionPipeline.Accepted.class, submit("png2nbt/full1214-suppress_crubtech.nbt"));
        Job j = a.job();
        assertTrue(j.isSuppression());
        assertEquals(8, j.suppression.size());
        assertEquals(8, j.tiles.size());
        assertEquals(List.of("cs"), j.platformIds);
        assertTrue(j.substitutions.isEmpty(), "color suppression is never swapped");
        assertEquals(13676 + 10032, a.totalBlocks());
        assertNotNull(a.previewPng());

        FileJobStore again = new FileJobStore(dir, null, null);
        Job back = again.get(j.id).orElseThrow();
        assertTrue(back.isSuppression());
        List<SuppressionArt.Pass> passes = new ArrayList<>();
        for (int i = 0; i < back.suppression.size(); i++) {
            Job.SuppressionPass p = back.suppression.get(i);
            TileArt art = again.loadTile(j.id, back.tiles.get(i).coord());
            passes.add(new SuppressionArt.Pass(p.layer, p.level, p.noobline, art));
        }
        SuppressionArt art = SuppressionArt.fromPasses(passes);
        assertEquals(13676, art.count(SuppressionArt.BOTTOM));
        assertEquals(10032, art.count(SuppressionArt.TOP));
        assertEquals(0, back.tiles.get(0).placed);

        Card card = SubmissionCards.accepted(a, List.of());
        assertTrue(card.toString().contains("color suppression") || card.fields().stream()
            .anyMatch(f -> f.value().contains("color suppression")), card.toString());
        assertTrue(MapArtCommand.localSummary(a).contains("color suppression, 8 passes"));
    }

    @Test
    void resubstituteRefusesColourSuppressionJobs() {
        var a = assertInstanceOf(SubmissionPipeline.Accepted.class, submit("png2nbt/full1214-suppress_crubtech.nbt"));
        var r = pipeline.resubstitute(a.job().id, false);
        assertFalse(r.ok());
        assertTrue(r.problem().contains("color-suppression"), r.problem());
    }

    /** Blocks outside {@code platforms[cs].allowedBlocks} (the CS chests' materials) refuse the upload, naming them. */
    @Test
    void anExportWithABlockTheCsChestsDoNotHoldIsRefused() {
        MapArtConfig.PlatformCfg cs = config.site.platforms.get(1);
        cs.allowedBlocks = List.of("minecraft:white_concrete", "minecraft:moss_block");
        var r = assertInstanceOf(SubmissionPipeline.Rejected.class, submit("png2nbt/full1214-suppress_crubtech.nbt"));
        String text = String.join(" ", r.reasons());
        assertTrue(text.contains("minecraft:oak_planks") && text.contains("minecraft:resin_block"), text);
        assertFalse(text.contains("hold: minecraft:moss_block") , text);
        cs.allowedBlocks = List.of("minecraft:blue_concrete", "minecraft:lime_concrete", "minecraft:cyan_concrete",
            "minecraft:red_concrete", "minecraft:oak_planks", "minecraft:white_terracotta", "minecraft:black_concrete",
            "minecraft:gray_concrete", "minecraft:white_concrete", "minecraft:brown_concrete", "minecraft:yellow_concrete",
            "minecraft:orange_concrete", "minecraft:moss_block", "minecraft:resin_block");
        assertInstanceOf(SubmissionPipeline.Accepted.class, submit("png2nbt/full1214-suppress_crubtech.nbt"));
    }

    static final String FULL = "png2nbt/full1214-suppress_crubtech.nbt";

    List<SubmissionPipeline.Outcome> submitAll(String name, NamedFile... files) {
        return pipeline.submitAll(new SubmissionPipeline.Request(List.of(files), name, "42", "jared2013", "100", "200"));
    }

    /** Live 2026-11-15: a zip of one picture's colour-suppression maps was refused as "not supported yet". */
    @Test
    void aZipOfSeveralColourSuppressionMapsIsQueuedAsOneJobEach() {
        NamedFile zip = new NamedFile("Mars_Curiosity_Rover.zip", Fixtures.zip(List.of(
            Fixtures.renamed(FULL, "IMG_5465-suppress_crubtech-1_1.nbt"),
            Fixtures.renamed(FULL, "IMG_5465-suppress_crubtech-0_1.nbt"),
            Fixtures.renamed(FULL, "IMG_5465-suppress_crubtech-1_0.nbt"))));
        List<SubmissionPipeline.Outcome> out = submitAll(null, zip);
        assertEquals(3, out.size());
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var a = assertInstanceOf(SubmissionPipeline.Accepted.class, out.get(i));
            assertTrue(a.job().isSuppression());
            assertEquals(8, a.job().tiles.size());
            assertEquals(i + 1, a.queuePosition());
            assertEquals("200", a.job().discordMessageId);
            assertNotNull(a.previewPng());
            names.add(a.job().name);
        }
        assertEquals(List.of("Mars_Curiosity_Rover 0,1", "Mars_Curiosity_Rover 1,0", "Mars_Curiosity_Rover 1,1"), names);
        assertEquals(3, store.list().size());
    }

    @Test
    void theNameInTheUploadMessageNamesEveryMapOfTheUpload() {
        var out = submitAll("Mars Curiosity Rover", Fixtures.renamed(FULL, "IMG_5465-suppress_crubtech-0_0.nbt"),
            Fixtures.renamed(FULL, "IMG_5465-suppress_crubtech-0_1.nbt"));
        assertEquals(2, out.size());
        assertEquals("Mars Curiosity Rover 0,0", assertInstanceOf(SubmissionPipeline.Accepted.class, out.get(0)).job().name);
        assertEquals("Mars Curiosity Rover 0,1", assertInstanceOf(SubmissionPipeline.Accepted.class, out.get(1)).job().name);
    }

    @Test
    void aRefusedMapDoesNotHoldBackTheOthersOfTheUpload() {
        var out = submitAll("Rover", Fixtures.renamed(FULL, "r-suppress_crubtech-0_0.nbt"),
            Fixtures.renamed("png2nbt/fullsteps-suppress_crubtech.nbt", "r-suppress_crubtech-0_1.nbt"),
            Fixtures.renamed(FULL, "r-suppress_crubtech-0_2.nbt"));
        assertEquals(3, out.size());
        assertEquals("Rover 0,0", assertInstanceOf(SubmissionPipeline.Accepted.class, out.get(0)).job().name);
        var r = assertInstanceOf(SubmissionPipeline.Rejected.class, out.get(1));
        assertTrue(r.reasons().getFirst().startsWith("'r-suppress_crubtech-0_1.nbt' is a color-suppression (two-layer)"
            + " map that the platform cannot build"), r.reasons().getFirst());
        assertEquals("Rover 0,2", assertInstanceOf(SubmissionPipeline.Accepted.class, out.get(2)).job().name);
        assertEquals(2, store.list().size());
    }

    @Test
    void oneJobUploadsGoThroughSubmitAllUnchanged() {
        var out = submitAll(null, Fixtures.file(FULL));
        assertEquals(1, out.size());
        assertEquals("full1214", assertInstanceOf(SubmissionPipeline.Accepted.class, out.getFirst()).job().name);
        var mixed = submitAll(null, Fixtures.renamed(FULL, "a-suppress_crubtech-0_0.nbt"), Fixtures.file(Fixtures.A));
        assertEquals(1, mixed.size());
        String why = assertInstanceOf(SubmissionPipeline.Rejected.class, mixed.getFirst()).reasons().getFirst();
        assertTrue(why.startsWith("1 of the 2 files is a color-suppression (two-layer) map and"), why);
        assertEquals(1, store.list().size());
    }

    @Test
    void aLongNameIsCutSoTheTileLabelStays() {
        String n = SubmissionPipeline.partName("Galileo Probe over Jupiter and Io and Europa and Ganymede", "", "10,11");
        assertEquals("Galileo Probe over Jupiter and Io and Europa 10,11", n);
        assertEquals(50, n.length());
        assertEquals("zipname 0,1", SubmissionPipeline.partName(null, "zipname", "0,1"));
        assertEquals("left", SubmissionPipeline.partName("", "", "left"));
        assertEquals("Rover left", SubmissionPipeline.partName("Rover", "", "left"));
    }

    @Test
    void withTheSuppressionPlatformOffTheUploadIsRefused() {
        config.site.platforms.get(1).enabled = false;
        var r = assertInstanceOf(SubmissionPipeline.Rejected.class, submit("png2nbt/full1214-suppress_crubtech.nbt"));
        assertTrue(r.reasons().getFirst().contains("not supported yet"), r.reasons().getFirst());
    }
}
