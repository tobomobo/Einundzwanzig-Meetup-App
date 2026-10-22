#!/usr/bin/env python3
"""Offline, deterministic mempool-compatible API for emulator UI tests.

Run locally, then `adb -s emulator-5556 reverse tcp:28021 tcp:28021` and set the
test app's custom mempool URL to http://127.0.0.1:28021. Does not contact public services.
"""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RESPONSES = {
    "/api/blocks/tip/height": 1000000,
    "/api/v1/prices": {"EUR": 100000, "USD": 120000, "GBP": 90000, "CHF": 95000,
                       "CAD": 160000, "AUD": 180000, "JPY": 15000000},
    "/api/v1/fees/recommended": {"economyFee": 2, "halfHourFee": 4, "fastestFee": 8},
    "/api/v1/mining/hashrate/3d": {"currentHashrate": 10**21},
    "/api/v1/difficulty-adjustment": {"difficultyChange": 2.5, "remainingBlocks": 1000},
    "/api/v1/lightning/statistics/latest": {
        "latest": {"total_capacity": 500000000000, "node_count": 15000, "channel_count": 50000}},
}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        value = RESPONSES.get(self.path)
        if value is None:
            self.send_error(404)
            return
        payload = str(value).encode() if isinstance(value, int) else json.dumps(value).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain" if isinstance(value, int) else "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        print(fmt % args, flush=True)


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 28021), Handler).serve_forever()
