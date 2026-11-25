package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Finds material containers (chest, trapped chest, barrel) in a configured region by reading the
 * chunk cache, a bounded number of positions per tick. A double chest is reported once, at the
 * canonical half (smaller x, then z) so its contents are never counted twice. Role chests (both
 * halves) are excluded.
 */
final class ContainerDiscovery extends BotTask {
    static final Set<String> CONTAINER_IDS = Set.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel");
    private static final int BUDGET_PER_TICK = 32_768;

    record Result(List<Pos> containers, int unloaded) { }

    private final WorldView world;
    private final int minX, minY, minZ, maxX, maxY, maxZ;
    private final Set<Pos> excluded;
    private final Consumer<Result> result;
    private final Set<Pos> found = new LinkedHashSet<>();
    private int unloaded;
    private int x, y, z;

    ContainerDiscovery(WorldView world, MapArtConfig.RegionCfg region, Collection<Pos> roleContainers, Consumer<Result> result) {
        this.world = world;
        this.minX = Math.min(region.min.x(), region.max.x());
        this.minY = Math.min(region.min.y(), region.max.y());
        this.minZ = Math.min(region.min.z(), region.max.z());
        this.maxX = Math.max(region.min.x(), region.max.x());
        this.maxY = Math.max(region.min.y(), region.max.y());
        this.maxZ = Math.max(region.min.z(), region.max.z());
        this.excluded = expandRoles(world, roleContainers);
        this.result = result;
        this.x = minX;
        this.y = minY;
        this.z = minZ;
    }

    @Override
    protected TaskStatus onTick() {
        for (int n = 0; n < BUDGET_PER_TICK; n++) {
            if (y > maxY) {
                result.accept(new Result(new ArrayList<>(found), unloaded));
                return TaskStatus.SUCCESS;
            }
            visit(new Pos(x, y, z));
            if (++x > maxX) {
                x = minX;
                if (++z > maxZ) {
                    z = minZ;
                    y++;
                }
            }
        }
        return TaskStatus.RUNNING;
    }

    private void visit(Pos p) {
        if (!world.isLoaded(p)) {
            unloaded++;
            return;
        }
        BlockSpec b = world.block(p);
        if (!CONTAINER_IDS.contains(b.id())) return;
        Pos canon = canonical(world, p, b);
        if (excluded.contains(p) || excluded.contains(canon)) return;
        found.add(canon);
    }

    @Override
    public String name() {
        return "discover containers";
    }

    static Set<Pos> expandRoles(WorldView world, Collection<Pos> roles) {
        Set<Pos> out = new LinkedHashSet<>();
        for (Pos p : roles) {
            if (p == null) continue;
            out.add(p);
            if (world.isLoaded(p)) {
                Pos partner = partner(world, p, world.block(p));
                if (partner != null) out.add(partner);
            }
        }
        return out;
    }

    /** The half of a double chest with the smaller x (then z); the position itself otherwise. */
    static Pos canonical(WorldView world, Pos p, BlockSpec b) {
        Pos other = partner(world, p, b);
        if (other == null) return p;
        if (other.x() < p.x() || (other.x() == p.x() && other.z() < p.z())) return other;
        return p;
    }

    /**
     * The other half of a double chest, by vanilla's {@code ChestBlock.getConnectedDirection}: a LEFT half's
     * partner is clockwise of {@code facing}, a RIGHT half's counter-clockwise. The neighbour must hold the
     * same block with the same facing and the opposite type, otherwise the chest is treated as single.
     * (Looking both ways would mis-pair a row of two double chests.)
     */
    static Pos partner(WorldView world, Pos p, BlockSpec b) {
        String type = b.props().get("type");
        String facing = b.props().get("facing");
        if (type == null || facing == null || type.equals("single")) return null;
        if (!b.id().equals("minecraft:chest") && !b.id().equals("minecraft:trapped_chest")) return null;
        boolean left = type.equals("left");
        int[] d = switch (facing) {
            case "north" -> left ? new int[]{1, 0} : new int[]{-1, 0};
            case "east" -> left ? new int[]{0, 1} : new int[]{0, -1};
            case "south" -> left ? new int[]{-1, 0} : new int[]{1, 0};
            case "west" -> left ? new int[]{0, -1} : new int[]{0, 1};
            default -> null;
        };
        if (d == null) return null;
        Pos q = p.offset(d[0], 0, d[1]);
        if (!world.isLoaded(q)) return null;
        BlockSpec o = world.block(q);
        String opposite = left ? "right" : "left";
        if (o.id().equals(b.id()) && facing.equals(o.props().get("facing")) && opposite.equals(o.props().get("type")))
            return q;
        return null;
    }
}
