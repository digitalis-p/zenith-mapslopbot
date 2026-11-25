package io.github.digitalisp.mapart.testutil;

import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;

import static io.github.digitalisp.mapart.testutil.ContainerSim.GLASS;
import static io.github.digitalisp.mapart.testutil.ContainerSim.STONE;
import static io.github.digitalisp.mapart.testutil.ContainerSim.chest;
import static io.github.digitalisp.mapart.testutil.ContainerSim.wallSign;

/**
 * Synthetic storage layouts for container-access tests. They copy the SHAPE of two spots of the moss base that failed
 * at gate A, around any origin; never its coordinates.
 */
public final class ContainerSites {
    private ContainerSites() { }

    /**
     * The output-chest column around the output chest {@code out} (a single chest facing south, one block above the
     * floor's feet level): a single chest under it; north of them a stack of double chests facing east; oak wall signs
     * against the east face of the lower chest, of the output chest and of the double-chest stack (the boards hide the
     * east face centres); a glass wall on the west side; anvils further north; open floor (stone at
     * {@code out.y() - 2}) east and south. Returns the bot's feet position at gate A: north-east of the output chest,
     * standing in the column of signs (signs have no collision).
     */
    public static double[] outputColumn(ContainerSim s, Pos out) {
        int x = out.x();
        int y = out.y();
        int z = out.z();
        s.fill(x - 4, y - 2, z - 6, x + 6, y - 2, z + 5, STONE);
        s.set(x, y - 1, z, chest("south", "single"));
        s.set(out, chest("south", "single"));
        for (int cy = y - 1; cy <= y + 3; cy++) {
            s.set(x, cy, z - 2, chest("east", "left"));
            s.set(x, cy, z - 1, chest("east", "right"));
            s.set(x + 1, cy, z - 1, wallSign("east"));
        }
        s.set(x + 1, y - 1, z, wallSign("east"));
        s.set(x + 1, y, z, wallSign("east"));
        s.fill(x - 3, y - 1, z, x - 1, y + 2, z, GLASS);
        s.fill(x - 1, y - 1, z - 2, x - 1, y + 2, z - 1, GLASS);
        s.fill(x, y, z - 3, x, y + 3, z - 3, BlockSpec.of("minecraft:anvil", java.util.Map.of("facing", "north")));
        s.set(x, y - 1, z - 3, BlockSpec.of("minecraft:crafting_table"));
        s.targets = List.of(out);
        return new double[]{x + 1.5, y - 1, z - 0.5};
    }

    /**
     * A sorter wall: double chests (halves along z, both facing west as built) at x in [x0, x1], five rows from feet
     * level {@code floorY + 1} up, the front half at {@code zFront}, the back half at {@code zFront - 1}, hoppers behind;
     * a glass walkway south of it with a row of carpets right in front of the chests. The top row is 4 blocks above
     * the feet: a goal at its height ± 2 has no cell on the walkway.
     */
    public static void sorterWall(ContainerSim s, int x0, int x1, int floorY, int zFront) {
        s.fill(x0 - 4, floorY, zFront + 1, x1 + 4, floorY, zFront + 6, GLASS);
        for (int x = x0; x <= x1; x++) {
            s.set(x, floorY + 1, zFront + 1, BlockSpec.of("minecraft:lime_carpet"));
            for (int y = floorY + 1; y <= floorY + 5; y++) {
                s.set(x, y, zFront - 2, BlockSpec.of("minecraft:hopper"));
                s.set(x, y, zFront - 1, chest("west", "right"));
                s.set(x, y, zFront, chest("west", "left"));
            }
        }
    }

    /** Both halves of the sorter's chest at column {@code x}, row {@code y}: the stock-index half first. */
    public static List<Pos> sorterChest(int x, int y, int zFront) {
        return List.of(new Pos(x, y, zFront - 1), new Pos(x, y, zFront));
    }
}
