#!/usr/bin/env bash
# Run ZenithProxy with our plugin in the background via the Gradle `run` task (repo's run/ dir).
# Terminal input comes from a FIFO, so commands can be sent with ./zenith-cmd.sh.
#
# Usage: ./start-zenith.sh [--no-wait] [--timeout SECONDS]
# Logs:  run/log/latest.log (ZenithProxy), run/zenith-console.log (Gradle + console output)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../lib.sh"
RUN_DIR="$REPO_DIR/run"
FIFO="$RUN_DIR/.zenith-stdin"
HOLDER_PID="$RUN_DIR/.zenith-holder.pid"
GRADLE_PID="$RUN_DIR/.zenith-gradle.pid"
ZENITH_PID="$RUN_DIR/.zenith.pid"
CONSOLE="$RUN_DIR/zenith-console.log"
ZLOG="$RUN_DIR/log/latest.log"

WAIT=1
TIMEOUT=600
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-wait) WAIT=0 ;;
        --timeout) shift; TIMEOUT="$1" ;;
        -h|--help) sed -n '2,6p' "$0"; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
    shift
done

[[ -f "$RUN_DIR/config.json" ]] || die "no run/config.json: run $HERE/install.sh first"
# Hard guard: never let the dev run reach 2b2t or listen beyond localhost.
python3 -I "$HERE/zenith.py" check "$RUN_DIR" || die "run/config.json is not local-only; re-run install.sh"

if pid=$(python3 -I "$HERE/zenith.py" pid "$RUN_DIR"); then
    log "ZenithProxy already running (pid $pid)"
    exit 0
fi
server_pid >/dev/null || warn "the test server is not running (testserver/start.sh); the bot will retry"

rm -f "$FIFO" "$ZENITH_PID"
mkfifo -m 600 "$FIFO"
# Holder keeps the FIFO's write end open, so ZenithProxy never sees EOF on its terminal.
setsid sleep infinity 3>"$FIFO" < /dev/null > /dev/null 2>&1 &
echo $! > "$HOLDER_PID"

start_marker=$(date +%s)
printf '\n===== start-zenith.sh %s =====\n' "$(date -Is)" >> "$CONSOLE"
(
    cd "$REPO_DIR"
    exec setsid ./gradlew --no-daemon --console=plain run < "$FIFO" >> "$CONSOLE" 2>&1
) &
echo $! > "$GRADLE_PID"
log "started gradle run (pid $(<"$GRADLE_PID")); console: $CONSOLE"

[[ "$WAIT" == 1 ]] || exit 0
log "waiting for 'ZenithProxy started!' (timeout ${TIMEOUT}s; first run compiles and downloads)..."
deadline=$(( start_marker + TIMEOUT ))
while :; do
    if [[ ! -d "/proc/$(<"$GRADLE_PID")" ]]; then
        warn "gradle exited; last lines of $CONSOLE:"
        tail -n 40 "$CONSOLE" >&2
        exit 1
    fi
    if [[ -f "$ZLOG" ]] && [[ $(stat -c %Y "$ZLOG") -ge $start_marker ]] && grep -q 'ZenithProxy started!' "$ZLOG"; then
        break
    fi
    if (( $(date +%s) > deadline )); then
        warn "not started after ${TIMEOUT}s; see $CONSOLE"
        exit 1
    fi
    sleep 2
done
zpid=$(python3 -I "$HERE/zenith.py" pid "$RUN_DIR") || die "ZenithProxy JVM not found"
echo "$zpid" > "$ZENITH_PID"
log "ZenithProxy started (pid $zpid). Send commands: $HERE/zenith-cmd.sh <command>"
