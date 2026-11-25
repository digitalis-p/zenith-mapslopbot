#!/usr/bin/env bash
# Show whether the test server runs, its memory, listening sockets and online players.
#
# Usage: ./status.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

if ! pid=$(server_pid); then
    log "server: not running"
    exit 1
fi
rss_kb=$(awk '/^VmRSS:/ {print $2}' "/proc/$pid/status")
log "server: running, pid $pid, RSS $(( rss_kb / 1024 )) MiB (heap max $SERVER_XMX)"
if command -v ss >/dev/null; then
    log "listening sockets of pid $pid:"
    ss -ltnpH 2>/dev/null | awk -v p="pid=$pid," 'index($0, p) { print "  " $4 }'
fi
log "players: $(rcon list 2>&1)"
