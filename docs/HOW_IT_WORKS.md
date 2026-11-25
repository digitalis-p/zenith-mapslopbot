# How it works

The plugin runs inside ZenithProxy and drives one Minecraft account. You give it flat map art as structure files.
It queues them and, for each map, builds the art on a platform at your base, makes and checks the map, locks it,
names it, stores it, and clears the platform for the next one. Nobody needs to be at the keyboard.

Everything it knows about your base comes from the site config (see [SITE_CONFIG.md](SITE_CONFIG.md)). Every other
setting is in [CONFIGURATION.md](CONFIGURATION.md). Jobs, tile files, the stock index and the engine checkpoint are
kept in `plugins/mapart/` under ZenithProxy's directory.

## Uploads

### Where they come from

- **Discord:** attach the files in the map art channel. Anyone who can post there can submit. Up to 10 files per
  message, each within `discord.maxAttachmentBytes` (25 MiB by default).
- **Console:** `mapart submit <path>` takes a `.nbt`, a `.zip`, or a folder.

Accepted forms:

| Form | What it is |
|---|---|
| One `.nbt` | A single map (128 x 129 with the noobline row), or one joined NxM export that the plugin cuts into tiles. |
| Several `.nbt` | A MapArtCraft "split" export: names end in `_<x>_<y>.nbt` (a leading prefix such as `0003_` is fine). |
| `.zip` | The same files zipped. Other files in the zip are ignored and listed. |

The job name is the text of your message, with a leading `!map queue` or similar stripped. With no text, the name
comes from the file. Names are cut to 50 characters (the anvil's limit).

### Checks

Each file is parsed and checked before anything is queued. If any check fails, the upload is refused and the reply
lists every reason it found.

| Check | Refused when |
|---|---|
| Export type | It is a two-layer colour-suppression export (and no colour-suppression platform is enabled), a PNG-to-NBT export, or its DataVersion is newer than MapArtCraft's 1.21.5 (4325). |
| Size | It is not 128·N wide and 128·M+1 deep, tiles of a split upload are missing or differ in size, or it holds more than 256 tiles. |
| Flat | The top blocks do not all sit at one height (staircased or 3D art). Support blocks, the noobline row and air are ignored. |
| Version | A block does not exist in Minecraft 1.21.4. MapArtCraft defaults to 1.21.5, so set it to 1.21.4. |
| Placement | A block state cannot be made by clicking the top face of the block below (sideways logs, top slabs, waterlogged blocks). |
| Platform | A block can be neither cleared by the platform's flying machines nor broken by the bot on any enabled platform. |
| Eyeblossoms | The map needs open and closed eyeblossoms at once. They flip together with day and night, so that map can never be right. |

Refusal messages end with how to export instead: MapArtCraft, Schematic (NBT), version 1.21.4, Staircasing off,
split files or one joined file.

### Same-colour substitution

A flat map's pixel depends only on the map colour of the top block and its height. A carpet and a full block of the
same colour give the same pixel. So when the art uses a block your base has no chest for, the plugin swaps it for a
stocked block with the identical 1.21.4 map colour (`map.substituteSameColour`, on by default).

- The stand-in must be clearable, placeable from the top face, and not protected.
- Among several stand-ins it prefers the cheaper one to build and clear, then the block the tile already uses most.
- `map.substituteStocked` (off by default) also swaps stocked blocks when the stand-in is easier to build.
- `map.substituteDeny` lists blocks that are never swapped.
- Nothing is swapped before the chests have been scanned once.
- The reply lists every swap. The job file keeps the original blocks.
- `mapart resubstitute <id>` redoes the swaps with today's stock on tiles with nothing placed yet. It is a dry run
  unless you add `apply` (which needs the builder paused or off).

### The reply

The reply card shows a preview image, the tile count, the materials against your stock, the platforms that can build
it, and the queue position. In the material list `!` marks a shortage and `✗` marks an item no chest at your base has
ever held. A `✗` item will not arrive by itself: someone has to add a chest or stock it.

### The queue

Jobs are built in queue order. A job is `QUEUED`, `WAITING_MATERIALS`, `ACTIVE`, then `DONE`, `CANCELLED` or
`FAILED`. A colour-suppression job without the ride ends `READY_FOR_RIDE`.

| Command | Effect |
|---|---|
| `queue` | List jobs and recently finished ones. |
| `move <id> <pos>` | Reorder (1 = next). |
| `cancel <id>` | Drop a job. Stored maps stay where they are. |
| `retry <id>` | Requeue a failed job. |
| `rename <id> <name>` | Rename a job (console only). |

## Choosing the next map

With no map started on a platform, the bot picks the first map, in queue order, that the stock covers completely.
That can be a later map of the same job (`build.skipShortTiles`). The start message names any map it skipped and what
that map is short of.

If no map is fully covered, it takes the one with the most blocks in stock. If at least half of it is covered
(`build.partialBuildMinShare`), it builds everything it can (`build.partialBuild`) and waits on the platform for the
rest. It looks at the chests again every few minutes, and at once when it picks up the missing item. A map is never
filled while a block is missing, and a started map is finished before another one starts on that platform.

If nothing can be built, one alert says so and lists what each waiting map needs.

## The build cycle

Each map (one tile of a job) runs through these phases. The checkpoint stores the phase, so a restart picks up where
it was.

| Phase | What happens |
|---|---|
| `IDLE` | Nothing to do. Polls the queue, runs a requested `scan`. |
| `SELECT` | Picks the next map and a free platform that accepts all its blocks. |
| `INSPECT` | Walks to the platform and reads the art layer. Empty: go on. This map's blocks already there: resume. Other blocks: clear them first (`NUKE`). |
| `STOCK_CHECK` | Compares what the map still needs with the stock and refreshes stale chest readings. Short maps wait or are skipped (above). |
| `GEAR` | Tools, food, and boots where the platform needs them. Item collection off on platforms that have a toggle. |
| `RESTOCK` | Drops off what the next lanes do not need and takes the next load from the material chests. |
| `BUILD` | Places the art lane by lane until the load runs out (back to `RESTOCK`) or no lanes are left. |
| `VERIFY` | Scans the whole tile against the plan. Breaks wrong blocks, places missing ones. Up to `build.maxRepairPasses` rounds. |
| `FILL_MAP` | Fetches an empty map and a glass pane, stands at the map stand and uses the map. |
| `CHECK_MAP` | Compares the map's pixels with the expected colours. A mismatch goes back to `VERIFY`, up to `map.maxFillAttempts`. |
| `LOCK` | Locks the map at the cartography table with the glass pane. |
| `RENAME` | Tops up XP and renames it at the anvil (`{job} {x},{z}`, or just `{job}` for a single map). Replaces a broken anvil from the anvil chest. |
| `STORE` | Puts the map in an output chest. Only this map leaves the inventory. |
| `COUNT` | Presses the base's map counter button once, if one is configured. A failure is an alert, never a stop. |
| `NUKE` | Item collection on. Breaks every block the flying machines cannot clear (full blocks would break them). |
| `CLEAR` | Presses the clear button or lever. With a side probe, it presses the one on the side where the machines are parked. |
| `WAIT_CLEAR` | Waits until the art layer is air with no fluid, then lets collection run on until no items are seen. Retries `CLEAR` up to `clearRetries` times, then alerts and pauses. |
| `FINISH_TILE` | Collection off, tile marked done, on to the next map. |
| `BLOCKED` | Needs a human. The status note says why. `resume` tries again. |

Colour-suppression maps add `RIDE_BOARD`, `RIDE_PRELOAD`, `RIDE`, `RIDE_END` and `RIDE_RESET`. See
[COLOR_SUPPRESSION.md](COLOR_SUPPRESSION.md).

Details that matter in practice:

- **Placement.** The plugin places blocks itself. Baritone only walks. By default blocks are air-placed (no look,
  reach up to 5) in 9-wide lanes. Blocks whose placed form follows the look, and the `LOOK` mode, use a real or silent
  look within 4.5 blocks in 5-wide lanes. Placement speed adapts: it backs off on misses and rollbacks.
- **Breaking.** `build.breakMode` `FAST` (default) sweeps large sets of blocks while walking. `DOUBLE` mines two
  blocks at once the way 2b2t players do. `SINGLE` breaks one at a time. A block counts as broken only when the server
  shows air.
- **Progress comes from the world.** What is placed is always read from the chunk cache, never from memory.
- **Blocks above the art.** A map pixel shows the topmost block with a map colour. If something above the art (another
  platform, a player's build) covers it, the bot holds the map and waits rather than counting a failed fill.
- **Eyeblossoms.** A map with eyeblossom pixels is filled only while they all show the state the art wants. The bot
  waits up to `map.eyeblossomWaitMinutes`, then goes `BLOCKED`.
- **No map data.** If no pixels arrive, the bot holds the map again and then tries one fresh map. If that fails too it
  goes `BLOCKED` and asks you to reconnect, which makes the server resend every map.
- **Carry-over** (`build.carryOver`, off by default). When the next map on the same platform shares many cells with
  the one just stored, the bot breaks only the cells that differ and skips the clear, if its estimate says that is
  faster.

## Multi-map jobs

A job of N x M maps is N·M tiles. Each tile is built, filled, locked, named `{job} {x},{z}` and stored on its own, so
you can lay the maps out later. Tiles are built in queue order unless the stock says otherwise (see "Choosing the next
map"). Progress is the count of stored maps ("6 of 28 maps done"). The job is `DONE` when every tile is stored.

## Restocking and storage

- **Stock index.** The bot scans the material chests in the configured regions and remembers what each holds. One
  chest per block type is the usual sorter layout. Chests, barrels and placed shulker boxes all work. `scan` rescans.
- **Loads.** Each load is planned for the next lanes. The bot drops off what those lanes do not need first.
- **Near first.** Chests close to the platform are used before far ones. A trip to far storage
  (`build.farStorage`) takes along everything later lanes will need from there, and is not made for a few blocks.
- **Leftovers** go to a return chest, or are thrown into a drop point such as a sorter's water channel.
- **Only materials leave the inventory.** Items the bot withdrew, items on the job's material lists, and the
  platform's materials can be deposited or dropped. Totems, gear, shulker boxes, maps the bot did not make and
  anything unknown are never moved out, and nothing is taken from the offhand or armour slots (except the configured
  boots).
- **Supplies** come from their own chests: empty maps, glass panes, XP bottles, anvils, tools, food, boots.

## How it moves

- **Baritone** (ZenithProxy's pathfinder) walks between chests and lanes, with its own breaking, placing and
  inventory use turned off. Lanes are walked with the plugin's own keys.
- **No-go zones** (`site.noGoZones`) are boxes the bot never enters. Goals avoid them, every planned path is checked
  before it is walked, stand spots inside them are never used, and a task stops if the bot's feet or head enter one.
  A walk that would enter one fails, and repeated failures go `BLOCKED` with the zone's name.
- **Protected blocks and regions.** Chests, hoppers, glass, redstone parts, floors and the rest of
  `site.protectedBlocks` are never broken. Inside `site.protectedRegions` nothing is broken, pressed, opened or placed.
- **Edges and falls.** Lethal edges are read from the world and kept off-limits with a margin. The bot sneaks or brakes
  near holes and drops, and never walks a planned fall of more than 3 blocks (`build.edgePlannedFalls`).
- **Setbacks.** A burst of anti-cheat setbacks makes the bot stop, stand still for a few seconds, then step to a nearby
  open cell. After three tries in two minutes it pauses with a "Stuck" alert.
- **Elytra legs** (`build.elytraLegs`, off by default). Long restock legs can be flown with an elytra and rockets when
  a straight, safe corridor and a landing pad exist. Anything doubtful walks instead. `mapart elytra off` stops all
  flights at once.

## Combat and safety

### Mobs

- On platforms with `fightMobs` on (soul sand by default, moss off), a hostile mob from `combat.mobs` within
  `combat.radius` stops the work at a safe point. The bot switches on ZenithProxy's KillAura, holds its best weapon
  above the durability floor and walks up to mobs out of reach. Once the area is quiet it goes back to work.
- Self-defence works everywhere: a listed mob that hits the bot starts a fight on any platform and on walkways.
- Creepers are fought by hit and run (`combat.creeperHitAndRun`): one hit, walk away past 7 blocks, wait, repeat.
- No fight runs during the clear. A fight that runs too long ends with an alert.

### Teleport safeguard (SAFETY STOP)

While the bot is in control, it halts at once when:

- the server moves it more than `safety.teleportStopDistance` blocks (8), unless it is put back onto its own recent path
  (a lag setback);
- it dies, respawns or changes dimension;
- after login it is more than `safety.maxDistanceFromBase` blocks (256) from every enabled platform.

A halt stops Baritone and the current task before the next tick, saves the pause, and sends one SAFETY STOP alert.
Only `resume` lets it go on.

### Kill switch

`!map stop` (or `kill`, `halt`) in the channel, or `mapart stop` in the console, is the same halt with the reason
"manual stop". Anyone who can post in the channel or run commands may use it. It replies STOPPED once Baritone is
stopped and the task cancelled, or STOP INCOMPLETE after 3 s if the engine did not confirm. `stop disconnect` then
also disconnects from the server in a way AutoReconnect does not undo.

`pause` is softer: it halts after the current action and is not a safety lock.

### Tools, food and XP

- **Tools never break.** No block is broken with a tool at `build.toolSafetyFloor` uses left (20) or fewer. Mending
  tools are mended with XP bottles below `build.mendBelowPercent` (50 %). At the floor the bot mends or swaps the tool
  at the tool chest. If neither works it goes `BLOCKED`.
- Carpets and other instant blocks are broken by hand.
- **Food.** The bot eats only standing still, when hunger is at or below `build.eatFoodLevel` (14) or health is low. It
  keeps `build.foodToKeep` items and never eats golden apples. ZenithProxy's AutoEat is switched off while the plugin
  works and given back on pause or stop. A starving bot with no food fetches some even while paused.
- **Keep-alive.** After `build.keepAliveSeconds` (60) standing still, the bot swings and turns its head, so the server
  does not drop it as idle.
- **Server restarts.** Within `site.restartHoldMinutes` (5) of an announced 2b2t restart, the bot starts no nuke and
  presses no clear button, so no machine is cut off halfway.

### Disconnects are ZenithProxy's job

The plugin never disconnects for low health, hunger or threats. Set up ZenithProxy's AutoDisconnect for that. Player
threats are out of scope too. The plugin's only disconnect is a manual `stop disconnect`.

## Restarts and reconnects

- The checkpoint stores the job, tile and phase. Progress is re-read from the world, so resuming after a restart, a
  kick, the queue or a reconnect is safe.
- A saved pause or SAFETY STOP survives restarts. The bot will not move until `resume`.
- When a player logs in to the account through the proxy, the bot stops sending anything. When they leave, it carries
  on.
- Map ids change per connection on 2b2t, so maps are never tracked by id across reconnects.
- Items the server drops at a disconnect (from the cursor, the cartography table or the anvil) are looked for and
  picked up after the reconnect.

## Discord

The plugin uses its own Discord bot token (`discord.token`) and one channel (`discord.channelId`). The same channel
takes uploads and `!map` commands and gets the reports. Replies are formatted to read on a phone.

- **Presence.** The bot's custom status says what it is doing in plain words, for example
  `Printing · Sunset · map 2 of 4 (72%) · 1 map done · job ~1h 10m left`. The dot is online while it works, idle while
  it waits, and do-not-disturb when it needs a human. It never shows coordinates.
- **Always posted:** alerts (with `discord.alertMention`), job started, each later map's start, one "Map done" per
  stored map with the final render, job done or cancelled, pause and resume, and waits that may need a human.
- **Verbose** (`discord.verbose`, or `!map verbose on`): routine steps and a progress card every
  `discord.progressIntervalMinutes`. Off by default. Everything is in the log either way.
- **Coordinates.** `discord.coordinates` `REAL` sends real positions. Use `RELATIVE` in a shared channel: positions are
  shown relative to the first platform's centre.

Commands (`!map …` in the channel, `mapart …` in the console):

| Command | Effect |
|---|---|
| `stop [disconnect]` | Kill switch. Open to everyone. |
| `pause [reason]`, `resume` | Pause or carry on. |
| `on`, `off` | Builder on or off (console). The queue still takes uploads while off. |
| `status`, `queue`, `stock [filter]` | Show state, queue, stock. |
| `scan` | Rescan the chests. |
| `cancel`, `move`, `retry`, `rename` | Manage jobs (above). |
| `site` | Check the loaded site config and list problems (console). |
| `verbose [on/off]`, `discord on/off/reload`, `elytra …` | Switches. |

In the console, commands that change anything need the account owner, except `stop`.

## Out of scope

- Staircased or 3D maps. Flat only.
- Blocks that need a side-face click (except sideways pillars on colour-suppression maps).
- Snow fill, map copies, item-frame walls.
- More than one account.
- Reacting to other players. Use ZenithProxy's own modules.
- Disconnecting for health or danger. That is ZenithProxy's AutoDisconnect.

Where in the code: the phases are `engine/Phase.java` and `engine/BuildEngine.java`; upload checks are in `plan/`;
commands in `command/`; Discord in `discord/`.
