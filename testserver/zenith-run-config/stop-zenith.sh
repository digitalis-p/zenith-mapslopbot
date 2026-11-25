#!/usr/bin/env bash
# Stop the background ZenithProxy dev run: terminal 'shutdown', then SIGTERM/SIGKILL fallbacks,
# then the Gradle client and the FIFO holder.
#
# Usage: ./stop-zenith.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../lib.sh"
RUN_DIR="$REPO_DIR/run"
FIFO="$RUN_DIR/.zenith-stdin"
HOLDER_PID="$RUN_DIR/.zenith-holder.pid"
GRADLE_PID="$RUN_DIR/.zenith-gradle.pid"
ZENITH_PID="$RUN_DIR/.zenith.pid"

gone() {  # gone <pid> <seconds>
    local end=$(( $(date +%s) + $2 ))
    while [[ -d "/proc/$1" ]]; do
        (( $(date +%s) < end )) || return 1
        sleep 1
    done
}

# kill_checked <pidfile> <expected comm>: only signal a pid that still is what we started.
kill_checked() {
    local f="$1" want="$2" pid comm
    [[ -f "$f" ]] || return 0
    pid=$(<"$f")
    if [[ "$pid" =~ ^[0-9]+$ && -r "/proc/$pid/comm" ]]; then
        comm=$(<"/proc/$pid/comm")
        if [[ "$comm" == "$want" ]]; then
            kill -TERM "$pid" 2>/dev/null || true
            gone "$pid" 20 || kill -KILL "$pid" 2>/dev/null || true
            log "stopped $want (pid $pid)"
        fi
    fi
    rm -f "$f"
}

if zpid=$(python3 -I "$HERE/zenith.py" pid "$RUN_DIR"); then
    log "ZenithProxy pid $zpid: sending 'shutdown'"
    if [[ -p "$FIFO" ]]; then
        timeout 5 bash -c 'printf "shutdown\n" > "$1"' _ "$FIFO" || true
    fi
    if ! gone "$zpid" 45; then
        warn "no exit after shutdown; SIGTERM"
        kill -TERM "$zpid" 2>/dev/null || true
        gone "$zpid" 20 || { warn "SIGKILL"; kill -KILL "$zpid" 2>/dev/null || true; }
    fi
    log "ZenithProxy stopped"
else
    log "ZenithProxy not running"
fi
rm -f "$ZENITH_PID"
# The Gradle client (wrapper JVM) normally exits by itself once the run task ends.
kill_checked "$GRADLE_PID" java
kill_checked "$HOLDER_PID" sleep
rm -f "$FIFO"
