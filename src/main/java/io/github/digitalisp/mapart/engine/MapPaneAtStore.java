package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.bot.InventoryView;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.ToIntFunction;

import static io.github.digitalisp.mapart.engine.EngineTuning.MAP;
import static io.github.digitalisp.mapart.engine.EngineTuning.PANE;

/**
 * {@code build.mapPaneAtStore}: the next tile's empty map and glass pane are
 * taken at STORE, one each, while the bot stands by their chests, so the next tile's last load makes no trip to them
 * (live soul sand: the station is 244 blocks east of the tile). The pure rules; the engine runs the withdrawals.
 */
final class MapPaneAtStore {
    private MapPaneAtStore() {
    }

    /**
     * STORE, after the working items went back: starts the withdrawal of the first missing item (the map, then the
     * pane) from the next of its chests ({@code attempt} counts the tries per item, as {@code Ctx.attempt}). True while
     * a withdrawal runs; false when both are carried, every chest of a missing one was tried (the last load fetches it,
     * as without the switch), or the switch is off.
     */
    static boolean fetch(boolean on, InventoryView inv, MapArtConfig.StorageCfg st, ToIntFunction<String> attempt,
                         BiConsumer<Pos, String> withdraw) {
        if (!on) return false;
        return next(inv, MAP, st.mapChests, attempt, withdraw) || next(inv, PANE, st.paneChests, attempt, withdraw);
    }

    private static boolean next(InventoryView inv, String item, List<Pos> chests, ToIntFunction<String> attempt,
                                BiConsumer<Pos, String> withdraw) {
        if (inv.count(item) >= 1) return false;
        int a = attempt.applyAsInt("next-tile " + item);
        if (a >= chests.size()) return false;
        withdraw.accept(chests.get(a), item);
        return true;
    }

    /** How many of {@code item} STORE's tidy keeps: one map and one pane with the switch on, else none. */
    static int keep(boolean on, String item) {
        return on && (item.equals(MAP) || item.equals(PANE)) ? 1 : 0;
    }

    /**
     * Slots the carried map and pane hold with the switch on (0 off): RESTOCK's XP tidy leaves them out of the kept
     * slots it compares with {@code build.reservedSlots}, or the bottles would go back to their chest (live soul sand:
     * at the station) at the next tile's first restock.
     */
    static int kitSlots(boolean on, InventoryView inv) {
        if (!on) return 0;
        var used = inv.slotsUsed();
        return Math.max(0, used.getOrDefault(MAP, 0)) + Math.max(0, used.getOrDefault(PANE, 0));
    }
}
