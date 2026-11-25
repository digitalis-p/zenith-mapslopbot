package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

/**
 * Grim 2.0's breaking checks (GrimAnticheat/Grim branch 2.0, {@code checks/impl/breaking}, read 2026-10-11) as the
 * {@link DigServer} sees the uplink: every dig packet, swing and tick (movement) packet in arrival order. Times in ms
 * (arrival tick × 50). Counts only, except that {@link DigServer#grimCancel} drops a packet FastBreak flags (Grim's
 * {@code shouldModifyPackets}).
 *
 * <ul>
 *   <li><b>FastBreak</b> (default on). START (not on air): {@code startBreak = now − (target == null ? 50 : 0)},
 *       target = pos, {@code maxDamage} = damage with the held item; {@code breakDelay = now − lastFinishBreak}, ≥ 275 ms
 *       → {@code delayBalance ×= 0.9} else {@code += 300 − breakDelay}; flag above 1000. Every swing and tick packet:
 *       {@code maxDamage = max(maxDamage, damage of the target with the held item now)}. STOP (any position, target
 *       set): {@code diff = ceil(1 / maxDamage) × 50 − (now − startBreak)}; {@code diff < 25 → breakBalance ×= 0.9}
 *       else {@code += diff}; flag above 1000; {@code lastFinishBreak = startBreak = now}. ABORT does nothing; the
 *       target is never cleared. Both balances are clamped to ±max(1000, ping).</li>
 *   <li><b>WrongBreak</b> (default on): a STOP on neither the last STARTed nor the last ABORTed block (unless the last
 *       START was on a block some hotbar item breaks instantly).</li>
 *   <li><b>AirLiquidBreak</b> (default on): START or STOP on air (the server's world as Grim's compensated world).</li>
 *   <li><b>PositionBreakB</b> (default on): after an ABORT whose face is not DOWN, any dig packet but a START with
 *       that face.</li>
 *   <li><b>MultiBreak</b>, <b>NoSwingBreak</b> (experimental): two non-ABORT digs between tick packets on another
 *       position or face; a non-ABORT dig without a swing before the tick packet.</li>
 * </ul>
 */
final class GrimBreakModel {
    static final int FACE_DOWN = 0;

    /** What Grim reads from its own world and inventory copies. */
    interface World {
        /** The block at {@code p} is air. */
        boolean isAir(Pos p);

        /** Destroy progress per tick on {@code p} with the item held now ({@code BlockBreakSpeed.getBlockDamage}). */
        double damage(Pos p);

        /** Some hotbar item breaks the block at {@code p} instantly ({@code couldInstantlyBreakBlock}). */
        boolean couldInstantlyBreak(Pos p);

        /** Transaction ping in ms. */
        double pingMs();
    }

    private final World world;

    // FastBreak
    private @Nullable Pos target;
    private double maxDamage;
    private long lastFinishBreak;
    private long startBreak;
    double breakBalance;
    double delayBalance;
    double maxBreakBalance;
    double maxDelayBalance;
    int fastBreakBreakFlags;
    int fastBreakDelayFlags;

    // WrongBreak
    private boolean lastBlockWasInstantBreak;
    private @Nullable Pos lastBlock;
    private @Nullable Pos lastCancelledBlock;
    int wrongBreakFlags;

    int airLiquidFlags;
    /**
     * AirLiquidBreak on positions out of the world (Lambda's fake STARTs): what the upstream check would flag if it
     * does not exempt them (its compensated world answers air there). Not counted in {@link #airLiquidFlags}.
     */
    int airLiquidOutOfWorld;

    // PositionBreakB
    private int lastFace = -1;
    int positionBreakBFlags;

    // MultiBreak
    private boolean hasBroken;
    private int mbFace;
    private @Nullable Pos mbPos;
    int multiBreakFlags;

    // NoSwingBreak
    private boolean sentAnimation;
    private boolean sentBreak;
    int noSwingFlags;

    GrimBreakModel(World world) {
        this.world = world;
    }

    /** One dig packet. True if FastBreak flagged it (Grim cancels it when it modifies packets). */
    boolean dig(DigClient.Action action, Pos pos, int face, long nowMs) {
        boolean air = world.isAir(pos);
        // PositionBreakB
        if (action == DigClient.Action.START && face == lastFace) lastFace = -1;
        if (lastFace >= 0) positionBreakBFlags++;
        if (action == DigClient.Action.ABORT) lastFace = face == FACE_DOWN ? -1 : face;
        // MultiBreak
        if (action != DigClient.Action.ABORT) {
            if (hasBroken && (face != mbFace || !pos.equals(mbPos))) multiBreakFlags++;
            mbFace = face;
            mbPos = pos;
            hasBroken = true;
            sentBreak = true;
        }
        // AirLiquidBreak
        if (action != DigClient.Action.ABORT && air) {
            if (DigClient.outOfWorld(pos)) airLiquidOutOfWorld++;
            else airLiquidFlags++;
        }
        // WrongBreak
        switch (action) {
            case START -> {
                lastBlockWasInstantBreak = !air && world.couldInstantlyBreak(pos);
                lastCancelledBlock = null;
                lastBlock = pos;
            }
            case ABORT -> {
                lastCancelledBlock = pos;
                lastBlock = null;
            }
            case STOP -> {
                if (!pos.equals(lastCancelledBlock) && !lastBlockWasInstantBreak && !pos.equals(lastBlock)) {
                    wrongBreakFlags++;
                }
            }
        }
        return fastBreak(action, pos, nowMs, air);
    }

    private boolean fastBreak(DigClient.Action action, Pos pos, long now, boolean air) {
        boolean flagged = false;
        if (action == DigClient.Action.START) {
            if (air) return false;
            startBreak = now - (target == null ? 50 : 0);
            target = pos;
            maxDamage = world.damage(pos);
            double breakDelay = now - lastFinishBreak;
            if (breakDelay >= 275) delayBalance *= 0.9;
            else delayBalance += 300 - breakDelay;
            maxDelayBalance = Math.max(maxDelayBalance, delayBalance);
            if (delayBalance > 1000) {
                fastBreakDelayFlags++;
                flagged = true;
            }
            clamp();
        }
        if (action == DigClient.Action.STOP && target != null) {
            double predicted = Math.ceil(1 / maxDamage) * 50;
            double real = now - startBreak;
            double diff = predicted - real;
            clamp();
            if (diff < 25) breakBalance *= 0.9;
            else breakBalance += diff;
            maxBreakBalance = Math.max(maxBreakBalance, breakBalance);
            if (breakBalance > 1000) {
                fastBreakBreakFlags++;
                flagged = true;
            }
            lastFinishBreak = startBreak = now;
        }
        return flagged;
    }

    /** An arm swing (animation packet). */
    void swing() {
        sentAnimation = true;
        updateDamage();
    }

    /** A movement packet: the client's tick ended. */
    void tickPacket() {
        updateDamage();
        if (sentBreak && !sentAnimation) noSwingFlags++;
        sentAnimation = false;
        sentBreak = false;
        hasBroken = false;
    }

    private void updateDamage() {
        if (target != null && !world.isAir(target)) maxDamage = Math.max(maxDamage, world.damage(target));
    }

    private void clamp() {
        double b = Math.max(1000, world.pingMs());
        breakBalance = Math.max(-b, Math.min(b, breakBalance));
        delayBalance = Math.max(-b, Math.min(b, delayBalance));
    }

    int fastBreakFlags() {
        return fastBreakBreakFlags + fastBreakDelayFlags;
    }
}
