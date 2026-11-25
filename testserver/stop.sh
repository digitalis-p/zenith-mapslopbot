#!/usr/bin/env bash
# Stop the local test server: RCON 'stop' (saves the world), wait, then SIGTERM, then SIGKILL.
#
# Usage: ./stop.sh [--timeout SECONDS]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

TIMEOUT=90
while [[ $# -gt 0 ]]; do
    case "$1" in
        --timeout) shift; TIMEOUT="$1" ;;
        -h|--help) sed -n '2,4p' "$0"; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
    shift
done

if ! pid=$(server_pid); then
    log "not running"
    rm -f "$PID_FILE"
    exit 0
fi

wait_gone() {  # wait_gone <seconds> -> 0 when the pid is gone
    local end=$(( $(date +%s) + $1 ))
    while [[ -d "/proc/$pid" ]]; do
        (( $(date +%s) < end )) || return 1
        sleep 1
    done
    return 0
}

log "stopping pid $pid via RCON 'stop'"
if rcon -q stop; then
    if wait_gone "$TIMEOUT"; then
        rm -f "$PID_FILE"
        log "stopped cleanly"
        exit 0
    fi
    warn "still running after ${TIMEOUT}s"
else
    warn "RCON stop failed"
fi

log "sending SIGTERM to $pid"
kill -TERM "$pid" 2>/dev/null || true
if wait_gone 60; then
    rm -f "$PID_FILE"
    log "stopped (SIGTERM)"
    exit 0
fi
warn "sending SIGKILL to $pid (world may not be saved)"
kill -KILL "$pid" 2>/dev/null || true
wait_gone 10 || die "pid $pid did not die"
rm -f "$PID_FILE"
log "killed"
