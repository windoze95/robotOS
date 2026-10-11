"""Backups must be recoverable snapshots, preserve live data, and exclude unverified media."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from server.robotos_hub.config import Config
from server.robotos_hub.store import Store

spec = importlib.util.spec_from_file_location('robotos_hub_backup', Path(__file__).resolve().parents[3] / 'tools' / 'hub-backup.py')
backup_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup_module)


class HubBackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source, self.output = self.root / 'live', self.root / 'backup'
        self.store = Store(Config(self.source, 'correct horse battery staple', owner_token='o' * 40))
        self.owner = self.store.authenticate_token('o' * 40)
        self.store.save_media(self.owner, 'audio', 'audio/wav', b'original recording')
        self.store.create_note(self.owner, dict(id='note', title='Voice', kind='audio', media_id='audio', text='Original transcript'))

    def tearDown(self):
        self.temp.cleanup()

    def test_live_wal_backup_is_private_standalone_and_restorable(self):
        # Keep a live connection with a WAL alive across backup to exercise actual online backup.
        with self.store.connection() as live:
            live.execute('PRAGMA wal_autocheckpoint=0')
            self.store.update_note(self.owner, 'note', dict(base_revision=1, title='Latest', text='Latest committed transcript'))
            result = backup_module.backup(self.source, self.output)
        self.assertFalse(result['credentials_included'])
        self.assertEqual(0o700, self.output.stat().st_mode & 0o777)
        self.assertEqual({'backup.json', 'workspace.sqlite3', 'media'}, {p.name for p in self.output.iterdir()})
        self.assertEqual(0o600, (self.output / 'workspace.sqlite3').stat().st_mode & 0o777)
        self.assertEqual(0o600, (self.output / 'media' / 'audio').stat().st_mode & 0o777)
        self.assertEqual(hashlib.sha256((self.output / 'workspace.sqlite3').read_bytes()).hexdigest(), result['database_sha256'])
        with sqlite3.connect(self.output / 'workspace.sqlite3') as saved:
            self.assertEqual('ok', saved.execute('PRAGMA integrity_check').fetchone()[0])
            self.assertEqual('Latest committed transcript', saved.execute("SELECT text FROM notes WHERE id='note'").fetchone()[0])
        self.assertEqual(b'original recording', (self.output / 'media' / 'audio').read_bytes())
        self.assertEqual('Latest committed transcript', self.store.notes(self.owner, 'note')['text'])
        self.assertEqual(result, json.loads((self.output / 'backup.json').read_text()))

    def test_missing_or_changed_audio_fails_without_publishing_backup(self):
        (self.source / 'media' / 'audio').write_bytes(b'corrupt')
        with self.assertRaises(ValueError):
            backup_module.backup(self.source, self.output)
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob('.robotos-backup-*')))
        (self.source / 'media' / 'audio').unlink()
        with self.assertRaises(FileNotFoundError):
            backup_module.backup(self.source, self.output)
        self.assertFalse(self.output.exists())

    def test_symlink_media_rejected_and_source_unmodified(self):
        media = self.source / 'media' / 'audio'
        target = self.root / 'outside'
        target.write_bytes(media.read_bytes())
        media.unlink()
        media.symlink_to(target)
        with self.assertRaises(ValueError):
            backup_module.backup(self.source, self.output)
        self.assertEqual(b'original recording', target.read_bytes())
        self.assertFalse(self.output.exists())

    def test_existing_destination_or_nested_backup_rejected(self):
        self.output.mkdir()
        sentinel = self.output / 'keep'
        sentinel.write_text('keep this')
        with self.assertRaises(ValueError):
            backup_module.backup(self.source, self.output)
        self.assertEqual('keep this', sentinel.read_text())
        with self.assertRaises(ValueError):
            backup_module.backup(self.source, self.source / 'backup')

    def test_only_media_referenced_by_database_snapshot_is_included(self):
        (self.source / 'media' / 'uncommitted-orphan').write_bytes(b'not committed')
        backup_module.backup(self.source, self.output)
        self.assertEqual(['audio'], [p.name for p in (self.output / 'media').iterdir()])

    def test_copy_failure_cleans_staging_and_preserves_live_workspace(self):
        with patch.object(backup_module.shutil, 'copyfile', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                backup_module.backup(self.source, self.output)
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob('.robotos-backup-*')))
        self.assertEqual('Original transcript', self.store.notes(self.owner, 'note')['text'])


if __name__ == '__main__':
    unittest.main()
