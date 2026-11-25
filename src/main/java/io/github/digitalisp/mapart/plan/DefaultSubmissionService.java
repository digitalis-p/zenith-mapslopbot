package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.bot.impl.TopFaceRules;
import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.catalog.Eyeblossoms;
import io.github.digitalisp.mapart.config.Defaults;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.NamedFile;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.Schematic;
import io.github.digitalisp.mapart.model.SuppressionArt;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.ValidationResult;
import io.github.digitalisp.mapart.nbt.NbtException;
import io.github.digitalisp.mapart.nbt.StructureNbtParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Parses MapArtCraft structure exports (loose .nbt, .zip of .nbt, 1x1-split or joined) into flat tiles and
 * validates them against the block registry and the configured platforms. Stateless and thread-safe.
 * Every parsed file first goes through {@link ExportCheck} (colour suppression, PNG-to-NBT, newer Minecraft), then
 * the dimension and flatness checks. Split files are parsed one at a time.
 * Parse warnings stay in {@link ParsedSubmission#warnings()}; {@link #validate} returns only its own.
 */
public final class DefaultSubmissionService implements SubmissionService {
    /** DataVersion of Minecraft 1.21.4. */
    public static final int DATA_VERSION_1_21_4 = 4189;
    public static final int MAX_TILES = 256;
    /** Per .nbt file, as stored (after unzipping, before gunzipping). */
    public static final long MAX_FILE_BYTES = 64L << 20;
    /**
     * All .nbt files of one submission together; also caps each file's gunzipped NBT. The block list is streamed into
     * packed ints ({@code StructureNbtParser}), so this bounds reading time, not memory (live 2026-10-15: a 1800x1200
     * single-file export, about 140 maps, exceeded the former 128 MiB).
     */
    public static final long MAX_TOTAL_BYTES = 1L << 30;
    /** Jobs with more maps than this get a "large job" warning. */
    public static final int LARGE_JOB_TILES = 16;

    private static final int MAP = TileArt.SIZE;
    private static final int MAX_LISTED = 12;
    private static final Pattern TILE_NAME = Pattern.compile("(?i)^(.*)_(\\d+)_(\\d+)\\.nbt$");
    private static final Pattern LEADING_INDEX = Pattern.compile("^\\d+_");
    private static final Set<String> PLATFORM_KINDS = Set.of("MOSS", "SOUL_SAND");

    private final int maxTiles;
    private final long maxFileBytes;
    private final long maxTotalBytes;

    public DefaultSubmissionService() {
        this(MAX_TILES, MAX_FILE_BYTES, MAX_TOTAL_BYTES);
    }

    DefaultSubmissionService(int maxTiles, long maxFileBytes, long maxTotalBytes) {
        this.maxTiles = maxTiles;
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    /** One .nbt file after unzipping; {@code zip} is the archive it came from, or null. */
    private record Input(String name, String zip, byte[] data) { }

    // ---------------------------------------------------------------- parse

    @Override
    public ParsedSubmission parse(List<NamedFile> files) throws SubmissionException {
        return parse(files, null);
    }

    @Override
    public ParsedSubmission parse(List<NamedFile> files, MapArtConfig.SiteCfg site) throws SubmissionException {
        if (files == null || files.isEmpty())
            throw new SubmissionException("No files. Attach MapArtCraft .nbt exports or a .zip of them.");
        List<String> warnings = new ArrayList<>();
        Expanded ex = expand(files);
        List<Input> inputs = ex.inputs();
        Set<String> ignored = ex.ignored();
        String zipStem = ex.zipStem();
        if (!ignored.isEmpty())
            warnings.add("Ignored " + plural(ignored.size(), "file") + " in the zip that " + (ignored.size() == 1 ? "is" : "are")
                + " not .nbt: " + listNames(ignored) + ".");
        if (inputs.isEmpty())
            throw new SubmissionException("No .nbt files found" + (zipStem != null ? " in the zip" : "") + ".");

        MapArtConfig.PlatformCfg cs = suppressionPlatform(site);
        if (cs != null && inputs.size() == 1) {
            ParsedSubmission p = suppression(inputs.getFirst(), cs, warnings, zipStem);
            if (p != null) return p;
        }

        List<Input> unnamed = new ArrayList<>();
        for (Input in : inputs) if (!TILE_NAME.matcher(in.name()).matches()) unnamed.add(in);

        Assembled a;
        if (inputs.size() == 1 && unnamed.size() == 1) {
            a = assembleJoined(inputs.getFirst());
        } else if (unnamed.isEmpty()) {
            a = assembleSplit(inputs);
        } else {
            String unsupported = unsupportedExport(unnamed);
            if (unsupported != null) throw new SubmissionException(unsupported);
            List<String> names = new ArrayList<>();
            for (Input in : unnamed) names.add(quote(in.name()));
            throw new SubmissionException("Cannot tell which map tile " + (names.size() == 1 ? "this file is" : "these files are")
                + ": " + listNames(names) + ". Send either one joined export, or 1x1-split files named <name>_<x>_<y>.nbt.");
        }

        for (var e : a.tiles.entrySet())
            if (e.getValue().nonEmptyCount() == 0)
                warnings.add("Tile " + e.getKey() + " is fully transparent; nothing will be built there.");
        String base = baseName(a.stems, zipStem);
        return new ParsedSubmission(base, a.tilesX, a.tilesZ, a.dataVersion, a.tiles, List.copyOf(warnings));
    }

    /** The upload's .nbt files, zips unpacked; {@code zipStem} is the first zip's name without ".zip", or null. */
    private record Expanded(List<Input> inputs, Set<String> ignored, String zipStem) { }

    private Expanded expand(List<NamedFile> files) throws SubmissionException {
        List<Input> inputs = new ArrayList<>();
        Set<String> ignored = new TreeSet<>();
        long[] total = {0};
        String zipStem = null;
        for (NamedFile f : files) {
            String name = lastSegment(f.name() == null ? "" : f.name());
            String lower = name.toLowerCase(Locale.ROOT);
            byte[] data = f.data() == null ? new byte[0] : f.data();
            if (lower.endsWith(".zip") || isZip(data)) {
                expandZip(name, data, inputs, ignored, total);
                if (zipStem == null) zipStem = stripExtension(name);
            } else if (lower.endsWith(".nbt")) {
                if (data.length > maxFileBytes) throw tooBig(name);
                addTotal(total, data.length);
                inputs.add(new Input(name, null, data));
                if (inputs.size() > maxTiles) throw tooManyFiles();
            } else {
                throw new SubmissionException("Unsupported file " + quote(name)
                    + ". Send MapArtCraft .nbt exports or a .zip of them.");
            }
        }
        return new Expanded(inputs, ignored, zipStem);
    }

    /** "-1_1" or "_1_0": what PNG-to-NBT puts after its mark for one map of a picture cut into several. */
    private static final Pattern PART_COORDS = Pattern.compile("^[-_](\\d{1,4})_(\\d{1,4})$");

    @Override
    public Split split(List<NamedFile> files, MapArtConfig.SiteCfg site) throws SubmissionException {
        MapArtConfig.PlatformCfg cs = suppressionPlatform(site);
        if (cs == null || files == null || files.isEmpty()) return null;
        List<Input> inputs;
        String zipStem;
        try {
            Expanded ex = expand(files);
            inputs = ex.inputs();
            zipStem = ex.zipStem();
        } catch (SubmissionException e) {
            return null; // parse refuses the upload with the same message
        }
        if (inputs.size() < 2) return null;
        boolean marked = false;
        for (Input in : inputs) marked |= ExportCheck.byName(in.name(), in.zip()) != null;
        // a flat split upload may be 100 files and more: without a name mark its first file tells, the rest stay unread
        if (!marked && !twoLayer(inputs.getFirst(), cs)) return null;
        List<String> flat = new ArrayList<>();
        for (Input in : inputs) if (!twoLayer(in, cs)) flat.add(quote(in.name()));
        if (flat.size() == inputs.size()) return null; // the mark on files that are no two-layer export: parse's reason
        if (!flat.isEmpty()) {
            int two = inputs.size() - flat.size();
            throw new SubmissionException(two + " of the " + inputs.size() + " files "
                + (two == 1 ? "is a color-suppression (two-layer) map" : "are color-suppression (two-layer) maps")
                + " and " + listNames(flat) + (flat.size() == 1 ? " is" : " are")
                + " not. Send color-suppression maps on their own: each file is one map, built and stored by itself.");
        }

        // "1,1" for "...-suppress_crubtech-1_1.nbt" when every file has such a suffix and no two share it
        record Named(Input in, String stem, int a, int b) { }
        List<Named> named = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean coords = true;
        for (Input in : inputs) {
            String stem = LEADING_INDEX.matcher(stripExtension(in.name())).replaceFirst("");
            String after = ExportCheck.afterMark(stem);
            Matcher m = after == null ? null : PART_COORDS.matcher(after);
            if (m != null && m.matches() && seen.add(m.group(1) + "," + m.group(2)))
                named.add(new Named(in, stem, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
            else {
                coords = false;
                named.add(new Named(in, stem, 0, 0));
            }
        }
        List<Part> parts = new ArrayList<>();
        if (coords) {
            named.sort(java.util.Comparator.comparingInt(Named::a).thenComparingInt(Named::b));
            List<String> stems = new ArrayList<>();
            for (Named n : named) {
                stems.add(ExportCheck.beforeMark(n.stem()));
                parts.add(new Part(n.a() + "," + n.b(), new NamedFile(n.in().name(), n.in().data())));
            }
            return new Split(baseName(stems, zipStem), List.copyOf(parts));
        }
        // no such suffix: each map takes its own file's name, a number added where two are alike
        Map<String, Integer> used = new HashMap<>();
        for (Named n : named) {
            String own = sanitize(ExportCheck.beforeMark(n.stem()));
            if (own.isEmpty()) own = "map";
            int k = used.merge(own.toLowerCase(Locale.ROOT), 1, Integer::sum);
            parts.add(new Part(k == 1 ? own : own + " " + k, new NamedFile(n.in().name(), n.in().data())));
        }
        return new Split("", List.copyOf(parts));
    }

    /** A CrubTech two-layer export, buildable or not ({@link SuppressionExport#read}); false for anything else. */
    private boolean twoLayer(Input in, MapArtConfig.PlatformCfg cs) {
        try {
            return SuppressionExport.read(StructureNbtParser.parse(in.data(), maxTotalBytes, true), cs.allowTransparent)
                != null;
        } catch (NbtException e) {
            return false;
        }
    }

    private record Assembled(int tilesX, int tilesZ, int dataVersion, SortedMap<TileCoord, TileArt> tiles,
                             List<String> stems) { }

    private Assembled assembleJoined(Input in) throws SubmissionException {
        Schematic s = parseNbt(in);
        if (s.sizeX() % MAP != 0 || s.sizeZ() < MAP + 1 || (s.sizeZ() - 1) % MAP != 0)
            throw new SubmissionException(quote(in.name()) + " is " + s.sizeX() + " wide and " + s.sizeZ()
                + " deep. A MapArtCraft map export is 128·N wide and 128·M+1 deep (the extra row is the noobline).");
        int n = s.sizeX() / MAP;
        int m = (s.sizeZ() - 1) / MAP;
        if ((long) n * m > maxTiles) throw tooManyTiles(n, m);
        FlatArt flat = new FlatArt(s);
        String stair = staircase(in, flat);
        if (stair != null) throw new SubmissionException(stair);
        SortedMap<TileCoord, TileArt> tiles = new TreeMap<>();
        for (int j = 0; j < m; j++)
            for (int i = 0; i < n; i++)
                tiles.put(new TileCoord(i, j), flat.tile(MAP * i, MAP * j));
        String stem = LEADING_INDEX.matcher(stripExtension(in.name())).replaceFirst("");
        return new Assembled(n, m, s.dataVersion(), tiles, List.of(stem));
    }

    private Assembled assembleSplit(List<Input> inputs) throws SubmissionException {
        SortedMap<TileCoord, Input> byCoord = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        List<String> stems = new ArrayList<>();
        for (Input in : inputs) {
            Matcher mt = TILE_NAME.matcher(in.name());
            if (!mt.matches()) throw new IllegalStateException(in.name());
            int x = coordinate(mt.group(2));
            int z = coordinate(mt.group(3));
            if (x >= maxTiles || z >= maxTiles) {
                problems.add(quote(in.name()) + ": tile coordinate " + mt.group(2) + "," + mt.group(3) + " is too large");
                continue;
            }
            TileCoord c = new TileCoord(x, z);
            Input prev = byCoord.putIfAbsent(c, in);
            if (prev != null) problems.add("Duplicate tile " + c + ": " + quote(prev.name()) + " and " + quote(in.name()));
            stems.add(LEADING_INDEX.matcher(mt.group(1)).replaceFirst(""));
        }
        if (!problems.isEmpty()) throw new SubmissionException(String.join("\n", problems));
        int tilesX = 0, tilesZ = 0;
        for (TileCoord c : byCoord.keySet()) {
            tilesX = Math.max(tilesX, c.x() + 1);
            tilesZ = Math.max(tilesZ, c.z() + 1);
        }
        if ((long) tilesX * tilesZ > maxTiles) throw tooManyTiles(tilesX, tilesZ);
        List<String> missing = new ArrayList<>();
        for (int z = 0; z < tilesZ; z++)
            for (int x = 0; x < tilesX; x++)
                if (!byCoord.containsKey(new TileCoord(x, z))) missing.add(x + "," + z);
        if (!missing.isEmpty())
            throw new SubmissionException("Missing " + plural(missing.size(), "tile") + " of a " + tilesX + "x" + tilesZ
                + " grid: " + listNames(missing) + ". Split tiles are named <name>_<x>_<y>.nbt (x = column from the west, "
                + "y = row from the north, both from 0); send all of them.");

        SortedMap<TileCoord, TileArt> tiles = new TreeMap<>();
        Map<String, String> wrongSize = new LinkedHashMap<>();
        Set<String> sizes = new HashSet<>();
        int dataVersion = 0;
        for (var e : byCoord.entrySet()) {
            Input in = e.getValue();
            Schematic s = parseNbt(in);
            dataVersion = Math.max(dataVersion, s.dataVersion());
            String dims = s.sizeX() + "x" + s.sizeZ();
            sizes.add(dims);
            if (s.sizeX() != MAP || s.sizeZ() != MAP + 1) {
                wrongSize.put(in.name(), dims);
                continue;
            }
            FlatArt flat = new FlatArt(s);
            String stair = staircase(in, flat);
            if (stair != null) problems.add(stair);
            else tiles.put(e.getKey(), flat.tile(0, 0));
        }
        if (!wrongSize.isEmpty()) {
            List<String> l = new ArrayList<>();
            wrongSize.forEach((k, v) -> l.add(quote(k) + " is " + v));
            String head = sizes.size() > 1 ? "Mixed tile sizes: " : "Wrong tile dimensions: ";
            problems.addFirst(head + listNames(l) + " (width x depth). Every split tile must be one map: "
                + "128 wide and 129 deep (incl. the noobline row).");
        }
        if (!problems.isEmpty()) throw new SubmissionException(String.join("\n", problems));
        return new Assembled(tilesX, tilesZ, dataVersion, tiles, stems);
    }

    /** The first enabled COLOR_SUPPRESSION platform of {@code site}, or null (colour-suppression uploads refused). */
    static MapArtConfig.PlatformCfg suppressionPlatform(MapArtConfig.SiteCfg site) {
        if (site == null || site.platforms == null) return null;
        for (MapArtConfig.PlatformCfg p : site.platforms)
            if (p != null && p.enabled && p.isColorSuppression()) return p;
        return null;
    }

    /**
     * A colour-suppression map ({@link SuppressionExport}), its passes as tiles (i, 0) in build order; null when the
     * file is not a CrubTech two-layer export (the normal checks then refuse or accept it). The 26.x structure format
     * is read too (docs/COLOR_SUPPRESSION.md): its blocks are checked against 1.21.4 in {@link #validate}.
     */
    private ParsedSubmission suppression(Input in, MapArtConfig.PlatformCfg cs, List<String> warnings, String zipStem)
        throws SubmissionException {
        Schematic s;
        try {
            s = StructureNbtParser.parse(in.data(), maxTotalBytes, true);
        } catch (NbtException e) {
            return null;
        }
        SuppressionExport.Result r = SuppressionExport.read(s, cs.allowTransparent);
        if (r == null) return null;
        if (r.problem() != null)
            throw new SubmissionException(label(in) + " is a color-suppression (two-layer) map that the platform cannot "
                + "build: " + r.problem() + ". " + SuppressionExport.HOW);
        // only blocks the colour-suppression chests hold (docs/COLOR_SUPPRESSION.md): the index row's palette as
        // last read from the world, else platforms[cs].allowedBlocks, else no restriction
        java.util.Set<String> allowed = io.github.digitalisp.mapart.config.CsMaterials.allowed(cs,
            io.github.digitalisp.mapart.config.CsMaterials.live());
        java.util.Set<String> bad = io.github.digitalisp.mapart.config.CsMaterials.refused(r.art().blockCounts().keySet(), allowed);
        if (!bad.isEmpty())
            throw new SubmissionException(label(in) + " uses blocks the color-suppression material chests do not hold: "
                + String.join(", ", bad) + ". Allowed: " + String.join(", ", allowed) + ".");
        List<String> w = new ArrayList<>(warnings);
        w.addAll(r.warnings());
        SortedMap<TileCoord, TileArt> tiles = new TreeMap<>();
        // the order of the passes as the platform says now (platform.topLayerFirst); a job not started by then takes the
        // platform's order again when it is first selected (engine PassOrder)
        List<SuppressionArt.Pass> passes = r.art().topFirst(cs.topLayerFirst).passes();
        for (int i = 0; i < passes.size(); i++) tiles.put(new TileCoord(i, 0), passes.get(i).art());
        if (tiles.isEmpty()) throw new SubmissionException(label(in) + " is an empty color-suppression map.");
        String stem = SuppressionExport.stem(LEADING_INDEX.matcher(stripExtension(in.name())).replaceFirst(""));
        String base = baseName(List.of(stem), zipStem);
        return new ParsedSubmission(base, tiles.size(), 1, s.dataVersion(), tiles, List.copyOf(w), r.art());
    }

    /** Single-layer art (ExportCheck passed) whose top blocks are not all at one height. */
    private static String staircase(Input in, FlatArt flat) {
        int[] r = flat.heightRange();
        if (r == null || r[0] == r[1]) return null;
        return label(in) + ": not flat: the top blocks sit at heights " + r[0] + ".." + r[1]
            + " (staircased or 3D art). The bot builds flat maps only: export from MapArtCraft with Staircasing OFF"
            + " (flat).";
    }

    /** Parses one file and refuses exports the bot cannot build ({@link ExportCheck}). */
    private Schematic parseNbt(Input in) throws SubmissionException {
        Schematic s;
        try {
            s = StructureNbtParser.parse(in.data(), maxTotalBytes);
        } catch (StructureNbtParser.NewerFormatException e) {
            throw new SubmissionException(ExportCheck.newerFormat(in.name(), in.zip(), e.dataVersion(), e.author()), e);
        } catch (NbtException e) {
            throw new SubmissionException(label(in) + " is not a readable structure .nbt: " + e.getMessage(), e);
        }
        String unsupported = ExportCheck.check(in.name(), in.zip(), s);
        if (unsupported != null) throw new SubmissionException(unsupported);
        return s;
    }

    /**
     * Files without a tile name: the first specific reason one of them cannot be built (colour suppression, PNG-to-NBT,
     * newer Minecraft), else null. Names first, then each file is parsed in turn, one in memory at a time; an
     * unreadable file is left to the "cannot tell which tile" message.
     */
    private String unsupportedExport(List<Input> files) {
        for (Input in : files) {
            String m = ExportCheck.byName(in.name(), in.zip());
            if (m != null) return m;
        }
        for (Input in : files) {
            try {
                String m = ExportCheck.check(in.name(), in.zip(), StructureNbtParser.parse(in.data(), maxTotalBytes));
                if (m != null) return m;
            } catch (StructureNbtParser.NewerFormatException e) {
                return ExportCheck.newerFormat(in.name(), in.zip(), e.dataVersion(), e.author());
            } catch (NbtException e) {
                // not readable: no specific reason
            }
        }
        return null;
    }

    private static String label(Input in) {
        return ExportCheck.label(in.name(), in.zip());
    }

    private void expandZip(String zipName, byte[] data, List<Input> out, Set<String> ignored, long[] total)
        throws SubmissionException {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String path = e.getName().replace('\\', '/');
                if (unsafePath(path))
                    throw new SubmissionException("Refusing " + quote(zipName) + ": unsafe path " + quote(path) + " inside.");
                if (e.isDirectory() || path.endsWith("/") || hiddenPath(path)) continue;
                String name = lastSegment(path);
                if (!name.toLowerCase(Locale.ROOT).endsWith(".nbt")) {
                    ignored.add(name);
                    continue;
                }
                byte[] b = readLimited(zin, name);
                addTotal(total, b.length);
                out.add(new Input(name, zipName, b));
                if (out.size() > maxTiles) throw tooManyFiles();
            }
        } catch (ZipException ex) {
            throw new SubmissionException(quote(zipName) + " is not a valid zip archive (" + ex.getMessage() + ").", ex);
        } catch (IOException ex) {
            throw new SubmissionException("Cannot read " + quote(zipName) + ": " + ex.getMessage(), ex);
        }
    }

    private byte[] readLimited(InputStream in, String name) throws IOException, SubmissionException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > maxFileBytes) throw tooBig(name);
        }
        return bos.toByteArray();
    }

    private void addTotal(long[] total, long n) throws SubmissionException {
        total[0] += n;
        if (total[0] > maxTotalBytes)
            throw new SubmissionException("Submission too large: more than " + megabytes(maxTotalBytes) + " of .nbt data.");
    }

    private SubmissionException tooBig(String name) {
        return new SubmissionException(quote(name) + " is larger than " + megabytes(maxFileBytes) + ".");
    }

    private SubmissionException tooManyFiles() {
        return new SubmissionException("Too many .nbt files: at most " + maxTiles + " maps per job.");
    }

    private SubmissionException tooManyTiles(int x, int z) {
        return new SubmissionException("Art is " + x + "x" + z + " = " + ((long) x * z) + " maps; at most " + maxTiles
            + " maps per job.");
    }

    private static int coordinate(String digits) {
        String d = digits.replaceFirst("^0+(?=\\d)", "");
        return d.length() > 6 ? Integer.MAX_VALUE : Integer.parseInt(d);
    }

    private static boolean unsafePath(String path) {
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) return true;
        for (String seg : path.split("/")) if (seg.equals("..")) return true;
        return false;
    }

    private static boolean hiddenPath(String path) {
        for (String seg : path.split("/"))
            if (seg.equals("__MACOSX") || (seg.startsWith(".") && !seg.equals("."))) return true;
        return false;
    }

    private static boolean isZip(byte[] d) {
        return d.length >= 4 && d[0] == 'P' && d[1] == 'K' && (d[2] == 3 || d[2] == 5) && (d[3] == 4 || d[3] == 6);
    }

    private static String lastSegment(String path) {
        String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * The zip file's own name (without a leading "0002_" index), else the common prefix of the file stems, sanitised
     * for map names. MapArtCraft names the files inside a split zip "mapart_..." whatever the art is, so a zip job got
     * the name "mapart" from them (10x test, 2026-10-11). A name in the upload message still wins (the caller).
     */
    static String baseName(List<String> stems, String zipStem) {
        if (zipStem != null) {
            String z = sanitize(LEADING_INDEX.matcher(zipStem).replaceFirst(""));
            if (!z.isEmpty()) return z;
        }
        String common = stems.isEmpty() ? "" : stems.getFirst();
        for (String s : stems) {
            int i = 0;
            while (i < common.length() && i < s.length() && common.charAt(i) == s.charAt(i)) i++;
            common = common.substring(0, i);
        }
        String b = sanitize(common);
        if (b.isEmpty() && zipStem != null) b = sanitize(LEADING_INDEX.matcher(zipStem).replaceFirst(""));
        return b.isEmpty() ? "mapart" : b;
    }

    static String sanitize(String s) {
        String r = s.replaceAll("[^A-Za-z0-9 ._-]", "_").replaceAll("_{2,}", "_").replaceAll(" {2,}", " ");
        r = r.replaceAll("^[ ._-]+|[ ._-]+$", "");
        if (r.length() > 32) r = r.substring(0, 32).replaceAll("[ ._-]+$", "");
        return r;
    }

    // ---------------------------------------------------------------- validate

    @Override
    public ValidationResult validate(ParsedSubmission sub, BlockCatalog catalog, MapArtConfig.SiteCfg site) {
        if (sub.isSuppression()) return validateSuppression(sub, catalog, site);
        Map<BlockSpec, Integer> states = new HashMap<>();
        List<String> mixedBlossoms = new ArrayList<>();
        for (var te : sub.tiles().entrySet()) {
            TileArt t = te.getValue();
            boolean open = false, closed = false;
            for (int pz = 0; pz < MAP; pz++)
                for (int px = 0; px < MAP; px++) {
                    BlockSpec s = t.get(px, pz);
                    if (s == null) continue;
                    states.merge(s, 1, Integer::sum);
                    if (s.id().equals(Eyeblossoms.OPEN)) open = true;
                    else if (s.id().equals(Eyeblossoms.CLOSED)) closed = true;
                }
            if (open && closed) mixedBlossoms.add(te.getKey().toString());
        }

        List<String> errors = new ArrayList<>();
        if (!mixedBlossoms.isEmpty())
            errors.add((mixedBlossoms.size() == 1 ? "Map " : "Maps ") + listNames(mixedBlossoms)
                + (mixedBlossoms.size() == 1 ? " uses" : " use")
                + " both open and closed eyeblossoms. All eyeblossoms open at night (orange on the map) and close by day "
                + "(green terracotta), together, so one map cannot show both. Use one kind per map, or stock a block of the "
                + "same map color (orange_carpet for open ones) so the upload swaps it in.");
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> unknown = new HashMap<>();
        Map<String, Integer> noItem = new HashMap<>();
        Map<String, Integer> notTop = new HashMap<>();
        Map<String, String> notTopWhy = new HashMap<>();
        Map<String, Integer> gravity = new HashMap<>();
        Map<String, Integer> usedIds = new HashMap<>();
        Map<String, Integer> materials = new HashMap<>();
        long blocks = 0;
        for (var e : states.entrySet()) {
            BlockSpec s = e.getKey();
            int n = e.getValue();
            blocks += n;
            if (!catalog.isKnownBlock(s.id())) {
                unknown.merge(s.id(), n, Integer::sum);
                continue;
            }
            usedIds.merge(s.id(), n, Integer::sum);
            Optional<String> item = catalog.placementItem(s);
            if (item.isEmpty()) noItem.merge(s.toString(), n, Integer::sum);
            else materials.merge(item.get(), n, Integer::sum);
            if (!catalog.placeableOnTopFace(s)) {
                notTop.merge(s.toString(), n, Integer::sum);
                String why = TopFaceRules.problem(s);
                if (why != null) notTopWhy.put(s.toString(), why);
            }
            if (catalog.isGravityBlock(s.id())) gravity.merge(s.id(), n, Integer::sum);
        }
        if (!unknown.isEmpty())
            errors.add("Not in Minecraft 1.21.4 (set MapArtCraft's version to 1.21.4): " + counts(unknown) + ".");
        if (!noItem.isEmpty())
            errors.add("No item can place: " + counts(noItem) + ".");
        if (!notTop.isEmpty())
            errors.add("A click on the top face of the block below cannot produce these states (v1 builds only that way): "
                + counts(notTop, notTopWhy) + ".");

        List<String> platformIds = new ArrayList<>();
        List<MapArtConfig.PlatformCfg> platforms = new ArrayList<>();
        if (site != null && site.platforms != null)
            for (MapArtConfig.PlatformCfg p : site.platforms)
                if (p != null && p.enabled && p.kind != null && PLATFORM_KINDS.contains(p.kind.trim().toUpperCase(Locale.ROOT)))
                    platforms.add(p);
        if (platforms.isEmpty()) {
            errors.add("No enabled moss or soul sand platform is configured (site.platforms).");
        } else {
            Map<MapArtConfig.PlatformCfg, Set<String>> allowed = new LinkedHashMap<>();
            for (MapArtConfig.PlatformCfg p : platforms) allowed.put(p, allowedIds(p));
            Map<String, Integer> nowhere = new HashMap<>();
            for (var e : usedIds.entrySet()) {
                boolean ok = false;
                for (Set<String> a : allowed.values()) ok |= a.contains(e.getKey());
                if (!ok) nowhere.put(e.getKey(), e.getValue());
            }
            List<String> missingPer = new ArrayList<>();
            for (var e : allowed.entrySet()) {
                Map<String, Integer> missing = new HashMap<>();
                usedIds.forEach((id, n) -> {
                    if (!e.getValue().contains(id)) missing.put(id, n);
                });
                if (missing.isEmpty()) platformIds.add(e.getKey().id);
                else missingPer.add(e.getKey().id + " cannot clear " + counts(missing));
            }
            if (!nowhere.isEmpty())
                errors.add("Not clearable on any enabled platform (" + String.join(", ", ids(platforms))
                    + "; neither piston-clearable nor nukeable): " + counts(nowhere) + ".");
            else if (platformIds.isEmpty())
                errors.add("No single platform accepts every block: " + String.join("; ", missingPer) + ".");
        }

        if (sub.dataVersion() > DATA_VERSION_1_21_4)
            warnings.add("Exported for a newer Minecraft (DataVersion " + sub.dataVersion() + "; 1.21.4 is "
                + DATA_VERSION_1_21_4 + "). Blocks were checked against 1.21.4.");
        if (!gravity.isEmpty())
            warnings.add("Gravity blocks (fine on the platform floor): " + counts(gravity) + ".");
        int tiles = sub.tilesX() * sub.tilesZ();
        if (tiles > LARGE_JOB_TILES)
            warnings.add("Large job: " + tiles + " maps, " + blocks + " blocks. It occupies the builder for a long time.");

        Map<String, Integer> sortedMaterials = new LinkedHashMap<>();
        materials.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .forEach(e -> sortedMaterials.put(e.getKey(), e.getValue()));
        return new ValidationResult(List.copyOf(errors), List.copyOf(warnings), sortedMaterials, List.copyOf(platformIds));
    }

    /**
     * A colour-suppression map: an enabled COLOR_SUPPRESSION platform with its two glass floors 14 apart, every block
     * known in 1.21.4, placeable, and one of the platform sorter's blocks ({@link Defaults#CS_BLOCKS}). No same-colour
     * swaps ever (docs/COLOR_SUPPRESSION.md). Pillars on their side (CrubTech's bamboo_block[axis=x]) are placed with a
     * click on a side face.
     */
    private ValidationResult validateSuppression(ParsedSubmission sub, BlockCatalog catalog, MapArtConfig.SiteCfg site) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> platformIds = new ArrayList<>();
        if (site != null && site.platforms != null)
            for (MapArtConfig.PlatformCfg p : site.platforms) {
                if (p == null || !p.enabled || !p.isColorSuppression()) continue;
                if (p.glassY == null || p.glassY.size() != 2 || p.glassY.get(1) - p.glassY.get(0) != SuppressionArt.LAYER_GAP)
                    errors.add("Platform " + p.id + ": `glassY` must list its two glass floors, bottom first, "
                        + SuppressionArt.LAYER_GAP + " apart (got " + p.glassY + ").");
                else platformIds.add(p.id);
            }
        if (platformIds.isEmpty() && errors.isEmpty())
            errors.add("Color suppression maps are built only on an enabled COLOR_SUPPRESSION platform, and none is "
                + "configured (site.platforms).");

        Map<BlockSpec, Integer> states = new HashMap<>();
        SuppressionArt art = sub.suppression();
        for (int layer = 0; layer < SuppressionArt.LAYERS; layer++)
            for (int level = 0; level < SuppressionArt.LEVELS; level++)
                for (int row = SuppressionArt.NOOBLINE; row < MAP; row++)
                    for (int col = 0; col < MAP; col++) {
                        BlockSpec b = art.get(layer, level, col, row);
                        if (b != null) states.merge(b, 1, Integer::sum);
                    }
        Set<String> allowed = new HashSet<>(Defaults.CS_BLOCKS);
        Map<String, Integer> unknown = new HashMap<>();
        Map<String, Integer> noItem = new HashMap<>();
        Map<String, Integer> notAllowed = new HashMap<>();
        Map<String, Integer> notPlaceable = new HashMap<>();
        Map<String, String> why = new HashMap<>();
        Map<String, Integer> materials = new HashMap<>();
        for (var e : states.entrySet()) {
            BlockSpec s = e.getKey();
            int n = e.getValue();
            if (!catalog.isKnownBlock(s.id())) {
                unknown.merge(s.id(), n, Integer::sum);
                continue;
            }
            if (!allowed.contains(s.id())) notAllowed.merge(s.id(), n, Integer::sum);
            Optional<String> item = catalog.placementItem(s);
            if (item.isEmpty()) noItem.merge(s.toString(), n, Integer::sum);
            else materials.merge(item.get(), n, Integer::sum);
            if (!catalog.placeableOnTopFace(s) && !sidewaysPillar(s)) {
                notPlaceable.merge(s.toString(), n, Integer::sum);
                String w = TopFaceRules.problem(s);
                if (w != null) why.put(s.toString(), w);
            }
        }
        if (!unknown.isEmpty()) errors.add("Not in Minecraft 1.21.4: " + counts(unknown) + ".");
        if (!noItem.isEmpty()) errors.add("No item can place: " + counts(noItem) + ".");
        if (!notAllowed.isEmpty())
            errors.add("Not among the color-suppression sorter's blocks (PNG-to-NBT's CrubTech preset plus its moss and "
                + "resin fillers; the guide says not to substitute any): " + counts(notAllowed) + ".");
        if (!notPlaceable.isEmpty())
            errors.add("The bot cannot place these states: " + counts(notPlaceable, why) + ".");
        if (sub.dataVersion() > DATA_VERSION_1_21_4)
            warnings.add("Exported for a newer Minecraft (DataVersion " + sub.dataVersion() + ", "
                + ExportCheck.versionName(sub.dataVersion()) + "); blocks were checked against 1.21.4.");
        warnings.add("Color suppression: " + art.count(SuppressionArt.BOTTOM) + " blocks on the bottom layer, "
            + art.count(SuppressionArt.TOP) + " on the top layer. The bot prints them; the ride stays manual.");
        Map<String, Integer> sortedMaterials = new LinkedHashMap<>();
        materials.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .forEach(e -> sortedMaterials.put(e.getKey(), e.getValue()));
        return new ValidationResult(List.copyOf(errors), List.copyOf(warnings), sortedMaterials,
            errors.isEmpty() ? List.copyOf(platformIds) : List.of());
    }

    /** A pillar block lying on its side (axis x or z): placed by a click on a side face, never on the top face. */
    static boolean sidewaysPillar(BlockSpec s) {
        String axis = s.props().get("axis");
        return s.props().size() == 1 && ("x".equals(axis) || "z".equals(axis));
    }

    private static Set<String> allowedIds(MapArtConfig.PlatformCfg p) {
        Set<String> s = new HashSet<>();
        if (p.clearableBlocks != null) for (String id : p.clearableBlocks) s.add(normalizeId(id));
        if (p.nukeBlocks != null) for (String id : p.nukeBlocks) s.add(normalizeId(id));
        return s;
    }

    private static List<String> ids(List<MapArtConfig.PlatformCfg> platforms) {
        Set<String> l = new LinkedHashSet<>();
        for (MapArtConfig.PlatformCfg p : platforms) l.add(p.id);
        return new ArrayList<>(l);
    }

    private static String normalizeId(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        int b = s.indexOf('[');
        if (b >= 0) s = s.substring(0, b);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    /** "a ×12, b ×3, and 4 more", most frequent first. */
    static String counts(Map<String, Integer> m) {
        return counts(m, Map.of());
    }

    /** As {@link #counts(Map)}, with " (reason)" after each key that has one in {@code why}. */
    static String counts(Map<String, Integer> m, Map<String, String> why) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()));
        List<String> parts = new ArrayList<>();
        for (var e : l) {
            String reason = why.get(e.getKey());
            parts.add(e.getKey() + " ×" + e.getValue() + (reason == null ? "" : " (" + reason + ")"));
        }
        return listNames(parts);
    }

    private static String listNames(Collection<String> names) {
        List<String> l = new ArrayList<>(names);
        if (l.size() <= MAX_LISTED) return String.join(", ", l);
        return String.join(", ", l.subList(0, MAX_LISTED)) + " and " + (l.size() - MAX_LISTED) + " more";
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    private static String quote(String s) {
        return "'" + s + "'";
    }

    private static String megabytes(long bytes) {
        return (bytes >> 20) > 0 ? (bytes >> 20) + " MB" : bytes + " bytes";
    }
}
