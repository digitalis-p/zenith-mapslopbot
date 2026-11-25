package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.command.Fmt;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class CoordFormatTest {
    // Synthetic base far from spawn, built arithmetically (not a real coordinate). Origin = moss map centre.
    static final int OX = -128 * 7813;
    static final int OZ = -128 * 15625;
    /** A second (disabled) platform 80 map widths east of the origin. */
    static final int FAR_PLATFORM_X = OX + 80 * 128;

    static MapArtConfig.PlatformCfg platform(String id, String kind, boolean enabled, int centreX, int centreZ) {
        MapArtConfig.PlatformCfg p = new MapArtConfig.PlatformCfg();
        p.id = id;
        p.kind = kind;
        p.enabled = enabled;
        p.minX = centreX - 64;
        p.minZ = centreZ - 64;
        p.artY = 173;
        p.center = new Pos(centreX, 179, centreZ);
        return p;
    }

    static MapArtConfig config(String mode) {
        MapArtConfig c = new MapArtConfig();
        c.discord.coordinates = mode;
        c.site.platforms.add(platform("old", "MOSS", false, FAR_PLATFORM_X, OZ));
        c.site.platforms.add(platform("moss", "MOSS", true, OX, OZ));
        return c;
    }

    static final CoordFormat REL = CoordFormat.of(config("RELATIVE"));

    static String rel(String s) {
        CoordFormat.Converted c = REL.convert(s);
        assertEquals(0, c.hits(), "known format must not need the safety net: " + c.text());
        return c.text();
    }

    @Test
    void defaultIsRealAndLeavesTextUnchanged() {
        assertEquals("REAL", new MapArtConfig().discord.coordinates);
        MapArtConfig c = config(new MapArtConfig().discord.coordinates);
        CoordFormat f = CoordFormat.of(c);
        assertFalse(f.relative());
        assertNull(f.problem());
        String text = "chest at " + new Pos(OX + 5, 64, OZ - 7) + ", range " + (OX - 28) + ".." + (OX - 21)
            + ", " + CoordFormat.point(OX + 5000.5, 70, OZ);
        assertEquals(new CoordFormat.Converted(text, 0), f.convert(text));
        assertSame(text, f.discord(text, "t", new RecordingLogger()));
        assertFalse(CoordFormat.of(config(" real ")).relative());
        assertFalse(CoordFormat.of(config(null)).relative());
    }

    @Test
    void originIsTheFirstEnabledMossOrSoulSandPlatform() {
        assertTrue(REL.relative());
        assertEquals(OX, REL.originX());
        assertEquals(OZ, REL.originZ());
        MapArtConfig c = config("relative");
        c.site.platforms.add(0, platform("cs", "COLOR_SUPPRESSION", true, OX - 128, OZ));
        c.site.platforms.add(1, platform("soul", "soul_sand", true, OX + 128, OZ));
        CoordFormat f = CoordFormat.of(c);
        assertEquals(OX + 128, f.originX());
        assertNull(f.problem());
    }

    @Test
    void relativeWithoutPlatformFallsBackToRealAndWarnsOnce() {
        MapArtConfig c = config("RELATIVE");
        c.site.platforms.forEach(p -> p.enabled = false);
        CoordFormat f = CoordFormat.of(c);
        assertFalse(f.relative());
        assertTrue(f.problem().contains("no enabled MOSS or SOUL_SAND platform"), f.problem());
        RecordingLogger log = new RecordingLogger();
        Supplier<CoordFormat> live = CoordFormat.live(c, log);
        for (int i = 0; i < 3; i++) assertFalse(live.get().relative());
        assertEquals(1, log.at(Level.WARN).size(), log.lines.toString());
        c.site.platforms.get(1).enabled = true;
        assertTrue(live.get().relative(), "config changes apply to the next message");
    }

    @Test
    void unknownModeIsTreatedAsRelative() {
        CoordFormat f = CoordFormat.of(config("relativ"));
        assertTrue(f.relative());
        assertTrue(f.problem().contains("relativ"), f.problem());
    }

    @Test
    void pointsPositionsAndPairsAreRelative() {
        assertEquals("from -27.5, 70.0, 31.5 -> 0.0, 64.0, -0.5",
            rel("from " + CoordFormat.point(OX - 27.5, 70, OZ + 31.5) + " -> " + CoordFormat.point(OX, 64, OZ - 0.5)));
        assertEquals("bedrock at [5,173,-7].", rel("bedrock at " + new Pos(OX + 5, 173, OZ - 7) + "."));
        assertEquals("probe 5,173,-7 is air", rel("probe " + new Pos(OX + 5, 173, OZ - 7).key() + " is air"));
        assertEquals("map area -64,-64 to 63,63",
            rel("map area " + (OX - 64) + "," + (OZ - 64) + " to " + (OX + 63) + "," + (OZ + 63)));
        assertEquals("near the old platform [10245,64,3]", rel("near the old platform "
            + new Pos(FAR_PLATFORM_X + 5, 64, OZ + 3)));
    }

    @Test
    void pointFarFromEveryPlatformIsHidden() {
        assertEquals("-> " + CoordFormat.FAR, rel("-> " + CoordFormat.point(OX + 5000.5, 70, OZ)));
        assertEquals("at " + CoordFormat.FAR, rel("at " + CoordFormat.point(0.5, 120, 0.5)));
    }

    @Test
    void safetyNetCatchesLooseFormats() {
        assertNet("lane x -28..-21", "lane x " + (OX - 28) + ".." + (OX - 21), 2);
        assertNet("tile_z-72-169.png", "tile_z-72" + (OZ - 169) + ".png", 1);
        assertNet("GoalBlock{x=3,y=70,z=-4}", "GoalBlock{x=" + (OX + 3) + ",y=70,z=" + (OZ - 4) + "}", 2);
        assertNet("magnitude -28", "magnitude " + (-(OX - 28)), 1);
        assertNet("at -27.5 east", "at " + (OX - 27.5) + " east", 1);
    }

    @Test
    void truncatedDigitsThatCouldStartACoordinateAreRemoved() {
        String cut = Fmt.truncate("chest at " + new Pos(OX - 28, 64, OZ + 5), 14);
        assertNet("chest at […", cut, 1);
        assertNet("~", Fmt.pad(String.valueOf(OX - 28), 5), 1);
        assertEquals("tile 5…", rel("tile 5…"));
    }

    @Test
    void ordinaryNumbersAreLeftAlone() {
        String text = "Started #j0007 Pine [1,0] on moss: 16384 blocks (12,345 / 1,638,400), 45.2%, ETA 1h 05m "
            + "<@&123456789012345678>";
        assertEquals(text, rel(text));
    }

    @Test
    void distanceIsExactUpToNearThenCapped() {
        assertEquals("20.0 blocks", CoordFormat.distance(20));
        assertEquals("1000.0 blocks", CoordFormat.distance(1000));
        assertEquals("over 1,000 blocks", CoordFormat.distance(1000.1));
    }

    @Test
    void discordLogsAWarnNamingTheTitleOnlyForSafetyNetHits() {
        RecordingLogger log = new RecordingLogger();
        assertEquals("[5,173,-7]", REL.discord(new Pos(OX + 5, 173, OZ - 7).toString(), "Known", log));
        assertTrue(log.lines.isEmpty(), log.lines.toString());
        assertEquals("-28..-21", REL.discord((OX - 28) + ".." + (OX - 21), "Odd format", log));
        assertEquals(1, log.at(Level.WARN).size(), log.lines.toString());
        assertTrue(log.at(Level.WARN).getFirst().contains("Odd format"), log.lines.toString());
    }

    private static void assertNet(String expected, String text, int hits) {
        assertEquals(new CoordFormat.Converted(expected, hits), REL.convert(text), text);
    }
}
