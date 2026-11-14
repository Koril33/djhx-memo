#!/usr/bin/env python3
"""Execute the site's JavaScript in an isolated browser and check rendered download URLs."""
import argparse
from functools import partial
from html.parser import HTMLParser
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading

ROOT = Path(__file__).resolve().parent.parent


class Page(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = {}
        self.attributes = {}
        self.versions = {}
        self.main_version = ""
        self.platform = None
        self.eyebrow = False

    def handle_starttag(self, tag, pairs):
        attrs = dict(pairs)
        if tag == "html":
            self.attributes = attrs
        if tag == "a" and "data-platform" in attrs:
            platform = attrs["data-platform"]
            if platform in self.links:
                raise ValueError("Duplicate platform link")
            self.links[platform] = attrs.get("href")
            self.platform = platform
            self.versions[platform] = ""
        if tag == "p" and "eyebrow" in attrs.get("class", "").split():
            self.eyebrow = True

    def handle_endtag(self, tag):
        if tag == "a":
            self.platform = None
        if tag == "p":
            self.eyebrow = False

    def handle_data(self, value):
        if self.platform:
            self.versions[self.platform] += value
        if self.eyebrow:
            self.main_version += value


class QuietHandler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/__check_controls":
            body = (Path(self.directory) / "index.html").read_text(encoding="utf-8")
            # Test-server instrumentation only: exercise existing DOM controls without
            # putting a testing API in the shipped website or changing release links.
            probe = """<script>
window.addEventListener('load', () => {
 const links = () => [...document.querySelectorAll('[data-platform]')].map(a => a.href).join('|');
 const initial = links();
 try {
  for (const radio of document.querySelectorAll('#lang-switch input[name=lang]')) {
   radio.checked = true; radio.dispatchEvent(new Event('change', {bubbles:true}));
   if (document.documentElement.lang !== (radio.value === 'zh' ? 'zh-CN' : radio.value)) throw new Error('language did not change');
   if (links() !== initial) throw new Error('language changed download URLs');
  }
  const transition = document.startViewTransition;
  document.startViewTransition = undefined;
  for (let i=0; i<2; i++) {
   const before = document.documentElement.dataset.theme;
   document.getElementById('theme-toggle').click();
   if (before === document.documentElement.dataset.theme || links() !== initial) throw new Error('theme changed URLs or did not toggle');
  }
  document.startViewTransition = transition;
  document.documentElement.dataset.memoControlsChecked = 'true';
 } catch (error) { document.documentElement.dataset.memoControlsChecked = 'failed'; }
});</script>"""
            encoded = body.replace("</body>", probe + "</body>").encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
            return
        super().do_GET()

    def log_message(self, *_args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", help="Public URL; omit to serve the built local website")
    parser.add_argument("--manifest", type=Path, default=ROOT / "website/downloads.json")
    parser.add_argument("--browser", type=Path)
    parser.add_argument("--dark", action="store_true")
    parser.add_argument("--language", default="en-US")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--exercise-controls", action="store_true", help="Local build only: exercise language and theme controls")
    args = parser.parse_args()
    browser = args.browser
    if not browser:
        candidates = [shutil.which("chromium"), shutil.which("google-chrome"),
                      "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
                      "C:/Program Files/Microsoft/Edge/Application/msedge.exe"]
        browser = next((Path(p) for p in candidates if p and Path(p).is_file()), None)
    if not browser or not browser.is_file():
        raise SystemExit("Specify an installed Chromium/Edge browser with --browser")
    data = json.loads(args.manifest.read_text(encoding="utf-8"))
    expected = {platform: f"https://github.com/Koril33/djhx-memo/releases/download/v{item['version']}/{item['asset']}"
                for platform, item in data["platforms"].items()}
    local = ROOT / ".local/website-browser"
    local.mkdir(parents=True, exist_ok=True)
    # A fresh task profile never reads the developer's signed-in browser profile.
    profile = Path(tempfile.mkdtemp(prefix="check-", dir=local)).resolve()
    assert profile.is_relative_to(local.resolve())
    server = None
    url = args.url
    if url and args.exercise_controls:
        raise SystemExit("--exercise-controls uses the local test server; omit --url")
    if not url:
        directory = ROOT / "website/dist"
        if not (directory / "index.html").is_file():
            raise SystemExit("Build website/dist first")
        server = ThreadingHTTPServer(("127.0.0.1", 0), partial(QuietHandler, directory=str(directory)))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        url = f"http://127.0.0.1:{server.server_port}/" + ("__check_controls" if args.exercise_controls else "")
    command = [str(browser), "--headless=new", "--no-first-run", "--disable-gpu", "--disable-extensions",
               f"--user-data-dir={profile}", f"--lang={args.language}", f"--accept-lang={args.language}",
               "--virtual-time-budget=5000", "--dump-dom", url]
    if args.dark:
        command.insert(-1, "--force-dark-mode")
    try:
        result = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace", timeout=45)
    finally:
        if server:
            server.shutdown()
            server.server_close()
    page = Page()
    page.feed(result.stdout)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(result.stdout, encoding="utf-8")
    errors = []
    if result.returncode or page.attributes.get("data-memo-ready") != "true":
        errors.append("current site JavaScript did not finish")
    for platform, expected_url in expected.items():
        if page.links.get(platform) != expected_url:
            errors.append(f"{platform}: rendered link {page.links.get(platform)!r} differs from release manifest")
        if data["platforms"][platform]["version"] not in page.versions.get(platform, "").split():
            errors.append(f"{platform}: rendered version differs from release manifest")
    if page.main_version.strip() != "v" + data["version"]:
        errors.append("rendered main version differs from release manifest")
    if set(page.links) != set(expected):
        errors.append("platform set differs from release manifest")
    if args.dark and page.attributes.get("data-theme") != "dark":
        errors.append("dark theme did not apply")
    if args.exercise_controls and page.attributes.get("data-memo-controls-checked") != "true":
        errors.append("language/theme controls did not preserve download URLs")
    if not args.exercise_controls:
        expected_language = args.language.split("-")[0]
        expected_language = "zh-CN" if expected_language == "zh" else expected_language
        if page.attributes.get("lang") != expected_language:
            errors.append("requested language did not apply")
    if errors:
        raise SystemExit("FAIL browser download regression: " + "; ".join(errors))
    print(f"PASS executed JavaScript; three distinct package URLs; lang={page.attributes.get('lang')}; theme={page.attributes.get('data-theme')}")


if __name__ == "__main__":
    main()
