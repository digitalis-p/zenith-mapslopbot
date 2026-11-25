package io.github.digitalisp.mapart.nbt;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UTFDataFormatException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Minimal streaming reader for Java-edition binary NBT: big-endian, modified-UTF-8 strings, gzip or raw.
 * Generic payloads decode to plain Java values: Byte, Short, Integer, Long, Float, Double, byte[], String,
 * {@code List<Object>} (list tag), {@code Map<String, Object>} (compound, insertion-ordered), int[], long[].
 * Every byte read counts against a size limit, so gzip bombs and absurd array lengths fail fast.
 */
public final class NbtReader {
    public static final int END = 0;
    public static final int BYTE = 1;
    public static final int SHORT = 2;
    public static final int INT = 3;
    public static final int LONG = 4;
    public static final int FLOAT = 5;
    public static final int DOUBLE = 6;
    public static final int BYTE_ARRAY = 7;
    public static final int STRING = 8;
    public static final int LIST = 9;
    public static final int COMPOUND = 10;
    public static final int INT_ARRAY = 11;
    public static final int LONG_ARRAY = 12;

    /** Maximum nesting of lists/compounds (vanilla's limit). */
    public static final int MAX_DEPTH = 512;

    /** A root tag: its type, name (usually empty) and decoded payload. */
    public record NamedTag(int type, String name, Object value) { }

    private final Counting counter;
    private final DataInputStream in;
    private final byte[] scratch = new byte[4096];

    /** Reads uncompressed NBT from {@code raw}; at most {@code maxBytes} bytes are consumed. */
    public NbtReader(InputStream raw, long maxBytes) {
        this.counter = new Counting(raw, maxBytes);
        this.in = new DataInputStream(counter);
    }

    /** Opens {@code data}, transparently gunzipping it; {@code maxBytes} limits the uncompressed size. */
    public static NbtReader open(byte[] data, long maxBytes) throws NbtException {
        InputStream s = new ByteArrayInputStream(data);
        if (isGzip(data)) {
            try {
                s = new BufferedInputStream(new GZIPInputStream(s, 8192), 65536);
            } catch (IOException e) {
                throw new NbtException("corrupt gzip header", e);
            }
        }
        return new NbtReader(s, maxBytes);
    }

    /** Reads a whole document (one root tag) from {@code data}. */
    public static NamedTag read(byte[] data, long maxBytes) throws NbtException {
        return open(data, maxBytes).readRoot();
    }

    public static boolean isGzip(byte[] data) {
        return data.length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B;
    }

    /** Reads the root tag: type byte, name, payload. */
    public NamedTag readRoot() throws NbtException {
        int type = readType();
        if (type == END) throw new NbtException("empty NBT document");
        String name = readString();
        return new NamedTag(type, name, readPayload(type));
    }

    /** Uncompressed bytes consumed so far. */
    public long bytesRead() {
        return counter.count;
    }

    public int readType() throws NbtException {
        int t = readByte() & 0xFF;
        if (t > LONG_ARRAY) throw new NbtException("unknown tag type " + t + " at byte " + counter.count);
        return t;
    }

    public byte readByte() throws NbtException {
        try {
            return in.readByte();
        } catch (IOException e) {
            throw wrap(e);
        }
    }

    public short readShort() throws NbtException {
        try {
            return in.readShort();
        } catch (IOException e) {
            throw wrap(e);
        }
    }

    public int readInt() throws NbtException {
        try {
            return in.readInt();
        } catch (IOException e) {
            throw wrap(e);
        }
    }

    public long readLong() throws NbtException {
        try {
            return in.readLong();
        } catch (IOException e) {
            throw wrap(e);
        }
    }

    /** Modified UTF-8 with an unsigned 16-bit length prefix. */
    public String readString() throws NbtException {
        try {
            return in.readUTF();
        } catch (UTFDataFormatException e) {
            throw new NbtException("malformed modified-UTF-8 string at byte " + counter.count, e);
        } catch (IOException e) {
            throw wrap(e);
        }
    }

    /** Reads a list header and returns its element type; call {@link #readLength(int)} next. */
    public int readListType() throws NbtException {
        return readType();
    }

    /** Reads a length prefix and checks that {@code n * minElementBytes} still fits in the limit. */
    public int readLength(int minElementBytes) throws NbtException {
        int n = readInt();
        if (n < 0) throw new NbtException("negative length " + n + " at byte " + counter.count);
        if ((long) n * minElementBytes > counter.remaining())
            throw new NbtException("length " + n + " at byte " + counter.count + " exceeds the limit of "
                + counter.max + " bytes");
        return n;
    }

    /** Decodes a payload of the given type into plain Java values (see class doc). */
    public Object readPayload(int type) throws NbtException {
        return readPayload(type, 0);
    }

    /** Skips a payload of the given type. */
    public void skipPayload(int type) throws NbtException {
        skipPayload(type, 0);
    }

    private Object readPayload(int type, int depth) throws NbtException {
        switch (type) {
            case BYTE:
                return readByte();
            case SHORT:
                return readShort();
            case INT:
                return readInt();
            case LONG:
                return readLong();
            case FLOAT:
                return Float.intBitsToFloat(readInt());
            case DOUBLE:
                return Double.longBitsToDouble(readLong());
            case BYTE_ARRAY: {
                byte[] b = new byte[readLength(1)];
                readFully(b);
                return b;
            }
            case STRING:
                return readString();
            case LIST: {
                checkDepth(depth);
                int et = readListType();
                int n = readLength(minSize(et));
                List<Object> l = new ArrayList<>(Math.min(n, 4096));
                if (et == END) return l;
                for (int i = 0; i < n; i++) l.add(readPayload(et, depth + 1));
                return l;
            }
            case COMPOUND: {
                checkDepth(depth);
                Map<String, Object> m = new LinkedHashMap<>();
                int t;
                while ((t = readType()) != END) {
                    String k = readString();
                    m.put(k, readPayload(t, depth + 1));
                }
                return m;
            }
            case INT_ARRAY: {
                int[] a = new int[readLength(4)];
                for (int i = 0; i < a.length; i++) a[i] = readInt();
                return a;
            }
            case LONG_ARRAY: {
                long[] a = new long[readLength(8)];
                for (int i = 0; i < a.length; i++) a[i] = readLong();
                return a;
            }
            default:
                throw new NbtException("unexpected tag type " + type + " at byte " + counter.count);
        }
    }

    private void skipPayload(int type, int depth) throws NbtException {
        switch (type) {
            case BYTE -> skip(1);
            case SHORT -> skip(2);
            case INT, FLOAT -> skip(4);
            case LONG, DOUBLE -> skip(8);
            case BYTE_ARRAY -> skip(readLength(1));
            case STRING -> skip(readShort() & 0xFFFF);
            case LIST -> {
                checkDepth(depth);
                int et = readListType();
                int n = readLength(minSize(et));
                if (et == END) return;
                int fixed = fixedSize(et);
                if (fixed > 0) skip((long) n * fixed);
                else for (int i = 0; i < n; i++) skipPayload(et, depth + 1);
            }
            case COMPOUND -> {
                checkDepth(depth);
                int t;
                while ((t = readType()) != END) {
                    skip(readShort() & 0xFFFF);
                    skipPayload(t, depth + 1);
                }
            }
            case INT_ARRAY -> skip((long) readLength(4) * 4);
            case LONG_ARRAY -> skip((long) readLength(8) * 8);
            default -> throw new NbtException("unexpected tag type " + type + " at byte " + counter.count);
        }
    }

    private static int fixedSize(int type) {
        return switch (type) {
            case BYTE -> 1;
            case SHORT -> 2;
            case INT, FLOAT -> 4;
            case LONG, DOUBLE -> 8;
            default -> 0;
        };
    }

    /** Smallest encoded size of one list element of this type (for length sanity checks). */
    private static int minSize(int type) {
        return switch (type) {
            case END -> 0;
            case BYTE, COMPOUND -> 1;
            case SHORT, STRING -> 2;
            case INT, FLOAT, BYTE_ARRAY, INT_ARRAY, LONG_ARRAY -> 4;
            case LIST -> 5;
            case LONG, DOUBLE -> 8;
            default -> 1;
        };
    }

    private void checkDepth(int depth) throws NbtException {
        if (depth >= MAX_DEPTH) throw new NbtException("NBT nested deeper than " + MAX_DEPTH);
    }

    private void readFully(byte[] b) throws NbtException {
        try {
            in.readFully(b);
        } catch (IOException e) {
            throw wrap(e);
        }
    }

    private void skip(long n) throws NbtException {
        try {
            while (n > 0) {
                int step = (int) Math.min(n, scratch.length);
                in.readFully(scratch, 0, step);
                n -= step;
            }
        } catch (IOException e) {
            throw wrap(e);
        }
    }

    private NbtException wrap(IOException e) {
        if (e instanceof NbtException n) return n;
        if (e instanceof EOFException) return new NbtException("truncated NBT data at byte " + counter.count, e);
        if (e instanceof ZipException) return new NbtException("corrupt gzip data: " + e.getMessage(), e);
        return new NbtException("cannot read NBT: " + e.getMessage(), e);
    }

    /** Counts consumed bytes and refuses to go past the limit. */
    private static final class Counting extends FilterInputStream {
        final long max;
        long count;

        Counting(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        long remaining() {
            return max - count;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) advance(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, (int) Math.min(len, Math.max(1, remaining() + 1)));
            if (n > 0) advance(n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(Math.min(n, Math.max(0, remaining())));
            advance(s);
            return s;
        }

        private void advance(long n) throws NbtException {
            count += n;
            if (count > max) throw new NbtException("NBT data exceeds the limit of " + max + " bytes");
        }
    }
}
