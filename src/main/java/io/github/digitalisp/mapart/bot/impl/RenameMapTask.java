package io.github.digitalisp.mapart.bot.impl;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.actions.InventoryAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.Set;

import static com.zenith.Globals.CACHE;

/**
 * Anvil rename: map → slot 0, rename packet, wait, take the result from slot 2 back into the map's slot. Needs at
 * least one XP level. Fails with a clear reason when the anvil block is missing (or broke during the rename). With
 * {@code build.mapSwapsWithoutCursor} (default) the map moves by hotbar-key trades ({@link KeySwaps#rename}: 2 clicks
 * from the hotbar, 4 from the main inventory), so it never rides the cursor; off: 4 plain pick/place clicks. The map in
 * slot 0 is dropped by the server if the connection ends before the result is out: the reconnect looks for it
 * ({@link DropWatch}).
 */
final class RenameMapTask extends ContainerTask {
    static final int MAX_NAME = 50;
    private static final Set<String> ANVILS = Set.of("minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil");

    private final int mapId;
    private final String newName;

    RenameMapTask(ZenithBotActions ctx, Pos anvil, int mapId, String name) {
        super(ctx, anvil, ctx.containerTimeout(anvil));
        this.mapId = mapId;
        String n = name == null ? "" : name.strip();
        this.newName = n.length() > MAX_NAME ? n.substring(0, MAX_NAME) : n;
    }

    @Override
    public String name() {
        return "renameMap " + mapId + " to \"" + newName + "\"";
    }

    /** The base's anvil is a station ({@code station:anvil}), not a chest. */
    @Override
    protected String claimKey() {
        return io.github.digitalisp.mapart.bot.MenuGate.ANVIL;
    }

    @Override
    protected @Nullable TaskStatus preflight() {
        if (newName.isEmpty()) return fail("empty name");
        int slot = Inv.find(Inv.isMap(mapId));
        if (slot < 0) return fail("filled map " + mapId + " not in inventory");
        if (newName.equals(Inv.customName(Inv.stack(slot)))) return TaskStatus.SUCCESS;
        int level = CACHE.getPlayerCache().getThePlayer().getLevel();
        if (level < 1) return fail("need at least 1 XP level to rename (have " + level + ")");
        return null;
    }

    @Override
    protected @Nullable String checkBlock() {
        String id = ctx.world().block(pos).id();
        return ANVILS.contains(id) ? null : "anvil missing at " + pos + " (found " + id + ")";
    }

    @Override
    protected @Nullable String checkContainer(Container c) {
        return c.getType() == ContainerType.ANVIL ? null : "opened " + typeName(c.getType()) + ", expected anvil";
    }

    @Override
    protected List<InventoryAction> actions(Container c, int top) {
        int cid = c.getContainerId();
        int map = Inv.find(Inv.isMap(mapId));
        if (map < 0 || map == ItemIds.OFFHAND) {
            problem = "map not in hotbar/main inventory";
            return List.of();
        }
        return menuActions(cid, KeySwaps.rename(cfg.build.mapSwapsWithoutCursor, map, top, ZenithTask::hotbarEmpty,
            Inv.heldSlot()), newName);
    }

    @Override
    protected int minActionDelay() {
        return 3;
    }

    @Override
    protected TaskStatus finish() {
        int slot = Inv.find(Inv.isMap(mapId));
        if (slot >= 0 && newName.equals(Inv.customName(Inv.stack(slot)))) return TaskStatus.SUCCESS;
        String id = ctx.world().block(pos).id();
        if (!ANVILS.contains(id)) return fail("anvil at " + pos + " is gone (now " + id + "); map not renamed");
        if (slot < 0) return fail("map " + mapId + " missing after rename");
        return fail("rename of map " + mapId + " failed" + (problem != null ? " (" + problem + ")" : ""));
    }
}
