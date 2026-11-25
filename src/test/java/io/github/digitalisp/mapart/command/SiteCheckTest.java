package io.github.digitalisp.mapart.command;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SiteCheckTest {
    static MapArtConfig complete() {
        MapArtConfig c = new MapArtConfig();
        MapArtConfig.PlatformCfg moss = new MapArtConfig.PlatformCfg();
        moss.id = "moss";
        moss.kind = "MOSS";
        moss.minX = -64;
        moss.minZ = 64;
        moss.artY = 65;
        moss.center = new Pos(0, 65, 128);
        MapArtConfig.ActuatorCfg button = new MapArtConfig.ActuatorCfg();
        button.pos = new Pos(0, 66, 128);
        moss.clear.add(button);
        moss.collectionOn = new MapArtConfig.ActuatorCfg();
        moss.collectionOn.pos = new Pos(63, 66, 65);
        moss.collectionOff = new MapArtConfig.ActuatorCfg();
        moss.collectionOff.pos = new Pos(63, 66, 63);
        moss.collectionIndicator = new Pos(63, 66, 64);
        c.site.platforms.add(moss);
        MapArtConfig.RegionCfg region = new MapArtConfig.RegionCfg();
        region.min = new Pos(0, 60, 0);
        region.max = new Pos(10, 70, 10);
        var s = c.site.storage;
        s.materialRegions.add(region);
        s.returnChests.add(new Pos(1, 64, 1));
        s.mapChests.add(new Pos(2, 64, 1));
        s.paneChests.add(new Pos(3, 64, 1));
        s.outputChests.add(new Pos(4, 64, 1));
        s.xpChests.add(new Pos(5, 64, 1));
        s.anvilChests.add(new Pos(6, 64, 1));
        s.toolChests.add(new Pos(7, 64, 1));
        s.foodChests.add(new Pos(8, 64, 1));
        c.site.cartographyTable = new Pos(9, 64, 1);
        c.site.anvil = new Pos(10, 64, 1);
        return c;
    }

    @Test
    void completeSiteHasNoWarnings() {
        SiteCheck.Report r = SiteCheck.check(complete());
        assertEquals(List.of(), r.warnings());
        assertTrue(r.lines().get(1).startsWith("Platform moss [MOSS] enabled: map area -64,64 to 63,191, art Y 65"), r.lines().get(1));
        Reply reply = SiteCheck.reply(complete());
        assertEquals(Reply.Tone.SUCCESS, reply.tone());
        assertTrue(reply.body().endsWith("No problems found."));
        Reply kit = SiteCheck.reply(complete(), null, "Rocket chest (build.elytraLegs on): found by its sign at 1,2,3");
        assertTrue(kit.body().contains("Rocket chest (build.elytraLegs on): found by its sign at 1,2,3\n"), kit.body());
        assertEquals(reply.body(), SiteCheck.reply(complete(), null, " ").body(), "a blank report adds no line");
    }

    /** A site with many named no-go zones filled the reply before the warnings: "Warnings (1)" with nothing below. */
    @Test
    void longSiteStillShowsItsWarnings() {
        MapArtConfig c = complete();
        for (int i = 0; i < 40; i++) {
            MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
            r.min = new Pos(5000 + i * 10, 10, 5000);
            r.max = new Pos(5005 + i * 10, 20, 5005);
            r.name = "machinery far from everything the bot uses, zone number " + i + ", with a long description of it";
            c.site.noGoZones.add(r);
        }
        c.site.platforms.get(0).minX = 10;
        Reply reply = SiteCheck.reply(c);
        assertEquals(Reply.Tone.WARN, reply.tone());
        assertTrue(reply.body().length() <= 3900, "fits a reply: " + reply.body().length());
        assertTrue(reply.body().contains("- moss: not map-grid aligned"), reply.body());
    }

    @Test
    void misalignedPlatformSuggestsNearestCorner() {
        MapArtConfig c = complete();
        c.site.platforms.get(0).minX = 10;
        c.site.platforms.get(0).minZ = -100;
        List<String> w = SiteCheck.check(c).warnings();
        assertTrue(w.stream().anyMatch(s -> s.startsWith("moss: not map-grid aligned")
            && s.contains("got 10,-100") && s.contains("nearest aligned corner 64,-64")), w.toString());
        // centre is now outside the shifted map area
        assertTrue(w.stream().anyMatch(s -> s.contains("is outside its map area")), w.toString());
        assertEquals(Reply.Tone.WARN, SiteCheck.reply(c).tone());
    }

    @Test
    void missingPiecesAreReported() {
        MapArtConfig c = new MapArtConfig();
        List<String> w = SiteCheck.check(c).warnings();
        assertTrue(w.contains("No platforms configured (site.platforms is empty)."), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("`outputChests`")));
        assertTrue(w.stream().anyMatch(s -> s.contains("`paneChests`")));
        assertTrue(w.stream().anyMatch(s -> s.contains("`cartographyTable`")));
        assertTrue(w.stream().anyMatch(s -> s.contains("No material regions")));

        MapArtConfig.PlatformCfg p = new MapArtConfig.PlatformCfg();
        p.id = "soul";
        p.kind = "SOUL_SAND";
        p.minX = -64;
        p.minZ = -64;
        p.soulSpeedBoots = true;
        p.stayDuringClear = false;
        c.site.platforms.add(p);
        w = SiteCheck.check(c).warnings();
        assertTrue(w.contains("soul: `center` is not set (map filling and clearing stand there)."), w.toString());
        assertTrue(w.contains("soul: no clear actuator configured (`clear` is empty)."), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.startsWith("soul: SOUL_SAND platform with `stayDuringClear` off")));
        assertTrue(w.contains("soul: `soulSpeedBoots` is on but no boot chest is configured."), w.toString());
        assertTrue(w.stream().noneMatch(s -> s.contains("not map-grid aligned")), w.toString());
    }

    @Test
    void probesCollectionAndDropPointsAreChecked() {
        MapArtConfig c = complete();
        MapArtConfig.PlatformCfg moss = c.site.platforms.get(0);
        MapArtConfig.ActuatorCfg west = new MapArtConfig.ActuatorCfg();
        west.pos = new Pos(-4, 66, 128);
        west.probe = new Pos(-72, 65, 65);
        moss.clear.add(west); // probe without probeBlock; the first button has no probe at all
        moss.collectionIndicator = null;
        moss.collectionOff = null;
        c.site.storage.returnChests.clear();
        List<String> w = SiteCheck.check(c).warnings();
        assertTrue(w.contains("moss: clear actuator #2 needs both `probe` and `probeBlock` (or neither)."), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.startsWith("moss: several clear actuators and some have no `probe`")), w.toString());
        assertTrue(w.contains("moss: item collection needs both `collectionOn` and `collectionOff`."), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.startsWith("moss: no `collectionIndicator`")), w.toString());
        assertTrue(w.contains("No item return chest (`returnChests`) or drop point (`returnDrops`) for leftovers."), w.toString());

        MapArtConfig.DropPointCfg drop = new MapArtConfig.DropPointCfg();
        drop.standAt = new Pos(-65, 66, 62);
        c.site.storage.returnDrops.add(drop);
        w = SiteCheck.check(c).warnings();
        assertTrue(w.contains("Drop point #1 needs both `standAt` and `target`."), w.toString());
        drop.target = new Pos(-65, 62, 66);
        w = SiteCheck.check(c).warnings();
        assertTrue(w.stream().noneMatch(s -> s.contains("drop point") || s.contains("Drop point")), w.toString());
    }

    private static MapArtConfig.RegionCfg box(String name, Pos a, Pos b) {
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.name = name;
        r.min = a;
        r.max = b;
        return r;
    }

    @Test
    void noGoZonesAndProtectedRegionsAreListed() {
        MapArtConfig c = complete();
        // far above the platform and away from every chest: listed, no warning
        c.site.noGoZones.add(box("muncher", new Pos(-130, 110, 60), new Pos(-65, 127, 170)));
        c.site.protectedRegions.add(box("machinery", new Pos(-65, 90, 64), new Pos(63, 130, 191)));
        SiteCheck.Report r = SiteCheck.check(c);
        assertEquals(List.of(), r.warnings());
        assertTrue(r.lines().contains("No-go zones (the bot never enters or stands in them): 1"), r.lines().toString());
        assertTrue(r.lines().contains("- no-go #1 \"muncher\" (-130,110,60 to -65,127,170)"), r.lines().toString());
        assertTrue(r.lines().contains("Protected regions (never broken, pressed, opened or placed into): 1"), r.lines().toString());
        assertTrue(r.lines().contains("- protected #1 \"machinery\" (-65,90,64 to 63,130,191)"), r.lines().toString());
        assertTrue(r.lines().stream().anyMatch(l -> l.startsWith("Protected block types: ")
            && l.contains("redstone machinery types")), r.lines().toString());
        assertEquals(Reply.Tone.SUCCESS, SiteCheck.reply(c).tone());

        MapArtConfig none = complete();
        List<String> lines = SiteCheck.check(none).lines();
        assertTrue(lines.contains("No-go zones (the bot never enters or stands in them): none"), lines.toString());
        assertTrue(lines.contains("Protected regions (never broken, pressed, opened or placed into): none"), lines.toString());
    }

    @Test
    void zonesOverlappingThePlatformOrSuppliesAreWarned() {
        MapArtConfig c = complete();
        // corners given in reverse order on purpose; y 67 is 2 above the art layer (65): where the bot walks
        c.site.noGoZones.add(box("low roof", new Pos(10, 67, 150), new Pos(-10, 67, 140)));
        c.site.noGoZones.add(box(null, new Pos(0, 66, 128), new Pos(0, 66, 128)));   // the centre's head cell
        c.site.noGoZones.add(box("chests", new Pos(2, 64, 1), new Pos(3, 64, 1)));    // empty-map + pane chests
        MapArtConfig.RegionCfg broken = new MapArtConfig.RegionCfg();
        broken.min = new Pos(0, 0, 0);
        c.site.noGoZones.add(broken);
        List<String> w = SiteCheck.check(c).warnings();
        assertTrue(w.contains("moss: the map area (y 65..68, where the bot builds and walks) overlaps no-go zone"
            + " #1 \"low roof\" (-10,67,140 to 10,67,150)."), w.toString());
        assertTrue(w.contains("moss: `center` 0,65,128 lies in no-go zone #2 (0,66,128 to 0,66,128); the bot cannot stand there."),
            w.toString());
        assertTrue(w.contains("Empty-map chest 2,64,1 lies in no-go zone #3 \"chests\" (2,64,1 to 3,64,1); the bot can only"
            + " reach it from outside the zone."), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.startsWith("Glass pane chest 3,64,1 lies in no-go zone #3")), w.toString());
        assertTrue(w.contains("No-go zone #4 needs both `min` and `max`; it is ignored."), w.toString());
        assertEquals(Reply.Tone.WARN, SiteCheck.reply(c).tone());
    }

    @Test
    void protectedRegionsOverActuatorsChestsAndTheArtLayerAreWarned() {
        MapArtConfig c = complete();
        c.site.protectedRegions.add(box("button", new Pos(0, 66, 128), new Pos(0, 66, 128)));
        c.site.protectedRegions.add(box("floor strip", new Pos(-64, 65, 100), new Pos(63, 65, 100)));
        c.site.protectedRegions.add(box("sorter", new Pos(5, 60, 5), new Pos(6, 70, 6)));
        c.site.protectedRegions.add(box("table", new Pos(9, 64, 1), new Pos(9, 64, 1)));
        List<String> w = SiteCheck.check(c).warnings();
        assertTrue(w.contains("moss: clear actuator #1 0,66,128 lies in protected region #1 \"button\" (0,66,128 to 0,66,128);"
            + " the bot never presses it."), w.toString());
        assertTrue(w.contains("moss: the art layer overlaps protected region #2 \"floor strip\" (-64,65,100 to 63,65,100);"
            + " NUKE and VERIFY stop (BLOCKED) at any block there."), w.toString());
        assertTrue(w.contains("Material region #1 overlaps protected region #3 \"sorter\" (5,60,5 to 6,70,6); chests there are"
            + " never opened."), w.toString());
        assertTrue(w.contains("`cartographyTable` 9,64,1 lies in protected region #4 \"table\" (9,64,1 to 9,64,1); the bot"
            + " never uses it."), w.toString());
    }

    @Test
    void theExampleSiteKeepsItsZonesClearOfThePlatformAndSupplies() throws java.io.IOException {
        // testserver/examples/site-config.moss.json: the moss site, base-relative, with the Phase 0 zones and regions
        String json = java.nio.file.Files.readString(java.nio.file.Path.of("testserver/examples/site-config.moss.json"));
        MapArtConfig c = new com.google.gson.Gson().fromJson("{\"site\":" + json + "}", MapArtConfig.class);
        assertEquals(5, c.site.noGoZones.size());
        assertEquals(5, c.site.protectedRegions.size());
        SiteCheck.Report r = SiteCheck.check(c);
        List<String> zoneWarnings = r.warnings().stream()
            .filter(w -> w.contains("no-go") || w.contains("protected region") || w.contains("No-go")).toList();
        assertEquals(List.of(), zoneWarnings);
        assertTrue(r.lines().contains("No-go zones (the bot never enters or stands in them): 5"), r.lines().toString());
        assertTrue(r.lines().stream().anyMatch(l -> l.startsWith("- no-go #1 \"muncher: ")), r.lines().toString());
    }

    @Test
    void duplicateIdsAndUnknownKinds() {
        MapArtConfig c = complete();
        MapArtConfig.PlatformCfg copy = new MapArtConfig.PlatformCfg();
        copy.id = "moss";
        copy.kind = "COLOR_SUPPRESSION";
        copy.minX = -64;
        copy.minZ = 64;
        copy.artY = 65;
        c.site.platforms.add(copy);
        List<String> w = SiteCheck.check(c).warnings();
        assertTrue(w.contains("Duplicate platform id `moss`."), w.toString());
        assertTrue(w.contains("moss: no `access` boxes: the bot reaches the layers only where no no-go zone lies on the way."),
            w.toString());
        assertTrue(w.stream().anyMatch(s -> s.startsWith("moss: `glassY` must list the two glass floors")), w.toString());
        assertTrue(w.stream().noneMatch(s -> s.contains("no clear actuator")), w.toString());
        assertTrue(w.contains("moss: uses the same map area as another enabled platform."), w.toString());
    }

    // ---------------------------------------------------------------- map counter

    /** A map counter button at {@code pos} (null: only its column is known). */
    private static MapArtConfig.ActuatorCfg counter(Pos pos) {
        MapArtConfig.ActuatorCfg a = new MapArtConfig.ActuatorCfg();
        a.pos = pos;
        return a;
    }

    private static List<String> counterLines(SiteCheck.Report r) {
        return r.lines().stream().filter(l -> l.startsWith("Map counter: ")).toList();
    }

    @Test
    void mapCounterOffOrAtItsPosition() {
        MapArtConfig c = complete();
        assertEquals(List.of("Map counter: off"), counterLines(SiteCheck.check(c)));

        // next to the supply chests, outside the material region
        c.site.mapCounter = counter(new Pos(12, 65, 1));
        SiteCheck.Report r = SiteCheck.check(c);
        assertEquals(List.of("Map counter: BUTTON at 12,65,1, pressed once per stored map"), counterLines(r));
        assertEquals(List.of(), r.warnings());
        c.site.mapCounter.standAt = new Pos(13, 64, 1);
        c.site.mapCounter.mode = null; // null counts as BUTTON
        r = SiteCheck.check(c);
        assertEquals(List.of("Map counter: BUTTON at 12,65,1, pressed once per stored map, standAt 13,64,1"), counterLines(r));
        assertEquals(List.of(), r.warnings());
        assertEquals(Reply.Tone.SUCCESS, SiteCheck.reply(c, null).tone());
    }

    @Test
    void mapCounterColumnWithAndWithoutTheFoundButton() {
        MapArtConfig c = complete(); // first output chest 4,64,1: the column is scanned at y 58..70
        c.site.mapCounter = counter(null);
        c.site.mapCounterColumn = new MapArtConfig.ColumnCfg(12, 1);
        SiteCheck.Report r = SiteCheck.check(c);
        assertEquals(List.of("Map counter: BUTTON in column 12,1, y not found yet: the bot looks for the one button at y 58..70"
            + " when it stores the next map"), counterLines(r));
        assertEquals(List.of(), r.warnings());
        assertEquals(r, SiteCheck.check(c, null));

        r = SiteCheck.check(c, new Pos(12, 65, 1));
        assertEquals(List.of("Map counter: BUTTON in column 12,1, found this run at 12,65,1 (searched y 58..70), pressed once"
            + " per stored map"), counterLines(r));
        assertEquals(List.of(), r.warnings());
        Reply reply = SiteCheck.reply(c, new Pos(12, 65, 1));
        assertEquals(Reply.Tone.SUCCESS, reply.tone());
        assertTrue(reply.body().contains("found this run at 12,65,1"), reply.body());
        // a button outside the column (found before a config reload) is not this column's button
        assertEquals(SiteCheck.check(c).lines(), SiteCheck.check(c, new Pos(13, 65, 1)).lines());

        c.site.mapCounter.standAt = new Pos(13, 64, 1);
        assertTrue(counterLines(SiteCheck.check(c)).getFirst().endsWith("when it stores the next map, standAt 13,64,1"),
            counterLines(SiteCheck.check(c)).toString());

        c.site.mapCounter.standAt = null;
        c.site.storage.outputChests.clear(); // no reference y: the engine scans around the bot's feet
        assertEquals(List.of("Map counter: BUTTON in column 12,1, y not found yet: the bot looks for the one button from 6 below"
            + " to 6 above its feet when it stores the next map"), counterLines(SiteCheck.check(c)));
        assertEquals(List.of("Map counter: BUTTON in column 12,1, found this run at 12,65,1 (searched around the bot's feet),"
            + " pressed once per stored map"), counterLines(SiteCheck.check(c, new Pos(12, 65, 1))));
    }

    @Test
    void mapCounterConfigProblemsAreWarned() {
        MapArtConfig c = complete();
        c.site.mapCounter = counter(new Pos(12, 65, 1));
        c.site.mapCounter.mode = "LEVER";
        SiteCheck.Report r = SiteCheck.check(c);
        assertEquals(List.of("Map counter: never pressed (see the warnings)"), counterLines(r));
        assertEquals(List.of("The map counter is never pressed (site.mapCounter has mode LEVER: only BUTTON is supported)."),
            r.warnings());
        assertEquals(Reply.Tone.WARN, SiteCheck.reply(c).tone());

        c.site.mapCounter = counter(null);
        r = SiteCheck.check(c);
        assertEquals(List.of("Map counter: never pressed (see the warnings)"), counterLines(r));
        assertEquals(List.of("The map counter is never pressed (site.mapCounter has no pos and site.mapCounterColumn is not"
            + " set)."), r.warnings());

        c.site.mapCounter = null;
        c.site.mapCounterColumn = new MapArtConfig.ColumnCfg(12, 1);
        r = SiteCheck.check(c);
        assertEquals(List.of("Map counter: off"), counterLines(r));
        assertEquals(List.of("`mapCounterColumn` is set but `mapCounter` is not: the map counter is off."), r.warnings());

        c.site.mapCounter = counter(new Pos(12, 65, 1));
        r = SiteCheck.check(c, new Pos(12, 66, 1)); // the column is ignored, and so is a button found in it
        assertEquals(List.of("Map counter: BUTTON at 12,65,1, pressed once per stored map"), counterLines(r));
        assertEquals(List.of("`mapCounter.pos` and `mapCounterColumn` are both set: `pos` wins and the column is ignored."),
            r.warnings());
    }

    @Test
    void mapCounterInAProtectedRegionOrNoGoZoneIsWarned() {
        MapArtConfig c = complete();
        c.site.mapCounter = counter(new Pos(12, 65, 1));
        c.site.mapCounter.standAt = new Pos(13, 64, 1);
        c.site.protectedRegions.add(box("counter", new Pos(12, 65, 1), new Pos(12, 65, 1)));
        c.site.noGoZones.add(box("wall", new Pos(12, 64, 0), new Pos(13, 65, 2)));
        List<String> w = SiteCheck.check(c).warnings();
        assertTrue(w.contains("`mapCounter` 12,65,1 lies in protected region #1 \"counter\" (12,65,1 to 12,65,1); the bot never"
            + " presses it."), w.toString());
        assertTrue(w.contains("`mapCounter` 12,65,1 lies in no-go zone #1 \"wall\" (12,64,0 to 13,65,2); the bot can only reach"
            + " it from outside the zone."), w.toString());
        assertTrue(w.contains("`mapCounter` `standAt` 13,64,1 lies in no-go zone #1 \"wall\" (12,64,0 to 13,65,2); the bot cannot"
            + " stand there."), w.toString());
        assertEquals(3, w.size(), w.toString());

        // column mode: the found button and standAt are checked the same way
        c.site.mapCounter.pos = null;
        c.site.mapCounterColumn = new MapArtConfig.ColumnCfg(12, 1);
        w = SiteCheck.check(c, new Pos(12, 65, 1)).warnings();
        assertTrue(w.contains("`mapCounterColumn` button 12,65,1 lies in protected region #1 \"counter\" (12,65,1 to 12,65,1);"
            + " the bot never presses it."), w.toString());
        assertTrue(w.contains("`mapCounterColumn` button 12,65,1 lies in no-go zone #1 \"wall\" (12,64,0 to 13,65,2); the bot"
            + " can only reach it from outside the zone."), w.toString());
        assertTrue(w.contains("`mapCounter` `standAt` 13,64,1 lies in no-go zone #1 \"wall\" (12,64,0 to 13,65,2); the bot"
            + " cannot stand there."), w.toString());
        assertEquals(3, w.size(), w.toString());
    }

    @Test
    void aProtectedRegionOverTheScannedColumnIsWarnedUntilTheButtonIsFound() {
        MapArtConfig c = complete(); // scanned y 58..70
        c.site.mapCounter = counter(null);
        c.site.mapCounterColumn = new MapArtConfig.ColumnCfg(12, 1);
        c.site.protectedRegions.add(box("above", new Pos(12, 71, 1), new Pos(12, 90, 1)));      // above the scan
        c.site.protectedRegions.add(box("next door", new Pos(11, 58, 0), new Pos(11, 70, 2))); // the next column
        c.site.noGoZones.add(box("walkway", new Pos(12, 58, 1), new Pos(12, 63, 1)));           // reachable from outside
        assertEquals(List.of(), SiteCheck.check(c).warnings());

        c.site.protectedRegions.add(box("piston", new Pos(12, 75, 1), new Pos(12, 69, 1)));
        List<String> w = SiteCheck.check(c).warnings();
        assertEquals(List.of("`mapCounterColumn` 12,1 crosses protected region #3 \"piston\" (12,69,1 to 12,75,1) at the"
            + " scanned y 58..70; a button found there is never pressed."), w);
        assertEquals(Reply.Tone.WARN, SiteCheck.reply(c).tone());
        // found outside the region: nothing to warn about any more
        assertEquals(List.of(), SiteCheck.check(c, new Pos(12, 65, 1)).warnings());
        // found inside it: the button itself is warned
        assertEquals(List.of("`mapCounterColumn` button 12,70,1 lies in protected region #3 \"piston\" (12,69,1 to 12,75,1); the"
            + " bot never presses it."), SiteCheck.check(c, new Pos(12, 70, 1)).warnings());
        // without an output chest the scanned range is not known here
        c.site.storage.outputChests.clear();
        assertTrue(SiteCheck.check(c).warnings().stream().noneMatch(s -> s.contains("mapCounter")),
            SiteCheck.check(c).warnings().toString());
    }

    @Test
    void theExampleSiteKeepsTheMapCounterColumnClear() throws java.io.IOException {
        // the counter of testserver/examples/site-config.moss.json, or the planned one (column 50,-70 by the supply chests)
        String json = java.nio.file.Files.readString(java.nio.file.Path.of("testserver/examples/site-config.moss.json"));
        MapArtConfig c = new com.google.gson.Gson().fromJson("{\"site\":" + json + "}", MapArtConfig.class);
        boolean planned = c.site.mapCounter == null;
        if (planned) {
            c.site.mapCounter = new MapArtConfig.ActuatorCfg();
            c.site.mapCounterColumn = new MapArtConfig.ColumnCfg(50, -70);
        }
        SiteCheck.Report r = SiteCheck.check(c);
        assertEquals(List.of(), r.warnings().stream()
            .filter(w -> w.contains("no-go") || w.contains("protected region") || w.contains("map counter")
                || w.contains("mapCounter")).toList());
        assertEquals(1, counterLines(r).size(), r.lines().toString());
        assertTrue(counterLines(r).getFirst().startsWith("Map counter: BUTTON "), counterLines(r).toString());
        if (planned) assertEquals("Map counter: BUTTON in column 50,-70, y not found yet: the bot looks for the one button at"
            + " y 169..181 when it stores the next map", counterLines(r).getFirst()); // output chest 52,175,-71
    }
}
