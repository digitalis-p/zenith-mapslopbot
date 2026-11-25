# Colour-suppression maps

Colour-suppression maps are optional and off unless you configure a `COLOR_SUPPRESSION` platform. Without one, these
uploads are refused. Everything here assumes a platform built for this design: two glass floors with a ride machine
(the "muncher") next to them.

## What they are

A flat map has one art layer, and each pixel is shaded by the height of the block north of it. A two-layer
colour-suppression map gets pixels a flat map cannot:

- The art sits on two plain-glass floors 14 blocks apart. Plain glass has no map colour, so a map sees through it.
- Each layer has cells at two levels: on the glass and one block above it. The level sets the shade.
- Half the pixels (where column + row is odd) come from the bottom layer, the other half from the top layer.
- The map is first filled with both layers in place. Then the bot rides a minecart past the art with the map in hand
  while the machine removes the top layer. The bottom-layer pixels are written again from the bottom layer alone. The
  top-layer pixels keep what they had at the fill.

A colour-suppression job is one map, built in several passes: each layer, each level, the noobline row first.

## The export it accepts

Only PNG-to-NBT "Suppress (2-Layer)" exports made with the CrubTech option:

- CrubTech preset, build mode Suppress (2-Layer), Support None, an opaque image with no water.
- Minecraft 1.21.4 format, or PNG-to-NBT's default newer format.
- 128 wide, 129 deep with the noobline row, one plain-glass floor under every map cell, the bottom layer 13 to 12
  blocks below it and the top layer 1 to 2 above it.
- Every block must exist in 1.21.4, be placeable, and be one of the 64 blocks of the colour-suppression sorter
  (CrubTech's preset plus its moss and resin fillers). Nothing is swapped for a same-colour block on these maps.

Refused, each with the reason: water pixels, late pixels (Late-Fillers, Late-Pairs), "Support: Steps", blocks between
the layers, transparent pixels (unless the platform's `allowTransparent` is set, for tests), and mixing
colour-suppression and flat files in one upload.

A picture cut into several maps (files named `<name>-suppress_crubtech-<a>_<b>.nbt`, loose or zipped) becomes one
job per map, queued by `<a>` then `<b>` and named `<name> <a>,<b>`. A part that is refused does not hold back the
others.

The upload preview shows the bottom layer, the top layer and the finished map.

## What the platform needs

In the site config (field details in [SITE_CONFIG.md](SITE_CONFIG.md)):

| Setting | What it is |
|---|---|
| `kind`, `enabled` | `COLOR_SUPPRESSION`, and `true`. |
| `minX`, `minZ` | The map tile's north-west corner, on the map grid. |
| `glassY` | The two glass floors, bottom first, 14 apart. |
| `center` | Where the map is made and held, on the top layer. |
| `access` | Boxes of the way up and the walkways. No-go zones and protected regions open there during a job. |
| `entry` | A cell where the way up reaches the layers. A bot below the bottom glass walks here first. |
| Material chests | The platform's own sorter chests, so the bot does not walk down to the base for every load. |

The machinery around the layers must be inside no-go zones and protected regions. The art volumes above each glass
floor open by themselves while a colour-suppression job runs. Lethal edges of each glass floor are found from the world
and kept off-limits with a two-cell margin.

## How a job runs

1. **Print.** Each pass is built with the normal lanes at its own height. Raised cells are air-placed; a pillar on its
   side is placed on the face of its axis. A pass starts only on empty cells, or on this job's own blocks after a
   restart. Anything else is `BLOCKED` with "not reset". The bot never nukes or clears these layers. Passes stay in
   order even when stock is short: a short pass waits on the platform.
2. **Fill and check.** After the last pass the map is made at the top centre and must match the "after fill" picture
   exactly.
3. **Ride** (only with `ride.enabled`). Without it the job ends `READY_FOR_RIDE` with one message, and the rest is up
   to you.
4. **Lock, name, store** at the booth's cartography table, anvil and chests, then count the map like any other.

The ride, in plain terms:

- **Checks first** (`RIDE_BOARD`). The bot waits out any announced server restart. It walks the viewpoints and reads
  the machine boxes against a reference it records before the platform's first ride. It checks that every stasis
  cell (the ender-pearl chunk loaders) shows a pearl.
- **Preload lap** (`RIDE_PRELOAD`, only with `ride.preload`). The bot gets into the platform's own cart on a separate
  loop track, presses its button from the seat and rides once around. This takes it past every chunk of the machine
  and its stasis pearls, so they are loaded on this connection before the real ride. It counts only for the same
  connection and for `preload.validMinutes`. After a disconnect, the checks and the lap run again.
- **Board.** A minecart goes on the start rail (from `ride.cartChests`, or the platform's own cart with
  `ride.reuseCart`) and the bot gets in.
- **Ride** (`RIDE`). It presses the launch button from the seat, pushes off, and rides with the map in hand until the
  cart stops on the brake in the booth. Progress is shown in minutes against `ride.expectedMinutes`.
- **End** (`RIDE_END`). It gets out, breaks the cart, picks it up, and compares the map with the finished art. Any pixel
  off: `BLOCKED`, and the map stays unlocked in the inventory.
- **Reset** (`RIDE_RESET`, only with `ride.waitForReset`). It waits until both layers are air and the machine matches
  its reference again. Without it the next job starts at once and its first pass waits for the muncher to finish.

The ride is never retried by itself. It stops `BLOCKED` and leaves the machinery alone on: a machine difference, a
missing pearl, the cart standing still for `ride.stallMinutes`, a ride longer than `ride.maxRideMinutes`, health below
`ride.minHealth`, or the map off the art. With `ride.recoverLostRide` (on by default), a ride where the bot lost the
cart after the launch is recovered: the bot waits for the run to end, returns the cart to the start and prints the
map again.

## Settings at a glance

Platform:

| Setting | Default | Effect |
|---|---|---|
| `topLayerFirst` | `true` | Print the top layer before the bottom one. |
| `allowTransparent` | `false` | Accept transparent pixels. For tests. |

Ride (`ride.*`):

| Setting | Default | Effect |
|---|---|---|
| `enabled` | `false` | Ride, lock and store. Off: stop at `READY_FOR_RIDE`. |
| `startRail`, `startStand`, `button`, `pushYaw`, `brake` | | The start rail and where to stand by it, the launch button, the push direction, and the end rail. |
| `cartographyTable`, `anvil`, `outputChests`, `cartChests` | | The booth, and where minecarts come from. Empty `outputChests`: the base's output chests. |
| `reuseCart` | `false` | Use and return the platform's own cart instead of bringing one. |
| `access`, `edges` | | Boxes opened for the ride's walks, and lethal margins along them. |
| `machine`, `machineIgnore`, `viewpoints` | | The machine guard: what is compared, what is left out, and where to read it from. |
| `stasis`, `stasisCells` | | Stasis-cell boxes and how many cells must be found. |
| `expectedMinutes` | 75 | Expected ride time, for the progress display. |
| `stallMinutes`, `maxRideMinutes` | 10, 180 | Ride limits. |
| `minHealth` | 10 | Health below this stops the ride (`BLOCKED`). |
| `settleMinutes` | 30 | How long to wait for the machine to come home. |
| `waitForReset` | `false` | Wait for the reset before the job counts as done. |
| `recoverLostRide` | `true` | Recover a ride that lost its cart. |
| `restartPause` | `false` | Pause at a lane end for an announced server restart and carry on afterwards. |

Preload lap (`ride.preload.*`, unset = no lap): `startRail`, `startStand`, `button`, `pushYaw`, `end`, `access`,
`edges`, and the limits `stallMinutes` (2), `maxMinutes` (10) and `validMinutes` (15).

Build options named `build.suppression*` tune printing, for example `suppressionMergeLevels` (both levels of a
layer in one sweep, off by default). See [CONFIGURATION.md](CONFIGURATION.md).

## Discord

A colour-suppression job reads as one map everywhere: "Printing · Fox · top layer · 62% · ~2h 10m left", "Riding the
muncher · Fox · 41 of ~75 min". Waits are named in words, such as "Waiting for the muncher to clear the bottom layer".
One "Map done" message is posted when the map is stored. See [HOW_IT_WORKS.md](HOW_IT_WORKS.md) for the rest.
