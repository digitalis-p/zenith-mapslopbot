package io.github.digitalisp.mapart.nbt;

import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Schematic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parses a vanilla structure-block .nbt (gzip or raw) into a {@link Schematic}. Streams the block list, so
 * memory stays proportional to the number of blocks rather than to the decoded tag tree.
 * Root layout: {@code {size:[X,Y,Z], palette:[{Name, Properties?}], blocks:[{pos:[x,y,z], state}], DataVersion,
 * author?}}, root tags in any order; a multi-palette {@code palettes} list is accepted (first palette used).
 * A palette keyed {@code id}/{@code properties} (the newer structure format, e.g. PNG-to-NBT at DataVersion 5006 and
 * up) is refused with a {@link NewerFormatException} once the whole root has been read, so the exception carries the
 * DataVersion and author wherever they sit in the file.
 */
public final class StructureNbtParser {
    /** Default cap on the uncompressed NBT size. */
    public static final long DEFAULT_MAX_BYTES = 128L << 20;
    /** Largest accepted dimension along any axis. */
    public static final int MAX_DIMENSION = 1 << 14;

    /**
     * The palette uses the {@code id}/{@code properties} keys of newer Minecraft structure files instead of 1.21.4's
     * {@code Name}/{@code Properties}. {@link #dataVersion()} is 0 and {@link #author()} "" when the file has none.
     */
    public static final class NewerFormatException extends NbtException {
        private static final long serialVersionUID = 1L;
        private final int dataVersion;
        private final String author;

        public NewerFormatException(int dataVersion, String author) {
            super("palette uses the 'id' keys of newer Minecraft structure files ("
                + (dataVersion > 0 ? "DataVersion " + dataVersion : "no DataVersion")
                + "); this plugin reads the 1.21.4 format");
            this.dataVersion = dataVersion;
            this.author = author == null ? "" : author;
        }

        public int dataVersion() {
            return dataVersion;
        }

        public String author() {
            return author;
        }
    }

    private StructureNbtParser() { }

    public static Schematic parse(byte[] data) throws NbtException {
        return parse(data, DEFAULT_MAX_BYTES);
    }

    public static Schematic parse(byte[] data, long maxBytes) throws NbtException {
        return parse(data, maxBytes, false);
    }

    /**
     * As {@link #parse(byte[], long)}; with {@code acceptIdKeys} a palette keyed {@code id}/{@code properties} (the 26.x
     * structure format) is read like {@code Name}/{@code Properties} instead of being refused. Used for
     * colour-suppression exports, whose blocks are then checked against 1.21.4 (docs/COLOR_SUPPRESSION.md).
     */
    public static Schematic parse(byte[] data, long maxBytes, boolean acceptIdKeys) throws NbtException {
        NbtReader r = NbtReader.open(data, maxBytes);
        if (r.readType() != NbtReader.COMPOUND) throw new NbtException("root tag is not a compound");
        r.readString();
        int[] size = null;
        List<?> palette = null;
        List<?> firstOfPalettes = null;
        List<Schematic.Block> blocks = null;
        boolean blocksSkipped = false;
        int dataVersion = 0;
        String author = "";
        int t;
        while ((t = r.readType()) != NbtReader.END) {
            String key = r.readString();
            switch (key) {
                case "size" -> size = readTriple(r, t, "size");
                case "palette" -> palette = readPalette(r, t);
                case "palettes" -> firstOfPalettes = readPalettes(r, t);
                case "blocks" -> {
                    if (!acceptIdKeys && palette != null && usesIdKeys(palette)) {
                        r.skipPayload(t); // refused below anyway: do not hold its blocks
                        blocksSkipped = true;
                    } else {
                        blocks = readBlocks(r, t);
                    }
                }
                case "DataVersion" -> dataVersion = readIntLike(r, t, "DataVersion");
                case "author" -> {
                    if (t == NbtReader.STRING) author = r.readString();
                    else r.skipPayload(t);
                }
                default -> r.skipPayload(t);
            }
        }
        List<?> raw = palette != null ? palette : firstOfPalettes;
        if (blocksSkipped || (!acceptIdKeys && raw != null && usesIdKeys(raw)))
            throw new NewerFormatException(dataVersion, author);
        if (size == null) throw new NbtException("not a structure file: missing 'size'");
        if (raw == null) throw new NbtException("not a structure file: missing 'palette'");
        if (blocks == null) throw new NbtException("not a structure file: missing 'blocks'");
        List<BlockSpec> spec = toPalette(raw, acceptIdKeys);
        for (int i = 0; i < 3; i++)
            if (size[i] <= 0 || size[i] > MAX_DIMENSION)
                throw new NbtException("invalid structure size " + size[0] + "x" + size[1] + "x" + size[2]);
        for (Schematic.Block b : blocks) {
            if (b.state() < 0 || b.state() >= spec.size())
                throw new NbtException("block at " + b.x() + "," + b.y() + "," + b.z() + " uses palette index "
                    + b.state() + " but the palette has " + spec.size() + " entries");
            if (b.x() < 0 || b.y() < 0 || b.z() < 0 || b.x() >= size[0] || b.y() >= size[1] || b.z() >= size[2])
                throw new NbtException("block at " + b.x() + "," + b.y() + "," + b.z() + " lies outside the structure size "
                    + size[0] + "x" + size[1] + "x" + size[2]);
        }
        return new Schematic(size[0], size[1], size[2], dataVersion, author, List.copyOf(spec), blocks);
    }

    private static List<Schematic.Block> readBlocks(NbtReader r, int type) throws NbtException {
        if (type != NbtReader.LIST) throw new NbtException("'blocks' is not a list");
        int et = r.readListType();
        int n = r.readLength(1);
        PackedBlocks out = new PackedBlocks(n);
        if (n == 0) return out;
        if (et != NbtReader.COMPOUND) throw new NbtException("'blocks' is not a list of compounds");
        for (int i = 0; i < n; i++) {
            int[] pos = null;
            Integer state = null;
            int t;
            while ((t = r.readType()) != NbtReader.END) {
                String key = r.readString();
                switch (key) {
                    case "pos" -> pos = readTriple(r, t, "pos");
                    case "state" -> state = readIntLike(r, t, "state");
                    default -> r.skipPayload(t);
                }
            }
            if (pos == null || state == null) throw new NbtException("block entry " + i + " lacks 'pos' or 'state'");
            out.add(pos[0], pos[1], pos[2], state);
        }
        return out;
    }

    /**
     * The block list as packed ints (4 per block, 16 bytes) instead of one record per block: a large single-file export
     * (millions of blocks) stays small on the live proxy's heap. Read-only once parsed; {@link #get} makes the record.
     */
    static final class PackedBlocks extends java.util.AbstractList<Schematic.Block> implements java.util.RandomAccess {
        private int[] v;
        private int size;

        PackedBlocks(int expected) {
            v = new int[4 * Math.max(16, Math.min(expected, 1 << 22))];
        }

        void add(int x, int y, int z, int state) {
            if (4 * size + 4 > v.length) v = java.util.Arrays.copyOf(v, Math.max(v.length * 2, 4 * size + 4));
            int i = 4 * size++;
            v[i] = x;
            v[i + 1] = y;
            v[i + 2] = z;
            v[i + 3] = state;
        }

        @Override
        public Schematic.Block get(int index) {
            java.util.Objects.checkIndex(index, size);
            int i = 4 * index;
            return new Schematic.Block(v[i], v[i + 1], v[i + 2], v[i + 3]);
        }

        @Override
        public int size() {
            return size;
        }
    }

    private static List<?> readPalette(NbtReader r, int type) throws NbtException {
        if (type != NbtReader.LIST) throw new NbtException("'palette' is not a list");
        return (List<?>) r.readPayload(type);
    }

    private static List<?> readPalettes(NbtReader r, int type) throws NbtException {
        if (type != NbtReader.LIST) throw new NbtException("'palettes' is not a list");
        Object v = r.readPayload(type);
        if (!(v instanceof List<?> l) || l.isEmpty()) return null;
        if (!(l.getFirst() instanceof List<?> first)) throw new NbtException("palette is not a list");
        return first;
    }

    /** True if some entry has the newer format's {@code id} key and no {@code Name}. */
    private static boolean usesIdKeys(List<?> palette) {
        for (Object o : palette)
            if (o instanceof Map<?, ?> m && !(m.get("Name") instanceof String) && m.get("id") instanceof String id
                && !id.isBlank()) return true;
        return false;
    }

    private static List<BlockSpec> toPalette(List<?> l, boolean acceptIdKeys) throws NbtException {
        List<BlockSpec> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m)) throw new NbtException("palette entry " + out.size() + " has no Name");
            Object n = m.get("Name");
            Object propsTag = m.get("Properties");
            if (acceptIdKeys && !(n instanceof String)) {
                n = m.get("id");
                propsTag = m.get("properties");
            }
            if (!(n instanceof String name) || name.isBlank())
                throw new NbtException("palette entry " + out.size() + " has no Name");
            TreeMap<String, String> props = new TreeMap<>();
            if (propsTag instanceof Map<?, ?> p)
                for (var e : p.entrySet()) props.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            out.add(new BlockSpec(name.trim(), props));
        }
        return out;
    }

    private static int[] readTriple(NbtReader r, int type, String what) throws NbtException {
        int[] v;
        if (type == NbtReader.INT_ARRAY) {
            v = (int[]) r.readPayload(type);
        } else if (type == NbtReader.LIST) {
            int et = r.readListType();
            int n = r.readLength(4);
            if (et != NbtReader.INT && n > 0) throw new NbtException("'" + what + "' is not a list of ints");
            v = new int[n];
            for (int i = 0; i < n; i++) v[i] = r.readInt();
        } else {
            throw new NbtException("'" + what + "' is not an int list");
        }
        if (v.length != 3) throw new NbtException("'" + what + "' has " + v.length + " values, expected 3");
        return v;
    }

    private static int readIntLike(NbtReader r, int type, String what) throws NbtException {
        return switch (type) {
            case NbtReader.BYTE -> r.readByte();
            case NbtReader.SHORT -> r.readShort();
            case NbtReader.INT -> r.readInt();
            case NbtReader.LONG -> {
                long l = r.readLong();
                if (l != (int) l) throw new NbtException("'" + what + "' out of range: " + l);
                yield (int) l;
            }
            default -> throw new NbtException("'" + what + "' is not an integer");
        };
    }
}
