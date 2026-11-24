import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import check_privacy as privacy


class PrivacyChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def source(self, path, contents):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contents, encoding="utf-8")
        return target

    def test_every_private_log_in_fixture_is_rejected(self):
        fixture = Path(__file__).with_name("fixtures") / "private_log_calls.kt"
        target = self.source(f"{privacy.SOURCES[0]}/PrivateFixture.kt", "")
        shutil.copyfile(fixture, target)
        rows, failures = privacy.log_calls(self.root)
        self.assertEqual([row[1] for row in rows if row[6]], [8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                                                            21, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33])
        self.assertEqual(len(failures), 25)

    def test_ci_command_fails_with_private_log_and_passes_after_removal(self):
        data = self.root / "build-evidence"
        self.source("build-evidence/merged-manifest-release.xml", '''
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.fixture">
                <application>
                    <receiver android:name="com.example.fixture.reminder.ReminderReceiver" android:exported="false" />
                    <provider android:name="com.example.fixture.export.CardExportProvider" android:exported="false" />
                </application>
            </manifest>
        ''')
        self.source("build-evidence/manifest-merger-release-report.txt", "")
        self.source("build-evidence/dependencies-releaseRuntimeClasspath.txt", "+--- androidx.core:core:1.0.0\n")
        self.source("androidApp/expected-exported.txt", "")
        for scope in privacy.RENDER_SOURCES:
            self.source(scope if scope.endswith(".kt") else f"{scope}/Renderer.kt", "")
        self.source(f"{privacy.SOURCES[0]}/Safe.kt", "PendingIntent.getActivity(ctx, 0, intent, FLAG_IMMUTABLE)")
        probe = self.source(f"{privacy.SOURCES[0]}/Failing.kt", 'println("synthetic payload: $x")')
        command = [sys.executable, "-B", str(Path(privacy.__file__).resolve()), str(data), str(self.root)]
        environment = {**os.environ, "PYTHONUTF8": "1"}
        failed = subprocess.run(command, capture_output=True, encoding="utf-8", env=environment, check=False)
        self.assertEqual(failed.returncode, 1, failed.stderr)
        self.assertIn("Failing.kt:1 (println)", failed.stdout)
        self.assertNotIn("synthetic payload", failed.stdout)

        probe.unlink()
        passed = subprocess.run(command, capture_output=True, encoding="utf-8", env=environment, check=False)
        self.assertEqual(passed.returncode, 0, passed.stdout + passed.stderr)

    def test_card_exception_is_rejected_even_with_constant_message(self):
        self.source(f"{privacy.SOURCES[0]}/CardExportStore.kt", '''
            Log.e(TAG, "writing failed", error)
            Log.e(TAG, "writing failed: $e")
        ''')
        self.assertEqual(len(privacy.log_calls(self.root)[1]), 2)

    def test_fixed_messages_and_counts_are_allowed(self):
        self.source(f"{privacy.SOURCES[0]}/Reminders.kt", '''
            private const val TAG = "Reminders"
            // Log.d(dynamicTag, privateValue)
            val snippet = "Log.d(dynamicTag, privateValue)"
            Log.d(TAG, "export failed" + " before writing")
            Log.d("Reminders", "notices ${notices.size}")
            Log.d(TAG, /* A comment with ')' */ "failed: ${error.javaClass.simpleName}")
            Timber.tag("Reminders").i("fixed text")
        ''')
        rows, failures = privacy.log_calls(self.root)
        self.assertEqual(len(rows), 5)
        self.assertEqual(failures, [])

    def test_renderer_database_reads_are_rejected(self):
        for scope in privacy.RENDER_SOURCES:
            target = scope if scope.endswith(".kt") else f"{scope}/Renderer.kt"
            self.source(target, '''
                db.allergyDao().list(personId)
                db.intakeMedicationDao().listFields(intakeId)
                db.load("SELECT * FROM `health_card`")
            ''')
        count, failures = privacy.renderer_card_reads(self.root)
        self.assertEqual(count, 4)
        self.assertEqual(len(failures), 12)

    def test_current_app_sources_pass(self):
        root = Path(__file__).resolve().parents[2]
        rows, failures = privacy.log_calls(root)
        self.assertTrue(rows)
        self.assertEqual(failures, [])
        count, failures = privacy.renderer_card_reads(root)
        self.assertGreater(count, 4)
        self.assertEqual(failures, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
