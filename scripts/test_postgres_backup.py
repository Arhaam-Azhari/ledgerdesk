import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from postgres_backup import backup, restore, command, database_name


class PostgresBackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.folder = self.root / 'saved'
        self.env = patch.dict(os.environ, {}, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)

    def dump(self, args, **kwargs):
        kwargs['stdout'].write(b'archive fixture')

    def saved(self):
        with patch('postgres_backup.subprocess.run', side_effect=self.dump):
            backup('ledgerdesk', self.folder)

    def test_archive_and_manifest_then_new_database_restore(self):
        self.saved()
        with patch('postgres_backup.subprocess.run') as run:
            restore(self.folder, 'restored_books')
        calls = [call.args[0] for call in run.call_args_list]
        self.assertEqual(calls[0], ['pg_restore', '--list'])
        self.assertEqual(calls[1], ['createdb', '--maintenance-db=postgres', '--template=template0', 'restored_books'])
        self.assertIn('--single-transaction', calls[2])
        self.assertNotIn('--clean', calls[2])

    def test_corruption_is_rejected_before_database_commands(self):
        self.saved()
        (self.folder / 'database.dump').write_bytes(b'corrupt')
        with patch('postgres_backup.subprocess.run') as run:
            with self.assertRaises(ValueError):
                restore(self.folder, 'restored_books')
            run.assert_not_called()

    def test_failed_dump_does_not_publish_a_backup(self):
        with patch('postgres_backup.subprocess.run', side_effect=subprocess.CalledProcessError(1, ['pg_dump'])):
            with self.assertRaises(subprocess.CalledProcessError):
                backup('ledgerdesk', self.folder)
        self.assertFalse(self.folder.exists())

    def test_existing_database_rejection_stops_before_restore(self):
        self.saved()
        with patch('postgres_backup.subprocess.run', side_effect=[None, subprocess.CalledProcessError(1, ['createdb'])]) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                restore(self.folder, 'existing_books')
            self.assertEqual(run.call_count, 2)

    def test_existing_backup_is_preserved(self):
        self.saved()
        original = (self.folder / 'database.dump').read_bytes()
        with self.assertRaises(ValueError):
            backup('ledgerdesk', self.folder)
        self.assertEqual((self.folder / 'database.dump').read_bytes(), original)

    def test_connection_string_is_not_accepted_as_database_name(self):
        for name in ['postgresql://user:secret@host/db', '-danger', 'db name', 'a' * 64]:
            with self.assertRaises(ValueError):
                database_name(name)

    def test_manifest_cannot_choose_another_file(self):
        self.saved()
        manifest = self.folder / 'manifest.json'
        data = json.loads(manifest.read_text())
        data['database'] = '../private.dump'
        manifest.write_text(json.dumps(data))
        with self.assertRaises(ValueError):
            restore(self.folder, 'restored_books')

    def test_ci_container_and_user_arguments_contain_no_password(self):
        with patch.dict(os.environ, {'LEDGERDESK_PG_CONTAINER': 'test-server', 'PGUSER': 'ledgerdesk', 'PGPASSWORD': 'test-secret'}):
            args = command('pg_dump', '--dbname', 'books')
        self.assertEqual(args[:5], ['docker', 'exec', '-i', 'test-server', 'pg_dump'])
        self.assertNotIn('test-secret', args)


if __name__ == '__main__':
    unittest.main()
