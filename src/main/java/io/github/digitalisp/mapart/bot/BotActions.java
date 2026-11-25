package io.github.digitalisp.mapart.bot;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Factory for in-game primitives. Every method returns a NEW, not-yet-started {@link BotTask}; nothing
 * happens until the engine ticks it. All tasks:
 * <ul>
 *   <li>path with Baritone with block placing/breaking/inventory use DISABLED while walking (restore the
 *       global pathfinder settings on release) — the platform and base must never be griefed;</li>
 *   <li>respect reach ({@code build.reach}), look at targets before interacting (Grim RotationPlace),
 *       check line of sight, and never interact more than once per tick. Lane placement follows
 *       {@code build.placeMode} instead: airplace (no look, no line of
 *       sight, {@code build.airPlaceRange}) and silent looks ({@code build.rotatePlayer} false);</li>
 *   <li>never break a block listed in {@code site.protectedBlocks}, a machinery type
 *       ({@code Defaults.MACHINERY_BLOCKS}) or a platform floor block, and never break, use, open or place into a
 *       block in {@code site.protectedRegions} ({@code SiteRules});</li>
 *   <li>never enter or stand in a {@code site.noGoZones} box (goals, paths, stand spots, position);</li>
 *   <li>fail (not hang) after a sensible timeout, with a human-readable reason.</li>
 * </ul>
 * Implementations hold the adaptive placement pacer so it persists across lanes. CONTRACT (lead-owned);
 * {@link #mendTool} and {@link #exchangeTool} were added for the tool-wear rule, {@link #pressButton} for the map
 * counter, the {@link WithdrawMode} withdrawal and {@link #planVisits(List, Pos, Consumer)} for the restock bundle,
 * {@link #escape} and the {@code avoid} form of {@link #placeLane} for the setback
 * escape ladder, {@link #keepAlive} and {@link #isBusy} for the keep-alive, the {@code pause} form of
 * {@link #breakBlocks} for mending in the nuke, {@link #restockLegs} for sprinting on the restock legs,
 * {@link #sprintWalks} for sprinting on plain walks, {@link #takeDisconnectDrops} and {@link #recoverDrops} for the
 * stacks a disconnect drops.
 */
public interface BotActions {
    WorldView world();

    InventoryView inventory();

    MapView maps();

    /** Walk until the player's feet are within {@code range} blocks (horizontal) of {@code target}. */
    BotTask walkTo(Pos target, int range);

    /**
     * {@link #walkTo} on foot only, along a route of the simplest moves (level steps, one up, at most three down)
     * walked in short hops: never an elytra flight, never a long fall, and never through a tripwire (the muncher's
     * lane-end triggers). For the walk off the rail field after a lost ride. The default is {@link #walkTo}.
     */
    default BotTask walkOnFoot(Pos target, int range) {
        return walkTo(target, range);
    }

    /** Open a container, report its contents, close it. */
    BotTask scan(Pos container, Consumer<ContainerSnapshot> result);

    /**
     * {@link #scan(Pos, Consumer)} from the stand cell {@code standAt} of a {@link #planVisits} sweep: the bot stays
     * where it is if it already sees the container, else that cell comes first among the stand spots (null: none).
     */
    default BotTask scan(Pos container, @Nullable Pos standAt, Consumer<ContainerSnapshot> result) {
        return scan(container, result);
    }

    /** Take up to {@code wanted} (item id → count) from a container. Partial success is SUCCESS with a problem. */
    BotTask withdraw(Pos container, Map<String, Integer> wanted, Consumer<TransferResult> result);

    /** {@link #withdraw(Pos, Map, Consumer)} from the stand cell of a {@link #planVisits} sweep (null: none). */
    default BotTask withdraw(Pos container, @Nullable Pos standAt, Map<String, Integer> wanted,
                             Consumer<TransferResult> result) {
        return withdraw(container, wanted, result);
    }

    /**
     * {@link #withdraw(Pos, Pos, Map, Consumer)} with the click pattern of {@code mode} ({@link WithdrawMode}: whole
     * stacks, top-up of held partial stacks, or an exact small count). {@code moved} is measured as always. Default:
     * whole stacks (implementations without the other patterns).
     */
    default BotTask withdraw(Pos container, @Nullable Pos standAt, Map<String, Integer> wanted, WithdrawMode mode,
                             Consumer<TransferResult> result) {
        return withdraw(container, standAt, wanted, result);
    }

    /**
     * Airplace can be used for lane placement ({@code build.placeMode} AIRPLACE): the offhand's own item places no block
     * and the offhand guard has not given up. While false the lanes are planned LOOK-wide ({@code laneHalfWidth}), since
     * every cell then needs a look and line of sight. Default: true.
     */
    default boolean airPlaceAvailable() {
        return true;
    }

    /**
     * The offhand should get its item back from the inventory now ({@code build.offhandRepair}): the engine parks the
     * running task and runs {@link #restoreOffhand} at the next safe point.
     * Default: never.
     */
    default boolean offhandRepairWanted() {
        return false;
    }

    /**
     * Part of {@link #offhandRepairWanted}: the offhand lacks its item and the inventory cache shows none, so the repair
     * first asks the server for the full inventory; "No totem" waits for
     * that answer. Default: never.
     */
    default boolean offhandCheckWanted() {
        return false;
    }

    /**
     * Puts the offhand's item back from the inventory: stands still, then one container click (the item's slot swapped
     * with the offhand); succeeds once the offhand holds it. Default: nothing to do (succeeds at once).
     */
    default BotTask restoreOffhand() {
        return new BotTask() {
            @Override
            protected TaskStatus onTick() {
                return TaskStatus.SUCCESS;
            }

            @Override
            public String name() {
                return "restore offhand";
            }
        };
    }

    /**
     * The order in which to open {@code containers} and where to stand for each: a few stand cells that together see
     * them all, walked in one pass along the storage, every container opened from the first of them that sees it
     * (the bot does not walk between containers that share a cell). Reported once to {@code result} (every container
     * exactly once); a container no cell is found for comes last without one. May take a few ticks on a large
     * storage; does not move the bot. The default keeps the given order, without stand cells.
     */
    default BotTask planVisits(List<Pos> containers, Consumer<List<ContainerVisit>> result) {
        List<ContainerVisit> visits = new java.util.ArrayList<>(containers.size());
        for (Pos p : containers) visits.add(new ContainerVisit(p, null));
        return new BotTask() {
            @Override
            protected TaskStatus onTick() {
                result.accept(List.copyOf(visits));
                return TaskStatus.SUCCESS;
            }

            @Override
            public String name() {
                return "planVisits " + containers.size();
            }
        };
    }

    /**
     * {@link #planVisits(List, Consumer)} for a walk that goes on to {@code then} afterwards (a restock: the start of the
     * next lane): the sweep runs in the direction that makes bot → containers → {@code then} shortest, instead of
     * always from the end nearer the bot. {@code then} null = as {@link #planVisits(List, Consumer)}.
     */
    default BotTask planVisits(List<Pos> containers, @Nullable Pos then, Consumer<List<ContainerVisit>> result) {
        return planVisits(containers, result);
    }

    /**
     * Put items matching {@code which} into a container, keeping {@code keep} (item id → count) in the
     * inventory. Never deposits keepItems from config (tools/food/boots) or valuables (totems, gear, shulker boxes),
     * and only from the 36 hotbar + main slots (never the offhand or armour). The engine passes materials only.
     */
    BotTask deposit(Pos container, Predicate<String> which, Map<String, Integer> keep, Consumer<TransferResult> result);

    /**
     * Put exactly one item, the filled map {@code mapId}, into a container (STORE). Nothing else is moved.
     * {@code moved} reports {@code minecraft:filled_map → 1} when it left the inventory.
     */
    BotTask depositMap(Pos container, int mapId, Consumer<TransferResult> result);

    /** The bot's working items that go back to their own chests ({@link #returnWorkingItems}). */
    java.util.Set<String> WORKING_ITEMS = java.util.Set.of("minecraft:map", "minecraft:glass_pane",
        "minecraft:experience_bottle", "minecraft:anvil");

    /**
     * Put the bot's own working items back into their chest: whole stacks of the
     * ids in {@code keep}, keeping that many of each in the inventory, and only {@link #WORKING_ITEMS} (any other id is
     * ignored). {@link #deposit} and {@link #dropItems} never move them; this is their only way out of the inventory
     * besides being used. Never keepItems from config, only from the 36 hotbar + main slots. {@code moved} reports what
     * left the inventory. The default moves nothing (implementations without it keep the items).
     */
    default BotTask returnWorkingItems(Pos container, Map<String, Integer> keep, Consumer<TransferResult> result) {
        return new BotTask() {
            @Override
            protected TaskStatus onTick() {
                result.accept(new TransferResult(Map.of(), null, "not supported"));
                return TaskStatus.SUCCESS;
            }

            @Override
            public String name() {
                return "returnWorkingItems " + container;
            }
        };
    }

    /**
     * Stand on {@code standAt}, turn towards {@code lookAt} with an aim that makes thrown items land in it, and drop
     * whole stacks of items matching {@code which}, keeping {@code keep} (item id → count), paced. Never drops
     * keepItems from config, maps, filled maps, glass panes, XP bottles or valuables (totems, gear, shulker boxes), and
     * only from the 36 hotbar + main slots (never the offhand or armour). The engine passes materials only. Reports
     * item id → count dropped.
     */
    BotTask dropItems(Pos standAt, Pos lookAt, Predicate<String> which, Map<String, Integer> keep,
                      Consumer<Map<String, Integer>> dropped);

    /**
     * {@link #dropItems} from where the bot already stands, within one block of {@code standAt} (the sorter's pickup
     * edge, which is no walkable goal of its own): throws the stacks of {@code which} above {@code keep} at
     * {@code target}. Default: {@link #dropItems}.
     */
    default BotTask throwBack(Pos standAt, Pos target, Predicate<String> which, Map<String, Integer> keep,
                              Consumer<Map<String, Integer>> dropped) {
        return dropItems(standAt, target, which, keep, dropped);
    }

    /**
     * Stand on the sorter's pickup spot {@code standAt} (an edge: the usual edge stops apply) and let the items that fall
     * past it into the inventory, sending nothing. Ends when every item of {@code want} (id → total carried) is carried in
     * that amount, nothing more came for {@code stallTicks}, {@code maxTicks} passed or at most {@code minFree} slots are
     * empty; the result says which. With {@code prime} the bot first walks there (the sorter ticks only near the bot) and
     * on to {@code standAt} as soon as it arrives; the stall timer starts at the edge. Default: nothing
     * to stand on.
     */
    default BotTask standForItems(@Nullable Pos prime, Pos standAt, Map<String, Integer> want,
                                  int stallTicks, int maxTicks, int minFree,
                                  Consumer<io.github.digitalisp.mapart.bot.PickupResult> result) {
        return new BotTask() {
            @Override
            protected TaskStatus onTick() {
                result.accept(new io.github.digitalisp.mapart.bot.PickupResult(Map.of(), "unsupported", Map.of()));
                return TaskStatus.SUCCESS;
            }

            @Override
            public String name() {
                return "standForItems " + standAt;
            }
        };
    }

    /**
     * The current tile's art for the dripstone break-through of failed walks ({@code build.dripstoneBreakThrough}),
     * restock and container walks included; {@code broken} hears of each art cell a recovery broke. Null art: none.
     */
    default void artWalkContext(java.util.function.@Nullable Function<Pos, io.github.digitalisp.mapart.model.@Nullable Placement> art,
                                java.util.function.Consumer<Pos> broken) { }

    /**
     * Walk the lane and place every placement within reach, adaptive pacing (start/min/max interval from
     * config; slow down on misses/rollbacks, speed up on streaks). Skips placements whose position already
     * holds the target state, and placements whose item is not in the inventory (reported via listener).
     * Never places into the cell the bot occupies.
     */
    BotTask placeLane(Lane lane, PlacementListener listener);

    /**
     * {@link #placeLane(Lane, PlacementListener)} that never stands in, or walks to a stand spot through, a cell within
     * one block of {@code avoid} (feet cells where the server kept setting the bot back). The default ignores {@code
     * avoid}.
     */
    default BotTask placeLane(Lane lane, PlacementListener listener, java.util.Set<Pos> avoid) {
        return placeLane(lane, listener);
    }

    /**
     * {@link #placeLane(Lane, PlacementListener, java.util.Set)} that may also place cells of {@code load}, the later
     * lanes of the restock load (their cells still to place), when they come into reach and the in-reach greedy is on
     * ({@code build.placeInReachGreedy}); those are reported to {@code listener} as placed too. The default ignores
     * {@code load}.
     */
    default BotTask placeLane(Lane lane, PlacementListener listener, java.util.Set<Pos> avoid, List<Lane> load) {
        return placeLane(lane, listener, avoid);
    }

    /**
     * A VERIFY repair lane: {@link #placeLane(Lane, PlacementListener, java.util.Set, List)} ({@code load} null: the
     * 3-argument form) where {@code art} gives the tile's placement per cell (null where the art has none), so the lane
     * may open a corridor to a cell walled in by blocks the pathfinder can neither walk through nor onto and place the
     * blocks it broke again ({@code build.verifyBreakIn}). The
     * default ignores {@code art}.
     */
    default BotTask placeLane(Lane lane, PlacementListener listener, java.util.Set<Pos> avoid, @Nullable List<Lane> load,
                              java.util.function.Function<Pos, io.github.digitalisp.mapart.model.@Nullable Placement> art) {
        return load == null ? placeLane(lane, listener, avoid) : placeLane(lane, listener, avoid, load);
    }

    /** VERIFY's repair load, including cells available to the seal-out refill independently of in-reach greedy. */
    default BotTask placeRepairLane(Lane lane, PlacementListener listener, java.util.Set<Pos> avoid, List<Lane> load,
                                    java.util.function.Function<Pos, io.github.digitalisp.mapart.model.@Nullable Placement> art) {
        return placeLane(lane, listener, avoid, load, art);
    }

    /**
     * One rung of the setback escape ladder, run by the engine after a setback
     * back-off and before the phase carries on: stop, resync the bot's movement state, wait for the server's block
     * updates, then step to a nearby open floor cell (server-confirmed blocks only, no climbing, away from
     * {@link EscapeRequest#avoid}). SUCCESS once there; FAILED (with the reason) when no such cell can be reached: the
     * engine logs it and carries on, the ladder goes on with the next burst. The default stands still for a second.
     */
    default BotTask escape(EscapeRequest request) {
        return waitTicks(20);
    }

    /**
     * Re-sync with the server around {@code at} (the feet cell the server kept setting the bot back to): stop, resync
     * the bot's movement state and end any open block
     * prediction, then re-request the walking surface of the cells around it from the server (per block a look at its
     * top face, then an off-hand use-item-on with the look held; empty or totem off-hand only: the server answers each
     * with the clicked block and the one above it and changes nothing), wait for the answers and log what changed.
     * Never moves the bot, never places or breaks, never touches the inventory. SUCCESS when done (also when it could click nothing); FAILED only
     * when it could not run (a menu open). The default stands still for a tick.
     */
    default BotTask resync(Pos at) {
        return waitTicks(1);
    }

    /**
     * Break the given blocks (nuke full blocks before a clear, or wrong blocks during repair), choosing the
     * best tool from the inventory. Order is the caller's; implementation may walk a nearest-next route.
     * Reports each position whose block became air (or was already air).
     * <p>Tools never break: a block that breaks (near-)instantly by hand (carpets) is broken with an empty hand or a
     * non-tool item; a tool is used only where it is faster than the hand, and never one with
     * {@code build.toolSafetyFloor} uses left or fewer (another of the same kind above the floor instead). When every
     * tool that would matter for the next block is at the floor, the task stops at once and FAILS with a reason
     * containing {@link #TOOL_AT_FLOOR}.
     */
    BotTask breakBlocks(List<Pos> targets, Consumer<Pos> broken);

    /**
     * {@link #breakBlocks(List, Consumer)} that may stop early for the engine's upkeep: at each lane end of a FAST
     * sweep the task asks {@code pause}; a non-null answer (why) makes it
     * finish the blocks in reach and the ones in flight, then end FAILED with a reason containing
     * {@link #BREAK_PAUSED} and the answer, standing at the lane end. {@code pause} is called on the tick thread, at
     * most once per lane end. The default (and SINGLE, DOUBLE) ignores it.
     */
    default BotTask breakBlocks(List<Pos> targets, Consumer<Pos> broken,
                                java.util.function.Supplier<@Nullable String> pause) {
        return breakBlocks(targets, broken);
    }

    /**
     * {@link #breakBlocks(List, Consumer, java.util.function.Supplier)} with {@code pause} optional (null: none), and
     * {@code artCleared}: the art layer is cleared afterwards (the NUKE), so blocks that are no target may be broken
     * where they wall the bot in or off as the clear would remove them anyway; otherwise (VERIFY, repairs) such a
     * block is art, broken only where it must be and placed again by VERIFY.
     */
    default BotTask breakBlocks(List<Pos> targets, Consumer<Pos> broken,
                                java.util.function.@Nullable Supplier<@Nullable String> pause, boolean artCleared) {
        return pause == null ? breakBlocks(targets, broken) : breakBlocks(targets, broken, pause);
    }

    /** Marker in the failure reason of a {@link #breakBlocks} task that stopped because its tool is at the floor. */
    String TOOL_AT_FLOOR = "tool at the safety floor";

    /** Marker in the failure reason of a {@link #breakBlocks} task that stopped at a lane end for upkeep. */
    String BREAK_PAUSED = "paused at a lane end";

    /** Place a single block (e.g. replacement anvil) at {@code pos} against any solid neighbour face. */
    BotTask placeBlock(Pos pos, String itemId);

    /** Right-click a block once (button, lever) — standing at {@code standAt} if non-null. */
    BotTask useBlock(Pos pos, Pos standAt);

    /**
     * Press a button once, outside any clear (the base's map counter, {@code site.mapCounter}): like {@link #useBlock},
     * but only a block whose id ends in {@code _button} is clicked (anything else FAILS), the bot may stand in a
     * platform's clear sweep (never on its art layer), it does not click while the button is still pressed
     * ({@code powered=true}: it waits for it to pop, then clicks; FAILED if it stays pressed for 5 s), and it succeeds
     * only once the button shows {@code powered=true} after its own click. Default: {@link #useBlock}.
     */
    default BotTask pressButton(Pos pos, @Nullable Pos standAt) {
        return useBlock(pos, standAt);
    }

    /**
     * Stand at {@code standAt}, use an empty map ("minecraft:map") to create a filled map, then keep it in the
     * main hand for {@code holdTicks} so the server fills in the pixels. Reports the new filled map's id.
     */
    BotTask createMap(Pos standAt, int holdTicks, Consumer<Integer> mapId);

    /** Stand at {@code standAt} holding filled map {@code mapId} for {@code ticks} (refresh pixels after repairs). */
    BotTask holdMap(Pos standAt, int mapId, int ticks);

    /**
     * {@link #createMap(Pos, int, Consumer)} with {@link MapHold} options: a near stand tried before the station
     * {@code standAt}, and a hold that ends once the map shows the art. Default: the options are ignored.
     */
    default BotTask createMap(Pos standAt, int holdTicks, Consumer<Integer> mapId, MapHold hold) {
        return createMap(standAt, holdTicks, mapId);
    }

    /** {@link #holdMap(Pos, int, int)} with {@link MapHold} options. Default: the options are ignored. */
    default BotTask holdMap(Pos standAt, int mapId, int ticks, MapHold hold) {
        return holdMap(standAt, mapId, ticks);
    }

    /** Cartography table: filled map {@code mapId} + glass pane → locked map. Reports the locked map's id. */
    BotTask lockMap(Pos cartographyTable, int mapId, Consumer<Integer> lockedMapId);

    /** Anvil: rename filled map {@code mapId} to {@code name} (needs ≥ 1 XP level). */
    BotTask renameMap(Pos anvil, int mapId, String name);

    /** Throw {@code bottles} bottles o' enchanting at the bot's feet and wait for the orbs to be absorbed. */
    BotTask throwXp(int bottles);

    /**
     * Mend carried {@code toolId} tools that have Mending, standing still where the bot is: hold the most worn one
     * below {@code targetFraction} of its maximum durability in the main hand (so Mending repairs it), and throw at
     * most {@code bottles} XP bottles from the hotbar straight down, one at a time, switching back to the tool before
     * the orbs land, until every such tool reaches {@code targetFraction} or the bottles run out. The offhand is never
     * touched. SUCCESS when done or out of bottles after at least one throw; FAILED when no bottle or no such tool is
     * carried.
     */
    BotTask mendTool(String toolId, int bottles, double targetFraction);

    /**
     * At a tool chest: unless a tool of {@code kind} ({@link InventoryView#toolKind}) above {@code floor} uses is
     * carried, take the best one above the floor from the chest (Mending first, then most uses left), and put back
     * every carried tool of that kind with at most {@code floor} uses left whose id is {@code returnable} (it came
     * from or belongs in the tool chest; any other worn tool is the owner's and stays), but only when one above the floor is
     * carried or was just taken (otherwise the worn one stays for a later mend). Never the offhand or armour. Reports
     * the tools taken in {@code moved}. Unlike {@link #deposit} this does move keepItems (only worn tools of that kind).
     */
    BotTask exchangeTool(Pos chest, String kind, int floor, Predicate<String> returnable, Consumer<TransferResult> result);

    /**
     * At the rocket chest ({@code build.elytraLegs}): {@link #exchangeTool} for
     * elytras. Unless an elytra above {@code floor} uses is carried (one worn in the chest slot counts, whatever its
     * uses: never two), take the best one above the floor (Mending, then Unbreaking, then most uses), and put back each
     * carried elytra at or below the floor when one above it is carried or was just taken (the spare comes out, the
     * worn-out one goes in). Never the offhand or armour. {@code moved} reports the elytra taken.
     */
    default BotTask exchangeElytra(Pos chest, int floor, Consumer<TransferResult> result) {
        return unsupported("exchangeElytra");
    }

    /** Equip an item into its EMPTY armor slot (boots); never moves a worn piece out (FAILED instead). */
    BotTask equip(String itemId);

    /** Take the worn boots off into an empty inventory slot (worn-out boots never break). */
    BotTask unequipBoots();

    /**
     * The durability guard: take the worn armour piece in {@code armorSlot} ({@link InventoryView.Armor#HEAD} ..
     * {@link InventoryView.Armor#FEET}) off into an empty hotbar or main inventory slot, so it never breaks. FAILED
     * when no slot is free (the reason contains {@link #NO_FREE_SLOT}) or nothing is worn there. Additive: the default
     * takes the boots off and refuses the other slots.
     */
    default BotTask unequipArmor(int armorSlot) {
        if (armorSlot == InventoryView.Armor.FEET) return unequipBoots();
        return new BotTask() {
            @Override
            protected TaskStatus onTick() {
                return fail("armor slot " + armorSlot + " cannot be taken off here");
            }

            @Override
            public String name() {
                return "take off armor slot " + armorSlot;
            }
        };
    }

    /** What {@link #unequipArmor} fails with when the inventory has no empty slot for the piece. */
    String NO_FREE_SLOT = "no free inventory slot";
    /** {@link #boardCart}'s failure with no minecart on the start rail and none in the inventory. */
    String NO_CART_CARRIED = "no minecart carried";

    /**
     * {@link #equip} of the piece in player slot {@code fromSlot} (9..44), not just the first stack of its id: of two
     * boots with the same id, the guard wears the one that can be mended. Additive: the default is {@link #equip}.
     */
    default BotTask equip(String itemId, int fromSlot) {
        return equip(itemId);
    }

    /** Idle for a number of ticks (still counts as bot activity, does not move). */
    BotTask waitTicks(int ticks);

    /**
     * Step off a raised stand (soul sand: the button cross, feet at the art layer + 6) and drop to {@code landing}, a
     * floor cell: the walk may fall up to {@code maxDrop} blocks (ZenithProxy's pathfinder allows 3 by default; it is
     * raised for this walk only and restored when the task ends). Fall damage is the caller's concern: it checks health
     * first. Default: {@link #walkTo} within 1 block (no drop allowance, so it takes the stairs).
     */
    default BotTask hopDown(Pos landing, int maxDrop) {
        return walkTo(landing, 1);
    }

    /**
     * Fight the mobs of {@code area}: hold the best weapon above {@code weaponFloor}
     * uses (sword, then axe; else a bare hand, never a worn weapon), let ZenithProxy's KillAura (switched on by the
     * caller) attack what is in reach (its weapon switching off for the fight), and with {@code area.chase()} walk
     * within reach of the nearest one out of reach (Baritone, no-go zones kept). SUCCESS once no mob of the area has
     * been seen for {@code quietTicks} and the previously held hotbar slot is selected again. Never drops anything,
     * never touches the offhand. Default: waits {@code quietTicks} (no fight).
     */
    default BotTask fight(FightArea area, int weaponFloor, int quietTicks) {
        return waitTicks(quietTicks);
    }

    /**
     * {@link #fight(FightArea, int, int)} that also ends when only mobs it cannot walk to are left and no listed mob has
     * hit the bot for a while ({@code hits}; live 2026-10-12: a pillager in the water channel under the moss walkway
     * stopped work for 120 s at a time): it reports those mobs to {@code unreachable} (the engine ignores them until a
     * mob hits the bot again) and succeeds. Hit again by a mob while only such mobs are left, it fails (standing there is
     * no defence). It goes for the mobs that hit the bot ({@link MobHits#attackers}) before the others. A fall or any
     * other damage without a listed mob behind it is no hit. Default: the plain fight.
     */
    default BotTask fight(FightArea area, int weaponFloor, int quietTicks, Consumer<List<WorldView.Mob>> unreachable,
                          MobHits hits) {
        return fight(area, weaponFloor, quietTicks);
    }

    /**
     * Gets away from mobs that hurt the bot when it cannot fight them (leave
     * when hurt): back along the bot's own recent track (cells it stood on) to the newest cell 24 or more blocks from
     * every one of {@code threats}, else the farthest one that gains 4 blocks; with no such cell, the setback escape's
     * widest ring around where it stands. Walk-only (no place, break or inventory), no-go zones kept. Default: stands
     * still for a second.
     */
    default BotTask retreat(List<WorldView.Mob> threats) {
        return waitTicks(20);
    }

    /**
     * The engine entered a restock ({@code on}; RESTOCK and the BUILD round right after it) or left it: the walks from
     * now on are restock legs (to the drop point, along the sorter, back to the next lane's start) until the first lane
     * start is reached or this is called with false. With {@code build.sprintRestockLegs} they sprint.
     * Default: nothing.
     */
    default void restockLegs(boolean on) { }

    /**
     * Whether the engine's phase lets plain walks sprint ({@code build.sprintWalks}): false while the clear runs (CLEAR
     * once a press went out or on a retry, all of WAIT_CLEAR). The engine
     * sets it every tick and at each phase change. Default: nothing.
     */
    default void sprintWalks(boolean allowed) { }

    /**
     * The gate every menu-opening task asks before its open click ({@link MenuGate}: the coordination's chest and station
     * claims). Set once by the engine; until then {@link MenuGate#OPEN}. Default: nothing.
     */
    default void menuGate(MenuGate gate) { }

    /**
     * The keep-alive while the engine waits, two ticks, standing still: a real head
     * turn of {@code yawDelta} degrees (pitch and keys unchanged) through ZenithProxy's input path, which its Bot sends
     * with its own movement packet of that tick (never a silent look, never an extra movement packet), then, once that
     * turn was executed, one arm swing (the normal swing packet). No keys, no click on a block, no inventory action.
     * FAILED with nothing more sent while a container menu is open, or when another module owned the input (AutoEat
     * eating, say). The default does nothing and succeeds.
     */
    default BotTask keepAlive(float yawDelta) {
        return new BotTask() {
            @Override
            protected TaskStatus onTick() {
                return TaskStatus.SUCCESS;
            }

            @Override
            public String name() {
                return "keepAlive";
            }
        };
    }

    /**
     * The bot layer has something going on outside the engine's tasks that a keep-alive must not disturb: a container
     * menu open, Baritone walking (a path someone else asked for), a delayed block the server still breaks. Default:
     * false.
     */
    default boolean isBusy() {
        return false;
    }

    /**
     * Where the edge guard kept taking a walk's jump away beside a hole within the last minutes, so that the walk handed
     * back ("on the way to the map station"), or null: the "No progress" alert names it. Default: null.
     */
    default @Nullable String edgeGuardTrouble() {
        return null;
    }

    /**
     * Stand still where the bot is and eat one food item: the first of {@code foods} in the hotbar or main inventory,
     * and nothing else (never another food, never golden apples or other valuables). Only after a few ticks without
     * movement keys: select the food's hotbar slot, or (food not in the hotbar) one inventory click moving it to hotbar
     * slot 0; then use it without an arm swing and keep still, sending nothing else, until the server consumed it.
     * SUCCESS without eating when the hunger bar is full; FAILED when none of {@code foods} is carried or the server
     * does not consume it.
     */
    BotTask eat(List<String> foods);

    // ---- the colour-suppression ride (docs/COLOR_SUPPRESSION.md) ----

    /**
     * Walk to {@code standAt}, put a carried minecart on the start rail {@code rail} unless one stands there, and get
     * in (vanilla clicks: rotation first, then a use-item-on on the rail, then interact on the cart). SUCCESS once the
     * bot rides a minecart on the rail; its entity id goes to {@code cartId}. The default fails.
     */
    default BotTask boardCart(Pos rail, Pos standAt, Consumer<Integer> cartId) {
        return unsupported("boardCart");
    }

    /**
     * From the seat: map {@code mapId} in the main hand for the whole task; one press of {@code button} (null = none);
     * the push west if the cart does not start by itself (forward held looking along {@code pushYaw}); then ride,
     * watching {@code watch}, until the cart stands still at {@code brake}. FAILED on leaving the cart, low health, a
     * stall or the time limit, with the reason and the cart's position. Never parks. {@code pressed} runs once the button
     * shows pressed. The default fails.
     */
    default BotTask ride(@Nullable Pos button, Pos brake, int mapId, float pushYaw, RideWatch watch, Runnable pressed) {
        return unsupported("ride");
    }

    /**
     * Asked by {@link #ride} every tick the cart has stood still for a while: true ends the ride at once with SUCCESS,
     * the bot still seated with the map in hand (the caller knows why it asked). {@code stillTicks}: how long the cart
     * has not moved.
     */
    interface RideStop {
        boolean stopNow(WorldView.Thing cart, long stillTicks);
    }

    /**
     * Options of {@link #ride(Pos, Pos, int, float, RideWatch, Runnable, RideOptions)} (docs/COLOR_SUPPRESSION.md).
     *
     * @param stop          asked while the cart stands still; null = the ride ends at the brake only
     * @param resumePushYaw the cart was put back on a lane after the launch and does not move by itself: forward is
     *                      held looking along this yaw until it moved a block (no button is pressed); null = a ride
     *                      launched before goes on as the machines move the cart
     */
    record RideOptions(@Nullable RideStop stop, @Nullable Float resumePushYaw) { }

    /** {@link #ride(Pos, Pos, int, float, RideWatch, Runnable)} with {@link RideOptions}. Default: the options are ignored. */
    default BotTask ride(@Nullable Pos button, Pos brake, int mapId, float pushYaw, RideWatch watch, Runnable pressed,
                         RideOptions opts) {
        return ride(button, brake, mapId, pushYaw, watch, pressed);
    }

    /**
     * {@link #holdMap(Pos, int, int)} on exactly {@code standAt}: the bot walks into that cell and nowhere else (no
     * ring around it, no near cell), and only there the map goes into the main hand. For a stand whose block column
     * is the point (a paused ride's lane end: the map is to update from the seat's own column). FAILED when the cell
     * cannot be walked into. Default: {@link #holdMap(Pos, int, int)}.
     */
    default BotTask holdMapExactly(Pos standAt, int mapId, int ticks) {
        return holdMap(standAt, mapId, ticks);
    }

    /**
     * After a fall: swims or walks the bot out to the nearest dry cell at the
     * floor's level ({@link FallRescue}); SUCCESS once it stands there, FAILED (with why) when it cannot within the
     * request's ticks. Never disconnects. Default: not supported (FAILED at once).
     */
    default BotTask rescueFall(FallRescue request) {
        return unsupported("rescueFall");
    }

    /**
     * The newest walk that failed boxed in after a drop it cannot climb back (the walk's boxed-in check), if the bot
     * still stands where it was boxed in; taken once (null again until another walk finds the same). The engine rescues
     * it as a fall. Default: none.
     */
    default @Nullable BoxedInDrop takeBoxedInDrop() {
        return null;
    }

    default BotTask leaveCart() {
        return unsupported("leaveCart");
    }

    /**
     * The cart put back after the ride ({@code ride.reuseCart}): walk to {@code standAt} and put a carried minecart on
     * {@code rail} without getting in. SUCCESS once a minecart stands still on the rail; {@code placed} hears true when
     * the bot put it there, false when one stood there already (nothing is clicked, the carried one is kept). FAILED
     * when none is carried or none appears. The default fails.
     */
    default BotTask placeCart(Pos rail, Pos standAt, Consumer<Boolean> placed) {
        return unsupported("placeCart");
    }

    /**
     * {@link #boardCart} for a cart that is the platform's own (the preload lap, {@code ride.preload}): walk to
     * {@code standAt} and get into the minecart standing on {@code rail}. Never places one: no cart on the rail FAILS.
     * The default fails.
     */
    default BotTask boardOwnCart(Pos rail, Pos standAt, Consumer<Integer> cartId) {
        return unsupported("boardOwnCart");
    }

    /**
     * The preload lap from the seat (docs/COLOR_SUPPRESSION.md): no filled map in the main hand for the
     * whole task (a slot without one is selected first, so the map does not update on the lap); one press of
     * {@code button} (null = pressed before); with {@code pushYaw} the push if the cart does not start by itself, without
     * it a cart that does not start FAILS; then ride, watching {@code watch}, until the cart stands still at {@code end}
     * after it left (a loop ends on its start rail). With {@code button} null a cart already standing still at
     * {@code end} is the lap's end. FAILED like {@link #ride}. {@code pressed} runs once the button shows pressed. The
     * default fails.
     */
    default BotTask preloadRide(@Nullable Pos button, Pos end, @Nullable Float pushYaw, RideWatch watch, Runnable pressed) {
        return unsupported("preloadRide");
    }

    /** Break the minecart within 2.5 blocks of {@code near} and pick it up; SUCCESS at once when none. The default fails. */
    default BotTask breakCart(Pos near) {
        return unsupported("breakCart");
    }

    /**
     * What the last disconnect may have dropped ({@link DisconnectDrop}),
     * taken once: the next call returns nothing until the next disconnect. The default knows of none.
     */
    default List<DisconnectDrop> takeDisconnectDrops() {
        return List.of();
    }

    /**
     * Pick up what a disconnect dropped: for each drop not back in the inventory already
     * ({@link DisconnectDrop#inventory}), the stacks of its item on the ground within {@code radius} blocks of where it
     * dropped, one walk to each, nearest first; nothing when the bot is farther than {@code radius} from there.
     * {@code result} gets one outcome per drop when the task ends (SUCCESS whatever was found). The default picks
     * nothing up.
     */
    default BotTask recoverDrops(List<DisconnectDrop> drops, double radius,
                                 Consumer<List<DisconnectDrop.Outcome>> result) {
        return new BotTask() {
            @Override
            protected TaskStatus onTick() {
                result.accept(drops.stream().map(d -> new DisconnectDrop.Outcome(d, 0, "not supported here", false)).toList());
                return TaskStatus.SUCCESS;
            }

            @Override
            public String name() {
                return "recoverDrops";
            }
        };
    }

    private static BotTask unsupported(String what) {
        return new BotTask() {
            @Override
            protected TaskStatus onTick() {
                return fail(what + " is not supported here");
            }

            @Override
            public String name() {
                return what;
            }
        };
    }
}
