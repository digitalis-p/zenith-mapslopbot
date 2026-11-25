package io.github.digitalisp.mapart.command;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.config.Defaults;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.config.MapArtConfig.ActuatorCfg;
import io.github.digitalisp.mapart.config.MapArtConfig.ColumnCfg;
import io.github.digitalisp.mapart.config.MapArtConfig.PlatformCfg;
import io.github.digitalisp.mapart.config.MapArtConfig.RegionCfg;
import io.github.digitalisp.mapart.config.SiteRules;
import io.github.digitalisp.mapart.engine.MapCounter;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Summary of the configured site (platforms, chests) with misconfiguration warnings. Pure. */
public final class SiteCheck {
    private SiteCheck() { }

    public record Report(List<String> lines, List<String> warnings) { }

    /** A reply's body (a Discord embed description holds 4,096 characters). */
    private static final int REPLY_CHARS = 3900;
    /** Room kept for the warnings of a reply, whatever the length of the summary above them. */
    private static final int WARNINGS_ROOM = 2000;

    public static Report check(MapArtConfig config) {
        return check(config, null);
    }

    /**
     * {@code counterButton}: the map counter button the engine found in {@code site.mapCounterColumn} this run
     * ({@link io.github.digitalisp.mapart.engine.EngineControl#mapCounterButton}), or null when not known.
     */
    public static Report check(MapArtConfig config, @Nullable Pos counterButton) {
        List<String> lines = new ArrayList<>();
        List<String> warn = new ArrayList<>();
        MapArtConfig.SiteCfg site = config.site;
        lines.add("Dimension: " + site.dimension);

        List<PlatformCfg> platforms = site.platforms == null ? List.of() : site.platforms;
        if (platforms.isEmpty()) warn.add("No platforms configured (site.platforms is empty).");
        else if (platforms.stream().noneMatch(p -> p.enabled)) warn.add("No platform is enabled.");
        Set<String> ids = new HashSet<>();
        Set<String> cells = new HashSet<>();
        for (PlatformCfg p : platforms) {
            String id = p.id == null || p.id.isBlank() ? "(no id)" : p.id;
            if (!ids.add(id)) warn.add("Duplicate platform id `" + id + "`.");
            PlatformGeometry g = p.geometry();
            lines.add("Platform " + id + " [" + p.kind + "] " + (p.enabled ? "enabled" : "disabled")
                + ": map area " + p.minX + "," + p.minZ + " to " + (p.minX + 127) + "," + (p.minZ + 127)
                + ", art Y " + p.artY + ", center " + fmt(p.center)
                + ", clear actuators " + (p.clear == null ? 0 : p.clear.size())
                + ", item collection " + (p.collectionOn != null || p.collectionOff != null
                    ? "on/off" + (p.collectionIndicator != null ? " + indicator" : "") : "no"));
            String kind = p.kind == null ? "" : p.kind.toUpperCase(Locale.ROOT);
            boolean cs = kind.equals("COLOR_SUPPRESSION");
            if (cs) {
                lines.add("  color suppression: glass floors at " + p.glassY + ", " + (p.access == null ? 0 : p.access.size())
                    + " access box(es)" + (p.allowTransparent ? ", transparent pixels allowed (tests only)" : ""));
                if (p.glassY == null || p.glassY.size() != 2 || p.glassY.get(1) - p.glassY.get(0) != 14)
                    warn.add(id + ": `glassY` must list the two glass floors, bottom first, 14 apart (got " + p.glassY
                        + "); color-suppression uploads are refused until it does.");
                lines.add("  color suppression: built in passes, " + (p.topLayerFirst ? "top layer then bottom" : "bottom layer then top") + "; no map, no nuke, no clear; the "
                    + "no-go zones and protected regions open over the art volumes and the access boxes during its jobs");
                if (p.enabled && (p.access == null || p.access.isEmpty()))
                    warn.add(id + ": no `access` boxes: the bot reaches the layers only where no no-go zone lies on the way.");
            } else if (!kind.equals("MOSS") && !kind.equals("SOUL_SAND"))
                warn.add(id + ": unknown kind `" + p.kind + "` (expected MOSS or SOUL_SAND).");
            if (!g.gridAligned()) {
                int ax = Math.floorDiv(p.minX + 64 + 64, 128) * 128 - 64;
                int az = Math.floorDiv(p.minZ + 64 + 64, 128) * 128 - 64;
                warn.add(id + ": not map-grid aligned: minX/minZ must be ≡ -64 (mod 128); got "
                    + p.minX + "," + p.minZ + ", nearest aligned corner " + ax + "," + az + ".");
            }
            if (p.enabled && !cells.add(p.minX + "," + p.minZ + "," + p.artY))
                warn.add(id + ": uses the same map area as another enabled platform.");
            if (p.center == null) warn.add(id + ": `center` is not set (map filling and clearing stand there).");
            else if (!g.contains(p.center.x(), p.center.z())) warn.add(id + ": `center` " + fmt(p.center) + " is outside its map area.");
            if (cs) {
                // cleared by the muncher during the manual ride: no clear actuator
            } else if (p.clear == null || p.clear.isEmpty()) warn.add(id + ": no clear actuator configured (`clear` is empty).");
            else for (int i = 0; i < p.clear.size(); i++) {
                ActuatorCfg a = p.clear.get(i);
                if (a == null || a.pos == null) warn.add(id + ": clear actuator #" + (i + 1) + " has no position.");
                else if (!"BUTTON".equalsIgnoreCase(a.mode) && !"LEVER".equalsIgnoreCase(a.mode))
                    warn.add(id + ": clear actuator #" + (i + 1) + " has unknown mode `" + a.mode + "` (BUTTON or LEVER).");
                if (a != null && (a.probe == null) != (a.probeBlock == null || a.probeBlock.isBlank()))
                    warn.add(id + ": clear actuator #" + (i + 1) + " needs both `probe` and `probeBlock` (or neither).");
            }
            if (p.clear != null && p.clear.size() > 1 && p.clear.stream().anyMatch(a -> a != null && a.probe == null))
                warn.add(id + ": several clear actuators and some have no `probe`; those are pressed on every clear.");
            if (kind.equals("MOSS") && p.collectionOn == null && p.collectionOff == null)
                warn.add(id + ": MOSS platform without `collectionOn`/`collectionOff`; cleared items are not collected.");
            if ((p.collectionOn == null) != (p.collectionOff == null))
                warn.add(id + ": item collection needs both `collectionOn` and `collectionOff`.");
            if ((p.collectionOn != null && p.collectionOn.pos == null) || (p.collectionOff != null && p.collectionOff.pos == null))
                warn.add(id + ": `collectionOn`/`collectionOff` has no position.");
            if ((p.collectionOn != null || p.collectionOff != null) && p.collectionIndicator == null)
                warn.add(id + ": no `collectionIndicator`; the collection state cannot be read, so its actuators are pressed blindly.");
            if (kind.equals("SOUL_SAND") && !p.stayDuringClear)
                warn.add(id + ": SOUL_SAND platform with `stayDuringClear` off; chunks may unload during the clear.");
            if (p.soulSpeedBoots && site.storage.bootChests.isEmpty())
                warn.add(id + ": `soulSpeedBoots` is on but no boot chest is configured.");
            if (p.mapStandAt != null && !g.contains(p.mapStandAt.x(), p.mapStandAt.z()))
                warn.add(id + ": `mapStandAt` " + fmt(p.mapStandAt) + " is outside its map area (the map is made there).");
            if (p.hopDownTo != null) {
                if (p.center == null || p.hopDownTo.y() >= p.center.y())
                    warn.add(id + ": `hopDownTo` " + fmt(p.hopDownTo) + " is not below `center`; the bot never hops down.");
                else if (p.center.y() - p.hopDownTo.y() > 8)
                    warn.add(id + ": `hopDownTo` is " + (p.center.y() - p.hopDownTo.y()) + " blocks below `center`: a fall"
                        + " that deep hurts (about 1 HP per block over 3).");
                if (p.hopDownMinHealth < 10)
                    warn.add(id + ": `hopDownMinHealth` " + p.hopDownMinHealth + " is low; the drop costs about 4 HP.");
            }
            // a colour-suppression platform is never nuked or piston-cleared: its blocks are the material chests'
            if (!cs && (p.clearableBlocks == null || p.clearableBlocks.isEmpty()))
                warn.add(id + ": `clearableBlocks` is empty; nothing can be built.");
            if (p.floorBlock == null || p.floorBlock.isBlank()) warn.add(id + ": `floorBlock` is not set.");
        }

        MapArtConfig.StorageCfg s = site.storage;
        lines.add("Material regions: " + s.materialRegions.size()
            + "; chests: return " + s.returnChests.size() + " (drop points " + s.returnDrops.size() + "), maps " + s.mapChests.size()
            + ", panes " + s.paneChests.size() + ", output " + s.outputChests.size()
            + ", xp " + s.xpChests.size() + ", anvils " + s.anvilChests.size()
            + ", tools " + s.toolChests.size() + ", food " + s.foodChests.size()
            + ", boots " + s.bootChests.size()
            + (s.scrapMapChests.isEmpty() ? "" : ", scrap maps " + s.scrapMapChests.size())
            + (s.rocketChests.isEmpty() ? "" : ", rockets " + s.rocketChests.size()));
        lines.add("Cartography table: " + fmt(site.cartographyTable) + "; anvil: " + fmt(site.anvil));
        Pos found = foundButton(site, counterButton);
        lines.add(counterLine(site, found));
        if (s.materialRegions.isEmpty()) warn.add("No material regions; the stock index stays empty and every job waits for materials.");
        for (int i = 0; i < s.materialRegions.size(); i++) {
            RegionCfg r = s.materialRegions.get(i);
            if (r == null || r.min == null || r.max == null) warn.add("Material region #" + (i + 1) + " needs both `min` and `max`.");
        }
        if (s.mapChests.isEmpty()) warn.add("No empty-map chest (`mapChests`).");
        if (s.paneChests.isEmpty()) warn.add("No glass pane chest (`paneChests`); maps cannot be locked.");
        if (s.outputChests.isEmpty()) warn.add("No output chest (`outputChests`) for finished maps.");
        if (s.returnChests.isEmpty() && s.returnDrops.isEmpty())
            warn.add("No item return chest (`returnChests`) or drop point (`returnDrops`) for leftovers.");
        for (int i = 0; i < s.returnDrops.size(); i++) {
            MapArtConfig.DropPointCfg d = s.returnDrops.get(i);
            if (d == null || d.standAt == null || d.target == null) warn.add("Drop point #" + (i + 1) + " needs both `standAt` and `target`.");
        }
        if (site.cartographyTable == null) warn.add("`cartographyTable` is not set.");
        if (config.map.rename) {
            if (site.anvil == null) warn.add("Renaming is on but `anvil` is not set.");
            if (s.xpChests.isEmpty()) warn.add("Renaming is on but no XP bottle chest (`xpChests`) is configured.");
            if (s.anvilChests.isEmpty()) warn.add("No anvil chest (`anvilChests`); a broken anvil cannot be replaced.");
        }
        if (s.toolChests.isEmpty()) warn.add("No tool chest (`toolChests`).");
        if (s.foodChests.isEmpty()) warn.add("No food chest (`foodChests`).");
        String problem = MapCounter.configProblem(site);
        if (problem != null) warn.add("The map counter is never pressed (" + problem + ").");
        if (site.mapCounter == null && site.mapCounterColumn != null)
            warn.add("`mapCounterColumn` is set but `mapCounter` is not: the map counter is off.");
        if (site.mapCounter != null && site.mapCounter.pos != null && site.mapCounterColumn != null)
            warn.add("`mapCounter.pos` and `mapCounterColumn` are both set: `pos` wins and the column is ignored.");
        zonesAndProtection(site, platforms, found, lines, warn);
        return new Report(lines, warn);
    }

    /** {@code counterButton} when the counter is searched in its column and the button lies in that column, else null. */
    private static @Nullable Pos foundButton(MapArtConfig.SiteCfg site, @Nullable Pos counterButton) {
        ColumnCfg col = site.mapCounterColumn;
        if (counterButton == null || site.mapCounter == null || site.mapCounter.pos != null || col == null) return null;
        return counterButton.x() == col.x && counterButton.z() == col.z ? counterButton : null;
    }

    /** One line on the map counter ({@code site.mapCounter}, {@code site.mapCounterColumn}); {@code found}: see {@link #foundButton}. */
    private static String counterLine(MapArtConfig.SiteCfg site, @Nullable Pos found) {
        ActuatorCfg c = site.mapCounter;
        if (c == null) return "Map counter: off";
        if (MapCounter.configProblem(site) != null) return "Map counter: never pressed (see the warnings)";
        String stand = c.standAt == null ? "" : ", standAt " + c.standAt.key();
        if (c.pos != null) return "Map counter: BUTTON at " + c.pos.key() + ", pressed once per stored map" + stand;
        ColumnCfg col = site.mapCounterColumn;
        int[] range = MapCounter.scanRange(site);
        String head = "Map counter: BUTTON in column " + col.x + "," + col.z;
        if (found != null) return head + ", found this run at " + found.key() + " (searched "
            + (range == null ? "around the bot's feet" : "y " + range[0] + ".." + range[1]) + "), pressed once per stored map" + stand;
        return head + ", y not found yet: the bot looks for the one button "
            + (range == null ? "from " + MapCounter.SCAN_BELOW + " below to " + MapCounter.SCAN_ABOVE + " above its feet"
                : "at y " + range[0] + ".." + range[1])
            + " when it stores the next map" + stand;
    }

    /**
     * Lists {@code site.noGoZones}, {@code site.protectedRegions} and the protected block types, and warns where they
     * collide with what the bot must use: a platform's working volume (the map area from the art layer up to 3 above
     * it: feet on a full block, head, a jump), its centre, actuators and their stand cells, every supply chest, the
     * material regions, the cartography table, the anvil and the map counter (its button, the {@code found} one, or,
     * while that is unknown, the scanned part of its column).
     */
    private static void zonesAndProtection(MapArtConfig.SiteCfg site, List<PlatformCfg> platforms, @Nullable Pos found,
                                           List<String> lines, List<String> warn) {
        List<RegionCfg> zones = site.noGoZones == null ? List.of() : site.noGoZones;
        List<RegionCfg> prot = site.protectedRegions == null ? List.of() : site.protectedRegions;
        lines.add("No-go zones (the bot never enters or stands in them): " + (zones.isEmpty() ? "none" : zones.size()));
        for (RegionCfg r : zones) if (r != null) lines.add("- no-go " + SiteRules.describe(zones, r));
        lines.add("Protected regions (never broken, pressed, opened or placed into): " + (prot.isEmpty() ? "none" : prot.size()));
        for (RegionCfg r : prot) if (r != null) lines.add("- protected " + SiteRules.describe(prot, r));
        lines.add("Protected block types: " + SiteRules.protectedIds(site).size() + " (`protectedBlocks`, "
            + Defaults.MACHINERY_BLOCKS.size() + " redstone machinery types, platform floors)");
        for (int i = 0; i < zones.size(); i++) {
            RegionCfg r = zones.get(i);
            if (r == null || r.min == null || r.max == null) warn.add("No-go zone #" + (i + 1) + " needs both `min` and `max`; it is ignored.");
        }
        for (int i = 0; i < prot.size(); i++) {
            RegionCfg r = prot.get(i);
            if (r == null || r.min == null || r.max == null) warn.add("Protected region #" + (i + 1) + " needs both `min` and `max`; it is ignored.");
        }

        for (PlatformCfg p : platforms) {
            String id = p.id == null || p.id.isBlank() ? "(no id)" : p.id;
            if (p.isColorSuppression()) {
                // its layers lie in the no-go zones on purpose: its jobs open them (SiteRules.suppressionOpenings)
                List<RegionCfg> open = SiteRules.suppressionOpenings(p);
                if (p.center != null && SiteRules.regionAt(open, p.center.x(), p.center.y(), p.center.z()) == null)
                    stand(warn, zones, id + ": `center`", p.center);
                continue;
            }
            Pos lo = new Pos(p.minX, p.artY, p.minZ);
            RegionCfg z = SiteRules.regionOverlapping(zones, lo, new Pos(p.minX + 127, p.artY + 3, p.minZ + 127));
            if (z != null) warn.add(id + ": the map area (y " + p.artY + ".." + (p.artY + 3) + ", where the bot builds and walks) "
                + "overlaps no-go zone " + SiteRules.describe(zones, z) + ".");
            RegionCfg pr = SiteRules.regionOverlapping(prot, lo, new Pos(p.minX + 127, p.artY, p.minZ + 127));
            if (pr != null) warn.add(id + ": the art layer overlaps protected region " + SiteRules.describe(prot, pr)
                + "; NUKE and VERIFY stop (BLOCKED) at any block there.");
            stand(warn, zones, id + ": `center`", p.center);
            stand(warn, zones, id + ": `mapStandAt`", p.mapStandAt);
            stand(warn, zones, id + ": `hopDownTo`", p.hopDownTo);
            if (p.clear != null)
                for (int i = 0; i < p.clear.size(); i++) actuator(warn, zones, prot, id + ": clear actuator #" + (i + 1), p.clear.get(i));
            actuator(warn, zones, prot, id + ": `collectionOn`", p.collectionOn);
            actuator(warn, zones, prot, id + ": `collectionOff`", p.collectionOff);
        }

        MapArtConfig.StorageCfg s = site.storage;
        String[] roles = {"Return chest", "Empty-map chest", "Glass pane chest", "Output chest", "XP chest", "Anvil chest",
            "Tool chest", "Food chest", "Boot chest", "Scrap map chest", "Rocket chest"};
        List<List<Pos>> chests = List.of(s.returnChests, s.mapChests, s.paneChests, s.outputChests, s.xpChests,
            s.anvilChests, s.toolChests, s.foodChests, s.bootChests, s.scrapMapChests, s.rocketChests);
        for (int i = 0; i < roles.length; i++)
            for (Pos c : chests.get(i)) block(warn, zones, prot, roles[i], c, "opens");
        block(warn, zones, prot, "`cartographyTable`", site.cartographyTable, "uses");
        block(warn, zones, prot, "`anvil`", site.anvil, "uses");
        mapCounter(warn, zones, prot, site, found);
        for (int i = 0; i < s.returnDrops.size(); i++) {
            MapArtConfig.DropPointCfg d = s.returnDrops.get(i);
            if (d != null) stand(warn, zones, "Drop point #" + (i + 1) + " `standAt`", d.standAt);
        }
        for (int i = 0; i < s.materialRegions.size(); i++) {
            RegionCfg m = s.materialRegions.get(i);
            if (m == null || m.min == null || m.max == null) continue;
            RegionCfg z = SiteRules.regionOverlapping(zones, m.min, m.max);
            if (z != null) warn.add("Material region #" + (i + 1) + " overlaps no-go zone " + SiteRules.describe(zones, z)
                + "; chests there may be out of reach.");
            RegionCfg pr = SiteRules.regionOverlapping(prot, m.min, m.max);
            if (pr != null) warn.add("Material region #" + (i + 1) + " overlaps protected region " + SiteRules.describe(prot, pr)
                + "; chests there are never opened.");
        }
    }

    /**
     * The map counter's button ({@code pos}, else the one {@code found} in its column this run) and its {@code standAt};
     * while the column's button is not found yet, a protected region over the scanned part of the column (a button found
     * there would never be pressed).
     */
    private static void mapCounter(List<String> warn, List<RegionCfg> zones, List<RegionCfg> prot, MapArtConfig.SiteCfg site,
                                   @Nullable Pos found) {
        ActuatorCfg c = site.mapCounter;
        if (c == null || MapCounter.configProblem(site) != null) return;
        if (c.pos != null) {
            actuator(warn, zones, prot, "`mapCounter`", c);
            return;
        }
        block(warn, zones, prot, "`mapCounterColumn` button", found, "presses");
        stand(warn, zones, "`mapCounter` `standAt`", c.standAt);
        ColumnCfg col = site.mapCounterColumn;
        int[] range = MapCounter.scanRange(site);
        if (found != null || range == null) return;
        RegionCfg pr = SiteRules.regionOverlapping(prot, new Pos(col.x, range[0], col.z), new Pos(col.x, range[1], col.z));
        if (pr != null) warn.add("`mapCounterColumn` " + col.x + "," + col.z + " crosses protected region "
            + SiteRules.describe(prot, pr) + " at the scanned y " + range[0] + ".." + range[1]
            + "; a button found there is never pressed.");
    }

    /** A cell the bot stands in (feet, head above): warn if either lies in a no-go zone. */
    private static void stand(List<String> warn, List<RegionCfg> zones, String what, Pos feet) {
        if (feet == null) return;
        RegionCfg z = SiteRules.regionAt(zones, feet.x(), feet.y(), feet.z());
        if (z == null) z = SiteRules.regionAt(zones, feet.x(), feet.y() + 1, feet.z());
        if (z != null) warn.add(what + " " + feet.key() + " lies in no-go zone " + SiteRules.describe(zones, z)
            + "; the bot cannot stand there.");
    }

    /** A block the bot opens or uses: warn if it lies in a no-go zone or a protected region. */
    private static void block(List<String> warn, List<RegionCfg> zones, List<RegionCfg> prot, String what, Pos p, String verb) {
        if (p == null) return;
        RegionCfg z = SiteRules.regionAt(zones, p.x(), p.y(), p.z());
        if (z != null) warn.add(what + " " + p.key() + " lies in no-go zone " + SiteRules.describe(zones, z)
            + "; the bot can only reach it from outside the zone.");
        RegionCfg pr = SiteRules.regionAt(prot, p.x(), p.y(), p.z());
        if (pr != null) warn.add(what + " " + p.key() + " lies in protected region " + SiteRules.describe(prot, pr)
            + "; the bot never " + verb + " it.");
    }

    private static void actuator(List<String> warn, List<RegionCfg> zones, List<RegionCfg> prot, String what, ActuatorCfg a) {
        if (a == null) return;
        block(warn, zones, prot, what, a.pos, "presses");
        stand(warn, zones, what + " `standAt`", a.standAt);
    }

    public static Reply reply(MapArtConfig config) {
        return reply(config, null);
    }

    /** {@link #check(MapArtConfig, Pos)} as a reply. */
    public static Reply reply(MapArtConfig config, @Nullable Pos counterButton) {
        return reply(config, counterButton, "");
    }

    /**
     * {@link #check(MapArtConfig, Pos)} as a reply, with {@code rocketChest} (the engine's
     * {@link io.github.digitalisp.mapart.engine.EngineControl#rocketChestReport}) as a line when not blank.
     */
    public static Reply reply(MapArtConfig config, @Nullable Pos counterButton, @Nullable String rocketChest) {
        Report r = check(config, counterButton);
        StringBuilder sb = new StringBuilder();
        for (String l : r.lines()) sb.append(l).append('\n');
        if (rocketChest != null && !rocketChest.isBlank()) sb.append(rocketChest).append('\n');
        if (r.warnings().isEmpty()) {
            sb.append("No problems found.");
            return Reply.ok("Map art site", sb.toString());
        }
        // The warnings are what the reply is for: a long site (15 no-go zones) used up the whole 3,900 characters
        // before them and the list came out empty ("Warnings (1)" and nothing below). They get their room first and
        // the summary above is cut instead.
        String head = "**Warnings (" + r.warnings().size() + ")**\n";
        String bullets = Fmt.bullets(r.warnings(), WARNINGS_ROOM);
        int room = REPLY_CHARS - head.length() - bullets.length();
        String summary = sb.length() <= room ? sb.toString() : Fmt.truncate(sb.toString(), room - 1) + "\n";
        return Reply.warn("Map art site", summary + head + bullets);
    }

    private static String fmt(Pos p) {
        return p == null ? "NOT SET" : p.key();
    }
}
