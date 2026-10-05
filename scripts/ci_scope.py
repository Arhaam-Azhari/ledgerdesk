"""Choose checks for a change; manual runs still cover the whole project."""
import json
import os
from pathlib import Path
import subprocess

COMPONENTS = {
    'AccountActivity': ['account-activity'], 'AccountingPeriods': ['periods'],
    'Accounts': ['accounts', 'persistent'], 'Accruals': ['accruals', 'handoff'],
    'Adjustments': ['adjustments'], 'Bank': ['e2e', 'reconciliation'],
    'BankMatching': ['e2e', 'reconciliation'], 'BankReconciliation': ['reconciliation'],
    'BillHandoff': ['handoff'], 'CashActivity': ['cash'],
    'CustomerStatements': ['reports'], 'FixedAssets': ['assets'],
    'OpeningBankBalance': ['opening'], 'OpeningBooks': ['opening-books'],
    'OwnPassword': ['password'], 'OwnerEquity': ['equity'], 'Prepaids': ['prepaid'],
    'ProfitComparison': ['reports'], 'Purchases': ['e2e', 'handoff', 'assets', 'prepaid'],
    'Reports': ['reports', 'account-activity', 'year-end', 'year-end-controls'],
    'YearEnd': ['year-end', 'year-end-controls'],
}
TEST_DIRS = {
    'tests': 'e2e', 'report-tests': 'reports', 'accounts-tests': 'accounts',
    'asset-tests': 'assets', 'year-end-control-tests': 'year-end-controls',
    'period-tests': 'periods', 'opening-tests': 'opening', 'opening-books-tests': 'opening-books',
    'reconciliation-tests': 'reconciliation', 'equity-tests': 'equity',
    'adjustment-tests': 'adjustments', 'accrual-tests': 'accruals', 'handoff-tests': 'handoff',
    'prepaid-tests': 'prepaid', 'cash-tests': 'cash', 'reviewer-tests': 'reviewer',
    'persistent-tests': 'persistent', 'bookkeeper-tests': 'bookkeeper', 'password-tests': 'password',
    'account-activity-tests': 'account-activity', 'year-end-tests': 'year-end',
}

def plan(paths, scripts, full=False):
    code = [p for p in paths if not (p.startswith('docs/') or p.endswith('.md') or p == '.gitignore')]
    backend = full or any(p.startswith('backend/') for p in code)
    recovery = full or any(p.startswith('scripts/') and p not in ('scripts/ci_scope.py', 'scripts/test_ci_scope.py') for p in code)
    recovery = recovery or any(p.startswith('backend/src/main/resources/') or p.endswith(('SecurityConfig.java', 'PersistentAccounts.java', 'AccountManagement.java', 'AccountRecovery.java', 'RecoveryCommand.java', 'PasswordConfig.java', 'OwnPasswordController.java', 'AccountController.java')) for p in code)
    selected = set()
    all_browser = full or backend
    for p in code:
        parts = p.split('/')
        if not p.startswith('frontend/'):
            # Policy tests cover the selector; application code has not changed here.
            if p.startswith('.github/') or p in ('scripts/ci_scope.py', 'scripts/test_ci_scope.py'):
                pass
            elif not p.startswith(('backend/', 'scripts/')):
                backend = recovery = all_browser = True
            continue
        if p in ('frontend/src/main.tsx', 'frontend/src/style.css'):
            selected.update(('e2e', 'reviewer', 'bookkeeper'))
        elif len(parts) == 3 and parts[1] == 'src' and Path(p).stem in COMPONENTS:
            selected.update(COMPONENTS[Path(p).stem])
        elif len(parts) >= 3 and parts[1] in TEST_DIRS:
            selected.add(TEST_DIRS[parts[1]])
        elif p.startswith('frontend/playwright.') and p.endswith('.config.ts'):
            name = Path(p).name.removeprefix('playwright.').removesuffix('.config.ts')
            selected.add('e2e' if name == 'config.ts' else name)
        else:
            all_browser = True
    available = {key.removeprefix('test:') for key in scripts if key.startswith('test:')}
    if all_browser or not selected.issubset(available):
        selected = available
    return {'backend': backend, 'restore': recovery, 'browser': bool(selected),
            'browser_checks': ' '.join('test:' + key for key in sorted(selected, key=lambda key: (key != 'opening-books', key)))}


def main():
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    manual = os.environ['GITHUB_EVENT_NAME'] == 'workflow_dispatch'
    base = event.get('before') or event.get('pull_request', {}).get('base', {}).get('sha')
    if not base or set(base) == {'0'}:
        base = subprocess.check_output(['git', 'rev-parse', 'HEAD^'], text=True).strip()
    paths = subprocess.check_output(['git', 'diff', '--name-only', base, 'HEAD'], text=True).splitlines()
    scripts = json.loads(Path('frontend/package.json').read_text())['scripts']
    result = plan(paths, scripts, full=manual)
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        for key, value in result.items():
            output.write(f'{key}={str(value).lower() if isinstance(value, bool) else value}\n')
    print('Checks:', result)

if __name__ == '__main__':
    main()
