#!/usr/bin/env python3
"""Tests for tools/replay/analyze_replay.py. Python stdlib only.

Run: python3 -I tools/replay/tests/test_analyze_replay.py   (or: python3 -I -m unittest discover -s tools/replay/tests)

The fixture is synthesized here: a tiny .mcpr built from known 1.21.4 packets (login, registry, chunk, block
updates, setbacks, health, chat, a chest, another player, a kick), a site config and a flat plan .nbt. Coordinates
are made up (platform minX 64, minZ 192, art layer y70); nothing here comes from a real recording.
"""
import contextlib
import gzip
import importlib.util
import io
import json
import os
import struct
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(os.path.dirname(HERE), "analyze_replay.py")
GEN = os.path.join(os.path.dirname(HERE), "gen_registry.py")


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


ar = _load("analyze_replay", TOOL)
gen = _load("gen_registry", GEN)
REG = ar.Registry()

# ---------------------------------------------------------------------------------------------------------------
# Encoders


def varint(v):
    v &= 0xFFFFFFFF
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def varlong(v):
    v &= 0xFFFFFFFFFFFFFFFF
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def string(s):
    b = s.encode("utf-8")
    return varint(len(b)) + b


def position(x, y, z):
    v = ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF)
    return struct.pack(">q", v - (1 << 64) if v >= 1 << 63 else v)


def nbt_str(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def nbt_compound(items):
    """items: list of (name, tag_type, payload bytes)."""
    return b"".join(bytes([t]) + nbt_str(n) + p for n, t, p in items) + b"\x00"


def text_component(s):
    return b"\x08" + nbt_str(s)


def translate_component(key):
    return b"\x0a" + nbt_compound([("translate", 8, nbt_str(key))])


def f64s(*v):
    return struct.pack(">%dd" % len(v), *v)


def f32s(*v):
    return struct.pack(">%df" % len(v), *v)


def uuid_bytes(n):
    return n.to_bytes(16, "big")


def state(name):
    return REG.by_name[name][1]


def game(name, body):
    return varint(ar.GAME_IDS[name]) + body


# ---------------------------------------------------------------------------------------------------------------
# Fixture

MIN_X, MIN_Z, ART_Y = 64, 192, 70
SELF = 42
CHEST = (100, 71, 250)
CLEAR_BUTTON = (100, 75, 250)
FORBIDDEN = (101, 75, 250)


def chunk_packet(cx, cz, carpet_at):
    """level_chunk_with_light with air sections up to the art layer's section (y64..79 -> index 8); one
    white carpet at local (lx, lz) on the art layer."""
    data = bytearray()
    for _ in range(8):
        data += struct.pack(">h", 0) + b"\x00" + varint(0) + varint(0) + b"\x00" + varint(0) + varint(0)
    longs = [0] * 256
    lx, lz = carpet_at
    i = ((ART_Y - 64) * 16 + lz) * 16 + lx
    longs[i // 16] |= 1 << ((i % 16) * 4)
    data += struct.pack(">h", 1) + b"\x04" + varint(2) + varint(0) + varint(state("white_carpet"))
    data += varint(256) + struct.pack(">256q", *[v - (1 << 64) if v >= 1 << 63 else v for v in longs])
    data += b"\x00" + varint(0) + varint(0)
    body = struct.pack(">ii", cx, cz) + b"\x0a\x00" + varint(len(data)) + bytes(data) + varint(0)
    return game("level_chunk_with_light", body)


def section_update(sx, sy, sz, entries):
    v = ((sx & 0x3FFFFF) << 42) | ((sz & 0x3FFFFF) << 20) | (sy & 0xFFFFF)
    body = struct.pack(">q", v - (1 << 64) if v >= 1 << 63 else v) + varint(len(entries))
    for (x, y, z, st) in entries:
        body += varlong((st << 12) | ((x - sx * 16) << 8) | ((z - sz * 16) << 4) | (y - sy * 16))
    return game("section_blocks_update", body)


def player_position(tid, x, y, z, flags=0):
    return game("player_position", varint(tid) + f64s(x, y, z, 0, 0, 0) + f32s(0, 0) + struct.pack(">i", flags))


def records():
    r = []
    add = lambda t, p: r.append((t, p))
    add(0, varint(0x02) + uuid_bytes(1) + string("TestBot") + varint(0))
    dim = nbt_compound([("min_y", 3, struct.pack(">i", -64)), ("height", 3, struct.pack(">i", 384))])
    add(1, varint(0x07) + string("minecraft:dimension_type") + varint(1) + string("minecraft:overworld") + b"\x01"
        + b"\x0a" + dim)
    add(1, varint(0x03))
    spawn = varint(0) + string("minecraft:overworld") + struct.pack(">q", 0) + b"\x00\xff\x00\x00\x00" + varint(0) \
        + varint(63)
    add(2, game("login", struct.pack(">i", SELF) + b"\x00" + varint(1) + string("minecraft:overworld") + varint(20)
                + varint(10) + varint(8) + b"\x00\x00\x00" + spawn + b"\x00"))
    add(3, player_position(1, 130.5, 71, 260.5))
    add(4, chunk_packet(8, 16, (1, 1)))                                  # initial carpet at (129, 70, 257)
    add(5, game("ticking_state", f32s(20) + b"\x00"))
    add(1000, game("block_update", position(130, ART_Y, 260) + varint(state("white_carpet"))))
    add(1001, game("block_update", position(130, ART_Y - 1, 260) + varint(state("moss_block"))))  # clicked face
    add(1100, section_update(8, 4, 16, [(131, ART_Y, 260, state("red_carpet")), (132, ART_Y, 260, state("black_carpet")),
                                        (134, ART_Y, 260, state("stone"))]))
    add(1200, game("teleport_entity", varint(SELF) + f64s(131.5, 71, 260.5, 0, 0, 0) + f32s(0, 0)
                   + struct.pack(">i", 0) + b"\x01"))
    for k in range(6):
        add(2000 + 80 * k, player_position(2 + k, 131.5, 71, 260.5))
    add(2500, player_position(8, 141.5, 71, 260.5))
    add(3000, game("set_health", f32s(20) + varint(20) + f32s(5)))
    add(3100, game("set_health", f32s(7.5) + varint(5) + f32s(0)))
    add(3200, game("system_chat", text_component("Grim: you were flagged for Timer") + b"\x00"))
    add(3300, game("player_chat", uuid_bytes(9) + varint(0) + b"\x00" + string("hello") + struct.pack(">qq", 0, 0)
                   + varint(0) + b"\x00" + varint(0) + varint(1) + text_component("Alice") + b"\x00"))
    add(3400, game("open_screen", varint(3) + varint(5) + translate_component("container.chestDouble")))
    add(3405, game("block_event", position(*CHEST) + b"\x01\x01" + varint(REG.blocks.index(REG.by_name["chest"]))))
    add(3450, game("container_set_content", varint(3) + varint(1) + varint(0) + varint(0)))
    # the recorder's own-equipment records on two container clicks; one of another entity does not count
    add(3500, game("set_equipment", varint(SELF) + b"\x00" + varint(0)))
    add(3600, game("set_equipment", varint(SELF) + b"\x00" + varint(0)))
    add(3650, game("set_equipment", varint(SELF + 7) + b"\x00" + varint(0)))
    add(3800, game("block_event", position(*CHEST) + b"\x01\x00" + varint(REG.blocks.index(REG.by_name["chest"]))))
    add(4000, game("player_info_update", bytes([0x01 | 0x08]) + varint(1) + uuid_bytes(7) + string("Stranger")
                   + varint(0) + b"\x01"))
    add(4010, game("add_entity", varint(77) + uuid_bytes(7) + varint(REG.player_type) + f64s(135.5, 71, 260.5)
                   + b"\x00\x00\x00" + varint(0) + struct.pack(">hhh", 0, 0, 0)))
    add(4100, game("move_entity_pos", varint(77) + struct.pack(">hhh", -2 * 4096, 0, 0) + b"\x01"))
    add(4200, game("remove_entities", varint(1) + varint(77)))
    add(5000, game("block_update", position(130, ART_Y, 260) + varint(state("air"))))           # the bot (NUKE)
    add(5050, game("block_update", position(*CLEAR_BUTTON) + varint(state("stone_button"))))
    add(5060, game("block_update", position(*FORBIDDEN) + varint(state("stone_button"))))
    add(5100, game("block_update", position(131, ART_Y, 260) + varint(state("slime_block"))))   # machine arrives
    add(5150, game("block_update", position(131, ART_Y, 260) + varint(state("air"))))
    add(5200, section_update(8, 4, 16, [(132, ART_Y, 260, state("air")), (134, ART_Y, 260, state("air"))]))
    add(5300, game("block_update", position(129, ART_Y, 257) + varint(state("air"))))
    add(6000, game("disconnect", translate_component("multiplayer.disconnect.kicked")))
    return r


def tmcpr(recs):
    return b"".join(struct.pack(">ii", t, len(p)) + p for t, p in recs)


META = {"singleplayer": False, "serverName": "127.0.0.1:25566", "duration": 6000, "date": 1700000000000,
        "mcversion": "1.21.4", "fileFormat": "MCPR", "fileFormatVersion": 14, "protocol": 769,
        "generator": "ZenithProxy 3.7.2+java.1.21.4", "selfId": -1, "players": []}


def write_mcpr(path, recs, meta=True):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("recording.tmcpr", tmcpr(recs))
        if meta:
            z.writestr("metaData.json", json.dumps(META))


def write_site(path):
    site = {"dimension": "minecraft:overworld",
            "_doNotPress": [{"x": FORBIDDEN[0], "y": FORBIDDEN[1], "z": FORBIDDEN[2], "why": "test"}],
            "platforms": [{"id": "off", "kind": "SOUL_SAND", "enabled": False, "minX": 192, "minZ": 192, "artY": 80},
                          {"id": "test", "kind": "MOSS", "enabled": True, "minX": MIN_X, "minZ": MIN_Z, "artY": ART_Y,
                           "clear": [{"pos": dict(zip("xyz", CLEAR_BUTTON)), "mode": "BUTTON"}]}]}
    with open(path, "w") as f:
        json.dump({"enabled": True, "discord": {"token": "not-read"}, "build": {"laneHalfWidth": 2}, "site": site}, f)


def write_plan(path):
    """Flat 128x1x129 structure: row z=0 is the noobline, pixel (px, pz) is column (px, pz+1)."""
    pal = ["minecraft:air", "minecraft:white_carpet", "minecraft:red_carpet", "minecraft:black_carpet"]
    blocks = [((65, 0, 66), 1), ((66, 0, 69), 1), ((67, 0, 69), 2), ((68, 0, 69), 3), ((72, 0, 69), 1)]
    pal_tags = b"".join(nbt_compound([("Name", 8, nbt_str(n))]) for n in pal)
    blk_tags = b"".join(nbt_compound([("pos", 9, b"\x03" + struct.pack(">iiii", 3, *p)),
                                      ("state", 3, struct.pack(">i", s))]) for p, s in blocks)
    root = nbt_compound([
        ("size", 9, b"\x03" + struct.pack(">iiii", 3, 128, 1, 129)),
        ("palette", 9, b"\x0a" + struct.pack(">i", len(pal)) + pal_tags),
        ("blocks", 9, b"\x0a" + struct.pack(">i", len(blocks)) + blk_tags),
        ("DataVersion", 3, struct.pack(">i", 4189))])
    with open(path, "wb") as f:
        f.write(gzip.compress(b"\x0a" + nbt_str("") + root))


# ---------------------------------------------------------------------------------------------------------------


class LaneWidth(unittest.TestCase):
    def test_follows_the_placement_mode(self):
        # PlaceMode.laneHalfWidth: AIRPLACE auto = 4 at airPlaceRange 5 (9-wide lanes), 3 at 4.5
        self.assertEqual(ar.lane_half_width_of({"placeMode": "AIRPLACE", "airPlaceLaneHalfWidth": -1,
                                                "airPlaceRange": 5.0, "laneHalfWidth": 2}), 4)
        self.assertEqual(ar.lane_half_width_of({"placeMode": "AIRPLACE", "airPlaceRange": 4.5}), 3)
        self.assertEqual(ar.lane_half_width_of({"placeMode": "airplace", "airPlaceLaneHalfWidth": 3}), 3)
        self.assertEqual(ar.lane_half_width_of({"placeMode": "LOOK", "laneHalfWidth": 2,
                                                "airPlaceLaneHalfWidth": -1}), 2)
        # a config from before placement modes: LOOK
        self.assertEqual(ar.lane_half_width_of({"laneHalfWidth": 2}), 2)
        self.assertIsNone(ar.lane_half_width_of({}))


class ProtocolTables(unittest.TestCase):
    def test_game_ids(self):
        self.assertEqual(len(ar.GAME_NAMES), 131)
        expect = {"add_entity": 0x01, "block_changed_ack": 0x05, "block_event": 0x08, "block_update": 0x09,
                  "container_close": 0x12, "container_set_content": 0x13, "disconnect": 0x1D,
                  "level_chunk_with_light": 0x28, "login": 0x2C, "open_screen": 0x35, "player_chat": 0x3B,
                  "player_info_update": 0x40, "player_position": 0x42, "respawn": 0x4C,
                  "section_blocks_update": 0x4E, "set_health": 0x62, "start_configuration": 0x70,
                  "system_chat": 0x73, "teleport_entity": 0x77, "server_links": 0x82}
        for name, pid in expect.items():
            self.assertEqual(ar.GAME_IDS[name], pid, name)

    def test_registry(self):
        self.assertEqual(REG.entities[147], "player")
        self.assertEqual(REG.block_name(0), "air")
        prev = -1
        for name, lo, hi, _c in REG.blocks:
            self.assertEqual(lo, prev + 1, name)
            prev = hi
        self.assertEqual(REG.block_name(state("white_carpet")), "white_carpet")
        self.assertEqual(REG.block_color(state("white_carpet")), REG.by_name["white_wool"][3])

    def test_position_roundtrip(self):
        for p in [(0, 0, 0), (-1, -64, -1), (33554431, 2047, -33554432), (130, 70, 260)]:
            self.assertEqual(ar.Buf(position(*p)).position(), p)

    def test_varint(self):
        for v in [0, 1, 127, 128, 25565, 2147483647, -1, -2147483648]:
            self.assertEqual(ar.Buf(varint(v)).varint(), v)

    def test_smile(self):
        doc = b":)\n\x01" + b"\xf8\xfa\x80a\xc2\xfb\xfa\x40\xc4\xfb\xf9"
        self.assertEqual(gen.decode_smile(doc), [{"a": 1}, {"a": 2}])

    def test_translate(self):
        lang = {"chat.type.text": "<{0}> {1}"}
        self.assertEqual(ar.translate(lang, "chat.type.text", ["A", "hi"]), "<A> hi")
        self.assertEqual(ar.translate({}, "x.y", ["1"]), "x.y(1)")


class SyntheticRecording(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        d = cls.tmp.name
        cls.mcpr = os.path.join(d, "2026-01-02_03-04-05_TestBot.mcpr")
        cls.site = os.path.join(d, "mapart.json")
        cls.plan = os.path.join(d, "plan.nbt")
        write_mcpr(cls.mcpr, records())
        write_site(cls.site)
        write_plan(cls.plan)
        cls.an, cls.rec, _ = ar.analyse(cls.mcpr, site=cls.site, plan_path=cls.plan)
        cls.out = os.path.join(d, "out")
        cls.res = ar.build_report(cls.an, cls.rec, cls.out, ar.Coords(), "test")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_session(self):
        an = self.an
        self.assertEqual((an.self_name, an.self_id, an.dimension), ("TestBot", SELF, "minecraft:overworld"))
        self.assertEqual(an.n_records, len(records()))
        self.assertFalse(an.errors, an.error_samples)
        self.assertFalse(an.state_resyncs)
        self.assertEqual(an.layer.platform_id, "test")
        self.assertEqual(an.tick_rates, [(5, 20.0, False)])

    def test_art_layer(self):
        an = self.an
        kinds = [(e[0], e[1] % ar.TILE, e[1] // ar.TILE, e[4]) for e in an.art_events]
        self.assertIn((4, 65, 65, "initial"), kinds)
        places = [k for k in kinds if k[3] == "place"]
        self.assertEqual([(t, px) for t, px, _pz, _k in places], [(1000, 66), (1100, 67), (1100, 68), (1100, 70)])
        self.assertEqual(self.res["places"], 4)
        self.assertEqual(self.res["removes"], 5)
        cmp = self.res["cmp_peak"]
        self.assertEqual((cmp["ok"], cmp["unplanned"], cmp["missing"], cmp["wrong"]), (4, 1, 1, 0))
        self.assertEqual(an.art_count, 0)
        c = self.res["clear"]
        self.assertEqual(([e[0] for e in c["nuked"]], c["machines"], c["end"], c["press"]), ([5000], 5100, 5300, 5050))
        self.assertEqual({k: v["n"] for k, v in self.res["lanes"].items()}, {13: 3, 14: 1})
        # the clicked floor block re-sent with the placement is not a block "above the art layer"
        self.assertFalse(any("above the art layer" in r for r in self.res["red_flags"]))

    def test_setbacks(self):
        sb = self.an.setbacks
        self.assertEqual([s["kind"] for s in sb], ["spawn"] + ["setback"] * 7)
        self.assertAlmostEqual(sb[-1]["distance"], 10.0)
        self.assertEqual(sb[1]["distance"], 0.0)   # from the bot's last reported move (teleport_entity at t=1200)
        self.assertIsNone(sb[0]["distance"])
        bursts = self.res["bursts"]
        self.assertEqual(len(bursts), 1)
        self.assertEqual((bursts[0]["first_t"], bursts[0]["t"]), (2000, 2320))

    def test_health_chat_disconnect(self):
        an = self.an
        self.assertEqual([(h["health"], h["food"]) for h in an.health], [(20.0, 20), (7.5, 5)])
        texts = {(c["kind"], c["sender"], c["text"], c["flagged"]) for c in an.chat}
        self.assertIn(("system", "", "Grim: you were flagged for Timer", True), texts)
        self.assertIn(("player", "Alice", "hello", False), texts)
        self.assertIn(("disconnect", "", "Kicked by an operator", True), texts)
        red = "\n".join(self.res["red_flags"])
        for needle in ("Disconnect/kick", "Health fell to 7.5", "Food fell to 5", "Player Stranger",
                       "DO NOT PRESS: test changed", "7 server setbacks", "1 art pixel(s) hold a block where the plan"):
            self.assertIn(needle, red)

    def test_containers_players(self):
        (c,) = self.an.containers
        self.assertEqual((c["t_open"], c["menu"], c["pos"], c["t_close"], c["updates"], c["client_events"]),
                         (3400, "generic_9x6", CHEST, 3800, 1, 2))
        self.assertIn("block_event", c["close_how"])
        with open(os.path.join(self.out, "containers.csv")) as f:
            rows = f.read().splitlines()
        self.assertEqual(rows[0].split(",")[-1], "client_events")
        self.assertEqual(rows[1].split(",")[-1], "2")
        (p,) = self.an.players.values()
        self.assertEqual(self.an.tab_names[p["uuid"]], "Stranger")
        self.assertAlmostEqual(p["min_dist"], 6.0)

    def test_outputs(self):
        names = sorted(os.listdir(self.out))
        for n in ("report.md", "placements.csv", "clicks.csv", "setbacks.csv", "chat.csv", "health.csv",
                  "containers.csv", "players.csv", "rate_10s.csv", "lanes.csv", "positions.csv", "block_changes.csv",
                  "art-peak.png", "art-final.png", "art-diff.png"):
            self.assertIn(n, names)
        with open(os.path.join(self.out, "art-peak.png"), "rb") as f:
            self.assertEqual(f.read(8), b"\x89PNG\r\n\x1a\n")
        with open(os.path.join(self.out, "placements.csv")) as f:
            rows = f.read().splitlines()
        self.assertEqual(rows[0].split(",")[:9], ["t_ms", "clock", "x", "y", "z", "px", "pz", "lane", "kind"])
        self.assertEqual(rows[1].split(",")[2:9], ["130", "70", "260", "66", "68", "13", "place"])
        with open(os.path.join(self.out, "report.md")) as f:
            md = f.read()
        self.assertIn("| Placed | 4 blocks on the art layer", md)

    def test_relative_and_cli(self):
        out = os.path.join(self.tmp.name, "rel")
        with contextlib.redirect_stdout(io.StringIO()) as so:
            ar.main([self.mcpr, "--site", self.site, "--plan", self.plan, "--out", out, "--relative", "--no-png"])
        self.assertIn("placed 4, removed 5, setbacks 7 (bursts 1)", so.getvalue())
        with open(os.path.join(out, "placements.csv")) as f:
            rows = f.read().splitlines()
        # offset = (minX + 64, minZ + 64) = (128, 256)
        self.assertEqual(rows[1].split(",")[2:5], ["2", "70", "4"])
        self.assertFalse(os.path.exists(os.path.join(out, "art-peak.png")))

    def test_auto_detect(self):
        an, _rec, _ = ar.analyse(self.mcpr)
        L = an.layer
        self.assertEqual((L.min_x, L.min_z, L.art_y), (MIN_X, MIN_Z, ART_Y))


class SessionChanges(unittest.TestCase):
    def run_records(self, extra):
        base = records()[:6]                                            # login .. spawn position, chunk
        an = ar.Analyzer(REG, ar.ArtLayer(MIN_X, MIN_Z, ART_Y))
        an.run(base + extra)
        return an

    def login(self, t):
        spawn = varint(0) + string("minecraft:overworld") + struct.pack(">q", 0) + b"\x00\xff\x00\x00\x00" \
            + varint(0) + varint(63)
        return (t, game("login", struct.pack(">i", SELF + 1) + b"\x00" + varint(1) + string("minecraft:overworld")
                        + varint(20) + varint(10) + varint(8) + b"\x00\x00\x00" + spawn + b"\x00"))

    def test_reconfiguration(self):
        an = self.run_records([(100, game("start_configuration", b"")),
                               (101, varint(0x0E) + varint(1) + string("minecraft") + string("core") + string("1.21.4")),
                               (102, varint(0x03)), self.login(103)])
        self.assertEqual((len(an.logins), an.self_id, an.state_resyncs, dict(an.errors)), (2, SELF + 1, [], {}))

    def test_reconfiguration_not_recorded(self):
        an = self.run_records([(100, game("start_configuration", b"")), self.login(103),
                               (104, game("block_update", position(130, ART_Y, 260) + varint(state("white_carpet"))))])
        self.assertEqual((len(an.logins), len(an.state_resyncs), an.state), (2, 1, "GAME"))
        self.assertEqual([e[4] for e in an.art_events if e[0] == 104], ["place"])

    def test_death_and_respawn(self):
        spawn = varint(0) + string("minecraft:overworld") + struct.pack(">q", 0) + b"\x00\xff\x00\x00\x00" \
            + varint(0) + varint(63)
        an = self.run_records([(100, game("set_health", f32s(0) + varint(10) + f32s(0))),
                               (101, game("player_combat_kill", varint(SELF) + text_component("TestBot fell"))),
                               (200, game("respawn", spawn + b"\x00")),
                               (201, player_position(9, 1.5, 70, 2.5))])
        self.assertEqual([(d["t"], d["message"]) for d in an.deaths], [(100, "TestBot fell")])
        self.assertEqual(len(an.respawns), 1)
        self.assertEqual(an.setbacks[-1]["kind"], "spawn")


def swing():
    return game("animate", varint(SELF) + b"\x00")


def ack(seq):
    return game("block_changed_ack", varint(seq))


def hotbar_slot(slot, count, state_id=1):
    item = varint(count) + (varint(REG.items.index("white_carpet") if "white_carpet" in REG.items else 1)
                            + varint(0) + varint(0) if count > 0 else b"")
    return game("container_set_slot", varint(0) + varint(state_id) + struct.pack(">h", 36 + slot) + item)


def answer(x, z, block):
    """The server's answer to a click on the floor under (x, ART_Y, z): the clicked block, then the cell above."""
    return [game("block_update", position(x, ART_Y - 1, z) + varint(state("moss_block"))),
            game("block_update", position(x, ART_Y, z) + varint(state(block)))]


def click_records():
    """Two placing runs and a run of chest clicks between them (sequences 10..25)."""
    r = list(records()[:6])                       # login .. chunk (white carpet at 129, ART_Y, 257)
    add = lambda t, *ps: r.extend((t, p) for p in ps)
    add(900, ack(9))                              # an earlier action: the first run starts at 10
    add(950, hotbar_slot(1, 2))
    for k in range(6):                            # run 1: seqs 10..15
        add(1000 + 50 * k, swing())
    add(1140, *answer(130, 260, "white_carpet"))
    add(1141, ack(10))
    add(1230, *answer(131, 260, "white_carpet"), *answer(132, 260, "white_carpet"))   # one server tick, one ack
    add(1231, hotbar_slot(1, 0))                  # the stack is used up: 13 and 14 meet an empty hand
    add(1232, ack(12))
    add(1300, *answer(133, 260, "air"))
    add(1301, ack(13))
    add(1350, *answer(134, 260, "air"))
    add(1351, ack(14))
    add(1400, ack(15))                            # 15: acked, no answer
    for k in range(5):                            # chest opens: swings with sequences 16..20, no art answers
        add(5000 + 500 * k, swing())
        add(5150 + 500 * k, game("block_update", position(*CHEST) + varint(state("chest"))), ack(16 + k))
    for k in range(5):                            # run 2: seqs 21..25
        add(11000 + 100 * k, swing())
    add(11150, *answer(129, 257, "white_carpet"), ack(21))       # already there: unchanged
    add(11250, *answer(135, 261, "black_carpet"), ack(22))
    add(11350, *answer(136, 261, "air"), ack(23))                # rejected, no stack end near
    add(13400, *answer(137, 261, "black_carpet"), ack(24))       # 2.1 s: late
    add(13500, game("set_time", struct.pack(">qq", 0, 0) + b"\x01"))  # 25 is never acked
    return r


class ClicksAndAcks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        d = cls.tmp.name
        cls.mcpr = os.path.join(d, "2026-01-02_03-04-05_TestBot.mcpr")
        cls.site = os.path.join(d, "mapart.json")
        write_mcpr(cls.mcpr, click_records())
        write_site(cls.site)
        cls.an, cls.rec, _ = ar.analyse(cls.mcpr, site=cls.site)
        cls.ca = ar.click_analysis(cls.an)
        cls.out = os.path.join(d, "out")
        cls.res = ar.build_report(cls.an, cls.rec, cls.out, ar.Coords(), "test", make_png=False)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_runs_and_sequences(self):
        ca = self.ca
        self.assertFalse(self.an.errors, self.an.error_samples)
        self.assertEqual(ca["swings"], 16)
        self.assertEqual(ca["other_runs"], 1, "the chest clicks are not a placing run")
        self.assertEqual([(i["run"], i["clicks"], i["seq0"]) for i in ca["runs"]], [(1, 6, 10), (2, 5, 21)])
        self.assertEqual([c["seq"] for c in ca["clicks"]], list(range(10, 16)) + list(range(21, 26)))

    def test_outcomes_and_round_trips(self):
        got = [(c["seq"], c["outcome"], c["stack_out"]) for c in self.ca["clicks"]]
        self.assertEqual(got, [(10, "placed", False), (11, "placed", False), (12, "placed", False),
                               (13, "rejected", True), (14, "rejected", True), (15, "acked, no answer", False),
                               (21, "unchanged", False), (22, "placed", False), (23, "rejected", False),
                               (24, "placed", False), (25, "no ack", False)])
        self.assertEqual(self.ca["rtt"], [141, 182, 132, 151, 151, 150, 150, 150, 150, 2100])
        self.assertEqual(self.ca["stack_out"], 2)
        lanes = {k: (d["clicks"], d["placed"], d["rejected"], d["stack_out"]) for k, d in self.ca["by_lane"].items()}
        self.assertEqual(lanes, {13: (5, 3, 1, 1), 14: (4, 2, 2, 1)})

    def test_report_and_csv(self):
        with open(os.path.join(self.out, "report.md")) as f:
            md = f.read()
        self.assertIn("## Clicks & acks", md)
        self.assertIn("| Outcomes | 5 placed, 3 rejected (2 at a stack end), 1 unchanged, 1 acked without an answer, "
                      "1 not acked |", md)
        self.assertIn("| Ack round trip | p50 150, p95 2100, p99 2100, max 2100 ms (10 acked clicks) |", md)
        self.assertIn("| Clicks | 11 in 2 run(s); 3 rejected (2 at a stack end)", md)
        red = "\n".join(self.res["red_flags"])
        for needle in ("1 click(s) rejected by the server, not at a stack end", "1 click(s) acked after more than 2 s",
                       "1 click(s) never acked"):
            self.assertIn(needle, red)
        with open(os.path.join(self.out, "clicks.csv")) as f:
            rows = f.read().splitlines()
        self.assertEqual(rows[0], "t_ms,clock,run,seq,ack_t_ms,ack_rtt_ms,answer_t_ms,answer_ms,x,y,z,px,pz,lane,"
                                  "outcome,block,stack_out,answer_kind")
        self.assertEqual(len(rows), 12)
        self.assertEqual(rows[4].split(",")[2:], ["1", "13", "1301", "151", "1300", "150", "133", "70", "260", "69",
                                                  "68", "13", "rejected", "air", "1", "look"])
        self.assertIn("| Answers | 9 LOOK (floor + cell), 0 AIRPLACE (cell + cell above) |", md)

    def test_relative_coordinates(self):
        out = os.path.join(self.tmp.name, "rel")
        ar.build_report(self.an, self.rec, out, ar.Coords(self.an.layer.offset), "test", make_png=False)
        with open(os.path.join(out, "clicks.csv")) as f:
            row = f.read().splitlines()[1].split(",")
        self.assertEqual(row[8:11], ["2", "70", "4"])      # (130, 260) - (128, 256)

    def test_answer_pairs_in_either_order(self):
        L = self.an.layer
        ev = [("bu", 10, 130, ART_Y, 260, state("white_carpet"), None), ("bu", 11, 130, ART_Y - 1, 260, 1, None),
              ("bu", 20, 131, ART_Y - 1, 260, 1, None), ("ack", 21, 5), ("bu", 22, 131, ART_Y, 260, 0, None),
              ("bu", 30, 132, ART_Y - 1, 260, 1, None), ("bu", 40, 132, ART_Y, 260, 0, None)]
        got = {i: (a["x"], a["t"]) for i, a in ar.click_answers(ev, L.art_y).items()}
        self.assertEqual(got, {1: (130, 11)}, "an ack between, or more than 5 ms apart: not one answer")

    def test_no_swings_no_runs(self):
        plain = ar.Analyzer(REG, ar.ArtLayer(MIN_X, MIN_Z, ART_Y))
        plain.run(records())
        ca = ar.click_analysis(plain)
        self.assertEqual((ca["swings"], ca["runs"], ca["clicks"]), (0, [], []))

    def test_quantile(self):
        self.assertIsNone(ar.quantile([], 0.5))
        self.assertEqual(ar.quantile([5, 1, 3, 2, 4], 0.5), 3)
        self.assertEqual(ar.quantile([5, 1, 3, 2, 4], 0.95), 5)
        self.assertEqual(ar.quantile([5, 1, 3, 2, 4], 1.0), 5)


def air_answer(x, z, block, above="air"):
    """The server's answer to an AIRPLACE click on the cell (x, ART_Y, z) itself (face UP): the clicked cell, then
    the block above it. The floor under the cell is not sent."""
    return [game("block_update", position(x, ART_Y, z) + varint(state(block))),
            game("block_update", position(x, ART_Y + 1, z) + varint(state(above)))]


def cell_echo(x, z, block):
    """The chunk's change broadcast of a placed cell, a few ms after the answer, before the ack: not an answer."""
    return game("block_update", position(x, ART_Y, z) + varint(state(block)))


def offhand_swing_echo():
    """animate SWING_OFF_HAND for the bot: only the server sends that (the recorder writes every swing of the bot as
    SWING_ARM, whatever the hand), so it is not a click."""
    return game("animate", varint(SELF) + b"\x03")


def airplace_records():
    """An AIRPLACE run (sequences 10..15), a lone swing (16), a mixed LOOK/AIRPLACE run at 5 ms ticks (17..22) and a
    run whose only art-area updates are blocks above the cells (23..27). The recorder writes AIRPLACE's offhand swing
    like any other swing (swing())."""
    r = list(records()[:6])                       # login .. chunk (white carpet at 129, ART_Y, 257)
    add = lambda t, *ps: r.extend((t, p) for p in ps)
    add(900, ack(9))
    add(950, hotbar_slot(2, 2))
    add(1000, swing())                            # run 1, AIRPLACE, one click per 50 ms
    add(1002, *air_answer(140, 262, "white_carpet"))
    add(1005, cell_echo(140, 262, "white_carpet"), hotbar_slot(2, 1), ack(10))
    add(1050, swing())
    add(1052, *air_answer(141, 262, "white_carpet"))
    add(1055, cell_echo(141, 262, "white_carpet"), hotbar_slot(2, 0), ack(11))   # the stack is used up
    add(1100, swing())
    add(1102, *air_answer(142, 262, "air"))       # 12 meets an empty hand: rejected at a stack end
    add(1103, offhand_swing_echo())
    add(1105, ack(12))
    add(1150, swing())
    add(1152, *air_answer(129, 257, "white_carpet"))                                # already there: unchanged
    add(1155, ack(13))
    add(1200, swing())
    add(1202, *air_answer(143, 262, "black_carpet"))
    add(1205, cell_echo(143, 262, "black_carpet"), ack(14))
    add(1250, swing())
    add(1255, ack(15))                            # 15: acked, no answer
    add(5000, swing())                            # a lone swing (16): no run
    add(5005, ack(16))
    for k in range(6):                            # run 2, mixed: LOOK on even k, AIRPLACE on odd k, 5 ms ticks
        t, x = 9000 + 5 * k, 150 + k
        if k == 0:
            add(t, swing())
        add(t + 1, *(answer(x, 270, "red_carpet") if k % 2 == 0 else air_answer(x, 270, "red_carpet")))
        # as at 10x speed: the next click's swing is recorded before this click's echo and ack
        add(t + 5, *([swing()] if k < 5 else []), cell_echo(x, 270, "red_carpet"), ack(17 + k))
    for k in range(5):                            # run 3: only blocks above the art layer change, no answers
        add(13000 + 100 * k, swing())
        add(13002 + 100 * k, game("block_update", position(160 + k, ART_Y + 1, 280) + varint(state("stone"))),
            ack(23 + k))
    return r


class AirplaceClicks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        d = cls.tmp.name
        cls.mcpr = os.path.join(d, "2026-01-02_03-04-05_TestBot.mcpr")
        cls.site = os.path.join(d, "mapart.json")
        write_mcpr(cls.mcpr, airplace_records())
        write_site(cls.site)
        cls.an, cls.rec, _ = ar.analyse(cls.mcpr, site=cls.site)
        cls.ca = ar.click_analysis(cls.an)
        cls.out = os.path.join(d, "out")
        cls.res = ar.build_report(cls.an, cls.rec, cls.out, ar.Coords(), "test", make_png=False)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_runs_and_guards(self):
        ca = self.ca
        self.assertFalse(self.an.errors, self.an.error_samples)
        self.assertEqual(ca["swings"], 18, "the server's offhand swing echo is not a click")
        self.assertEqual([(i["run"], i["clicks"], i["seq0"]) for i in ca["runs"]], [(1, 6, 10), (2, 6, 17)])
        self.assertEqual(ca["other_runs"], 1, "a lone swing is no run; blocks above the cells are no answers")
        self.assertEqual([c["seq"] for c in ca["clicks"]], list(range(10, 16)) + list(range(17, 23)))
        self.assertEqual(ca["mismatched"], [], "the cell's echo after a placement is not a second answer")

    def test_airplace_outcomes_and_latencies(self):
        got = [(c["seq"], c["outcome"], c["stack_out"], c["answer"] and c["answer"]["kind"],
                c["answer"] and c["answer"]["x"]) for c in self.ca["clicks"] if c["run"] == 1]
        self.assertEqual(got, [(10, "placed", False, "airplace", 140), (11, "placed", False, "airplace", 141),
                               (12, "rejected", True, "airplace", 142), (13, "unchanged", False, "airplace", 129),
                               (14, "placed", False, "airplace", 143), (15, "acked, no answer", False, None, None)])
        self.assertEqual(self.ca["rtt"], [5] * 12)
        self.assertEqual(self.ca["answer_ms"], [2] * 5 + [1] * 6)
        self.assertEqual(self.ca["stack_out"], 1)

    def test_mixed_run_pairs_both_kinds(self):
        got = [(c["seq"], c["outcome"], c["answer"]["kind"], c["answer"]["x"]) for c in self.ca["clicks"]
               if c["run"] == 2]
        self.assertEqual(got, [(17 + k, "placed", "look" if k % 2 == 0 else "airplace", 150 + k) for k in range(6)])
        self.assertEqual(dict(self.ca["kinds"]), {"look": 3, "airplace": 8})
        lanes = {k: (d["clicks"], d["placed"], d["rejected"], d["unchanged"]) for k, d in self.ca["by_lane"].items()}
        self.assertEqual(lanes, {15: (4, 3, 1, 0), 13: (1, 0, 0, 1), 17: (4, 4, 0, 0), 18: (2, 2, 0, 0)})

    def test_report_and_csv(self):
        with open(os.path.join(self.out, "report.md")) as f:
            md = f.read()
        self.assertIn("| Clicks | 12 in 2 run(s); 1 rejected (1 at a stack end)", md)
        self.assertIn("| Answers | 3 LOOK (floor + cell), 8 AIRPLACE (cell + cell above) |", md)
        self.assertIn("| Outcomes | 9 placed, 1 rejected (1 at a stack end), 1 unchanged, 1 acked without an answer, "
                      "0 not acked |", md)
        self.assertFalse(any("rejected by the server" in r for r in self.res["red_flags"]))
        with open(os.path.join(self.out, "clicks.csv")) as f:
            rows = f.read().splitlines()
        self.assertEqual(len(rows), 13)
        self.assertEqual(rows[3].split(",")[2:], ["1", "12", "1105", "5", "1102", "2", "142", "70", "262", "78",
                                                  "70", "15", "rejected", "air", "1", "airplace"])
        self.assertEqual([r.split(",")[-1] for r in rows[7:]], ["look", "airplace"] * 3)

    def test_answer_shapes(self):
        c = state("white_carpet")
        ev = [("bu", 10, 130, ART_Y + 1, 260, 0, None), ("bu", 10, 130, ART_Y, 260, c, None),     # either order
              ("bu", 20, 131, ART_Y, 260, c, None), ("bu", 23, 131, ART_Y, 260, c, None),       # cell twice: no
              ("bu", 30, 132, ART_Y + 1, 260, 0, None),                                          # above alone: no
              ("bu", 40, 133, ART_Y, 260, c, None), ("ack", 41, 5), ("bu", 41, 133, ART_Y + 1, 260, 0, None),
              ("bu", 50, 134, ART_Y - 1, 260, 1, None), ("bu", 50, 134, ART_Y + 1, 260, 0, None),  # floor + above
              ("bu", 60, 135, ART_Y, 260, c, None), ("bu", 60, 135, ART_Y - 1, 260, 1, None)]       # LOOK
        got = {i: (a["x"], a["t"], a["kind"]) for i, a in ar.click_answers(ev, ART_Y).items()}
        self.assertEqual(got, {1: (130, 10, "airplace"), 11: (135, 60, "look")})


class DamagedRecordings(unittest.TestCase):
    def test_truncated_zip(self):
        with tempfile.TemporaryDirectory() as d:
            full = os.path.join(d, "full.mcpr")
            pad = [(10000 + i, game("set_time", struct.pack(">qq", i * 7919 % 100003, i) + b"\x01"))
                   for i in range(4000)]
            write_mcpr(full, records()[:-1] + pad, meta=False)
            with open(full, "rb") as f:
                data = f.read()
            cut = os.path.join(d, "cut.mcpr")
            with open(cut, "wb") as f:
                f.write(data[:len(data) * 3 // 5])
            rec = ar.Recording(cut)
            n = sum(1 for _ in rec.records())
            self.assertFalse(rec.complete)
            self.assertGreater(n, len(records()))
            self.assertLess(n, len(records()) - 1 + len(pad))
            an, rec2, _ = ar.analyse(cut, site=None)
            self.assertEqual(an.self_name, "TestBot")
            res = ar.build_report(an, rec2, os.path.join(d, "out"), ar.Coords(), "test", make_png=False)
            self.assertTrue(any(r.startswith("Recording incomplete") for r in res["red_flags"]))

    def test_no_metadata(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "2026-01-02_03-04-05_TestBot.mcpr")
            write_mcpr(p, records(), meta=False)
            rec = ar.Recording(p)
            self.assertFalse(rec.complete)
            self.assertTrue(rec.meta.get("date"))


if __name__ == "__main__":
    unittest.main()
