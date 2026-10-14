"""Transport-level regression test for the multi-worker TCP_NODELAY fix (see backend/app/main.py)."""
import os
import socket
import unittest

os.environ["NS_NO_AUTOAPP"] = "1"
from backend.app import main  # noqa: E402  (installs the patch)
import asyncio.base_events as be  # noqa: E402


class TestNoDelay(unittest.TestCase):
    def test_patch_installed(self):
        self.assertIs(be._set_nodelay, main._set_nodelay_any_tcp)

    def test_proto_zero_socket_gets_nodelay(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)   # proto 0, as uvicorn binds with --workers > 1
        try:
            self.assertEqual(s.proto, 0)
            be._set_nodelay(s)
            self.assertEqual(s.getsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY), 1)
        finally:
            s.close()


if __name__ == "__main__":
    unittest.main()
