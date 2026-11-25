package io.github.digitalisp.mapart.testutil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Test-side NBT encoder from plain Java values: Byte, Short, Integer, Long, Float, Double, byte[], String,
 * List (element type from the first element; empty = End), Map (compound), int[], long[].
 */
public final class NbtWriter {
    private NbtWriter() { }

    public static byte[] raw(Map<String, ?> root) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeByte(10);
            out.writeUTF("");
            payload(out, root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    public static byte[] gzip(Map<String, ?> root) {
        return gzipBytes(raw(root));
    }

    public static byte[] gzipBytes(byte[] raw) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    public static int type(Object v) {
        if (v instanceof Byte) return 1;
        if (v instanceof Short) return 2;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        if (v instanceof Float) return 5;
        if (v instanceof Double) return 6;
        if (v instanceof byte[]) return 7;
        if (v instanceof String) return 8;
        if (v instanceof List) return 9;
        if (v instanceof Map) return 10;
        if (v instanceof int[]) return 11;
        if (v instanceof long[]) return 12;
        throw new IllegalArgumentException("no NBT type for " + v);
    }

    @SuppressWarnings("unchecked")
    private static void payload(DataOutputStream out, Object v) throws IOException {
        switch (type(v)) {
            case 1 -> out.writeByte((Byte) v);
            case 2 -> out.writeShort((Short) v);
            case 3 -> out.writeInt((Integer) v);
            case 4 -> out.writeLong((Long) v);
            case 5 -> out.writeFloat((Float) v);
            case 6 -> out.writeDouble((Double) v);
            case 7 -> {
                byte[] b = (byte[]) v;
                out.writeInt(b.length);
                out.write(b);
            }
            case 8 -> out.writeUTF((String) v);
            case 9 -> {
                List<Object> l = (List<Object>) v;
                out.writeByte(l.isEmpty() ? 0 : type(l.getFirst()));
                out.writeInt(l.size());
                for (Object o : l) payload(out, o);
            }
            case 10 -> {
                for (var e : ((Map<String, Object>) v).entrySet()) {
                    out.writeByte(type(e.getValue()));
                    out.writeUTF(e.getKey());
                    payload(out, e.getValue());
                }
                out.writeByte(0);
            }
            case 11 -> {
                int[] a = (int[]) v;
                out.writeInt(a.length);
                for (int i : a) out.writeInt(i);
            }
            case 12 -> {
                long[] a = (long[]) v;
                out.writeInt(a.length);
                for (long l : a) out.writeLong(l);
            }
            default -> throw new IllegalStateException();
        }
    }
}
