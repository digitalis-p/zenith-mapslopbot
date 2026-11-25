#!/usr/bin/env bash
# Install the ZenithProxy dev-run config into the repo's run/ directory (gitignored), which is
# where `./gradlew run` starts ZenithProxy with our plugin.
#
# Usage: ./install.sh [--name BOTNAME] [--fresh] [--site FILE | --no-site]
#   --name     offline account name (default $TESTSERVER_BOT_NAME, else MapArtBot; 3-16 chars, letters/digits/_)
#   --fresh    replace run/config.json instead of merging the template keys over it
#   --site     site config to put into run/plugins/config/mapart.json (default:
#              testserver/local/site-config.moss.json); --no-site leaves mapart.json alone
# Existing run/config.json, launch_config.json and mapart.json are backed up first (*.bak-<time>).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../lib.sh"
RUN_DIR="$REPO_DIR/run"

NAME="$BOT_NAME"
FRESH=()
SITE="$TS_DIR/local/site-config.moss.json"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --name) shift; NAME="$1" ;;
        --fresh) FRESH=(--fresh) ;;
        --site) shift; SITE="$1" ;;
        --no-site) SITE="" ;;
        -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
    shift
done

if python3 -I "$HERE/zenith.py" pid "$RUN_DIR" >/dev/null; then
    die "ZenithProxy is running in $RUN_DIR; stop it first (./stop-zenith.sh); it rewrites its configs on exit"
fi

python3 -I "$HERE/zenith.py" render "$RUN_DIR" "$NAME" "${FRESH[@]}"
python3 -I "$HERE/zenith.py" check "$RUN_DIR"
if [[ -n "$SITE" ]]; then
    python3 -I "$HERE/zenith.py" site "$RUN_DIR" "$SITE"
fi
log "installed. Start with: $HERE/start-zenith.sh"
