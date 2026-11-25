#!/usr/bin/env bash
# Speed mode for quick functional runs of the plugin: Grim removed, the server and ZenithProxy both ticking
# N times faster (the plugin counts in ticks, so it scales along), and the packet limiters scaled to match.
# Speed mode gives NO anti-cheat signal; turn it off for runs that measure Grim flags.
#
# Usage: ./speed-mode.sh on [N] | off | status
#   on [N]  N = speed multiplier, default 10: server 20*N TPS, ZenithProxy client.tickRate N, and
#           work/config/paper-global.yml:
#             packet-limiter.all-packets.max-packet-rate    500.0 -> 500*N per second
#             spam-limiter.incoming-packet-threshold        300   -> 300/N ms (click window, floor, min 1)
#           work/plugins/ViaVersion/config.yml:
#             packet-limiter.max-per-second                 800   -> 800*N
#             packet-limiter.sustained-max-per-second       200   -> 200*N
#   off     Grim back, tick rates and all four limits back to the 1x values above
#   status  mode, Grim jar count and the current limit values
# on and off need the test server and ZenithProxy stopped (stop-zenith.sh, stop.sh); start.sh then applies
# the server tick rate, and ZenithProxy reads client.tickRate from run/config.json. A config file that
# changes is first copied to <file>.bak-speed-mode-YYYYmmdd-HHMMSS.
#
# Why each limit scales (the bot sends N times as many packets per real second):
# - Paper all-packets: 500/s at 1x. A fixed 5000/s got the bot kicked at 30x ("too many packets") within
#   0.5 s of walking.
# - Paper spam-limiter (ServerGamePacketListenerImpl.checkLimit, used by use-item and use-item-on): a window
#   of incoming-packet-threshold ms of real time (packet timestamps) lets 9 packets through and drops the rest;
#   the window restarts on the first packet after it ends. 300 ms is 6 ticks at 1x; at N x those 6 ticks take
#   300/N ms, so dividing the window by N keeps the allowance per tick. Integer division: a slightly shorter
#   window is slightly more permissive. At 10x with 300 the limiter dropped clicks (pacer "rejected").
# - ViaVersion packet-limiter: max-per-second and sustained-max-per-second count real seconds, so both scale
#   by N. At 10x, 800/s kicked the airplace bot ("You are sending too many packets!"); the gate B2 kicks at
#   20x/30x were most likely this too. sustained-period-seconds and sustained-threshold are real seconds
#   and stay as they are. ViaVersion writes its config.yml on the first server start; before that it is skipped.
set -euo pipefail
shopt -s nullglob
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

MARK="$WORK_DIR/speed-mode"
DISABLED="$WORK_DIR/plugins-disabled"
PAPER_GLOBAL="$WORK_DIR/config/paper-global.yml"
VIA_CONFIG="$WORK_DIR/plugins/ViaVersion/config.yml"
RUN_DIR="$REPO_DIR/run"

# The 1x values (Paper and ViaVersion defaults).
PAPER_RATE=500      # packet-limiter.all-packets.max-packet-rate, packets per second
SPAM_MS=300         # spam-limiter.incoming-packet-threshold, ms
VIA_MAX=800         # packet-limiter.max-per-second
VIA_SUSTAINED=200   # packet-limiter.sustained-max-per-second

PAPER_KEYS=(packet-limiter.all-packets.max-packet-rate spam-limiter.incoming-packet-threshold)
VIA_KEYS=(packet-limiter.max-per-second packet-limiter.sustained-max-per-second)

set_tick_rate() {  # set_tick_rate <multiplier> : client.tickRate in run/config.json
    [[ -f "$RUN_DIR/config.json" ]] || { warn "no run/config.json yet; run zenith-run-config/install.sh"; return 0; }
    python3 -I - "$RUN_DIR/config.json" "$1" <<'EOF'
import json, sys
path, rate = sys.argv[1], float(sys.argv[2])
c = json.load(open(path))
c.setdefault("client", {})["tickRate"] = rate
json.dump(c, open(path, "w"), indent=2)
print("run/config.json client.tickRate = %g" % rate)
EOF
}

# yaml_keys get <file> <label> <dotted.key>...          print the values
# yaml_keys set <file> <label> <dotted.key>=<number>...  set them, keeping comments and layout
# Each key is found by walking the indentation from its top-level block, so packet-limiter.max-per-second never
# matches the same key under packet-size-limiter. set changes nothing unless every key is found with a numeric
# value, and backs the file up beside itself first when the content changes.
yaml_keys() {
    python3 -I - "$@" <<'EOF'
import os, re, shutil, sys, time

mode, path, label, specs = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
with open(path) as f:
    text = f.read()
lines = text.split("\n")

def content(line):
    s = line.strip()
    return bool(s) and not s.startswith("#")

def indent(line):
    return len(line) - len(line.lstrip(" "))

def find(keys):
    lo, hi, ind = 0, len(lines), 0
    hit = None
    for depth, key in enumerate(keys):
        if depth:
            kids = [i for i in range(lo, hi) if content(lines[i])]
            if not kids:
                return None
            ind = indent(lines[kids[0]])
        hit = next((i for i in range(lo, hi) if content(lines[i]) and indent(lines[i]) == ind
                    and re.match(re.escape(key) + r":(\s|$)", lines[i][ind:])), None)
        if hit is None:
            return None
        lo, hi = hit + 1, next((j for j in range(hit + 1, hi)
                                if content(lines[j]) and indent(lines[j]) <= ind), hi)
    return hit

def leaf(dotted):
    i = find(dotted.split("."))
    if i is None:
        return None, None
    key = dotted.split(".")[-1]
    m = re.fullmatch(r"( *" + re.escape(key) + r":[ \t]+)(-?[0-9]+(?:\.[0-9]+)?)([ \t]*(?:#.*)?)", lines[i])
    return i, m

if mode == "get":
    for dotted in specs:
        i, m = leaf(dotted)
        value = "(missing)" if i is None else m.group(2) if m else lines[i].split(":", 1)[1].strip()
        print("%s %s = %s" % (label, dotted, value))
    sys.exit(0)

edits, report = [], []
for spec in specs:
    dotted, value = spec.split("=", 1)
    i, m = leaf(dotted)
    if m is None:
        sys.exit("could not find a numeric %s in %s; that file is unchanged" % (dotted, path))
    edits.append((i, m.group(1) + value + m.group(3)))
    report.append("%s %s = %s%s" % (label, dotted, value, "" if m.group(2) == value else " (was %s)" % m.group(2)))
for i, line in edits:
    lines[i] = line
new = "\n".join(lines)
if new != text:
    backup = path + time.strftime(".bak-speed-mode-%Y%m%d-%H%M%S")
    n = 1
    while os.path.exists(backup + ("" if n == 1 else "-%d" % n)):
        n += 1
    backup += "" if n == 1 else "-%d" % n
    shutil.copy2(path, backup)
    print("backed up %s to %s" % (label, backup))
    with open(path, "w") as f:
        f.write(new)
for r in report:
    print(r)
EOF
}

set_limits() {  # set_limits <multiplier> : the Paper and ViaVersion packet limiters for N x speed
    local n=$1 spam
    spam=$(( SPAM_MS / n ))
    (( spam >= 1 )) || spam=1
    if [[ -f "$PAPER_GLOBAL" ]]; then
        yaml_keys set "$PAPER_GLOBAL" paper-global.yml \
            "${PAPER_KEYS[0]}=$(( PAPER_RATE * n )).0" "${PAPER_KEYS[1]}=$spam"
    else
        warn "no $PAPER_GLOBAL yet (start the server once); Paper limits not set"
    fi
    if [[ -f "$VIA_CONFIG" ]]; then
        yaml_keys set "$VIA_CONFIG" ViaVersion/config.yml \
            "${VIA_KEYS[0]}=$(( VIA_MAX * n ))" "${VIA_KEYS[1]}=$(( VIA_SUSTAINED * n ))"
    else
        warn "no $VIA_CONFIG yet (start the server once with ViaVersion); Via limits not set"
    fi
}

show_limits() {
    if [[ -f "$PAPER_GLOBAL" ]]; then yaml_keys get "$PAPER_GLOBAL" paper-global.yml "${PAPER_KEYS[@]}"
    else log "no $PAPER_GLOBAL yet"; fi
    if [[ -f "$VIA_CONFIG" ]]; then yaml_keys get "$VIA_CONFIG" ViaVersion/config.yml "${VIA_KEYS[@]}"
    else log "no $VIA_CONFIG yet"; fi
}

require_stopped() {
    if server_pid >/dev/null; then die "the test server is running; stop it first (./stop.sh)"; fi
    if python3 -I "$TS_DIR/zenith-run-config/zenith.py" pid "$RUN_DIR" >/dev/null; then
        die "ZenithProxy is running; stop it first (zenith-run-config/stop-zenith.sh)"
    fi
}

cmd="${1:-status}"
case "$cmd" in
    on)
        n="${2:-10}"
        [[ "$n" =~ ^[0-9]+$ && "$n" -ge 1 && "$n" -le 50 ]] || die "multiplier must be 1..50"
        require_stopped
        mkdir -p "$DISABLED"
        for j in "$WORK_DIR"/plugins/grimac*.jar; do mv -n "$j" "$DISABLED/" && log "disabled $(basename "$j")"; done
        set_limits "$n"
        set_tick_rate "$n"
        echo "$n" > "$MARK"
        log "speed mode ON (${n}x): start.sh sets the server to $(( n * 20 )) TPS; packet limiters scaled for ${n}x; no Grim"
        ;;
    off)
        require_stopped
        for j in "$DISABLED"/grimac*.jar; do mv -n "$j" "$WORK_DIR/plugins/" && log "enabled $(basename "$j")"; done
        set_limits 1
        set_tick_rate 1.0
        rm -f "$MARK"
        log "speed mode OFF: Grim back, 20 TPS, 1x packet limits"
        ;;
    status)
        if [[ -f "$MARK" ]]; then log "speed mode ON ($(<"$MARK")x)"; else log "speed mode off"; fi
        grim=("$WORK_DIR"/plugins/grimac*.jar)
        log "Grim jar in plugins/: ${#grim[@]}"
        show_limits
        ;;
    -h|--help) sed -n '2,18p' "$0" ;;
    *) die "unknown command: $cmd (on [N] | off | status)" ;;
esac
