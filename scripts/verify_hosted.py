"""Exercise the disposable CI installation over Caddy's local HTTPS certificate."""
import http.cookiejar
from decimal import Decimal
import json
import os
from pathlib import Path
import ssl
import subprocess
import time
import tempfile
from encrypted_backup import encrypt, decrypt
from postgres_backup import backup, restore
import urllib.error
import urllib.parse
import urllib.request

# CI uses localhost's private CA. Public deployments must use a trusted certificate.
BASE = 'https://localhost'
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(
    urllib.request.HTTPSHandler(context=ssl._create_unverified_context()),
    urllib.request.HTTPCookieProcessor(cookies))

def request(path, body=None, form=False):
    headers = {}
    if body is not None:
        _, _, token = request('/api/csrf')
        headers[token['headerName']] = token['token']
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
        if not form:
            headers['Idempotency-Key'] = 'installation-' + path
        body = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
    req = urllib.request.Request(BASE + path, data=body, headers=headers)
    try:
        response = client.open(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        media = response.headers.get_content_type()
        data = json.loads(raw) if media == 'application/json' else (raw if media == 'application/pdf' else raw.decode())
        return response.status, response.headers, data

def post(path, body):
    status, _, data = request(path, body)
    assert status == 200, (path, status, data)
    return data

def read(path):
    status, _, data = request(path)
    assert status == 200, (path, status)
    return data

REPORTS = '/api/reports?startsOn=2026-09-01&endsOn=2026-10-31'

def snapshot():
    return {'state': read('/api/state'), 'reports': read(REPORTS)}

def walkthrough():
    customer = post('/api/customers', {'name': 'Maple Coffee Co.', 'email': 'accounts@maple.example'})['id']
    vendor = post('/api/vendors', {'name': 'Harbor Supply', 'email': 'accounts@harbor.example'})['id']
    invoice = post('/api/invoices', {'customerId': customer, 'description': 'Design work', 'issuedOn': '2026-09-01', 'dueOn': '2026-09-30', 'amount': '1200.00'})['id']
    payment = {'paidOn': '2026-09-03', 'amount': '700.00'}
    payment_path = f'/api/invoices/{invoice}/payments'
    first = post(payment_path, payment)
    assert post(payment_path, payment) == first
    bill = post('/api/bills', {'vendorId': vendor, 'reference': 'SUP-104', 'description': 'Office supplies', 'issuedOn': '2026-10-01', 'dueOn': '2026-10-31', 'accountCode': '5000', 'amount': '600.00'})['id']
    post(f'/api/bills/{bill}/payments', {'paidOn': '2026-10-02', 'amount': '200.00'})
    post('/api/expenses', {'vendorId': vendor, 'description': 'Design software', 'spentOn': '2026-10-03', 'accountCode': '5100', 'amount': '50.00'})
    csv = 'transaction_id,date,description,amount\nHOSTED-1,2026-09-03,Customer payment,700.00\nHOSTED-2,2026-10-02,Bill payment,-200.00\nHOSTED-3,2026-10-03,Software,-50.00\n'
    imported = {'label': 'September-October statement', 'csv': csv}
    assert post('/api/bank/imports/preview', imported)['added'] == '3'
    post('/api/bank/imports', imported)
    for transaction in read('/api/state')['bankTransactions']:
        candidates = read(f"/api/bank/transactions/{transaction['id']}/candidates")
        assert len(candidates) == 1
        post(f"/api/bank/transactions/{transaction['id']}/match", {'lineId': candidates[0]['line_id']})
    statement = {'startsOn': '2026-09-01', 'endsOn': '2026-10-31', 'openingBalance': '0.00', 'closingBalance': '450.00'}
    preview = post('/api/bank/reconciliations/preview', statement)
    assert Decimal(str(preview['bookBalance'])) == Decimal('450.00')
    assert all(Decimal(str(preview[key])) == 0 for key in ('statementDifference', 'bookDifference'))
    assert not preview['unmatchedTransactions']
    post('/api/bank/reconciliations', statement)
    reports = read(REPORTS)
    for key, amount in {'revenue': '1200.00', 'expenses': '650.00', 'netProfit': '550.00'}.items():
        assert Decimal(str(reports['profitLoss'][key])) == Decimal(amount)
    assert Decimal(str(reports['receivables']['total'])) == Decimal('500.00')
    assert Decimal(str(reports['payables']['total'])) == Decimal('400.00')
    assert reports['trialBalance']['debits'] == reports['trialBalance']['credits']
    status, headers, pdf = request(f'/api/invoices/{invoice}/pdf')
    assert status == 200 and pdf.startswith(b'%PDF-') and headers['Cache-Control'] == 'no-store'
    return invoice

def ready():
    for _ in range(60):
        try:
            if request('/api/auth')[2] == {'mode': 'session'}:
                return
        except (OSError, ValueError):
            pass
        time.sleep(2)
    raise AssertionError('Hosted API did not become ready.')

def login():
    password = Path('deploy/secrets/owner-password').read_text().strip()
    assert request('/api/session/login', {'username': os.environ['LEDGERDESK_OWNER'], 'password': password}, form=True)[0] == 200
    session = next(cookie for cookie in cookies if cookie.name == 'JSESSIONID')
    assert session.secure and session.has_nonstandard_attr('HttpOnly')
    assert session.get_nonstandard_attr('SameSite').lower() == 'strict'

def main():
    ready()
    status, _, html = request('/')
    assert status == 200 and 'Ledgerdesk' in html and '/assets/' in html
    assert request('/api/state')[0] == 401
    login()
    assert request('/api/access')[2]['role'] == 'OWNER'
    # Business setup must survive both restart and encrypted database recovery.
    version = request('/api/state')[2]['businessVersion']
    assert request('/api/business', {'name': 'Hosted recovery studio', 'version': version})[0] == 200
    invoice = walkthrough()
    before = snapshot()
    subprocess.run(['docker', 'compose', '-f', 'compose.hosted.yaml', 'restart', 'api'], check=True, stdout=subprocess.DEVNULL)
    ready()
    assert request('/api/state')[0] == 401
    login()
    assert snapshot() == before
    # Stop the sole writer, then recover the encrypted archive into a fresh database.
    subprocess.run(['docker', 'compose', '-f', 'compose.hosted.yaml', 'stop', 'api'], check=True, stdout=subprocess.DEVNULL)
    container = subprocess.check_output(['docker', 'compose', '-f', 'compose.hosted.yaml', 'ps', '-q', 'database'], text=True).strip()
    os.environ['LEDGERDESK_PG_CONTAINER'] = container
    os.environ['PGUSER'] = 'ledgerdesk'
    with tempfile.TemporaryDirectory(prefix='ledgerdesk-hosted-recovery-') as temp:
        folder = Path(temp)
        identity = folder / 'identity'
        subprocess.run(['age-keygen', '-o', str(identity)], check=True, stderr=subprocess.DEVNULL)
        recipients = folder / 'recipients'
        recipients.write_bytes(subprocess.check_output(['age-keygen', '-y', str(identity)]))
        saved = backup('ledgerdesk', folder / 'original')
        encrypted = encrypt(saved, folder / 'backup.age', recipients)
        checked = decrypt(encrypted, folder / 'checked', identity)
        restore(checked, 'restored_installation')
        override = folder / 'recovery.json'
        override.write_text(json.dumps({'services': {'api': {'environment': {'DATABASE_URL': 'jdbc:postgresql://database:5432/restored_installation'}}}}))
        subprocess.run(['docker', 'compose', '-f', 'compose.hosted.yaml', '-f', str(override), 'up', '-d', '--no-deps', '--no-build', 'api'], check=True, stdout=subprocess.DEVNULL)
        ready()
        assert request('/api/state')[0] == 401
        login()
        assert snapshot() == before
        assert read(f'/api/invoices/{invoice}/pdf').startswith(b'%PDF-')
    assert request('/api/session/logout', {})[0] == 200
    assert request('/api/state')[0] == 401
    print('Hosted setup, invoice/partial payment, bill/expense, matched bank close, reports/PDF, restart, encrypted recovery and logout verified.')

if __name__ == '__main__':
    main()
