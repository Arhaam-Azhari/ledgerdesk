#!/usr/bin/env python3
"""Back up a stopped local H2 database, or restore it to a new location."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tempfile


def digest(path):
    checksum = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            checksum.update(block)
    return checksum.hexdigest()


def database_file(path):
    path = Path(path).absolute()
    if not path.name.endswith('.mv.db') or not path.is_file() or path.is_symlink():
        raise ValueError('Choose an existing H2 .mv.db file.')
    lock = path.with_name(path.name[:-6] + '.lock.db')
    if lock.exists():
        raise ValueError('A database lock file exists. Stop the backend first.')
    return path


def backup(source, destination):
    source = database_file(source)
    destination = Path(destination).absolute()
    if destination.exists():
        raise ValueError('Backup destination already exists.')
    if not destination.parent.is_dir():
        raise ValueError('Create the backup parent directory first.')
    before = source.stat()
    original_hash = digest(source)
    # Build beside the destination so publication stays on the same filesystem.
    with tempfile.TemporaryDirectory(prefix='.ledgerdesk-backup-', dir=destination.parent) as folder:
        stage = Path(folder)
        copied = stage / 'database.mv.db'
        shutil.copyfile(source, copied)
        copied.chmod(0o600)
        after = source.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns) or digest(copied) != original_hash or digest(source) != original_hash:
            raise ValueError('Database changed during backup. Stop the backend and retry.')
        manifest = {'format': 1, 'database': 'database.mv.db', 'bytes': copied.stat().st_size, 'sha256': original_hash}
        (stage / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
        # mkdir refuses an existing backup instead of replacing it.
        destination.mkdir(mode=0o700)
        try:
            shutil.copyfile(stage / 'manifest.json', destination / 'manifest.json')
            shutil.copyfile(copied, destination / 'database.mv.db')
            (destination / 'database.mv.db').chmod(0o600)
        except BaseException:
            shutil.rmtree(destination)
            raise
    return destination


def restore(backup_folder, destination):
    folder = Path(backup_folder).resolve()
    manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('format') != 1 or manifest.get('database') != 'database.mv.db':
        raise ValueError('Unsupported backup manifest.')
    source = database_file(folder / 'database.mv.db')
    if source.stat().st_size != manifest.get('bytes') or digest(source) != manifest.get('sha256'):
        raise ValueError('Backup integrity check failed.')
    destination = Path(destination).absolute()
    if not destination.name.endswith('.mv.db'):
        raise ValueError('Restore destination must end in .mv.db.')
    if not destination.parent.is_dir():
        raise ValueError('Create the restore parent directory first.')
    if destination.with_name(destination.name[:-6] + '.lock.db').exists():
        raise ValueError('Restore destination has a database lock file.')
    # Exclusive creation protects the original database and existing restores.
    created = False
    try:
        with destination.open('xb') as output:
            created = True
            destination.chmod(0o600)
            with source.open('rb') as incoming:
                shutil.copyfileobj(incoming, output)
        if digest(destination) != manifest['sha256']:
            raise ValueError('Restored file integrity check failed.')
    except BaseException:
        if created:
            destination.unlink()
        raise
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['backup', 'restore'])
    parser.add_argument('source')
    parser.add_argument('destination')
    parser.add_argument('--confirm-stopped', action='store_true', help='Confirm all processes using this database are stopped.')
    args = parser.parse_args()
    if not args.confirm_stopped:
        parser.error('Stop the backend first, then pass --confirm-stopped. File checks cannot prove that H2 is offline.')
    try:
        result = (backup if args.operation == 'backup' else restore)(args.source, args.destination)
    except (OSError, ValueError, TypeError) as error:
        parser.exit(1, f'{error}\n')
    print(f'{args.operation.capitalize()} complete: {result}')


if __name__ == '__main__':
    main()
