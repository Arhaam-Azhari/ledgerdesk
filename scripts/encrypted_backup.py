"""Encrypt a PostgreSQL backup folder, or unpack it for the existing restore tool."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
from local_backup import digest

FILES = {'manifest.json', 'database.dump'}

def validate(folder):
    folder = Path(folder)
    for name in FILES:
        file = folder / name
        if not file.is_file() or file.is_symlink():
            raise ValueError('Use a regular PostgreSQL backup folder.')
    manifest = json.loads((folder / 'manifest.json').read_text())
    archive = folder / 'database.dump'
    if (not isinstance(manifest, dict) or manifest.get('format') != 1
            or manifest.get('database') != 'database.dump'
            or manifest.get('bytes') != archive.stat().st_size
            or manifest.get('sha256') != digest(archive)):
        raise ValueError('Backup integrity check failed.')

def output_path(destination):
    destination = Path(destination).absolute()
    if destination.exists() or destination.is_symlink():
        raise ValueError('Destination already exists.')
    if not destination.parent.is_dir():
        raise ValueError('Create the destination parent directory first.')
    return destination

def encrypt(folder, destination, recipients):
    validate(folder)
    destination = output_path(destination)
    with tempfile.TemporaryDirectory(prefix='.ledgerdesk-encrypt-', dir=destination.parent) as temp:
        payload = Path(temp) / 'backup.tar'
        with tarfile.open(payload, 'w') as archive:
            for name in sorted(FILES):
                archive.add(Path(folder) / name, arcname=name, recursive=False)
        payload.chmod(0o600)
        encrypted = Path(temp) / 'backup.age'
        with encrypted.open('xb') as output:
            encrypted.chmod(0o600)
            subprocess.run(['age', '--encrypt', '--recipients-file', str(recipients), str(payload)], stdout=output, check=True)
        # Hard linking publishes a complete file without replacing an existing backup.
        destination.hardlink_to(encrypted)
    return destination

def decrypt(source, destination, identity):
    destination = output_path(destination)
    with tempfile.TemporaryDirectory(prefix='.ledgerdesk-decrypt-', dir=destination.parent) as temp:
        stage = Path(temp)
        payload = stage / 'backup.tar'
        with payload.open('xb') as output:
            payload.chmod(0o600)
            subprocess.run(['age', '--decrypt', '--identity', str(identity), str(source)], stdout=output, check=True)
        folder = stage / 'checked'
        folder.mkdir(mode=0o700)
        with tarfile.open(payload, 'r:') as archive:
            members = archive.getmembers()
            if len(members) != 2 or {m.name for m in members} != FILES or any(not m.isfile() for m in members):
                raise ValueError('Unexpected encrypted backup contents.')
            # Copy only these two plain files; never extract paths or links from an archive.
            for member in members:
                with archive.extractfile(member) as incoming, (folder / member.name).open('xb') as output:
                    shutil.copyfileobj(incoming, output)
                (folder / member.name).chmod(0o600)
        validate(folder)
        destination.mkdir(mode=0o700)
        try:
            for name in FILES:
                shutil.copyfile(folder / name, destination / name)
                (destination / name).chmod(0o600)
        except BaseException:
            shutil.rmtree(destination)
            raise
    return destination

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['encrypt', 'decrypt'])
    parser.add_argument('source')
    parser.add_argument('destination')
    parser.add_argument('--recipients', help='Public age recipients file for encryption')
    parser.add_argument('--identity', help='Private age identity file for recovery')
    args = parser.parse_args()
    key = args.recipients if args.operation == 'encrypt' else args.identity
    if not key:
        parser.error('Supply --recipients for encryption or --identity for decryption.')
    try:
        result = (encrypt if args.operation == 'encrypt' else decrypt)(args.source, args.destination, key)
    except (OSError, ValueError, tarfile.TarError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'Encrypted backup did not complete: {error}\n')
    print(f'{args.operation.capitalize()} complete: {result}')

if __name__ == '__main__':
    main()
