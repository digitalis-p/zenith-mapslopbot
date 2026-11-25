#!/usr/bin/env bash
# Give a player operator rights on the local test server (via RCON).
#
# Usage: ./op.sh <player> [--deop]
# The server is offline-mode, so any name works; the ZenithProxy bot uses the name from
# zenith-run-config (default MapArtBot). Ops can use /tp, /give, /gamemode in-game.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -ge 1 && "$1" != -h && "$1" != --help ]] || { sed -n '2,6p' "$0"; exit 2; }
name="$1"
[[ "$name" =~ ^[A-Za-z0-9_]{1,16}$ ]] || die "invalid player name: $name"
server_pid >/dev/null || die "server is not running (./start.sh)"

if [[ "${2:-}" == "--deop" ]]; then
    rcon "deop $name"
else
    rcon "op $name"
fi
