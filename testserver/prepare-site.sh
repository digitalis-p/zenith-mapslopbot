#!/usr/bin/env bash
# Make the freshly reset test world ready for a moss build: apply the test-world edits
# (labelled tools/food/kit/return chests, output label, terracotta sorter columns) and stock
# every chest over RCON. Idempotent; run after reset-world.sh + start.sh.
#
# Usage: ./prepare-site.sh [--kit PLAYER] [stock-chests.py options...]
#   --kit PLAYER   also hand an online player (e.g. MapArtBot) enchanted tools, boots and food
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ "${1:-}" == -h || "${1:-}" == --help ]] && { sed -n '2,8p' "$0"; exit 0; }
server_pid >/dev/null || die "server is not running (./start.sh)"

[[ -f "$LOCAL_DIR/world-edits.moss.txt" ]] || die "missing $LOCAL_DIR/world-edits.moss.txt (base coordinates are kept out of git; see docs/TESTING.md)"
log "applying local/world-edits.moss.txt"
rcon -q -f "$LOCAL_DIR/world-edits.moss.txt"
log "stocking chests (local/site-config.moss.json + local/moss-materials.json)"
python3 -I "$TS_DIR/stock-chests.py" "$@"
