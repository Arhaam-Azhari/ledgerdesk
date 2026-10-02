import json
from pathlib import Path
import tempfile
import unittest
from local_backup import backup, restore


class LocalBackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'original.mv.db'
        self.source.write_bytes(b'isolated database fixture\x00\xff')
        self.saved = self.root / 'saved'

    def test_round_trip_and_independent_restore(self):
        backup(self.source, self.saved)
        target = restore(self.saved, self.root / 'restored.mv.db')
        self.assertEqual(target.read_bytes(), self.source.read_bytes())
        target.write_bytes(b'later writes')
        self.assertEqual((self.saved / 'database.mv.db').read_bytes(), self.source.read_bytes())

    def test_existing_backup_and_database_are_preserved(self):
        backup(self.source, self.saved)
        with self.assertRaises(ValueError):
            backup(self.source, self.saved)
        with self.assertRaises(FileExistsError):
            restore(self.saved, self.source)
        self.assertEqual(self.source.read_bytes(), b'isolated database fixture\x00\xff')

    def test_corruption_is_rejected_without_creating_target(self):
        backup(self.source, self.saved)
        (self.saved / 'database.mv.db').write_bytes(b'corrupt')
        target = self.root / 'restored.mv.db'
        with self.assertRaises(ValueError):
            restore(self.saved, target)
        self.assertFalse(target.exists())

    def test_same_size_corruption_is_detected(self):
        backup(self.source, self.saved)
        copied = self.saved / 'database.mv.db'
        copied.write_bytes(b'x' * copied.stat().st_size)
        with self.assertRaises(ValueError):
            restore(self.saved, self.root / 'restored.mv.db')

    def test_lock_file_blocks_backup(self):
        (self.root / 'original.lock.db').touch()
        with self.assertRaises(ValueError):
            backup(self.source, self.saved)
        self.assertFalse(self.saved.exists())

    def test_manifest_cannot_choose_another_file(self):
        backup(self.source, self.saved)
        manifest = self.saved / 'manifest.json'
        data = json.loads(manifest.read_text())
        data['database'] = '../original.mv.db'
        manifest.write_text(json.dumps(data))
        with self.assertRaises(ValueError):
            restore(self.saved, self.root / 'restored.mv.db')


if __name__ == '__main__':
    unittest.main()
