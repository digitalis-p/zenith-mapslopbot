package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.InventoryView;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A fake 1.21.4 (Paper) server for {@link BreakSim}: the world, the player's hotbar and tool wear, the connection both
 * ways, and {@code ServerPlayerGameMode}'s dig state machine with its one "delayed destroy". Times are client ticks.
 *
 * <p><b>Dig state</b> (decompiled 1.21.4 + Paper patches): {@code gameTicks}, {@code isDestroyingBlock},
 * {@code destroyPos}, {@code destroyProgressStart}, {@code hasDelayedDestroy}, {@code delayedDestroyPos},
 * {@code delayedTickStart}. Destroy progress per tick ({@link #progress}) = tool speed (1 for a non-tool or a block
 * outside the tool's kind; + efficiency² + 1 when above 1) / hardness / (30 if the item is correct for drops or the
 * block needs no tool, else 100), with the hotbar item selected <i>now</i>; on the ground, no haste.
 * <ul>
 *   <li>Reach first: eye to the block's box squared below (4.5 + 1)², else ignored (still acked).</li>
 *   <li>START: {@code destroyProgressStart = gameTicks}; a non-air block with progress ≥ 1 is destroyed at once
 *       (insta mine, the dig state untouched). Else a block already being destroyed gets a block update
 *       ({@link #startWhileDestroying}) and the new one becomes {@code destroyPos}.</li>
 *   <li>STOP: only on {@code destroyPos} (else nothing, {@link #stopMismatch}). Progress {@code f × (gameTicks −
 *       destroyProgressStart + 1)} ≥ 0.7 destroys it; below, it becomes the delayed destroy if the slot is free, else
 *       nothing ({@link #delayedSlotBusy}). It does not check {@code isDestroyingBlock}.</li>
 *   <li>ABORT: {@code isDestroyingBlock = false}; on another position than {@code destroyPos} Paper moves
 *       {@code destroyPos} to (0,0,0) ({@link #abortMismatch}). Never touches the delayed destroy.</li>
 *   <li>After each: {@code ack = max(ack, seq)} (ABORT carries 0).</li>
 *   <li>The tick: {@code gameTicks} advances; the delayed destroy breaks once {@code f(held now) × (gameTicks −
 *       delayedTickStart + 1) ≥ 1}, no ack (or is dropped when its block is already air).</li>
 *   <li>A destroy turns the block to air; the main-hand item loses one use if the block's hardness is above 0. Each
 *       destroy records the item that broke it.</li>
 * </ul>
 * Per server tick: the packets that arrived (in order), the game-mode tick, then to the client: slot updates (wear),
 * block updates, the ack (highest sequence handled). A hotbar select changes the held item when it is handled
 * ({@link #selectsWhileDelayed} counts those handled while a delayed destroy is pending).
 *
 * <p><b>Connection</b>: uplink and downlink latency in ticks plus jitter that keeps the order (TCP).
 *
 * <p><b>Faults</b>: {@link #dropDelayedStops} drops that share of the STOPs that would enter the delayed slot (an
 * anti-cheat cancelling them: no ack, the block stays); {@link #neverComplete} positions whose delayed destroy fails
 * (a cancelled BlockBreakEvent: the slot is cleared, the block stays); {@link #tpsFactor} below 1 skips server ticks
 * (with {@link #paperClock} the mining clock still follows the wall clock); {@link #miningClock} below 1 is Paper's
 * wall-clock {@code gameTicks} seen from a client that ticks faster (the local 10x speed mode); {@link #grimCancel}
 * drops dig packets {@link GrimBreakModel} FastBreak flags.
 */
final class DigServer {
    /** A block type: hardness (−1 unbreakable), the tool kind that is faster on it, and whether drops need it. */
    record BlockType(String id, float hardness, @Nullable String tool, boolean requiresTool) {
        BlockSpec spec() {
            return BlockSpec.of(id);
        }
    }

    static final BlockType TERRACOTTA = new BlockType("minecraft:white_terracotta", 1.25f, "pickaxe", true);
    static final BlockType CONCRETE = new BlockType("minecraft:white_concrete", 1.8f, "pickaxe", true);
    static final BlockType WOOL = new BlockType("minecraft:white_wool", 0.8f, null, false);
    static final BlockType STONE = new BlockType("minecraft:stone", 1.5f, "pickaxe", true);
    static final BlockType ANDESITE = new BlockType("minecraft:andesite", 1.5f, "pickaxe", true);
    static final BlockType PLANKS = new BlockType("minecraft:oak_planks", 2.0f, "axe", false);
    static final BlockType STEM = new BlockType("minecraft:mushroom_stem", 0.2f, "axe", false);
    static final BlockType CLAY = new BlockType("minecraft:clay", 0.6f, "shovel", false);
    static final BlockType DIRT = new BlockType("minecraft:dirt", 0.5f, "shovel", false);
    static final BlockType BEDROCK = new BlockType("minecraft:bedrock", -1f, null, false);
    static final BlockType FLOOR = new BlockType("minecraft:smooth_stone", 2.0f, "pickaxe", true);

    /** An item stack in the hotbar: a tool (kind, tier speed, efficiency level, uses) or anything else (kind null). */
    static final class Item {
        final String id;
        final @Nullable String kind;
        final float tierSpeed;
        final int efficiency;
        /** Max uses; 0 = not damageable. */
        final int maxUses;
        final boolean mending;
        int damage;

        Item(String id, @Nullable String kind, float tierSpeed, int efficiency, int maxUses, boolean mending,
             int damage) {
            this.id = id;
            this.kind = kind;
            this.tierSpeed = tierSpeed;
            this.efficiency = efficiency;
            this.maxUses = maxUses;
            this.mending = mending;
            this.damage = damage;
        }

        static Item tool(String id, int efficiency, boolean mending, int usesLeft) {
            String kind = InventoryView.toolKind(id);
            boolean neth = id.contains("netherite");
            int max = neth ? 2031 : 1561;
            float speed = neth ? 9f : 8f;
            return new Item(id, kind, speed, efficiency, max, mending, max - Math.min(max, usesLeft));
        }

        static Item food() {
            return new Item("minecraft:cooked_beef", null, 1f, 0, 0, false, 0);
        }

        boolean damageable() {
            return maxUses > 0;
        }

        int remaining() {
            return maxUses - damage;
        }

        Item copy() {
            return new Item(id, kind, tierSpeed, efficiency, maxUses, mending, damage);
        }

        @Override
        public String toString() {
            return id + (damageable() ? " " + remaining() + "/" + maxUses : "");
        }
    }

    /** Vanilla {@code Player.getDestroySpeed}: on the ground, no haste. */
    static float destroySpeed(@Nullable Item item, BlockType b) {
        float s = 1f;
        if (item != null && item.kind != null && item.kind.equals(b.tool())) s = item.tierSpeed;
        if (s > 1f && item != null && item.efficiency > 0) s += item.efficiency * item.efficiency + 1;
        return s;
    }

    /** Vanilla {@code BlockState.getDestroyProgress}: destroy progress per tick with {@code item} in the main hand. */
    static float progress(@Nullable Item item, BlockType b) {
        if (b.hardness() < 0) return 0f;
        boolean correct = !b.requiresTool() || (item != null && b.tool() != null && b.tool().equals(item.kind));
        return destroySpeed(item, b) / b.hardness() / (correct ? 30f : 100f);
    }

    /**
     * Mining speed multiplier of the player now: Haste (1 + 0.2 × (amplifier + 1): 1.4 for Haste II), 1 without. The
     * fast nuke's tests change it to model a beacon's effect running out.
     */
    float speedFactor = 1f;

    /** {@link #progress} with the player's {@link #speedFactor} (Haste), as {@code ServerPlayerGameMode} computes it. */
    float prog(@Nullable Item item, BlockType b) {
        return progress(item, b) * speedFactor;
    }

    // ---------------------------------------------------------------------------------------------------------- wire

    enum Up { DIG, SWING, SELECT, MOVE }

    /** A serverbound packet. */
    record UpPacket(Up type, long sent, long arrival, DigClient.@Nullable Action action, @Nullable Pos pos, int face,
                    int seq, int slot, double x, double z) { }

    enum Down { BLOCK, SLOT, ACK }

    /** A clientbound packet: a block update (type null = air), a hotbar slot (item copy, null = gone), an ack. */
    record DownPacket(Down type, long arrival, @Nullable Pos pos, @Nullable BlockType block, int slot,
                      @Nullable Item item, int seq) { }

    /** One destroy: where, what, with which item (its id, or "hand"), its uses left after, delayed or not. */
    record Destroy(Pos pos, BlockType block, String item, @Nullable String kind, int usesLeftAfter, boolean delayed,
                   long tick) { }

    // ---------------------------------------------------------------------------------------------------- options

    int uplink = 2;
    int downlink = 2;
    /** Extra one-way delay, uniform 0..jitter ticks, order kept. */
    int jitter;
    /** Server ticks per client tick (≤ 1): below 1 some client ticks have no server tick. */
    double tpsFactor = 1.0;
    /**
     * Paper (true, default): {@code ServerPlayerGameMode.tick} sets {@code gameTicks} from the wall clock
     * ({@code getLagCompensationTick}), so a lagging server ({@link #tpsFactor}) still counts mining time in real
     * time. Vanilla (false): {@code gameTicks++} per server tick.
     */
    boolean paperClock = true;
    /**
     * Paper's {@code gameTicks} per client tick: 1 on 2b2t; 0.1 locally when ZenithProxy's client ticks 10x faster than
     * the wall clock.
     */
    double miningClock = 1.0;
    /** Share of the STOPs that would enter the delayed slot dropped before the server sees them. */
    double dropDelayedStops;
    /** Positions whose delayed destroy never completes (BlockBreakEvent cancelled; slot cleared, block stays). */
    final Set<Pos> neverComplete = new HashSet<>();
    /** Drop dig packets that Grim FastBreak flags (Grim's {@code shouldModifyPackets}). */
    boolean grimCancel;
    /**
     * Drop dig packets that Grim's MultiBreak flags (experimental, off in Grim's default config): a second dig packet
     * on another block or face within one client tick.
     */
    boolean cancelMultiBreak;
    /** Share of instant STARTs the server does not break (acked, the block stays): the odd refusal. */
    double refuseInstant;
    int instantsRefused;
    /**
     * Share of the blocks broken on a dig packet (instant or stopped; not the delayed destroy) whose air goes out
     * {@code 1..}{@link #lateAirTicks} server ticks after the dig's ack instead of with it, as 2b2t now and then (live
     * j0024: about 83 of 2,067 instant blocks showed air more than 2 ticks after their ack). 0 (default): with the ack.
     */
    double lateAir;
    /** Most extra server ticks a {@link #lateAir} block's air waits (uniform from 1). */
    int lateAirTicks = 4;
    /** {@link #lateAir} blocks so far. */
    int lateAirs;
    /** Late air: position → the server tick its block update goes out. */
    private final Map<Pos, Long> lateAirAt = new java.util.LinkedHashMap<>();

    // ------------------------------------------------------------------------------------------------------ state

    final Map<Pos, BlockType> world = new HashMap<>();
    final Item[] hotbar = new Item[9];
    int selected;
    /** Player feet (from the movement packets); the eye is 1.62 above. */
    double px;
    double pz;
    final double feetY;

    private final Random rnd;
    private final ArrayDeque<UpPacket> up = new ArrayDeque<>();
    private final ArrayDeque<DownPacket> down = new ArrayDeque<>();
    private long lastUpArrival;
    private long lastDownArrival;
    private long serverTicks;

    int gameTicks;
    boolean isDestroyingBlock;
    Pos destroyPos = new Pos(0, 0, 0);
    int destroyProgressStart;
    boolean hasDelayedDestroy;
    @Nullable Pos delayedDestroyPos;
    int delayedTickStart;
    private int ackUpTo = -1;
    private final Set<Pos> changed = new LinkedHashSet<>();
    private final Set<Integer> dirtySlots = new LinkedHashSet<>();

    final GrimBreakModel grim;

    // ------------------------------------------------------------------------------------------------- statistics

    final List<Destroy> destroys = new ArrayList<>();
    final Map<Pos, Integer> destroyCount = new HashMap<>();
    /** Dig packets per position (as sent, before any drop). */
    final Map<Pos, Integer> digsAt = new HashMap<>();
    int startWhileDestroying;
    int stopMismatch;
    int delayedSlotBusy;
    int abortMismatch;
    int selectsWhileDelayed;
    int tooFar;
    /** STARTs and STOPs beyond Grim FarBreak's reach ({@link #farBreak}), and the farthest one (blocks). */
    int farBreak;
    double farthestDig;
    int instaMines;
    int delayedEntered;
    int delayedDestroyed;
    int delayedNeverCompleted;
    int stopsDropped;
    int grimCancelled;
    int toolsBroken;
    int digPackets;
    /**
     * Dig packets at a position out of the world (Lambda's fake STARTs, {@link DigClient#outOfWorld}): vanilla ignores
     * them ("too far" or "too high") and acks their sequence. Not counted in {@link #tooFar}.
     */
    int outOfWorldDigs;
    int selects;

    DigServer(long seed, double feetY) {
        this.rnd = new Random(seed);
        this.feetY = feetY;
        this.grim = new GrimBreakModel(new GrimBreakModel.World() {
            @Override
            public boolean isAir(Pos p) {
                return !world.containsKey(p);
            }

            @Override
            public double damage(Pos p) {
                BlockType b = world.get(p);
                return b == null ? 1.0 : prog(hotbar[selected], b);
            }

            @Override
            public boolean couldInstantlyBreak(Pos p) {
                BlockType b = world.get(p);
                if (b == null) return true;
                if (prog(null, b) >= 1f) return true;
                for (Item i : hotbar) if (i != null && prog(i, b) >= 1f) return true;
                return false;
            }

            @Override
            public double pingMs() {
                return (uplink + downlink) * 50.0;
            }
        });
    }

    @Nullable Item held() {
        return hotbar[selected];
    }

    // ----------------------------------------------------------------------------------------------------- uplink

    /** The client sends {@code p} in tick {@code now} (arrival from the latency model). */
    void send(Up type, long now, DigClient.@Nullable Action action, @Nullable Pos pos, int face, int seq, int slot,
              double x, double z) {
        long arrival = Math.max(lastUpArrival, now + uplink + (jitter > 0 ? rnd.nextInt(jitter + 1) : 0));
        lastUpArrival = arrival;
        up.add(new UpPacket(type, now, arrival, action, pos, face, seq, slot, x, z));
    }

    /** Clientbound packets that arrived by tick {@code now}, in order. */
    List<DownPacket> receive(long now) {
        List<DownPacket> out = new ArrayList<>();
        while (!down.isEmpty() && down.peek().arrival() <= now) out.add(down.poll());
        return out;
    }

    /** Nothing in flight either way. */
    boolean idle() {
        return up.isEmpty() && down.isEmpty() && lateAirAt.isEmpty();
    }

    // ------------------------------------------------------------------------------------------------------ ticks

    /** The server's part of client tick {@code now} (after the client sent its packets). */
    void tick(long now) {
        if (Math.floor((now + 1) * tpsFactor) <= Math.floor(now * tpsFactor)) return;
        while (!up.isEmpty() && up.peek().arrival() <= now) handle(up.poll(), now);
        serverTicks++;
        gameModeTick(now);
        flush(now);
    }

    private void handle(UpPacket p, long now) {
        switch (p.type()) {
            case MOVE -> {
                px = p.x();
                pz = p.z();
                grim.tickPacket();
            }
            case SWING -> grim.swing();
            case SELECT -> {
                selects++;
                if (hasDelayedDestroy) selectsWhileDelayed++;
                selected = p.slot();
            }
            case DIG -> dig(p, now);
        }
    }

    private void dig(UpPacket p, long now) {
        digPackets++;
        DigClient.Action action = p.action();
        Pos pos = p.pos();
        digsAt.merge(pos, 1, Integer::sum);
        int multiBefore = grim.multiBreakFlags;
        boolean flagged = grim.dig(action, pos, p.face(), p.arrival() * 50);
        if (cancelMultiBreak && grim.multiBreakFlags > multiBefore) {
            grimCancelled++;
            resend(pos);
            return;
        }
        if (flagged && grimCancel) {
            grimCancelled++;
            resend(pos);
            return;
        }
        if (action == DigClient.Action.STOP && dropDelayedStops > 0 && wouldDelay(pos)
            && rnd.nextDouble() < dropDelayedStops) {
            stopsDropped++;
            return;
        }
        handleBlockBreakAction(pos, action, p.seq(), now);
        ackUpTo = Math.max(ackUpTo, p.seq());
    }

    /** A STOP on {@code pos} now would leave it to the delayed destroy. */
    private boolean wouldDelay(Pos pos) {
        if (!pos.equals(destroyPos) || hasDelayedDestroy) return false;
        BlockType b = world.get(pos);
        if (b == null) return false;
        return prog(held(), b) * (float) (gameTicks - destroyProgressStart + 1) < 0.7f;
    }

    private boolean canInteract(Pos pos) {
        double ex = px;
        double ey = feetY + 1.62;
        double ez = pz;
        double dx = Math.max(Math.max(pos.x() - ex, 0), ex - (pos.x() + 1));
        double dy = Math.max(Math.max(pos.y() - ey, 0), ey - (pos.y() + 1));
        double dz = Math.max(Math.max(pos.z() - ez, 0), ez - (pos.z() + 1));
        return dx * dx + dy * dy + dz * dz < 5.5 * 5.5;
    }

    /**
     * Grim FarBreak (experimental), strictly: the eye standing (1.62 above the feet of the last movement packet) to the
     * block's box beyond the block interaction range 4.5 (Grim adds the movement threshold and also tries the lower eye
     * heights) on a START or STOP.
     */
    private void farBreak(Pos pos, DigClient.Action action) {
        if (action == DigClient.Action.ABORT) return;
        double ey = feetY + 1.62;
        double dx = Math.max(Math.max(pos.x() - px, 0), px - (pos.x() + 1));
        double dy = Math.max(Math.max(pos.y() - ey, 0), ey - (pos.y() + 1));
        double dz = Math.max(Math.max(pos.z() - pz, 0), pz - (pos.z() + 1));
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        farthestDig = Math.max(farthestDig, d);
        if (d > 4.5) farBreak++;
    }

    private void handleBlockBreakAction(Pos pos, DigClient.Action action, int seq, long now) {
        if (DigClient.outOfWorld(pos)) {
            outOfWorldDigs++;
            return;
        }
        farBreak(pos, action);
        if (!canInteract(pos)) {
            tooFar++;
            return;
        }
        switch (action) {
            case START -> {
                destroyProgressStart = gameTicks;
                BlockType b = world.get(pos);
                float f = 1f;
                if (b != null) f = prog(held(), b);
                if (b != null && f >= 1f && refuseInstant > 0 && rnd.nextDouble() < refuseInstant) {
                    instantsRefused++;
                    resend(pos); // as if not instant here (an effect just ran out): acked, the block stays
                } else if (b != null && f >= 1f) {
                    instaMines++;
                    destroyAndAck(pos, false, now);
                } else {
                    if (isDestroyingBlock) {
                        startWhileDestroying++;
                        resend(destroyPos);
                    }
                    isDestroyingBlock = true;
                    destroyPos = pos;
                }
            }
            case STOP -> {
                if (!pos.equals(destroyPos)) {
                    stopMismatch++;
                    return;
                }
                int t = gameTicks - destroyProgressStart;
                BlockType b = world.get(pos);
                if (b == null) return;
                float p = prog(held(), b) * (float) (t + 1);
                if (p >= 0.7f) {
                    isDestroyingBlock = false;
                    destroyAndAck(pos, false, now);
                } else if (!hasDelayedDestroy) {
                    isDestroyingBlock = false;
                    hasDelayedDestroy = true;
                    delayedDestroyPos = pos;
                    delayedTickStart = destroyProgressStart;
                    delayedEntered++;
                } else {
                    delayedSlotBusy++;
                }
            }
            case ABORT -> {
                isDestroyingBlock = false;
                if (!pos.equals(destroyPos)) {
                    abortMismatch++;
                    destroyPos = new Pos(0, 0, 0);
                }
            }
        }
    }

    private void gameModeTick(long now) {
        if (paperClock) {
            gameTicks = (int) Math.floor((now + 1) * miningClock + 1e-9);
        } else {
            gameTicks++;
        }
        if (hasDelayedDestroy) {
            Pos d = delayedDestroyPos;
            BlockType b = world.get(d);
            if (b == null) {
                hasDelayedDestroy = false;
            } else {
                float p = prog(held(), b) * (float) (gameTicks - delayedTickStart + 1);
                if (p >= 1f) {
                    hasDelayedDestroy = false;
                    if (neverComplete.contains(d)) {
                        delayedNeverCompleted++;
                        resend(d);
                    } else {
                        delayedDestroyed++;
                        destroyBlock(d, true, now);
                    }
                }
            }
        } else if (isDestroyingBlock && !world.containsKey(destroyPos)) {
            isDestroyingBlock = false;
        }
    }

    private void destroyAndAck(Pos pos, boolean delayed, long now) {
        if (!destroyBlock(pos, delayed, now)) resend(pos);
    }

    private boolean destroyBlock(Pos pos, boolean delayed, long now) {
        BlockType b = world.remove(pos);
        if (b == null) return false;
        if (!delayed && lateAir > 0 && rnd.nextDouble() < lateAir) {
            lateAirAt.put(pos, now + 1 + rnd.nextInt(Math.max(1, lateAirTicks)));
            lateAirs++;
        } else {
            changed.add(pos);
        }
        Item h = held();
        int left = Integer.MAX_VALUE;
        if (h != null && h.damageable()) {
            if (b.hardness() > 0) {
                h.damage++;
                dirtySlots.add(selected);
            }
            left = h.remaining();
            if (left <= 0) {
                toolsBroken++;
                hotbar[selected] = null;
            }
        }
        destroys.add(new Destroy(pos, b, h == null ? "hand" : h.id, h == null ? null : h.kind, left, delayed, now));
        destroyCount.merge(pos, 1, Integer::sum);
        return true;
    }

    /** Mending stand-in: hotbar slot {@code slot}'s tool at {@code usesLeft}, sent to the client with the next flush. */
    void repair(int slot, int usesLeft) {
        Item i = hotbar[slot];
        if (i == null || !i.damageable()) return;
        i.damage = Math.max(0, i.maxUses - Math.min(i.maxUses, usesLeft));
        dirtySlots.add(slot);
    }

    /** A block update for {@code pos} with its current state. */
    private void resend(Pos pos) {
        changed.add(pos);
    }

    private void flush(long now) {
        for (var it = lateAirAt.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue() > now) continue;
            changed.add(e.getKey());
            it.remove();
        }
        if (dirtySlots.isEmpty() && changed.isEmpty() && ackUpTo < 0) return;
        long arrival = Math.max(lastDownArrival, now + downlink + (jitter > 0 ? rnd.nextInt(jitter + 1) : 0));
        lastDownArrival = arrival;
        for (int s : dirtySlots) {
            Item i = hotbar[s];
            down.add(new DownPacket(Down.SLOT, arrival, null, null, s, i == null ? null : i.copy(), 0));
        }
        dirtySlots.clear();
        for (Pos p : changed) down.add(new DownPacket(Down.BLOCK, arrival, p, world.get(p), 0, null, 0));
        changed.clear();
        if (ackUpTo >= 0) {
            down.add(new DownPacket(Down.ACK, arrival, null, null, 0, null, ackUpTo));
            ackUpTo = -1;
        }
    }

    long serverTicks() {
        return serverTicks;
    }
}
