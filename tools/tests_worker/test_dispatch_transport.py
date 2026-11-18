"""Real process framing, I/O bounds and termination; no runtime effects."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from authoring_host.contracts import HostError, MAX_BYTES
from authoring_host.dispatch_transport import RuntimeSession


class DispatchTransportTests(unittest.TestCase):
    def session(self, source, timeout=3):
        return RuntimeSession([sys.executable, "-u", "-c", source], timeout, {})

    def test_duplex_exchange_and_clean_exit(self):
        with self.session('import sys; print(sys.stdin.readline(), end=""); print(sys.stdin.readline(), end="")') as session:
            session.send(dict(message="first"))
            self.assertEqual(dict(message="first"), session.receive())
            session.send(dict(message="second"))
            self.assertEqual(dict(message="second"), session.receive())
            session.finish()

    def test_duplicate_keys_truncated_frames_and_trailing_frames_are_rejected(self):
        for source in ('print(\'{"id":1,"id":2}\')', 'print(\'{"id":1}\', end="")'):
            with self.subTest(source=source), self.session(source) as session:
                with self.assertRaises(HostError): session.receive()
        with self.session('print(\'{"id":1}\'); print(\'{"id":2}\')') as session:
            session.receive()
            with self.assertRaises(HostError): session.finish()

    def test_timeout_retires_a_process_holding_its_streams(self):
        with self.session("import time; time.sleep(10)", timeout=0.1) as session:
            with self.assertRaises(HostError): session.receive()
        self.assertIsNotNone(session.process.returncode)

    def test_stdout_and_stderr_are_bounded_while_reading_and_writing(self):
        for source in ('print("x" * 2100000)', 'import sys; sys.stderr.write("x" * 13000000)'):
            with self.subTest(source=source), self.session(source) as session:
                with self.assertRaises(HostError): session.receive()
        with self.session('import sys; sys.stderr.write("x" * 13000000)') as session:
            with self.assertRaises(HostError): session.send(dict(payload="x" * (MAX_BYTES - 100)))

    def test_nonzero_exit_is_not_a_completed_exchange(self):
        with self.session('print(\'{"id":1}\'); raise SystemExit(2)') as session:
            session.receive()
            with self.assertRaises(HostError): session.finish()

    def test_descendant_cannot_keep_the_exchange_alive(self):
        with self.session('import subprocess,sys; subprocess.Popen([sys.executable,"-c","import time; time.sleep(10)"])', 0.2) as session:
            with self.assertRaises(HostError): session.receive()


if __name__ == "__main__":
    unittest.main()
