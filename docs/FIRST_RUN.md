# First run on a real server

A checklist for the first supervised run. The goal is one small map, built while you watch and can stop it at any
moment. Run it through the local test server first ([TESTING.md](TESTING.md)).

## The stop ladder

Keep this open the whole time. Fastest first. If a step does not work, take the next one.

| # | Do this | What happens |
|---|---|---|
| 1 | `!map stop` in the map art channel (or `kill`, `halt`) | Task cancelled, Baritone stopped, pause saved. Reply: STOPPED. Anyone in the channel may use it. |
| 2 | `!map stop disconnect` | Step 1, then ZenithProxy disconnects. AutoReconnect does not reconnect after it. |
| 3 | Log in to the proxy with the account's own client | The bot sends nothing while you are in control. |
| 4 | `disconnect` in the ZenithProxy terminal | Disconnects without the plugin. |
| 5 | Kill the ZenithProxy process | Everything stops. |

`mapart stop` and `mapart stop disconnect` in the ZenithProxy terminal do the same as steps 1 and 2. If a stop replies
STOP INCOMPLETE, go to the next step at once.

Steps 3 to 5 save no pause. When control comes back the bot carries on building, so send `!map stop` as well whenever
Discord works. Resume with `!map resume` only once you know what went wrong.

## ZenithProxy settings that matter

- **Java release channel.** ZenithProxy 3.7.2 for 1.21.4 on the `java` channel. Native builds cannot load plugins.
- **Pinned version, auto-update off.** The plugin uses ZenithProxy internals. Set `auto_update: false` in
  `launch_config.json` (`testserver/zenith-run-config/launch_config.json` is a working example).
- **AutoReconnect off** for the first run, so a kick stays a stop.
- **AutoDisconnect on**, with a health threshold you trust. The plugin never disconnects for health or danger.
- **Map cache.** The plugin raises ZenithProxy's map cache limit to 4096 at load (`map.raiseZenithMapCache`). Check
  that the log says so. A raise in the middle of a session only helps after a reconnect.
- **Modules.** Leave KillAura, AntiAFK, Spammer and AutoReply off. AutoEat may stay on. The plugin switches off the
  modules in `build.disableModules` while it works and restores them on pause or stop. With air-placement this
  includes AutoTotem, because the offhand is used for placing: a totem already in the offhand stays, but a popped one
  is not replaced while the bot works.
- **One proxy per account.** If another proxy uses the same account, `disconnect` it and turn its AutoReconnect off
  first. Two proxies on one account kick each other in a loop.
- **Watching.** `spectator on` and `spectator whitelist add <alt>` let you watch with a second account. Joining as
  the bot's own account is a takeover (step 3).

## Pre-flight

Plugin and config, with ZenithProxy stopped (it rewrites configs on exit):

- [ ] `./gradlew build`, then copy the jar from `build/libs/` into `plugins/`.
- [ ] Start ZenithProxy once so it writes `plugins/config/mapart.json`, then `shutdown`.
- [ ] Put your site into `mapart.json` ([SITE_CONFIG.md](SITE_CONFIG.md)).
- [ ] `enabled: false`, so the builder starts off.
- [ ] Discord: `discord.enabled`, `token`, `channelId`, `alertMention`. `coordinates: RELATIVE` unless the channel
      is private.
- [ ] Leave `safety.*` at the defaults, with `teleportGuard` on.
- [ ] Check in game that the real base has everything the config uses: stocked supply chests (maps, panes, XP,
      anvils, tools, food), room in the output chest, the cartography table and anvil, the clear buttons, and any
      collection switch.

With the builder off, once the bot stands at the base:

- [ ] `mapart site` lists no problems.
- [ ] `mapart status` shows the builder off and the Discord bot connected.
- [ ] `!map help` answers in the channel, with `stop` listed first.
- [ ] Kill switch dry run: `!map stop` replies STOPPED, `!map resume` carries on.
- [ ] The bot is within `safety.maxDistanceFromBase` (256) of a platform, or the first tick is a SAFETY STOP.

Scan and stock, builder on, queue empty:

- [ ] `!map queue` is empty.
- [ ] `mapart on`, then `mapart scan`. Watch the walk past the material chests.
- [ ] `!map stock <item>` shows plausible counts.

The job:

- [ ] Upload exactly one small, carpet-only, single map with every colour in stock. Its card shows no `!` and no `✗`.
- [ ] `!map queue` shows exactly that job.
- [ ] The platform is empty, collection off, flying machines parked, nobody around.

## Watch

- **Discord:** the presence line and alerts (SAFETY STOP, "Map art blocked", "Stuck: paused"). `!map status` any time.
  `!map verbose on` adds routine steps.
- **In game:** spectate with a second account.
- **Log:** `log/latest.log` in the ZenithProxy directory. Plugin lines start with `[mapart]`, alerts with
  `[mapart] ALERT`.

Expected order: `STOCK_CHECK`, `GEAR`, `RESTOCK`, `BUILD`, `VERIFY`, `FILL_MAP`, `CHECK_MAP`, `LOCK`, `RENAME`,
`STORE`, `COUNT` (only with a map counter), `NUKE`, `CLEAR`, `WAIT_CLEAR`, `FINISH_TILE`, `IDLE`. What each one does is
in [HOW_IT_WORKS.md](HOW_IT_WORKS.md).

If `CHECK_MAP` goes `BLOCKED` with "no map data", run `disconnect` and `connect` in ZenithProxy, then `!map resume`
once the bot is back in game.

## Abort

Stop with `!map stop` (or `!map stop disconnect` for anything server-side) on any of these:

- a kick or disconnect;
- setbacks that keep coming, rubber-banding, or a "Stuck: paused" alert;
- a wrong block placed, or a block placed off the art layer;
- walking off the platform or walkway, or heading somewhere unexpected;
- another player nearby;
- a chest that does not open, items that do not move, or a chest left open;
- a button or lever pressed that should not be;
- anything else that looks wrong.

Stopping costs nothing. `!map resume` continues where it left off.

## After

- [ ] The output chest holds one locked map with the job's name. The render in "Map done" matches the preview.
- [ ] The platform is clear, collection off, the machines parked.
- [ ] `!map queue` shows the job `DONE`.
- [ ] Keep the log and `plugins/mapart/` if you want to look at the run later. They hold real coordinates.

To roll back: `mapart off`, `shutdown`, remove the jar from `plugins/`.
