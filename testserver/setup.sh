#!/usr/bin/env bash
# Download + verify the pinned server jars into cache/, then lay out work/ (server jar, plugins,
# eula.txt, server.properties with a fresh RCON password). Idempotent: re-running only fixes
# what is missing or wrong. Does not touch the world (see reset-world.sh).
#
# Usage: ./setup.sh [--check-latest]
#   --check-latest  also print the newest Paper/Grim/Via releases upstream (informational only)
set -euo pipefail
shopt -s nullglob
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

UA="zenith-mapslopbot-testenv/0.1 (+local test server)"
CHECK_LATEST=0
for a in "$@"; do
    case "$a" in
        --check-latest) CHECK_LATEST=1 ;;
        -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
        *) die "unknown argument: $a" ;;
    esac
done

command -v curl >/dev/null || die "curl is required"
command -v sha256sum >/dev/null || die "sha256sum is required"
command -v python3 >/dev/null || die "python3 is required"

mkdir -p "$CACHE_DIR" "$WORK_DIR/plugins" "$WORK_DIR/logs"

# fetch <file> <url> <sha256> [sha512]
fetch() {
    local file="$1" url="$2" sha256="$3" sha512="${4:-}" dst="$CACHE_DIR/$1" tmp got
    if [[ -f "$dst" ]] && [[ "$(sha256sum "$dst" | cut -d' ' -f1)" == "$sha256" ]]; then
        log "cached  $file (sha256 ok)"
        return 0
    fi
    log "fetch   $file"
    tmp="$dst.part"
    rm -f "$tmp"
    curl -fsSL --retry 3 --connect-timeout 20 -A "$UA" -o "$tmp" "$url"
    got=$(sha256sum "$tmp" | cut -d' ' -f1)
    if [[ "$got" != "$sha256" ]]; then
        rm -f "$tmp"
        die "sha256 mismatch for $file: got $got, want $sha256"
    fi
    if [[ -n "$sha512" ]]; then
        got=$(sha512sum "$tmp" | cut -d' ' -f1)
        if [[ "$got" != "$sha512" ]]; then
            rm -f "$tmp"
            die "sha512 mismatch for $file (Modrinth hash): got $got"
        fi
    fi
    mv -f "$tmp" "$dst"
    log "ok      $file sha256=$sha256"
}

fetch "$PAPER_FILE" "$PAPER_URL" "$PAPER_SHA256"
fetch "$GRIM_FILE" "$GRIM_URL" "$GRIM_SHA256" "$GRIM_SHA512"
fetch "$VIAVERSION_FILE" "$VIAVERSION_URL" "$VIAVERSION_SHA256" "$VIAVERSION_SHA512"
fetch "$VIABACKWARDS_FILE" "$VIABACKWARDS_URL" "$VIABACKWARDS_SHA256" "$VIABACKWARDS_SHA512"

# install <cache file> <destination>: copy only when content differs.
install_file() {
    local src="$CACHE_DIR/$1" dst="$2"
    if [[ -f "$dst" ]] && cmp -s "$src" "$dst"; then return 0; fi
    cp -f "$src" "$dst.tmp"
    mv -f "$dst.tmp" "$dst"
    log "install $(basename "$dst")"
}

if server_pid >/dev/null; then
    warn "server is running; restart it (stop.sh, start.sh) to pick up changed jars or server.properties"
fi

install_file "$PAPER_FILE" "$WORK_DIR/paper.jar"

# Remove stale versions of the plugins we manage (work/ is generated, nothing of the owner's lives here).
for old in "$WORK_DIR"/plugins/grimac-*.jar "$WORK_DIR"/plugins/ViaVersion-*.jar "$WORK_DIR"/plugins/ViaBackwards-*.jar; do
    case "$(basename "$old")" in
        "$GRIM_FILE"|"$VIAVERSION_FILE"|"$VIABACKWARDS_FILE") ;;
        *) log "remove stale plugin $(basename "$old")"; rm -f -- "$old" ;;
    esac
done
install_file "$GRIM_FILE" "$WORK_DIR/plugins/$GRIM_FILE"
install_file "$VIAVERSION_FILE" "$WORK_DIR/plugins/$VIAVERSION_FILE"
install_file "$VIABACKWARDS_FILE" "$WORK_DIR/plugins/$VIABACKWARDS_FILE"

# Minecraft EULA (https://aka.ms/MinecraftEULA). The server refuses to start without it.
if [[ ! -f "$WORK_DIR/eula.txt" ]] || ! grep -q '^eula=true$' "$WORK_DIR/eula.txt"; then
    printf '#Accepted by testserver/setup.sh for the local test server (https://aka.ms/MinecraftEULA)\neula=true\n' > "$WORK_DIR/eula.txt"
    log "wrote eula.txt"
fi

# RCON password: random, generated once, never committed (work/ is gitignored).
if [[ ! -s "$RCON_PASSWORD_FILE" ]]; then
    umask 077
    python3 -I -c 'import secrets; print(secrets.token_urlsafe(24))' > "$RCON_PASSWORD_FILE"
    umask 022
    log "generated RCON password -> $RCON_PASSWORD_FILE"
fi
chmod 600 "$RCON_PASSWORD_FILE"
RCON_PASSWORD=$(<"$RCON_PASSWORD_FILE")

# Merge the managed keys from config/server.properties into work/server.properties.
[[ -f "$PROPS_FILE" ]] || : > "$PROPS_FILE"
managed=$(mktemp)
trap 'rm -f "$managed"' EXIT
grep -v '^[[:space:]]*#' "$CONFIG_DIR/server.properties" | grep '=' | sed -e "s|@RCON_PASSWORD@|$RCON_PASSWORD|" \
    -e "s|^server-port=.*|server-port=$SERVER_PORT|" -e "s|^rcon\.port=.*|rcon.port=$RCON_PORT|" > "$managed"
awk -F= '
    NR == FNR { k = $1; v = $0; sub(/^[^=]*=/, "", v); want[k] = v; order[++n] = k; next }
    {
        k = $1
        if (!/^[[:space:]]*#/ && (k in want)) { print k "=" want[k]; seen[k] = 1; next }
        print
    }
    END { for (i = 1; i <= n; i++) if (!(order[i] in seen)) print order[i] "=" want[order[i]] }
' "$managed" "$PROPS_FILE" > "$PROPS_FILE.tmp"
mv -f "$PROPS_FILE.tmp" "$PROPS_FILE"
chmod 600 "$PROPS_FILE"
log "server.properties: managed keys applied ($(wc -l < "$managed") keys; server-port $SERVER_PORT, rcon.port $RCON_PORT)"

# No End dimension (bukkit.yml); the Nether is off via allow-nether=false.
if [[ -f "$WORK_DIR/bukkit.yml" ]]; then
    sed -i 's/^\([[:space:]]*\)allow-end: true/\1allow-end: false/' "$WORK_DIR/bukkit.yml"
else
    printf 'settings:\n  allow-end: false\n' > "$WORK_DIR/bukkit.yml"
fi

JAVA_BIN=$(pick_java) || die "no java found; install JDK 21-25 or set TESTSERVER_JAVA"
log "java: $JAVA_BIN (major $(java_major "$JAVA_BIN"))"

if [[ "$CHECK_LATEST" == 1 ]]; then
    log "upstream check (informational, nothing is changed):"
    curl -fsS -A "$UA" "https://fill.papermc.io/v3/projects/paper/versions/$MC_VERSION/builds" \
        | python3 -I -c 'import json,sys; b=[x for x in json.load(sys.stdin) if x["channel"]=="STABLE"]; print("  paper %s newest stable build: %s (pinned %s)" % (sys.argv[1], b[0]["id"], sys.argv[2]))' "$MC_VERSION" "$PAPER_BUILD" || warn "paper check failed"
    for proj in grimac viaversion viabackwards; do
        curl -fsS -A "$UA" "https://api.modrinth.com/v2/project/$proj/version?loaders=%5B%22paper%22%5D&game_versions=%5B%22$MC_VERSION%22%5D" \
            | python3 -I -c 'import json,sys; v=[x for x in json.load(sys.stdin) if x["version_type"]=="release"]; print("  %s newest release for %s: %s" % (sys.argv[1], sys.argv[2], v[0]["version_number"] if v else "none"))' "$proj" "$MC_VERSION" || warn "$proj check failed"
    done
fi

if [[ ! -f "$WORLD_DIR/level.dat" ]]; then
    log "no world yet: run ./reset-world.sh"
fi
log "setup done (work dir: $WORK_DIR)"
