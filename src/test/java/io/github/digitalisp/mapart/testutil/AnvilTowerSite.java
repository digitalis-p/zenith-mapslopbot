package io.github.digitalisp.mapart.testutil;

import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;

import static io.github.digitalisp.mapart.testutil.ContainerSim.GLASS;
import static io.github.digitalisp.mapart.testutil.ContainerSim.STONE;
import static io.github.digitalisp.mapart.testutil.ContainerSim.chest;
import static io.github.digitalisp.mapart.testutil.ContainerSim.wallSign;

/**
 * The SHAPE of the gate-B2 anvil failure, around a synthetic anvil cell {@code (x, y, z)} (never the base's
 * coordinates): the anvil tower at the north end of the supply column, with the tower emptied as the test did. The
 * tile ({@link #tile}) starts 10 south of the tower, its columns include the tower's x and its art layer is the
 * walkway floor's height, so the tower and the whole walkway lie in the tile's clear sweep.
 * <ul>
 *   <li>The walkway: floor at {@code y - 2}, feet {@code y - 1}, from the brick wall at {@code x - 1} to
 *       {@code x + 10}, {@code z - 5 .. z + 8}. Solid below, but for three sealed 2-high pockets with their feet at
 *       {@code y - 4} ({@link #pockets}): standable and in reach of the table, never walked to.</li>
 *   <li>The tower: a crafting table at walkway level {@code (x, y - 1, z)}, the anvil cell above it, and the cells
 *       above that empty.</li>
 *   <li>North of it chiseled blackstone at walkway level, polished blackstone walls above (not full cubes). South of
 *       it the supply column: chests facing east at {@code x}, {@code z + 1 .. z + 3}, walkway level to 4 above, with
 *       a wall sign on each east face of {@code z + 2} and {@code z + 3}. West a brick wall along {@code x - 1} (1.5
 *       high: not stood on) and a glass railing behind the column.</li>
 * </ul>
 * Nothing in reach to place against but the crafting table's top face: a sneak-click from the walkway east of it.
 */
public final class AnvilTowerSite {
    private AnvilTowerSite() { }

    public static final BlockSpec TABLE = BlockSpec.of("minecraft:crafting_table");

    /** Builds it and returns the anvil cell {@code (x, y, z)}, empty. */
    public static Pos build(ContainerSim s, int x, int y, int z) {
        s.fill(x - 1, y - 5, z - 5, x + 10, y - 2, z + 8, STONE); // the walkway and the solid mass under it
        for (Pos p : pockets(x, y, z)) s.fill(p.x(), p.y(), p.z(), p.x(), p.y() + 1, p.z(), BlockSpec.AIR);
        s.set(x, y - 1, z, TABLE);
        s.set(x, y - 1, z - 1, BlockSpec.of("minecraft:chiseled_polished_blackstone"));
        s.fill(x, y, z - 1, x, y + 1, z - 1, BlockSpec.of("minecraft:polished_blackstone_wall"));
        s.set(x + 1, y + 1, z - 1, BlockSpec.of("minecraft:polished_blackstone_wall"));
        for (int cz = z + 1; cz <= z + 3; cz++) {
            for (int cy = y - 1; cy <= y + 3; cy++) {
                s.set(x, cy, cz, chest("east", "single"));
                if (cz >= z + 2) s.set(x + 1, cy, cz, wallSign("east"));
            }
        }
        s.fill(x - 1, y - 1, z - 5, x - 1, y - 1, z + 2, BlockSpec.of("minecraft:polished_blackstone_brick_wall"));
        s.fill(x - 3, y - 1, z + 3, x - 1, y + 1, z + 3, GLASS);
        return new Pos(x, y, z);
    }

    /** The tile: 116 east of its west edge, 10 north of its north edge, art layer at the walkway floor. */
    public static PlatformGeometry tile(int x, int y, int z) {
        return new PlatformGeometry(x - 116, z + 10, y - 2);
    }

    /** Feet position of the bot where it stood holding the anvil: on the walkway south-east of the column. */
    public static double[] bot(int x, int y, int z) {
        return new double[]{x + 2.6, y - 1, z + 4.3};
    }

    /** Feet cells of the sealed pockets under the walkway. */
    public static List<Pos> pockets(int x, int y, int z) {
        return List.of(new Pos(x, y - 4, z - 3), new Pos(x + 2, y - 4, z - 3), new Pos(x + 4, y - 4, z - 1));
    }
}
