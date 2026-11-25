package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Which blocks {@code build.breakMode} DOUBLE breaks together. Pure
 * (no ZenithProxy calls), so it is unit-tested.
 *
 * <p>Vanilla 1.21.4 {@code ServerPlayerGameMode} keeps one "delayed destroy": a STOP_DESTROY_BLOCK whose progress
 * ({@code speed × (ticks since START + 1)}) is below {@link #DELAY_MAX_SPEED 0.7} does not break the block but leaves it
 * to the server's own tick, which breaks it once {@code speed × (ticks since START + 1) ≥ 1}, with the speed of the item
 * held at that tick. Meanwhile a second block can be mined the normal way. A pair is therefore:
 * <ul>
 *   <li>the <b>delayed</b> block: started and stopped at once, so its destroy speed per tick must stay below 0.7
 *       (otherwise the early STOP breaks it on the spot, which is a plain fast break);</li>
 *   <li>the <b>active</b> block: mined as usual, so it may be any block that does not break on the START itself
 *       (speed below 1);</li>
 *   <li>both with the <b>same tool group</b>: the delayed block progresses with whatever the main hand holds while the
 *       active one is mined, so a pickaxe block never pairs with an axe or a hand block;</li>
 *   <li>both <b>within reach and sight of the same stand position</b>: the caller passes only such candidates.</li>
 * </ul>
 * Blocks that break on the START itself (speed ≥ 1: a mushroom stem with an axe, sand with a shovel) gain nothing and
 * are never paired; carpets (3 ticks by hand) and wool (24) pair by hand. Of two partners the slower one is delayed: it
 * starts a tick earlier and needs the full progress of 1.0, the active one only until the client's own progress
 * reaches 1.
 */
final class BreakPairs {
    private BreakPairs() { }

    /**
     * Least destroy progress at which vanilla's STOP_DESTROY_BLOCK breaks the block itself
     * ({@code ServerPlayerGameMode.handleBlockBreakAction}: {@code progress >= 0.7F → destroyAndAck}). A block stopped
     * in the tick it was started has progress = its speed per tick, so only blocks slower than this can be delayed.
     */
    static final double DELAY_MAX_SPEED = 0.7;

    /**
     * What breaking a block takes with the item {@link ToolChoice} picks for it.
     *
     * @param group a tool kind ("pickaxe"), {@link ToolRack#HAND}, or "" (neither: never paired)
     * @param speed vanilla destroy progress per tick with that item, on the ground
     */
    record Info(String group, double speed) {
        /** Ticks the vanilla client mines it ({@link ToolChoice#ticks}). */
        int ticks() {
            return ToolChoice.ticks(speed);
        }

        /** Breaks on the START (vanilla "insta mine"). */
        boolean instant() {
            return speed >= 1.0;
        }

        /** May be the delayed block of a pair. */
        boolean canDelay() {
            return !group.isEmpty() && speed > 0 && speed < DELAY_MAX_SPEED;
        }

        /** May be the actively mined block of a pair. */
        boolean canBeActive() {
            return !group.isEmpty() && speed > 0 && speed < 1.0;
        }
    }

    /** One round: {@code delayed} (null for a single block) is started first, {@code active} is mined after it. */
    record Round<T>(@Nullable T delayed, T active) {
        boolean pair() {
            return delayed != null;
        }

        /** The block the round starts with. */
        T first() {
            return delayed != null ? delayed : active;
        }
    }

    /** True if {@code a} and {@code b} can be broken as a pair (either one delayed). */
    static boolean pairable(Info a, Info b) {
        if (!a.group().equals(b.group())) return false;
        if (!a.canBeActive() || !b.canBeActive()) return false;
        return a.canDelay() || b.canDelay();
    }

    /**
     * The partners {@code first} could be paired with, best first: candidates whose {@link Info} is known and
     * {@linkplain #pairable pairable} with {@code first}'s; the most similar break time first (both finish together),
     * then the nearest to {@code first} (the smallest turn), then the given order. {@code candidates} are the targets the
     * caller sees within reach from where the bot stands now (it still checks sight on the chosen one); {@code first}
     * itself is skipped. Empty when {@code first} cannot be paired at all.
     */
    static <T> List<T> partners(T first, Info firstInfo, Collection<T> candidates, Function<T, @Nullable Info> info,
                                ToDoubleFunction<T> distSqToFirst) {
        List<T> out = new ArrayList<>();
        if (!firstInfo.canBeActive()) return out;
        List<Scored<T>> scored = new ArrayList<>();
        int i = 0;
        for (T c : candidates) {
            i++;
            if (c.equals(first)) continue;
            Info ci = info.apply(c);
            if (ci == null || !pairable(firstInfo, ci)) continue;
            scored.add(new Scored<>(c, Math.abs(ci.ticks() - firstInfo.ticks()), distSqToFirst.applyAsDouble(c), i));
        }
        scored.sort(Comparator.<Scored<T>>comparingInt(Scored::tickGap)
            .thenComparingDouble(Scored::distSq)
            .thenComparingInt(Scored::order));
        for (Scored<T> s : scored) out.add(s.item());
        return out;
    }

    private record Scored<T>(T item, int tickGap, double distSq, int order) { }

    /**
     * The round of {@code first} with {@code partner} (null: a single): the slower block (lower speed; on a tie
     * {@code first}) is delayed, provided it {@linkplain Info#canDelay can be}; otherwise a single.
     */
    static <T> Round<T> round(T first, Info firstInfo, @Nullable T partner, @Nullable Info partnerInfo) {
        if (partner == null || partnerInfo == null || partner.equals(first) || !pairable(firstInfo, partnerInfo)) {
            return new Round<>(null, first);
        }
        boolean firstSlower = firstInfo.speed() <= partnerInfo.speed();
        T slow = firstSlower ? first : partner;
        T fast = firstSlower ? partner : first;
        Info slowInfo = firstSlower ? firstInfo : partnerInfo;
        if (!slowInfo.canDelay()) return new Round<>(null, first);
        return new Round<>(slow, fast);
    }

    /**
     * Single-break ticks for one block at the cadence {@link DigClient} uses (ZenithProxy's: no progress in the START
     * tick), for the round log's "vs single": an instant block one tick (one START per tick), else START to STOP
     * ({@link Info#ticks}), the 5-tick delay after the STOP, and the tick that starts the next block.
     */
    static int singleTicks(Info i) {
        return singleTicks(i, 1.0);
    }

    /**
     * {@link #singleTicks(Info)} in client ticks when the server's mining clock advances {@code clock} per client tick
     * ({@link DigClient#serverClock}: 0.1 with the local 10x client): the mining scales, the client's 5-tick delay and
     * the START tick do not.
     */
    static int singleTicks(Info i, double clock) {
        if (!(i.speed() > 0)) return 0;
        if (i.instant()) return 1;
        double c = clock > 0 && clock < 1 ? clock : 1.0;
        return (int) Math.ceil(i.ticks() / c - 1e-9) + DigClient.DESTROY_DELAY + 1;
    }
}
