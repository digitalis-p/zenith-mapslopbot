# Configuration

The plugin keeps its settings in `plugins/config/mapart.json`, inside ZenithProxy's working directory. ZenithProxy
creates the file with the defaults below the first time the plugin loads.

- The plugin reads the file once, when ZenithProxy starts. No command re-reads it.
- ZenithProxy writes its in-memory copy back to the file whenever a setting changes (for example after `mapart on`
  or `mapart pause`). Edits you make while ZenithProxy runs can be overwritten.
- So: stop ZenithProxy, edit the file, start ZenithProxy.
- A file that is not valid JSON stops ZenithProxy from starting. Check it with a JSON validator before you start.
- Keys you leave out keep their defaults. Unknown keys are ignored.

The `site` section describes your base and has its own page: [SITE_CONFIG.md](SITE_CONFIG.md).

## What to set first

Most of the defaults are tuned for 2b2t and can stay as they are. A new setup needs:

| Key | Why |
|---|---|
| `site` | Your platforms and chests. Nothing works without it. See [SITE_CONFIG.md](SITE_CONFIG.md). |
| `discord.enabled`, `discord.token`, `discord.channelId` | Uploads and status in Discord. Optional; the console works without it. |
| `discord.alertMention` | Who gets pinged when the bot needs a person. |
| `build.keepItems`, `build.foodItems` | Must match the tools and food you give the bot. |
| `teammates` | Players the bot must never fight. |
| `enabled` | Turn on last, once `mapart site` reports no problems. |

Look at these too: `build.toolSafetyFloor`, `build.disableModules`, `combat.mobs`, `safety.maxDistanceFromBase`,
`map.nameFormat`.

## Top level

| Key | Default | What it does |
|---|---|---|
| `enabled` | `false` | Turns the builder on. While off, uploads are still checked and queued. `mapart on`/`off` sets it. |
| `paused` | `false` | Set by `mapart pause`, `resume` and `stop`, and kept across restarts. You do not normally edit it. |
| `pauseReason` | `""` | Why the builder is paused, as shown by `status`. Set by the plugin. |
| `dataDir` | `"plugins/mapart"` | Folder for jobs, progress and the stock index, relative to ZenithProxy's working directory. |
| `teammates` | `[]` | Players that are never treated as a threat or a fight target. Each entry is a UUID or a player name, for example `"jared2013"`. |

## discord

The plugin runs its own Discord bot, separate from ZenithProxy's.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `false` | Starts the plugin's Discord bot. `mapart discord on`/`off` sets it. |
| `token` | `""` | Bot token of the Discord application the plugin logs in as. Do not reuse ZenithProxy's bot token. |
| `channelId` | `""` | The one channel for uploads, commands and reports. Anyone who can post there can submit maps. |
| `commandPrefix` | `"!map"` | Prefix for commands in the channel. |
| `alertMention` | `""` | Text put in front of alerts that need a person, such as `<@123456789>` or `<@&roleId>`. Empty means no ping. |
| `verbose` | `false` | Also posts routine steps and periodic progress cards. Off, the channel gets alerts, job start and end, pause and resume, and one message per finished map. `mapart verbose on`/`off` sets it. |
| `progressIntervalMinutes` | `15` | Minutes between progress cards when `verbose` is on. |
| `maxAttachmentBytes` | `26214400` (25 MiB) | Largest upload the bot accepts. |
| `coordinates` | `"REAL"` | `REAL` shows world coordinates in messages. `RELATIVE` shows them relative to the first enabled platform's map centre (Y unchanged). |
| `presence` | `true` | Shows what the bot is doing as its Discord status. |
| `presenceIntervalSeconds` | `15` | Least seconds between status updates. Values below 15 count as 15. |

## map

Filling, locking, naming and block substitution.

| Key | Default | What it does |
|---|---|---|
| `holdTicks` | `100` | Ticks the filled map is held before its pixels are checked. |
| `holdUntilMatch` | `true` | Ends the hold early, after at least 20 ticks, once the map shows exactly the art. |
| `maxFillAttempts` | `3` | Fill and check attempts before the bot alerts. |
| `rename` | `true` | Renames each finished map at the anvil. Needs an anvil, XP chests and anvil chests in `site`. |
| `nameFormat` | `"{job} {x},{z}"` | Anvil name for maps of a multi-map job. `{job}`, `{x}`, `{z}` and `{id}` are filled in. Cut to 50 characters. |
| `nameFormatSingle` | `"{job}"` | Anvil name for a one-map job. Same placeholders. |
| `xpBottlesPerTrip` | `32` | XP bottles taken per trip to the XP chest. |
| `substituteSameColour` | `true` | At upload, swaps a block your base has no chest for (and eyeblossoms) for a stocked block with the same map colour. Only blocks every enabled platform can clear are used. |
| `substituteStocked` | `false` | Also swaps stocked blocks for a same-colour block that is easier or cheaper to build on that map. |
| `substituteDeny` | `[]` | Block ids never swapped in either direction, such as `"minecraft:mushroom_stem"`. |
| `eyeblossomWaitMinutes` | `12` | A map with eyeblossoms is filled only when they show the right state for the time of day. Longest wait before the job is marked BLOCKED. |
| `occludedReminderMinutes` | `30` | While blocks above the art would show on the map (someone using a platform overhead), the bot waits. Discord reminder interval for that wait; 0 means none. |
| `occludedMaxWaitMinutes` | `0` | That wait goes BLOCKED after this many minutes. 0 means wait as long as it takes. |
| `raiseZenithMapCache` | `true` | Raises ZenithProxy's `debug maxCachedMaps` to 4096 at load when it is below 2048. |
| `fillNearStand` | `false` | Fills the map from the nearest cell of the tile that still covers the whole map, instead of walking to the map station first. |

## safety

The teleport guard pauses the bot on an unexpected server teleport. Only a manual `resume` restarts it. The plugin
never disconnects for low health; set that up in ZenithProxy's AutoDisconnect.

| Key | Default | What it does |
|---|---|---|
| `teleportGuard` | `true` | Halts the bot (saved pause and alert) when the server moves it unexpectedly. |
| `teleportStopDistance` | `8.0` | Teleports farther than this (blocks) halt the bot. Shorter ones count as setbacks. |
| `maxDistanceFromBase` | `256` | Halts the bot when it is farther than this (horizontal blocks) from every enabled platform centre. |
| `leaveWhenHurt` | `true` | When health keeps dropping while the bot stands still, it first walks to dry, open floor nearby, and only then goes BLOCKED. |
| `requireTotem` | `true` | With no totem in the offhand or inventory: one alert, and no walking task runs until a totem is back in the offhand. |
| `setbackAlertCount` | `10` | Alerts (without stopping) after this many small setbacks within `setbackAlertWindowSeconds`. 0 means never. |
| `setbackAlertWindowSeconds` | `300` | Window for `setbackAlertCount`. |
| `setbackBurstCount` | `5` | This many setbacks within `setbackBurstWindowMillis` make the bot stop and stand still for a moment. 0 means never. |
| `setbackBurstWindowMillis` | `2000` | Window for `setbackBurstCount`. |
| `setbackBackoffSeconds` | `5` | How long that stop lasts. 0 means no stop. |
| `setbackBackoffMaxPerWindow` | `3` | Stops allowed within 2 minutes. The next burst pauses the bot with a "Stuck" alert. 0 means never pause. |
| `setbackRecentPath` | `true` | A long teleport back onto a spot the bot stood on recently counts as an anti-cheat setback, not a reason to halt. |
| `setbackRecentSeconds` | `60` | How recent that spot must be. |
| `setbackRecentMaxDistance` | `32.0` | How far back such a setback may go (blocks). |
| `setbackRecentSpeed` | `12.0` | Accepts longer rollbacks, up to `setbackRecentFarMaxDistance`, if the bot could have walked that far at this many blocks per second. 0 turns this off. |
| `setbackRecentFarMaxDistance` | `160.0` | Longest rollback `setbackRecentSpeed` accepts (blocks). |
| `setbackResyncRepeats` | `3` | After this many setbacks onto the same spot within 2 minutes, the bot stops and asks the server for the real blocks around it. 0 means never. |
| `startGraceTicks` | `100` | Ticks after the bot starts in which teleports count as the login position sync. |

## combat

Fights hostile mobs on platforms whose `fightMobs` is on (soul sand by default). During a fight the plugin turns on
ZenithProxy's KillAura, then hands control back.

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | Master switch for fighting. |
| `mobs` | `pillager`, `vindicator`, `ravager`, `evoker`, `witch`, `illusioner`, `zombie` | Mob types fought. Add `player` only if you really want that; `teammates` are always spared. |
| `radius` | `16` | Blocks around the tile and around the bot in which a listed mob starts a fight. |
| `selfDefense` | `true` | A listed mob that hits the bot is fought anywhere, on any platform. |
| `creeperHitAndRun` | `true` | Fights creepers by hitting once and backing off until the fuse resets. Off, creepers are ignored. |
| `creeperRetreatBlocks` | `7.5` | How far the bot backs off from a creeper after a hit. |
| `creeperWaitSeconds` | `2.0` | How long it waits out there before the next hit. |
| `quietSeconds` | `3` | The fight ends once no listed mob has been in the area this long. |
| `maxSeconds` | `120` | A fight still going after this long ends with an alert. |
| `cooldownSeconds` | `60` | After a fight that ran out of time, mobs start no new fight for this long. |

Two related keys sit in `build`: `fightSetAsideUnreachable` and `fightSetAsideMinutes` (see "Other" below).

## build

`build` holds over 200 keys. Nearly all are tuning switches with tested defaults. A switch described as "off by
default, experimental" has not been proven against 2b2t's anti-cheat; leave it off unless you test it yourself.

Keys a new user should check: `placeMode`, `reach`, `airPlaceRange`, `keepItems`, `foodItems`, `foodToKeep`,
`toolSafetyFloor`, `reservedSlots`, `disableModules`, `breakMode`.

### Placement

The defaults match the common 2b2t printer settings: airplace on, silent Grim rotations, reach 4.5, airplace range 5.

| Key | Default | What it does |
|---|---|---|
| `placeMode` | `"AIRPLACE"` | `AIRPLACE` places blocks into the empty cell through an offhand swap, with no look. `LOOK` aims at the block below and clicks it. Blocks whose placed shape follows the look (lichen, lanterns, torches) always use LOOK. |
| `reach` | `4.5` | Longest eye-to-target distance for looked-at clicks, containers and breaking. |
| `airPlaceRange` | `5.0` | Longest eye-to-cell distance for airplace. |
| `airPlaceLaneHalfWidth` | `-1` | Half width of airplace lanes. -1 picks the widest that fits `airPlaceRange` (9-wide lanes at 5.0). |
| `laneHalfWidth` | `2` | Half width of LOOK lanes (5-wide lanes). |
| `rotatePlayer` | `false` | `true` turns the bot's real rotation to each target. `false` keeps it and sends the look silently. |
| `grimRotate` | `true` | With `rotatePlayer` off, sends the look as an extra packet that Grim accepts. |
| `offhandItem` | `"minecraft:totem_of_undying"` | What the offhand holds while building in airplace mode. Blank means the totem. |
| `offhandRepair` | `true` | Puts `offhandItem` back into the offhand when something else ended up there. |
| `lookSideFaces` | `true` | LOOK blocks may be placed against a neighbour's side face when the top face is hidden. |
| `maxRepairPasses` | `4` | Check-and-repair passes on a map before the bot gives up on it. |

### Placement speed (tuning)

| Key | Default | What it does |
|---|---|---|
| `pipelinePlacement` | `true` | Aims at the next block in the same tick as the current click. |
| `placeIntervalStartTicks` | `2` | Starting gap between clicks, in ticks. The gap adapts to rejections. |
| `placeIntervalMinTicks` | `1` | Smallest gap. |
| `placeIntervalMaxTicks` | `8` | Largest gap. |
| `placePacer` | `"BURST"` | How rejections slow placing down. `BURST` ignores isolated refusals; `LEGACY` slows on every one. |
| `placeBurstWindowTicks` | `100` | `BURST`: two refusals within this many ticks slow placing down. |
| `placeBurstRecoverConfirms` | `8` | `BURST`: successes in a row before placing speeds up again. |
| `placeRecoverConfirms` | `16` | `LEGACY`: successes in a row before placing speeds up again. |
| `placeStackSettleTicks` | `1` | Tuning. Ticks after a click's answer before its stack count is trusted. |
| `placeStackSwitch` | `true` | When one hotbar stack runs out mid-lane, carries on from another stack of the same item. |
| `placeConfirmTicks` | `40` | Ticks a click may go unanswered before it counts as unknown. |
| `placeConfirmMaxTicks` | `200` | Longest wait for a click's answer before it is retried. |
| `reachStepMargin` | `true` | Tuning. Keeps the last walking step inside reach as a margin. |

### Fast placement (experimental, off by default)

More than one airplace per tick. Grim's MultiPlace check targets exactly this.

| Key | Default | What it does |
|---|---|---|
| `placeClicksPerTick` | `1` | Airplaces per tick in a lane walk. 1 is normal speed and turns the rest of this table off. |
| `placeClickWindowTicks` | `7` | Ticks of the click window that keeps the bot under Paper's packet limit. |
| `placeClickWindowMax` | `9` | Most clicks in any such window. |
| `placeSubTickDispatch` | `false` | Sends prepared clicks between ticks. |
| `placeClickWindowMillis` | `310` | Window length in milliseconds when clicks go between ticks. |
| `placeAdaptiveWindow` | `true` | Narrows or widens the window based on dropped clicks. |
| `placeClickWindowTicksMin` | `6` | Smallest adaptive window. |
| `placeClickWindowCleanClicks` | `60` | Clean clicks before the window narrows by a tick. |
| `placeDropRateMax` | `0.02` | Share of dropped clicks above which the window widens. |
| `placeLatchFails` | `3` | Refusals that turn fast placement off for the rest of the run. |
| `placeLatchWindow` | `20` | Clicks looked at for `placeLatchFails`. |
| `placeRollingClickWindow` | `true` | With `placeHotbarLookahead`, also limits clicks in every rolling window. |

### Hotbar (tuning)

| Key | Default | What it does |
|---|---|---|
| `placeHotbarBelady` | `true` | When a stack moves into the hotbar, it replaces the one needed last. |
| `placeHotbarStacks` | `true` | Sets up the hotbar before each lane. |
| `placeAirPlaceBatch` | `true` | Uses one offhand swap pair for all airplaces in a tick. |
| `hotbarMoveFence` | `true` | After moving an item into the hotbar, waits for the server to confirm it. |
| `placeDuringMoveFence` | `true` | Keeps clicking from other slots during that wait. |
| `placeHotbarPrefetch` | `true` | Moves the next needed material into a free slot while standing still in a lane. |
| `placeHotbarLookahead` | `false` | Experimental. Prefetches shortages in the next 40 cells. |
| `placeHotbarLookaheadEvict` | `false` | Experimental. Lets lookahead replace stacks needed later. |
| `unknownSlotResync` | `true` | Asks the server for the full inventory when a hotbar slot's content is unknown. |
| `unknownSlotTrustTicks` | `100` | Ticks before an unknown slot is trusted again without a server report. 0 means never. |
| `hotbarMoveForeignStateId` | `false` | Tuning. Sends hotbar moves in a way that always gets a full inventory answer. |
| `laneLayoutPin` | `true` | Stops the pre-lane hotbar layout from moving the same slot twice. |

### Lanes (tuning)

The bot builds in straight lanes and walks along each one. These switches handle walls, pockets and lane ends.

| Key | Default | What it does |
|---|---|---|
| `walkWalledLanes` | `true` | Walks over a partly built lane instead of placing the rest from stand spots. |
| `trimLaneEnds` | `true` | Walks between the full blocks at a lane's ends. |
| `laneOpenSegment` | `true` | Walks the longest open part of a walled lane; the rest goes to stand spots. |
| `laneWallBrake` | `true` | Brakes early so the bot does not slide into a wall at a lane end. |
| `laneBreakOut` | `true` | When the way to a lane is walled off by placed blocks the clear removes anyway, breaks the fewest of them and places them again later. |
| `dripstoneBreakThrough` | `true` | Breaks through up to six art blocks to recover a failed walk. |
| `laneStartSpots` | `true` | A lane whose start cannot be reached is placed from stand spots instead of failing. |
| `laneStartTail` | `false` | Places cells behind the lane start before the walk, not after. |
| `laneStartOnFloor` | `false` | Keeps the walk to a lane start off the wall along the tile's edge. |
| `laneWayOut` | `true` | Never walls the bot in with blocks it cannot walk on. |
| `laneKeepReachable` | `true` | Never closes off a cell that still needs a block. |
| `laneStepBack` | `true` | Steps back a little for cells just passed out of reach. |
| `laneStepBackClearPath` | `false` | A step back never walks into a cell whose new block the bot has not seen yet. |
| `laneForwardClearPath` | `true` | The same check for cells beside the walking line ahead. |
| `pacedLaneWalk` | `false` | Experimental. Keeps walking at the speed the clicks allow instead of stop and go. |
| `placeInReachGreedy` | `false` | Experimental. Also places cells of later lanes that are in reach. |
| `laneEvenWidths` | `false` | Shares the 128 columns evenly between lanes instead of leaving a narrow last lane. |
| `laneWatchdogTicks` | `200` | A lane with no progress for this long ends and is planned again. 0 means off. |
| `laneWatchdogHotbarTicks` | `1200` | Longest time hotbar changes alone count as lane progress. 0 means always. |

### Check and repair

| Key | Default | What it does |
|---|---|---|
| `verifyBreakIn` | `true` | Reaches a walled-in missing cell by breaking up to 4 art blocks and placing them back. |
| `verifySealOut` | `true` | Refills several walled-in cells in one walk. |
| `verifyStockWaitFree` | `true` | Cells that only wait for materials do not use up repair passes. |
| `verifyProgressFree` | `true` | A pass that filled many missing cells does not count against `maxRepairPasses`. |

### Walking and pathing (tuning)

| Key | Default | What it does |
|---|---|---|
| `walkStepOut` | `true` | Steps out of a cell the pathfinder cannot start from (such as pointed dripstone) before asking for a path. |
| `baritoneBreakEscape` | `true` | Last resort of a failed walk: one try with the pathfinder allowed to break blocks. |
| `boxedInEscape` | `true` | When boxed in, walks back along its own track and paths again from there. |
| `walkShortTurns` | `false` | Sends large pathfinder turns as the equivalent short turn. |
| `standSpotBrake` | `true` | Comes to a full stop on a stand spot before aiming. |
| `standSpotMemory` | `true` | Remembers which spot worked for each container, in `stand-spots.txt` in `dataDir`. |
| `standSpotWalkable` | `true` | Uses only stand spots the pathfinder can walk on. |
| `containerColumnFloor` | `true` | Uses the lowest walkable row of a storage column as the stand row for chests above it. |
| `rowRun` | `true` | Uses a platform's `runRow` for long walks along the storage. |
| `rowRunBand` | `6` | Blocks (in z) from the run row within which a walk uses it. |
| `rowRunMinDx` | `12` | Shortest walk (in x) that uses the run row. |
| `soulSandRoute` | `true` | Walks along soul sand rows when going to the sorter pickup, so Soul Speed works. |
| `soulSandRouteSpread` | `4` | Rows on each side looked at for that. |

### Edges and falls

| Key | Default | What it does |
|---|---|---|
| `edgeSneak` | `true` | Sneaks near drops, water, lava and no-go zones so the bot cannot slip off. |
| `edgeSpotsLast` | `true` | Uses stand spots next to such hazards only when nothing else works. |
| `edgeDescents` | `true` | Avoids stepping down toward a hazard straight ahead. |
| `edgeHoles` | `true` | Steers away from holes deeper than 3 blocks and from water or lava. |
| `edgePlannedFalls` | `true` | Never walks a path that falls more than 3 blocks. |
| `noGoCornerDetour` | `true` | Plans around a no-go zone's corner instead of failing the walk. |
| `fallRescue` | `true` | After a fall off the platform, swims or walks back to dry floor, or alerts. |
| `fallRescuePillar` | `true` | When it cannot walk out, climbs out with its own blocks and breaks them afterwards. |
| `fallRescueBlocks` | `netherrack`, `cobblestone`, `cobbled_deepslate`, `dirt` | Blocks used for that climb, first choice first. |
| `fallRescueAnyBlock` | `true` | With none of those carried, uses any suitable cheap full block. |
| `fallRescueKit` | `8` | Blocks of `fallRescueBlocks` kept in the inventory for the climb. 0 means no kit. |
| `fallRescueClearHoldSeconds` | `120` | During a piston clear, the climb waits until this many seconds after the clear started. 0 means no wait. |

### Sprinting (tuning)

| Key | Default | What it does |
|---|---|---|
| `sprintWalks` | `true` | Sprints on plain walks (to chests, lanes, buttons, between platforms). Never while placing, breaking or during a clear. |
| `sprintRestockLegs` | `false` | Sprints on restock walks even with `sprintWalks` off. |
| `sprintWalksLaneStart` | `false` | Also sprints on the short walk from one lane to the next. |
| `sprintMinFood` | `8` | Sprints only at this hunger or above. |
| `sprintStopDistance` | `4.0` | Stops sprinting this many blocks before the goal. |
| `sprintStartTicks` | `0` | Ticks at the start of a walk without sprint. |
| `sprintQuietTicks` | `5` | Clicks, placing and eating wait this many ticks after a sprint. |
| `sprintSync` | `true` | Keeps the sprint state the way a vanilla client does. |
| `sprintOnce` | `true` | One sprint per walk, started from rest. |
| `sprintRestartTicks` | `0` | Experimental. Lets a walk sprint again after this many ticks without. 0 means never. |
| `sprintLateStart` | `false` | Experimental. Lets a walk start sprinting after it has already begun moving. |

### Restock and inventory

| Key | Default | What it does |
|---|---|---|
| `reservedSlots` | `5` | Inventory slots kept free of materials for tools, food, maps and panes. |
| `keepItems` | diamond and netherite pickaxe, axe, shovel and boots; golden carrot, cooked beef, bread | Item ids the bot keeps and never deposits. |
| `restockPlanner` | `"AUTO"` | How each load is chosen. `AUTO`, `SPLIT`, `LANES` or `GREEDY`. Leave on `AUTO`. |
| `restockCarriedGuard` | `true` | Tuning. Stops the planner from choosing very small loads when stacks are already carried. |
| `restockLaneFlip` | `false` | Lets a load walk its lanes in the other direction when that is shorter. |
| `topUpStacks` | `true` | Tops up partly used stacks so each item takes as few slots as possible. |
| `exactMapAndPane` | `true` | Takes exactly one empty map and one glass pane, with the map's last load. |
| `mapPaneAtStore` | `false` | Picks up the next map's empty map and pane while storing the current one. |
| `exactAnvil` | `false` | Takes exactly one anvil when the anvil needs replacing, and puts spares back. |
| `mergeDrops` | `true` | Gets rid of leftovers and crowding items in one drop. |
| `lazyDrop` | `true` | Drops leftover materials only when that saves restock time. |
| `lazyDropSeconds` | `6` | Restock seconds a drop costs, for `lazyDrop`. |
| `crowdKeepReused` | `true` | Keeps a carried item a load needs again instead of returning it. |
| `crowdLaneCells` | `true` | When the inventory is crowded, returns the items needed furthest ahead. |
| `dropJunk` | `true` | Throws away `junkItems` at the platform's junk spot during a restock. |
| `junkItems` | 25 common mob drops (see below) | Items `dropJunk` may throw away. |
| `junkNukeDrops` | `true` | Counts what broken art blocks drop instead of themselves (clay balls, bricks) as junk. |
| `junkDropMaxDetour` | `40` | Longest walk to the junk spot. |
| `recoverDisconnectDrops` | `true` | After a reconnect, picks up valuables the server dropped at the bot's feet. |

Default `junkItems`: lead, rotten_flesh, bone, arrow, spectral_arrow, tipped_arrow, ominous_bottle, string,
spider_eye, gunpowder, white_banner, leather, feather, chicken, poisonous_potato, stick, wheat_seeds, slime_ball,
glass_bottle, rabbit_hide, sugar, glowstone_dust, iron_nugget, crossbow, bow.

### Stock and storage

The bot keeps an index of what each material chest holds and reads chests again when the index may be wrong.
"Near" chests are close to the platform in use; "far" chests are more than `farStorageBlocks` outside its map area.

| Key | Default | What it does |
|---|---|---|
| `stockMaxAgeMinutes` | `0` | Rescans a chest whose last reading is older than this. 0 means only when it turns out wrong. |
| `waitingRecheckMinutes` | `10` | How often a job waiting for materials checks the stock again. |
| `nearScanFirst` | `true` | Looks in near chests again before skipping a map or going to far storage. |
| `nearScanMaxAgeMinutes` | `10` | How long such a look stays valid. 0 means a new look every time. |
| `nearScanFullMaxAgeMinutes` | `60` | Minutes between full sweeps of every near chest. 0 means a sweep each far trip. |
| `nearRecheckAll` | `true` | Those looks open every near chest, not only the ones that should hold the item. |
| `nearRecheckAllSeconds` | `60` | How long a full near sweep stands for the next look. |
| `nearScanPersist` | `true` | Keeps the look times across restarts. |
| `seedDiscoveryFromStock` | `true` | After a restart, trusts the known chest list instead of walking to every region. `scan` finds new chests. |
| `seedDiscoveryPartial` | `true` | When you change the material regions, only new or changed regions are discovered again. |
| `farStorage` | `true` | Prefers near chests and saves far trips for when they are needed. |
| `farStorageBlocks` | `64` | Chests more than this many blocks outside a platform's map area are far storage for it. 0 means none are far. |
| `farTripMinCells` | `64` | No far trip for fewer blocks than this when a later load goes there anyway. |
| `farStorageBatching` | `true` | A far trip also brings what later lanes will need from there. |
| `farLoadFill` | `false` | A load that goes far anyway takes all of that item from far storage. |

### Partial builds

| Key | Default | What it does |
|---|---|---|
| `skipShortTiles` | `true` | Builds the first queued map the stock fully covers, instead of waiting on the next one in order. |
| `partialBuild` | `true` | Builds as much of a map as the stock allows, then waits on the platform for the rest. |
| `partialBuildMinShare` | `0.5` | A map not yet started is built partly only if the stock covers at least this share. |
| `partialBuildCarried` | `true` | Places what the bot already carries right away. |
| `partialTripMinCells` | `64` | Least number of blocks the stock must cover before a waiting map makes a trip for them. |
| `stockWaitWakeOnChange` | `true` | A waiting map checks again at once after a reconnect or when the inventory changes. |

### Sorter refills (platforms with `sorterRefills`)

For platforms whose collection system sorts cleared items back into nearby chests. See [SITE_CONFIG.md](SITE_CONFIG.md).

| Key | Default | What it does |
|---|---|---|
| `sorterRefillMinutes` | `3` | Minutes the near count of a short item may stay flat before the bot goes to far storage. |
| `sorterRefillMaxCells` | `640` | Items short by more than this are fetched from far storage at once. |
| `sorterRefillPollSeconds` | `20` | Seconds between looks at the sorter chests while waiting. |
| `sorterPickupMaxSeconds` | `90` | Longest stand at the sorter pickup cell. |
| `sorterPickupMinFreeSlots` | `1` | The pickup ends when this many slots or fewer are free. |
| `sorterPickupStallSeconds` | `8` | The pickup ends when nothing arrived for this long. |
| `sorterPickupReprimes` | `2` | Times the bot walks back to the prime cell to wake the sorter before giving up. |
| `sorterPickupWatchRadius` | `24` | Items moving within this many blocks keep the pickup going. 0 means off. |
| `sorterPickupFarMinCells` | `256` | After a pickup, items still short by this many come from far storage. |
| `pickupThrowBack` | `true` | Throws picked-up items the map does not need back into the sorter. |

### Containers (tuning)

| Key | Default | What it does |
|---|---|---|
| `containerOpenMinMillis` | `700` | Least milliseconds between two container opens. |
| `containerClickTicks` | `0` | Ticks between container clicks. 0 uses ZenithProxy's own `actionDelayTicks`. |
| `containerAckTicks` | `8` | With `containerClickTicks` above 0, how long to wait for the server's answer to a click. |
| `containerAnswerEarly` | `true` | Closes the menu as soon as the transfer shows. |
| `containerAnswerWaits` | `false` | Ends each wait on the server's answer instead of a fixed time. |
| `containerShiftAnswerTicks` | `20` | Longest wait for a shift-click's answer when `containerAnswerWaits` is on. |
| `containerSettleFast` | `false` | Skips the 2 settle ticks after closing a menu. |
| `containerClickEveryTick` | `false` | Experimental. One container click every tick. |
| `coastClick` | `false` | Opens a container while still sliding to the stand spot. |
| `swapWithoutCursor` | `true` | Moves armour with hotbar-key swaps, so nothing sits on the cursor during a disconnect. |
| `mapSwapsWithoutCursor` | `true` | The same for the map at the cartography table and anvil. |

### Food, tools and armour

Tools and armour never break. The bot mends them with XP bottles, swaps them for spares, or stops.

| Key | Default | What it does |
|---|---|---|
| `eatFoodLevel` | `14` | Eats, standing still, at this hunger or below (or at 10 health or below). 0 means the plugin never eats and leaves ZenithProxy's AutoEat as it is. |
| `foodToKeep` | `32` | Food items carried, topped up from the food chests. |
| `foodItems` | `golden_carrot`, `cooked_beef`, `bread` | The only foods the bot eats and fetches, in order of preference. Golden apples are never eaten. |
| `toolSafetyFloor` | `20` | No block is broken with a tool that has this many uses left or fewer. |
| `mendBelowPercent` | `50` | A Mending tool at or below this durability is mended at the next stop. |
| `mendTargetPercent` | `95` | Mending stops at this durability. |
| `nukeMend` | `true` | Brings XP for the next nuke and mends at a lane end when a tool is due. |
| `mendDuringClear` | `true` | Mends tools while waiting for the piston clear. |
| `mendWatchdogMinutes` | `10` | A tool or armour piece left unmended this long is mended at the next safe point. 0 means off. |
| `wearBoots` | `true` | Wears carried boots from `keepItems` if the feet slot is empty. |
| `bootsMendBelowPercent` | `50` | Worn boots below this durability are mended between tasks. 0 means only at the rename. |
| `gearGuard` | `true` | Watches all worn armour and mends, parks or takes off a piece before it breaks. |
| `armorMendBelowPercent` | `50` | Other worn Mending armour below this is mended. |
| `armorParkPercent` | `25` | Below this, the running task stops early for the mend. 0 means only between tasks. |
| `armorOffPercent` | `6` | At or below this, a piece that cannot be mended is taken off. |
| `heldItemGuard` | `true` | Blocks any dig or attack packet while the held tool is at the safety floor. |

### Breaking

Before each piston clear the bot breaks ("nukes") the full blocks of the old map. The same code breaks wrong blocks
during repairs.

| Key | Default | What it does |
|---|---|---|
| `breakMode` | `"FAST"` | `FAST` breaks blocks itself and sweeps large areas while walking. `DOUBLE` mines two blocks at once. `SINGLE` uses ZenithProxy's left click, one block at a time. |
| `nukeStartsPerTick` | `1` | Instant breaks per tick. 2b2t refuses more than 1. |
| `nukeMaxDigsPerSecond` | `100` | Most dig packets per second. |
| `nukeReach` | `-1` | Breaking reach. -1 uses `reach`. |
| `nukeStopDelayTicks` | `5` | Ticks after finishing a slow block before the next one starts. 5 is vanilla. |
| `nukeSweep` | `true` | Sweeps large flat areas in lanes, breaking while walking. |
| `nukeSweepMinTargets` | `48` | Fewer targets than this are not swept. |
| `nukeLaneHalfWidth` | `4` | Half width of sweep lanes (9 wide). |
| `nukeLaneAxis` | `"AUTO"` | Sweep direction: `AUTO`, `X` or `Z`. |
| `nukeSweepBreaksWay` | `false` | Breaks clearable blocks in the way instead of walking around them. |
| `nukeEdgeGuard` | `true` | Keeps the sweep away from drops, water, lava and no-go zones. |
| `nukePocketGuard` | `true` | Keeps the bot out of pockets of blocks it cannot walk on. |
| `nukeHasteMarginTicks` | `40` | A block counts as instant through Haste only while at least this many ticks of Haste are left. |
| `nukeSwingEachStart` | `false` | One arm swing per instant break instead of one per tick. |
| `nukePairSlow` | `true` | Lets the server finish slow blocks by itself while the bot walks on. Turns itself off if the server refuses. |
| `nukeLoadFromCentre` | `true` | On platforms with `stayDuringClear`, a map with nothing to break sends the bot straight to the clear stand instead of the map station first. |
| `nukeLeftoverRoute` | `true` | Breaks what the sweep missed on one planned route. |
| `nukeInstantAckTicks` | `2` | Tuning. Ticks to wait for a broken block's air before retrying it. |
| `nukeMineMoving` | `false` | Mines slow blocks without stopping the walk. |
| `nukeHoldAtSpeed` | `false` | Looks ahead at walking speed so blocks are not passed out of reach. |
| `nukeTour` | `false` | For sparse maps, walks a shorter route between groups of targets. |
| `nukeTourMaxDensity` | `0.5` | Maps denser than this keep the normal sweep. |
| `nukeEntryCorner` | `false` | Enters the platform at the corner named by `nukeEndCorner` instead of the map station. |
| `nukeLambda` | `false` | Experimental. Lambda's break timing for slow blocks. |
| `nukeLambdaStartsPerTick` | `8` | With `nukeLambda`: instant breaks per tick. |
| `nukeLambdaFakeStarts` | `true` | With `nukeLambda`: the extra out-of-world dig packets. |
| `nukeLambdaBurst` | `true` | With `nukeLambda`: the burst that lets the next slow block start at once. |
| `nukeLambdaDoubleBreak` | `true` | With `nukeLambda`: mines two blocks at once. |
| `breakWatchdogSeconds` | `15` | A break task with no progress this long plans again. 0 means off. |
| `carryOver` | `false` | When the next map shares many blocks with the last one, breaks only what differs and skips the clear. |
| `carryOverMargin` | `0.1` | Carry-over must be estimated at least this much faster (0.1 = 10 %). |

### Elytra (experimental, off by default)

Flies restock legs with an elytra and rockets instead of walking. `mapart elytra on`/`off` switches `elytraLegs`
while running. The rocket chests are in `site.storage.rocketChests`.

| Key | Default | What it does |
|---|---|---|
| `elytraLegs` | `false` | Master switch. Restock legs fly when a safe corridor and landing spot exist. |
| `elytraWalkLegs` | `false` | Also flies walks between platforms and storage. |
| `elytraContainerLegs` | `false` | Also flies walks to the cartography table, anvil and output chests. |
| `elytraLaneStartLegs` | `false` | Also flies walks between lanes. |
| `elytraStandSpotLegs` | `false` | Also flies walks to stand spots. |
| `elytraMapStationLegs` | `false` | Also flies walks to the map station. |
| `elytraMinBlocks` | `60` | Shortest leg that flies. |
| `elytraArcLegs` | `false` | Flies an arc on legs too short for a straight flight. |
| `elytraArcMinBlocks` | `60` | Shortest leg for an arc. |
| `elytraHops` | `0` | Extra flights after a landing short of the goal. |
| `elytraOnTheWayBlocks` | `0` | A leg that walked plans a flight again every this many blocks. 0 means off. |
| `elytraOverWater` | `false` | Allows short stretches of water under a flight. |
| `elytraShortLandings` | `false` | Lands on a small spot at the goal. `mapart elytra short` switches it. |
| `elytraShortMinBlocks` | `30` | Shortest leg for such a landing. |
| `elytraLongHops` | `false` | Flies toward a goal that is not loaded yet in several hops. `mapart elytra hops` switches it. |
| `elytraKitFarTrip` | `false` | Lets a colour-suppression job walk to a far rocket chest. `mapart elytra kittrip` switches it. |
| `elytraGear` | `"flight"` | When the elytra is worn: `flight` (swapped on for each flight), `restock` (for the whole restock) or `worn` (always). |
| `elytraMinPercent` | `10` | No flight with the elytra at or below this durability. |
| `elytraMinHealth` | `14` | No flight below this health. |
| `elytraMaxRockets` | `3` | Rockets per flight. |
| `elytraCruise` | `8` | Highest a flight goes above the start floor. |
| `elytraSlideTicks` | `3` | Tuning. Ticks of slide counted after touchdown. |
| `elytraRocketStock` | `64` | Rockets topped up to during a restock. |
| `elytraRocketLow` | `16` | Below this many rockets, a restock tops them up. |
| `elytraTakeoffHoldSeconds` | `10` | No take-off this long after a start, reconnect, teleport or setback. |

### Colour suppression

Only used on a `COLOR_SUPPRESSION` platform. See [COLOR_SUPPRESSION.md](COLOR_SUPPRESSION.md).

| Key | Default | What it does |
|---|---|---|
| `suppressionLaneEndFallback` | `true` | Moves a lane end that lands in a no-go zone to the nearest free cell. |
| `suppressionDropLeftovers` | `true` | Returns unused materials after every pass. |
| `suppressionMendBeforeRide` | `true` | Mends worn boots on the platform before the ride. |
| `suppressionFreshLanes` | `true` | Drops already placed cells from the lane plan when the platform is not fully in view. |
| `suppressionMergeNoobline` | `false` | Places a level's noobline row with that level's main pass. |
| `suppressionMergeLevels` | `false` | Builds both levels of a layer in one sweep. Needs airplace. |

### Other

| Key | Default | What it does |
|---|---|---|
| `disableModules` | `AntiAFK`, `Spook`, `AutoFish`, `KillAura`, `SpawnPatrol`, `AutoDrop`, `AutoMend`, `AutoArmor`, `AutoTotem` | ZenithProxy modules turned off while the builder runs, and restored afterwards. AutoEat is added while `eatFoodLevel` is above 0. |
| `keepAliveSeconds` | `60` | While waiting, swings the arm and turns the head every this many seconds so the server does not kick for idling. 0 means off. |
| `fightSetAsideUnreachable` | `true` | A mob the bot could not reach is left alone until it hits the bot or comes close. |
| `fightSetAsideMinutes` | `10` | How long such a mob is left alone. |
