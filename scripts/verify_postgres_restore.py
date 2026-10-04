"""Exercise backup/restore against isolated CI databases and the packaged backend."""
import http.cookiejar
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import base64
from postgres_backup import command

ROOT = Path(__file__).resolve().parent.parent
BASE = 'http://127.0.0.1:8093'
SOURCE = 'backup_source'
TARGET = 'backup_restored'
OWNER = 'backup-owner'
BOOTSTRAP_PASSWORD = 'stored-owner-password'
PASSWORD = 'self-changed-owner-password'
REVIEWER = 'backup-reviewer'
BOOTSTRAP_REVIEWER_PASSWORD = 'stored-reviewer-password'
REVIEWER_PASSWORD = 'self-changed-reviewer-password'
BOOKKEEPER = 'backup-bookkeeper'
INITIAL_BOOKKEEPER_PASSWORD = 'stored-bookkeeper-password'
BOOKKEEPER_PASSWORD = 'self-changed-bookkeeper-password'
server = None
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def api(path, user=OWNER, password=PASSWORD, data=None, headers=None):
    auth = base64.b64encode(f'{user}:{password}'.encode()).decode()
    request = urllib.request.Request(BASE + path, data=None if data is None else data if isinstance(data, bytes) else json.dumps(data).encode(), headers={'Authorization': 'Basic ' + auth, 'Content-Type': 'application/json', **(headers or {})})
    try:
        with opener.open(request, timeout=3) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, None


def stop():
    global server
    if server is not None and server.poll() is None:
        server.terminate()
        try:
            server.wait(timeout=20)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
            raise RuntimeError('Backend did not stop gracefully.')


def start(database, log):
    global server
    settings = {
        'server': {'port': 8093, 'address': '127.0.0.1'},
        'spring': {'datasource': {'url': f'jdbc:postgresql://127.0.0.1:5432/{database}', 'username': os.environ['PGUSER'], 'password': os.environ['DATABASE_PASSWORD']}},
        'app': {'accounts': {'persistent': True}, 'username': OWNER, 'password': BOOTSTRAP_PASSWORD if database == SOURCE else 'changed-bootstrap-password', 'reviewer': {'username': REVIEWER, 'password': BOOTSTRAP_REVIEWER_PASSWORD}},
    }
    server = subprocess.Popen(['java', '-jar', 'target/ledgerdesk-0.1.0.jar'], cwd=ROOT / 'backend', env={**os.environ, 'SPRING_APPLICATION_JSON': json.dumps(settings)}, stdout=log, stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if server.poll() is not None:
            raise RuntimeError('Backend exited before readiness; inspect the CI backend log.')
        try:
            if api('/api/csrf')[0] == 200:
                return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.3)
    raise RuntimeError('Backend readiness timed out.')


def write(path, data, key, user=OWNER, password=PASSWORD):
    status, csrf = api('/api/csrf', user, password)
    assert status == 200
    return api(path, user, password, data, {csrf['headerName']: csrf['token'], 'Idempotency-Key': key})


def download(receipt_id, user=OWNER, password=PASSWORD):
    auth = base64.b64encode(f'{user}:{password}'.encode()).decode()
    headers = {} if user is None else {'Authorization': 'Basic ' + auth}
    try:
        with opener.open(urllib.request.Request(BASE + '/api/receipts/' + receipt_id, headers=headers), timeout=3) as response:
            return response.status, response.read(), {key: response.headers[key] for key in ['Content-Type', 'Content-Disposition', 'X-Content-Type-Options', 'Cache-Control']}
    except urllib.error.HTTPError as error:
        return error.code, None, None


def upload(path, name, media_type, content, key, user=OWNER, password=PASSWORD):
    status, csrf = api('/api/csrf', user, password)
    assert status == 200
    boundary = 'ledgerdesk-restore-fixture'
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: {media_type}\r\n\r\n'.encode() + content + f'\r\n--{boundary}--\r\n'.encode())
    return api(path, user, password, body, {csrf['headerName']: csrf['token'], 'Idempotency-Key': key, 'Content-Type': 'multipart/form-data; boundary=' + boundary})


def main():
    # These names belong to the disposable CI service; existing names are refused.
    subprocess.run(command('createdb', '--maintenance-db=postgres', SOURCE), check=True)
    with tempfile.TemporaryDirectory(prefix='ledgerdesk-pg-proof-') as folder:
        backup = Path(folder) / 'saved'
        with (ROOT / 'postgres-restore-backend.log').open('w') as log:
            try:
                start(SOURCE, log)
                status, bookkeeper_account = write('/api/accounts', {'username': BOOKKEEPER, 'password': INITIAL_BOOKKEEPER_PASSWORD, 'role': 'BOOKKEEPER'}, 'pg-bookkeeper-account', OWNER, BOOTSTRAP_PASSWORD)
                assert status == 200
                # Exercise the actual self-service route before any accounting work.
                for user, initial, replacement, role in [(OWNER, BOOTSTRAP_PASSWORD, PASSWORD, 'OWNER'), (REVIEWER, BOOTSTRAP_REVIEWER_PASSWORD, REVIEWER_PASSWORD, 'REVIEWER'), (BOOKKEEPER, INITIAL_BOOKKEEPER_PASSWORD, BOOKKEEPER_PASSWORD, 'BOOKKEEPER')]:
                    assert write('/api/me/password', {'currentPassword': initial, 'password': replacement}, 'unused-password-key', user, initial)[0] == 200
                    assert api('/api/access', user, initial)[0] == 401
                    status, identity = api('/api/access', user, replacement)
                    assert status == 200 and identity['role'] == role
                status, accounts_before = api('/api/accounts')
                assert status == 200
                status, vendor = write('/api/vendors', {'name': 'Restored supplier', 'email': 'supplier@example.test'}, 'pg-backup-vendor', BOOKKEEPER, BOOKKEEPER_PASSWORD)
                assert status == 200
                opening = {'asOf': '2026-09-30', 'balance': '1000.25', 'memo': 'Cleared opening retained in PostgreSQL backup'}
                status, original_opening = write('/api/opening-bank-balance', opening, 'pg-backup-opening')
                assert status == 200
                transfer = {'kind': 'CONTRIBUTION', 'postedOn': '2026-10-01', 'memo': 'PostgreSQL restore proof', 'amount': '125.37'}
                status, original = write('/api/equity', transfer, 'pg-backup-contribution')
                assert status == 200
                status, bill = write('/api/bills', {'vendorId': vendor['id'], 'reference': 'RESTORE-40', 'description': 'Supplies with receipt', 'issuedOn': '2026-10-01', 'dueOn': '2026-10-15', 'accountCode': '5000', 'amount': '40.00'}, 'pg-restore-bill')
                assert status == 200
                status, expense = write('/api/expenses', {'vendorId': vendor['id'], 'description': 'Software with receipt', 'spentOn': '2026-10-01', 'accountCode': '5100', 'amount': '25.00'}, 'pg-restore-expense')
                assert status == 200
                attachments = []
                for path, name, media_type, key in [(f"/api/bills/{bill['id']}/receipts", 'supply-receipt.png', 'image/png', 'pg-bill-receipt'), (f"/api/expenses/{expense['id']}/receipts", 'software-receipt.jpg', 'image/jpeg', 'pg-expense-receipt'), (f"/api/expenses/{expense['id']}/receipts", 'software-receipt.pdf', 'application/pdf', 'pg-pdf-receipt')]:
                    content = (ROOT / 'frontend/tests/fixtures' / name).read_bytes()
                    status, receipt = upload(path, name, media_type, content, key)
                    assert status == 200
                    stored = download(receipt['id'])
                    assert stored[0] == 200
                    if media_type == 'application/pdf':
                        assert stored[1] == content
                    attachments.append((path, name, media_type, key, content, receipt['id'], stored))
                statement = {'startsOn': '2026-10-01', 'endsOn': '2026-10-31', 'openingBalance': '1000.25', 'closingBalance': '1000.25'}
                status, bank_record = write('/api/bank/reconciliations', statement, 'pg-backup-statement')
                assert status == 200
                period = {'endsOn': '2026-10-31', 'reviewNote': 'Reviewed reports before PostgreSQL backup'}
                status, first_period = write('/api/accounting-periods', period, 'pg-period-first')
                assert status == 200
                reopening = {'version': 1, 'reason': 'Second review before backup'}
                assert write('/api/accounting-periods/' + first_period['id'] + '/reopen', reopening, 'pg-period-first-reopen')[0] == 200
                status, active_period = write('/api/accounting-periods', period, 'pg-period-active')
                assert status == 200
                status, cash_before = api('/api/reports/cash-activity?startsOn=2026-10-01&endsOn=2026-10-31')
                assert status == 200 and cash_before['openingCash'] == '1000.25' and cash_before['closingCash'] == '1100.62'
                assert cash_before['receipts'] == '125.37' and cash_before['payments'] == '25.00'
                status, reports_before = api('/api/reports?startsOn=2026-10-01&endsOn=2026-10-31')
                assert status == 200 and reports_before['profitLoss']['netProfit'] == '-65.00'
                assert reports_before['balanceSheet']['totalAssets'] == '1100.62'
                assert reports_before['balanceSheet']['totalEquity'] == '1060.62'
                status, before = api('/api/state')
                assert status == 200 and len(before['equityTransactions']) == 1 and len(before['ledger']) == 8 and len(before['openingBankBalances']) == 1 and len(before['bankReconciliations']) == 1 and len(before['receipts']) == 3 and len(before['accountingPeriodCloses']) == 2
                for user in [OWNER, REVIEWER, BOOKKEEPER]:
                    assert len([row for row in before['audit'] if row['actor'] == user and row['action'] == 'ACCOUNT_SELF_PASSWORD_CHANGED']) == 1
                assert all(secret not in json.dumps(before) for secret in [BOOTSTRAP_PASSWORD, PASSWORD, BOOTSTRAP_REVIEWER_PASSWORD, REVIEWER_PASSWORD, INITIAL_BOOKKEEPER_PASSWORD, BOOKKEEPER_PASSWORD])
                stop()
                start(SOURCE, log)
                # Old bootstrap settings cannot reset self-changed stored passwords.
                for user, initial, replacement, role in [(OWNER, BOOTSTRAP_PASSWORD, PASSWORD, 'OWNER'), (REVIEWER, BOOTSTRAP_REVIEWER_PASSWORD, REVIEWER_PASSWORD, 'REVIEWER'), (BOOKKEEPER, INITIAL_BOOKKEEPER_PASSWORD, BOOKKEEPER_PASSWORD, 'BOOKKEEPER')]:
                    assert api('/api/access', user, initial)[0] == 401
                    status, identity = api('/api/access', user, replacement)
                    assert status == 200 and identity['role'] == role
                assert api('/api/state')[1] == before
                assert api('/api/accounts')[1] == accounts_before
                stop()
                tool = ROOT / 'scripts/postgres_backup.py'
                subprocess.run(['python3', str(tool), 'backup', SOURCE, str(backup), '--confirm-stopped'], check=True)
                subprocess.run(['python3', str(tool), 'restore', str(backup), TARGET, '--confirm-stopped'], check=True)
                start(TARGET, log)
                assert api('/api/access', password='changed-bootstrap-password')[0] == 401
                for user, initial in [(OWNER, BOOTSTRAP_PASSWORD), (REVIEWER, BOOTSTRAP_REVIEWER_PASSWORD), (BOOKKEEPER, INITIAL_BOOKKEEPER_PASSWORD)]:
                    assert api('/api/access', user, initial)[0] == 401
                status, owner = api('/api/access')
                assert status == 200 and owner['canWrite']
                status, reviewer = api('/api/access', REVIEWER, REVIEWER_PASSWORD)
                assert status == 200 and not reviewer['canWrite']
                assert api('/api/state')[1] == before
                status, bookkeeper = api('/api/access', BOOKKEEPER, BOOKKEEPER_PASSWORD)
                assert status == 200 and bookkeeper['role'] == 'BOOKKEEPER' and bookkeeper['canWrite']
                assert api('/api/access', BOOKKEEPER, 'wrong-bookkeeper-password')[0] == 401
                assert api('/api/accounts')[1] == accounts_before
                assert api('/api/accounts', BOOKKEEPER, BOOKKEEPER_PASSWORD)[0] == 403
                assert api('/api/state', BOOKKEEPER, BOOKKEEPER_PASSWORD)[1] == before
                assert write('/api/vendors', {'name': 'Restored supplier', 'email': 'supplier@example.test'}, 'pg-backup-vendor', BOOKKEEPER, BOOKKEEPER_PASSWORD)[1] == vendor
                for blocked in ['/api/accounts', '/api/opening-bank-balance', '/api/equity', '/api/accounting-periods', '/api/bank/reconciliations/' + bank_record['id'] + '/reopen']:
                    assert write(blocked, {}, 'pg-bookkeeper-blocked', BOOKKEEPER, BOOKKEEPER_PASSWORD)[0] == 403
                assert write('/api/expenses', {'vendorId': vendor['id'], 'description': 'Closed-date bookkeeper expense', 'spentOn': '2026-10-15', 'accountCode': '5100', 'amount': '1.00'}, 'pg-bookkeeper-closed', BOOKKEEPER, BOOKKEEPER_PASSWORD)[0] == 400
                assert api('/api/state')[1] == before
                for path, name, media_type, key, content, receipt_id, stored in attachments:
                    assert download(receipt_id) == stored
                    assert download(receipt_id, REVIEWER, REVIEWER_PASSWORD) == stored
                    assert download(receipt_id, BOOKKEEPER, BOOKKEEPER_PASSWORD) == stored
                    assert download(receipt_id, None)[0] == 401
                    assert upload(path, name, media_type, content, key)[1]['id'] == receipt_id
                    assert upload(path, name, media_type, content, 'blocked-' + key, REVIEWER, REVIEWER_PASSWORD)[0] == 403
                assert api('/api/state')[1] == before
                assert write('/api/vendors', {'name': 'Blocked supplier', 'email': ''}, 'pg-reviewer-blocked', REVIEWER, REVIEWER_PASSWORD)[0] == 403
                assert write('/api/equity', transfer, 'pg-backup-contribution')[1] == original
                assert api('/api/state')[1] == before
                assert write('/api/opening-bank-balance', opening, 'pg-backup-opening')[1] == original_opening
                assert write('/api/opening-bank-balance', opening, 'pg-second-opening')[0] == 400
                assert write('/api/opening-bank-balance', opening, 'pg-reviewer-opening', REVIEWER, REVIEWER_PASSWORD)[0] == 403
                assert write('/api/equity', {**transfer, 'postedOn': '2026-09-30'}, 'pg-cutover-post')[0] == 400
                assert api('/api/reports/cash-activity?startsOn=2026-09-30&endsOn=2026-10-31')[0] == 400
                assert api('/api/reports/cash-activity?startsOn=2026-10-01&endsOn=2026-10-31')[1] == cash_before
                assert api('/api/reports?startsOn=2026-10-01&endsOn=2026-10-31')[1] == reports_before
                # Preview continuity without changing the restored close or its snapshot.
                next_statement = {**statement, 'startsOn': '2026-11-01', 'endsOn': '2026-11-30'}
                status, next_preview = write('/api/bank/reconciliations/preview', next_statement, 'pg-restored-preview')
                assert status == 200 and next_preview['bookDifference'] == '0.00'
                assert next_preview['outstandingDeposits'] == '125.37' and next_preview['outstandingPayments'] == '25.00'
                assert api('/api/state')[1] == before
                assert write('/api/accounting-periods', period, 'pg-period-first')[1] == first_period
                assert write('/api/accounting-periods', period, 'pg-period-active')[1] == active_period
                assert write('/api/accounting-periods/' + first_period['id'] + '/reopen', reopening, 'pg-period-first-reopen')[1] == first_period
                assert write('/api/accounting-periods', period, 'pg-period-duplicate')[0] == 400
                assert write('/api/accounting-periods/' + active_period['id'] + '/reopen', reopening, 'pg-period-reviewer', REVIEWER, REVIEWER_PASSWORD)[0] == 403
                assert write('/api/bank/reconciliations/' + bank_record['id'] + '/reopen', reopening, 'pg-protected-bank')[0] == 400
                assert write('/api/equity', {**transfer, 'postedOn': '2026-10-15'}, 'pg-protected-period')[0] == 400
                status, reviewer_history = api('/api/accounting-periods', REVIEWER, REVIEWER_PASSWORD)
                assert status == 200 and reviewer_history['accountingPeriodCloses'] == before['accountingPeriodCloses']
                assert api('/api/state')[1] == before
                # A second restore must refuse the existing target, preserving its books.
                rejected = subprocess.run(['python3', str(tool), 'restore', str(backup), TARGET, '--confirm-stopped'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                assert rejected.returncode != 0
                assert api('/api/state')[1] == before
                # The retained current version still permits an intentional owner reopen.
                path = '/api/accounting-periods/' + active_period['id'] + '/reopen'
                assert write(path, reopening, 'pg-restored-period-reopen')[0] == 200
                after_reopen = api('/api/state')[1]
                assert write(path, reopening, 'pg-restored-period-reopen')[1] == active_period
                assert write('/api/accounting-periods', period, 'pg-period-active')[1] == active_period
                assert api('/api/state')[1] == after_reopen
                assert after_reopen['ledger'] == before['ledger']
                for old in before['accountingPeriodCloses']:
                    current = next(row for row in after_reopen['accountingPeriodCloses'] if row['id'] == old['id'])
                    assert current['snapshot'] == old['snapshot'] and current['status'] == 'REOPENED'
                # New work is allowed after restoration, with the retained role and actor.
                new_vendor = {'name': 'Post-restore bookkeeper supplier', 'email': 'new@example.test'}
                status, new_result = write('/api/vendors', new_vendor, 'pg-bookkeeper-new', BOOKKEEPER, BOOKKEEPER_PASSWORD)
                assert status == 200
                after_new = api('/api/state')[1]
                assert write('/api/vendors', new_vendor, 'pg-bookkeeper-new', BOOKKEEPER, BOOKKEEPER_PASSWORD)[1] == new_result
                assert api('/api/state')[1] == after_new
                assert after_new['ledger'] == before['ledger']
                assert any(row['actor'] == BOOKKEEPER and row['record_id'] == new_result['id'] for row in after_new['audit'])
                assert write('/api/accounts/' + bookkeeper_account['id'] + '/access', {'role': 'REVIEWER', 'enabled': True}, 'pg-bookkeeper-demote')[0] == 200
                assert write('/api/vendors', new_vendor, 'pg-bookkeeper-demoted', BOOKKEEPER, BOOKKEEPER_PASSWORD)[0] == 403
                assert write('/api/accounts/' + bookkeeper_account['id'] + '/access', {'role': 'BOOKKEEPER', 'enabled': False}, 'pg-bookkeeper-disable')[0] == 200
                assert api('/api/state', BOOKKEEPER, BOOKKEEPER_PASSWORD)[0] == 401
                print('PostgreSQL 17 restore verified: all three self-changed passwords survive source restart and restore with old passwords rejected; bookkeeper identity, account IDs, reads, routine retry/posting and denied owner actions; stored roles, exact workspace, PNG/JPEG/PDF receipt bytes and headers, opening balance, closed statement and retained accounting close/reopen history, dated reports, protected writes and retained retries.')
            finally:
                stop()


if __name__ == '__main__':
    main()
