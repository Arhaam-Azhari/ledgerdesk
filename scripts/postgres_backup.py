#!/usr/bin/env python3
"""Create a PostgreSQL archive or restore it into a new database."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from local_backup import digest


def command(tool, *args):
    # CI uses the server's matching client tools inside its PostgreSQL container.
    container = os.environ.get('LEDGERDESK_PG_CONTAINER')
    prefix = ['docker', 'exec', '-i', container] if container else []
    user = os.environ.get('PGUSER')
    return prefix + [tool] + (['--username', user] if user else []) + list(args)


def database_name(value):
    if not re.fullmatch(r'[a-zA-Z_][a-zA-Z0-9_]{0,62}', value):
        raise ValueError('Use a plain database name of at most 63 letters, digits or underscores.')
    return value


def backup(database, destination):
    database_name(database)
    destination = Path(destination).absolute()
    if destination.exists():
        raise ValueError('Backup destination already exists.')
    if not destination.parent.is_dir():
        raise ValueError('Create the backup parent directory first.')
    with tempfile.TemporaryDirectory(prefix='.ledgerdesk-pg-', dir=destination.parent) as folder:
        stage = Path(folder)
        archive = stage / 'database.dump'
        with archive.open('xb') as output:
            archive.chmod(0o600)
            subprocess.run(command('pg_dump', '--format=custom', '--no-owner', '--no-privileges', '--dbname', database), stdout=output, check=True)
        manifest = {'format': 1, 'database': 'database.dump', 'bytes': archive.stat().st_size, 'sha256': digest(archive)}
        (stage / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
        destination.mkdir(mode=0o700)
        try:
            shutil.copyfile(stage / 'manifest.json', destination / 'manifest.json')
            shutil.copyfile(archive, destination / 'database.dump')
            (destination / 'database.dump').chmod(0o600)
        except BaseException:
            shutil.rmtree(destination)
            raise
    return destination


def restore(folder, database):
    database_name(database)
    folder = Path(folder).resolve()
    manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
    if not isinstance(manifest, dict) or manifest.get('format') != 1 or manifest.get('database') != 'database.dump':
        raise ValueError('Unsupported PostgreSQL backup manifest.')
    archive = folder / 'database.dump'
    if not archive.is_file() or archive.is_symlink() or archive.stat().st_size != manifest.get('bytes') or digest(archive) != manifest.get('sha256'):
        raise ValueError('Backup integrity check failed.')
    # Inspect the archive before creating anything on the server.
    with archive.open('rb') as incoming:
        subprocess.run(command('pg_restore', '--list'), stdin=incoming, stdout=subprocess.DEVNULL, check=True)
    subprocess.run(command('createdb', '--maintenance-db=postgres', '--template=template0', database), check=True)
    # Keep a failed target for inspection; never drop a database automatically.
    with archive.open('rb') as incoming:
        subprocess.run(command('pg_restore', '--exit-on-error', '--single-transaction', '--no-owner', '--no-privileges', '--dbname', database), stdin=incoming, check=True)
    return database


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['backup', 'restore'])
    parser.add_argument('source')
    parser.add_argument('destination')
    parser.add_argument('--confirm-stopped', action='store_true')
    args = parser.parse_args()
    if not args.confirm_stopped:
        parser.error('Stop the backend and other writers first, then pass --confirm-stopped.')
    try:
        result = (backup if args.operation == 'backup' else restore)(args.source, args.destination)
    except (OSError, ValueError, TypeError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'Backup/restore did not complete: {error}\n')
    print(f'{args.operation.capitalize()} complete: {result}')


if __name__ == '__main__':
    main()
