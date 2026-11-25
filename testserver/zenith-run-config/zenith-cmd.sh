#!/usr/bin/env bash
# Send one command to the ZenithProxy terminal of the background dev run, e.g.
#   ./zenith-cmd.sh status
#   ./zenith-cmd.sh mapart submit /path/to/art.nbt
# The output appears in run/log/latest.log (tail -f it); this script prints the new log lines
# written in the next few seconds.
#
# Usage: ./zenith-cmd.sh <command...> [--wait SECONDS]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../lib.sh"
RUN_DIR="$REPO_DIR/run"
FIFO="$RUN_DIR/.zenith-stdin"
ZLOG="$RUN_DIR/log/latest.log"

WAIT=3
args=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --wait) shift; WAIT="$1" ;;
        -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
        *) args+=("$1") ;;
    esac
    shift
done
[[ ${#args[@]} -gt 0 ]] || { sed -n '2,9p' "$0"; exit 2; }
python3 -I "$HERE/zenith.py" pid "$RUN_DIR" >/dev/null || die "ZenithProxy is not running (./start-zenith.sh)"
[[ -p "$FIFO" ]] || die "no terminal FIFO at $FIFO"

before=0
[[ -f "$ZLOG" ]] && before=$(wc -l < "$ZLOG")
printf '%s\n' "${args[*]}" > "$FIFO"
sleep "$WAIT"
[[ -f "$ZLOG" ]] && tail -n +"$(( before + 1 ))" "$ZLOG"
exit 0
