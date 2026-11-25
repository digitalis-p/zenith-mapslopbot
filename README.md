# zenith-mapslopbot

A [ZenithProxy](https://github.com/rfresh2/ZenithProxy) plugin that builds flat map art on a Minecraft server
(made for 2b2t) without anyone at the keyboard. You post MapArtCraft `.nbt` files in a Discord channel. The bot
queues them, then for each map:

**restocks → builds → fills the map → locks it → renames it → stores it → clears the platform → starts the next.**

It drives one account through ZenithProxy, and it was built for and tested on 2b2t's Grim-style anti-cheat.

## Features

- **Uploads from Discord or the console:** loose `.nbt` files, a `.zip` of a MapArtCraft "split" export, or one
  joined NxM export. Each upload is checked first: is the art flat, does every block exist in 1.21.4, can every
  block be placed, and can the platform clear it. A clear report comes back.
- **Building:** runs in lanes with air-place and Grim-safe rotations, pipelined placement, and checks for missing
  blocks or wrong colours. A block with no chest at the base is swapped for a stocked block of the same map colour.
- **Restocking:** from the base's chests (material sorters, shulkers, far storage), with gear, food, XP bottles
  for mending, and spare tools. Tools are never used past their durability floor.
- **Finishing each map:** fills it, checks it against the art, locks it at a cartography table, renames it at an
  anvil, and stores it in the output chests.
- **Clearing:** breaks ("nukes") the full blocks, then triggers the platform's piston flying machines and waits
  for the clear.
- **Colour-suppression (two-layer) maps** on a dedicated platform, including a minecart "muncher" ride
  ([docs/COLOR_SUPPRESSION.md](docs/COLOR_SUPPRESSION.md)).
- **Safety:**
  - fights hostile mobs, and backs away from creepers before hitting them;
  - edge and fall guards keep it from falling, and no-go zones keep it off the base's machinery;
  - a teleport safeguard stops everything on a large position change;
  - it resumes after restarts and reconnects.
- **Discord status:** a presence line plus one message per finished map. `discord.verbose` adds progress cards.

## Requirements

- ZenithProxy **3.7.2+1.21.4** on the **java** release channel. Native Linux builds cannot load plugins. Pin
  the version and turn auto-update off: the plugin uses ZenithProxy internals.
- **Java 25** to build. The jar targets Java 21.
- A base that fits the plugin's model, described in a **site config**: one or more flat, map-grid-aligned
  platforms cleared by piston flying machines, plus chests for materials, maps, glass panes, output, tools, food
  and XP. The examples in [`testserver/examples/`](testserver/examples/) show a moss platform and a soul sand
  platform.
- Optional: a Discord bot token and a channel for uploads and status.

## Build and install

```sh
./gradlew build          # the jar lands in build/libs/
```

1. Copy the jar into ZenithProxy's `plugins/` folder and restart ZenithProxy.
2. On first load the plugin writes `plugins/config/mapart.json`. Fill in:
   - `site`: your base, i.e. platforms, chests, no-go zones and stand spots (start from an example in
     `testserver/examples/`);
   - `discord.token` / `discord.channelId` (optional);
   - `enabled: true`, when you are ready.
3. Data such as jobs, stock and progress goes to `plugins/mapart/`.

## Commands

From the ZenithProxy console (`mapart …`) or Discord (`!map …` in the map-art channel):

| Command | What it does |
|---|---|
| `stop` / `stop disconnect` | **Kill switch.** Halts at once, and `disconnect` also leaves the server. Anyone in the channel may use it. |
| `on` / `off`, `pause [reason]`, `resume` | Start, stop or pause the builder. |
| `status`, `queue`, `stock [filter]`, `site` | Show the state, the queue, the stock and the loaded site. |
| `submit <path>` | Queue a `.nbt`/`.zip` (or a folder) from disk. |
| `cancel <id>`, `move <id> <pos>`, `rename <id> <name>`, `retry <id>` | Manage jobs. |
| `resubstitute <id> [apply]` | Redo the same-colour block swaps on tiles with nothing placed yet. Without `apply` it is a dry run. |
| `scan` | Rescan the base's chests. |
| `elytra …`, `discord …`, `verbose …` | Feature switches. See `mapart` in the console for the full list. |

## Testing locally

[`testserver/`](testserver/) runs a local Paper 1.21.4 server with GrimAC and ViaVersion, a copy of a world
download of your base, and a ZenithProxy dev instance in front of it. A speed mode runs the game 10× faster for
long runs. See [docs/TESTING.md](docs/TESTING.md). Nothing in there touches a public server.

Unit tests: `./gradlew test`.

## Documentation

- [docs/HOW_IT_WORKS.md](docs/HOW_IT_WORKS.md): uploads, the build cycle, restocking, movement, safety, Discord.
- [docs/CONFIGURATION.md](docs/CONFIGURATION.md): every setting in `plugins/config/mapart.json`.
- [docs/SITE_CONFIG.md](docs/SITE_CONFIG.md): describing your base (platforms, chests, no-go zones).
- [docs/COLOR_SUPPRESSION.md](docs/COLOR_SUPPRESSION.md): two-layer colour-suppression maps and the minecart ride.
- [docs/TESTING.md](docs/TESTING.md): unit tests and the local test server.
- [docs/FIRST_RUN.md](docs/FIRST_RUN.md): a checklist for the first supervised run on a real server.

## Disclaimer

The plugin automates a Minecraft account. Check the rules of the server you use it on. You run it at your own
risk.

## License

[AGPL-3.0](LICENSE). The plugin builds on ZenithProxy, which is AGPL-3.0.
