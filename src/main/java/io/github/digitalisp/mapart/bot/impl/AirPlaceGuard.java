package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Offhand safety for airplace. Pure; client tick thread only.
 *
 * <p><b>The rule</b> is {@link OffhandGuard}'s: the offhand (live: a totem of undying, AutoTotem off) and the armour are
 * the owner's, the bot never moves anything out of them, and no material is ever left in the offhand between placements.
 * Its one sanctioned exception is airplace: {@link AirPlace} swaps the selected hotbar slot with the offhand
 * (PlayerAction SWAP_ITEM_WITH_OFFHAND), uses the offhand and swaps back, in one tick, so the offhand item never leaves the
 * inventory (for the length of the packets it sits in the selected hotbar slot). This guard drives that exception: it
 * records each swap in {@link OffhandGuard}'s swap record ({@link #record}, which also decides the repair of an
 * interrupted one) and adds what the record alone cannot see. Any other offhand logic asks {@link #busy} first and leaves
 * the offhand alone while it is true.
 *
 * <p><b>What the cache shows.</b> ZenithProxy predicts each swap in its inventory cache when the packet is sent, so after
 * a complete airplace the cache is unchanged. The server answers with slot updates: normally only the held stack's new
 * count; if it handled the two swaps in different server ticks, first the swapped slots (the offhand item in the held
 * slot, the material in the offhand), then the restored ones. That transient state matches {@link #swapPattern}; the
 * guard waits {@link #SETTLE_TICKS} for it to clear ({@link #busy} true meanwhile: no click, no inventory move).
 *
 * <p><b>Recovery</b> ({@link #tick}, every bot tick before the engine, so also after a pause, a stop, a player's takeover
 * or a reconnect):
 * <ul>
 *   <li>Swap 1 sent but swap 2 not (an exception between the packets): on the next tick {@link OffhandGuard#restore}
 *       decides: back in place, swap 2 now (the same slot selected first), or LOST (the item is neither in the offhand
 *       nor in that slot: airplace off, an alert line, never searched for with more clicks). With an empty offhand,
 *       swap 2 follows if the airplaced item still sits in the offhand. After a disconnect the server's state decides
 *       instead (next point).</li>
 *   <li>The cache shows the swap pattern longer than {@link #SETTLE_TICKS} after the last swap (the server kept the
 *       swapped state: a swap packet lost or refused, or a disconnect between the swaps): select the slot that holds
 *       the offhand item and swap once more. At most {@link #MAX_RESTORES} times per ZenithProxy run, then the guard
 *       gives up (no more airplace; LOOK placements go on; an alert, {@link #takeAlert}).</li>
 *   <li>ZenithProxy fires no bot tick while disconnected or while a player controls the account, so the module reports
 *       those ({@link #stopped}); the first in-game ticks after them wait {@link #SETTLE_TICKS} for the inventory
 *       before anything is judged (an empty cache right after the login is not a lost item), and longer while the cache
 *       shows nothing at all (offhand and hotbar empty).</li>
 *   <li>The first in-game tick learns the offhand item. Learnt from an empty cache (offhand and hotbar empty: right
 *       after a login the inventory has not arrived yet) it is a guess: the first tick the cache shows the inventory
 *       learns it again, silently. Only a change of an offhand seen in a filled cache is an event (live gate C: "offhand
 *       now holds the totem (was nothing)" at every login).</li>
 *   <li>The offhand changed otherwise (a totem used up, the owner or a module put something else there), once it is
 *       stable for {@link #SETTLE_TICKS} with no swap in flight: see "Offhand item" below.</li>
 * </ul>
 *
 * <p><b>Offhand item</b> ({@code build.offhandItem}, the totem; {@code build.offhandRepair}, the kill switch). Live
 * j0002 2026-10-12, tile [1,1]: during setbacks a corrective swap acted on a stale cache and moved the totem out of the
 * offhand; a lane's hotbar move then put it into the main inventory and the guard adopted "nothing"; at 01:49 another
 * corrective swap left clay in the offhand and the guard adopted clay. A block in the offhand turns airplace off, so the
 * next three tiles were built in LOOK lanes, unnoticed. Now only the configured item or a totem is ever adopted as
 * the offhand's item ({@link #desired}), at the first tick as after a change, and an empty offhand only when neither is
 * in the inventory. Anything else is a mismatch: airplace is off ({@link #airPlaceAvailable}) and, while the item is in
 * the inventory, {@link #repairSlot} names its slot, which the engine swaps into the offhand at the next safe point
 * (OffhandRepairTask: standing still, one container click, button 40). With the kill switch off any stable item is
 * adopted, as before.
 *
 * <p><b>Offhand confirmation</b> (local 1x Grim drills C1 and gs2 at 100 ms each way, 2026-10-29). The cache is a
 * prediction plus whatever slot updates arrive, and one of them is out of band: Grim, cancelling an offhand place
 * (AirLiquidPlace), sends SetSlot(45) with the server's offhand as it reads it on the netty thread, which can fall
 * between the two swaps of a pair the main thread is processing. That slot update shows the airplaced block in the
 * offhand after the pair is long done; vanilla's slot tracking never corrects it (it thinks the client knows), so the
 * cache showed carpet in the offhand and in the hotbar slot and no totem anywhere, for good (gs2: "No totem" for 9
 * minutes). Corrective swaps sent on such a cache then really moved the totem out (C1: three of them in 3 s), and the
 * lane's next airplace went out on the corrective swap's prediction before the server had answered it. Now:
 * <ul>
 *   <li>A swap of the guard's own (corrective, finishing) and the engine's repair click ({@link #clickedOffhand}) wait for
 *       the server's report of the offhand ({@link View#offhandReports}) or {@link #ANSWER_TICKS}: no airplace, no
 *       judging, {@link #busy} meanwhile; what the answer shows settles {@link #SETTLE_TICKS} before a further swap.</li>
 *   <li>The airplaced block in the offhand with the offhand item nowhere in the cache is a stale cache: airplace off at
 *       once, no swap.</li>
 *   <li>A swapped state back after one corrective swap gets no second swap on the cache: {@link #checkWanted} first;
 *       the server's answer decides ({@link #serverChecked}); after {@link #RECHECK_TICKS} without one, the swap.</li>
 *   <li>Whenever airplace is off over an offhand without its item and the cache shows none in the inventory,
 *       {@link #checkWanted}: the engine parks the lane and asks the server for the full inventory (OffhandRepairTask,
 *       the stateId -1 no-op click); {@link #serverChecked} judges the answer, and the repair puts the item back if the
 *       server shows it in the inventory.</li>
 * </ul>
 * Ordinary pairs are not paced: a pair handled in one server tick changes no slot the server reports, so there is no
 * answer to wait for, and one pair per round trip would cut the airplace rate to about a third at 2b2t's 165 ms.
 *
 * <p><b>Alerts</b> ({@link #takeAlert}, {@link #takeOffhandAlert}, always-on Discord alerts in AIRPLACE mode): airplace
 * turning off for any reason, and the offhand without the item while one is in the inventory; each at most once per
 * {@link #ALERT_REPEAT_TICKS} (later ones are logged). Airplace back on is a notice ({@link #takeNotice}).
 *
 * <p>Airplace is allowed ({@link #mayAirPlace}) only when settled and when the offhand holds nothing that places a
 * block: a desynced swap must never place the offhand item. The printer moves such a block out of the offhand with a
 * container click and close; here that would be a container click while walking (Grim MultiActionsC), so the lane
 * places by LOOK instead until the offhand is repaired at a safe point.
 */
public final class AirPlaceGuard {
    /** Ticks a swapped-looking cache is taken for the server's echo of a split airplace before it is corrected. */
    public static final int SETTLE_TICKS = 20;
    /** Corrective swaps before the guard gives up on airplace. */
    public static final int MAX_RESTORES = 3;
    /** The totem of undying: always an offhand item the guard adopts. */
    public static final String TOTEM = "minecraft:totem_of_undying";
    /** Bot ticks between two Discord alerts of one kind (5 minutes at 20 TPS); the ones between are logged only. */
    public static final int ALERT_REPEAT_TICKS = 6000;
    /**
     * Ticks a swap of the guard's own (a corrective or finishing swap, the engine's repair click) waits for the server's
     * report of the offhand before the guard judges again (2 s: ten round trips at 2b2t's 165 ms).
     */
    public static final int ANSWER_TICKS = 40;
    /** Bot ticks between two full-inventory checks of one blind episode (5 minutes). */
    public static final int CHECK_REPEAT_TICKS = 6000;
    /**
     * Ticks a swapped state that came back after a corrective swap waits for the server check before the guard swaps
     * on the cache anyway (20 s: the lane's next safe point and the check's own bounds).
     */
    public static final int RECHECK_TICKS = 400;

    /** The inventory as ZenithProxy's cache shows it now (fakes in tests). Namespaced material ids, null = empty. */
    public interface View {
        /** Connected and in game (bot ticks running). */
        boolean inGame();

        @Nullable String offhand();

        /** Item in hotbar slot {@code i} (0-8). */
        @Nullable String hotbar(int i);

        /** Selected hotbar slot 0-8. */
        int heldSlot();

        boolean containerOpen();

        /** No use-item-on while an InventoryManager request executes (MultiActions). */
        default boolean inventoryBusy() { return false; }

        /** True if using {@code itemId} on a block places a block (block items, redstone, string, seeds, ...). */
        boolean placesBlock(String itemId);

        /** Player slot (9-44: main inventory, then the hotbar 36-44) holding {@code itemId}, or -1. */
        int slotOf(String itemId);

        /**
         * Server reports that named the offhand so far ({@link InventoryEchoes#offhandReports}: full contents or a slot
         * update of slot 45), a count that only grows; -1 when the view cannot tell (fakes whose cache is the server's at
         * once): a swap of the guard's own is then taken as answered at once.
         */
        default long offhandReports() { return -1; }
    }

    private static final AirPlaceGuard SHARED = new AirPlaceGuard();

    /** The guard of the plugin's one bot (MapArtModule ticks it; lane tasks place through it). */
    public static AirPlaceGuard shared() {
        return SHARED;
    }

    private final OffhandGuard record = new OffhandGuard();
    private long now;
    private boolean known;
    /** {@link #expected} was learnt from an empty cache (the inventory not arrived yet): learnt again once it shows. */
    private boolean guessed;
    private @Nullable String expected;
    /** Hotbar index of the last airplace (where the offhand item sits while swapped), -1 = none yet. */
    private int slot = -1;
    /** Item airplaced from {@link #slot} (what sits in the offhand while swapped). */
    private @Nullable String material;
    private long lastSwapAt = Long.MIN_VALUE / 4;
    private long restoredAt = Long.MIN_VALUE / 4;
    /** Swap 1 sent, swap 2 not (yet). */
    private boolean out;
    private int restores;
    private boolean gaveUp;
    private boolean wasInGame;
    /** Bot ticks stopped since the last in-game tick (disconnect, takeover): settle before judging. */
    private boolean restarted;
    private long resumedAt;
    private @Nullable String alert;
    private @Nullable String changedTo;
    private long changedSince;
    private boolean changeSeen;
    private @Nullable String lastEvent;
    // the offhand item (class javadoc "Offhand item")
    private String offhandItem = TOTEM;
    private boolean repair = true;
    private boolean airplaceMode = true;
    /** The offhand holds something other than {@link #expected}, judged stable and not adopted: airplace off. */
    private boolean mismatch;
    /** Airplace was available at the last judged tick (null: not judged yet). */
    private @Nullable Boolean wasAvailable;
    private @Nullable String gaveUpReason;
    private @Nullable String offhandAlert;
    private @Nullable String notice;
    private long offAlertAt = Long.MIN_VALUE / 4;
    private long itemAlertAt = Long.MIN_VALUE / 4;
    /** The offhand-without-the-item alert was raised (or logged) for the current episode. */
    private boolean itemMissingSeen;
    // offhand confirmation (class javadoc "Offhand confirmation")
    /** A swap of the guard's own (or the repair click) went out: nothing is judged until the server reports the offhand. */
    private boolean awaiting;
    private long answerMark;
    private long awaitingSince;
    /** The last full-inventory check (the server's answer to the request); one per {@link #CHECK_REPEAT_TICKS}. */
    private long checkedAt = Long.MIN_VALUE / 4;
    private int checks;
    private int staleSeen;
    /** Corrective swaps sent on the cache since the last full-inventory check (or the last settled state). */
    private int blindSwaps;
    /** The swapped state came back after a corrective swap at this tick: a server check is wanted first; -1 = none. */
    private long recheckSince = -1;

    /**
     * The owner's settings, every tick before {@link #tick}: {@code item} the offhand's item ({@code build.offhandItem},
     * null or blank = the totem), {@code repair} the policy ({@code build.offhandRepair}), {@code airplaceMode} placing
     * in AIRPLACE mode (alerts and repairs only then: in LOOK mode ZenithProxy's AutoTotem keeps the offhand).
     */
    public void configure(@Nullable String item, boolean repair, boolean airplaceMode) {
        this.offhandItem = item == null || item.isBlank() ? TOTEM : ItemIds.ns(item.strip());
        this.repair = repair;
        this.airplaceMode = airplaceMode;
    }

    /**
     * Bot ticks stopped (ZenithProxy fires none while disconnected or while a player controls the account): the next
     * in-game tick waits {@link #SETTLE_TICKS} for the inventory before it judges the offhand. {@code disconnected}: swap
     * 2 of an interrupted pair can no longer follow; the server's inventory after the reconnect decides instead.
     * Client tick thread.
     */
    public void stopped(boolean disconnected) {
        if (disconnected) awaiting = false;
        if (disconnected && out) {
            out = false;
            record.swappedBack();
            event("disconnected between the two offhand swaps; checking the offhand after the reconnect");
        }
        if (wasInGame) restarted = true;
        wasInGame = false;
    }

    /** Guard clock: bot ticks seen by {@link #tick}. */
    public long now() {
        return now;
    }

    /**
     * One bot tick, before any task: advances the clock, learns the offhand item, finishes an interrupted airplace and
     * corrects a swapped state that outlived the settle time. Sends at most one select and one swap.
     */
    public void tick(View v, AirPlace.Port port) {
        step(v, port);
        watch(v);
    }

    private void step(View v, AirPlace.Port port) {
        now++;
        if (!v.inGame()) {
            awaiting = false; // the answer of the last connection never comes; the reconnect settles instead
            if (out) {
                // swap 2 cannot follow any more: after the reconnect the server's inventory shows what it kept
                out = false;
                record.swappedBack();
                event("disconnected between the two offhand swaps; checking the offhand after the reconnect");
            }
            if (wasInGame) restarted = true;
            wasInGame = false;
            return;
        }
        if (!wasInGame) {
            wasInGame = true;
            if (restarted) {
                resumedAt = now;
                if (slot >= 0) lastSwapAt = now;
            }
        }
        // after a reconnect or a takeover the inventory (re)arrives: judge nothing until it has settled
        if (restarted) {
            if (now - resumedAt <= SETTLE_TICKS || cacheEmpty(v)) return;
            restarted = false;
        }
        if (out) {
            finishSwap(v, port);
            return;
        }
        if (awaiting) {
            // a swap of our own went out: the cache shows ZenithProxy's prediction of it until the server reports the
            // offhand; nothing is judged (and no airplace sent) on a prediction
            long r = v.offhandReports();
            boolean answered = r < 0 || r > answerMark;
            if (!answered && now - awaitingSince <= ANSWER_TICKS) return;
            awaiting = false;
            restoredAt = Math.max(restoredAt, now); // what the answer shows settles again before a further swap
            if (!answered) {
                event("no server report of the offhand " + ANSWER_TICKS
                    + " ticks after the guard's swap; judging the inventory cache");
            }
        }
        String off = v.offhand();
        if (!known || (guessed && !cacheEmpty(v))) {
            // the first in-game tick, or the first one whose cache shows the inventory after learning from an empty one
            known = true;
            guessed = cacheEmpty(v);
            expected = guessed ? off : desired(off, v);
            mismatch = !Objects.equals(expected, off);
            if (mismatch) event("offhand holds " + name(off) + " at the start: not adopted (" + plan(v) + ")");
            changeSeen = false;
            return;
        }
        if (Objects.equals(off, expected)) {
            changeSeen = false;
            recheckSince = -1;
            if (now - Math.max(lastSwapAt, restoredAt) > SETTLE_TICKS) blindSwaps = 0; // settled: a new episode
            if (mismatch) {
                mismatch = false;
                event("offhand holds " + name(off) + " again");
            } else if (off == null && repair) {
                // an empty offhand adopted with nothing to put back: the item has turned up in the inventory since
                String d = desired(null, v);
                if (d != null) {
                    expected = d;
                    mismatch = true;
                    event("offhand empty while " + d + " is in the inventory (" + plan(v) + ")");
                }
            }
            return;
        }
        if (swapPattern(v)) {
            changeSeen = false;
            if (gaveUp || now - Math.max(lastSwapAt, restoredAt) <= SETTLE_TICKS || v.containerOpen()) return;
            if (restores >= MAX_RESTORES) {
                gaveUp = true;
                event("gave up: the offhand item is still in hotbar slot " + slot + " after " + restores
                    + " corrective swaps; airplace off");
                gaveUpReason = lastEvent;
                return;
            }
            if (repair && airplaceMode && blindSwaps >= 1) {
                // the swapped state is back after a corrective swap on the cache (drill OD1: three in 3 s, the totem
                // ending up in the main inventory): no second swap on the cache; the server's full inventory first
                if (recheckSince < 0) {
                    recheckSince = now;
                    event("the swapped state is back after a corrective swap; asking the server for the full inventory"
                        + " before another swap");
                }
                if (now - recheckSince <= RECHECK_TICKS) return;
                // no answer in time (no safe point): swap on the cache, as before the check existed
            }
            if (v.heldSlot() != slot && !port.select(slot)) return;
            if (port.swapOffhand()) {
                restores++;
                blindSwaps++;
                recheckSince = -1;
                restoredAt = now;
                awaitAnswer(v);
                event("offhand item found in hotbar slot " + slot + " and " + off + " in the offhand: swapped back ("
                    + restores + "/" + MAX_RESTORES + ")" + (awaiting ? "; waiting for the server's report" : ""));
            }
            return;
        }
        if (repair && airplaceMode && off != null && off.equals(material) && expected != null
            && v.slotOf(expected) < 0) {
            // the airplaced block in the offhand and the offhand item nowhere in the cache: no swap of ours does that
            // (local drill gs2: an out-of-band slot update, Grim's answer to a cancelled offhand place, read the server's
            // offhand between the two swaps; the server never corrected the cache). Airplace off at once, and the
            // engine asks the server for the full inventory before anything is judged "no totem"
            changeSeen = false;
            if (!mismatch) {
                mismatch = true;
                staleSeen++;
                event(off + " in the offhand and no " + expected + " anywhere in the inventory cache after an airplace:"
                    + " the cache is stale; airplace off (" + plan(v) + ")");
            }
            return;
        }
        // changed by someone else (or a corrective swap that acted on a stale cache): judged once stable and no swap of
        // ours is in flight; only the offhand item, a totem or nothing (none to put back) is adopted (class javadoc)
        if (!changeSeen || !Objects.equals(changedTo, off)) {
            changeSeen = true;
            changedTo = off;
            changedSince = now;
        } else if (now - changedSince >= SETTLE_TICKS && now - Math.max(lastSwapAt, restoredAt) > SETTLE_TICKS) {
            changeSeen = false;
            String d = desired(off, v);
            if (Objects.equals(d, off)) {
                event("offhand now holds " + name(off) + " (was " + name(expected) + ")");
                expected = off;
                mismatch = false;
            } else if (!mismatch || !Objects.equals(d, expected)) {
                expected = d;
                mismatch = true;
                event("offhand holds " + name(off) + ": not adopted (" + plan(v) + ")");
            }
        }
    }

    /**
     * What the offhand should hold, seeing {@code off} there: {@code off} itself when it is the offhand item or a totem
     * (or with the kill switch off: anything); else the offhand item, or a totem, when the inventory holds one (to be put
     * back); else nothing for an empty offhand, and the offhand item for anything else (never adopted: airplace stays
     * off until the owner sees to it).
     */
    private @Nullable String desired(@Nullable String off, View v) {
        if (!repair || (off != null && allowed(off))) return off;
        if (v.slotOf(offhandItem) >= 0) return offhandItem;
        if (v.slotOf(TOTEM) >= 0) return TOTEM;
        return off == null ? null : offhandItem;
    }

    private boolean allowed(String item) {
        return item.equals(offhandItem) || item.equals(TOTEM);
    }

    /** What happens about a mismatch, for the log and the alerts. */
    private String plan(View v) {
        if (!repair) return "build.offhandRepair is off";
        int s = expected == null ? -1 : v.slotOf(expected);
        if (s >= 0) {
            return "airplace off until " + expected + " is back: moving it from inventory slot " + s
                + " into the offhand at the next safe point";
        }
        if (airplaceMode && now - checkedAt >= CHECK_REPEAT_TICKS) {
            return "no " + offhandItem + " in the inventory cache: airplace off; asking the server for the full"
                + " inventory at the next safe point";
        }
        return "no " + offhandItem + " in the inventory to put back: airplace off; put one in the offhand";
    }

    // ---- offhand confirmation (class javadoc "Offhand confirmation")

    /** A swap of the guard's own went out: hold judging and airplace until the server reports the offhand. */
    private void awaitAnswer(View v) {
        long r = v.offhandReports();
        if (r < 0) return;
        awaiting = true;
        answerMark = r;
        awaitingSince = now;
    }

    /**
     * The engine's repair click ({@link OffhandRepair}) went out: as after a swap of the guard's own, nothing is judged
     * and no airplace sent until the server reports the offhand (or {@link #ANSWER_TICKS} pass).
     */
    public void clickedOffhand(View v) {
        awaitAnswer(v);
        lastSwapAt = now;
    }

    /** Waiting for the server's report of the offhand after a swap of the guard's own or the repair click. */
    public boolean awaitingAnswer() {
        return awaiting;
    }

    /**
     * The engine should ask the server for the full inventory now (the stateId -1 no-op click, at a safe point:
     * {@link OffhandRepair}): airplace is off over an offhand without its item and the cache shows none in the inventory
     * either. That cache may be stale (drill gs2); "no totem" is only believed from the server's own answer, at most once
     * per {@link #CHECK_REPEAT_TICKS}.
     */
    public boolean checkWanted(View v) {
        if (!repair || !airplaceMode || !known || guessed || out || awaiting || restarted || !v.inGame()) return false;
        if (recheckSince >= 0 && now - recheckSince <= RECHECK_TICKS) return true; // a swapped state came back
        return mismatch && expected != null && !Objects.equals(v.offhand(), expected) && v.slotOf(expected) < 0
            && now - checkedAt >= CHECK_REPEAT_TICKS;
    }

    /**
     * The server's full inventory arrived (the cache now shows it): the offhand is judged from it at once. Its item in the
     * offhand ends the episode (the cache was stale); in the inventory, {@link #repairSlot} names it; neither, airplace
     * stays off ("no totem" is now the server's word).
     */
    public void serverChecked(View v) {
        checkedAt = now;
        checks++;
        String off = v.offhand();
        boolean recheck = recheckSince >= 0;
        recheckSince = -1;
        blindSwaps = 0;
        if (!Objects.equals(off, expected) && swapPattern(v)) {
            // the server's own inventory shows the swap kept: the corrective swap now acts on the truth, next tick
            restoredAt = now - SETTLE_TICKS - 1;
            lastSwapAt = Math.min(lastSwapAt, restoredAt);
            event("the server's full inventory shows the offhand item in hotbar slot " + slot + " and " + name(off)
                + " in the offhand: swapping back");
            return;
        }
        if (recheck && !mismatch) {
            changeSeen = false;
            event("the server's full inventory shows " + name(off) + " in the offhand" + (Objects.equals(off, expected)
                ? ": the swapped state was the cache's" : ""));
            if (Objects.equals(off, expected)) return;
        }
        boolean was = mismatch;
        expected = desired(off, v);
        mismatch = !Objects.equals(expected, off);
        changeSeen = false;
        event("the server's full inventory shows " + name(off) + " in the offhand" + (mismatch ? " (" + plan(v) + ")"
            : was ? ": the cache was stale; airplace back on" : ""));
    }

    /** Full-inventory checks answered so far (status, tests). */
    public int checks() {
        return checks;
    }

    private static String name(@Nullable String item) {
        return item == null ? "nothing" : item;
    }

    /**
     * Player slot (9-44) whose stack the engine should swap into the offhand now ({@code build.offhandRepair}, class
     * javadoc "Offhand item"), or -1: a mismatch, the offhand item in the inventory, in AIRPLACE mode, settled (no swap
     * of ours in flight, no airplace echo, no container open).
     */
    public int repairSlot(View v) {
        if (!repair || !airplaceMode || !known || guessed || !mismatch || expected == null || out || restarted
            || !v.inGame() || v.containerOpen() || busy(v) || Objects.equals(v.offhand(), expected)) {
            return -1;
        }
        int s = v.slotOf(expected);
        return s >= ItemIds.MAIN_START && s <= ItemIds.HOTBAR_END ? s : -1;
    }

    /**
     * After each in-game tick, in AIRPLACE mode: airplace turning off raises an alert ({@link #takeAlert}), turning on
     * again a notice; the offhand without the offhand item (or a totem) while the inventory holds one raises the offhand
     * alert ({@link #takeOffhandAlert}) once per episode. Alerts of one kind closer than {@link #ALERT_REPEAT_TICKS} are
     * logged only.
     */
    private void watch(View v) {
        if (!airplaceMode || !v.inGame() || !known || guessed || restarted || out || awaiting) return;
        boolean available = airPlaceAvailable(v);
        if ((wasAvailable == null || wasAvailable) && !available) { // off, or off from the start (a restart)
            String reason = offReason(v);
            if (now - offAlertAt >= ALERT_REPEAT_TICKS) {
                offAlertAt = now;
                alert = reason;
            } else {
                event("airplace off again: " + reason);
            }
        } else if (wasAvailable != null && !wasAvailable && available) {
            notice = "airplace back on: the offhand holds " + name(v.offhand());
        }
        wasAvailable = available;
        String off = v.offhand();
        if (off != null && allowed(off)) {
            itemMissingSeen = false;
        } else if (!itemMissingSeen && !changeSeen && !busy(v)) {
            int s = v.slotOf(offhandItem);
            if (s < 0) s = v.slotOf(TOTEM);
            if (s >= 0) {
                itemMissingSeen = true;
                String body = "the offhand holds " + name(off) + ", not " + offhandItem + ", while inventory slot " + s
                    + " holds " + (v.slotOf(offhandItem) >= 0 ? offhandItem : TOTEM) + (repair && airplaceMode
                    ? "; it goes back into the offhand at the next safe point" : "; build.offhandRepair is off");
                if (now - itemAlertAt >= ALERT_REPEAT_TICKS) {
                    itemAlertAt = now;
                    offhandAlert = body;
                } else {
                    event(body);
                }
            }
        }
    }

    /** Why airplace is off now (for the alert). */
    private String offReason(View v) {
        if (gaveUp) return gaveUpReason != null ? gaveUpReason : "the offhand guard gave up";
        if (mismatch) return "the offhand holds " + name(v.offhand()) + " (" + plan(v) + ")";
        if (expected != null && v.placesBlock(expected)) return "the offhand holds a block, " + expected;
        return "the offhand guard";
    }

    private void finishSwap(View v, AirPlace.Port port) {
        if (v.containerOpen()) return;
        if (record.open()) {
            // the offhand held an item: OffhandGuard's swap record decides
            OffhandGuard.Restore r = record.restore(now, v.offhand(), v.hotbar(slot));
            switch (r.action()) {
                case NONE -> {
                    if (!record.open()) { // back in place
                        out = false;
                        lastSwapAt = now;
                    }
                    return;
                }
                case LOST -> {
                    out = false;
                    gaveUp = true;
                    event(r.item() + " is neither in the offhand nor in hotbar slot " + r.hotbar()
                        + " after an interrupted airplace (moved by someone else?): airplace off, not searched for");
                    gaveUpReason = lastEvent;
                    return;
                }
                case SWAP_BACK -> { }
            }
        } else if (v.offhand() == null || !v.offhand().equals(material)) {
            out = false; // the offhand was empty and holds no airplaced item: nothing to bring back
            lastSwapAt = now;
            return;
        }
        if (v.heldSlot() != slot && !port.select(slot)) return;
        if (port.swapOffhand()) {
            out = false;
            record.swappedBack();
            lastSwapAt = now;
            awaitAnswer(v);
            event("finished an interrupted airplace: swapped the offhand back");
        }
    }

    /**
     * True while the cache shows (or may soon show) an airplace swap: swap 2 still to send, or the offhand item in the
     * hotbar slot of the last airplace and something else in the offhand. Inventory moves, clicks and any other offhand
     * logic wait meanwhile. False once the guard gave up (LOOK placements go on; the item stays in the hotbar).
     */
    public boolean busy(View v) {
        if (out || awaiting) return true;
        if (!known || gaveUp) return false;
        return !Objects.equals(v.offhand(), expected) && swapPattern(v);
    }

    /**
     * Hotbar index (0-8) whose stack the cache shows in the offhand right now (swap 2 still to send, or the swap
     * pattern), -1 otherwise or once the guard gave up. Counts read for that slot ({@code hotbarCount}, the stack guard)
     * and for its item then take the offhand stack: the stack still belongs to that hotbar slot.
     */
    public int swappedSlot(View v) {
        if (gaveUp || slot < 0) return -1;
        return out || (!Objects.equals(v.offhand(), expected) && swapPattern(v)) ? slot : -1;
    }

    /**
     * Airplace allowed now: in game, no container open, settled (offhand = the known offhand item, not busy), the
     * offhand holds nothing that places a block, and the guard has not given up.
     */
    public boolean mayAirPlace(View v) {
        if (gaveUp || out || awaiting || mismatch || !known || !v.inGame() || v.containerOpen()) return false;
        String off = v.offhand();
        if (!Objects.equals(off, expected)) return false;
        return off == null || !v.placesBlock(off);
    }

    /**
     * Airplace is the method for this lane at all (the lane places AIRPLACE cells by LOOK while false): the guard has
     * not given up and the kept offhand item places no block. Unlike {@link #mayAirPlace} it stays true while the
     * guard settles; {@link #busy} holds the clicks meanwhile, so a carpet lane never turns to aim.
     */
    public boolean airPlaceAvailable(View v) {
        if (gaveUp || !known || mismatch) return false;
        return expected == null || !v.placesBlock(expected);
    }

    /** The item the offhand should hold (null: empty), once known. */
    public @Nullable String expectedItem() {
        return expected;
    }

    /** The offhand holds something other than its item, not adopted (class javadoc "Offhand item"): airplace off. */
    public boolean mismatch() {
        return mismatch;
    }

    /** Airplace is off for good in this session ({@link #MAX_RESTORES} corrective swaps did not settle it). */
    public boolean gaveUp() {
        return gaveUp;
    }

    /**
     * Called by {@link AirPlace} just before swap 1 is sent from hotbar slot {@code hotbarSlot} holding {@code item},
     * with {@code offhandItem} in the offhand ({@link #swappedBack} withdraws it if the packet could not be sent).
     */
    void swappedOut(int hotbarSlot, @Nullable String item, @Nullable String offhandItem) {
        record.swappedOut(offhandItem, hotbarSlot, now);
        slot = hotbarSlot;
        material = item;
        out = true;
        lastSwapAt = now;
    }

    /** Called by {@link AirPlace} right after swap 2 was sent. */
    void swappedBack() {
        record.swappedBack();
        out = false;
        lastSwapAt = now;
    }

    /**
     * The swap record ({@link OffhandGuard}: the offhand rule and its airplace exception): open between the two swaps of
     * an airplace, and until an interrupted one is repaired.
     */
    public OffhandGuard record() {
        return record;
    }

    /**
     * The cache shows the airplace swap: the known offhand item sits in the last airplace's hotbar slot and the offhand
     * holds the airplaced item (or nothing, that stack used up, when the known item is not nothing). Anything else in
     * the offhand is someone else's doing and never swapped out.
     */
    private boolean swapPattern(View v) {
        if (slot < 0 || !known || !Objects.equals(v.hotbar(slot), expected)) return false;
        String off = v.offhand();
        return (off != null && off.equals(material)) || (off == null && expected != null);
    }

    /**
     * The cache shows no inventory: nothing in the offhand nor in the hotbar (right after a login, before the server's
     * inventory arrives).
     */
    private static boolean cacheEmpty(View v) {
        if (v.offhand() != null) return false;
        for (int i = 0; i < 9; i++) if (v.hotbar(i) != null) return false;
        return true;
    }

    /** The last notable event (corrective swap, adopted item, gave up), or null; cleared when read. */
    public @Nullable String takeEvent() {
        String e = lastEvent;
        lastEvent = null;
        return e;
    }

    /** The alert for the owner: airplace turned off, and why (class javadoc "Alerts"), or null; cleared when read. */
    public @Nullable String takeAlert() {
        String a = alert;
        alert = null;
        return a;
    }

    /**
     * The alert for the owner: the offhand without its item while the inventory holds one (class javadoc "Alerts"), or
     * null; cleared when read.
     */
    public @Nullable String takeOffhandAlert() {
        String a = offhandAlert;
        offhandAlert = null;
        return a;
    }

    /** Airplace on again after it was off (a routine notice), or null; cleared when read. */
    public @Nullable String takeNotice() {
        String n = notice;
        notice = null;
        return n;
    }

    /** Several events of one tick are joined: none is lost. */
    private void event(String s) {
        lastEvent = lastEvent == null ? "Offhand: " + s : lastEvent + "; " + s;
    }

    /** One line for status and logs. */
    public String describe() {
        return "offhand " + (known && !guessed ? (expected == null ? "empty" : expected) : "unknown")
            + (out ? ", swap 2 pending" : "") + (restores > 0 ? ", " + restores + " corrective swaps" : "")
            + (awaiting ? ", waiting for the server's report of the offhand" : "")
            + (staleSeen > 0 ? ", " + staleSeen + " stale offhand reads" : "")
            + (checks > 0 ? ", " + checks + " full-inventory checks" : "")
            + (mismatch ? ", airplace off (the offhand's item is not there)" : "")
            + (gaveUp ? ", airplace off (gave up)" : "");
    }

    /** Forgets everything (tests). */
    void reset() {
        now = 0;
        known = false;
        guessed = false;
        expected = null;
        slot = -1;
        material = null;
        lastSwapAt = Long.MIN_VALUE / 4;
        restoredAt = Long.MIN_VALUE / 4;
        out = false;
        restores = 0;
        gaveUp = false;
        record.swappedBack();
        wasInGame = false;
        restarted = false;
        resumedAt = 0;
        alert = null;
        changeSeen = false;
        changedTo = null;
        lastEvent = null;
        mismatch = false;
        wasAvailable = null;
        gaveUpReason = null;
        offhandAlert = null;
        notice = null;
        offAlertAt = Long.MIN_VALUE / 4;
        itemAlertAt = Long.MIN_VALUE / 4;
        itemMissingSeen = false;
        awaiting = false;
        answerMark = 0;
        awaitingSince = 0;
        checkedAt = Long.MIN_VALUE / 4;
        checks = 0;
        staleSeen = 0;
        blindSwaps = 0;
        recheckSince = -1;
    }
}
