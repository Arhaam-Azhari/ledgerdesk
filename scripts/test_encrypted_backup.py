import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from encrypted_backup import encrypt, decrypt
from local_backup import digest

class EncryptedBackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.saved = self.root / 'saved'
        self.saved.mkdir()
        dump = self.saved / 'database.dump'
        dump.write_bytes(b'private accounting fixture')
        (self.saved / 'manifest.json').write_text(json.dumps({'format': 1, 'database': 'database.dump', 'bytes': dump.stat().st_size, 'sha256': digest(dump)}))

    def test_corruption_is_rejected_before_encryption(self):
        (self.saved / 'database.dump').write_bytes(b'changed')
        with patch('encrypted_backup.subprocess.run') as run:
            with self.assertRaises(ValueError):
                encrypt(self.saved, self.root / 'backup.age', 'recipients')
            run.assert_not_called()

    def test_existing_backup_is_not_overwritten(self):
        output = self.root / 'backup.age'
        output.write_bytes(b'keep this')
        with self.assertRaises(ValueError):
            encrypt(self.saved, output, 'recipients')
        self.assertEqual(output.read_bytes(), b'keep this')

    def test_failed_encryption_does_not_publish_partial_output(self):
        def fail(args, **kwargs):
            kwargs['stdout'].write(b'partial')
            raise subprocess.CalledProcessError(1, args)
        with patch('encrypted_backup.subprocess.run', side_effect=fail):
            with self.assertRaises(subprocess.CalledProcessError):
                encrypt(self.saved, self.root / 'backup.age', 'recipients')
        self.assertEqual(set(p.name for p in self.root.iterdir()), {'saved'})

    def test_unexpected_archive_paths_never_get_extracted(self):
        def unpack(args, **kwargs):
            with tarfile.open(fileobj=kwargs['stdout'], mode='w') as archive:
                item = tarfile.TarInfo('../outside')
                item.size = 3
                archive.addfile(item, io.BytesIO(b'bad'))
        with patch('encrypted_backup.subprocess.run', side_effect=unpack):
            with self.assertRaises(ValueError):
                decrypt('backup.age', self.root / 'restored', 'identity')
        self.assertFalse((self.root / 'restored').exists())
        self.assertFalse((self.root / 'outside').exists())

    @unittest.skipUnless(shutil.which('age') and shutil.which('age-keygen'), 'age tools are required for the real round trip')
    def test_real_encryption_round_trip_and_wrong_key_rejection(self):
        identity = self.root / 'identity'
        other = self.root / 'other'
        for key in (identity, other):
            subprocess.run(['age-keygen', '-o', str(key)], check=True, stderr=subprocess.DEVNULL)
        recipients = self.root / 'recipients'
        recipients.write_bytes(subprocess.check_output(['age-keygen', '-y', str(identity)]))
        encrypted = encrypt(self.saved, self.root / 'backup.age', recipients)
        self.assertNotIn(b'private accounting fixture', encrypted.read_bytes())
        with self.assertRaises(subprocess.CalledProcessError):
            decrypt(encrypted, self.root / 'wrong-key', other)
        self.assertFalse((self.root / 'wrong-key').exists())
        restored = decrypt(encrypted, self.root / 'restored', identity)
        for name in ('manifest.json', 'database.dump'):
            self.assertEqual((restored / name).read_bytes(), (self.saved / name).read_bytes())
        self.assertEqual(encrypted.stat().st_mode & 0o777, 0o600)
        self.assertEqual(restored.stat().st_mode & 0o777, 0o700)

if __name__ == '__main__':
    unittest.main()
