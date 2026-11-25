#!/usr/bin/env python3
"""Helpers for the ZenithProxy dev run (python3 stdlib only; run with -I).

  render RUN_DIR NAME [--fresh]  write RUN_DIR/config.json + launch_config.json from the templates.
                                 Existing files are backed up (*.bak-<time>) and the template keys
                                 are merged over them; --fresh replaces them instead.
  site RUN_DIR FILE              write FILE's site object into RUN_DIR/plugins/config/mapart.json
                                 (backup first; other plugin settings are kept)
  check RUN_DIR                  exit 1 unless config.json only talks to the local test server
  pid RUN_DIR                    print the pid of the ZenithProxy JVM running in RUN_DIR
"""
import hashlib
import json
import os
import shutil
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
MAIN_CLASSES = (b"com.zenith.ProxyLaunchWrapper", b"com.zenith.Proxy")


def offline_uuid(name):
    """Same as Java UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8))."""
    h = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    h[6] = (h[6] & 0x0F) | 0x30
    h[8] = (h[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(h)))


def merge(dst, src):
    for k, v in src.items():
        if isinstance(v, dict) and isinstance(dst.get(k), dict):
            merge(dst[k], v)
        else:
            dst[k] = v
    return dst


def write_json(path, data, fresh):
    if os.path.exists(path):
        bak = "%s.bak-%s" % (path, time.strftime("%Y%m%d-%H%M%S"))
        shutil.copy2(path, bak)
        print("backed up %s -> %s" % (os.path.basename(path), os.path.basename(bak)))
        if not fresh:
            with open(path, encoding="utf-8") as f:
                data = merge(json.load(f), data)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    os.replace(tmp, path)
    print("wrote %s" % path)


def render(run_dir, name, fresh):
    if not (1 <= len(name) <= 16) or not all(c.isalnum() or c == "_" for c in name):
        raise SystemExit("invalid player name: %r" % name)
    os.makedirs(run_dir, exist_ok=True)
    with open(os.path.join(HERE, "config.template.json"), encoding="utf-8") as f:
        text = f.read()
    u = offline_uuid(name)
    cfg = json.loads(text.replace("@USERNAME@", name).replace("@OFFLINE_UUID@", u))
    # Port overrides for a second instance (testserver/lib.sh): the test server and ZenithProxy's own server.
    cfg["client"]["server"]["port"] = int(os.environ.get("TESTSERVER_PORT") or cfg["client"]["server"]["port"])
    cfg["server"]["bind"]["port"] = int(os.environ.get("TESTSERVER_ZENITH_PORT") or cfg["server"]["bind"]["port"])
    with open(os.path.join(HERE, "launch_config.json"), encoding="utf-8") as f:
        launch = json.load(f)
    write_json(os.path.join(run_dir, "config.json"), cfg, fresh)
    write_json(os.path.join(run_dir, "launch_config.json"), launch, fresh)
    print("bot account: offline '%s' uuid %s -> 127.0.0.1:%d (ZenithProxy's own server 127.0.0.1:%d)"
          % (name, u, cfg["client"]["server"]["port"], cfg["server"]["bind"]["port"]))


def install_site(run_dir, site_file):
    """Put a site config (MapArtConfig.site shape; '_' keys dropped) into plugins/config/mapart.json."""
    with open(site_file, encoding="utf-8") as f:
        site = json.load(f)
    site = site.get("site", site)
    site = {k: v for k, v in site.items() if not k.startswith("_")}
    path = os.path.join(run_dir, "plugins", "config", "mapart.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    cfg = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            cfg = json.load(f)
        bak = "%s.bak-%s" % (path, time.strftime("%Y%m%d-%H%M%S"))
        shutil.copy2(path, bak)
        print("backed up mapart.json -> %s" % os.path.basename(bak))
    cfg["site"] = site
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)
        f.write("\n")
    os.replace(tmp, path)
    print("wrote site (%d platforms) into %s" % (len(site.get("platforms", [])), path))


def check(run_dir):
    path = os.path.join(run_dir, "config.json")
    with open(path, encoding="utf-8") as f:
        c = json.load(f)
    problems = []

    def get(*keys, default=None):
        cur = c
        for k in keys:
            if not isinstance(cur, dict) or k not in cur:
                return default
            cur = cur[k]
        return cur

    addr = get("client", "server", "address", default="2b2t.org")
    if str(addr).lower() not in ("127.0.0.1", "localhost"):
        problems.append("client.server.address is %r (must be 127.0.0.1)" % addr)
    if get("authentication", "accountType", default="device_code") != "offline":
        problems.append("authentication.accountType is not 'offline'")
    if get("server", "queueStatusRefreshWhileNotOn2b2t", default=True) is not False:
        problems.append("server.queueStatusRefreshWhileNotOn2b2t must be false (else ZenithProxy pings 2b2t.org)")
    bind = get("server", "bind", "address", default="0.0.0.0")
    if get("server", "enabled", default=True) and bind not in ("127.0.0.1", "localhost"):
        problems.append("server.bind.address is %r (must be 127.0.0.1)" % bind)
    if get("discord", "enable", default=False):
        problems.append("discord.enable is true")
    if get("client", "viaversion", "enabled", default=False):
        problems.append("client.viaversion.enabled is true (the test server translates; see docs/TESTING.md)")
    if get("server", "ping", "lanBroadcast", default=True):
        problems.append("server.ping.lanBroadcast is true")
    # The bot must join this checkout's own test server, never another instance's (TESTSERVER_PORT).
    props = os.path.join(HERE, "..", "work", "server.properties")
    if os.path.exists(props):
        with open(props, encoding="utf-8") as f:
            sport = next((ln.split("=", 1)[1].strip() for ln in f if ln.startswith("server-port=")), None)
        port = get("client", "server", "port", default=25565)
        if sport is not None and str(port) != sport:
            problems.append("client.server.port is %s but this checkout's test server listens on %s "
                            "(testserver/work/server.properties); re-run install.sh with the same TESTSERVER_PORT"
                            % (port, sport))
    rate = get("client", "tickRate", default=1.0)
    speed_mark = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "work", "speed-mode")
    if rate != 1.0 and not os.path.exists(speed_mark):
        problems.append("client.tickRate is %s but speed mode is off (testserver/speed-mode.sh off resets it)" % rate)
    for p in problems:
        print("zenith config: " + p, file=sys.stderr)
    return 1 if problems else 0


def find_pid(run_dir):
    run_dir = os.path.realpath(run_dir)
    for d in os.listdir("/proc"):
        if not d.isdigit():
            continue
        try:
            with open("/proc/%s/comm" % d, "rb") as f:
                if f.read().strip() != b"java":
                    continue
            with open("/proc/%s/cmdline" % d, "rb") as f:
                args = f.read().split(b"\0")
            if not any(a in MAIN_CLASSES for a in args):
                continue
            if os.path.realpath("/proc/%s/cwd" % d) != run_dir:
                continue
        except OSError:
            continue
        print(d)
        return 0
    return 1


def main(argv):
    if len(argv) >= 4 and argv[1] == "render":
        render(argv[2], argv[3], "--fresh" in argv[4:])
        return 0
    if len(argv) == 4 and argv[1] == "site":
        install_site(argv[2], argv[3])
        return 0
    if len(argv) == 3 and argv[1] == "check":
        return check(argv[2])
    if len(argv) == 3 and argv[1] == "pid":
        return find_pid(argv[2])
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
