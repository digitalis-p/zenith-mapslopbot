package io.github.digitalisp.mapart.bot.impl;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.actions.InventoryAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Cartography table lock: map → slot 0, a glass pane → slot 1, wait, take the result from slot 2 into the map's old
 * slot. Locking gives the map a new id, reported from the filled map that was not in the inventory before. With
 * {@code build.mapSwapsWithoutCursor} (default) the map and the pane move by hotbar-key trades ({@link KeySwaps#lock}:
 * 3 to 7 clicks, never more than before), so the map never rides the cursor; off: plain pick/place clicks, ONE pane
 * into slot 1 (pick up the stack, right-click one in, put the rest back), 7 clicks. Either way the predictions are
 * right (no shift-click) so the cache follows every step. The map in slot 0 is dropped by the server if the connection
 * ends before the result is out (vanilla {@code clearContainer}): the reconnect looks for it ({@link DropWatch}).
 */
final class LockMapTask extends ContainerTask {
    static final String PANE = "minecraft:glass_pane";

    private final int mapId;
    private final Consumer<Integer> lockedId;
    private Set<Integer> idsBefore = Set.of();

    LockMapTask(ZenithBotActions ctx, Pos table, int mapId, Consumer<Integer> lockedId) {
        super(ctx, table, ctx.containerTimeout(table));
        this.mapId = mapId;
        this.lockedId = lockedId;
    }

    @Override
    public String name() {
        return "lockMap " + mapId + " at " + pos;
    }

    /** The base's cartography table is a station ({@code station:cartography}), not a chest. */
    @Override
    protected String claimKey() {
        return io.github.digitalisp.mapart.bot.MenuGate.CARTOGRAPHY;
    }

    @Override
    protected @Nullable TaskStatus preflight() {
        if (ctx.maps().isLocked(mapId)) {
            lockedId.accept(mapId);
            return TaskStatus.SUCCESS;
        }
        if (Inv.count(Inv.isMap(mapId)) == 0) return fail("filled map " + mapId + " not in inventory");
        if (Inv.count(Inv.is(PANE)) == 0) return fail("no glass pane in inventory");
        return null;
    }

    @Override
    protected @Nullable String checkBlock() {
        String id = ctx.world().block(pos).id();
        return id.equals("minecraft:cartography_table") ? null : "no cartography table at " + pos + " (found " + id + ")";
    }

    @Override
    protected @Nullable String checkContainer(Container c) {
        return c.getType() == ContainerType.CARTOGRAPHY ? null : "opened " + typeName(c.getType()) + ", expected cartography";
    }

    @Override
    protected void onOpened(Container c) {
        idsBefore = new HashSet<>(Inv.mapIds());
    }

    @Override
    protected List<InventoryAction> actions(Container c, int top) {
        int cid = c.getContainerId();
        int map = Inv.find(Inv.isMap(mapId));
        int pane = Inv.find(Inv.is(PANE));
        if (map < 0 || map == ItemIds.OFFHAND || pane < 0 || pane == ItemIds.OFFHAND) {
            problem = "map or glass pane not in hotbar/main inventory";
            return List.of();
        }
        int panes = Inv.stack(pane).getAmount();
        return menuActions(cid, KeySwaps.lock(cfg.build.mapSwapsWithoutCursor, map, pane, panes, top,
            ZenithTask::hotbarEmpty, Inv.heldSlot()), null);
    }

    @Override
    protected int minActionDelay() {
        return 3;
    }

    @Override
    protected TaskStatus finish() {
        for (Integer id : Inv.mapIds()) {
            if (!idsBefore.contains(id) && id != mapId) {
                lockedId.accept(id);
                return TaskStatus.SUCCESS;
            }
        }
        if (ctx.maps().isLocked(mapId) && Inv.count(Inv.isMap(mapId)) > 0) {
            lockedId.accept(mapId);
            return TaskStatus.SUCCESS;
        }
        return fail("map " + mapId + " was not locked" + (problem != null ? " (" + problem + ")" : ""));
    }
}
