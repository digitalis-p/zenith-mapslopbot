#!/usr/bin/env python3
"""Regenerate tools/replay/data/registry-1.21.4.json from ZenithProxy's bundled game data.

ZenithProxy ships Minecraft registry data for its protocol version as Jackson Smile files in its jar
(mcdata/blocks.smile, items.smile, entities.smile, mapColorIdToColor.smile). The replay analyser needs
block state ids -> block names (and map colours), entity type ids and item ids for the SAME protocol
version the recording was written with: ZenithProxy 3.7.2 records 1.21.4 (protocol 769) packets, whatever
the server version is (ViaVersion on the server translates). This script decodes those Smile files with a
small stdlib-only Smile reader and writes a compact JSON table, which is committed so that the analyser
needs nothing at run time.

Usage: python3 -I gen_registry.py [ZENITH_JAR] [--out FILE]
  ZENITH_JAR  default: ZenithProxy-3.7.2+1.21.4.jar in the Gradle cache ($GRADLE_USER_HOME or ~/.gradle)
  --out FILE  default: data/registry-1.21.4.json next to this script
"""
import glob
import json
import os
import struct
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUT = os.path.join(HERE, "data", "registry-1.21.4.json")
ZENITH_VERSION = "3.7.2+1.21.4"
# Translation keys the analyser may meet in chat, death and kick messages.
LANG_PREFIXES = ("chat.", "commands.message.", "commands.kick.", "commands.teleport.", "death.", "disconnect.",
                 "entity.", "gameMode.", "multiplayer.")


class SmileError(Exception):
    pass


class Smile:
    """Minimal Jackson Smile decoder: everything Jackson writes for plain JSON trees."""

    def __init__(self, data):
        if data[:3] != b":)\n":
            raise SmileError("not a Smile document")
        flags = data[3]
        self.shared_names = bool(flags & 0x01)
        self.shared_values = bool(flags & 0x02)
        self.d = data
        self.i = 4
        self.names = []
        self.values = []

    def byte(self):
        b = self.d[self.i]
        self.i += 1
        return b

    def take(self, n):
        b = self.d[self.i:self.i + n]
        if len(b) != n:
            raise SmileError("truncated")
        self.i += n
        return b

    def vint(self):
        v = 0
        while True:
            b = self.byte()
            if b & 0x80:
                return (v << 6) | (b & 0x3F)
            v = (v << 7) | b

    @staticmethod
    def zigzag(n):
        return (n >> 1) ^ -(n & 1)

    def until_fc(self):
        j = self.d.index(0xFC, self.i)
        s = self.d[self.i:j].decode("utf-8")
        self.i = j + 1
        return s

    def _add(self, table, s):
        if len(table) >= 1024:
            table.clear()
        table.append(s)

    def value(self):
        b = self.byte()
        if 0x01 <= b <= 0x1F:
            return self.values[b - 1]
        if b == 0x20:
            return ""
        if b == 0x21:
            return None
        if b == 0x22:
            return False
        if b == 0x23:
            return True
        if b in (0x24, 0x25):
            return self.zigzag(self.vint())
        if b == 0x28:
            raw = 0
            for _ in range(5):
                raw = (raw << 7) | self.byte()
            return struct.unpack(">f", struct.pack(">I", raw & 0xFFFFFFFF))[0]
        if b == 0x29:
            raw = 0
            for _ in range(10):
                raw = (raw << 7) | self.byte()
            return struct.unpack(">d", struct.pack(">Q", raw & 0xFFFFFFFFFFFFFFFF))[0]
        if 0x40 <= b <= 0x7F or 0x80 <= b <= 0xBF:
            if b < 0x60:
                n = (b & 0x1F) + 1
            elif b < 0x80:
                n = (b & 0x1F) + 33
            elif b < 0xA0:
                n = (b & 0x1F) + 2
            else:
                n = (b & 0x1F) + 34
            s = self.take(n).decode("utf-8")
            if self.shared_values and len(s.encode("utf-8")) <= 64:
                self._add(self.values, s)
            return s
        if 0xC0 <= b <= 0xDF:
            return self.zigzag(b & 0x1F)
        if b in (0xE0, 0xE4):
            return self.until_fc()
        if 0xEC <= b <= 0xEF:
            return self.values[((b & 0x03) << 8) | self.byte()]
        if b == 0xE8:  # 7-bit encoded binary (item component blobs): skipped, not needed
            n = self.vint()
            self.i += n // 7 * 8 + (n % 7 + 1 if n % 7 else 0)
            return None
        if b == 0xFD:  # raw binary
            self.i += self.vint()
            return None
        if b == 0xF8:
            out = []
            while self.d[self.i] != 0xF9:
                out.append(self.value())
            self.i += 1
            return out
        if b == 0xFA:
            out = {}
            while True:
                k = self.key()
                if k is None:
                    return out
                out[k] = self.value()
        raise SmileError("unsupported Smile token 0x%02X at %d" % (b, self.i - 1))

    def key(self):
        b = self.byte()
        if b == 0xFB:
            return None
        if b == 0x20:
            return ""
        if 0x30 <= b <= 0x33:
            return self.names[((b & 0x03) << 8) | self.byte()]
        if b == 0x34:
            s = self.until_fc()
        elif 0x40 <= b <= 0x7F:
            return self.names[b & 0x3F]
        elif 0x80 <= b <= 0xBF:
            s = self.take((b & 0x3F) + 1).decode("utf-8")
        elif 0xC0 <= b <= 0xF7:
            s = self.take((b & 0x3F) + 2).decode("utf-8")
        else:
            raise SmileError("unsupported Smile key token 0x%02X at %d" % (b, self.i - 1))
        if self.shared_names and len(s.encode("utf-8")) <= 64:
            self._add(self.names, s)
        return s


def decode_smile(data):
    return Smile(data).value()


def find_jar():
    homes = [os.environ.get("GRADLE_USER_HOME"), os.path.expanduser("~/.gradle"),
             os.path.expanduser("~/.local/share/gradle")]
    for h in homes:
        if not h:
            continue
        hits = glob.glob(os.path.join(h, "caches", "modules-2", "files-2.1", "com.zenith", "ZenithProxy",
                                      ZENITH_VERSION, "*", "ZenithProxy-%s.jar" % ZENITH_VERSION))
        if hits:
            return sorted(hits)[0]
    return None


def by_id(entries, what):
    out = [None] * (max(e["id"] for e in entries) + 1)
    for e in entries:
        out[e["id"]] = e
    missing = [i for i, e in enumerate(out) if e is None]
    if missing:
        raise SystemExit("%s: ids missing: %s" % (what, missing[:10]))
    return out


def build(jar):
    with zipfile.ZipFile(jar) as z:
        blocks = decode_smile(z.read("mcdata/blocks.smile"))
        items = decode_smile(z.read("mcdata/items.smile"))
        entities = decode_smile(z.read("mcdata/entities.smile"))
        colors = decode_smile(z.read("mcdata/mapColorIdToColor.smile"))
        lang = decode_smile(z.read("mcdata/language.smile"))
    blocks = by_id(blocks, "blocks")
    prev = -1
    for b in blocks:
        if b["minStateId"] != prev + 1 or b["maxStateId"] < b["minStateId"]:
            raise SystemExit("block state ranges are not contiguous at %s" % b["name"])
        prev = b["maxStateId"]
    return {
        "_about": "Minecraft 1.21.4 (protocol 769) registry tables for tools/replay/analyze_replay.py. "
                  "Generated by tools/replay/gen_registry.py from ZenithProxy %s's bundled mcdata/*.smile. "
                  "blocks[id] = [name, minStateId, maxStateId, mapColorId]; items[id] and entities[id] = name; "
                  "mapColors[mapColorId] = 0xRRGGBB base colour; lang = en_us chat/death/kick strings "
                  "({0}-style placeholders)." % ZENITH_VERSION,
        "minecraft": "1.21.4",
        "protocol": 769,
        "blocks": [[b["name"], b["minStateId"], b["maxStateId"], b["mapColorId"]] for b in blocks],
        "items": [e["name"] for e in by_id(items, "items")],
        "entities": [e["name"] for e in by_id(entities, "entities")],
        "mapColors": [colors[str(i)] & 0xFFFFFF for i in range(len(colors))],
        "lang": {k: v for k, v in sorted(lang.items()) if k.startswith(LANG_PREFIXES)},
    }


def main(argv):
    args = argv[1:]
    out = DEFAULT_OUT
    if "--out" in args:
        k = args.index("--out")
        out = args[k + 1]
        del args[k:k + 2]
    if args and args[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    jar = args[0] if args else find_jar()
    if not jar or not os.path.exists(jar):
        print("ZenithProxy jar not found; pass its path", file=sys.stderr)
        return 2
    table = build(jar)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w") as f:
        f.write("{\n")
        keys = list(table)
        for n, k in enumerate(keys):
            v = table[k]
            sep = "," if n < len(keys) - 1 else ""
            if isinstance(v, dict):
                f.write(" %s: {\n" % json.dumps(k))
                f.write(",\n".join("  %s: %s" % (json.dumps(a), json.dumps(b)) for a, b in v.items()))
                f.write("\n }%s\n" % sep)
            elif isinstance(v, list):
                f.write(" %s: [\n" % json.dumps(k))
                f.write(",\n".join("  " + json.dumps(e, separators=(",", ":")) for e in v))
                f.write("\n ]%s\n" % sep)
            else:
                f.write(" %s: %s%s\n" % (json.dumps(k), json.dumps(v), sep))
        f.write("}\n")
    print("wrote %s: %d blocks (%d states), %d items, %d entity types, %d map colours, %d strings" % (
        out, len(table["blocks"]), table["blocks"][-1][2] + 1, len(table["items"]), len(table["entities"]),
        len(table["mapColors"]), len(table["lang"])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
