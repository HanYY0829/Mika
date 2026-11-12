#!/usr/bin/env python3
"""模拟企业内网自签 HTTPS 的 OpenAI 兼容端点:GET /v1/models 与 POST /v1/chat/completions。"""
import json
import ssl
from http.server import BaseHTTPRequestHandler, HTTPServer

MODELS = {
    "object": "list",
    "data": [{"id": "internal-test-model", "object": "model", "owned_by": "internal-ca"}],
}

class Handler(BaseHTTPRequestHandler):
    def _json(self, code, body):
        payload = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        print(f"GET {self.path}", flush=True)
        if self.path.startswith("/v1/models"):
            self._json(200, MODELS)
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        print(f"POST {self.path}", flush=True)
        if self.path.startswith("/v1/chat/completions"):
            length = int(self.headers.get("Content-Length") or 0)
            req = json.loads(self.rfile.read(length) or b"{}") if length else {}
            has_tools = bool(req.get("tools"))
            # Mika 的「测试并保存」要求模型会调用工具:请求里带 tools 时回一个 tool_call
            message = (
                {"role": "assistant", "content": None, "tool_calls": [{
                    "id": "call_1", "type": "function",
                    "function": {"name": "test_tool", "arguments": "{}"},
                }]}
                if has_tools else
                {"role": "assistant", "content": "来自内部 CA 端点的回复"}
            )
            body = {
                "id": "test", "object": "chat.completion", "created": 0, "model": "internal-test-model",
                "choices": [{"index": 0, "message": message, "finish_reason": "tool_calls" if has_tools else "stop"}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
            }
            self._json(200, body)
        else:
            self._json(404, {"error": "not found"})

    def log_message(self, *args):
        pass

ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain("/tmp/mika-ca/server.crt", "/tmp/mika-ca/server.key")
httpd = HTTPServer(("127.0.0.1", 8443), Handler)
httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
print("serving on 127.0.0.1:8443", flush=True)
httpd.serve_forever()
