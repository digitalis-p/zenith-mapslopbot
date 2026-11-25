#!/usr/bin/env bash
# Replace work/world with a FRESH COPY of the owner's world download of the base.
# The source is only read (cp), never modified. The previous copy is kept as work/world.prev
# (one generation; the older work/world.prev is removed). This also wipes the bot's playerdata,
# stock in chests and everything the plugin built, so the next run starts from the download.
#
# Usage: ./reset-world.sh [--bot NAME]...   (server must be stopped)
#   --bot NAME  offline player to place on the world spawn (repeatable; default $TESTSERVER_BOT_NAME,
#               else MapArtBot, the ZenithProxy bot). Its playerdata is seeded so it joins on the
#               walkway, not on the highest block above it.
# Env:   TESTSERVER_WORLD_SRC=<dir>  use another world folder as the source
set -euo pipefail
shopt -s nullglob
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

BOTS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --bot) shift; BOTS+=("$1") ;;
        -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
    shift
done
[[ ${#BOTS[@]} -gt 0 ]] || BOTS=("$BOT_NAME")
for b in "${BOTS[@]}"; do
    [[ "$b" =~ ^[A-Za-z0-9_]{3,16}$ ]] || die "invalid player name: $b"
done

if pid=$(server_pid); then
    die "server is running (pid $pid); run ./stop.sh first"
fi

[[ -f "$WORLD_SRC/level.dat" ]] || die "no level.dat in source world: $WORLD_SRC"
[[ -d "$WORLD_SRC/region" ]] || die "no region/ in source world: $WORLD_SRC"

src_dv=$(python3 -I "$TS_DIR/nbt.py" "$WORLD_SRC/level.dat" Data.DataVersion | cut -d= -f2)
src_name=$(python3 -I "$TS_DIR/nbt.py" "$WORLD_SRC/level.dat" Data.Version.Name | cut -d= -f2 | tr -d '"')
log "source: $WORLD_SRC"
log "source DataVersion $src_dv ($src_name); pinned server writes $WORLD_DATA_VERSION ($MC_VERSION)"
if [[ "$src_dv" =~ ^[0-9]+$ ]] && (( src_dv > WORLD_DATA_VERSION )); then
    die "world is newer than the pinned server ($src_dv > $WORLD_DATA_VERSION); bump versions.env"
fi
src_level_sha=$(sha256sum "$WORLD_SRC/level.dat" | cut -d' ' -f1)

mkdir -p "$WORK_DIR"
if [[ -d "$WORLD_DIR" ]]; then
    if [[ -d "$WORK_DIR/world.prev" ]]; then
        rm -rf -- "$WORK_DIR/world.prev"
    fi
    mv -T -- "$WORLD_DIR" "$WORK_DIR/world.prev"
    log "previous copy kept as work/world.prev"
fi
# Paper also creates these when the dimensions are enabled; they are not part of the download.
for d in "$WORK_DIR/${WORLD_NAME}_nether" "$WORK_DIR/${WORLD_NAME}_the_end"; do
    if [[ -d "$d" ]]; then rm -rf -- "$d"; fi
done

tmp="$WORK_DIR/.world.copying"
rm -rf -- "$tmp"
cp -a --reflink=auto -- "$WORLD_SRC" "$tmp"
# The copy must not carry the client's lock or marker files.
rm -f -- "$tmp/session.lock" "$tmp/.swd_marker.txt"
mv -T -- "$tmp" "$WORLD_DIR"

# Prove the source was not touched.
[[ "$(sha256sum "$WORLD_SRC/level.dat" | cut -d' ' -f1)" == "$src_level_sha" ]] || die "source level.dat changed during copy?!"

# World spawn. SimpleWorldDownloader writes the pre-1.21.9 SpawnX/Y/Z fields (where the owner stood, on
# the moss walkway). 1.21.11 only reads Data.spawn.pos, so it would spawn new players at 0,0 over
# the void. Keep the downloader's spawn in work/spawn.txt; start.sh applies it with /setworldspawn.
spawn=$(python3 -I "$TS_DIR/nbt.py" "$WORLD_DIR/level.dat" Data.SpawnX Data.SpawnY Data.SpawnZ | cut -d= -f2 | paste -sd' ')
if [[ "$spawn" == *missing* ]]; then
    spawn=$(python3 -I "$TS_DIR/nbt.py" "$WORLD_DIR/level.dat" Data.spawn.pos.0 Data.spawn.pos.1 Data.spawn.pos.2 | cut -d= -f2 | paste -sd' ')
fi
[[ "$spawn" =~ ^-?[0-9]+\ -?[0-9]+\ -?[0-9]+$ ]] || die "could not read the world spawn from level.dat (got '$spawn')"
echo "$spawn" > "$WORK_DIR/spawn.txt"
read -r sx sy sz <<< "$spawn"
for b in "${BOTS[@]}"; do
    python3 -I "$TS_DIR/nbt.py" seed-player "$WORLD_DIR" "$b" "$sx" "$sy" "$sz" | sed 's/^/[testserver] /'
done
nreg=$(find "$WORLD_DIR/region" -name '*.mca' | wc -l)
log "fresh copy at $WORLD_DIR ($nreg region files, $(du -sh --apparent-size "$WORLD_DIR" | cut -f1))"
log "world spawn $spawn -> work/spawn.txt (applied by start.sh)"
log "start the server with ./start.sh"
