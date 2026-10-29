import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


SOURCE: Path = Path(__file__).with_name('director.py')
STUDIO: types.ModuleType = types.ModuleType('studio')
STUDIO.ROOT = SOURCE.parents[3]
STUDIO.OUTPUT = STUDIO.ROOT / 'build/demo'
STUDIO.ACTOR = 'DemoGuide'
STUDIO.rig = types.SimpleNamespace(FFMPEG=shutil.which('ffmpeg'), FFPROBE=shutil.which('ffprobe'))
STUDIO.runtime = Mock()
SPEC = importlib.util.spec_from_file_location('demo_director', SOURCE)
director = importlib.util.module_from_spec(SPEC)
with patch.dict('sys.modules', {'studio': STUDIO}):
    SPEC.loader.exec_module(director)


class DirectorRetentionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workspace = tempfile.TemporaryDirectory()
        self.addCleanup(self.workspace.cleanup)
        self.root: Path = Path(self.workspace.name).resolve()
        self.output: Path = self.root / 'build/demo'
        self.assets: Path = self.root / 'docs/gloss-assets/demos'
        self.output.mkdir(parents=True)
        self.assets.mkdir(parents=True)
        self.key: str = 'inventory-menus-pov'
        self.media: bytes = b'reviewed recording'
        self.patch = patch.multiple(director, OUTPUT=self.output, ASSETS=self.assets, ROOT=self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.validation = patch.object(director, 'validate_clip')
        self.validator: Mock = self.validation.start()
        self.addCleanup(self.validation.stop)
        self.seed_take()

    def seed_take(self) -> None:
        (self.output / 'manifest.json').write_text(json.dumps({self.key: {
            'completed': True, 'accepted': False, 'status': 'pending-review',
            'raw': str(self.root / 'outside.mp4')}}))
        for extension in director.TAKE_EXTENSIONS:
            (self.output / (self.key + extension)).write_bytes(self.media)

    def manifest(self) -> dict:
        return json.loads((self.output / 'manifest.json').read_text())[self.key]

    def assert_local_retained(self) -> None:
        for extension in director.TAKE_EXTENSIONS:
            self.assertEqual(self.media, (self.output / (self.key + extension)).read_bytes())

    def assert_local_removed(self) -> None:
        self.assertFalse(any((self.output / (self.key + extension)).exists()
                             for extension in director.TAKE_EXTENSIONS))

    def test_accept_publishes_then_removes_only_owned_media(self) -> None:
        outside: Path = self.root / 'outside.mp4'
        outside.write_bytes(b'unrelated')
        scene: Path = self.output / 'scene-cache'
        scene.mkdir()
        (scene / 'world.tar.gz').write_bytes(b'scene')
        other: Path = self.output / 'another-shot-pov.mp4'
        other.write_bytes(b'pending')
        director.review('inventory-menus', 'pov', True, 'Frames and motion reviewed')
        self.assertEqual(self.media, (self.assets / (self.key + '.webm')).read_bytes())
        self.assertEqual(hashlib.sha256(self.media).hexdigest(), self.manifest()['sha256'])
        self.assertEqual(str(self.assets / (self.key + '.webm')), self.manifest()['published'])
        self.assertTrue(self.manifest()['accepted'])
        self.assert_local_removed()
        self.assertEqual(b'unrelated', outside.read_bytes())
        self.assertEqual(b'scene', (scene / 'world.tar.gz').read_bytes())
        self.assertEqual(b'pending', other.read_bytes())
        self.validator.assert_called_once()

    def test_repeat_accept_verifies_published_hash_without_local_media(self) -> None:
        director.review('inventory-menus', 'pov', True, 'Reviewed')
        director.review('inventory-menus', 'pov', True, 'Reviewed again')
        self.validator.assert_called_once()
        self.assert_local_removed()
        self.assertEqual('Reviewed again', self.manifest()['review'])

    def test_repeat_accept_detects_changed_docs(self) -> None:
        director.review('inventory-menus', 'pov', True, 'Reviewed')
        published: Path = self.assets / (self.key + '.webm')
        published.write_bytes(b'changed independently')
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            director.review('inventory-menus', 'pov', True, 'Reviewed again')
        self.assertEqual(b'changed independently', published.read_bytes())

    def test_validation_failure_preserves_sources_and_previous_docs(self) -> None:
        published: Path = self.assets / (self.key + '.webm')
        published.write_bytes(b'previous clip')
        self.validator.side_effect = ValueError('Corrupt video')
        with self.assertRaisesRegex(ValueError, 'Corrupt'):
            director.review('inventory-menus', 'pov', True, 'Reviewed')
        self.assert_local_retained()
        self.assertEqual(b'previous clip', published.read_bytes())
        self.assertEqual([published], list(self.assets.iterdir()))
        self.assertEqual('pending-review', self.manifest()['status'])

    def test_atomic_publication_failure_preserves_sources(self) -> None:
        published: Path = self.assets / (self.key + '.webm')
        published.write_bytes(b'previous clip')
        replace = os.replace

        def fail_publish(source: Path, destination: Path) -> None:
            if destination == published:
                raise OSError('Publication denied')
            replace(source, destination)

        with patch.object(director.os, 'replace', side_effect=fail_publish):
            with self.assertRaisesRegex(OSError, 'denied'):
                director.review('inventory-menus', 'pov', True, 'Reviewed')
        self.assert_local_retained()
        self.assertEqual(b'previous clip', published.read_bytes())
        self.assertEqual([published], list(self.assets.iterdir()))
        self.assertFalse(self.manifest()['accepted'])

    def test_manifest_failure_preserves_sources_after_publication(self) -> None:
        replace = os.replace

        def fail_manifest(source: Path, destination: Path) -> None:
            if destination == self.output / 'manifest.json':
                raise OSError('Manifest denied')
            replace(source, destination)

        with patch.object(director.os, 'replace', side_effect=fail_manifest):
            with self.assertRaisesRegex(OSError, 'denied'):
                director.review('inventory-menus', 'pov', True, 'Reviewed')
        self.assert_local_retained()
        self.assertFalse(self.manifest()['accepted'])
        director.review('inventory-menus', 'pov', True, 'Reviewed')
        self.assert_local_removed()

    def test_reject_removes_local_media_and_keeps_published_clip(self) -> None:
        published: Path = self.assets / (self.key + '.webm')
        published.write_bytes(b'previous clip')
        director.review('inventory-menus', 'pov', False, 'Wrong camera')
        director.review('inventory-menus', 'pov', False, 'Wrong camera')
        self.assert_local_removed()
        self.assertEqual(b'previous clip', published.read_bytes())
        self.assertEqual('rejected', self.manifest()['status'])
        self.validator.assert_not_called()

    def test_review_requires_evidence_and_completed_take(self) -> None:
        with self.assertRaises(ValueError):
            director.review('inventory-menus', 'pov', True, ' ')
        with director.manifest_update() as takes:
            takes[self.key]['completed'] = False
        with self.assertRaises(AssertionError):
            director.review('inventory-menus', 'pov', True, 'Reviewed')
        self.assert_local_retained()
        self.assertEqual([], list(self.assets.iterdir()))

    def test_live_lock_refuses_record_accept_and_reject_without_changes(self) -> None:
        original: bytes = (self.output / 'manifest.json').read_bytes()
        with director.take_lock(self.key):
            with self.assertRaisesRegex(RuntimeError, 'in use'):
                director.record('inventory-menus')
            for accepted in (False, True):
                with self.assertRaisesRegex(RuntimeError, 'in use'):
                    director.review('inventory-menus', 'pov', accepted, 'Reviewed')
        self.assert_local_retained()
        self.assertEqual(original, (self.output / 'manifest.json').read_bytes())
        self.assertEqual([], list(self.assets.iterdir()))

    def test_crashed_lock_holder_leaves_stale_take_rejectable(self) -> None:
        with director.manifest_update() as takes:
            takes[self.key].update(completed=False, status='recording')
        result: subprocess.CompletedProcess = subprocess.run([sys.executable, '-c',
            "import fcntl, os, sys; lock = open(sys.argv[1], 'a'); "
            "fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB); os._exit(7)",
            str(self.output / (self.key + '.lock'))], timeout=10)
        self.assertEqual(7, result.returncode)
        director.review('inventory-menus', 'pov', False, 'Interrupted capture discarded')
        self.assertEqual('rejected', self.manifest()['status'])
        self.assert_local_removed()

    def test_path_traversal_cannot_delete_outside_outputs(self) -> None:
        outside: Path = self.root / 'outside-pov.mp4'
        outside.write_bytes(b'keep')
        with self.assertRaises(ValueError):
            director.review('../../outside', 'pov', False, 'Rejected')
        with self.assertRaises(ValueError):
            director.discard_outputs('../../outside-pov')
        self.assertEqual(b'keep', outside.read_bytes())
        self.assert_local_retained()

    def test_symlink_source_cannot_delete_or_publish_external_media(self) -> None:
        outside: Path = self.root / 'outside.webm'
        outside.write_bytes(b'keep')
        source: Path = self.output / (self.key + '.webm')
        source.unlink()
        source.symlink_to(outside)
        for accepted in (False, True):
            with self.assertRaises(ValueError):
                director.review('inventory-menus', 'pov', accepted, 'Reviewed')
        self.assertTrue(source.is_symlink())
        self.assertEqual(b'keep', outside.read_bytes())
        self.assertEqual([], list(self.assets.iterdir()))

    def test_symlink_destination_cannot_replace_external_media(self) -> None:
        outside: Path = self.root / 'outside.webm'
        outside.write_bytes(b'keep')
        (self.assets / (self.key + '.webm')).symlink_to(outside)
        with self.assertRaises(ValueError):
            director.review('inventory-menus', 'pov', True, 'Reviewed')
        self.assert_local_retained()
        self.assertEqual(b'keep', outside.read_bytes())

    def test_record_replaces_superseded_media_and_stays_pending(self) -> None:
        with director.manifest_update() as takes:
            takes[self.key].update(completed=False, status='recording')
        shots: Path = self.root / 'src/test/demo/shots.json'
        shots.parent.mkdir(parents=True)
        shots.write_text(json.dumps([{'id': 'inventory-menus', 'setup': [], 'beats': []}]))
        server: Path = self.root / 'server'
        (server / 'plugins').mkdir(parents=True)
        (server / 'plugins/Gloss.jar').write_bytes(b'plugin')
        bridge: Mock = Mock()
        bridge.command.return_value = {'captureSeconds': 2.0, 'captureFrames': 60}
        rcon: Mock = Mock(history=[])

        def encode(arguments: list[str], **kwargs) -> None:
            Path(arguments[-1]).write_bytes(b'new recording')

        published: Path = self.assets / (self.key + '.webm')
        published.write_bytes(b'previous accepted clip')
        rig = types.SimpleNamespace(FFMPEG='ffmpeg', fit_hidden_renderer=Mock(),
                                   capture_evidence=Mock(), capture_metrics=Mock(return_value={'frames': 60}))
        with patch.object(director, 'runtime', return_value=(server, rcon, bridge)), \
                patch.object(director, 'rig', rig), patch.object(director.time, 'sleep'), \
                patch.object(director.subprocess, 'run', side_effect=encode):
            director.record('inventory-menus')
        self.assertEqual('pending-review', self.manifest()['status'])
        self.assertFalse(self.manifest()['accepted'])
        self.assertEqual(b'new recording', (self.output / (self.key + '.webm')).read_bytes())
        self.assertFalse((self.output / 'takes').exists())
        self.assertEqual(b'previous accepted clip', published.read_bytes())
        rig.capture_evidence.side_effect = ValueError('Failed capture')
        with patch.object(director, 'runtime', return_value=(server, rcon, bridge)), \
                patch.object(director, 'rig', rig), patch.object(director.time, 'sleep'):
            with self.assertRaisesRegex(ValueError, 'Failed capture'):
                director.record('inventory-menus')
        self.assertEqual('rejected', self.manifest()['status'])
        self.assert_local_removed()
        self.assertEqual(b'previous accepted clip', published.read_bytes())


class ClipValidationTest(unittest.TestCase):
    @unittest.skipUnless(STUDIO.rig.FFMPEG and STUDIO.rig.FFPROBE, 'FFmpeg and FFprobe required')
    def test_real_silent_video_validates_and_corrupt_media_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            clip: Path = Path(directory) / 'clip.webm'
            subprocess.run([STUDIO.rig.FFMPEG, '-v', 'error', '-f', 'lavfi', '-i',
                'color=c=black:s=1920x1080:r=30:d=0.1', '-an', '-c:v', 'libvpx-vp9',
                '-deadline', 'realtime', '-cpu-used', '8', str(clip)], check=True)
            director.validate_clip(clip)
            clip.write_bytes(b'invalid video')
            with self.assertRaises(subprocess.CalledProcessError):
                director.validate_clip(clip)


if __name__ == '__main__':
    unittest.main()
