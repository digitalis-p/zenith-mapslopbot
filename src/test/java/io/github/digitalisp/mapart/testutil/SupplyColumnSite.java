package io.github.digitalisp.mapart.testutil;

import io.github.digitalisp.mapart.model.Pos;

import java.util.List;

import static io.github.digitalisp.mapart.testutil.ContainerSim.GLASS;
import static io.github.digitalisp.mapart.testutil.ContainerSim.STONE;
import static io.github.digitalisp.mapart.testutil.ContainerSim.chest;
import static io.github.digitalisp.mapart.testutil.ContainerSim.wallSign;

/**
 * The SHAPE of the gate-B food-chest failure, around a synthetic origin (never the base's coordinates): a column of
 * single chests facing east with a wall sign on each east face, stone beside them on the north, open to the south at
 * the target's height; a walkway east of it; and behind the column, on the west, a walled-in pit with a glass floor.
 * From the pit the target's west face centre is close and in sight, and the pit is nearer in a straight line to a bot
 * standing north-east on the walkway than any walkway cell that sees the south face; but nothing walks into the pit
 * (walls 4 high, the column too: a drop of 4).
 */
public final class SupplyColumnSite {
    private SupplyColumnSite() { }

    /** The target chest (third of four, at {@code y + 3}) of the column at (x, y + 1 .. y + 4, z); floor at {@code y}. */
    public static Pos build(ContainerSim s, int x, int y, int z) {
        // walkway east of the column and a strip south of it
        s.fill(x + 1, y, z - 8, x + 9, y, z + 8, STONE);
        s.fill(x - 3, y, z + 2, x, y, z + 8, STONE);
        for (int cy = y + 1; cy <= y + 4; cy++) {
            s.set(x, cy, z, chest("east", "single"));
            s.set(x + 1, cy, z, wallSign("east"));
            s.set(x, cy, z - 1, STONE); // north neighbour: the north face hidden
            if (cy != y + 3) s.set(x, cy, z + 1, STONE); // south neighbour, open beside the target
        }
        // the pit: glass floor, stone walls 4 high on its west, north and south
        s.fill(x - 1, y, z - 1, x - 1, y, z + 1, GLASS);
        s.fill(x - 2, y + 1, z - 2, x - 2, y + 4, z + 2, STONE);
        s.fill(x - 1, y + 1, z - 2, x, y + 4, z - 2, STONE);
        s.fill(x - 1, y + 1, z + 2, x, y + 4, z + 2, STONE);
        Pos target = new Pos(x, y + 3, z);
        s.targets = List.of(target);
        return target;
    }

    /** Feet position of the bot coming from the north on the walkway (the pit is nearer than the south side). */
    public static double[] botNorthEast(int x, int y, int z) {
        return new double[]{x + 1.5, y + 1, z - 3.5};
    }

    /** The pit's feet cells. */
    public static List<Pos> pit(int x, int y, int z) {
        return List.of(new Pos(x - 1, y + 1, z - 1), new Pos(x - 1, y + 1, z), new Pos(x - 1, y + 1, z + 1));
    }
}
