# -*- coding: utf-8 -*-
"""
网关调试用回显服务：
把收到的请求方法、路径、以及关键请求头原样返回，
用来观察 AuthGlobalFilter 到底往下游透传了什么 X-User-Id。
仅本机调试使用。
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

WATCH = ("x-user-id", "authorization")


class Echo(BaseHTTPRequestHandler):
    def _echo(self):
        headers = {k.lower(): v for k, v in self.headers.items()}
        payload = {
            "method": self.command,
            "path": self.path,
            "x-user-id": headers.get("x-user-id"),
            "has_authorization": "authorization" in headers,
        }
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json;charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        print(json.dumps(payload, ensure_ascii=False), flush=True)

    do_GET = _echo
    do_POST = _echo
    do_PUT = _echo

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
    print("echo server on %d" % port, flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Echo).serve_forever()
