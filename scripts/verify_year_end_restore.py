"""Restore populated earnings history through the packaged backend on H2 or PostgreSQL."""
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

SOURCE = 'earnings_backup_source'
TARGET = 'earnings_backup_restored'
DATES = 'startsOn=2026-01-01&endsOn=2026-12-31'


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
            raise RuntimeError('Backend exited; inspect the year-end restore log.')
        try:
            with app.opener.open(app.BASE + '/api/csrf', timeout=3) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.3)
    raise RuntimeError('Year-end restore backend readiness timed out.')


def read(path, user=app.OWNER, password=app.PASSWORD):
    status, result = app.api(path, user, password)
    assert status == 200, (path, status)
    return result


def post(path, data, key):
    status, result = app.write(path, data, key)
    assert status == 200, (path, status)
    return result


def evidence():
    # History is a separate endpoint, so a workspace comparison alone misses it.
    return {
        'history': read('/api/year-end'),
        'state': read('/api/state'),
        'reports': read('/api/reports?' + DATES),
        'earlier': read('/api/reports?startsOn=2026-01-01&endsOn=2026-12-30'),
        'cash': read('/api/reports/cash-activity?' + DATES),
        'retained': read('/api/reports/accounts/3300/activity?' + DATES),
        'preview': read('/api/year-end/preview?year=2026'),
    }


def verify(mode):
    if mode == 'postgres':
        # Only new disposable CI names are used; never drop an existing database.
        subprocess.run(command('createdb', '--maintenance-db=postgres', SOURCE), check=True)
    with tempfile.TemporaryDirectory(prefix='ledgerdesk-year-end-restore-') as folder:
        folder = Path(folder)
        source = SOURCE if mode == 'postgres' else str(folder / 'source')
        target = TARGET if mode == 'postgres' else str(folder / 'restored')
        with (app.ROOT / f'year-end-{mode}-restore.log').open('w') as log:
            try:
                start(source, mode, log)
                post('/api/accounts', {'username': app.BOOKKEEPER,
                     'password': app.BOOKKEEPER_PASSWORD, 'role': 'BOOKKEEPER'}, 'earnings-account')
                post('/api/opening-bank-balance', {'asOf': '2025-12-31',
                     'balance': '1000.25', 'memo': 'Cleared opening'}, 'earnings-opening')
                vendor = post('/api/vendors', {'name': 'Harbor', 'email': 'accounts@harbor.example'}, 'earnings-vendor')
                post('/api/invoices', {'customerId': 'demo-customer', 'description': 'Design',
                     'issuedOn': '2026-01-01', 'dueOn': '2026-01-15', 'amount': '100.10'}, 'earnings-sale')
                post('/api/bills', {'vendorId': vendor['id'], 'reference': 'YEAR-END',
                     'description': 'Supplies', 'issuedOn': '2026-01-01', 'dueOn': '2026-01-15',
                     'accountCode': '5000', 'amount': '40.04'}, 'earnings-bill')
                bank = post('/api/bank/reconciliations', {'startsOn': '2026-01-01',
                     'endsOn': '2026-12-31', 'openingBalance': '1000.25',
                     'closingBalance': '1000.25'}, 'earnings-bank')
                period = post('/api/accounting-periods', {'endsOn': '2026-12-31',
                     'reviewNote': 'Reviewed supporting annual reports'}, 'earnings-period')
                before_close = read('/api/reports?' + DATES)
                assert before_close['profitLoss']['netProfit'] == '60.06'
                close = {'year': 2026, 'reviewNote': 'Reviewed earnings before recovery exercise'}
                reason = {'version': 1, 'reason': 'Recheck the retained annual proposal'}
                first = post('/api/year-end', close, 'earnings-first')
                original = read('/api/year-end')['yearEndCloses'][0]
                post('/api/year-end/' + first['id'] + '/reopen', reason, 'earnings-first-reopen')
                active = post('/api/year-end', close, 'earnings-active')
                saved = evidence()
                rows = saved['history']['yearEndCloses']
                assert len(rows) == 2 and sum(row['status'] == 'CLOSED' for row in rows) == 1
                old = next(row for row in rows if row['id'] == first['id'])
                current = next(row for row in rows if row['id'] == active['id'])
                assert old['status'] == 'REOPENED' and int(old['version']) == 2
                assert old['snapshot'] == original['snapshot'] and old['entry_id'] == original['entry_id']
                assert old['reversal_entry_id'] and old['reopen_reason'] == reason['reason']
                assert current['status'] == 'CLOSED' and int(current['version']) == 1
                assert current['accounting_period_id'] == period['id'] and current['bank_reconciliation_id'] == bank['id']
                assert not saved['preview']['ready']
                assert saved['reports']['profitLoss'] == before_close['profitLoss']
                assert saved['reports']['balanceSheet']['difference'] == '0.00'
                assert saved['reports']['balanceSheet']['totalEquity'] == before_close['balanceSheet']['totalEquity']
                assert saved['retained']['closingBalance'] == '-60.06'

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
                    assert read('/api/year-end', user, password) == saved['history']
                    for path, body in [('/api/year-end', close), ('/api/year-end/' + active['id'] + '/reopen', reason)]:
                        assert app.write(path, body, 'earnings-role-blocked-' + user, user, password)[0] == 403
                # Old keys must return their original IDs without reopening the replacement.
                assert post('/api/year-end', close, 'earnings-first') == first
                assert post('/api/year-end/' + first['id'] + '/reopen', reason, 'earnings-first-reopen') == first
                assert post('/api/year-end', close, 'earnings-active') == active
                assert app.write('/api/year-end', close, 'earnings-duplicate')[0] == 400
                assert app.write('/api/year-end', {**close, 'reviewNote': 'Different review'}, 'earnings-active')[0] == 400
                assert app.write('/api/year-end/' + active['id'] + '/reopen', {**reason, 'version': 2}, 'earnings-stale')[0] == 400
                assert app.write('/api/accounting-periods/' + period['id'] + '/reopen', reason, 'earnings-protected-period')[0] == 400
                assert app.write('/api/equity', {'kind': 'CONTRIBUTION', 'postedOn': '2026-12-31',
                     'memo': 'Blocked closed-date work', 'amount': '1.00'}, 'earnings-protected-date')[0] == 400
                assert evidence() == saved

                # An intentional reopen creates one reversal; retries add no lines or audit.
                path = '/api/year-end/' + active['id'] + '/reopen'
                post(path, reason, 'earnings-restored-reopen')
                reopened = evidence()
                record = next(row for row in reopened['history']['yearEndCloses'] if row['id'] == active['id'])
                assert record['status'] == 'REOPENED' and int(record['version']) == 2
                assert record['snapshot'] == current['snapshot'] and record['entry_id'] == current['entry_id']
                assert record['reversal_entry_id'] and record['reopened_by'] == app.OWNER
                assert record['reopen_reason'] == reason['reason']
                closing_lines = [row for row in saved['state']['ledger'] if row['entry_id'] == current['entry_id']]
                reversed_lines = [row for row in reopened['state']['ledger'] if row['entry_id'] == record['reversal_entry_id']]
                assert len(reversed_lines) == len(closing_lines) == 3
                for line in closing_lines:
                    reversal = next(row for row in reversed_lines if row['code'] == line['code'])
                    assert reversal['debit'] == line['credit'] and reversal['credit'] == line['debit']
                    assert reversal['source_id'] == active['id']
                assert reopened['reports'] == before_close and reopened['earlier'] == saved['earlier']
                assert reopened['cash'] == saved['cash'] and reopened['retained']['closingBalance'] == '0.00'
                assert reopened['preview']['ready']
                assert reopened['state']['accountingPeriodCloses'] == saved['state']['accountingPeriodCloses']
                assert reopened['state']['bankReconciliations'] == saved['state']['bankReconciliations']
                assert post(path, reason, 'earnings-restored-reopen') == active
                assert post('/api/year-end', close, 'earnings-active') == active
                assert evidence() == reopened
                replacement = post('/api/year-end', close, 'earnings-restored-replacement')
                assert replacement['id'] not in [first['id'], active['id']]
                final = evidence()
                assert len(final['history']['yearEndCloses']) == 3
                assert sum(row['status'] == 'CLOSED' for row in final['history']['yearEndCloses']) == 1
                for old_row in reopened['history']['yearEndCloses']:
                    assert next(row for row in final['history']['yearEndCloses'] if row['id'] == old_row['id']) == old_row
                assert final['reports'] == saved['reports'] and final['retained']['closingBalance'] == '-60.06'
                assert post('/api/year-end', close, 'earnings-restored-replacement') == replacement
                assert evidence() == final
                print(f'Year-end {mode} restore verified: exact populated history/workspace/reports, stored role reads and write denials, retained keys, closed-date/period protection, one intentional reversal, unchanged original snapshots, and replacement close without duplicates.')
            finally:
                app.stop()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', choices=['h2', 'postgres'], required=True)
    verify(parser.parse_args().database)
