# Shared helpers for the testserver scripts. Source it; do not run it.
# shellcheck shell=bash

TS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$TS_DIR/.." && pwd)"
CACHE_DIR="$TS_DIR/cache"
WORK_DIR="$TS_DIR/work"
CONFIG_DIR="$TS_DIR/config"
# Base-specific files with real coordinates (gitignored); committed copies live in examples/.
LOCAL_DIR="$TS_DIR/local"
PID_FILE="$WORK_DIR/server.pid"
CONSOLE_LOG="$WORK_DIR/logs/console.out"
LATEST_LOG="$WORK_DIR/logs/latest.log"
PROPS_FILE="$WORK_DIR/server.properties"
RCON_PASSWORD_FILE="$WORK_DIR/.rcon-password"
WORLD_NAME="world"
WORLD_DIR="$WORK_DIR/$WORLD_NAME"

# A world download of your base (e.g. a singleplayer save). READ-ONLY: scripts only ever copy from it.
# Set TESTSERVER_WORLD_SRC to its folder.
DEFAULT_WORLD_SRC="$HOME/.minecraft/saves/2b2t.org"
WORLD_SRC="${TESTSERVER_WORLD_SRC:-$DEFAULT_WORLD_SRC}"

# Ports (all bound to 127.0.0.1). Override them to run a second instance from another checkout
# (setup.sh writes them into work/server.properties; zenith-run-config/install.sh into run/config.json).
SERVER_HOST="127.0.0.1"
SERVER_PORT="${TESTSERVER_PORT:-25566}"
RCON_PORT="${TESTSERVER_RCON_PORT:-25576}"
# ZenithProxy's own server (players join the bot here) and the bot's offline account name.
ZENITH_PORT="${TESTSERVER_ZENITH_PORT:-25565}"
BOT_NAME="${TESTSERVER_BOT_NAME:-MapArtBot}"

# Server heap. The world is six region files; 2 GiB is plenty.
SERVER_XMS="${TESTSERVER_XMS:-1G}"
SERVER_XMX="${TESTSERVER_XMX:-2G}"

# shellcheck source=versions.env
source "$TS_DIR/versions.env"

log()  { printf '[testserver] %s\n' "$*"; }
warn() { printf '[testserver] WARN: %s\n' "$*" >&2; }
die()  { printf '[testserver] ERROR: %s\n' "$*" >&2; exit 1; }

# java_major <java-binary> -> prints the feature version (e.g. 25), or nothing.
java_major() {
    local out
    out=$("$1" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2; exit}') || true
    printf '%s' "${out%%.*}"
}

# pick_java -> prints the java binary to use for Paper.
# Order: $TESTSERVER_JAVA, $JAVA_HOME, Gradle-provisioned JDKs, PATH. First one with major 21..25 wins
# (Paper 1.21.11 needs >= 21; 25 is the newest LTS it is known to run on). Falls back to PATH java.
pick_java() {
    local cands=() c m gh
    [[ -n "${TESTSERVER_JAVA:-}" ]] && cands+=("$TESTSERVER_JAVA")
    [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]] && cands+=("$JAVA_HOME/bin/java")
    for gh in "${GRADLE_USER_HOME:-}" "$HOME/.gradle" "$HOME/.local/share/gradle"; do
        [[ -n "$gh" && -d "$gh/jdks" ]] || continue
        while IFS= read -r c; do cands+=("$c"); done < <(find "$gh/jdks" -mindepth 3 -maxdepth 3 -path '*/bin/java' -type f 2>/dev/null | LC_ALL=C sort -r)
    done
    if command -v java >/dev/null 2>&1; then cands+=("$(command -v java)"); fi
    if [[ -n "${TESTSERVER_JAVA:-}" ]]; then printf '%s' "$TESTSERVER_JAVA"; return 0; fi
    for c in "${cands[@]}"; do
        m=$(java_major "$c")
        if [[ -n "$m" && "$m" -ge 21 && "$m" -le 25 ]]; then printf '%s' "$c"; return 0; fi
    done
    if command -v java >/dev/null 2>&1; then command -v java; return 0; fi
    return 1
}

# server_pid -> prints the pid of the running test server (from the pid file), or returns 1.
server_pid() {
    [[ -f "$PID_FILE" ]] || return 1
    local pid comm
    pid=$(<"$PID_FILE")
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    [[ -r "/proc/$pid/comm" ]] || return 1
    comm=$(<"/proc/$pid/comm")
    [[ "$comm" == "java" ]] || return 1
    # Must be our server: its working directory is WORK_DIR.
    [[ "$(readlink "/proc/$pid/cwd" 2>/dev/null)" == "$WORK_DIR" ]] || return 1
    printf '%s' "$pid"
}

# prop_get <key> -> value from work/server.properties
prop_get() {
    [[ -f "$PROPS_FILE" ]] || return 1
    awk -F= -v k="$1" '$1 == k { sub(/^[^=]*=/, ""); print; exit }' "$PROPS_FILE"
}

# Refuse to run a server that could be reached from outside this machine.
assert_local_only() {
    local ip rcon query
    ip=$(prop_get server-ip || true)
    rcon=$(prop_get enable-rcon || true)
    query=$(prop_get enable-query || true)
    [[ "$ip" == "127.0.0.1" ]] || die "server.properties server-ip is '$ip', expected 127.0.0.1 (run ./setup.sh)"
    [[ "$query" != "true" ]] || die "enable-query=true in server.properties; refusing"
    [[ "$rcon" == "true" ]] || die "enable-rcon is not true in server.properties (run ./setup.sh)"
}

rcon() { python3 -I "$TS_DIR/rcon.py" "$@"; }
