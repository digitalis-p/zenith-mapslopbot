package io.github.digitalisp.mapart.nbt;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Schematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The packed block list a large export is parsed into (live 2026-10-15: a 1800x1200 single-file export). */
class PackedBlocksTest {
    @Test
    void growsPastItsFirstCapacityAndReturnsEveryBlock() {
        StructureNbtParser.PackedBlocks b = new StructureNbtParser.PackedBlocks(3);
        int n = 100_000;
        for (int i = 0; i < n; i++) b.add(i % 1800, 0, i / 1800, i % 7);
        assertEquals(n, b.size());
        assertEquals(new Schematic.Block(0, 0, 0, 0), b.get(0));
        assertEquals(new Schematic.Block((n - 1) % 1800, 0, (n - 1) / 1800, (n - 1) % 7), b.get(n - 1));
        int states = 0;
        for (Schematic.Block x : b) states += x.state();
        int want = 0;
        for (int i = 0; i < n; i++) want += i % 7;
        assertEquals(want, states);
        assertThrows(IndexOutOfBoundsException.class, () -> b.get(n));
    }
}
