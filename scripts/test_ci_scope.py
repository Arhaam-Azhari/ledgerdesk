import json
from pathlib import Path
import unittest
from ci_scope import plan

class CheckSelectionTest(unittest.TestCase):
    scripts = json.loads((Path(__file__).resolve().parents[1] / 'frontend/package.json').read_text())['scripts']

    def test_docs_do_not_start_application_checks(self):
        result = plan(['README.md', 'docs/screenshots/example.png'], self.scripts)
        self.assertFalse(any(result[key] for key in ('backend', 'restore', 'browser')))

    def test_opening_editor_and_navigation_keep_role_coverage(self):
        result = plan(['frontend/src/OpeningBooks.tsx', 'frontend/src/main.tsx'], self.scripts)
        self.assertFalse(result['backend'])
        self.assertFalse(result['restore'])
        self.assertEqual(set(result['browser_checks'].split()), {'test:opening-books', 'test:e2e', 'test:reviewer', 'test:bookkeeper'})

    def test_unknown_frontend_and_policy_changes_keep_all_browser_checks(self):
        for path in ('frontend/src/money.ts', 'frontend/package.json'):
            self.assertEqual(set(plan([path], self.scripts)['browser_checks'].split()), {s for s in self.scripts if s.startswith('test:') and s != 'test:hosted'})

    def test_recovery_fixture_does_not_rerun_unrelated_application_suites(self):
        result = plan(['scripts/verify_opening_books_restore.py', '.github/workflows/checks.yml'], self.scripts)
        self.assertTrue(result['restore'])
        self.assertFalse(result['backend'])
        self.assertFalse(result['browser'])

    def test_schema_and_manual_runs_keep_recovery_checks(self):
        for result in (plan(['backend/src/main/resources/db/migration/V27.sql'], self.scripts), plan([], self.scripts, full=True)):
            self.assertTrue(result['backend'])
            self.assertTrue(result['restore'])
            self.assertTrue(result['browser'])

    def test_hosted_browser_uses_its_own_installation_job(self):
        result = plan(['frontend/hosted-tests/hosted.spec.ts', 'frontend/playwright.hosted.config.ts'], self.scripts)
        self.assertFalse(result['browser'])
        self.assertNotIn('test:hosted', plan(['backend/src/main/java/com/ledgerdesk/SecurityConfig.java'], self.scripts)['browser_checks'].split())

if __name__ == '__main__':
    unittest.main()
