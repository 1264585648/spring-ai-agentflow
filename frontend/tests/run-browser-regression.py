"""Run mounted React lifecycle regressions in the CI runner's real Chrome, without new npm dependencies."""
import functools
import http.server
import json
from html.parser import HTMLParser
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading


class ResultParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.status = None
        self.payload = ""
        self.reading = False

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag == "pre" and values.get("id") == "regression-result":
            self.status = values.get("data-status")
            self.reading = True

    def handle_endtag(self, tag):
        if tag == "pre":
            self.reading = False

    def handle_data(self, data):
        if self.reading:
            self.payload += data


def main():
    binary = next((shutil.which(name) for name in ("google-chrome", "google-chrome-stable", "chromium", "chromium-browser") if shutil.which(name)), None)
    if not binary:
        raise SystemExit("Browser regression failed: Chrome/Chromium is required; test was NOT skipped")
    root = Path("regression-dist").resolve()
    if not (root / "tests/chat-drawer-regression.html").is_file():
        raise SystemExit("Missing browser regression build")
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(root)))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix="agentflow-chrome-") as profile:
            completed = subprocess.run([
                binary, "--headless=new", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage",
                "--no-first-run", "--disable-background-networking", f"--user-data-dir={profile}",
                "--virtual-time-budget=10000", "--dump-dom",
                f"http://127.0.0.1:{server.server_port}/tests/chat-drawer-regression.html",
            ], text=True, capture_output=True, timeout=45, check=False)
        parser = ResultParser()
        parser.feed(completed.stdout)
        print(parser.payload or "Browser produced no test result")
        if completed.returncode or parser.status != "passed":
            print(completed.stderr[-6000:])
            raise SystemExit("ChatDrawer browser regressions FAILED")
        report = json.loads(parser.payload)
        if report.get("passed") != 7:
            raise SystemExit("Incomplete browser regression suite")
        print("PASS: 7 real-browser ChatDrawer lifecycle regression cases")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


if __name__ == "__main__":
    main()
