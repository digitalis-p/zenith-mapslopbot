package io.github.digitalisp.mapart.bot.impl;

import com.zenith.cache.data.chunk.Chunk;
import com.zenith.cache.data.entity.Entity;
import com.zenith.cache.data.entity.EntityCache;
import com.zenith.feature.player.World;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;

/** {@link WorldView} over ZenithProxy's chunk cache and the simulated bot player. Client tick thread. */
public final class ZenithWorldView implements WorldView {
    @Override
    public boolean isLoaded(Pos pos) {
        return World.isChunkLoadedBlockPos(pos.x(), pos.z());
    }

    @Override
    public BlockSpec block(Pos pos) {
        if (!isLoaded(pos)) return BlockSpec.AIR;
        return BlockStates.spec(World.getBlockStateId(pos.x(), pos.y(), pos.z()));
    }

    /** Raw state id (0 = air or unloaded). */
    public int stateId(Pos pos) {
        return World.getBlockStateId(pos.x(), pos.y(), pos.z());
    }

    @Override
    public boolean hasFluid(Pos pos) {
        if (!isLoaded(pos)) return false;
        int state = World.getBlockStateId(pos.x(), pos.y(), pos.z());
        if (World.getFluidState(state) != null) return true;
        return "true".equals(BlockStates.spec(state).props().get("waterlogged"));
    }

    /**
     * The top of the column's highest chunk section holding any non-air block (the section block count the server
     * sends and the chunk cache keeps up to date); below the chunk when every section is empty, and
     * {@link Integer#MIN_VALUE} when the chunk is not loaded.
     */
    @Override
    public int topY(Pos column) {
        Chunk chunk = World.getChunk(column.x() >> 4, column.z() >> 4);
        if (chunk == null) return Integer.MIN_VALUE;
        ChunkSection[] sections = chunk.getSections();
        for (int i = sections.length - 1; i >= 0; i--) {
            ChunkSection s = sections[i];
            if (s != null && !s.isBlockCountEmpty()) return chunk.minY() + i * 16 + 15;
        }
        return chunk.minY() - 1;
    }

    /**
     * Counts the {@code ITEM} entities in ZenithProxy's entity cache (kept from the server's add/move/remove entity
     * packets; a hopper minecart picking an item up removes it). Empty when a chunk of the box is not loaded.
     */
    @Override
    public OptionalInt itemEntities(Pos min, Pos max) {
        int x0 = Math.min(min.x(), max.x()), x1 = Math.max(min.x(), max.x());
        int y0 = Math.min(min.y(), max.y()), y1 = Math.max(min.y(), max.y());
        int z0 = Math.min(min.z(), max.z()), z1 = Math.max(min.z(), max.z());
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++)
                if (!World.isChunkLoadedChunkPos(cx, cz)) return OptionalInt.empty();
        EntityCache cache = CACHE.getEntityCache();
        if (cache == null) return OptionalInt.empty();
        int n = 0;
        for (Entity e : cache.getEntities().values()) {
            if (e.getEntityType() != EntityType.ITEM || e.isRemoved()) continue;
            int x = (int) Math.floor(e.getX()), y = (int) Math.floor(e.getY()), z = (int) Math.floor(e.getZ());
            if (x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1) n++;
        }
        return OptionalInt.of(n);
    }

    /**
     * The {@code ITEM} entities of the entity cache within {@code radius} of {@code at}, with the stack from their metadata
     * (the item entity's DATA_ITEM, an {@code ItemStack} value; null when no metadata value is a stack). The server sends
     * item entities within its entity tracking range of the player.
     */
    @Override
    public java.util.List<ItemInFlight> itemsNear(Pos at, double radius) {
        EntityCache cache = CACHE.getEntityCache();
        if (cache == null) return java.util.List.of();
        java.util.List<ItemInFlight> out = new java.util.ArrayList<>();
        for (Entity e : cache.getEntities().values()) {
            if (e.getEntityType() != EntityType.ITEM || e.isRemoved()) continue;
            if (Math.abs(e.getX() - (at.x() + 0.5)) > radius || Math.abs(e.getZ() - (at.z() + 0.5)) > radius
                || Math.abs(e.getY() - at.y()) > radius) continue;
            String item = null;
            int count = 0;
            var md = e.getMetadata();
            if (md != null) {
                for (var m : md.values()) {
                    if (m != null && m.getValue() instanceof org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack st) {
                        item = Inv.id(st);
                        count = st.getAmount();
                        break;
                    }
                }
            }
            out.add(new ItemInFlight(e.getEntityId(), item, count, e.getX(), e.getY(), e.getZ()));
        }
        return out;
    }

    @Override
    public java.util.List<Mob> mobs(java.util.Set<String> types) {
        EntityCache cache = CACHE.getEntityCache();
        if (cache == null || types.isEmpty()) return java.util.List.of();
        java.util.List<Mob> out = new java.util.ArrayList<>();
        for (Entity e : cache.getEntities().values()) {
            if (e.isRemoved() || !(e instanceof com.zenith.cache.data.entity.EntityLiving l) || !l.isAlive()) continue;
            if (e == CACHE.getPlayerCache().getThePlayer()) continue;
            String type = e.getEntityType().name().toLowerCase(java.util.Locale.ROOT);
            if (!types.contains(type)) continue;
            boolean player = e instanceof com.zenith.cache.data.entity.EntityPlayer;
            out.add(new Mob(type, e.getX(), e.getY(), e.getZ(), e.getEntityId(), type.equals("creeper") && fusing(e),
                l.getHealth() == null ? Float.NaN : l.getHealth(), player ? e.getUuid() : null,
                player ? playerName(e.getUuid()) : null));
        }
        return out;
    }

    /**
     * A player's name from the tab list (the entity cache knows only its UUID), or null when it is not listed; for
     * the teammate check. Client tick thread or client event loop.
     */
    static @org.jspecify.annotations.Nullable String playerName(java.util.@org.jspecify.annotations.Nullable UUID uuid) {
        if (uuid == null) return null;
        try {
            var tab = CACHE.getTabListCache();
            return tab == null ? null : tab.get(uuid).map(org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry::getName)
                .orElse(null);
        } catch (RuntimeException ex) {
            return null; // the tab list changing under us: the UUID alone decides
        }
    }

    /** Creeper metadata 16 (swell direction, 1 = fusing) or 18 (ignited): the creeper is about to explode. */
    private static boolean fusing(Entity e) {
        var swell = e.getMetadata().get(16);
        if (swell != null && swell.getValue() instanceof Number n && n.intValue() > 0) return true;
        var ignited = e.getMetadata().get(18);
        return ignited != null && Boolean.TRUE.equals(ignited.getValue());
    }

    @Override
    public java.util.List<Thing> entities(java.util.Set<String> types) {
        EntityCache cache = CACHE.getEntityCache();
        if (cache == null || types.isEmpty()) return java.util.List.of();
        java.util.List<Thing> out = new java.util.ArrayList<>();
        for (Entity e : cache.getEntities().values()) {
            if (e.isRemoved() || e == CACHE.getPlayerCache().getThePlayer()) continue;
            String type = e.getEntityType().name().toLowerCase(java.util.Locale.ROOT);
            if (types.contains(type)) out.add(thing(e));
        }
        return out;
    }

    /** {@code e} as a {@link Thing}; a player with its identity (UUID, tab-list name) for the teammate check. */
    static Thing thing(Entity e) {
        String type = e.getEntityType().name().toLowerCase(java.util.Locale.ROOT);
        if (!(e instanceof com.zenith.cache.data.entity.EntityPlayer)) return new Thing(e.getEntityId(), type, e.getX(),
            e.getY(), e.getZ());
        return new Thing(e.getEntityId(), type, e.getX(), e.getY(), e.getZ(), e.getUuid(), playerName(e.getUuid()));
    }

    @Override
    public Thing vehicle() {
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player == null || !player.isInVehicle()) return null;
        EntityCache cache = CACHE.getEntityCache();
        Entity v = cache == null ? null : cache.get(player.getVehicleId());
        if (v == null || v.isRemoved()) return null;
        return new Thing(v.getEntityId(), v.getEntityType().name().toLowerCase(java.util.Locale.ROOT), v.getX(), v.getY(),
            v.getZ());
    }

    @Override
    public double playerX() {
        return BOT.getX();
    }

    @Override
    public double playerY() {
        return BOT.getY();
    }

    @Override
    public double playerZ() {
        return BOT.getZ();
    }

    @Override
    public Pos playerBlock() {
        return new Pos((int) Math.floor(BOT.getX()), (int) Math.floor(BOT.getY() + 1e-6), (int) Math.floor(BOT.getZ()));
    }

    @Override
    public int viewDistance() {
        int v = CACHE.getChunkCache().getServerViewDistance();
        return Math.max(0, v);
    }

    /**
     * The signs among the cached chunks' block entities within the server's view distance of the player (at least 2,
     * at most 32 chunks): a chunk's block entity stores x and z within the chunk, y as is. Copies each chunk's list
     * first (the network thread may change it); a chunk that does not read is skipped.
     */
    @Override
    public List<Sign> signs() {
        return scanSigns(null);
    }

    @Override
    public String blockEntityCensus() {
        int[] n = new int[3];
        scanSigns(n);
        return n[0] + " chunks, " + n[1] + " block entities, " + n[2] + " signs";
    }

    /** {@link #signs}; {@code count} (when given) gets the chunks, block entities and signs looked at. */
    private List<Sign> scanSigns(int @org.jspecify.annotations.Nullable [] count) {
        List<Sign> out = new ArrayList<>();
        var cache = CACHE.getChunkCache();
        Pos me = playerBlock();
        int r = Math.max(2, Math.min(32, viewDistance()));
        int cx0 = me.x() >> 4, cz0 = me.z() >> 4;
        for (int cx = cx0 - r; cx <= cx0 + r; cx++) {
            for (int cz = cz0 - r; cz <= cz0 + r; cz++) {
                BlockEntityInfo[] all;
                try {
                    Chunk ch = cache.get(cx, cz);
                    if (ch == null || ch.getBlockEntities() == null) continue;
                    all = ch.getBlockEntities().toArray(new BlockEntityInfo[0]);
                } catch (RuntimeException e) {
                    continue;
                }
                if (count != null) {
                    count[0]++;
                    count[1] += all.length;
                }
                for (BlockEntityInfo be : all) {
                    if (be == null || (be.getType() != BlockEntityType.SIGN && be.getType() != BlockEntityType.HANGING_SIGN)) continue;
                    if (count != null) count[2]++;
                    Pos p = new Pos(cx * 16 + (be.getX() & 15), be.getY(), cz * 16 + (be.getZ() & 15));
                    out.add(new Sign(p, block(p), SignText.of(be.getNbt())));
                }
            }
        }
        return out;
    }

    @Override
    public int simulationDistance() {
        int v = CACHE.getChunkCache().getServerSimulationDistance();
        return Math.max(0, v);
    }
}
