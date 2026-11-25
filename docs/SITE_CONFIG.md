# Site config

The `site` section of `plugins/config/mapart.json` describes your base: the platforms the bot builds on, the chests
it uses, and the places it must stay out of. Edit it while ZenithProxy is stopped (see
[CONFIGURATION.md](CONFIGURATION.md)). After a restart, run `mapart site` in the console or `!map site` in Discord. It
prints what it read and lists every problem it finds.

## What your base needs

- One or more flat 128 x 128 platforms, each lined up with the map grid, so one map covers exactly one platform.
- A full floor of one block under the art layer, and a permanent noobline row just north of the tile.
- Piston flying machines that sweep the art layer clean, started by a button or lever.
- Chests for materials (normally a sorter's output chests), and chests for empty maps, glass panes, finished
  maps, XP bottles, anvils, tools and food.
- A cartography table and an anvil.

The bot breaks the full blocks of a finished map itself. The flying machines remove everything else: carpets,
pressure plates, candles, pointed dripstone, decorated pots and similar.

## Coordinates and boxes

A position is `{"x": 100, "y": 64, "z": 200}`. A box is `{"min": {...}, "max": {...}, "name": "optional label"}`.
Both corners are included, in any order.

Positions named `standAt`, `center` or similar are feet cells: the block the bot's feet are in, one above the
block it stands on. Positions of buttons, chests and tables are the block itself.

## Minimal example

One moss platform with its map area from x 64 to 191 and z 192 to 319. The art layer is y 64 and the floor is
y 63. Remove the `//` notes before you use it; JSON has no comments.

```json
"site": {
  "dimension": "minecraft:overworld",
  "platforms": [
    {
      "id": "main",
      "kind": "MOSS",
      "enabled": true,
      "minX": 64,
      "minZ": 192,
      "artY": 64,
      "floorBlock": "minecraft:moss_block",
      "center": {"x": 128, "y": 70, "z": 256},                // feet cell on a bridge over the middle
      "clear": [
        {"pos": {"x": 130, "y": 70, "z": 256}, "mode": "BUTTON",
         "standAt": {"x": 128, "y": 70, "z": 256}}
      ],
      "collectionOn":  {"pos": {"x": 190, "y": 65, "z": 189}, "mode": "BUTTON"},
      "collectionOff": {"pos": {"x": 190, "y": 65, "z": 187}, "mode": "BUTTON"},
      "collectionIndicator": {"x": 190, "y": 65, "z": 188}    // a redstone lamp, lit while collection runs
    }
  ],
  "storage": {
    "materialRegions": [
      {"min": {"x": 64, "y": 64, "z": 180}, "max": {"x": 127, "y": 68, "z": 182}, "name": "sorter"}
    ],
    "returnChests": [{"x": 60, "y": 64, "z": 181}],
    "mapChests":    [{"x": 140, "y": 64, "z": 181}],
    "paneChests":   [{"x": 140, "y": 65, "z": 181}],
    "outputChests": [{"x": 140, "y": 66, "z": 181}],
    "xpChests":     [{"x": 141, "y": 64, "z": 181}],
    "anvilChests":  [{"x": 141, "y": 65, "z": 181}],
    "toolChests":   [{"x": 141, "y": 66, "z": 181}],
    "foodChests":   [{"x": 142, "y": 64, "z": 181}]
  },
  "cartographyTable": {"x": 144, "y": 64, "z": 181},
  "anvil": {"x": 145, "y": 64, "z": 181},
  "noGoZones": [
    {"min": {"x": 200, "y": 60, "z": 190}, "max": {"x": 210, "y": 80, "z": 320}, "name": "machine room"}
  ]
}
```

- `minX` 64 and `minZ` 192 are both 64 more than a multiple of 128, which is what the map grid needs.
- The material region is north of the tile, outside the map area. A role chest (maps, panes, output and so on)
  may sit inside a material region; it is left out of the material index.
- `protectedBlocks` is left out, so it keeps its default list.

## How to survey your base

Use F3 in game. "Targeted Block" gives a block's position. When you stand somewhere, the floored "XYZ" line is your
feet cell.

1. **Map area.** Find the north-west corner of the tile (smallest x, smallest z). Both values must satisfy
   `(value + 64) % 128 == 0`, for example -64, 64, 192 or -192. To check, fill a new map standing on the platform:
   its edges must match the tile's edges.
2. **Floor and art layer.** All 128 x 128 floor blocks are the same block at `artY - 1`. The art layer at `artY`
   is empty, with no blocks or water left from earlier maps.
3. **Noobline.** The row just north of the tile (z = `minZ - 1`) at the art layer's height holds a permanent row of
   blocks. The map's top row is shaded against it. The plugin never builds or breaks it.
4. **Clear.** Note every button or lever that starts the flying machines. If the machines park on alternating
   sides and only one button works at a time, pick a block of the parked machines for each button as its `probe`
   (with `probeBlock`). Time a full clear; `clearTimeoutSeconds` must be longer.
5. **What the clear removes.** If your machines cannot clear something on the default `clearableBlocks` list (for
   example foliage on soul sand), remove it from the platform's list. A platform accepts only maps whose blocks are
   all on its `clearableBlocks` or `nukeBlocks` list.
6. **Centre.** Pick a feet cell inside the map area from which the bot can reach the clear buttons and wait out
   the clear. Every chunk the machines pass through must stay within the server's simulation distance of it (5
   chunks on 2b2t), or the machines stop.
7. **Storage.** Draw one box around each group of material chests. Every chest must be within reach (4.5 blocks
   from the bot's eyes) of a floor cell the bot can walk to. Then note each supply chest by its role.
8. **Leftovers.** Either a sorter input chest (`returnChests`) or a spot where the bot can throw items into the
   sorter's water stream (`returnDrops`).
9. **Keep-out areas.** Box the machinery the bot has no reason to enter as `noGoZones`. Box machinery built from
   blocks that art also uses (redstone blocks, pots, concrete, TNT) as `protectedRegions`.
10. Start ZenithProxy, run `mapart site`, and fix every warning before you set `enabled` to true. A local test
    server is described in [TESTING.md](TESTING.md).

## Site keys

| Key | Default | What it does |
|---|---|---|
| `dimension` | `"minecraft:overworld"` | Dimension the base is in. |
| `platforms` | `[]` | The platforms. See below. |
| `storage` | | The chests. See below. |
| `cartographyTable` | not set | Where maps are locked. |
| `anvil` | not set | Where maps are renamed. A replacement is placed here when it breaks, so it needs a solid block below. |
| `protectedBlocks` | chests, barrels, shulker boxes, hoppers, glass, obsidian, beacons, pistons, observers, slime and honey blocks, rails, buttons, levers, floors, and more | Block types the bot never breaks. Every platform's floor block and the redstone parts that never appear in map art are always added. |
| `protectedRegions` | `[]` | Boxes in which the bot never breaks, presses, opens or places into anything. |
| `noGoZones` | `[]` | Boxes the bot never walks into or stands in. |
| `hasteZones` | `[]` | Boxes covered by your beacons' Haste. The bot plans its breaking around them. A level 4 beacon reaches 50 blocks sideways. |
| `edgeSneakOff` | `[]` | Boxes where the bot does not sneak at edges, such as a glass stair it would not step down otherwise. Never use one over a platform's edges. |
| `mapCounter` | not set | A button pressed once for every stored map. `mode` must be `BUTTON`. A failed press is one alert; the job goes on. |
| `mapCounterColumn` | not set | `{"x": .., "z": ..}`: if you only know the column of the counter button, the bot finds the button in it. Used while `mapCounter.pos` is not set. |
| `mapCounterLoginDelaySeconds` | `10` | No counter press until the bot has been online, and the counter's chunk loaded, this long. |
| `mapCounterCheckRadius` | `4` | Blocks around the button checked for a jam after each press (at most 8). 0 means no check. |
| `restartHoldMinutes` | `5` | Within this many minutes of an announced server restart, no clear starts and no nuke runs. 0 means off. |
| `restartEveryMinutes` | `0` | The server's restart cycle. Colour-suppression rides that would still run at the next restart wait. 0 means off. |

`openings`, `hazards` and `repairable` exist only while the plugin runs. They are never saved; do not set them.

## Platforms

Each entry in `platforms` is one tile.

| Key | Default | What it does |
|---|---|---|
| `id` | `"moss"` | A short unique name. |
| `kind` | `"MOSS"` | `MOSS`, `SOUL_SAND` or `COLOR_SUPPRESSION`. |
| `enabled` | `true` | Whether jobs are built here. |
| `minX`, `minZ` | | North-west corner of the 128 x 128 map area. Must be map-grid aligned. |
| `artY` | | Y of the art layer, one above the floor. Not used for colour suppression. |
| `floorBlock` | `"minecraft:moss_block"` | The floor block. Always protected. |
| `center` | | Feet cell inside the map area: where the map is filled and, with `stayDuringClear`, where the bot waits out the clear. |
| `mapStandAt` | `center` | Where the map is filled and checked, if not at `center`. Best with the same x and z. |
| `hopDownTo` | not set | After the clear, the bot drops from `center` to this floor cell instead of walking down. |
| `hopDownMinHealth` | `14` | Only hops down with at least this much health (of 20). |
| `offLayerAt` | not set | Feet cell off the art layer, outside the clear's sweep, where the bot steps aside to wait. Unset, it uses `center` unless the clear is pressed from there. |
| `clear` | `[]` | Buttons or levers that start the flying machines. Every one whose probe matches (or that has no probe) is pressed. |
| `clearTimeoutSeconds` | `600` | Longest wait for the art layer to read clear. |
| `clearRetries` | `2` | Presses again this many times before the bot alerts and stops. |
| `stayDuringClear` | `true` | The bot stays at `center` until the clear is done, so the chunks stay loaded. |
| `clearableBlocks` | carpets, candles, pressure plates, pointed dripstone, decorated pots, resin clumps, glow lichen, birch leaves, pink petals, crimson roots, eyeblossoms | Blocks the flying machines remove. |
| `nukeBlocks` | terracotta, dripstone block, mushroom stem, clay, lapis, redstone, raw iron, emerald blocks, and other full blocks | Full blocks allowed in art. The bot breaks them before the clear. |
| `fightMobs` | on for `SOUL_SAND`, off otherwise | Whether the bot fights hostile mobs here. See `combat` in [CONFIGURATION.md](CONFIGURATION.md). |
| `soulSpeedBoots` | `false` | Wears Soul Speed boots here and mends them after each map. Needs `storage.bootChests`. |
| `returnChests` | `[]` | This platform's own leftovers chests. Empty means the site's `returnChests` or `returnDrops`. |
| `nukeEndCorner` | `""` | `NW`, `NE`, `SW` or `SE`: the corner the bot leaves the art layer by. The breaking sweep ends there. |

### Item collection (moss)

On a moss platform, hopper minecarts collect what the clear knocks off. They run during the nuke and the clear and
must be off while the bot builds.

| Key | Default | What it does |
|---|---|---|
| `collectionOn`, `collectionOff` | not set | The two switches. Both or neither. |
| `collectionIndicator` | not set | A block that shows the state. Without it the switches are pressed blindly. |
| `collectionIndicatorOn` | `"minecraft:redstone_lamp[lit=true]"` | The indicator's state while collection runs. Only the listed properties are compared. |
| `collectionRunAfterClearSeconds` | `30` | Longest the carts keep running after the layer reads clear. |
| `collectionQuietSeconds` | `3` | They stop earlier once no dropped item has been seen on the platform this long. |

### Actuators

`clear`, `collectionOn`, `collectionOff` and `mapCounter` all use this shape.

| Key | Default | What it does |
|---|---|---|
| `pos` | | The button or lever. |
| `mode` | `"BUTTON"` | `BUTTON` is pressed once. `LEVER` is flipped on and, if `holdTicks` is above 0, flipped back after that many ticks. |
| `holdTicks` | `0` | See `mode`. |
| `standAt` | not set | Feet cell to press it from. Without it the bot walks next to it. |
| `probe`, `probeBlock` | not set | The actuator is used only while the block at `probe` matches `probeBlock`. Set both or neither. |

### Sorter refills and walking aids (soul sand)

For a platform whose collection system sorts every cleared item back into its own chests within minutes.

| Key | Default | What it does |
|---|---|---|
| `sorterRefills` | `false` | A short item is waited for at the sorter before the bot goes to far storage. |
| `sorterPickup` | not set | Feet cell where sorted items fall past the bot. Standing there picks them up. |
| `sorterPickupPrime` | not set | Cell visited first so the far end of the sorter starts working. |
| `sorterPickupReturn` | one below `sorterPickup` | Where picked-up items that no load needs are thrown back. |
| `junkDropStand`, `junkDropAt` | not set | Stand cell and target water block for throwing away junk. Both must be set, or junk is kept. |
| `runRow` | not set | z and feet y of a clear row along the storage, used for long walks there. |

The timing keys for these are in `build` (`sorterRefill*`, `sorterPickup*`, `rowRun*`).

### Colour-suppression platforms

A `COLOR_SUPPRESSION` platform prints two-layer maps over glass and is cleared by a minecart ride, not by pistons.
See [COLOR_SUPPRESSION.md](COLOR_SUPPRESSION.md) for how it works.

| Key | Default | What it does |
|---|---|---|
| `glassY` | `[]` | Y of the two glass floors, bottom first, 14 apart. |
| `access` | `[]` | Boxes where the no-go zones and protected regions open while this platform builds (the way up, walkways). |
| `entry` | not set | Feet cell where the way up reaches the layers. A bot below walks there first. |
| `foot` | not set | Feet cell at the bottom of the way up. The bot goes there before each restock. |
| `materialRegions` | `[]` | This platform's own material chests. Its jobs use only these; other jobs leave them alone. |
| `materialRow` | not set | Box of the floor row in front of the chest columns, one block per column naming its material. |
| `allowedBlocks` | `[]` | Blocks a map may use when `materialRow` cannot be read. Empty means no limit. |
| `allowTransparent` | `false` | For tests only: accepts maps with transparent pixels. |
| `topLayerFirst` | `true` | Prints the top layer before the bottom one. |
| `ride` | off | The minecart ride that clears the layers and finishes the map. |

`ride` keys: `enabled` (`false`), `startRail`, `startStand`, `button`, `pushYaw` (`90`, west), `brake`,
`cartographyTable`, `anvil`, `outputChests`, `cartChests`, `reuseCart` (`false`), `recoverLostRide` (`true`),
`restartPause` (`false`), `access`, `edges`, `machine`, `machineIgnore`, `viewpoints`, `stasis`, `stasisCells`
(`0`), `pearlSightRange` (`48`), `stallMinutes` (`10`), `maxRideMinutes` (`180`), `expectedMinutes` (`75`),
`minHealth` (`10`), `swingSeconds` (`30`), `settleMinutes` (`30`), `guardConfirmMinutes` (`2`), `waitForReset`
(`false`), and `preload` for an optional lap before the ride (`startRail`, `startStand`, `button`, `pushYaw`, `end`,
`stallMinutes` `2`, `maxMinutes` `10`, `validMinutes` `15`, `access`, `edges`). The ride runs only when `enabled`
is true and `startRail`, `startStand`, `button`, `brake` and `cartographyTable` are set. Their meaning is in
[COLOR_SUPPRESSION.md](COLOR_SUPPRESSION.md).

## Storage

Every entry except `materialRegions` and `returnDrops` is a list of chest positions.

| Key | What goes there |
|---|---|
| `materialRegions` | Boxes of material chests. Every container inside is scanned and indexed, except the role chests below. |
| `returnChests` | Sorter input chests for leftover materials. Nothing else is ever put in. |
| `returnDrops` | Used when `returnChests` is empty: `{"standAt": feet cell, "target": water block}`. The bot stands there and throws leftovers into the water that feeds the sorter. |
| `mapChests` | Empty maps. |
| `scrapMapChests` | Spoiled filled maps. Empty means they go to `mapChests`. |
| `paneChests` | Glass panes, for locking maps. |
| `outputChests` | Finished, locked and renamed maps. |
| `xpChests` | Bottles o' enchanting, for renaming and mending. |
| `anvilChests` | Spare anvils. |
| `toolChests` | Spare pickaxes, axes and shovels. |
| `foodChests` | Food. |
| `bootChests` | Soul Speed boots with Mending. |
| `rocketChests` | Firework rockets and elytra, only used with `build.elytraLegs`. Empty means the chest or barrel with a sign that says "rocket". |

`mapart site` warns when map, pane, output, tool, food, XP or anvil chests are missing, and when there is neither a
return chest nor a drop point.

## No-go zones and protected regions

| | The bot never... | Use it for |
|---|---|---|
| `noGoZones` | walks into or stands in the box (feet or head) | Machinery and other platforms it has no reason to visit. |
| `protectedRegions` | breaks, presses, opens or places into any block in the box | Machinery made of block types that art also uses. |
| `protectedBlocks` | breaks a block of these types anywhere | Base infrastructure. |

- A no-go zone must not overlap a platform's map area from `artY` to `artY + 3`, or the bot cannot build there.
- A protected region must not overlap the art layer, or breaking and repairs stop there.
- Keep `center`, stand cells, buttons and every chest out of both. `mapart site` names each conflict.
