package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Schematic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refuses structure exports the bot cannot build, with a message that says what the file is and how to export a
 * supported one. Pure: no ZenithProxy globals, no I/O. Checks, first hit wins:
 * <ol>
 *   <li>colour suppression / two-layer maps: a PNG-to-NBT suppression or split file name, columns with two separate
 *       stacks of blocks, or a plain glass floor with art above and below it;</li>
 *   <li>a PNG-to-NBT export (root {@code author} tag);</li>
 *   <li>a DataVersion newer than {@link #MAX_DATA_VERSION}.</li>
 * </ol>
 * MapArtCraft exports (flat, staircased, joined, split) have one run of blocks per column, no empty level inside it
 * and no glass floor, so they pass; staircased ones are refused later as not flat.
 */
public final class ExportCheck {
    /** How to make an export the bot accepts; every message here ends with it. */
    public static final String HOW = "Export from MapArtCraft (mike2b2t.github.io/mapartcraft): Schematic (NBT), "
        + "Minecraft version 1.21.4, Staircasing OFF (flat), 1x1 split files or one joined file.";

    /**
     * Newest DataVersion accepted: 4325, MapArtCraft's 1.21.5 (its newest version and its default), not 1.21.4's 4189.
     * Refusing 4325 would refuse every MapArtCraft export made with the default setting, although its structure
     * format is the same as 1.21.4's: the 1.21.4 registry check in validation rejects any 1.21.5-only block, and
     * validation warns about the newer DataVersion. Anything newer is refused (MapArtCraft has none; PNG-to-NBT
     * writes 4440 for 1.21.5-1.21.8 and 5023 for 26.3).
     */
    public static final int MAX_DATA_VERSION = 4325;

    /** Two art layers: at least this many per cent of the non-empty columns (rows z >= 1) hold two separate stacks. */
    public static final int TWO_LAYER_MIN_PERCENT = 1;
    /** Glass floor: plain glass fills at least this many per cent of the footprint (rows z >= 1) at one level. */
    public static final int GLASS_FLOOR_MIN_PERCENT = 50;

    static final String GLASS = "minecraft:glass";

    /**
     * PNG-to-NBT's file-name suffixes for colour suppression: "-suppress_crubtech", "-suppress_2layer",
     * "-suppress_step_pairs_&lt;dir&gt;", "-suppress_step_checker_&lt;dir&gt;", "-split_row", "-split_checker". The match
     * stops before a "_&lt;x&gt;_&lt;y&gt;" tile suffix.
     */
    private static final Pattern SUPPRESSION_NAME =
        Pattern.compile("(?i)-(suppress|split)_(?:[a-z0-9]+(?:_[a-z][a-z0-9]*)*)?");
    private static final int Y_BITS = 15;
    private static final long Y_MASK = (1L << Y_BITS) - 1;
    private static final int MAX_BANDS = 4;

    private ExportCheck() { }

    /**
     * The message refusing this parsed export, or null if it may be built (dimensions and flatness are checked
     * elsewhere). {@code zip} is the archive the file came from, or null.
     */
    public static String check(String file, String zip, Schematic s) {
        List<String> evidence = new ArrayList<>();
        String name = nameEvidence(file);
        if (name != null) evidence.add(name);
        evidence.addAll(structureEvidence(s));
        if (!evidence.isEmpty()) return suppression(file, zip, evidence);
        if (isPngToNbt(s.author())) return pngToNbt(file, zip, "");
        if (s.dataVersion() > MAX_DATA_VERSION)
            return label(file, zip) + " was exported for a newer Minecraft (DataVersion " + s.dataVersion() + ", "
                + versionName(s.dataVersion()) + "); " + supported() + " " + HOW;
        return null;
    }

    /**
     * The message for a file whose palette uses the newer {@code id} keys (the parser refused it before its blocks
     * could be looked at). {@code dataVersion} is 0 and {@code author} "" when the file has none.
     */
    public static String newerFormat(String file, String zip, int dataVersion, String author) {
        String name = nameEvidence(file);
        if (name != null) return suppression(file, zip, List.of(name));
        if (isPngToNbt(author))
            return pngToNbt(file, zip, ", in the 26.x structure format ("
                + (dataVersion > 0 ? "DataVersion " + dataVersion + "; " : "")
                + "PNG-to-NBT's Options -> Minecraft version selects 1.21.4)");
        String which = dataVersion > MAX_DATA_VERSION
            ? "DataVersion " + dataVersion + ", " + versionName(dataVersion) + "; " : "";
        return label(file, zip) + " was exported for a newer Minecraft (" + which
            + "its palette uses the 'id' keys of the 26.x structure format); " + supported() + " " + HOW;
    }

    /** The suppression message if the file name alone gives it away (PNG-to-NBT's suffixes), else null. */
    public static String byName(String file, String zip) {
        String name = nameEvidence(file);
        return name == null ? null : suppression(file, zip, List.of(name));
    }

    /**
     * What follows PNG-to-NBT's colour-suppression mark in a file stem: "-1_1" of "IMG-suppress_crubtech-1_1", "" when
     * the mark ends the stem; null when the stem has no mark.
     */
    static String afterMark(String stem) {
        if (stem == null) return null;
        Matcher m = SUPPRESSION_NAME.matcher(stem);
        return m.find() ? stem.substring(m.end()) : null;
    }

    /** The stem up to PNG-to-NBT's colour-suppression mark ("IMG" of "IMG-suppress_crubtech-1_1"), else all of it. */
    static String beforeMark(String stem) {
        if (stem == null) return "";
        Matcher m = SUPPRESSION_NAME.matcher(stem);
        return m.find() ? stem.substring(0, m.start()) : stem;
    }

    /** A readable Minecraft version for a DataVersion, as far as the exporters in use go. */
    public static String versionName(int dataVersion) {
        if (dataVersion == 4189) return "1.21.4";
        if (dataVersion < 4189) return "older than 1.21.4";
        if (dataVersion <= 4325) return "1.21.5";
        if (dataVersion < 5000) return "1.21.6 or newer";
        if (dataVersion == 5023) return "26.3";
        return "26.x";
    }

    // ---------------------------------------------------------------- evidence

    private static String nameEvidence(String file) {
        if (file == null) return null;
        Matcher m = SUPPRESSION_NAME.matcher(file);
        if (!m.find()) return null;
        String what = m.group(1).equalsIgnoreCase("split") ? "one half of a split color-suppression build"
            : "a color-suppression build";
        return "the file name has '" + m.group() + "', PNG-to-NBT's mark of " + what;
    }

    /**
     * Two art layers and glass floors, over rows z >= 1. Memory is one long per non-air block plus two arrays of the
     * structure's height, never proportional to the declared footprint (this runs before the dimension check).
     */
    static List<String> structureEvidence(Schematic s) {
        int sx = s.sizeX(), sy = s.sizeY(), sz = s.sizeZ();
        if (sx <= 0 || sy <= 0 || sz <= 1 || sy > Y_MASK) return List.of();
        List<BlockSpec> palette = s.palette();
        boolean[] air = new boolean[palette.size()];
        boolean[] glass = new boolean[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            air[i] = palette.get(i).isAir();
            glass[i] = palette.get(i).id().equals(GLASS);
        }
        long[] keys = new long[s.blocks().size()];
        int[] glassAt = new int[sy];
        int n = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (Schematic.Block b : s.blocks()) {
            if (b.z() < 1 || b.z() >= sz || b.x() < 0 || b.x() >= sx || b.y() < 0 || b.y() >= sy) continue;
            if (b.state() < 0 || b.state() >= air.length || air[b.state()]) continue;
            keys[n++] = (((long) b.z() * sx + b.x()) << Y_BITS) | b.y();
            if (glass[b.state()]) glassAt[b.y()]++;
            minY = Math.min(minY, b.y());
            maxY = Math.max(maxY, b.y());
        }
        if (n == 0) return List.of();
        Arrays.sort(keys, 0, n);

        boolean[] gapLevel = new boolean[sy];
        long nonEmpty = 0, gapped = 0;
        for (int i = 0; i < n; ) {
            long column = keys[i] >>> Y_BITS;
            int j = i + 1;
            boolean gap = false;
            for (; j < n && (keys[j] >>> Y_BITS) == column; j++)
                if ((keys[j] & Y_MASK) > (keys[j - 1] & Y_MASK) + 1) gap = true;
            nonEmpty++;
            if (gap) {
                gapped++;
                for (int k = i; k < j; k++) gapLevel[(int) (keys[k] & Y_MASK)] = true;
            }
            i = j;
        }

        List<String> out = new ArrayList<>();
        if (gapped > 0 && gapped * 100 >= nonEmpty * TWO_LAYER_MIN_PERCENT)
            out.add("two art layers: blocks at heights " + bands(gapLevel) + " with empty levels between ("
                + gapped + " of " + nonEmpty + " columns)");
        long footprint = (long) sx * (sz - 1);
        List<String> floors = new ArrayList<>();
        for (int y = minY + 1; y < maxY; y++)
            if (glassAt[y] > 0 && (long) glassAt[y] * 100 >= footprint * GLASS_FLOOR_MIN_PERCENT) floors.add(String.valueOf(y));
        if (floors.size() == 1)
            out.add("a plain glass floor at height " + floors.getFirst() + " with art above and below it");
        else if (floors.size() > 1)
            out.add("plain glass floors at heights " + joinAnd(floors) + " with art above and below them");
        return out;
    }

    /** "0..1 and 13..15": the runs of used levels, at most {@link #MAX_BANDS} of them listed. */
    private static String bands(boolean[] used) {
        List<String> l = new ArrayList<>();
        int extra = 0;
        for (int y = 0; y < used.length; y++) {
            if (!used[y]) continue;
            int start = y;
            while (y + 1 < used.length && used[y + 1]) y++;
            if (l.size() < MAX_BANDS) l.add(start == y ? String.valueOf(y) : start + ".." + y);
            else extra++;
        }
        String s = joinAnd(l);
        return extra == 0 ? s : s + " and " + extra + " more";
    }

    private static String joinAnd(List<String> l) {
        if (l.size() <= 1) return String.join("", l);
        return String.join(", ", l.subList(0, l.size() - 1)) + " and " + l.getLast();
    }

    // ---------------------------------------------------------------- messages

    private static boolean isPngToNbt(String author) {
        return author != null && author.toLowerCase(Locale.ROOT).contains("png-to-nbt");
    }

    private static String suppression(String file, String zip, List<String> evidence) {
        return label(file, zip) + " is a color-suppression (two-layer) map: " + String.join("; ", evidence)
            + ". Color suppression maps are not supported yet: the bot builds normal flat maps on the moss platform "
            + "only. " + HOW;
    }

    private static String pngToNbt(String file, String zip, String note) {
        return label(file, zip) + " was made with PNG-to-NBT (evmodder.net)" + note + ". The bot reads MapArtCraft "
            + "exports only; PNG-to-NBT's suppression, split and step modes are color-suppression builds, which are "
            + "not supported yet. " + HOW;
    }

    private static String supported() {
        return "the bot builds 1.21.4 maps and reads exports up to DataVersion " + MAX_DATA_VERSION
            + " (MapArtCraft 1.21.5).";
    }

    /** {@code 'file'}, or {@code 'file' (in 'archive.zip')}. */
    static String label(String file, String zip) {
        return "'" + file + "'" + (zip != null ? " (in '" + zip + "')" : "");
    }
}
