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
PASSWORD = 'stored-owner-password'
REVIEWER = 'backup-reviewer'
REVIEWER_PASSWORD = 'stored-reviewer-password'
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
        'app': {'accounts': {'persistent': True}, 'username': OWNER, 'password': PASSWORD if database == SOURCE else 'changed-bootstrap-password', 'reviewer': {'username': REVIEWER, 'password': REVIEWER_PASSWORD}},
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
                status, vendor = write('/api/vendors', {'name': 'Restored supplier', 'email': 'supplier@example.test'}, 'pg-backup-vendor')
                assert status == 200
                transfer = {'kind': 'CONTRIBUTION', 'postedOn': '2026-10-01', 'memo': 'PostgreSQL restore proof', 'amount': '125.37'}
                status, original = write('/api/equity', transfer, 'pg-backup-contribution')
                assert status == 200
                status, bill = write('/api/bills', {'vendorId': vendor['id'], 'reference': 'RESTORE-40', 'description': 'Supplies with receipt', 'issuedOn': '2026-10-01', 'dueOn': '2026-10-15', 'accountCode': '5000', 'amount': '40.00'}, 'pg-restore-bill')
                assert status == 200
                status, expense = write('/api/expenses', {'vendorId': vendor['id'], 'description': 'Software with receipt', 'spentOn': '2026-10-01', 'accountCode': '5100', 'amount': '25.00'}, 'pg-restore-expense')
                assert status == 200
                attachments = []
                for path, name, media_type, key in [(f"/api/bills/{bill['id']}/receipts", 'supply-receipt.png', 'image/png', 'pg-bill-receipt'), (f"/api/expenses/{expense['id']}/receipts", 'software-receipt.jpg', 'image/jpeg', 'pg-expense-receipt')]:
                    content = (ROOT / 'frontend/tests/fixtures' / name).read_bytes()
                    status, receipt = upload(path, name, media_type, content, key)
                    assert status == 200
                    stored = download(receipt['id'])
                    assert stored[0] == 200
                    attachments.append((path, name, media_type, key, content, receipt['id'], stored))
                status, before = api('/api/state')
                assert status == 200 and len(before['equityTransactions']) == 1 and len(before['ledger']) == 6 and len(before['receipts']) == 2
                stop()
                tool = ROOT / 'scripts/postgres_backup.py'
                subprocess.run(['python3', str(tool), 'backup', SOURCE, str(backup), '--confirm-stopped'], check=True)
                subprocess.run(['python3', str(tool), 'restore', str(backup), TARGET, '--confirm-stopped'], check=True)
                start(TARGET, log)
                assert api('/api/access', password='changed-bootstrap-password')[0] == 401
                status, owner = api('/api/access')
                assert status == 200 and owner['canWrite']
                status, reviewer = api('/api/access', REVIEWER, REVIEWER_PASSWORD)
                assert status == 200 and not reviewer['canWrite']
                assert api('/api/state')[1] == before
                for path, name, media_type, key, content, receipt_id, stored in attachments:
                    assert download(receipt_id) == stored
                    assert download(receipt_id, REVIEWER, REVIEWER_PASSWORD) == stored
                    assert download(receipt_id, None)[0] == 401
                    assert upload(path, name, media_type, content, key)[1]['id'] == receipt_id
                    assert upload(path, name, media_type, content, 'blocked-' + key, REVIEWER, REVIEWER_PASSWORD)[0] == 403
                assert api('/api/state')[1] == before
                assert write('/api/vendors', {'name': 'Blocked supplier', 'email': ''}, 'pg-reviewer-blocked', REVIEWER, REVIEWER_PASSWORD)[0] == 403
                assert write('/api/equity', transfer, 'pg-backup-contribution')[1] == original
                assert api('/api/state')[1] == before
                # A second restore must refuse the existing target, preserving its books.
                rejected = subprocess.run(['python3', str(tool), 'restore', str(backup), TARGET, '--confirm-stopped'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                assert rejected.returncode != 0
                assert api('/api/state')[1] == before
                print('PostgreSQL 17 restore verified: stored roles, exact workspace, PNG/JPEG receipt bytes and headers, protected writes and retained retries.')
            finally:
                stop()


if __name__ == '__main__':
    main()
