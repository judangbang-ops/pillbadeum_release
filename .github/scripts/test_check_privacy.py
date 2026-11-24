import shutil
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
        self.assertEqual(len(rows), 14)
        self.assertEqual(len(failures), len(rows), [row[1] for row in rows if not row[6]])

    def test_card_exception_is_rejected_even_with_constant_message(self):
        self.source(f"{privacy.SOURCES[0]}/CardExportStore.kt", 'Log.e(TAG, "writing failed", error)')
        self.assertEqual(len(privacy.log_calls(self.root)[1]), 1)

    def test_fixed_messages_and_counts_are_allowed(self):
        self.source(f"{privacy.SOURCES[0]}/SafeFixture.kt", '''
            Log.d(TAG, "export failed")
            Log.d("Export", "entries ${card.entries.size}")
            Log.d(TAG, "action ${intent.action}")
        ''')
        rows, failures = privacy.log_calls(self.root)
        self.assertEqual(len(rows), 3)
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
