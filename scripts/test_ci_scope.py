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
        for path in ('frontend/src/money.ts', '.github/workflows/checks.yml'):
            self.assertEqual(set(plan([path], self.scripts)['browser_checks'].split()), {s for s in self.scripts if s.startswith('test:')})

    def test_schema_and_manual_runs_keep_recovery_checks(self):
        for result in (plan(['backend/src/main/resources/db/migration/V27.sql'], self.scripts), plan([], self.scripts, full=True)):
            self.assertTrue(result['backend'])
            self.assertTrue(result['restore'])
            self.assertTrue(result['browser'])

if __name__ == '__main__':
    unittest.main()
