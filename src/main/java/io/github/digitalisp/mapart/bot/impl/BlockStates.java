package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.pathfinder.Ternary;
import com.zenith.feature.pathfinder.movement.MovementHelper;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.block.BlockStatePropertyDefinition;
import com.zenith.mc.block.BlockStatePropertyRegistry;
import com.zenith.mc.block.CollisionBox;
import com.zenith.mc.block.properties.api.Property;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.BlockSpec;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static com.zenith.Globals.BLOCK_DATA;

/**
 * Conversion between ZenithProxy block state ids (1.21.4 registry) and {@link BlockSpec}. Read-only registries,
 * memoised, thread-safe.
 */
public final class BlockStates {
    private BlockStates() { }

    private static final Map<Integer, BlockSpec> SPEC_BY_STATE = new ConcurrentHashMap<>();
    private static final Map<String, Integer> STATE_BY_SPEC = new ConcurrentHashMap<>();

    public static @Nullable Block block(String id) {
        return id == null ? null : BlockRegistry.REGISTRY.get(ItemIds.path(id));
    }

    /** Full state with all properties; {@link BlockSpec#AIR} for unknown ids. */
    public static BlockSpec spec(int stateId) {
        BlockSpec cached = SPEC_BY_STATE.get(stateId);
        if (cached != null) return cached;
        Block b = BLOCK_DATA.getBlockDataFromBlockStateId(stateId);
        if (b == null) return BlockSpec.AIR;
        return specOf(b, stateId);
    }

    /** State {@code stateId} of block {@code b} (must lie in b's state range). Uses only the static registries. */
    static BlockSpec specOf(Block b, int stateId) {
        BlockSpec cached = SPEC_BY_STATE.get(stateId);
        if (cached != null) return cached;
        TreeMap<String, String> props = new TreeMap<>();
        BlockStatePropertyDefinition def = BlockStatePropertyRegistry.STATES.get(b.id());
        if (def != null) {
            int offset = stateId - b.minStateId();
            for (var e : def.getPropertiesByName().entrySet()) {
                String v = valueName(def, e.getValue(), offset);
                if (v != null) props.put(e.getKey(), v);
            }
        }
        BlockSpec spec = new BlockSpec("minecraft:" + b.name(), props);
        SPEC_BY_STATE.put(stateId, spec);
        return spec;
    }

    /**
     * Lowest state id of the block whose properties match every property given in {@code spec} (properties the
     * spec omits may take any value), or -1 if the block or a property value is unknown.
     */
    public static int stateId(BlockSpec spec) {
        String key = spec.toString();
        Integer cached = STATE_BY_SPEC.get(key);
        if (cached != null) return cached;
        int found = -1;
        Block b = block(spec.id());
        if (b != null) {
            for (int id = b.minStateId(); id <= b.maxStateId(); id++) {
                BlockSpec s = specOf(b, id);
                boolean ok = true;
                for (var e : spec.props().entrySet()) {
                    if (!e.getValue().equals(s.props().get(e.getKey()))) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    found = id;
                    break;
                }
            }
        }
        STATE_BY_SPEC.put(key, found);
        return found;
    }

    /** Highest collision box top (0 = no collision, 1 = full height) of the state, or 1 if unknown. */
    public static double collisionTop(BlockSpec spec) {
        int id = stateId(spec);
        if (id < 0) return 1.0;
        List<CollisionBox> boxes = BLOCK_DATA.getCollisionBoxesFromBlockStateId(id);
        double top = 0;
        for (CollisionBox cb : boxes) top = Math.max(top, cb.maxY());
        return top;
    }

    /**
     * {@link #collisionTop} when one of the state's collision boxes lies under the cell's horizontal centre (it holds a
     * box falling down the column), else 0: a ladder, a wall sign or an open trapdoor is a thin plate on one side, and
     * a bot in the middle of the cell falls past it. Unknown states: 1 (as {@link #collisionTop}). Live 2026-10-28 (soul
     * sand, the east item trench): a ladder 2 below the rim made the trench's cell look like a 1-block step down.
     */
    public static double floorTop(BlockSpec spec) {
        int id = stateId(spec);
        if (id < 0) return 1.0;
        List<CollisionBox> boxes = BLOCK_DATA.getCollisionBoxesFromBlockStateId(id);
        boolean centre = false;
        double top = 0;
        for (CollisionBox cb : boxes) {
            top = Math.max(top, cb.maxY());
            if (cb.minX() <= 0.5 && cb.maxX() >= 0.5 && cb.minZ() <= 0.5 && cb.maxZ() >= 0.5) centre = true;
        }
        return centre ? top : 0;
    }

    /**
     * A block a side-face LOOK click may use ({@link TopFaceRules#sideFacePlaceable}): a full cube in collision and
     * outline (its side face is the whole face; the raycast hits the outline), not replaceable (the click would place
     * into it), no block entity (chests, pots, jukeboxes act on the click) and none of {@link TopFaceRules#sideClickable}'s
     * usable blocks.
     */
    public static boolean sideClickable(int stateId) {
        Block b = BLOCK_DATA.getBlockDataFromBlockStateId(stateId);
        if (b == null || b.isAir() || b.replaceable() || b.blockEntityType() != null) return false;
        if (!TopFaceRules.sideClickable(b.name())) return false;
        return fullCube(BLOCK_DATA.getCollisionBoxesFromBlockStateId(stateId))
            && fullCube(BLOCK_DATA.getInteractionBoxesFromBlockStateId(stateId));
    }

    /**
     * Baritone (and the bot) can stand on top of the state: a full cube in collision, as ZenithProxy's pathfinder wants
     * (MovementHelper.isBlockNormalCube; pointed dripstone, a thin column, and decorated pots are not), or unknown.
     */
    public static boolean walkOnTop(BlockSpec spec) {
        int id = stateId(spec);
        if (id < 0) return true;
        Block b = BLOCK_DATA.getBlockDataFromBlockStateId(id);
        if (b == null || b.isAir() || b.name().contains("pointed_dripstone")) return false;
        return BLOCK_DATA.isShapeFullBlock(id);
    }

    /**
     * ZenithProxy's pathfinder walks on top of the state ({@code MovementHelper.canWalkOnBlockState}: normal cubes but
     * magma and honey, soul sand, farmland, chests, glass, slabs, stairs, closed trapdoors, ladders; not decorated pots
     * or hoppers, whose collision reaches the top all the same), or unknown. Water and lava count: the pathfinder
     * decides those by position.
     */
    public static boolean pathfinderWalksOn(BlockSpec spec) {
        int id = stateId(spec);
        if (id < 0) return true;
        return MovementHelper.canWalkOnBlockState(id) != Ternary.NO;
    }

    private static boolean fullCube(List<CollisionBox> boxes) {
        if (boxes.size() != 1) return false;
        CollisionBox c = boxes.getFirst();
        return c.minX() <= 0 && c.minY() <= 0 && c.minZ() <= 0 && c.maxX() >= 1 && c.maxY() >= 1 && c.maxZ() >= 1;
    }

    /** True for states that are not air and not replaceable (a placement there would fail). */
    public static boolean blocksPlacement(int stateId) {
        Block b = BLOCK_DATA.getBlockDataFromBlockStateId(stateId);
        return b != null && !b.isAir() && !b.replaceable();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static @Nullable String valueName(BlockStatePropertyDefinition def, Property property, int offset) {
        Comparable value = def.getValue(property, offset);
        return value == null ? null : property.getName(value);
    }
}
