package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.bot.BotActions;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.bot.ContainerSnapshot;
import io.github.digitalisp.mapart.bot.ContainerVisit;
import io.github.digitalisp.mapart.bot.EscapeRequest;
import io.github.digitalisp.mapart.bot.InventoryView;
import io.github.digitalisp.mapart.bot.MenuGate;
import io.github.digitalisp.mapart.bot.MapView;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.TransferResult;
import io.github.digitalisp.mapart.bot.WithdrawMode;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.bot.impl.AdaptivePacer;
import io.github.digitalisp.mapart.bot.impl.Aim;
import io.github.digitalisp.mapart.bot.impl.Approach;
import io.github.digitalisp.mapart.bot.impl.ContainerRun;
import io.github.digitalisp.mapart.bot.impl.KeepAliveRun;
import io.github.digitalisp.mapart.bot.impl.LaneRun;
import io.github.digitalisp.mapart.bot.impl.PlaceMode;
import io.github.digitalisp.mapart.bot.impl.PlaceRun;
import io.github.digitalisp.mapart.bot.impl.StandSpots;
import io.github.digitalisp.mapart.bot.impl.SweepPlanner;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner;
import io.github.digitalisp.mapart.bot.impl.UseRun;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.SimFakes.FakeCatalog;
import io.github.digitalisp.mapart.engine.SimFakes.FakeInventory;
import io.github.digitalisp.mapart.engine.SimFakes.FakeMaps;
import io.github.digitalisp.mapart.engine.SimFakes.FakeWorld;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.ContainerSim;
import io.github.digitalisp.mapart.testutil.LaneSim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Bot primitives acting directly on the fake world/inventory/maps/containers. Tasks complete after a
 * tick or two; lanes and break lists progress a batch per tick so cancellation can land mid-lane.
 */
final class FakeBot implements BotActions {
    static final class Container {
        final Map<String, Integer> items = new TreeMap<>();
        final List<MapItem> maps = new ArrayList<>();
        /** Worn tools put back by exchangeTool (never taken again). */
        final List<FakeInventory.FakeTool> wornTools = new ArrayList<>();
        int slots = 27;

        int freeSlots() {
            int used = maps.size() + wornTools.size();
            for (int v : items.values()) used += (v + 63) / 64;
            return Math.max(0, slots - used);
        }
    }

    record MapItem(int id, String name, boolean locked) { }

    final FakeWorld world;
    final FakeInventory inv;
    final FakeMaps maps;
    final FakeCatalog catalog;
    final Map<Pos, Container> containers = new HashMap<>();
    final List<String> history = new ArrayList<>();
    final Map<String, Integer> failNext = new HashMap<>();
    final Map<Pos, Integer> placeCounts = new HashMap<>();
    final Map<Integer, String> mapNames;
    /** Everything dropped through dropItems (item id → count). */
    final Map<String, Integer> droppedItems = new TreeMap<>();
    /** Where each dropItems task stood, in order (including injected failures). */
    final List<Pos> dropStands = new ArrayList<>();
    final Map<Pos, Runnable> useHandlers = new HashMap<>();
    /** Every lane handed to placeLane, in order. */
    final List<Lane> lanesRun = new ArrayList<>();
    /** Containers the bot cannot reach: scans of them fail. */
    final java.util.Set<Pos> unreachable = new java.util.HashSet<>();
    final List<long[]> scheduledAt = new ArrayList<>();
    final List<Runnable> scheduled = new ArrayList<>();
    int placementsPerTick = 64;
    /** Withdraw whole stacks like the production bot (shift-clicks); false = exactly the wanted counts. */
    boolean wholeStackWithdrawals;
    int placed;
    int broken;
    int createdMaps;
    int heldMaps;
    int bottlesThrown;
    long now;
    /** Return the block actually placed for a placement (wrong-block injection); null = place the target. */
    UnaryOperator<Placement> placeOverride;
    /** Report cells a lane finds placed already as placed, as LaneRun does (a re-run after a stop). */
    boolean reportPlacedCells;
    /** Every n-th placement is reported as placed from a stand spot ({@code placedFromStandSpot}); 0 = none. */
    int standSpotEvery;
    /**
     * Skip reason for a placement the lane cannot place although its item is in the inventory (reach, line of sight,
     * standing position), like PlaceLaneTask's "not reachable from the lane"; null = placeable. The item is kept.
     */
    java.util.function.Function<Placement, String> unplaceable;
    /**
     * Every lane ends at once with nothing placed or skipped, its cells left for the next round, as LaneRun's watchdog
     * ends a lane the bot cannot get on (live 2026-10-19, wedged in a dripstone cell).
     */
    boolean lanesStandStill;
    /** A target the break task cannot reach (left standing, the task FAILS after the others); null: none. */
    java.util.function.Predicate<Pos> unbreakable;
    Runnable beforeCreateMap;
    /** Runs once at the end of the next holdMap, before the map is drawn. */
    Runnable beforeHoldMap;
    /**
     * Every this many ticks the unlocked map in hand is drawn again while the bot stands where it was last created
     * or held, like a vanilla map that keeps updating in the hand (0 = off: maps are drawn only by createMap/holdMap).
     */
    int heldMapRefreshTicks;
    /** Where the map in hand was last created or held. */
    Pos heldAt;
    /** The fill options of the last createMap/holdMap with {@link io.github.digitalisp.mapart.bot.MapHold} (null: none). */
    io.github.digitalisp.mapart.bot.MapHold lastHold;
    /** A near stand the fill cannot walk to: the fill falls back to the station, like MapTask. */
    boolean nearStandUnreachable;
    /** Lamp that must be unlit while placing (moss item collection indicator). */
    Pos collectionLamp;
    boolean placedWithToggleOn;
    BotTask lastTask;
    /** The engine's config (tool safety floor). */
    MapArtConfig config;

    // ---- tools ----
    /** Broken blocks by id: with the empty hand / with a tool. */
    final Map<String, Integer> brokenByHand = new TreeMap<>();
    final Map<String, Integer> brokenWithTool = new TreeMap<>();
    /** Fewest uses any tool had left right after a break with it. */
    int lowestToolUses = Integer.MAX_VALUE;
    /** Breaks done with a tool that was at or below the floor before the break (must stay 0). */
    int breaksAtFloor;
    /** Break tasks that stopped because the pickaxe was at the floor. */
    int toolStops;
    int mends;
    int mendBottles;
    /** Phase of the engine at every mend, in order. */
    final List<Phase> mendPhases = new ArrayList<>();
    /** Mend ticks while another (walking/placing/breaking) task was still unfinished. */
    int mendTicksBesideOtherTask;
    /** Bottles of a mend whose XP went to damaged boots instead (orbs picked up with the boots, not the tool). */
    boolean bootsTakeEveryOtherBottle;

    // ---- armour wear (the durability guard) ----
    /** Soul Speed wear: the worn boots lose one use every this many ticks while a walking task runs; 0 = none. */
    int bootsWearTicks;
    /** Mob hits: every worn piece loses one use every this many ticks while a task runs; 0 = none. */
    int armorWearTicks;
    /** Fewest uses the worn boots had left, and whether any worn piece reached 0 (broke). */
    int lowestBootsUses = Integer.MAX_VALUE;
    int lowestArmorUses = Integer.MAX_VALUE;
    boolean armorBroke;
    /** Pieces taken off (unequipArmor, unequipBoots) and put on (equip). */
    int armorOff;
    int armorOn;
    /** Ticks a walking task ran with the boots at or below their hard floor uses ({@link #bootsFloorUses}). */
    int bootsFloorUses = -1;
    int ticksWornAtFloor;
    /** A tool mend's XP goes to damaged worn Mending armour first (all of it while one is damaged), as live 10-17. */
    boolean armorTakesAllMendXp;
    /** The next this many tool mends raise nothing (a Mending that "does not repair" for a while). */
    int toolMendsGainNothing;
    /** Lanes and break tasks refuse to stop at a safe point (requestPark false): the guard must cut them short. */
    boolean tasksRefusePark;
    int exchanges;
    int toolsReturned;
    private BotTask lastWork;
    /** A break with a pickaxe costs a use only every this many breaks (Unbreaking III: about 4); 1 = every break. */
    int breaksPerUse = 1;
    private long pickaxeBreaks;
    /** Every this many broken blocks one item of the block is picked up while the inventory has room; 0 = never. */
    int pickupEvery;
    private long pickupCounter;
    /** Items picked up while breaking, and pickups refused because the inventory was full. */
    final Map<String, Integer> pickedUp = new TreeMap<>();
    int pickupsRefused;
    /** Pickups refused while the engine was in each phase. */
    final Map<Phase, Integer> pickupsRefusedIn = new java.util.EnumMap<>(Phase.class);
    /** A FAST sweep's lane end every this many broken blocks of a break task (the pause question); 0 = none. */
    int laneBlocks;
    /**
     * Phase timing (mending during the clear): a break task breaks one target every this many ticks instead of 64 per
     * tick; 0 = the fast default.
     */
    int breakTicksPerBlock;
    /** Phase timing: a mend task lasts this many ticks per bottle it may throw (about 8 live); 0 = 2 ticks in all. */
    int mendTicksPerBottle;
    /** Lane ends asked, and the uses left of the most worn pickaxe at every pause there. */
    int laneEndsAsked;
    final List<Integer> pausedAtUses = new ArrayList<>();
    /** Every withdrawal: container and the engine's phase then. */
    final List<Map.Entry<Pos, Phase>> withdrawLog = new ArrayList<>();
    /** Runs at each pause at a lane end (a test shapes the inventory there). */
    Runnable onPause;
    /**
     * Per withdrawal in the NUKE, in order: empty slots the walk there fills with picked-up drops (a stack of
     * {@link #walkDrop} each) before the chest opens.
     */
    final java.util.ArrayDeque<Integer> pickupsBeforeWithdraw = new java.util.ArrayDeque<>();
    String walkDrop = "minecraft:white_terracotta";

    // ---- hunger ----
    /** Hunger points per food item. */
    static final Map<String, Integer> FOOD_POINTS = Map.of(
        "minecraft:golden_carrot", 6, "minecraft:bread", 5, "minecraft:cooked_beef", 8);
    static final int EAT_TICKS = 3;
    /** One hunger point is lost every this many placements (0 = no hunger drain). */
    int placementsPerHunger;
    int lowestFoodLevel = 20;
    int eats;
    /** Every item eaten, in order. */
    final List<String> eatenIds = new ArrayList<>();
    /** Phase of the engine at every eat, in order. */
    final List<Phase> eatPhases = new ArrayList<>();
    /** Eat ticks while another (walking/placing/clicking) task was still unfinished. */
    int eatTicksBesideOtherTask;
    /** Ticks of non-eat tasks while ZenithProxy's AutoEat module was enabled. */
    int ticksWithAutoEatOn;
    /** Eats while a lane, break or walk task was parked (stopped at a safe point for it). */
    int eatsWhileParked;
    java.util.function.BooleanSupplier autoEatOn = () -> false;
    /**
     * Simulates ZenithProxy's AutoEat while its module is on ({@link #autoEatOn}): one food item at hunger or health at
     * or below its thresholds with the bar not full, then busy for 32 ticks. Off by default.
     */
    boolean autoEatEats;
    int autoEatHungerThreshold = 10;
    float autoEatHealthThreshold = 10f;
    int autoEats;
    /** AutoEat ate while an engine task (not parked) was unfinished: the two would fight over the inventory. */
    int autoEatsBesideTask;
    private long autoEatBusyUntil;

    // ---- hunger over time, health ----
    /** One hunger point is lost every this many ticks up to tick {@link #hungerUntil} (0 = off): a hunger effect. */
    int ticksPerHungerPoint;
    long hungerUntil = Long.MAX_VALUE;
    /** At hunger 0, one health point is lost every this many ticks (0 = none). The 10x test server: 8. */
    int starveTicks;
    /** With the hunger bar at 18 or more, one health point comes back every this many ticks (0 = none). */
    int regenTicks;
    float lowestHealth = 20f;
    boolean died;
    /** Parkable tasks that stopped at a safe point, and that carried on. */
    int parks;
    int resumes;
    Supplier<Phase> phaseProbe = () -> null;
    private BotTask lastOther;

    FakeBot(FakeWorld world, FakeInventory inv, FakeMaps maps, FakeCatalog catalog) {
        this.world = world;
        this.inv = inv;
        this.maps = maps;
        this.catalog = catalog;
        this.mapNames = inv.mapNames;
    }

    void tick(long tick) {
        now = tick;
        wearArmor(tick);
        if (ticksPerHungerPoint > 0 && tick <= hungerUntil && tick % ticksPerHungerPoint == 0 && inv.foodLevel > 0) {
            inv.foodLevel--;
            lowestFoodLevel = Math.min(lowestFoodLevel, inv.foodLevel);
        }
        if (inv.health > 0) {
            if (starveTicks > 0 && inv.foodLevel == 0 && tick % starveTicks == 0) inv.health -= 1;
            else if (regenTicks > 0 && inv.foodLevel >= 18 && inv.health < 20 && tick % regenTicks == 0) inv.health += 1;
            lowestHealth = Math.min(lowestHealth, inv.health);
            if (inv.health <= 0) died = true;
        }
        if (heldMapRefreshTicks > 0 && tick % heldMapRefreshTicks == 0 && maps.held != null && heldAt != null
            && !maps.locked.contains(maps.held) && inv.filledMaps.contains(maps.held)
            && world.playerBlock().equals(heldAt)) {
            maps.colors.put(maps.held, render(heldAt));
        }
        if (autoEatEats && autoEatOn.getAsBoolean() && inv.health > 0 && tick >= autoEatBusyUntil && inv.foodLevel < 20
            && (inv.foodLevel <= autoEatHungerThreshold || inv.health <= autoEatHealthThreshold)) {
            String id = null;
            for (String f : SimFakes.FOODS) if (inv.count(f) > 0) { id = f; break; }
            if (id != null) {
                if (lastTask != null && !lastTask.isFinished() && !lastTask.parked()) autoEatsBesideTask++;
                inv.remove(id, 1);
                inv.foodLevel = Math.min(20, inv.foodLevel + FOOD_POINTS.getOrDefault(id, 4));
                autoEats++;
                autoEatBusyUntil = tick + 32;
            }
        }
        for (int i = 0; i < scheduled.size(); i++) {
            if (scheduledAt.get(i)[0] <= tick) {
                Runnable r = scheduled.remove(i);
                scheduledAt.remove(i);
                i--;
                r.run();
            }
        }
    }

    /** The armour wear model: Soul Speed boots while walking tasks run, all pieces while any task runs (mob hits). */
    private void wearArmor(long tick) {
        BotTask t = lastTask;
        boolean running = t != null && !t.isFinished() && !t.parked();
        String n = running ? t.name() : "";
        boolean walking = running && !(n.startsWith("throwXp") || n.startsWith("mendTool") || n.startsWith("eat")
            || n.startsWith("equip") || n.startsWith("take off") || n.startsWith("unequip") || n.startsWith("wait"));
        if (walking && inv.boots != null && bootsFloorUses >= 0 && inv.bootsUses() <= bootsFloorUses) ticksWornAtFloor++;
        if (walking && bootsWearTicks > 0 && tick % bootsWearTicks == 0 && inv.boots != null) {
            int max = SimFakes.FakeInventory.armorMax(inv.boots);
            int left = Math.max(0, inv.bootsUses() - 1);
            inv.bootsDurability = (double) left / max;
            if (left <= 0) armorBroke = true;
        }
        if (running && armorWearTicks > 0 && tick % armorWearTicks == 0) {
            for (SimFakes.FakeInventory.FakeArmor a : inv.worn.values()) {
                a.remaining = Math.max(0, a.remaining - 1);
                if (a.remaining <= 0) armorBroke = true;
            }
        }
        if (inv.boots != null) lowestBootsUses = Math.min(lowestBootsUses, inv.bootsUses());
        for (SimFakes.FakeInventory.FakeArmor a : inv.worn.values()) lowestArmorUses = Math.min(lowestArmorUses, a.remaining);
    }

    /** Boots withdrawn while none are worn come half worn (the fakes' boot chest), as carried pieces. */
    private void withdrawnBoots(String id, int n) {
        int max = SimFakes.FakeInventory.armorMax(id);
        for (int i = 0; i < n; i++) inv.carriedArmor.add(new SimFakes.FakeInventory.FakeArmor(id, (max + 1) / 2, true));
    }

    void schedule(int delayTicks, Runnable r) {
        scheduledAt.add(new long[]{now + delayTicks});
        scheduled.add(r);
    }

    // ---- what a disconnect drops ----

    /** The stack on the cursor (a swap or a top-up cut short by the next disconnect), or null. */
    String cursorItem;
    int cursorCount;
    /** Stacks on the ground: what disconnects dropped, until picked up. */
    final List<io.github.digitalisp.mapart.bot.WorldView.ItemInFlight> ground = new ArrayList<>();
    private final List<io.github.digitalisp.mapart.bot.DisconnectDrop> disconnectDrops = new ArrayList<>();
    private int groundIds = 70_000;

    /**
     * The server's side of a disconnect: the cursor's stack is dropped about 1.5 blocks ahead of the bot (vanilla
     * {@code AbstractContainerMenu.removed}) and the production {@code DropWatch} notes it for the reconnect, with
     * what the inventory held of its item besides.
     */
    void disconnect() {
        disconnect(true);
    }

    /**
     * {@link #disconnect()}; {@code reached} false: the click that took the cursor's stack out of the inventory had not
     * reached the server, so the server has it in the inventory still and drops nothing (DropWatch saw it all the same).
     */
    void disconnect(boolean reached) {
        if (cursorItem == null || cursorCount <= 0) return;
        Pos feet = world.playerBlock();
        disconnectDrops.add(new io.github.digitalisp.mapart.bot.DisconnectDrop(cursorItem, cursorCount, feet,
            io.github.digitalisp.mapart.bot.DisconnectDrop.CURSOR, true, inv.count(cursorItem)));
        if (reached) {
            ground.add(new io.github.digitalisp.mapart.bot.WorldView.ItemInFlight(groundIds++, cursorItem, cursorCount,
                world.x + 1.5, world.y, world.z));
        } else {
            inv.add(cursorItem, cursorCount);
        }
        cursorItem = null;
        cursorCount = 0;
    }

    @Override
    public List<io.github.digitalisp.mapart.bot.DisconnectDrop> takeDisconnectDrops() {
        List<io.github.digitalisp.mapart.bot.DisconnectDrop> out = List.copyOf(disconnectDrops);
        disconnectDrops.clear();
        return out;
    }

    /**
     * As DropRecoveryTask: a drop back in the inventory is done at once; else walks onto each stack of the item within
     * {@code radius} of where it dropped and picks it up; a stack more than a block below where it dropped (fell off an
     * edge) has no way to it.
     */
    @Override
    public BotTask recoverDrops(List<io.github.digitalisp.mapart.bot.DisconnectDrop> drops, double radius,
                                Consumer<List<io.github.digitalisp.mapart.bot.DisconnectDrop.Outcome>> result) {
        return simple("recoverDrops", "recoverDrops " + drops.size(), 3, () -> {
            List<io.github.digitalisp.mapart.bot.DisconnectDrop.Outcome> out = new ArrayList<>();
            for (io.github.digitalisp.mapart.bot.DisconnectDrop d : drops) {
                if (inv.count(d.item()) >= d.inventory() + d.count()) {
                    out.add(new io.github.digitalisp.mapart.bot.DisconnectDrop.Outcome(d, 0, null, true));
                    continue;
                }
                Pos feet = world.playerBlock();
                int away = Math.max(Math.abs(feet.x() - d.at().x()), Math.abs(feet.z() - d.at().z()));
                if (away > radius) {
                    out.add(new io.github.digitalisp.mapart.bot.DisconnectDrop.Outcome(d, 0,
                        "the bot is " + away + " blocks from where it dropped", false));
                    continue;
                }
                int picked = 0;
                String seen = null;
                for (var it = ground.iterator(); it.hasNext(); ) {
                    var e = it.next();
                    if (!d.item().equals(e.item()) || Math.abs(e.x() - (d.at().x() + 0.5)) > radius
                        || Math.abs(e.z() - (d.at().z() + 0.5)) > radius) continue;
                    int below = d.at().y() - (int) Math.floor(e.y());
                    if (below > 1) {
                        seen = "seen on the ground " + below + " blocks below where it dropped, no way to it";
                        continue;
                    }
                    world.moveTo(new Pos((int) Math.floor(e.x()), (int) Math.floor(e.y()), (int) Math.floor(e.z())));
                    inv.add(e.item(), e.count());
                    picked += e.count();
                    it.remove();
                }
                out.add(new io.github.digitalisp.mapart.bot.DisconnectDrop.Outcome(d, picked, picked >= d.count() ? null
                    : seen != null ? seen : io.github.digitalisp.mapart.bot.DisconnectDrop.NONE_ON_THE_GROUND + (int) radius
                    + " blocks", false));
            }
            result.accept(out);
            return null;
        });
    }

    long count(String prefix) {
        return history.stream().filter(h -> h.startsWith(prefix)).count();
    }

    // ---- task plumbing ----

    private abstract class Task extends BotTask {
        private final String name;
        boolean cancelled;

        Task(String name) {
            this.name = name;
            history.add(name);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        protected void release() {
            if (!isFinishedNormally()) cancelled = true;
        }

        boolean done;

        boolean isFinishedNormally() {
            return done;
        }

        /** Parks at its next tick when asked (lanes, breaks); other tasks run to their end. */
        boolean parkable;
        private boolean parkReq;
        private boolean parkedNow;

        @Override
        public boolean requestPark() {
            if (!parkable) return false;
            if (!parkedNow) parkReq = true;
            return true;
        }

        @Override
        public boolean parked() {
            return parkedNow;
        }

        @Override
        public void resume() {
            if (parkedNow) resumes++;
            parkedNow = false;
            parkReq = false;
        }

        /** Call first in onTick: true while this tick goes to parking (nothing else happens). */
        boolean parking() {
            if (parkedNow) return true;
            if (!parkReq) return false;
            parkReq = false;
            parkedNow = true;
            parks++;
            return true;
        }
    }

    /** Completes after {@code ticks} ticks by running {@code body} (null = success, else failure reason). */
    private BotTask simple(String kind, String name, int ticks, Supplier<String> body) {
        return simple(kind, name, ticks, body, null);
    }

    /**
     * {@link #simple}; with a {@code gateKey} the menu gate ({@link #menuGate}) is asked first, every tick until it says
     * yes (the production ContainerTask holds still meanwhile), and the key goes back when the task ends or is cancelled.
     */
    private BotTask simple(String kind, String name, int ticks, Supplier<String> body, String gateKey) {
        Integer f = failNext.get(kind);
        if (f != null && f > 0) {
            failNext.put(kind, f - 1);
            return remember(new Task(name + " (injected failure)") {
                @Override
                protected TaskStatus onTick() {
                    done = true;
                    return fail("injected failure");
                }
            });
        }
        return remember(new Task(name) {
            int left = ticks;
            boolean asked;

            @Override
            protected TaskStatus onTick() {
                probe(kind);
                if (gateKey != null && !gate(gateKey)) return TaskStatus.RUNNING;
                if (gateKey != null && !menuGate.mayKeep(gateKey)) { // the production ContainerTask closes the menu now
                    done = true;
                    menuDrops.add(kind);
                    return fail("the claim on this menu was lost: menu closed at once");
                }
                if (--left > 0) return TaskStatus.RUNNING;
                done = true;
                String r = body.get();
                return r == null ? TaskStatus.SUCCESS : fail(r);
            }

            private boolean gate(String key) {
                if (opened) return true;
                asked = true;
                opened = menuGate.mayOpen(key);
                if (!opened) menuWaitTicks++;
                return opened;
            }

            boolean opened;

            @Override
            protected void release() {
                super.release();
                if (asked) menuGate.done(gateKey);
            }
        });
    }

    private BotTask remember(BotTask t) {
        lastTask = t;
        if (!t.name().startsWith("eat")) lastOther = t;
        if (!t.name().startsWith("eat") && !t.name().startsWith("mendTool")) lastWork = t;
        return t;
    }

    /** Per task tick: AutoEat must be off while the engine's tasks run; eating only beside no other task. */
    private void probe(String kind) {
        if (kind.equals("eat")) {
            if (lastOther != null && !lastOther.isFinished() && !lastOther.parked()) eatTicksBesideOtherTask++;
        } else if (kind.equals("mend")) {
            if (lastWork != null && !lastWork.isFinished()) mendTicksBesideOtherTask++;
        } else if (autoEatOn.getAsBoolean()) {
            ticksWithAutoEatOn++;
        }
    }

    // ---- BotActions ----

    @Override public WorldView world() { return world; }
    @Override public InventoryView inventory() { return inv; }
    /** What the engine reads as map data instead of {@link #maps} (e.g. the production ZenithMapView); null = maps. */
    MapView mapView;

    @Override public MapView maps() { return mapView != null ? mapView : maps; }

    /** {@code BotActions.sprintWalks} as the engine last set it (plain walks may sprint; true until it says). */
    boolean sprintWalks = true;
    /** {@code BotActions.restockLegs} as the engine last set it. */
    boolean restockLegs;

    @Override
    public void sprintWalks(boolean allowed) {
        sprintWalks = allowed;
    }

    @Override
    public void restockLegs(boolean on) {
        restockLegs = on;
    }

    /** Targets of the {@link #walkOnFoot} walks that arrived, in order (a lost ride's walk to the booth, a paused ride's way round). */
    final List<Pos> footRoutes = new ArrayList<>();

    @Override
    public BotTask walkOnFoot(Pos target, int range) {
        return simple("walk", "walkTo " + target, 1, () -> {
            footRoutes.add(target);
            world.moveTo(target);
            return null;
        });
    }

    @Override
    public BotTask walkTo(Pos target, int range) {
        return simple("walk", "walkTo " + target, 1, () -> {
            world.moveTo(target);
            return null;
        });
    }

    @Override
    public BotTask scan(Pos container, Consumer<ContainerSnapshot> result) {
        return scan(container, null, result);
    }

    @Override
    public BotTask scan(Pos container, Pos standAt, Consumer<ContainerSnapshot> result) {
        return accessed("scan", "scan " + container, container, standAt, 0, 1, () -> {
            Container c = containers.get(container);
            if (c == null) return "no container at " + container;
            if (unreachable.contains(container)) return "cannot reach " + container;
            world.moveTo(container.offset(0, 0, -2));
            result.accept(new ContainerSnapshot(container, "chest", new TreeMap<>(c.items), c.freeSlots(), c.slots));
            return null;
        });
    }

    @Override
    public BotTask withdraw(Pos container, Map<String, Integer> wanted, Consumer<TransferResult> result) {
        return withdraw(container, null, wanted, result);
    }

    @Override
    public BotTask withdraw(Pos container, Pos standAt, Map<String, Integer> wanted, Consumer<TransferResult> result) {
        return withdraw(container, standAt, wanted, WithdrawMode.WHOLE_STACKS, result);
    }

    /** Withdrawals per mode, in order: what the engine asked for. */
    final List<WithdrawMode> withdrawModes = new ArrayList<>();

    /**
     * With {@link #wholeStackWithdrawals}: WHOLE_STACKS and TOP_UP move what the production pattern would move (TOP_UP
     * fills a carried partial stack from a full container stack first, when that saves a slot); EXACT and the plain
     * fake move the exact count.
     */
    @Override
    public BotTask withdraw(Pos container, Pos standAt, Map<String, Integer> wanted, WithdrawMode mode,
                            Consumer<TransferResult> result) {
        withdrawModes.add(mode);
        withdrawLog.add(Map.entry(container, phaseProbe.get() == null ? Phase.IDLE : phaseProbe.get()));
        return accessed("withdraw", "withdraw " + container + " " + wanted, container, standAt,
            Math.max(1, wanted.size()), 1, () -> {
            Container c = containers.get(container);
            if (c == null) return "no container at " + container;
            world.moveTo(container.offset(0, 0, -2));
            if (phaseProbe.get() == Phase.NUKE && !pickupsBeforeWithdraw.isEmpty()) {
                int n = Math.min(pickupsBeforeWithdraw.poll(), inv.freeSlots());
                if (n > 0) {
                    inv.add(walkDrop, 64 * n);
                    pickedUp.merge(walkDrop, 64 * n, Integer::sum);
                }
            }
            if (wholeStackWithdrawals && mode != WithdrawMode.EXACT) {
                result.accept(mode == WithdrawMode.TOP_UP ? withdrawTopUp(container, c, wanted)
                    : withdrawWholeStacks(container, c, wanted));
                return null;
            }
            Map<String, Integer> moved = new TreeMap<>();
            String problem = null;
            for (var e : wanted.entrySet()) {
                int avail = c.items.getOrDefault(e.getKey(), 0);
                int take = Math.min(e.getValue(), Math.min(avail, inv.room(e.getKey())));
                if (take < e.getValue()) problem = avail < e.getValue() ? "container empty" : "inventory full";
                if (take <= 0) continue;
                c.items.merge(e.getKey(), -take, Integer::sum);
                if (c.items.get(e.getKey()) <= 0) c.items.remove(e.getKey());
                if (e.getKey().endsWith("_boots") && inv.boots == null) withdrawnBoots(e.getKey(), take);
                inv.add(e.getKey(), take);
                moved.put(e.getKey(), take);
            }
            result.accept(new TransferResult(moved, snapshot(container, c), problem));
            return null;
        });
    }

    @Override
    public BotTask deposit(Pos container, Predicate<String> which, Map<String, Integer> keep, Consumer<TransferResult> result) {
        return accessed("deposit", "deposit " + container, container, 1, () -> {
            Container c = containers.get(container);
            if (c == null) return "no container at " + container;
            world.moveTo(container.offset(0, 0, -2));
            Map<String, Integer> moved = new TreeMap<>();
            String problem = null;
            if (which.test("minecraft:filled_map")) {
                for (Iterator<Integer> it = inv.filledMaps.iterator(); it.hasNext(); ) {
                    int id = it.next();
                    if (c.freeSlots() <= 0) {
                        problem = "container full";
                        break;
                    }
                    c.maps.add(new MapItem(id, mapNames.get(id), maps.locked.contains(id)));
                    it.remove();
                    if (maps.held != null && maps.held == id) maps.held = null;
                    moved.merge("minecraft:filled_map", 1, Integer::sum);
                }
            }
            for (var e : new TreeMap<>(inv.items).entrySet()) {
                String id = e.getKey();
                if (!which.test(id)) continue;
                if (id.endsWith("_pickaxe") || FakeBot.KEEP.contains(id)) continue;
                int n = e.getValue() - keep.getOrDefault(id, 0);
                int room = c.freeSlots() * 64;
                n = Math.min(n, room);
                if (n <= 0) continue;
                inv.remove(id, n);
                c.items.merge(id, n, Integer::sum);
                moved.put(id, n);
            }
            result.accept(new TransferResult(moved, snapshot(container, c), problem));
            return null;
        });
    }

    static final java.util.Set<String> KEEP = java.util.Set.of("minecraft:golden_carrot", "minecraft:netherite_boots");
    static final java.util.Set<String> NEVER_DROP = java.util.Set.of("minecraft:map", "minecraft:filled_map",
        "minecraft:glass_pane", "minecraft:experience_bottle");

    // ---- container access ----

    /**
     * Container access on a simulated block world (stand spots, line of sight with wall signs, look then click) by the
     * production {@link Approach}, before the transfer, for the containers that are blocks of this world; null or a
     * container not in it = instant. See {@link #simulateContainerAccess}.
     */
    ContainerSim containerSim;
    /** Where the bot stands when the first simulated approach starts (null = where it is). */
    double[] containerStart;
    /** Stand spot cache (as ZenithBotActions keeps it for the session). */
    final Map<Pos, Pos> standSpots = new HashMap<>();
    /** Simulated approaches run, and their failures. */
    int approaches;
    final List<String> approachFailures = new ArrayList<>();

    ContainerSim simulateContainerAccess(long seed) {
        containerSim = new ContainerSim(seed);
        return containerSim;
    }

    /** {@link #accessed(String, String, Pos, Pos, int, int, Supplier)} with one click a tick, no planned stand cell. */
    private BotTask accessed(String kind, String name, Pos container, int clicks, Supplier<String> body) {
        return accessed(kind, name, container, null, clicks, 1, body);
    }

    private BotTask accessed(String kind, String name, Pos container, int clicks, int delay, Supplier<String> body) {
        return accessed(kind, name, container, null, clicks, delay, body);
    }

    /**
     * Plan sweeps ({@link #planVisits}) with the production {@link SweepPlanner} on the {@link #containerSim} world; false
     * (or no simulated world) = the default plan, the engine's order without stand cells (the nearest-first scan of
     * before).
     */
    boolean planSweeps = true;
    /** Ticks a container visit takes without a {@link #containerSim} world. */
    int containerTicks = 1;
    /** What {@link #edgeGuardTrouble} reports (null: nothing). */
    String edgeTrouble;

    @Override
    public String edgeGuardTrouble() {
        return edgeTrouble;
    }
    /** Menus as before the look-only scan: a scan waits for a still tick and settles 2 ticks after the close. */
    boolean legacyMenuTiming;
    /** Every plan handed to the engine, in order. */
    final List<List<ContainerVisit>> plans = new ArrayList<>();
    /** The end point of every planned sweep, in order (null: none). */
    final List<Pos> planEnds = new ArrayList<>();

    @Override
    public BotTask planVisits(List<Pos> containers, Consumer<List<ContainerVisit>> result) {
        return planVisits(containers, null, result);
    }

    @Override
    public BotTask planVisits(List<Pos> containers, Pos then, Consumer<List<ContainerVisit>> result) {
        planEnds.add(then);
        ContainerSim cs = containerSim;
        if (cs == null || !planSweeps) {
            return BotActions.super.planVisits(containers, v -> {
                plans.add(v);
                result.accept(v);
            });
        }
        return remember(new Task("planVisits " + containers.size()) {
            SweepPlanner planner;

            @Override
            protected TaskStatus onTick() {
                if (planner == null) {
                    List<SweepPlanner.Target> targets = new ArrayList<>();
                    for (Pos p : containers) {
                        List<Pos> blocks = halves(cs, p);
                        targets.add(new SweepPlanner.Target(p, blocks, sightOf(cs, blocks)));
                    }
                    double[] at = containerStart != null ? containerStart : new double[]{world.x, world.y, world.z};
                    planner = new SweepPlanner(cs, targets, config.build.reach, at[0], at[1], at[2], then);
                }
                if (!planner.step(SweepPlanner.CONTAINERS_PER_TICK)) return TaskStatus.RUNNING;
                done = true;
                List<ContainerVisit> v = planner.plan().visits();
                plans.add(v);
                result.accept(v);
                return TaskStatus.SUCCESS;
            }
        });
    }

    /** The blocks that open the container at {@code p} in the simulated world: both halves of a double chest. */
    static List<Pos> halves(ContainerSim cs, Pos p) {
        BlockSpec b = cs.block(p);
        if (!b.id().endsWith("chest")) return List.of(p);
        int[] d = Aim.chestNeighbour(b.props().get("facing"), b.props().get("type"));
        if (d == null) return List.of(p);
        Pos other = p.offset(d[0], 0, d[1]);
        return cs.block(other).id().equals(b.id()) ? List.of(p, other) : List.of(p);
    }

    /** The simulated raycast to a face centre of {@code blocks} (as {@code Aiming.faceCentre} for a container). */
    static StandSpots.Sight sightOf(ContainerSim cs, List<Pos> blocks) {
        return new StandSpots.Sight() {
            @Override
            public boolean visibleFrom(double ex, double ey, double ez, double reach) {
                List<Pos> keep = cs.targets;
                Map<Pos, Integer> face = cs.onlyFace;
                cs.targets = blocks;
                cs.onlyFace = null;
                try {
                    return cs.shoot(ex, ey, ez, reach) != null;
                } finally {
                    cs.targets = keep;
                    cs.onlyFace = face;
                }
            }

            @Override
            public String hiddenFrom(double ex, double ey, double ez, double reach) {
                List<Pos> keep = cs.targets;
                Map<Pos, Integer> face = cs.onlyFace;
                cs.targets = blocks;
                cs.onlyFace = null;
                try {
                    return cs.hiddenFrom(ex, ey, ez, reach);
                } finally {
                    cs.targets = keep;
                    cs.onlyFace = face;
                }
            }
        };
    }

    /**
     * {@link #simple}, but when the container is a block of {@link #containerSim} the bot first gets to it and clicks
     * it (production {@link Approach}), waits for the menu, and then runs the production {@link ContainerRun} in it:
     * a still tick, {@code clicks} clicks {@code delay} ticks apart as one request, close, settle. The simulated
     * ZenithProxy drops every input request while the menu is open. {@code body} applies the transfer at the end, as
     * {@code ContainerTask.finish} reads it.
     */
    private BotTask accessed(String kind, String name, Pos container, Pos standAt, int clicks, int delay,
                             Supplier<String> body) {
        ContainerSim cs = containerSim;
        String gateKey = gateKey(kind, container);
        if (cs == null || cs.block(container).isAir()) return simple(kind, name, containerTicks, body, gateKey);
        return remember(new Task(name) {
            Approach<ContainerSim.Shot> approach;
            ContainerRun menu;
            long clickedAt = -1;
            long t;
            boolean asked;

            @Override
            protected TaskStatus onTick() {
                probe(kind);
                if (approach == null) {
                    asked = true;
                    if (!menuGate.mayOpen(gateKey)) {
                        menuWaitTicks++;
                        return TaskStatus.RUNNING;
                    }
                    if (containerStart != null) {
                        cs.teleport(containerStart[0], containerStart[1], containerStart[2]);
                        containerStart = null;
                    } else {
                        cs.teleport(world.x, world.y, world.z);
                    }
                    // a fresh ContainerTask: no sneaking, any face, nothing to place; both halves of a double chest
                    cs.targets = halves(cs, container);
                    cs.onlyFace = null;
                    cs.sneakToClick = false;
                    cs.keepClear = null;
                    cs.placeItem = null;
                    cs.opened = null;
                    approaches++;
                    approach = new Approach<>("chest at " + container, cs.targets, config.build.reach,
                        standAt != null ? standAt : standSpots.get(container), cs);
                }
                cs.beginTick(++t);
                TaskStatus s = step();
                cs.endTick();
                world.x = cs.x;
                world.y = cs.y;
                world.z = cs.z;
                if (s == TaskStatus.RUNNING) return s;
                done = true;
                if (s == TaskStatus.FAILED) return s;
                String r = body.get();
                world.x = cs.x; // the transfer does not move the bot
                world.y = cs.y;
                world.z = cs.z;
                return r == null ? TaskStatus.SUCCESS : fail(r);
            }

            private TaskStatus step() {
                if (clickedAt >= 0 && !menuGate.mayKeep(gateKey)) { // the production ContainerTask closes it now
                    if (cs.openMenuId() != 0) cs.closeMenu();
                    menuDrops.add(kind);
                    return fail("the claim on this menu was lost: menu closed at once");
                }
                if (menu != null) {
                    TaskStatus s = menu.tick(t);
                    if (s == TaskStatus.FAILED) {
                        menuFailures.add(kind + " " + container + ": " + menu.failure());
                        return fail(menu.failure());
                    }
                    if (s == TaskStatus.SUCCESS) menus.add(kind + " " + container + " clicks " + clicks);
                    return s;
                }
                if (clickedAt < 0) {
                    TaskStatus s = approach.tick(t);
                    if (s == TaskStatus.FAILED) {
                        approachFailures.add(approach.failure());
                        return fail("cannot reach " + container + ": " + approach.failure());
                    }
                    if (s == TaskStatus.SUCCESS) clickedAt = t;
                    return TaskStatus.RUNNING;
                }
                cs.holdStill();
                if (cs.openMenuId() != 0) {
                    if (!cs.targets.contains(cs.menuAt())) return fail("opened " + cs.menuAt() + " instead of " + container);
                    standSpots.put(container, approach.standingCell());
                    cs.menuClicks = clicks;
                    cs.clickDelay = delay;
                    menu = new ContainerRun(cs.openMenuId(), cs, kind.equals("scan") && !legacyMenuTiming);
                    return TaskStatus.RUNNING;
                }
                if (t - clickedAt > 40) return fail("container at " + container + " did not open");
                return TaskStatus.RUNNING;
            }

            @Override
            protected void release() {
                super.release();
                cs.stopWalking();
                if (cs.openMenuId() != 0) cs.closeMenu();
                if (asked) menuGate.done(gateKey);
            }
        });
    }

    /** The production ContainerTask's claim key: the base's two stations, else the chest. */
    private static String gateKey(String kind, Pos container) {
        return switch (kind) {
            case "lockMap" -> MenuGate.CARTOGRAPHY;
            case "rename" -> MenuGate.ANVIL;
            default -> MenuGate.chest(container);
        };
    }

    /** The engine's menu gate (its claims on chests and stations); container tasks ask it before opening. */
    MenuGate menuGate = MenuGate.OPEN;
    /** Ticks container tasks waited for the gate. */
    int menuWaitTicks;
    /** Container tasks that closed their menu because its claim was lost (kind per drop). */
    final List<String> menuDrops = new ArrayList<>();

    @Override
    public void menuGate(MenuGate gate) {
        menuGate = gate;
    }

    /** Menus used through {@link ContainerRun} ("kind pos clicks n"), and the runs that failed in the menu. */
    final List<String> menus = new ArrayList<>();
    final List<String> menuFailures = new ArrayList<>();

    /** Feet cells the simulated presses ({@link #pressed}) were made from, in order. */
    final List<Pos> pressedFrom = new ArrayList<>();

    /**
     * {@link #simple}, but when {@code pos} is a block of {@link #containerSim} the production {@link UseRun} presses it
     * there first ({@code standAt} first, look then click, the state change), then {@code body} runs as usual.
     */
    private BotTask pressed(String name, Pos pos, Pos standAt, Supplier<String> body) {
        ContainerSim cs = containerSim;
        if (cs == null || cs.block(pos).isAir()) return simple("use", name, 1, body);
        return remember(new Task(name) {
            UseRun<ContainerSim.Shot> run;
            long t;

            @Override
            protected TaskStatus onTick() {
                probe("use");
                if (run == null) {
                    cs.teleport(world.x, world.y, world.z);
                    cs.targets = List.of(pos);
                    approaches++;
                    run = new UseRun<>(pos, standAt == null ? List.of() : List.of(standAt), standSpots.get(pos),
                        config.build.reach, cs);
                }
                cs.beginTick(++t);
                TaskStatus s = run.tick(t);
                cs.endTick();
                world.x = cs.x;
                world.y = cs.y;
                world.z = cs.z;
                if (s == TaskStatus.RUNNING) return s;
                done = true;
                if (s == TaskStatus.FAILED) {
                    approachFailures.add(run.failure());
                    return fail(run.failure());
                }
                standSpots.put(pos, run.standingCell());
                pressedFrom.add(run.standingCell());
                String r = body.get();
                world.x = cs.x; // the press does not move the bot
                world.y = cs.y;
                world.z = cs.z;
                return r == null ? TaskStatus.SUCCESS : fail(r);
            }

            @Override
            protected void release() {
                super.release();
                cs.stopWalking();
            }
        });
    }

    @Override
    public BotTask depositMap(Pos container, int mapId, Consumer<TransferResult> result) {
        return accessed("depositMap", "depositMap " + mapId + " " + container, container, 1, () -> {
            Container c = containers.get(container);
            if (c == null) return "no container at " + container;
            if (!inv.filledMaps.contains(mapId)) return "map " + mapId + " not in inventory";
            world.moveTo(container.offset(0, 0, -2));
            Map<String, Integer> moved = new TreeMap<>();
            String problem = null;
            if (c.freeSlots() <= 0) {
                problem = "container full";
            } else if (mapsKept > 0) {
                mapsKept--; // the server kept it: the map stays in the inventory
                problem = "map " + mapId + " is still in the inventory";
            } else {
                c.maps.add(new MapItem(mapId, mapNames.get(mapId), maps.locked.contains(mapId)));
                inv.filledMaps.remove(Integer.valueOf(mapId));
                if (maps.held != null && maps.held == mapId) maps.held = null;
                if (staleMapReadBacks > 0) {
                    staleMapReadBacks--; // moved, but the read-back came before the server's answer
                    problem = "map " + mapId + " is still in the inventory";
                } else {
                    moved.put("minecraft:filled_map", 1);
                }
            }
            result.accept(new TransferResult(moved, snapshot(container, c), problem));
            return null;
        });
    }

    /** Map deposits that move the map but report it still in the inventory (the read-back before the answer). */
    int staleMapReadBacks;
    /** Map deposits the server refuses: the map stays in the inventory. */
    int mapsKept;

    /** Working items put back by returnWorkingItems (item id → count), over the whole run. */
    final Map<String, Integer> returnedWorking = new TreeMap<>();

    @Override
    public BotTask returnWorkingItems(Pos container, Map<String, Integer> keep, Consumer<TransferResult> result) {
        return accessed("returnWorking", "returnWorkingItems " + container + " " + keep.keySet(), container, 1, () -> {
            Container c = containers.get(container);
            if (c == null) return "no container at " + container;
            world.moveTo(container.offset(0, 0, -2));
            Map<String, Integer> moved = new TreeMap<>();
            String problem = null;
            for (var e : keep.entrySet()) {
                String id = e.getKey();
                if (!WORKING_ITEMS.contains(id)) continue;
                int n = inv.count(id) - Math.max(0, e.getValue());
                if (n <= 0) continue;
                int room = c.freeSlots() * 64 + (c.items.containsKey(id) ? (64 - c.items.get(id) % 64) % 64 : 0);
                if (room < n) problem = "container full";
                n = Math.min(n, room);
                if (n <= 0) continue;
                inv.remove(id, n);
                c.items.merge(id, n, Integer::sum);
                moved.put(id, n);
                returnedWorking.merge(id, n, Integer::sum);
            }
            result.accept(new TransferResult(moved, snapshot(container, c), problem));
            return null;
        });
    }

    /** Where each standForItems task stood, in order, and the reasons they ended. */
    final List<Pos> pickupStands = new ArrayList<>();
    final List<String> pickupReasons = new ArrayList<>();
    final List<Pos> pickupPrimes = new ArrayList<>();
    /** Re-prime walks made in all stands, ticks one leg takes, and the re-primes after which the sorter trickles again. */
    int pickupReprimes;
    static final int PICKUP_LEG_TICKS = 40;
    int pickupTrickleAfterReprimes;
    /** What the sorter lets fall past the pickup spot per second of standing (item id → count); empty = nothing. */
    Map<String, Integer> pickupTrickle = new TreeMap<>();

    @Override
    public BotTask standForItems(Pos prime, Pos standAt, Map<String, Integer> want, int stallTicks,
                                 int maxTicks, int minFree, Consumer<io.github.digitalisp.mapart.bot.PickupResult> result) {
        pickupStands.add(standAt);
        if (prime != null) pickupPrimes.add(prime);
        return new Task("standForItems " + standAt) {
            int stood;
            int lastGain;
            int elapsed; // ticks since the first arrival at the edge, the re-prime walks included
            int walking;
            int reprimes;
            final Map<String, Integer> gained = new TreeMap<>();

            @Override
            protected TaskStatus onTick() {
                if (walking > 0) { // a re-prime: the walk to the prime cell and back
                    elapsed++;
                    if (--walking == 0) {
                        world.moveTo(standAt);
                        lastGain = stood;
                    }
                    return TaskStatus.RUNNING;
                }
                if (stood == 0 && prime != null) world.moveTo(prime); // on the way: the prime cell, then the edge
                if (stood == 0) world.moveTo(standAt);
                stood++;
                elapsed++;
                if (stood % 20 == 0 && reprimes >= pickupTrickleAfterReprimes) {
                    pickupTrickle.forEach((id, n) -> {
                        if (inv.freeSlots() <= minFree && inv.count(id) % 64 == 0) return;
                        inv.add(id, n);
                        gained.merge(id, n, Integer::sum);
                        lastGain = stood;
                    });
                }
                String why = null;
                boolean covered = true;
                for (var e : want.entrySet()) if (inv.count(e.getKey()) < e.getValue()) covered = false;
                if (covered) why = "covered";
                else if (inv.freeSlots() <= minFree) why = "slots";
                else if (stood - lastGain >= stallTicks) {
                    if (prime != null && reprimes < config.build.sorterPickupReprimes && elapsed < maxTicks) {
                        reprimes++;
                        pickupReprimes++;
                        world.moveTo(prime);
                        walking = 2 * PICKUP_LEG_TICKS;
                        return TaskStatus.RUNNING;
                    }
                    why = "stalled";
                } else if (elapsed >= maxTicks) why = "time";
                if (why == null) return TaskStatus.RUNNING;
                pickupReasons.add(why);
                Map<String, Integer> carried = new TreeMap<>();
                want.keySet().forEach(id -> carried.put(id, inv.count(id)));
                result.accept(new io.github.digitalisp.mapart.bot.PickupResult(gained, why, carried));
                return TaskStatus.SUCCESS;
            }
        };
    }

    @Override
    public BotTask dropItems(Pos standAt, Pos lookAt, Predicate<String> which, Map<String, Integer> keep,
                             Consumer<Map<String, Integer>> dropped) {
        dropStands.add(standAt);
        return simple("drop", "dropItems " + standAt + " -> " + lookAt, 2, () -> {
            world.moveTo(standAt);
            Map<String, Integer> out = new TreeMap<>();
            for (var e : new TreeMap<>(inv.items).entrySet()) {
                String id = e.getKey();
                if (!which.test(id) || NEVER_DROP.contains(id) || KEEP.contains(id) || id.endsWith("_pickaxe")) continue;
                int n = e.getValue() - keep.getOrDefault(id, 0);
                if (n <= 0) continue;
                inv.remove(id, n);
                out.put(id, n);
                droppedItems.merge(id, n, Integer::sum);
            }
            dropped.accept(out);
            return null;
        });
    }

    /**
     * Lanes on a simulated platform (collision, jumping, line of sight, Baritone stand-in) run by the production
     * {@link LaneRun} instead of the instant placement below; null = instant. See {@link #simulateLanes}.
     */
    LaneSim laneSim;
    java.util.function.Function<Lane, LaneRun.Params> laneParams;

    /** Runs lanes with {@link LaneRun} on a {@link LaneSim} over this fake world and inventory. */
    LaneSim simulateLanes(MapArtConfig config, MapArtConfig.PlatformCfg platform, long seed) {
        // a simulated lane starts where the fake world left the bot (the storage level, y61) and walks the tile at that
        // height: three blocks under the art layer, which the fall rescue (build.fallRescue) would take for a fall
        config.build.fallRescue = false;
        laneSim = new LaneSim(new LaneSim.Blocks() {
            @Override
            public BlockSpec get(Pos p) {
                return world.block(p);
            }

            @Override
            public void set(Pos p, BlockSpec b) {
                world.set(p, b);
            }
        }, new LaneSim.Items() {
            @Override
            public int count(String item) {
                return inv.count(item);
            }

            @Override
            public boolean take(String item) {
                return inv.remove(item, 1);
            }
        }, platform.minX, platform.minZ, platform.artY, new AdaptivePacer(config.build.placeIntervalStartTicks,
            config.build.placeIntervalMinTicks, config.build.placeIntervalMaxTicks), seed);
        laneSim.onAccept = p -> {
            placeCounts.merge(p.pos(), 1, Integer::sum);
            placed++;
        };
        MapArtConfig.BuildCfg b = config.build;
        laneSim.silent = PlaceMode.silentRotation(b);
        laneParams = lane -> LaneRun.Params.of(b, laneSim.tileArea());
        return laneSim;
    }

    private BotTask simulatedLane(Lane lane, PlacementListener listener, List<Lane> load) {
        LaneSim ls = laneSim;
        return remember(new Task("lane " + lane.index()) {
            LaneRun<LaneSim.Shot> run;
            long t;

            @Override
            public boolean requestPark() {
                if (run == null) return false;
                run.requestPark();
                return true;
            }

            @Override
            public boolean parked() {
                return run != null && run.parked();
            }

            @Override
            public void resume() {
                if (run != null && run.parked()) resumes++;
                if (run != null) run.resume();
            }

            @Override
            protected TaskStatus onTick() {
                probe("lane");
                if (collectionLamp != null && "true".equals(world.block(collectionLamp).props().get("lit")))
                    placedWithToggleOn = true;
                if (run == null) {
                    ls.teleport(world.x, world.y, world.z);
                    ls.begin(lane, listener);
                    ls.addTargets(load);
                    run = new LaneRun<>(lane, ls, laneParams.apply(lane), load);
                }
                ls.beginTick(++t);
                TaskStatus s = run.tick(t);
                ls.endTick();
                world.x = ls.x;
                world.y = ls.y;
                world.z = ls.z;
                if (s == TaskStatus.RUNNING) return s;
                done = true;
                return s == TaskStatus.FAILED ? fail(run.failure()) : s;
            }

            @Override
            protected void release() {
                super.release();
                ls.stopWalking();
            }
        });
    }

    @Override
    public BotTask placeLane(Lane lane, PlacementListener listener) {
        return lane(lane, listener, lane.from());
    }

    /**
     * Like PlaceLaneTask with setback burst cells: records {@code avoid} ({@link #laneAvoids}) and stands 2 blocks further
     * along z (as often as needed) when the lane's start is within one block of one of them.
     */
    @Override
    public BotTask placeLane(Lane lane, PlacementListener listener, java.util.Set<Pos> avoid) {
        laneAvoids.add(java.util.Set.copyOf(avoid));
        Pos stand = lane.from();
        while (nearAny(stand, avoid)) stand = stand.offset(0, 0, 2);
        if (!stand.equals(lane.from())) laneStandsMoved++;
        return lane(lane, listener, stand);
    }

    private static boolean nearAny(Pos p, java.util.Set<Pos> cells) {
        for (Pos c : cells)
            if (Math.abs(c.x() - p.x()) <= 1 && Math.abs(c.y() - p.y()) <= 1 && Math.abs(c.z() - p.z()) <= 1) return true;
        return false;
    }

    /** Every escape the engine ran, in order. */
    final List<EscapeRequest> escapes = new ArrayList<>();
    /** The escape tasks, in order (cancelled ones included). */
    final List<BotTask> escapeTasks = new ArrayList<>();
    /**
     * The in-reach greedy's form: records {@code load} ({@link #laneLoads}); simulated lanes ({@link #simulateLanes}) may
     * place its cells.
     */
    @Override
    public BotTask placeLane(Lane lane, PlacementListener listener, java.util.Set<Pos> avoid, List<Lane> load) {
        laneLoads.add(List.copyOf(load));
        nextLoad = List.copyOf(load);
        try {
            return placeLane(lane, listener, avoid);
        } finally {
            nextLoad = List.of();
        }
    }

    /** The load of every 4-argument placeLane (the in-reach greedy), in order. */
    final List<List<Lane>> laneLoads = new ArrayList<>();
    /** The load handed to the lane being made ({@link #placeLane(Lane, PlacementListener, java.util.Set, List)}). */
    private List<Lane> nextLoad = List.of();

    /** The avoid set of every 3-argument placeLane, in order. */
    final List<java.util.Set<Pos>> laneAvoids = new ArrayList<>();
    /** Lanes that stood elsewhere because their start was next to a setback burst cell. */
    int laneStandsMoved;
    /** Why an escape fails (null = it succeeds: the bot ends 2 blocks east of the burst cell). */
    java.util.function.Function<EscapeRequest, String> escapeFailure = r -> null;
    /** Ticks an escape takes. */
    int escapeTicks = 10;

    @Override
    public BotTask escape(EscapeRequest request) {
        escapes.add(request);
        BotTask t = simple("escape", "escape rung " + request.rung() + " from " + request.cell().key(), escapeTicks, () -> {
            String why = escapeFailure.apply(request);
            if (why != null) return why;
            world.moveTo(request.cell().offset(2, 0, 0));
            return null;
        });
        escapeTasks.add(t);
        return t;
    }

    /** Every fall rescue the engine ran, in order. */
    final List<io.github.digitalisp.mapart.bot.FallRescue> fallRescues = new ArrayList<>();
    /** Why a fall rescue fails (null = it succeeds: the bot stands on the floor 1 cell east of where it fell). */
    java.util.function.Function<io.github.digitalisp.mapart.bot.FallRescue, String> fallRescueFailure = r -> null;
    /** A successful fall rescue tells the engine it left these blocks of its own (null: none). */
    String fallRescueLeft;
    /** A failed fall rescue tells the engine these blocks of its own still stand (null: none). */
    java.util.Map<Pos, String> fallRescueOwn;
    /** The highest level a failed fall rescue stood at (null: where the bot is at its end). */
    Integer fallRescueStandY;

    /** A walk that failed boxed in after a drop ({@link #takeBoxedInDrop}): taken once, while the bot stands there. */
    io.github.digitalisp.mapart.bot.BoxedInDrop boxedInDrop;

    @Override
    public io.github.digitalisp.mapart.bot.BoxedInDrop takeBoxedInDrop() {
        io.github.digitalisp.mapart.bot.BoxedInDrop d = boxedInDrop;
        boxedInDrop = null;
        return d != null && d.at().equals(world.playerBlock()) ? d : null;
    }

    /** A rescue takes 20 ticks after its hold (the WAIT_CLEAR hold, or the wait before another try). */
    @Override
    public BotTask rescueFall(io.github.digitalisp.mapart.bot.FallRescue request) {
        fallRescues.add(request);
        return simple("rescueFall", "rescueFall from " + request.at().key(), 20 + request.holdTicks(), () -> {
            String why = fallRescueFailure.apply(request);
            if (why != null) {
                request.ended().accept(new io.github.digitalisp.mapart.bot.FallRescue.Ended(
                    fallRescueOwn != null ? fallRescueOwn : java.util.Map.of(),
                    fallRescueStandY != null ? fallRescueStandY : world.playerBlock().y()));
                return why;
            }
            request.ended().accept(new io.github.digitalisp.mapart.bot.FallRescue.Ended(java.util.Map.of(),
                world.playerBlock().y()));
            world.fluid.remove(world.playerBlock());
            world.moveTo(new Pos(request.at().x() + 1, request.floorY(), request.at().z()));
            if (fallRescueLeft != null) request.leftBlocks().accept(fallRescueLeft);
            return null;
        });
    }

    /** Every retreat the engine ran (the mobs it retreated from), in order. */
    final List<List<io.github.digitalisp.mapart.bot.WorldView.Mob>> retreats = new ArrayList<>();

    /** A retreat walks the bot 30 blocks west (away from mobs placed east of it in the tests). */
    @Override
    public BotTask retreat(List<io.github.digitalisp.mapart.bot.WorldView.Mob> threats) {
        retreats.add(List.copyOf(threats));
        return simple("retreat", "retreat from " + threats.size(), escapeTicks, () -> {
            world.moveTo(world.playerBlock().offset(-30, 0, 0));
            return null;
        });
    }

    /** Every resync the engine ran (the spot), in order. */
    final List<Pos> resyncs = new ArrayList<>();
    /** The resync tasks, in order (cancelled ones included). */
    final List<BotTask> resyncTasks = new ArrayList<>();
    /** Ticks a resync takes. */
    int resyncTicks = 30;

    @Override
    public BotTask resync(Pos at) {
        resyncs.add(at);
        BotTask t = simple("resync", "resync at " + at.key(), resyncTicks, () -> null);
        resyncTasks.add(t);
        return t;
    }

    // ---- keep-alive: the production KeepAliveRun on a packet-level model of ZenithProxy's input path ----

    /** One keep-alive the engine started: the tick, its turn, the phase, and whether another task was unfinished. */
    record KeepAliveStart(long tick, float turn, Phase phase, boolean besideTask) { }

    /** The bot's real rotation (what ZenithProxy's Bot applied), and what its last movement packet carried. */
    float yaw = 30f;
    float pitch = 45f;
    private float sentYaw = 30f;
    private float sentPitch = 45f;
    private int ticksSincePos;
    /** This tick's rotation request through the input path (null: none), and whether last tick's was executed. */
    private Float requestedYaw;
    private boolean executedLastTick;
    /** Another module owns the input (AutoEat eating, KillAura): every request is rejected. */
    boolean inputOwnedElsewhere;
    /** A container menu is open (the keep-alive itself checks it). */
    boolean menuOpen;
    /** {@link BotActions#isBusy}: an open menu, Baritone walking, a delayed block the server still breaks. */
    boolean busy;
    /**
     * A delayed block of a cancelled double break that the server still holds (production: PendingDelayed): busy
     * until the world shows air there.
     */
    Pos delayedBlock;
    final List<KeepAliveStart> keepAlives = new ArrayList<>();
    /** Keep-alives that ended with the swing. */
    int keepAlivesDone;
    int swings;
    /** Every packet the model sent, per tick: "12: rot 45.0/45.0", "13: swing", ... (position reminders left out). */
    final List<String> wire = new ArrayList<>();
    private final List<String> tickWire = new ArrayList<>();
    /** Movement packets (MovePlayer*, position reminders included) in the busiest tick. */
    int maxFlyingPerTick;
    /** Movement packets not sent by ZenithProxy's Bot (a silent look would be one). */
    int extraFlying;

    private final KeepAliveRun.Port keepAlivePort = new KeepAliveRun.Port() {
        @Override public boolean containerOpen() { return menuOpen; }
        @Override public float yaw() { return yaw; }

        @Override
        public boolean requestYaw(float y) {
            if (inputOwnedElsewhere) return false;
            requestedYaw = y;
            return true;
        }

        @Override public boolean executedLastTick() { return executedLastTick; }

        @Override
        public void swing() {
            swings++;
            tickWire.add("swing");
        }
    };

    @Override
    public boolean isBusy() {
        return busy || (delayedBlock != null && !world.block(delayedBlock).isAir());
    }

    /** Not a {@link Task}: never in {@link #history} or {@link #lastTask}. */
    @Override
    public BotTask keepAlive(float yawDelta) {
        KeepAliveRun run = new KeepAliveRun(yawDelta);
        return new BotTask() {
            boolean started;

            @Override
            protected TaskStatus onTick() {
                if (!started) {
                    started = true;
                    boolean beside = lastTask != null && !lastTask.isFinished()
                        || lastOther != null && !lastOther.isFinished();
                    keepAlives.add(new KeepAliveStart(now, yawDelta, phaseProbe.get(), beside));
                }
                TaskStatus s = run.tick(keepAlivePort);
                if (s == TaskStatus.SUCCESS) keepAlivesDone++;
                return s == TaskStatus.FAILED ? fail(run.failure()) : s;
            }

            @Override
            public String name() {
                return "keepAlive " + yawDelta;
            }
        };
    }

    /**
     * After the engine's handler: ZenithProxy's InputManager hands this tick's request to its Bot, which applies the
     * rotation and sends at most one movement packet (a rotation change, or the 20-tick position reminder).
     */
    void endTick() {
        executedLastTick = requestedYaw != null;
        if (requestedYaw != null) yaw = requestedYaw;
        requestedYaw = null;
        boolean rot = yaw != sentYaw || pitch != sentPitch;
        boolean pos = ++ticksSincePos >= 20;
        int flying = rot || pos ? 1 : 0;
        if (rot) {
            tickWire.add("rot " + yaw + "/" + pitch);
            sentYaw = yaw;
            sentPitch = pitch;
        }
        if (pos) ticksSincePos = 0;
        maxFlyingPerTick = Math.max(maxFlyingPerTick, flying);
        if (!tickWire.isEmpty()) wire.add(now + ": " + String.join(", ", tickWire));
        tickWire.clear();
    }

    private BotTask lane(Lane lane, PlacementListener listener, Pos stand) {
        history.add("placeLane " + lane.index());
        lanesRun.add(lane);
        if (laneSim != null) return simulatedLane(lane, listener, nextLoad);
        return remember(new Task("lane " + lane.index()) {
            int i;

            {
                parkable = !tasksRefusePark;
            }

            @Override
            protected TaskStatus onTick() {
                if (parking()) return TaskStatus.RUNNING;
                probe("lane");
                if (lanesStandStill) {
                    done = true;
                    return TaskStatus.SUCCESS;
                }
                world.moveTo(stand);
                if (collectionLamp != null && "true".equals(world.block(collectionLamp).props().get("lit")))
                    placedWithToggleOn = true;
                List<Placement> ps = lane.placements();
                for (int n = 0; n < placementsPerTick && i < ps.size(); n++, i++) {
                    Placement p = ps.get(i);
                    BlockSpec cur = world.block(p.pos());
                    if (catalog.matches(p.block(), cur)) {
                        if (reportPlacedCells) listener.placed(p);
                        continue;
                    }
                    if (!cur.isAir()) {
                        listener.skipped(p, "occupied");
                        continue;
                    }
                    String cannot = unplaceable == null || inv.count(p.itemId()) <= 0 ? null : unplaceable.apply(p);
                    if (cannot != null) {
                        listener.skipped(p, cannot);
                        continue;
                    }
                    if (!inv.remove(p.itemId(), 1)) {
                        listener.skipped(p, "no item");
                        continue;
                    }
                    Placement actual = placeOverride != null ? placeOverride.apply(p) : null;
                    placeCounts.merge(p.pos(), 1, Integer::sum);
                    placed++;
                    if (placementsPerHunger > 0 && placed % placementsPerHunger == 0 && inv.foodLevel > 0) {
                        inv.foodLevel--;
                        lowestFoodLevel = Math.min(lowestFoodLevel, inv.foodLevel);
                    }
                    if (actual != null) {
                        world.set(p.pos(), actual.block());
                        listener.skipped(p, "rolled back");
                    } else {
                        world.set(p.pos(), p.block());
                        if (standSpotEvery > 0 && placed % standSpotEvery == 0) listener.placedFromStandSpot(p);
                        else listener.placed(p);
                    }
                }
                if (i < ps.size()) return TaskStatus.RUNNING;
                done = true;
                return TaskStatus.SUCCESS;
            }
        });
    }

    @Override
    public BotTask breakBlocks(List<Pos> targets, Consumer<Pos> brokenCb) {
        return breakTask(targets, brokenCb, null);
    }

    /** With {@link #laneBlocks}: asks {@code pause} every that many broken blocks, as the FAST sweep at a lane end. */
    @Override
    public BotTask breakBlocks(List<Pos> targets, Consumer<Pos> brokenCb, Supplier<String> pause) {
        return breakTask(targets, brokenCb, pause);
    }

    private BotTask breakTask(List<Pos> targets, Consumer<Pos> brokenCb, Supplier<String> pause) {
        history.add("breakBlocks " + targets.size());
        List<Pos> copy = List.copyOf(targets);
        return remember(new Task("break " + copy.size()) {
            int i;
            int sinceLaneEnd;

            {
                parkable = !tasksRefusePark;
            }

            int ticks;

            @Override
            protected TaskStatus onTick() {
                if (parking()) return TaskStatus.RUNNING;
                probe("break");
                if (breakTicksPerBlock > 0 && ticks++ % breakTicksPerBlock != 0) return TaskStatus.RUNNING;
                int perTick = breakTicksPerBlock > 0 ? 1 : 64;
                for (int n = 0; n < perTick && i < copy.size(); n++, i++) {
                    Pos p = copy.get(i);
                    BlockSpec b = world.block(p);
                    if (!b.isAir() && unbreakable != null && unbreakable.test(p)) {
                        missed.add(p);
                        continue;
                    }
                    if (!b.isAir()) {
                        String worn = breakWear(b);
                        if (worn != null) {
                            toolStops++;
                            done = true;
                            return fail(BotActions.TOOL_AT_FLOOR + ": " + worn);
                        }
                        broken++;
                        pickUp(b);
                        sinceLaneEnd++;
                    }
                    world.set(p, BlockSpec.AIR);
                    brokenCb.accept(p);
                    if (pause != null && laneBlocks > 0 && sinceLaneEnd >= laneBlocks && i + 1 < copy.size()) {
                        sinceLaneEnd = 0;
                        laneEndsAsked++;
                        String why = pause.get();
                        if (why != null) {
                            if (onPause != null) onPause.run();
                            pausedAtUses.add(inv.toolsOfKind("pickaxe").stream().mapToInt(t -> t.remaining).min()
                                .orElse(-1));
                            done = true;
                            return fail(BotActions.BREAK_PAUSED + ": " + why);
                        }
                    }
                }
                if (i < copy.size()) return TaskStatus.RUNNING;
                done = true;
                if (!missed.isEmpty()) return fail("cannot reach " + world.block(missed.getFirst()) + " at " + missed.getFirst());
                return TaskStatus.SUCCESS;
            }

            final List<Pos> missed = new ArrayList<>();
        });
    }

    @Override
    public BotTask placeBlock(Pos pos, String itemId) {
        Supplier<String> body = () -> {
            if (!inv.remove(itemId, 1)) return "no " + itemId;
            world.set(pos, BlockSpec.of(itemId, Map.of("facing", "north")));
            return null;
        };
        ContainerSim cs = containerSim;
        if (cs == null || cs.block(pos.below()).isAir()) return simple("placeBlock", "placeBlock " + pos + " " + itemId, 1, body);
        // the production PlaceRun places it in the simulated world first (support face, stand spot, sneak-click)
        return remember(new Task("placeBlock " + pos + " " + itemId) {
            PlaceRun<ContainerSim.Shot> run;
            long t;

            @Override
            protected TaskStatus onTick() {
                probe("placeBlock");
                if (run == null) {
                    cs.teleport(world.x, world.y, world.z);
                    cs.placeItem = BlockSpec.of(itemId, Map.of("facing", "north"));
                    cs.carried = inv.count(itemId);
                    run = new PlaceRun<>(pos, itemId, config.build.reach, config.build.placeConfirmTicks, cs);
                }
                cs.beginTick(++t);
                TaskStatus s = run.tick(t);
                cs.endTick();
                world.x = cs.x;
                world.y = cs.y;
                world.z = cs.z;
                if (s == TaskStatus.RUNNING) return s;
                done = true;
                if (s == TaskStatus.FAILED) return fail(run.failure());
                String r = body.get();
                return r == null ? TaskStatus.SUCCESS : fail(r);
            }

            @Override
            protected void release() {
                super.release();
                cs.stopWalking();
            }
        });
    }

    /** Fights ({@link BotActions#fight}): the areas, in order; the module states seen during each fight. */
    final List<io.github.digitalisp.mapart.bot.FightArea> fights = new ArrayList<>();
    final List<String> fightModules = new ArrayList<>();
    /** The fake fight kills one mob of the area every this many ticks. */
    int killTicks = 20;
    /** Module switch to read during a fight (KillAura on?); set by the test. */
    java.util.function.BooleanSupplier killAuraOn = () -> false;

    @Override
    public BotTask fight(io.github.digitalisp.mapart.bot.FightArea area, int weaponFloor, int quietTicks) {
        return fight(area, weaponFloor, quietTicks, m -> { }, io.github.digitalisp.mapart.bot.MobHits.NONE);
    }

    /**
     * Mobs the fake fight cannot walk to: never killed; with only them left it reports them after 20 ticks, or fails at
     * once when a listed mob hits the bot meanwhile (CombatTask: hurt by mobs out of reach; the engine's hit count).
     */
    final java.util.Set<io.github.digitalisp.mapart.bot.WorldView.Mob> unreachableMobs = new java.util.HashSet<>();
    /** The same by entity id, wherever the mob stands (a mob that wanders on a roof stays out of reach). */
    final java.util.Set<Integer> unreachableIds = new java.util.HashSet<>();
    /** What the fights reported as unreachable, in order. */
    final List<List<io.github.digitalisp.mapart.bot.WorldView.Mob>> reportedUnreachable = new ArrayList<>();
    /** The mobs the fake fights killed, in order (one that hit the bot goes first, as in CombatTask). */
    final List<io.github.digitalisp.mapart.bot.WorldView.Mob> killed = new ArrayList<>();

    @Override
    public BotTask fight(io.github.digitalisp.mapart.bot.FightArea area, int weaponFloor, int quietTicks,
                         Consumer<List<io.github.digitalisp.mapart.bot.WorldView.Mob>> unreachable,
                         io.github.digitalisp.mapart.bot.MobHits hits) {
        fights.add(area);
        return remember(new Task("fight " + area.types()) {
            int ticks;
            int quiet;
            int stuck;
            long seen = -1;

            @Override
            protected TaskStatus onTick() {
                ticks++;
                if (ticks == 1) fightModules.add("KillAura=" + killAuraOn.getAsBoolean());
                List<io.github.digitalisp.mapart.bot.WorldView.Mob> all = area.filter(world.mobList);
                List<io.github.digitalisp.mapart.bot.WorldView.Mob> in = all.stream()
                    .filter(m -> !unreachableMobs.contains(m) && !unreachableIds.contains(m.id())).toList();
                long n = hits.count();
                boolean hurt = seen >= 0 && n > seen;
                seen = n;
                if (in.isEmpty() && !all.isEmpty()) {
                    if (stuck > 0 && hurt) {
                        done = true;
                        return fail("hurt by " + all.size() + " out of reach");
                    }
                    if (++stuck >= 20) {
                        reportedUnreachable.add(List.copyOf(all));
                        unreachable.accept(List.copyOf(all));
                        done = true;
                        return TaskStatus.SUCCESS;
                    }
                    return TaskStatus.RUNNING;
                }
                stuck = 0;
                if (in.isEmpty()) {
                    if (++quiet >= quietTicks) {
                        done = true;
                        return TaskStatus.SUCCESS;
                    }
                    return TaskStatus.RUNNING;
                }
                quiet = 0;
                if (ticks % killTicks == 0) {
                    java.util.Set<Integer> attackers = hits.attackers();
                    io.github.digitalisp.mapart.bot.WorldView.Mob m = in.stream().anyMatch(x -> attackers.contains(x.id()))
                        ? area.target(in, attackers, world.x, world.y, world.z).orElseThrow() : in.getFirst();
                    killed.add(m);
                    world.moveTo(new Pos((int) Math.floor(m.x()), (int) Math.floor(m.y()), (int) Math.floor(m.z())));
                    world.mobList.remove(m);
                }
                return TaskStatus.RUNNING;
            }
        });
    }

    /** Hops down ({@link BotActions#hopDown}): the landing cells asked for, in order. */
    final List<Pos> hops = new ArrayList<>();
    /** Health the fake drop costs (the soul sand cross: about 4 HP before armour). */
    float hopDamage = 4f;

    @Override
    public BotTask hopDown(Pos landing, int maxDrop) {
        return simple("hopDown", "hopDown " + landing + " drop " + maxDrop, 1, () -> {
            hops.add(landing);
            world.moveTo(landing);
            inv.health = Math.max(0f, inv.health - hopDamage);
            return null;
        });
    }

    @Override
    public BotTask useBlock(Pos pos, Pos standAt) {
        useIssued.add(new UseIssued(pos, world.x, world.y, world.z));
        Supplier<String> press = () -> {
            world.moveTo(standAt != null ? standAt : pos.offset(1, 0, 0));
            BlockSpec b = world.block(pos);
            String p = b.props().get("powered");
            if (p != null) {
                TreeMap<String, String> props = new TreeMap<>(b.props());
                props.put("powered", String.valueOf(!Boolean.parseBoolean(p)));
                world.set(pos, BlockSpec.of(b.id(), props));
            }
            Runnable h = useHandlers.get(pos);
            if (h != null) h.run();
            return null;
        };
        Integer walk = useWalkTicks.get(pos);
        if (walk == null) return pressed("use " + pos, pos, standAt, press);
        // a walk to the block first: parks while walking, as the production press does before it aims
        return remember(new Task("use " + pos) {
            int left = walk;

            {
                parkable = true;
            }

            @Override
            protected TaskStatus onTick() {
                if (parking()) return TaskStatus.RUNNING;
                if (--left > 0) return TaskStatus.RUNNING;
                done = true;
                String r = press.get();
                return r == null ? TaskStatus.SUCCESS : fail(r);
            }
        });
    }

    /** Blocks whose {@link #useBlock} walks this many ticks before the press (parkable while walking); default 1 tick. */
    final Map<Pos, Integer> useWalkTicks = new HashMap<>();

    /** Every press of {@link #pressButton} that went out: the button, and the tick it was pressed on. */
    final List<Pos> buttonPresses = new ArrayList<>();
    final List<Long> buttonPressTicks = new ArrayList<>();

    /**
     * Like the production press: only a block whose id ends in {@code _button}; while it shows {@code powered=true} the
     * task waits (at most 100 ticks, then FAILED); the press sets {@code powered=true} and the button pops after 20
     * ticks (stone) or 30 (wood). {@code failNext "pressButton"} injects a failure. When the button is a block of
     * {@link #containerSim}, the production {@code UseRun.pressButton} gets there and presses it first (stand cells
     * from the simulated world's {@code allowedCells}), then the press is applied here as above.
     */
    @Override
    public BotTask pressButton(Pos pos, Pos standAt) {
        Integer f = failNext.get("pressButton");
        if (f != null && f > 0) {
            failNext.put("pressButton", f - 1);
            return remember(new Task("pressButton " + pos + " (injected failure)") {
                @Override
                protected TaskStatus onTick() {
                    done = true;
                    return fail("injected failure");
                }
            });
        }
        ContainerSim cs = containerSim;
        if (cs != null && !cs.block(pos).isAir()) return remember(simulatedPress(cs, pos, standAt));
        return remember(new Task("pressButton " + pos) {
            int waited;

            @Override
            protected TaskStatus onTick() {
                probe("use");
                BlockSpec b = world.block(pos);
                if (!b.id().endsWith("_button")) {
                    done = true;
                    return fail(b.id() + " at " + pos + " is not a button");
                }
                if ("true".equals(b.props().get("powered"))) {
                    if (++waited > 100) {
                        done = true;
                        return fail("the button at " + pos + " stayed pressed for 5 s");
                    }
                    return TaskStatus.RUNNING;
                }
                world.moveTo(standAt != null ? standAt : pos.offset(1, 0, 0));
                applyButtonPress(pos, b);
                done = true;
                return TaskStatus.SUCCESS;
            }
        });
    }

    /** The production {@code UseRun.pressButton} on the simulated world, then the press applied to the engine's world. */
    private Task simulatedPress(ContainerSim cs, Pos pos, Pos standAt) {
        return new Task("pressButton " + pos) {
            UseRun<ContainerSim.Shot> run;
            long t;

            @Override
            protected TaskStatus onTick() {
                probe("use");
                if (run == null) {
                    cs.teleport(world.x, world.y, world.z);
                    cs.targets = List.of(pos);
                    approaches++;
                    run = UseRun.pressButton(pos, standAt == null ? List.of() : List.of(standAt), standSpots.get(pos),
                        config.build.reach, cs);
                }
                cs.beginTick(++t);
                TaskStatus st = run.tick(t);
                cs.endTick();
                world.x = cs.x;
                world.y = cs.y;
                world.z = cs.z;
                if (st == TaskStatus.RUNNING) return st;
                done = true;
                if (st == TaskStatus.FAILED) {
                    approachFailures.add(run.failure());
                    return fail(run.failure());
                }
                standSpots.put(pos, run.standingCell());
                pressedFrom.add(run.standingCell());
                applyButtonPress(pos, world.block(pos));
                return TaskStatus.SUCCESS;
            }

            @Override
            protected void release() {
                super.release();
                cs.stopWalking();
            }
        };
    }

    /** The button at {@code pos} ({@code b}) pressed: powered, popping after 20 ticks (stone) or 30 (wood), recorded. */
    private void applyButtonPress(Pos pos, BlockSpec b) {
        TreeMap<String, String> props = new TreeMap<>(b.props());
        props.put("powered", "true");
        world.set(pos, BlockSpec.of(b.id(), props));
        int pop = b.id().contains("stone") ? 20 : 30;
        schedule(pop, () -> {
            BlockSpec cur = world.block(pos);
            if (!cur.id().equals(b.id())) return;
            TreeMap<String, String> off = new TreeMap<>(cur.props());
            off.put("powered", "false");
            world.set(pos, BlockSpec.of(cur.id(), off));
        });
        buttonPresses.add(pos);
        buttonPressTicks.add(now);
        Runnable h = useHandlers.get(pos);
        if (h != null) h.run();
    }

    // ---- the colour-suppression ride ----

    /** What a held or new map shows instead of {@link #render} (a colour-suppression map); null = render. */
    java.util.function.Function<Pos, byte[]> mapRender;
    /** What the ride writes into the held map (the finished art); null = unchanged. */
    java.util.function.Supplier<byte[]> rideColors;
    /** Runs at the end of a ride (the fake muncher: clears the layers, moves machine blocks ...). */
    Runnable afterRide;
    /** Fails the next ride with this reason (once). */
    String rideFails;
    /** {@link #rideFails} leaves the bot out of the cart with the cart two blocks away. */
    boolean rideFailsOutOfCart;
    /** With {@link #rideFailsOutOfCart}: the cart stays in reach (false: it went on without the bot). */
    boolean rideFailsCartInReach = true;
    /**
     * With {@link #rideFailsOutOfCart}: for this many ticks after the bot is put out the client's picture is false (live
     * 2026-11-16: the anti-cheat's setback to the boarding seat with the cart moved there for the client), so getting
     * back in fails with no cart on the rail.
     */
    int rideFailsUnsettledTicks;
    private long unsettledUntil = -1;
    int rides;
    int launches;
    int cartsBroken;
    /** A minecart stands on the start rail already (ride.reuseCart): boarding needs none from the inventory. */
    boolean cartOnRail;

    @Override
    public BotTask boardCart(Pos rail, Pos standAt, Consumer<Integer> cartId) {
        return simple("boardCart", "boardCart " + rail, 3, () -> {
            if (world.vehicle == null) {
                if (!cartOnRail && !inv.remove("minecraft:minecart", 1)) return "no minecart carried";
                rideEvents.add(cartOnRail ? "board" : "place+board");
                cartOnRail = false; // it leaves with the bot
                cartAtBrake = false;
                world.moveTo(rail);
                world.vehicle = new WorldView.Thing(4242, "minecart", rail.x() + 0.5, rail.y(), rail.z() + 0.5);
            }
            cartId.accept(world.vehicle.id());
            return null;
        });
    }

    /**
     * The hold cell of a lane end the next ride's cart comes to rest in on its way (the slope rail's cell); there the
     * ride's {@code RideOptions.stop} is asked once, the cart having stood {@link #rideHoldStill} ticks. Null: none.
     */
    Pos rideHoldAt;
    long rideHoldStill = 40;
    /** Rides ended by their {@code RideOptions.stop} (the bot seated in the hold). */
    int rideStops;
    /** A ridden cart stands where the bot got out of it, away from the brake (until it is broken). */
    boolean cartLoose;
    /** Where the bot stood with the map on its exact cell ({@code holdMapExactly}), newest last. */
    final List<Pos> exactStands = new ArrayList<>();

    @Override
    public BotTask ride(Pos button, Pos brake, int mapId, float pushYaw, io.github.digitalisp.mapart.bot.RideWatch watch,
                        Runnable pressed) {
        return ride(button, brake, mapId, pushYaw, watch, pressed, null);
    }

    @Override
    public BotTask ride(Pos button, Pos brake, int mapId, float pushYaw, io.github.digitalisp.mapart.bot.RideWatch watch,
                        Runnable pressed, RideOptions opts) {
        return simple("ride", "ride to " + brake, 20, () -> {
            if (world.vehicle == null) return "not in the cart";
            if (!inv.filledMaps.contains(mapId)) return "map " + mapId + " is not in the inventory any more";
            rides++;
            rideEvents.add(opts != null && opts.resumePushYaw() != null ? "ride after a push" : "ride");
            maps.held = mapId;
            if (button != null) {
                launches++;
                pressed.run();
            }
            if (rideHoldAt != null && opts != null && opts.stop() != null) {
                Pos h = rideHoldAt;
                world.moveTo(h);
                world.vehicle = new WorldView.Thing(4242, "minecart", h.x() + 0.49, h.y() + 0.5, h.z() + 0.5);
                if (opts.stop().stopNow(world.vehicle, rideHoldStill)) {
                    rideHoldAt = null;
                    rideStops++;
                    rideEvents.add("stop");
                    return null;
                }
            }
            if (rideFails != null) {
                String r = rideFails;
                rideFails = null;
                if (rideFailsOutOfCart) { // the server put the bot beside its held cart (live 2026-11-14)
                    WorldView.Thing cart = world.vehicle;
                    world.vehicle = null;
                    unsettledUntil = now + rideFailsUnsettledTicks;
                    if (rideFailsCartInReach)
                        world.things.add(new WorldView.Thing(cart.id(), "minecart", cart.x() + 2, cart.y(), cart.z()));
                }
                return r;
            }
            if (rideColors != null) maps.colors.put(mapId, rideColors.get());
            world.moveTo(brake);
            world.vehicle = new WorldView.Thing(4242, "minecart", brake.x() + 0.5, brake.y(), brake.z() + 0.5);
            cartAtBrake = true;
            if (afterRide != null) afterRide.run();
            return null;
        });
    }

    // ---- the preload lap (ride.preload) ----

    /** The platform's own cart stands on the preload lap's start rail. */
    boolean preloadCartOnRail = true;
    /** Fails the next preload lap with this reason (once). */
    String preloadFails;
    int preloadBoards;
    int preloadRides;
    int preloadLaunches;
    /** The ride's steps in the order they ran: "preload-board", "preload", "board", "ride". */
    final List<String> rideEvents = new ArrayList<>();

    @Override
    public BotTask boardOwnCart(Pos rail, Pos standAt, Consumer<Integer> cartId) {
        return simple("boardOwnCart", "boardOwnCart " + rail, 3, () -> {
            if (world.vehicle == null) {
                if (now < unsettledUntil) return "no minecart stands on the rail " + rail + " (the picture is false)";
                if (!preloadCartOnRail) return "no minecart stands on the rail " + rail;
                preloadBoards++;
                rideEvents.add("preload-board");
                world.moveTo(rail);
                world.vehicle = new WorldView.Thing(4343, "minecart", rail.x() + 0.5, rail.y(), rail.z() + 0.5);
            }
            cartId.accept(world.vehicle.id());
            return null;
        });
    }

    @Override
    public BotTask preloadRide(Pos button, Pos end, Float pushYaw, io.github.digitalisp.mapart.bot.RideWatch watch, Runnable pressed) {
        return simple("preloadRide", "preload lap to " + end, 20, () -> {
            if (world.vehicle == null) return "not in the cart";
            preloadRides++;
            rideEvents.add("preload");
            // the production task selects a slot without a filled map first: the map does not update on the lap
            maps.held = null;
            if (button != null) {
                preloadLaunches++;
                pressed.run();
            }
            if (preloadFails != null) {
                String r = preloadFails;
                preloadFails = null;
                return r;
            }
            world.moveTo(end);
            world.vehicle = new WorldView.Thing(4343, "minecart", end.x() + 0.5, end.y(), end.z() + 0.5);
            return null;
        });
    }

    @Override
    public BotTask leaveCart() {
        return simple("leaveCart", "leaveCart", 2, () -> {
            if (world.vehicle == null) {
                maps.held = null; // on foot: only the map leaves the hand
                return null;
            }
            if (world.vehicle.id() == 4242 && !cartAtBrake) cartLoose = true;
            world.moveTo(new Pos((int) Math.floor(world.vehicle.x()), (int) Math.floor(world.vehicle.y()),
                (int) Math.floor(world.vehicle.z()) - 1));
            world.vehicle = null;
            maps.held = null;
            return null;
        });
    }

    @Override
    public BotTask holdMapExactly(Pos standAt, int mapId, int ticks) {
        return simple("holdMapExactly", "holdMapExactly " + mapId, 2, () -> {
            if (!inv.filledMaps.contains(mapId)) return "map " + mapId + " not in inventory";
            world.moveTo(standAt);
            maps.held = mapId;
            if (exactStands.isEmpty() || !"stand".equals(rideEvents.getLast())) rideEvents.add("stand");
            exactStands.add(standAt);
            return null;
        });
    }

    @Override
    public BotTask breakCart(Pos near) {
        return simple("breakCart", "breakCart near " + near, 2, () -> {
            if (!cartAtBrake && !cartLoose) return null; // none there: nothing to do
            cartsBroken++;
            cartAtBrake = false;
            cartLoose = false;
            rideEvents.add("break");
            if (!cartLostOnBreak) inv.add("minecraft:minecart", 1);
            return null;
        });
    }

    /** The ridden cart stands on the brake (after a ride, until it is broken). */
    boolean cartAtBrake;
    /** The broken cart's item is not picked up. */
    boolean cartLostOnBreak;
    /** Fails the next placeCart with this reason (once). */
    String placeCartFails;
    int cartsPlaced;

    @Override
    public BotTask placeCart(Pos rail, Pos standAt, Consumer<Boolean> placed) {
        return simple("placeCart", "placeCart on " + rail, 3, () -> {
            if (world.vehicle != null) return "riding a minecart";
            world.moveTo(standAt);
            if (placeCartFails != null) {
                String r = placeCartFails;
                placeCartFails = null;
                return r;
            }
            if (cartOnRail) {
                placed.accept(false);
                return null;
            }
            if (!inv.remove("minecraft:minecart", 1)) return "no minecart carried";
            cartOnRail = true;
            cartsPlaced++;
            rideEvents.add("place");
            placed.accept(true);
            return null;
        });
    }

    @Override
    public BotTask createMap(Pos standAt, int holdTicks, Consumer<Integer> mapId) {
        return simple("createMap", "createMap " + standAt, 2, () -> {
            if (!inv.remove("minecraft:map", 1)) return "no empty map";
            world.moveTo(standAt);
            if (beforeCreateMap != null) {
                Runnable r = beforeCreateMap;
                beforeCreateMap = null;
                r.run();
            }
            int id = maps.nextId++;
            maps.colors.put(id, mapRender != null ? mapRender.apply(standAt) : render(standAt));
            inv.filledMaps.add(id);
            maps.held = id;
            heldAt = standAt;
            createdMaps++;
            mapId.accept(id);
            return null;
        });
    }

    @Override
    public BotTask holdMap(Pos standAt, int mapId, int ticks) {
        return simple("holdMap", "holdMap " + mapId, 2, () -> {
            if (!inv.filledMaps.contains(mapId)) return "map " + mapId + " not in inventory";
            world.moveTo(standAt);
            if (beforeHoldMap != null) {
                Runnable r = beforeHoldMap;
                beforeHoldMap = null;
                r.run();
            }
            if (!maps.locked.contains(mapId))
                maps.colors.put(mapId, mapRender != null ? mapRender.apply(standAt) : render(standAt));
            maps.held = mapId;
            heldAt = standAt;
            heldMaps++;
            return null;
        });
    }

    @Override
    public BotTask createMap(Pos standAt, int holdTicks, Consumer<Integer> mapId, io.github.digitalisp.mapart.bot.MapHold hold) {
        lastHold = hold;
        return createMap(fillStand(standAt, hold), holdTicks, mapId);
    }

    @Override
    public BotTask holdMap(Pos standAt, int mapId, int ticks, io.github.digitalisp.mapart.bot.MapHold hold) {
        lastHold = hold;
        return holdMap(fillStand(standAt, hold), mapId, ticks);
    }

    /** MapTask's stand: the near stand when set and reachable, else the station. */
    private Pos fillStand(Pos standAt, io.github.digitalisp.mapart.bot.MapHold hold) {
        return hold != null && hold.nearStand() != null && !nearStandUnreachable ? hold.nearStand() : standAt;
    }

    @Override
    public BotTask lockMap(Pos cartographyTable, int mapId, Consumer<Integer> lockedMapId) {
        // the production LockMapTask: 8 clicks 3 ticks apart in the cartography menu
        return accessed("lockMap", "lockMap " + mapId, cartographyTable, 8, 3, () -> {
            if (!inv.filledMaps.contains(mapId)) return "map " + mapId + " not in inventory";
            if (!inv.remove("minecraft:glass_pane", 1)) return "no glass pane";
            world.moveTo(cartographyTable.offset(0, 0, -1));
            int id = maps.nextId++;
            maps.locked.add(id); // before the picture: the locked copy's first packet says locked
            maps.colors.put(id, maps.colors.get(mapId).clone());
            inv.filledMaps.remove(Integer.valueOf(mapId));
            inv.filledMaps.add(id);
            maps.held = id;
            lockedMapId.accept(id);
            return null;
        });
    }

    @Override
    public BotTask renameMap(Pos anvil, int mapId, String name) {
        // the production RenameMapTask: 5 clicks and the rename packet, 3 ticks apart, in the anvil menu
        return accessed("rename", "rename " + mapId, anvil, 6, 3, () -> {
            if (!inv.filledMaps.contains(mapId)) return "map " + mapId + " not in inventory";
            if (inv.xpLevel() < 1) return "no XP";
            if (!world.block(anvil).id().endsWith("anvil")) return "no anvil";
            inv.xpPoints -= 7;
            mapNames.put(mapId, name);
            return null;
        });
    }

    @Override
    public BotTask throwXp(int bottles) {
        return simple("throwXp", "throwXp " + bottles, 1, () -> {
            if (!inv.remove("minecraft:experience_bottle", bottles)) return "not enough bottles";
            bottlesThrown += bottles;
            for (int b = 0; b < bottles; b++) {
                int points = 7;
                while (points > 0 && mendWornPoint()) points--;
                inv.xpPoints += points;
            }
            return null;
        });
    }

    /** One XP point to the most worn damaged worn piece that takes it (boots +1 %, as before; others +2 uses). */
    private boolean mendWornPoint() {
        double bootsF = inv.boots != null && inv.bootsMending && inv.bootsDurability < 1.0 ? inv.bootsDurability : 2;
        SimFakes.FakeInventory.FakeArmor low = null;
        for (SimFakes.FakeInventory.FakeArmor a : inv.worn.values()) {
            if (!a.mending || a.remaining >= a.max) continue;
            if (low == null || (double) a.remaining / a.max < (double) low.remaining / low.max) low = a;
        }
        double lowF = low == null ? 2 : (double) low.remaining / low.max;
        if (bootsF > 1 && lowF > 1) return false;
        if (bootsF <= lowF) inv.bootsDurability = Math.min(1.0, inv.bootsDurability + 0.01);
        else low.remaining = Math.min(low.max, low.remaining + 2);
        return true;
    }

    int toolFloor() {
        return config == null ? 20 : Math.max(0, config.build.toolSafetyFloor);
    }

    /**
     * Wear of breaking {@code b} (mirrors ToolChoice): carpets by hand; anything else with the fastest pickaxe above
     * the floor (no pickaxe at all: by hand). Returns the stop reason when every pickaxe is at the floor.
     */
    private String breakWear(BlockSpec b) {
        if (b.id().endsWith("_carpet")) {
            brokenByHand.merge(b.id(), 1, Integer::sum);
            return null;
        }
        int floor = toolFloor();
        List<FakeInventory.FakeTool> picks = inv.toolsOfKind("pickaxe");
        FakeInventory.FakeTool best = null;
        for (FakeInventory.FakeTool t : picks) {
            if (t.remaining <= floor) continue;
            if (best == null || speed(t.id) > speed(best.id)
                || (speed(t.id) == speed(best.id) && (t.mending && !best.mending
                    || t.mending == best.mending && t.remaining > best.remaining))) best = t;
        }
        if (best == null) {
            if (picks.isEmpty()) {
                brokenByHand.merge(b.id(), 1, Integer::sum);
                return null;
            }
            FakeInventory.FakeTool w = picks.getFirst();
            return "no pickaxe above the safety floor (" + floor + " uses): " + w.id + " at " + w.remaining + "/" + w.max;
        }
        if (best.remaining <= floor) breaksAtFloor++;
        if (++pickaxeBreaks % Math.max(1, breaksPerUse) == 0) best.remaining--;
        lowestToolUses = Math.min(lowestToolUses, best.remaining);
        brokenWithTool.merge(b.id(), 1, Integer::sum);
        return null;
    }

    /** Every {@link #pickupEvery}-th broken block's item goes into the inventory while it has room (a nuke's drops). */
    private void pickUp(BlockSpec b) {
        if (pickupEvery <= 0 || ++pickupCounter % pickupEvery != 0) return;
        String id = b.id();
        if (inv.room(id) > 0) {
            inv.add(id, 1);
            pickedUp.merge(id, 1, Integer::sum);
        } else {
            pickupsRefused++;
            Phase ph = phaseProbe.get();
            if (ph != null) pickupsRefusedIn.merge(ph, 1, Integer::sum);
        }
    }

    private static int speed(String id) {
        if (id.startsWith("minecraft:netherite_")) return 9;
        if (id.startsWith("minecraft:diamond_")) return 8;
        if (id.startsWith("minecraft:iron_")) return 6;
        return 2;
    }

    @Override
    public BotTask mendTool(String toolId, int bottles, double targetFraction) {
        history.add("mendTool " + toolId + " " + bottles);
        int length = Math.max(2, mendTicksPerBottle * Math.max(0, bottles));
        return remember(new Task("mendTool " + toolId) {
            int left = length;

            @Override
            protected TaskStatus onTick() {
                if (left == length) mendPhases.add(phaseProbe.get());
                probe("mend");
                if (--left > 0) return TaskStatus.RUNNING;
                done = true;
                if (inv.count("minecraft:experience_bottle") == 0) return fail("no experience bottles in inventory");
                if (wornMending(toolId, targetFraction) == null) return fail("no " + toolId + " with Mending below target");
                int thrown = 0;
                boolean nothing = toolMendsGainNothing > 0;
                if (nothing) toolMendsGainNothing--;
                while (thrown < bottles && inv.count("minecraft:experience_bottle") > 0) {
                    FakeInventory.FakeTool t = wornMending(toolId, targetFraction);
                    if (t == null) break;
                    inv.remove("minecraft:experience_bottle", 1);
                    thrown++;
                    bottlesThrown++;
                    mendBottles++;
                    int xp = 7;
                    if (armorTakesAllMendXp) while (xp > 0 && mendWornPoint()) xp--;
                    if (nothing) {
                        inv.xpPoints += xp;
                        continue;
                    }
                    if (bootsTakeEveryOtherBottle && thrown % 2 == 0 && inv.boots != null && inv.bootsDurability < 1.0) {
                        while (xp > 0 && inv.bootsDurability < 1.0) {
                            inv.bootsDurability = Math.min(1.0, inv.bootsDurability + 0.01);
                            xp--;
                        }
                    }
                    while (xp > 0 && t.remaining < t.max) {
                        t.remaining = Math.min(t.max, t.remaining + 2);
                        xp--;
                    }
                    inv.xpPoints += xp;
                }
                mends++;
                return TaskStatus.SUCCESS;
            }
        });
    }

    private FakeInventory.FakeTool wornMending(String id, double target) {
        FakeInventory.FakeTool best = null;
        for (FakeInventory.FakeTool t : inv.stacksOf(id)) {
            if (!t.mending || t.fraction() >= target) continue;
            if (best == null || t.fraction() < best.fraction()) best = t;
        }
        return best;
    }

    @Override
    public BotTask exchangeTool(Pos chest, String kind, int floor, Predicate<String> returnable,
                               Consumer<TransferResult> result) {
        return accessed("exchange", "exchangeTool " + kind + " " + chest, chest, 1, () -> {
            Container c = containers.get(chest);
            if (c == null) return "no container at " + chest;
            world.moveTo(chest.offset(0, 0, -2));
            exchanges++;
            boolean usable = inv.toolsOfKind(kind).stream().anyMatch(t -> t.remaining > floor);
            Map<String, Integer> moved = new TreeMap<>();
            if (!usable) {
                for (String id : new ArrayList<>(c.items.keySet())) {
                    if (!kind.equals(InventoryView.toolKind(id))) continue;
                    c.items.merge(id, -1, Integer::sum);
                    if (c.items.get(id) <= 0) c.items.remove(id);
                    inv.addTool(id, FakeInventory.maxUses(id), inv.freshToolsMending);
                    moved.put(id, 1);
                    break;
                }
            }
            if (usable || !moved.isEmpty()) {
                for (FakeInventory.FakeTool t : inv.toolsOfKind(kind)) {
                    if (t.remaining > floor || c.freeSlots() <= 0 || !returnable.test(t.id)) continue;
                    inv.removeTool(t);
                    c.wornTools.add(t);
                    toolsReturned++;
                }
            }
            result.accept(new TransferResult(moved, snapshot(chest, c),
                !usable && moved.isEmpty() ? "no " + kind + " above " + floor + " uses in the chest" : null));
            return null;
        });
    }

    /** {@link #exchangeElytra} calls, and the worn-out elytras put back. */
    int elytraExchanges;
    int elytrasReturned;

    /**
     * As {@code ToolExchangeTask} for elytras: unless an elytra above {@code floor} is carried (a worn one counts), take
     * one from the chest (new: full uses, {@code freshArmorMending}), and put back each carried one at or below the
     * floor when one above it is carried or was just taken.
     */
    @Override
    public BotTask exchangeElytra(Pos chest, int floor, Consumer<TransferResult> result) {
        return accessed("exchange", "exchangeElytra " + chest, chest, 1, () -> {
            Container c = containers.get(chest);
            if (c == null) return "no container at " + chest;
            world.moveTo(chest.offset(0, 0, -2));
            elytraExchanges++;
            String e = "minecraft:elytra";
            boolean usable = inv.armor().stream().anyMatch(a -> a.itemId().equals(e) && (a.worn() || a.remaining() > floor));
            Map<String, Integer> moved = new TreeMap<>();
            if (!usable && c.items.getOrDefault(e, 0) > 0 && inv.freeSlots() > 0) {
                c.items.merge(e, -1, Integer::sum);
                if (c.items.get(e) <= 0) c.items.remove(e);
                inv.add(e, 1);
                moved.put(e, 1);
            }
            if (usable || !moved.isEmpty()) {
                for (SimFakes.FakeInventory.FakeArmor a : new ArrayList<>(inv.carriedArmor)) {
                    if (!a.id.equals(e) || a.remaining > floor || c.freeSlots() <= 0) continue;
                    inv.carriedArmor.remove(a);
                    inv.remove(e, 1);
                    c.items.merge(e, 1, Integer::sum);
                    elytrasReturned++;
                }
            }
            result.accept(new TransferResult(moved, snapshot(chest, c),
                !usable && moved.isEmpty() ? "no elytra above " + floor + " uses in the chest" : null));
            return null;
        });
    }

    @Override
    public BotTask equip(String itemId) {
        return equip(itemId, -1);
    }

    /** {@code fromSlot}: a slot {@link SimFakes.FakeInventory#armor} reported (9.. a recorded piece, 30.. a new one). */
    @Override
    public BotTask equip(String itemId, int fromSlot) {
        return simple("equip", "equip " + itemId, 1, () -> {
            // like EquipTask: a worn piece is never moved out of its armour slot
            int slot = SimFakes.FakeInventory.armorSlot(itemId);
            if (slot == 8 && inv.boots != null) return "armor slot 8 holds " + inv.boots + "; never moved out";
            if (slot != 8 && inv.worn.containsKey(slot)) return "armor slot " + slot + " holds " + inv.worn.get(slot).id;
            SimFakes.FakeInventory.FakeArmor rec = null;
            int i = fromSlot - SimFakes.FakeInventory.ItemIdsSlots.MAIN_START;
            if (fromSlot >= 0 && fromSlot < SimFakes.FakeInventory.ItemIdsSlots.FRESH_START) {
                if (i < 0 || i >= inv.carriedArmor.size() || !inv.carriedArmor.get(i).id.equals(itemId))
                    return "slot " + fromSlot + " holds no " + itemId;
                rec = inv.carriedArmor.get(i);
            } else if (fromSlot < 0) {
                for (SimFakes.FakeInventory.FakeArmor a : inv.carriedArmor) if (a.id.equals(itemId)) { rec = a; break; }
            } else {
                long recorded = inv.carriedArmor.stream().filter(a -> a.id.equals(itemId)).count();
                if (inv.count(itemId) <= recorded) return "slot " + fromSlot + " holds no new " + itemId;
            }
            if (!inv.remove(itemId, 1)) return "no " + itemId;
            if (rec != null) inv.carriedArmor.remove(rec);
            armorOn++;
            if (rec != null) inv.wear(rec);
            else if (slot == 8) inv.boots = itemId; // a new pair: the fakes' boots keep their last durability
            else inv.wear(new SimFakes.FakeInventory.FakeArmor(itemId, SimFakes.FakeInventory.armorMax(itemId),
                inv.freshArmorMending));
            return null;
        });
    }

    @Override
    public BotTask unequipBoots() {
        return simple("unequip", "take off the boots", 1, () -> {
            if (inv.boots == null) return "no boots worn";
            takeOff(8);
            return null;
        });
    }

    @Override
    public BotTask unequipArmor(int armorSlot) {
        return simple("unequip", "take off armor slot " + armorSlot, 1, () -> {
            boolean worn = armorSlot == 8 ? inv.boots != null : inv.worn.containsKey(armorSlot);
            if (!worn) return "nothing worn in armor slot " + armorSlot;
            if (inv.freeSlots() <= 0) return NO_FREE_SLOT + " for the piece in armor slot " + armorSlot;
            takeOff(armorSlot);
            return null;
        });
    }

    /** The worn piece in {@code slot} goes into the inventory, its durability kept. */
    private void takeOff(int slot) {
        armorOff++;
        if (slot == 8) {
            inv.carry(new SimFakes.FakeInventory.FakeArmor(inv.boots, inv.bootsUses(), inv.bootsMending));
            inv.boots = null;
        } else {
            inv.carry(inv.worn.remove(slot));
        }
    }

    @Override
    public BotTask waitTicks(int ticks) {
        return simple("wait", "wait " + ticks, Math.max(1, ticks), () -> null);
    }

    /** The offhand without its item, the inventory holding it (the airplace guard's repair is wanted). */
    boolean offhandWanted;
    /** The repair task fails this many times before it works. */
    int offhandRepairFailures;
    /** Repairs run, and of them while another task (a lane) was parked. */
    int offhandRepairs;
    int offhandRepairsWhileParked;

    /**
     * The guard's server check is wanted (the cache lost the totem; drill gs2): the repair asks the server, whose full
     * inventory shows the totem in the offhand, so the repair puts {@link #checkFinds} totems there.
     */
    boolean offhandCheck;
    int checkFinds = 1;
    int offhandChecks;

    @Override
    public boolean offhandRepairWanted() {
        return offhandWanted || offhandCheck;
    }

    @Override
    public boolean offhandCheckWanted() {
        return offhandCheck;
    }

    @Override
    public BotTask restoreOffhand() {
        return new Task("restore offhand") {
            @Override
            protected TaskStatus onTick() {
                offhandRepairs++;
                if (lastOther != null && !lastOther.isFinished() && lastOther.parked()) offhandRepairsWhileParked++;
                if (offhandRepairFailures > 0) {
                    offhandRepairFailures--;
                    return fail("the offhand still holds the clay");
                }
                if (offhandCheck) {
                    offhandCheck = false;
                    offhandChecks++;
                    if (checkFinds <= 0) return fail("no minecraft:totem_of_undying to put back (the server's full inventory has none)");
                    inv.offhand.put("minecraft:totem_of_undying", checkFinds);
                }
                offhandWanted = false;
                return TaskStatus.SUCCESS;
            }
        };
    }

    @Override
    public BotTask eat(List<String> foods) {
        return remember(new Task("eat") {
            int left = EAT_TICKS;

            @Override
            protected TaskStatus onTick() {
                // status() is published at the end of each engine tick: on the first tick it shows the creating phase
                if (left == EAT_TICKS) {
                    eatPhases.add(phaseProbe.get());
                    if (lastOther != null && !lastOther.isFinished() && lastOther.parked()) eatsWhileParked++;
                }
                probe("eat");
                if (--left > 0) return TaskStatus.RUNNING;
                done = true;
                if (inv.foodLevel >= 20) return TaskStatus.SUCCESS;
                // like EatTask: only the foods the engine lists, never anything else
                String id = null;
                for (String f : foods) if (inv.count(f) > 0) { id = f; break; }
                if (id == null) return fail("no food in the inventory");
                eatenIds.add(id);
                inv.remove(id, 1);
                inv.foodLevel = Math.min(20, inv.foodLevel + FOOD_POINTS.getOrDefault(id, 4));
                maps.held = null; // the food is in the main hand now
                eats++;
                return TaskStatus.SUCCESS;
            }
        });
    }

    /** A useBlock the engine issued: the block and where the bot's feet were then (where its walk there starts). */
    record UseIssued(Pos pos, double x, double y, double z) { }

    final List<UseIssued> useIssued = new ArrayList<>();

    // ---- helpers ----

    /**
     * Withdraws like the production ContainerTransferTask: whole-stack shift-clicks chosen by {@link TransferPlanner}
     * (a request may be overshot by less than a stack; a stack that does not fit completely is not moved). The
     * container holds full stacks plus one remainder stack per item; the inventory holds compact stacks.
     */
    private TransferResult withdrawWholeStacks(Pos pos, Container c, Map<String, Integer> wanted) {
        List<TransferPlanner.Stack> box = stacks(c.items, 0);
        List<TransferPlanner.Stack> player = stacks(inv.items, 1000);
        TransferPlanner.Plan plan = TransferPlanner.planWithdraw(box, wanted, player, inv.freeSlots());
        Map<String, Integer> moved = new TreeMap<>();
        plan.expected().forEach((id, n) -> {
            c.items.merge(id, -n, Integer::sum);
            if (c.items.get(id) <= 0) c.items.remove(id);
            if (id.endsWith("_boots") && inv.boots == null) withdrawnBoots(id, n);
            inv.add(id, n);
            moved.put(id, n);
        });
        return new TransferResult(moved, snapshot(pos, c), plan.problem());
    }

    /** Top-up fills done ({@link #withdrawTopUp}), for tests. */
    int topUps;

    /**
     * TOP_UP as the production pattern: per item, a carried partial stack r is filled to the max from a full container
     * stack when whole stacks would leave the item in one slot more ({@code ceil((w + r) / max) <= ceil(w / max)}),
     * then whole stacks for the rest. The fake inventory is compact, so its partial stack is {@code count mod max}.
     */
    private TransferResult withdrawTopUp(Pos pos, Container c, Map<String, Integer> wanted) {
        Map<String, Integer> rest = new java.util.LinkedHashMap<>();
        Map<String, Integer> moved = new TreeMap<>();
        for (var e : wanted.entrySet()) {
            String id = e.getKey();
            int w = e.getValue() == null ? 0 : e.getValue();
            int max = Math.max(1, catalog.maxStackSize(id));
            int r = inv.items.getOrDefault(id, 0) % max;
            boolean saves = w > 0 && r > 0 && (w + r + max - 1) / max <= (w + max - 1) / max;
            if (saves && c.items.getOrDefault(id, 0) >= max) {
                int fill = max - r;
                c.items.merge(id, -fill, Integer::sum);
                if (c.items.get(id) <= 0) c.items.remove(id);
                inv.add(id, fill);
                moved.merge(id, fill, Integer::sum);
                topUps++;
                w -= fill;
            }
            if (w > 0) rest.put(id, w);
        }
        TransferResult whole = withdrawWholeStacks(pos, c, rest);
        whole.moved().forEach((id, n) -> moved.merge(id, n, Integer::sum));
        return new TransferResult(moved, snapshot(pos, c), whole.problem());
    }

    private List<TransferPlanner.Stack> stacks(Map<String, Integer> items, int firstSlot) {
        List<TransferPlanner.Stack> out = new ArrayList<>();
        int slot = firstSlot;
        for (var e : items.entrySet()) {
            int max = Math.max(1, catalog.maxStackSize(e.getKey()));
            for (int left = e.getValue(); left > 0; left -= max)
                out.add(new TransferPlanner.Stack(slot++, e.getKey(), Math.min(max, left), max));
        }
        return out;
    }

    private static ContainerSnapshot snapshot(Pos pos, Container c) {
        return new ContainerSnapshot(pos, "chest", new TreeMap<>(c.items), c.freeSlots(), c.slots);
    }

    /**
     * What a flat map held at {@code stand} (on the art layer) shows, like vanilla MapItem: per column the topmost
     * block with a map colour, so a coloured block above the art layer (the platform above in use) hides the art
     * pixel; else the art layer block, else the floor one block lower. Shaded by the height of the column to the north
     * (the row north of the map for the first row): higher = HIGH, lower = LOW, same = NORMAL.
     */
    byte[] render(Pos stand) {
        int minX = Math.floorDiv(stand.x() + 64, 128) * 128 - 64;
        int minZ = Math.floorDiv(stand.z() + 64, 128) * 128 - 64;
        int artY = stand.y();
        byte[] out = new byte[128 * 128];
        int[] northHeight = new int[128];
        for (int px = 0; px < 128; px++) northHeight[px] = top(minX + px, minZ - 1, artY)[1];
        for (int pz = 0; pz < 128; pz++) {
            for (int px = 0; px < 128; px++) {
                int[] t = top(minX + px, minZ + pz, artY);
                int shade = t[1] == northHeight[px] ? 1 : t[1] < northHeight[px] ? 0 : 2;
                out[pz * 128 + px] = (byte) ((t[0] << 2) | shade);
                northHeight[px] = t[1];
            }
        }
        return out;
    }

    /** {colour, height} of the column's topmost block with a map colour, down to the art layer, else the floor. */
    private int[] top(int x, int z, int artY) {
        Pos art = new Pos(x, artY, z);
        for (int y = world.topY(art); y > artY; y--) {
            BlockSpec b = world.block(new Pos(x, y, z));
            int c = b.isAir() ? 0 : catalog.mapColorId(b);
            if (c > 0) return new int[]{c, y};
        }
        BlockSpec b = world.block(art);
        int c = b.isAir() ? 0 : catalog.mapColorId(b);
        return c > 0 ? new int[]{c, artY} : new int[]{SimFakes.FLOOR_COLOR, artY - 1};
    }

    void addContainer(Pos p, Map<String, Integer> items) {
        Container c = new Container();
        c.items.putAll(items);
        containers.put(p, c);
        world.set(p, BlockSpec.of("minecraft:chest", Map.of("facing", "north", "type", "single", "waterlogged", "false")));
    }

    /** Wires a fake piston clear to a platform's button: clears the clearables after {@code delay} ticks. */
    void clearMachine(MapArtConfig.PlatformCfg p, Pos button, int delay, java.util.function.BooleanSupplier works,
                      Runnable broke) {
        useHandlers.put(button, () -> schedule(delay, () -> {
            if (!works.getAsBoolean()) return;
            for (int pz = 0; pz < 128; pz++) {
                for (int px = 0; px < 128; px++) {
                    Pos c = new Pos(p.minX + px, p.artY, p.minZ + pz);
                    BlockSpec b = world.block(c);
                    if (b.isAir()) continue;
                    if (p.clearableBlocks.contains(b.id())) world.set(c, BlockSpec.AIR);
                    else broke.run();
                }
            }
        }));
    }
}
