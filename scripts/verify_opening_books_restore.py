"""Check imported opening books through real H2 and PostgreSQL restoration."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

import verify_postgres_restore as app
from postgres_backup import command

SOURCE = 'opening_books_backup_source'
TARGET = 'opening_books_backup_restored'
DATES = 'startsOn=2026-01-01&endsOn=2026-01-31'


def start(database, mode, log):
    datasource = (
        {'url': f'jdbc:postgresql://127.0.0.1:5432/{database}',
         'username': os.environ['PGUSER'], 'password': os.environ['DATABASE_PASSWORD']}
        if mode == 'postgres' else
        {'url': f'jdbc:h2:file:{database};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE',
         'username': 'sa', 'password': ''}
    )
    settings = {
        'server': {'port': 8093, 'address': '127.0.0.1'},
        'spring': {'datasource': datasource},
        'app': {'accounts': {'persistent': True}, 'username': app.OWNER,
                'password': app.PASSWORD, 'reviewer': {
                    'username': app.REVIEWER, 'password': app.REVIEWER_PASSWORD}},
    }
    app.server = subprocess.Popen(
        ['java', '-jar', 'target/ledgerdesk-0.1.0.jar'], cwd=app.ROOT / 'backend',
        env={**os.environ, 'SPRING_APPLICATION_JSON': json.dumps(settings)},
        stdout=log, stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if app.server.poll() is not None:
            raise RuntimeError('Backend exited; inspect the opening-books restore log.')
        try:
            with app.opener.open(app.BASE + '/api/csrf', timeout=3) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.3)
    raise RuntimeError('Opening-books restore backend readiness timed out.')


def read(path, user=app.OWNER, password=app.PASSWORD):
    status, result = app.api(path, user, password)
    assert status == 200, (path, status)
    return result


def post(path, data, key):
    status, result = app.write(path, data, key)
    assert status == 200, (path, status)
    return result


def evidence():
    return {
        'history': read('/api/opening-books'),
        'state': read('/api/state'),
        'reports': read('/api/reports?' + DATES),
        'earlier': read('/api/reports?startsOn=2025-12-01&endsOn=2025-12-30'),
        'cash': read('/api/reports/cash-activity?' + DATES),
        'statement': read('/api/reports/customers/demo-customer/statement?' + DATES),
        'bank': read('/api/reports/accounts/1000/activity?' + DATES),
    }


def verify(mode):
    if mode == 'postgres':
        # These are fresh CI databases. Existing names are refused, never dropped.
        subprocess.run(command('createdb', '--maintenance-db=postgres', SOURCE), check=True)
    with tempfile.TemporaryDirectory(prefix='ledgerdesk-opening-restore-') as folder:
        folder = Path(folder)
        source = SOURCE if mode == 'postgres' else str(folder / 'source')
        target = TARGET if mode == 'postgres' else str(folder / 'restored')
        with (app.ROOT / f'opening-books-{mode}-restore.log').open('w') as log:
            try:
                start(source, mode, log)
                post('/api/accounts', {'username': app.BOOKKEEPER, 'password': app.BOOKKEEPER_PASSWORD,
                     'role': 'BOOKKEEPER'}, 'opening-restore-account')
                vendor = post('/api/vendors', {'name': 'Harbor Supplies',
                     'email': 'accounts@harbor.example'}, 'opening-restore-vendor')
                draft = {
                    'asOf': '2025-12-31', 'reviewNote': 'Prior books and unpaid originals agreed',
                    'balances': [
                        {'code': '1000', 'debit': '1000.25', 'credit': '0.00'},
                        {'code': '1100', 'debit': '100.10', 'credit': '0.00'},
                        {'code': '2000', 'debit': '0.00', 'credit': '40.04'},
                        {'code': '3000', 'debit': '0.00', 'credit': '1000.25'},
                        {'code': '3300', 'debit': '0.00', 'credit': '60.06'},
                    ],
                    'receivables': [{'customerId': 'demo-customer', 'reference': 'OLD-INV-7',
                        'description': 'Prior design work', 'issuedOn': '2025-12-01',
                        'dueOn': '2026-01-15', 'amount': '100.10'}],
                    'payables': [{'vendorId': vendor['id'], 'reference': 'OLD-BILL-9',
                        'description': 'Prior supplies', 'issuedOn': '2025-12-02',
                        'dueOn': '2026-01-20', 'amount': '40.04'}],
                }
                preview = post('/api/opening-books/preview', draft, 'opening-restore-preview')
                assert preview['ready'] and preview['debits'] == preview['credits'] == '1100.35'
                imported = post('/api/opening-books', draft, 'opening-restore-import')
                initial = read('/api/opening-books')
                assert len(initial['openingBooks']) == 1
                original = initial['openingBooks'][0]
                assert original['id'] == imported['id'] and original['created_by'] == app.OWNER
                assert json.loads(original['snapshot']) == preview
                invoice = initial['receivables'][0]['invoice_id']
                bill = initial['payables'][0]['bill_id']
                collection_path = '/api/invoices/' + invoice + '/payments'
                payment_path = '/api/bills/' + bill + '/payments'
                collection = {'paidOn': '2026-01-05', 'amount': '40.04'}
                payment = {'paidOn': '2026-01-05', 'amount': '10.01'}
                first_collection = post(collection_path, collection, 'opening-restore-first-collection')
                first_payment = post(payment_path, payment, 'opening-restore-first-payment')
                bank_request = {'startsOn': '2026-01-01', 'endsOn': '2026-01-31',
                    'openingBalance': '1000.25', 'closingBalance': '1000.25'}
                bank = post('/api/bank/reconciliations', bank_request, 'opening-restore-bank')
                saved = evidence()
                assert saved['history']['openingBooks'][0] == original
                assert saved['reports']['receivables']['total'] == '60.06'
                assert saved['reports']['payables']['total'] == '30.03'
                assert saved['reports']['profitLoss']['netProfit'] == '0.00'
                assert saved['reports']['balanceSheet']['difference'] == '0.00'
                assert saved['bank']['closingBalance'] == '1030.28'
                assert saved['statement']['openingBalance'] == '100.10'
                assert saved['statement']['closingBalance'] == '60.06'
                assert saved['earlier']['balanceSheet']['totalAssets'] == '0.00'
                assert saved['earlier']['receivables']['total'] == saved['earlier']['payables']['total'] == '0.00'

                app.stop()
                tool = app.ROOT / 'scripts' / ('postgres_backup.py' if mode == 'postgres' else 'local_backup.py')
                source_file = source if mode == 'postgres' else source + '.mv.db'
                target_file = target if mode == 'postgres' else target + '.mv.db'
                backup = str(folder / 'saved')
                subprocess.run(['python3', str(tool), 'backup', source_file, backup, '--confirm-stopped'], check=True)
                subprocess.run(['python3', str(tool), 'restore', backup, target_file, '--confirm-stopped'], check=True)
                start(target, mode, log)
                assert evidence() == saved
                for user, password in [(app.REVIEWER, app.REVIEWER_PASSWORD), (app.BOOKKEEPER, app.BOOKKEEPER_PASSWORD)]:
                    assert read('/api/opening-books', user, password) == saved['history']
                    for path in ('/api/opening-books', '/api/opening-books/preview'):
                        assert app.write(path, draft, 'opening-restore-role-' + user, user, password)[0] == 403
                # Original keys stay useful after restoration, without a second import or payment.
                assert post('/api/opening-books', draft, 'opening-restore-import') == imported
                assert post(collection_path, collection, 'opening-restore-first-collection') == first_collection
                assert post(payment_path, payment, 'opening-restore-first-payment') == first_payment
                assert post('/api/bank/reconciliations', bank_request, 'opening-restore-bank') == bank
                assert app.write('/api/opening-books', draft, 'opening-restore-duplicate')[0] == 400
                assert app.write('/api/opening-books', {**draft, 'reviewNote': 'Changed review'}, 'opening-restore-import')[0] == 400
                for path in (collection_path, payment_path):
                    assert app.write(path, {'paidOn': '2025-12-31', 'amount': '1.00'}, 'opening-restore-cutoff-' + path)[0] == 400
                    assert app.write(path, {'paidOn': '2026-02-05', 'amount': '1000.00'}, 'opening-restore-overpayment-' + path)[0] == 400
                assert app.write('/api/invoices/' + invoice + '/void', {'date': '2026-02-05'}, 'opening-restore-invoice-void')[0] == 400
                assert app.write('/api/bills/' + bill + '/void', {'date': '2026-02-05'}, 'opening-restore-bill-void')[0] == 400
                assert evidence() == saved

                for path, amount, key in [(collection_path, '60.06', 'collection'), (payment_path, '30.03', 'payment')]:
                    body = {'paidOn': '2026-02-05', 'amount': amount}
                    status, result = app.write(path, body, 'opening-restored-final-' + key, app.BOOKKEEPER, app.BOOKKEEPER_PASSWORD)
                    assert status == 200, (path, status)
                    before_retry = read('/api/state')
                    assert app.write(path, body, 'opening-restored-final-' + key, app.BOOKKEEPER, app.BOOKKEEPER_PASSWORD) == (200, result)
                    assert read('/api/state') == before_retry
                final_history = read('/api/opening-books')
                assert final_history['openingBooks'] == initial['openingBooks']
                assert final_history['receivables'][0]['paid'] == '100.10'
                assert final_history['payables'][0]['paid'] == '40.04'
                final = read('/api/reports?startsOn=2026-02-01&endsOn=2026-02-28')
                assert final['receivables']['total'] == final['payables']['total'] == '0.00'
                assert final['profitLoss']['netProfit'] == final['balanceSheet']['difference'] == '0.00'
                assert final['balanceSheet']['totalEquity'] == '1060.31'
                activity = read('/api/reports/accounts/1000/activity?startsOn=2026-02-01&endsOn=2026-02-28')
                assert activity['closingBalance'] == '1060.31'
                assert read('/api/reports?' + DATES) == saved['reports']
                assert read('/api/reports/customers/demo-customer/statement?' + DATES) == saved['statement']
                assert post('/api/opening-books', draft, 'opening-restore-import') == imported
                assert read('/api/opening-books') == final_history
                print(f'Opening-books {mode} restore verified: original review/source references, partial balances, bank carry-forward, role restrictions and retained retries survive; restored documents settle fully without new operating profit.')
            finally:
                app.stop()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', choices=['h2', 'postgres'], required=True)
    verify(parser.parse_args().database)
