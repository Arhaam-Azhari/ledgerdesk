"""Exercise the disposable CI installation over Caddy's local HTTPS certificate."""
import http.cookiejar
import json
import os
from pathlib import Path
import ssl
import subprocess
import time
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
            headers['Idempotency-Key'] = 'installation-vendor'
        body = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
    req = urllib.request.Request(BASE + path, data=body, headers=headers)
    try:
        response = client.open(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        data = json.loads(raw) if response.headers.get_content_type() == 'application/json' else raw.decode()
        return response.status, response.headers, data

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
    name = 'Hosted installation supplier'
    assert request('/api/vendors', {'name': name, 'email': 'supplier@example.test'})[0] == 200
    before = request('/api/state')[2]
    subprocess.run(['docker', 'compose', '-f', 'compose.hosted.yaml', 'restart', 'api'], check=True, stdout=subprocess.DEVNULL)
    ready()
    assert request('/api/state')[0] == 401
    login()
    assert request('/api/state')[2] == before
    assert request('/api/session/logout', {})[0] == 200
    assert request('/api/state')[0] == 401
    print('Hosted HTTPS sign-in, secure cookie, private reads, restart persistence and logout verified.')

if __name__ == '__main__':
    main()
