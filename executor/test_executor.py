"""Phase 0 baseline tests for executor.py — stdlib unittest only, no SDK, no DB.

Run:  python3 -m unittest executor/test_executor.py   (from the repo root)
      or: cd executor && python3 -m unittest test_executor

Freezes the HTTP contract the Java JupyterExecutionService depends on:
  POST /execute {"code": str, "timeout"?: int}
    200 {"stdout", "stderr", "returncode"}   — any completed run, even non-zero exit
    400 {"error"}                            — malformed JSON / missing code
    504 {"error": "Execution timed out after Ns"}
    404 {"error": "Not found"}               — any other path
  GET /health -> 200 {"status": "ok", "port": PORT}
and the chunked-request-body support Spring's RestClient needs.
"""
import http.client
import importlib.util
import json
import os
import socket
import tempfile
import threading
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("executor", os.path.join(_HERE, "executor.py"))
executor = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(executor)


class ExecutorHttpContractTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        # The default WORKDIR (/home/jovyan) only exists in the container; the
        # handler reads the module global per request, so point it at a temp dir.
        cls._workdir = tempfile.TemporaryDirectory()
        executor.WORKDIR = cls._workdir.name
        cls.server = executor.ThreadingHTTPServer(("127.0.0.1", 0), executor.ExecutorHandler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls._workdir.cleanup()

    def _post(self, body, path="/execute"):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)
        try:
            payload = body if isinstance(body, bytes) else json.dumps(body).encode()
            conn.request("POST", path, body=payload, headers={"Content-Type": "application/json"})
            res = conn.getresponse()
            return res.status, json.loads(res.read())
        finally:
            conn.close()

    def test_success_returns_stdout_and_zero_returncode(self):
        status, body = self._post({"code": "print('hello from executor')"})
        self.assertEqual(status, 200)
        self.assertEqual(body["returncode"], 0)
        self.assertEqual(body["stdout"], "hello from executor\n")
        self.assertEqual(body["stderr"], "")
        self.assertEqual(set(body), {"stdout", "stderr", "returncode"})

    def test_stdout_and_stderr_captured_separately_on_failure(self):
        code = (
            "import sys\n"
            "print('before')\n"
            "print('warn', file=sys.stderr)\n"
            "raise ValueError('boom')\n"
        )
        status, body = self._post({"code": code})
        # A failing script is still HTTP 200 — the caller reads returncode.
        self.assertEqual(status, 200)
        self.assertNotEqual(body["returncode"], 0)
        self.assertEqual(body["stdout"], "before\n")
        self.assertIn("warn", body["stderr"])
        self.assertIn("ValueError: boom", body["stderr"])

    def test_export_confirmation_line_passes_through_stdout(self):
        # CopilotService scrapes this exact line to learn the saved strategy name.
        status, body = self._post({"code": "print(\"Kairos 'fixture_rsi' exported successfully\")"})
        self.assertEqual(status, 200)
        self.assertIn("Kairos 'fixture_rsi' exported successfully", body["stdout"])

    def test_timeout_returns_504_and_cleans_up_temp_file(self):
        status, body = self._post({"code": "import time\ntime.sleep(10)", "timeout": 1})
        self.assertEqual(status, 504)
        self.assertEqual(body, {"error": "Execution timed out after 1s"})
        self.assertEqual([f for f in os.listdir(self._workdir.name) if f.endswith(".py")], [])

    def test_missing_code_is_400(self):
        status, body = self._post({"code": ""})
        self.assertEqual(status, 400)
        self.assertEqual(body, {"error": "Missing 'code' field"})

    def test_malformed_json_is_400(self):
        status, body = self._post(b"{not json")
        self.assertEqual(status, 400)
        self.assertTrue(body["error"].startswith("Malformed request body"))

    def test_unknown_path_is_404(self):
        status, body = self._post({"code": "print(1)"}, path="/nope")
        self.assertEqual(status, 404)
        self.assertEqual(body, {"error": "Not found"})

    def test_health(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            conn.request("GET", "/health")
            res = conn.getresponse()
            self.assertEqual(res.status, 200)
            self.assertEqual(json.loads(res.read())["status"], "ok")
        finally:
            conn.close()

    def test_chunked_request_body(self):
        """Spring's RestClient sends Transfer-Encoding: chunked with no Content-Length."""
        payload = json.dumps({"code": "print('chunked ok')"}).encode()
        # Split into several chunks, one with a chunk extension, then a trailer.
        parts = [payload[:7], payload[7:20], payload[20:]]
        raw = b""
        for i, p in enumerate(parts):
            ext = b";ext=1" if i == 1 else b""
            raw += format(len(p), "x").encode() + ext + b"\r\n" + p + b"\r\n"
        raw += b"0\r\nX-Trailer: 1\r\n\r\n"

        request = (
            b"POST /execute HTTP/1.1\r\n"
            b"Host: 127.0.0.1\r\n"
            b"Content-Type: application/json\r\n"
            b"Transfer-Encoding: chunked\r\n"
            b"Connection: close\r\n\r\n" + raw
        )
        with socket.create_connection(("127.0.0.1", self.port), timeout=30) as s:
            s.sendall(request)
            data = b""
            while True:
                buf = s.recv(65536)
                if not buf:
                    break
                data += buf
        head, _, body = data.partition(b"\r\n\r\n")
        self.assertTrue(head.startswith(b"HTTP/1.1 200"), head)
        result = json.loads(body)
        self.assertEqual(result["returncode"], 0)
        self.assertEqual(result["stdout"], "chunked ok\n")


if __name__ == "__main__":
    unittest.main()
