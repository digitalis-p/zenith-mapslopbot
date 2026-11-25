package io.github.digitalisp.mapart.nbt;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.testutil.NbtWriter;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbtReaderTest {
    private static final String TRICKY = "héllo \u0000 😀 end";

    private static Map<String, Object> everyType() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("inner", "x");
        nested.put("deeper", new LinkedHashMap<>(Map.of("n", 1)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", (byte) -5);
        m.put("s", (short) 31000);
        m.put("i", -123456789);
        m.put("l", 1L << 40);
        m.put("f", 1.5f);
        m.put("d", -2.25);
        m.put("ba", new byte[] {1, 2, -3});
        m.put("str", TRICKY);
        m.put("ints", List.of(1, 2, 3));
        m.put("compounds", List.of(new LinkedHashMap<>(Map.of("a", 1)), new LinkedHashMap<>(Map.of("a", 2))));
        m.put("empty", List.of());
        m.put("lists", List.of(List.of("a"), List.of("b", "c")));
        m.put("nested", nested);
        m.put("ia", new int[] {7, -8});
        m.put("la", new long[] {Long.MIN_VALUE, 9});
        return m;
    }

    @Test
    void readsEveryTagTypeRawAndGzip() throws NbtException {
        for (byte[] data : List.of(NbtWriter.raw(everyType()), NbtWriter.gzip(everyType()))) {
            NbtReader.NamedTag root = NbtReader.read(data, 1 << 20);
            assertEquals(NbtReader.COMPOUND, root.type());
            assertEquals("", root.name());
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) root.value();
            assertEquals((byte) -5, m.get("b"));
            assertEquals((short) 31000, m.get("s"));
            assertEquals(-123456789, m.get("i"));
            assertEquals(1L << 40, m.get("l"));
            assertEquals(1.5f, m.get("f"));
            assertEquals(-2.25, m.get("d"));
            assertArrayEquals(new byte[] {1, 2, -3}, (byte[]) m.get("ba"));
            assertEquals(TRICKY, m.get("str"));
            assertEquals(List.of(1, 2, 3), m.get("ints"));
            assertEquals(List.of(Map.of("a", 1), Map.of("a", 2)), m.get("compounds"));
            assertEquals(List.of(), m.get("empty"));
            assertEquals(List.of(List.of("a"), List.of("b", "c")), m.get("lists"));
            assertEquals(Map.of("inner", "x", "deeper", Map.of("n", 1)), m.get("nested"));
            assertArrayEquals(new int[] {7, -8}, (int[]) m.get("ia"));
            assertArrayEquals(new long[] {Long.MIN_VALUE, 9}, (long[]) m.get("la"));
            assertEquals(List.copyOf(everyType().keySet()), List.copyOf(m.keySet()), "compound keeps key order");
        }
    }

    @Test
    void modifiedUtf8EncodesNulAsTwoBytes() throws NbtException {
        byte[] raw = NbtWriter.raw(Map.of("s", "\u0000"));
        // root(1) + name len(2) + type(1) + key len(2) + "s"(1) + str len(2) + C0 80 + end(1)
        assertEquals(12, raw.length);
        assertEquals((byte) 0xC0, raw[9]);
        assertEquals((byte) 0x80, raw[10]);
        assertEquals("\u0000", ((Map<?, ?>) NbtReader.read(raw, 100).value()).get("s"));
    }

    @Test
    void skipsEveryTagType() throws NbtException {
        byte[] raw = NbtWriter.raw(everyType());
        NbtReader r = NbtReader.open(raw, 1 << 20);
        assertEquals(NbtReader.COMPOUND, r.readType());
        r.readString();
        r.skipPayload(NbtReader.COMPOUND);
        assertEquals(raw.length, r.bytesRead());
    }

    @Test
    void enforcesTheSizeLimitOnDecompressedData() {
        byte[] gz = NbtWriter.gzip(Map.of("big", new byte[100_000]));
        assertTrue(gz.length < 2_000, "compresses well");
        NbtException e = assertThrows(NbtException.class, () -> NbtReader.read(gz, 50_000));
        assertTrue(e.getMessage().contains("exceeds"), e.getMessage());
    }

    @Test
    void rejectsAbsurdLengthsWithoutAllocating() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(10);
        out.writeUTF("");
        out.writeByte(11);
        out.writeUTF("ia");
        out.writeInt(1_000_000_000);
        out.writeByte(0);
        NbtException e = assertThrows(NbtException.class, () -> NbtReader.read(bos.toByteArray(), 1 << 20));
        assertTrue(e.getMessage().contains("length"), e.getMessage());
    }

    @Test
    void rejectsNegativeLengths() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(9);
        out.writeUTF("");
        out.writeByte(3);
        out.writeInt(-1);
        assertThrows(NbtException.class, () -> NbtReader.read(bos.toByteArray(), 1 << 20));
    }

    @Test
    void rejectsTruncatedData() {
        byte[] raw = NbtWriter.raw(everyType());
        byte[] cut = Arrays.copyOf(raw, raw.length - 10);
        NbtException e = assertThrows(NbtException.class, () -> NbtReader.read(cut, 1 << 20));
        assertTrue(e.getMessage().contains("truncated"), e.getMessage());
        byte[] gz = NbtWriter.gzip(everyType());
        assertThrows(NbtException.class, () -> NbtReader.read(Arrays.copyOf(gz, gz.length / 2), 1 << 20));
    }

    @Test
    void rejectsUnknownTagTypesAndEmptyDocuments() {
        assertThrows(NbtException.class, () -> NbtReader.read(new byte[] {13, 0, 0}, 100));
        assertThrows(NbtException.class, () -> NbtReader.read(new byte[] {0}, 100));
        assertThrows(NbtException.class, () -> NbtReader.read(new byte[0], 100));
    }

    @Test
    void limitsNestingDepth() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(9);
        out.writeUTF("");
        for (int i = 0; i < 600; i++) {
            out.writeByte(9);
            out.writeInt(1);
        }
        out.writeByte(0);
        out.writeInt(0);
        NbtException e = assertThrows(NbtException.class, () -> NbtReader.read(bos.toByteArray(), 1 << 20));
        assertTrue(e.getMessage().contains("deeper"), e.getMessage());
    }

    @Test
    void returnsTheRootName() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(10);
        out.writeUTF("Level");
        out.writeByte(3);
        out.writeUTF("k");
        out.writeInt(1);
        out.writeByte(0);
        NbtReader.NamedTag t = NbtReader.read(bos.toByteArray(), 100);
        assertEquals("Level", t.name());
        assertInstanceOf(Map.class, t.value());
        assertEquals(Map.of("k", 1), t.value());
    }
}
