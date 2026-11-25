package io.github.digitalisp.mapart.bot.impl;

import com.zenith.mc.block.Block;
import com.zenith.mc.block.CollisionBox;
import io.github.digitalisp.mapart.bot.FallRescue;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.config.SiteRules;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.zenith.Globals.BLOCK_DATA;

/**
 * {@link FallClimb.Grid} on ZenithProxy's cached world. A block of the rescue's own
 * goes only where {@link #placeAllowed}: no protected region ({@code site.protectedRegions}), no art-layer cell of a
 * platform, no cell of a no-go zone except the zones the bot's feet stood in when the rescue started (the east channel
 * of the soul sand platform is one: only the rescue's own cells there). Exits: {@link FallExit#standable} (dry, no
 * no-go zone at the feet or the head) at the floor's level or above.
 */
final class FallClimbGrid implements FallClimb.Grid {
    private final ZenithWorldView w;
    private final MapArtConfig cfg;
    private final NoGoZones zones;
    private final EdgeDrop.Cells cells;
    private final List<NoGoZones.Zone> startZones = new ArrayList<>();
    private final int floorY;
    /** Cells the rescue gave up placing into (a pillar jump that never got high enough). */
    private final Set<Pos> banned;

    FallClimbGrid(ZenithWorldView w, MapArtConfig cfg, NoGoZones zones, Pos start, int floorY, Set<Pos> banned) {
        this.w = w;
        this.cfg = cfg;
        this.zones = zones;
        this.cells = EdgeApproach.cells(w, zones);
        this.floorY = floorY;
        this.banned = banned;
        for (NoGoZones.Zone z : zones.zones()) if (z.contains(start.x(), start.y(), start.z())) startZones.add(z);
    }

    @Override
    public boolean loaded(Pos p) {
        return w.isLoaded(p);
    }

    @Override
    public double top(Pos p) {
        BlockSpec b = w.block(p);
        return b == null || b.isAir() ? 0 : BlockStates.collisionTop(b);
    }

    @Override
    public double outline(Pos p) {
        int id = w.stateId(p);
        Block b = BLOCK_DATA.getBlockDataFromBlockStateId(id);
        if (b == null || b.isAir()) return 0;
        double top = 0;
        for (CollisionBox cb : BLOCK_DATA.getInteractionBoxesFromBlockStateId(id)) top = Math.max(top, cb.maxY());
        return top;
    }

    @Override
    public boolean fluid(Pos p) {
        return w.hasFluid(p);
    }

    @Override
    public boolean replaceable(Pos p) {
        Block b = BLOCK_DATA.getBlockDataFromBlockStateId(w.stateId(p));
        if (b == null) return false;
        if (b.isAir()) return true;
        String name = ItemIds.path(b.name());
        return name.equals("water") || (b.replaceable() && !w.hasFluid(p) && top(p) <= 0);
    }

    @Override
    public boolean clickable(Pos p) {
        return BlockStates.sideClickable(w.stateId(p));
    }

    @Override
    public boolean placeAllowed(Pos p) {
        if (banned.contains(p)) return false;
        // never into a fluid's source: the block broken again leaves air there, and the channel below it runs dry (local
        // run 2026-10-23: a stair into the west channel's south end, the source of its last segment, dried 8 cells)
        if (source(p)) return false;
        if (SiteRules.protectedRegionAt(cfg.site, p) != null) return false;
        for (MapArtConfig.PlatformCfg pl : cfg.site.platforms) {
            if (pl == null || !pl.enabled) continue;
            if (p.y() == pl.artY && pl.geometry().contains(p.x(), p.z())) return false;
        }
        for (NoGoZones.Zone z : zones.zones()) {
            if (z.contains(p.x(), p.y(), p.z()) && !startZones.contains(z)) return false;
        }
        return true;
    }

    /** A water or lava source block (level 0), or a waterlogged block. */
    private boolean source(Pos p) {
        int id = w.stateId(p);
        if (id == 0) return false;
        BlockSpec s = BlockStates.spec(id);
        if ("true".equals(s.props().get("waterlogged"))) return true;
        String name = ItemIds.path(s.id());
        return (name.equals("water") || name.equals("lava")) && "0".equals(s.props().get("level"));
    }

    @Override
    public boolean exit(Pos feet) {
        if (floorY != FallRescue.ANY_LEVEL && feet.y() < floorY) return false;
        return FallExit.standable(feet, cells);
    }
}
