#!/usr/bin/env bash
# Start the local Paper test server in the background (pid file + log), wait until it is ready,
# then apply config/init-commands.txt over RCON (gamerules, world spawn; idempotent).
#
# Usage: ./start.sh [--no-wait] [--timeout SECONDS]
# Env:   TESTSERVER_JAVA=<java binary>  TESTSERVER_XMX=2G  TESTSERVER_XMS=1G
# Logs:  work/logs/latest.log (Paper), work/logs/console.out (stdout/stderr of the JVM)
set -euo pipefail
shopt -s nullglob
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

WAIT=1
TIMEOUT=240
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-wait) WAIT=0 ;;
        --timeout) shift; TIMEOUT="$1" ;;
        -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
    shift
done

if pid=$(server_pid); then
    log "already running (pid $pid)"
    exit 0
fi
rm -f "$PID_FILE"

[[ -f "$WORK_DIR/paper.jar" ]] || die "work/paper.jar missing: run ./setup.sh"
[[ -f "$WORLD_DIR/level.dat" ]] || die "no world in work/: run ./reset-world.sh"
assert_local_only

JAVA_BIN=$(pick_java) || die "no java found; set TESTSERVER_JAVA"
JAVA_MAJOR=$(java_major "$JAVA_BIN")
log "java $JAVA_MAJOR: $JAVA_BIN"

# Paper's recommended G1 flags (from the Fill API), sized for a small local server.
JVM_ARGS=(
    "-Xms$SERVER_XMS" "-Xmx$SERVER_XMX"
    -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200
    -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC
    -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M
    -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4
    -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90
    -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem
    -XX:MaxTenuringThreshold=1
)

mkdir -p "$WORK_DIR/logs"
start_marker=$(date +%s)
{
    printf '\n===== start.sh %s =====\n' "$(date -Is)"
} >> "$CONSOLE_LOG"

# setsid: own session, so the server survives the calling shell; stdin from /dev/null (no console).
(
    cd "$WORK_DIR"
    exec setsid "$JAVA_BIN" "${JVM_ARGS[@]}" -jar paper.jar --nogui < /dev/null >> "$CONSOLE_LOG" 2>&1
) &
pid=$!
echo "$pid" > "$PID_FILE"
log "started pid $pid (log: $LATEST_LOG)"

[[ "$WAIT" == 1 ]] || exit 0

log "waiting for 'Done' (timeout ${TIMEOUT}s)..."
deadline=$(( start_marker + TIMEOUT ))
while :; do
    if [[ ! -d "/proc/$pid" ]]; then
        warn "server exited during startup; last lines of $CONSOLE_LOG:"
        tail -n 40 "$CONSOLE_LOG" >&2
        rm -f "$PID_FILE"
        exit 1
    fi
    if [[ -f "$LATEST_LOG" ]] && [[ $(stat -c %Y "$LATEST_LOG") -ge $start_marker ]] \
        && grep -q 'Done (' "$LATEST_LOG"; then
        break
    fi
    if (( $(date +%s) > deadline )); then
        warn "not ready after ${TIMEOUT}s; still running as pid $pid. See $LATEST_LOG"
        exit 1
    fi
    sleep 1
done
log "ready: $(grep -m1 'Done (' "$LATEST_LOG" | sed 's/^.*Done (/Done (/')"

if [[ -f "$CONFIG_DIR/init-commands.txt" ]]; then
    log "applying config/init-commands.txt"
    rcon - < "$CONFIG_DIR/init-commands.txt" | sed 's/^/  rcon> /'
fi
# Speed mode (./speed-mode.sh): the server ticks N times faster.
if [[ -f "$WORK_DIR/speed-mode" ]]; then
    mult=$(<"$WORK_DIR/speed-mode")
    if [[ "$mult" =~ ^[0-9]+$ ]]; then
        rcon "tick rate $(( mult * 20 ))" | sed 's/^/  rcon> /'
        warn "SPEED MODE ${mult}x: no Grim, results say nothing about anti-cheat (./speed-mode.sh off)"
    fi
fi
# World spawn: $TESTSERVER_SPAWN="x y z", else work/spawn.txt (written by reset-world.sh).
spawn="${TESTSERVER_SPAWN:-}"
if [[ -z "$spawn" && -f "$WORK_DIR/spawn.txt" ]]; then spawn=$(<"$WORK_DIR/spawn.txt"); fi
if [[ "$spawn" =~ ^-?[0-9]+\ -?[0-9]+\ -?[0-9]+$ ]]; then
    rcon "setworldspawn $spawn" | sed 's/^/  rcon> /'
elif [[ -n "$spawn" ]]; then
    warn "ignoring malformed spawn '$spawn' (want 'x y z')"
fi
log "server listening on $SERVER_HOST:$(prop_get server-port), RCON on $SERVER_HOST:$(prop_get rcon.port)"
[[ "$(prop_get server-port)" == "$SERVER_PORT" && "$(prop_get rcon.port)" == "$RCON_PORT" ]] \
    || warn "work/server.properties ports differ from TESTSERVER_PORT/TESTSERVER_RCON_PORT ($SERVER_PORT/$RCON_PORT); re-run setup.sh with them"
