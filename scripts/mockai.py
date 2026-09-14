"""Local OpenAI-compatible mock provider for verifying Maya on-device without API keys.

Phase 1 (no `tool` message in history): streams text + a get_time tool_call.
Phase 2 (history contains a `tool` message): streams the final answer.

Run:  python scripts/mockai.py            (listens on 0.0.0.0:18080)
Emulator reaches it via:  adb reverse tcp:18789 tcp:18080  ->  http://localhost:18789/v1
"""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = "scripts/mockai.log"


def log(msg):
    line = f"MOCKAI: {msg}"
    print(line, flush=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")


def sse(obj):
    return "data: " + json.dumps(obj) + "\n\n"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send_sse(self, chunks, finish):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for c in chunks:
            body = sse({"choices": [{"delta": {"content": c}, "index": 0}]}).encode()
            self.wfile.write(hex(len(body))[2:].encode() + b"\r\n" + body + b"\r\n")
        if finish.startswith("tool_calls"):
            if finish == "tool_calls2":
                fn = {"name": "whatsapp_send_message",
                      "arguments": json.dumps({"phone": "+919876543210", "message": "hello from Maya"})}
            else:
                fn = {"name": "get_time", "arguments": "{}"}
            tool = sse({"choices": [{"delta": {"tool_calls": [{
                "index": "0", "id": "call_" + finish,
                "function": fn,
            }]}, "index": 0}]}).encode()
            self.wfile.write(hex(len(tool))[2:].encode() + b"\r\n" + tool + b"\r\n")
        fin = sse({"choices": [{"delta": {}, "finish_reason": finish, "index": 0}]}).encode()
        self.wfile.write(hex(len(fin))[2:].encode() + b"\r\n" + fin + b"\r\n")
        done = b"data: [DONE]\n\n"
        self.wfile.write(hex(len(done))[2:].encode() + b"\r\n" + done + b"\r\n")
        self.wfile.write(b"0\r\n\r\n")

    def do_POST(self):
        if not self.path.endswith("/chat/completions"):
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        messages = body.get("messages", [])
        roles = [m.get("role") for m in messages]
        has_tool_result = "tool" in roles
        log(f"POST {self.path} model={body.get('model')} msgs={len(messages)} roles={roles} "
            f"tools_declared={[t.get('function', {}).get('name') for t in body.get('tools', [])]} "
            f"stream={body.get('stream')}")

        tool_texts = [m.get("content", "") for m in messages if m.get("role") == "tool"]
        if any("Confirmation needed" in t for t in tool_texts):
            self._send_sse(["Understood — I'll send it ", "as soon as you approve."], "stop")
        elif has_tool_result:
            # Round 2 after a successful tool result: now request a CONFIRM-risk tool
            # to exercise the risk gate through the real streaming pipeline.
            self._send_sse(["Let me also ", "message John."], "tool_calls2")
        else:
            self._send_sse(["Checking the time for you."], "tool_calls")

    def do_GET(self):
        body = b"maya-mock-up"
        log(f"GET {self.path}")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    open(LOG, "w").close()
    log("listening on 0.0.0.0:18080")
    ThreadingHTTPServer(("0.0.0.0", 18080), Handler).serve_forever()
