#!/usr/bin/env python3
"""Exercise the release-note generation without accessing GitHub or signing keys."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/publish-apk.yml"
SAFETY = (
    "업데이트는 지금 앱 위에 그대로 설치해 주세요. "
    "앱을 지우면 기록이 사라질 수 있어요. "
    "새 폰으로 옮기기 전에는 먼저 알려 주세요."
)


class PublishNotesTest(unittest.TestCase):
    def test_published_notes_include_safety_message(self):
        lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
        step = next(i for i, line in enumerate(lines) if "- name: GitHub 릴리즈 만들기" in line)
        run = next(i for i in range(step, len(lines)) if lines[i] == "        run: |")
        script = []
        for line in lines[run + 1:]:
            if line and not line.startswith("          "):
                break
            script.append(line[10:] if line else "")
        self.assertTrue(script, "release step script is missing")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake_bin = root / "bin"
            fake_bin.mkdir()
            fake_gh = fake_bin / "gh"
            fake_gh.write_text(
                "#!/bin/sh\n"
                "[ \"$1\" = release ] && [ \"$2\" = create ] || exit 9\n"
                "while [ \"$#\" -gt 0 ]; do\n"
                "  if [ \"$1\" = --notes-file ]; then shift; cp \"$1\" \"$CAPTURE_NOTES\"; exit; fi\n"
                "  shift\n"
                "done\n"
                "exit 10\n",
                encoding="utf-8",
            )
            fake_gh.chmod(0o700)
            apk = root / "signed.apk"
            apk.write_bytes(b"test-only apk")
            env = os.environ.copy()
            env.update({
                "PATH": str(fake_bin) + os.pathsep + os.defpath,
                "RUNNER_TEMP": str(root),
                "SIGNED_APK": str(apk),
                "VERSION_NAME": "0.1.0.999",
                "SHA": "a" * 40,
                "CODE": "999",
                "GH_REPO": "example/test",
                "APP_SIGNING_CERT_SHA256": "test-fingerprint",
                "GITHUB_STEP_SUMMARY": str(root / "summary"),
                "CAPTURE_NOTES": str(root / "captured.md"),
            })
            subprocess.run(["/bin/bash", "-n"], input="\n".join(script), text=True, check=True)
            subprocess.run(["/bin/bash"], input="\n".join(script), text=True, env=env, check=True, capture_output=True)
            notes = (root / "captured.md").read_text(encoding="utf-8")
            self.assertIn(SAFETY, notes)
            self.assertIn("0.1.0.999", notes)
            self.assertIn("example/test", notes)


if __name__ == "__main__":
    unittest.main()
