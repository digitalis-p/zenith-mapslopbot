# Testing

Two levels: unit tests that need nothing but Gradle, and a local test server that runs the real plugin against a copy
of your base. Nothing here connects to a public server.

## Unit tests

```sh
./gradlew test
```

The tests cover parsing and upload checks, planning, the engine against a simulated world, commands and Discord
replies. Fixtures (MapArtCraft and PNG-to-NBT exports) are in `src/test/resources/fixtures/`.

## The local test server

`testserver/` sets up and runs:

| Part | Version | Address |
|---|---|---|
| Paper | 1.21.11 | 127.0.0.1:25566, offline mode |
| GrimAC | 2.3.73 | inside Paper; alerts go to the server log |
| ViaVersion + ViaBackwards | 5.12.1 | inside Paper, so the 1.21.4 client can join |
| RCON | | 127.0.0.1:25576, random password in `testserver/work/.rcon-password` |
| ZenithProxy dev instance | 3.7.2+1.21.4 via `./gradlew run` | its own server on 127.0.0.1:25565, offline account `MapArtBot` |

Via runs on the server, not in ZenithProxy, so Grim sees a 1.21.4 client as it would on 2b2t. Pinned download URLs
and hashes are in `testserver/versions.env`; `setup.sh` checks them. The scripts pick a JDK 21 to 25; set
`TESTSERVER_JAVA` to choose one. Ports and the bot name can be changed with `TESTSERVER_PORT`,
`TESTSERVER_RCON_PORT`, `TESTSERVER_ZENITH_PORT` and `TESTSERVER_BOT_NAME`.

Downloads go to `testserver/cache/`, the server and world copy to `testserver/work/`, and ZenithProxy runs in `run/`.
All three are gitignored.

### What you provide

- **A world download of your base**, for example a singleplayer save made with a world downloader. Point
  `TESTSERVER_WORLD_SRC` at its folder. The scripts only copy from it and never change it. The pinned server writes
  1.21.11, so the world must not be newer.
- **Your site files in `testserver/local/`** (gitignored, so real coordinates stay out of git). Start from the
  copies in `testserver/examples/` and change the coordinates to your base's:
  - `site-config.moss.json`: the site config (see [SITE_CONFIG.md](SITE_CONFIG.md));
  - `moss-materials.json`: which material chest holds which item, for stocking;
  - `world-edits.moss.txt`: RCON commands that change the test copy (for example chests your real base lacks).

A world download has no chest contents. `prepare-site.sh` fills the chests over RCON.

### Setup and start

```sh
testserver/setup.sh                            # download and verify jars, lay out work/ (safe to re-run)
testserver/reset-world.sh                      # fresh copy of the world; the bot is seeded at the world spawn
testserver/start.sh                            # Paper in the background; waits until it is ready
testserver/prepare-site.sh                     # world edits, then stock every chest
testserver/zenith-run-config/install.sh        # run/config.json (offline bot) and the plugin's site config
testserver/zenith-run-config/start-zenith.sh   # ./gradlew run in the background
python3 -I testserver/rcon.py list             # shows MapArtBot once it has joined
```

Every script prints its usage with `--help`. Run the Python tools with `python3 -I`.

- `reset-world.sh` refuses to run while the server is up. It keeps the previous copy as `work/world.prev`.
- `start.sh` applies `testserver/config/init-commands.txt` after every start: hard difficulty, normal random ticks,
  and no hostile mobs, phantoms, patrols or traders. Set those gamerules to true to rehearse with mobs.
- `install.sh` writes a config that only talks to 127.0.0.1, with auto-update off and Baritone's breaking, placing
  and inventory use off. It backs up existing files first. `--site FILE` uses another site config.
- `start-zenith.sh` refuses to start if `run/config.json` points anywhere but the local server.
- `stock-chests.py` (run by `prepare-site.sh`) has options for small stock, empty chests and a starter kit for the
  bot. See `python3 -I testserver/stock-chests.py --help`.

### Stop and reset

```sh
testserver/zenith-run-config/stop-zenith.sh    # sends shutdown; Gradle then prints BUILD FAILED, which is expected
testserver/stop.sh                             # RCON stop (saves the world), then SIGTERM, then SIGKILL
```

To start over: stop both, then `reset-world.sh`, `start.sh`, `prepare-site.sh`, `start-zenith.sh`. The plugin's job
store in `run/plugins/mapart/` is not reset by this. Delete it yourself if you want an empty queue.

Edit `run/config.json` and `run/plugins/config/mapart.json` only while ZenithProxy is stopped. It rewrites them on
exit.

### Speed mode

For functional runs that do not need anti-cheat results, run the game 10 times faster:

```sh
testserver/zenith-run-config/stop-zenith.sh && testserver/stop.sh
testserver/speed-mode.sh on 10
testserver/start.sh
testserver/zenith-run-config/start-zenith.sh
```

`on N` removes Grim, sets the server to 20·N ticks per second and ZenithProxy's `client.tickRate` to N, and scales
Paper's and ViaVersion's packet limits to match. The plugin counts ticks, so its timing scales along. Speed mode gives
no anti-cheat signal. `speed-mode.sh off` restores everything, and `speed-mode.sh status` shows the current state.
Both `on` and `off` need the server and ZenithProxy stopped, and back up each file they change.

## Running a build locally

1. Start everything as above and wait until `rcon.py list` shows the bot.
2. Turn the builder on: `testserver/zenith-run-config/zenith-cmd.sh mapart on`.
3. Optionally scan first: `zenith-cmd.sh mapart scan`, then `zenith-cmd.sh mapart stock`.
4. Submit a map: `zenith-cmd.sh mapart submit /absolute/path/to/art.nbt`. The fixtures in
   `src/test/resources/fixtures/mapartcraft/` are a good start.
5. Watch the logs (below) and `zenith-cmd.sh mapart status`.
6. Check the result in the world: `python3 -I testserver/rcon.py "save-all flush"`, then
   `python3 -I testserver/worldscan.py count X0 Y0 Z0 X1 Y1 Z1` over the art layer gives a block histogram.

`zenith-cmd.sh` sends one line to ZenithProxy's terminal and prints the log lines of the next 3 seconds (`--wait N`
for longer). With Discord off, every notice goes to the log as `[mapart] …`, alerts as `[mapart] ALERT …`.

To watch in game without taking over the bot, join the Paper server directly at `localhost:25566` (offline mode, any
name) and use spectator mode; `testserver/op.sh <name>` makes you operator. A player that joins ZenithProxy's own
server on `localhost:25565` from its main whitelist takes control of the account, and the bot stops while they are
on.

## Logs

| What | Where |
|---|---|
| Paper log (Grim alerts, joins, deaths) | `testserver/work/logs/latest.log` |
| Paper JVM output | `testserver/work/logs/console.out` |
| ZenithProxy and plugin log | `run/log/latest.log` |
| Gradle and ZenithProxy console | `run/zenith-console.log` |
| Server status, memory, players | `testserver/status.sh` |

Grim prints alerts only at its punishment thresholds. To see every flag, set `verbose.print-to-console: true` in
`testserver/work/plugins/GrimAC/config.yml` and run `rcon.py "grim reload"`.

`tools/metrics/map_metrics.py LOG` reads a ZenithProxy log and reports maps per hour and where each map's time went.
It never prints log text, so its output holds no coordinates.

## How the test server differs from 2b2t

- Paper 1.21.11 behind ViaBackwards, not a 1.21.4 server. Grim warns that this setup is unsupported for vehicles,
  which matters for the colour-suppression ride.
- Maps are drawn with 1.21.11 colours. Closed eyeblossoms show a different colour there, so eyeblossom maps fail
  `CHECK_MAP` locally.
- Default Grim config. 2b2t's anti-cheat and its thresholds are not public.
- No queue, no session kick, almost no lag. Rollback handling gets little exercise.
- No mobs unless you turn them on. A world download has no entities such as hopper minecarts, so items from a clear
  are not collected.
- View and simulation distance are set in `testserver/config/server.properties`; re-run `setup.sh` after changing
  them.

The first run on a real server is covered in [FIRST_RUN.md](FIRST_RUN.md).
