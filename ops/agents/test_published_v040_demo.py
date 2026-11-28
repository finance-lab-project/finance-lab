"""One-off verification of the public v0.4.0 README example on the PR runner."""
import hashlib
import http.cookiejar
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import time
import unittest
import urllib.parse
import urllib.request


class PublishedDemoTest(unittest.TestCase):
    def test_published_v040_readme(self):
        jar = "finance-lab-0.4.0.jar"
        url = "https://github.com/finance-lab-project/finance-lab/releases/download/v0.4.0"
        expected = "e2c81f8072512ba1bed99a90d8bc04499eec566f86017f26e28591a7cde2115d"
        readme = Path("README.md").read_text()
        self.assertIn('release_url="' + url + '"', readme)
        with tempfile.TemporaryDirectory() as work:
            for filename in (jar, jar + ".sha256"):
                self.assertIn('curl -fLO "$release_url/' + filename + '"', readme)
                subprocess.run(["curl", "-fLO", url + "/" + filename], cwd=work, check=True)
            subprocess.run(["shasum", "-a", "256", "-c", jar + ".sha256"], cwd=work, check=True)
            self.assertEqual(hashlib.sha256((Path(work) / jar).read_bytes()).hexdigest(), expected)
            password = secrets.token_hex(12)
            env = dict(os.environ, BUDGET_DB=work + "/demo.sqlite",
                       BUDGET_OWNER_A_PASSWORD=password, BUDGET_OWNER_B_PASSWORD=secrets.token_hex(12))
            base = "http://127.0.0.1:18095"
            client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
            with open(Path(work) / "server.log", "w") as log:
                proc = subprocess.Popen(["java", "--enable-native-access=ALL-UNNAMED", "-jar", jar,
                                         "--spring.profiles.active=demo", "--server.port=18095"],
                                        cwd=work, env=env, stdout=log, stderr=log)
                try:
                    for attempt in range(90):
                        try:
                            with client.open(base + "/login", timeout=3) as response:
                                login = response.read().decode()
                            break
                        except OSError:
                            if proc.poll() is not None or attempt == 89:
                                raise
                            time.sleep(1)
                    token = re.search(r'name="_csrf"[^>]*value="([^"]+)"', login).group(1)
                    form = urllib.parse.urlencode(dict(username="owner-a", password=password, _csrf=token)).encode()
                    with client.open(base + "/login", form, timeout=10) as response:
                        self.assertIn("/months/2027-03", response.geturl())
                        month = response.read().decode()
                    self.assertRegex(month, r'id="actual-income">50\s000\s₽')
                    self.assertRegex(month, r'id="actual-contributions">10\s000\s₽')
                    self.assertRegex(month, r'(?s)id="row-food".*?class="actual">6\s000\s₽')
                    self.assertIn("Редактировать", month)
                    print("Published v0.4.0 public downloads, README checksum, demo sign-in and March page verified.")
                finally:
                    proc.terminate()
                    try:
                        proc.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                        proc.wait()
