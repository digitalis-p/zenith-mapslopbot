#!/usr/bin/env python3
"""A TCP relay that holds every chunk a fixed time in each direction (order kept), for a 2b2t-like round trip on the
local test server (python3 stdlib only; run with -I). Point ZenithProxy's client.server.port at LISTEN_PORT.

Usage:
  python3 -I delay-proxy.py LISTEN_PORT TARGET_PORT ONE_WAY_MS     e.g. 26466 26465 75 (150 ms round trip)

Both ports are on 127.0.0.1. Runs until killed. 2b2t's block-ack round trip on 2026-10-11: p50 about 130 ms, p95
about 270 ms (docs/TESTING.md section 10). Race conditions between the bot's packets and the server's answers only show
with such a delay: the sprint-attribute race of docs/ARCHITECTURE.md "Sprint on restock legs" did not happen at local
latency and did with 75 ms each way.
"""
import asyncio
import socket
import sys
import time


def usage():
    print(__doc__, file=sys.stderr)
    sys.exit(2)


if len(sys.argv) != 4:
    usage()
try:
    LISTEN, TARGET, DELAY = int(sys.argv[1]), int(sys.argv[2]), float(sys.argv[3]) / 1000.0
except ValueError:
    usage()


async def pump(reader, writer):
    """Copies reader to writer, each chunk sent DELAY after it was read."""
    q = asyncio.Queue()

    async def sender():
        try:
            while True:
                item = await q.get()
                if item is None:
                    break
                due, data = item
                wait = due - time.monotonic()
                if wait > 0:
                    await asyncio.sleep(wait)
                writer.write(data)
                await writer.drain()
        except (ConnectionError, OSError):
            pass
        finally:
            try:
                writer.close()
            except (ConnectionError, OSError):
                pass

    task = asyncio.create_task(sender())
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            q.put_nowait((time.monotonic() + DELAY, data))
    except (ConnectionError, OSError):
        pass
    q.put_nowait(None)
    await task


async def handle(client_reader, client_writer):
    try:
        server_reader, server_writer = await asyncio.open_connection("127.0.0.1", TARGET)
    except OSError as e:
        print("cannot reach 127.0.0.1:%d: %s" % (TARGET, e), flush=True)
        client_writer.close()
        return
    for w in (client_writer, server_writer):
        sock = w.get_extra_info("socket")
        if sock is not None:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    print("connection opened", flush=True)
    await asyncio.gather(pump(client_reader, server_writer), pump(server_reader, client_writer))
    print("connection closed", flush=True)


async def main():
    server = await asyncio.start_server(handle, "127.0.0.1", LISTEN)
    print("listening on 127.0.0.1:%d -> 127.0.0.1:%d, %.0f ms each way" % (LISTEN, TARGET, DELAY * 1000), flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
