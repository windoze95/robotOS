"""Exercise installer boundaries without contacting a host or physical device."""
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from server.hermes import setup_macos

ROOT = Path(__file__).resolve().parents[3]


class SetupSafetyTest(unittest.TestCase):
    def test_documented_speech_smoke_play_option_uses_default_or_explicit_config(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            for name in ('tools', 'bin'):
                (folder / name).mkdir()
            for name in ('lib.sh', 'smoke-test.sh'):
                shutil.copyfile(ROOT / 'tools' / name, folder / 'tools' / name)
            config = folder / 'tools/r1ptt.json'
            config.write_text('{}')
            log = folder / 'jq-args.json'
            jq = folder / 'bin/jq'
            jq.write_text('#!' + sys.executable + '\nimport json,os,sys\n'
                          'open(os.environ["SETUP_TEST_LOG"],"w").write(json.dumps(sys.argv[1:]))\n'
                          'sys.exit(71)\n')
            jq.chmod(0o755)
            for args in (['--play'], [str(config), '--play'], ['--play', str(config)]):
                result = subprocess.run(['/bin/bash', str(folder / 'tools/smoke-test.sh'), *args],
                                        env=dict(os.environ, PATH=str(folder / 'bin') + ':' + os.environ['PATH'],
                                                 SETUP_TEST_LOG=str(log)), capture_output=True, text=True)
                # Stop at the first parser call, before speech/network work; it received the right file.
                self.assertEqual(71, result.returncode, result.stdout + result.stderr)
                self.assertEqual(str(config), json.loads(log.read_text())[-1])

    def test_direct_python_setup_requires_explicit_mode_and_rejects_shared_hosts(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            state, hermes = home / 'state', home / '.hermes'
            state.mkdir()
            hermes.mkdir()
            with patch.object(setup_macos, 'STATE', state), patch.object(setup_macos, 'HERMES_HOME', hermes), \
                 patch.object(setup_macos, 'run') as run, patch.object(setup_macos, 'write_private') as write, \
                 contextlib.redirect_stderr(io.StringIO()) as errors:
                with self.assertRaises(SystemExit):
                    setup_macos.main(['--r1', '100.1.1.1'])
                self.assertIn('requires --direct', errors.getvalue())
                for env in ('ROBOTOS_HUB_MCP_TOKEN=secret-value\n', 'ROBOTOS_HUB_MCP_TOKEN_FILE=/private/token\n',
                            'export ROBOTOS_HUB_MCP_TOKEN=secret-value\n',
                            'export ROBOTOS_HUB_MCP_TOKEN_FILE=/private/token\n',
                            'export\tROBOTOS_HUB_MCP_TOKEN=secret-value\n',
                            "'ROBOTOS_HUB_MCP_TOKEN_FILE'=/private/token\n"):
                    (hermes / '.env').write_text(env)
                    with self.assertRaises(SystemExit):
                        setup_macos.main(['--direct', '--r1', '100.1.1.1'])
                (hermes / '.env').unlink()
                (state / 'hub').mkdir()
                (state / 'hub/hub.json').write_text('{}')
                with self.assertRaises(SystemExit):
                    setup_macos.main(['--direct', '--r1', '100.1.1.1', '--introduce'])
                self.assertNotIn('secret-value', errors.getvalue())
                run.assert_not_called()
                write.assert_not_called()

    def test_legacy_shell_rejects_implicit_mode_or_unsafe_arguments_before_external_calls(self):
        for args in (['--host', 'user@host'], ['--direct', '--host', '-option'],
                     ['--direct', '--host', 'user@host', '--owner', '+12345678;echo leak'],
                     ['--direct', '--host']):
            with tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary)
                log = folder / 'calls'
                for name in ('adb', 'ssh', 'rsync', 'jq'):
                    executable = folder / name
                    executable.write_text('#!/bin/sh\nprintf called >> "$SETUP_TEST_LOG"\nexit 99\n')
                    executable.chmod(0o755)
                result = subprocess.run(['/bin/bash', str(ROOT / 'tools/hermes-setup.sh'), *args],
                                        env=dict(os.environ, PATH=str(folder) + ':' + os.environ['PATH'],
                                                 SETUP_TEST_LOG=str(log)), capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(log.exists(), result.stdout + result.stderr)

    def test_provisioning_preserves_lockscreen_and_device_admin_settings(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            for name in ('tools', 'out', 'bin'):
                (folder / name).mkdir()
            for name in ('lib.sh', 'provision.sh'):
                shutil.copyfile(ROOT / 'tools' / name, folder / 'tools' / name)
            for name in ('r1ptt.apk', 'r1ptt-system.zip'):
                (folder / 'out' / name).touch()
            log = folder / 'calls.jsonl'
            adb = folder / 'bin/adb'
            adb.write_text('#!' + sys.executable + '\n' + '''import json,os,sys
args=sys.argv[1:]
with open(os.environ['SETUP_TEST_LOG'],'a') as f:f.write(json.dumps(args)+'\\n')
if args==['shell','id','-u']: print('0')
elif args==['shell','id']: print('uid=0(root) gid=0(root)')
elif args[:5]==['shell','pm','list','packages','-U']: print('package:dev.r1ptt uid:10123')
''')
            adb.chmod(0o755)
            result = subprocess.run(['/bin/bash', str(folder / 'tools/provision.sh'), '--no-debloat', '--density', '0'],
                                    env=dict(os.environ, PATH=str(folder / 'bin') + ':' + os.environ['PATH'],
                                             SETUP_TEST_LOG=str(log)), capture_output=True, text=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertIn(['shell', 'cmd', 'package', 'set-home-activity', 'dev.r1ptt/.HomeActivity'], calls)
            self.assertFalse(any('locksettings' in call or 'dpm' in call for call in calls))
            self.assertIn('preserving Android lock and administrator settings', result.stdout)


if __name__ == '__main__':
    unittest.main()
