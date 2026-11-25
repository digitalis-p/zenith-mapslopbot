#!/usr/bin/env python3
"""Tiny Source-RCON client for the local test server (python3 stdlib only; run with -I).

Usage:
  python3 -I rcon.py COMMAND [COMMAND...]   run each argument as one command, print the replies
  python3 -I rcon.py -                      read commands from stdin, one per line ('#' = comment)
  python3 -I rcon.py -f FILE                read commands from FILE, same format
Options:
  -q        print nothing but errors
  --host H  --port P  --password-file F    override (defaults: work/server.properties)

The leading '/' of a command is optional. Exit status 1 if the connection or auth fails.
Vanilla RCON limits: a request may be at most 1460 bytes, and only one request may be in flight
(the server closes the connection otherwise). This client enforces both.
Script directives (lines starting with '@'):
  @wait-loaded X Z [SECONDS]   wait until the chunk holding block X,Z is loaded (after forceload)
  @sleep SECONDS
"""
import os
import socket
import struct
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(HERE, "work")

SERVERDATA_AUTH = 3
SERVERDATA_EXECCOMMAND = 2
SERVERDATA_RESPONSE_VALUE = 0
# Vanilla RconClient reads requests into a 1460-byte buffer (length prefix included).
MAX_PACKET = 1460
MAX_COMMAND = MAX_PACKET - 4 - 8 - 2


def read_props(path):
    props = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()
    except FileNotFoundError:
        pass
    return props


class Rcon:
    def __init__(self, host, port, password, timeout=60.0):
        self.timeout = timeout
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.next_id = 1
        rid = self._send(SERVERDATA_AUTH, password)
        # Auth reply is type 2 (AUTH_RESPONSE) with our id, or id -1 on a wrong password.
        # Some servers send an empty RESPONSE_VALUE first; skip it.
        while True:
            pid, ptype, _ = self._recv()
            if ptype == 2:
                if pid == -1:
                    raise PermissionError("RCON authentication failed (wrong password)")
                if pid == rid:
                    break

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass

    def _send(self, ptype, body):
        rid = self.next_id
        self.next_id += 1
        payload = struct.pack("<ii", rid, ptype) + body.encode("utf-8") + b"\x00\x00"
        if len(payload) + 4 > MAX_PACKET:
            # The vanilla server drops the connection on larger packets.
            raise ValueError("RCON command too long (%d bytes, max %d): %s..." % (
                len(payload) + 4, MAX_PACKET, body[:60]))
        self.sock.sendall(struct.pack("<i", len(payload)) + payload)
        return rid

    def _recvn(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("RCON connection closed")
            buf += chunk
        return buf

    def _recv(self):
        (length,) = struct.unpack("<i", self._recvn(4))
        data = self._recvn(length)
        pid, ptype = struct.unpack("<ii", data[:8])
        body = data[8:-2].decode("utf-8", errors="replace")
        return pid, ptype, body

    def command(self, cmd):
        # Strictly one request in flight: the vanilla server reads one TCP chunk per request and
        # drops the connection if two requests arrive in one read (no pipelining, no markers).
        # It answers with one packet, or several of exactly 4096 chars when the output is long.
        rid = self._send(SERVERDATA_EXECCOMMAND, cmd)
        out = []
        while True:
            if out:
                # A 4096-char chunk may be the last one; don't wait long for a follow-up.
                self.sock.settimeout(1.0)
            try:
                pid, _, body = self._recv()
            except socket.timeout:
                break
            finally:
                self.sock.settimeout(self.timeout)
            if pid != rid:
                continue
            out.append(body)
            if len(body) < 4096:
                break
        return "".join(out)


def script_lines(f):
    out = []
    for line in f:
        line = line.strip()
        if line and not line.startswith("#"):
            out.append(line)
    return out


def directive(r, line, quiet):
    parts = line.split()
    if parts[0] == "@sleep":
        time.sleep(float(parts[1]))
    elif parts[0] == "@wait-loaded":
        x, z = int(parts[1]), int(parts[2])
        limit = time.time() + (float(parts[3]) if len(parts) > 3 else 30.0)
        while not r.command("execute if loaded %d 0 %d" % (x, z)).startswith("Test passed"):
            if time.time() > limit:
                raise SystemExit("rcon: chunk at %d,%d did not load" % (x, z))
            time.sleep(0.25)
        if not quiet:
            print("(chunk at %d,%d loaded)" % (x, z))
    else:
        raise SystemExit("rcon: unknown directive " + parts[0])


def main(argv):
    quiet = False
    host = port = pwfile = None
    cmds = []
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == "-q":
            quiet = True
        elif a == "--host":
            i += 1
            host = argv[i]
        elif a == "--port":
            i += 1
            port = int(argv[i])
        elif a == "--password-file":
            i += 1
            pwfile = argv[i]
        elif a in ("-h", "--help"):
            print(__doc__)
            return 0
        elif a == "-":
            cmds += script_lines(sys.stdin)
        elif a == "-f":
            i += 1
            with open(argv[i], encoding="utf-8") as f:
                cmds += script_lines(f)
        else:
            cmds.append(a)
        i += 1
    if not cmds:
        print(__doc__, file=sys.stderr)
        return 2

    props = read_props(os.path.join(WORK, "server.properties"))
    host = host or props.get("server-ip") or "127.0.0.1"
    port = port or int(props.get("rcon.port") or os.environ.get("TESTSERVER_RCON_PORT") or "25575")
    pwfile = pwfile or os.path.join(WORK, ".rcon-password")
    password = None
    if os.path.exists(pwfile):
        with open(pwfile, encoding="utf-8") as f:
            password = f.read().strip()
    password = password or props.get("rcon.password")
    if not password:
        print("rcon: no password (run testserver/setup.sh)", file=sys.stderr)
        return 1

    try:
        r = Rcon(host, port, password)
    except (OSError, PermissionError) as e:
        print("rcon: cannot connect to %s:%d: %s" % (host, port, e), file=sys.stderr)
        return 1
    try:
        for c in cmds:
            if c.startswith("@"):
                directive(r, c, quiet)
                continue
            if c.startswith("/"):
                c = c[1:]
            reply = r.command(c)
            if not quiet:
                print(reply.rstrip("\n") if reply else "(no output)")
    finally:
        r.close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
