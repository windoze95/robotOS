import concurrent.futures
import http.client
import json
import os
from pathlib import Path
import socket
import sqlite3
import tempfile
import threading
import unittest
from unittest.mock import patch

from robotos_hub.config import Config
from robotos_hub.store import Store, HubError, canonical, now
from robotos_hub.http import HubServer
from robotos_hub.upstream import Workers, UpstreamError, Client


class FakeHermes:
    identity = 'fixed-server-identity'
    def __init__(self):
        self.submissions = []
        self.fail_submission = False
        self.durable = True
        self.state = 'running'
        self.transcript = 'transcribed words'
        self.on_transcribe = lambda: None

    def capabilities(self):
        return {'runs': True, 'durable': self.durable, 'retention_seconds': 3600, 'stop': True, 'approval': True}

    def hermes(self, method, path, payload=None, headers=None):
        if path == '/v1/runs':
            self.submissions.append((payload, headers['Idempotency-Key']))
            if self.fail_submission:
                raise UpstreamError()
            return {'run_id': 'remote-' + headers['Idempotency-Key']}
        if path.endswith('/stop'):
            self.state = 'cancelled'
            return {'status': 'cancelled'}
        return {'run_id': path.split('/')[-1], 'status': self.state, 'output': 'saved result' if self.state == 'completed' else '', 'error': ''}

    def transcribe(self, media, data):
        self.on_transcribe()
        return self.transcript


class HubFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.config = Config(Path(self.temp.name), 'correct horse battery staple', owner_token='o' * 40, mcp_token='m' * 40, port=0)
        self.store = Store(self.config)
        self.owner = self.store.authenticate_token('o' * 40)
        self.mcp = self.store.authenticate_token('m' * 40)
        self.a = self.enroll('One')
        self.b = self.enroll('Two')
        self.fake = FakeHermes()
        self.workers = Workers(self.store, self.fake)

    def tearDown(self):
        self.temp.cleanup()

    def enroll(self, name):
        code = self.store.enrollment(self.owner, {'name': name})
        claimed = self.store.claim({'code': code['code']})
        return dict(claimed, workspace=self.store.authenticate_token(claimed['device_token']), recovery=self.store.authenticate_token(claimed['recovery_token']))

    def prompt(self, actor=None):
        return self.store.create_prompt(actor or self.a['workspace'], {'id': 'prompt', 'title': 'Test', 'instruction': 'Do the work', 'blocks': []})

    def assertError(self, code, function, *args, **kwargs):
        with self.assertRaises(HubError) as caught:
            function(*args, **kwargs)
        self.assertEqual(code, caught.exception.code)
        return caught.exception

    def checkin(self, device=None, **extra):
        device = device or self.a
        body = dict(app_version='1.0', battery=70, charging=True, capabilities={k: True for k in ['ring', 'stop_ring', 'show_message', 'lock', 'locate', 'clear_workspace', 'factory_reset', 'update_app', 'reboot', 'device_tool']},
                    settings={'remote_management': True, 'location_reporting': True, 'secure_lock': True, 'allow_clear_workspace': True})
        body.update(extra)
        return self.store.check_in(device['recovery'], body)


class HubTest(HubFixture):
    def test_claim_consumed_once_and_credentials_hashed(self):
        code = self.store.enrollment(self.owner, {})['code']
        claim = self.store.claim({'code': code})
        self.assertError('invalid_enrollment', self.store.claim, {'code': code})
        with self.store.connection() as db:
            row = dict(db.execute('SELECT * FROM devices WHERE id=?', (claim['device_id'],)).fetchone())
            self.assertNotIn(claim['device_token'], canonical(row))
            self.assertNotIn(claim['recovery_token'], canonical(row))

    def test_wrong_scopes_and_mcp_admin_denied(self):
        self.assertError('wrong_scope', self.store.notes, self.a['recovery'])
        self.assertError('wrong_scope', self.store.check_in, self.a['workspace'], {})
        self.assertError('wrong_scope', self.store.enrollment, self.mcp, {})
        self.assertError('wrong_scope', self.store.devices, self.b['workspace'])
        self.assertError('wrong_scope', self.store.patch_device, self.mcp, self.a['device_id'], {'base_revision': 1})

    def test_lease_takeover_fences_delayed_writer_and_stale_revision(self):
        self.prompt()
        a = self.store.lease(self.a['workspace'], 'prompt')
        self.assertError('lease_held', self.store.lease, self.b['workspace'], 'prompt')
        b = self.store.lease(self.b['workspace'], 'prompt', True)
        self.assertNotEqual(a['lease_token'], b['lease_token'])
        self.assertError('lease_lost', self.store.update_prompt, self.a['workspace'], 'prompt', dict(base_revision=1, lease_token=a['lease_token'], title='old', instruction='old', blocks=[]))
        self.store.update_prompt(self.b['workspace'], 'prompt', dict(base_revision=1, lease_token=b['lease_token'], title='new', instruction='new', blocks=[]))
        self.assertError('revision_conflict', self.store.update_prompt, self.b['workspace'], 'prompt', dict(base_revision=1, lease_token=b['lease_token'], title='old', instruction='old', blocks=[]))
        self.assertEqual('new', self.store.prompts(self.a['workspace'], 'prompt')['instruction'])

    def test_same_device_renews_and_expired_lease_fenced(self):
        self.prompt()
        a = self.store.lease(self.a['workspace'], 'prompt')
        self.assertEqual(a['lease_token'], self.store.lease(self.a['workspace'], 'prompt')['lease_token'])
        with self.store.transaction() as db:
            db.execute('UPDATE leases SET expires_at=0')
        fresh = self.store.lease(self.a['workspace'], 'prompt')
        self.assertNotEqual(a['lease_token'], fresh['lease_token'])

    def test_two_devices_race_one_run_and_request_payload_binding(self):
        self.prompt()
        barrier = threading.Barrier(2)
        def reserve(pair):
            actor, request = pair
            barrier.wait()
            return self.store.reserve_run(actor, 'prompt', dict(revision=1, request_id=request))
        with concurrent.futures.ThreadPoolExecutor(2) as executor:
            runs = list(executor.map(reserve, [(self.a['workspace'], 'a-request'), (self.b['workspace'], 'b-request')]))
        self.assertEqual(runs[0]['id'], runs[1]['id'])
        self.assertError('idempotency_conflict', self.store.reserve_run, self.a['workspace'], 'prompt', dict(revision=1, request_id='a-request', run_again=True))
        self.workers.tick()
        self.assertEqual(1, len(self.fake.submissions))
        self.fake.state = 'completed'
        self.workers.tick()
        prompt = self.store.prompts(self.b['workspace'], 'prompt')
        self.assertNotIn('state', prompt)
        self.assertEqual(runs[0]['id'], prompt['last_run_id'])
        self.assertEqual('saved result', self.store.runs(self.b['workspace'], runs[0]['id'])['output'])

    def test_active_session_prevents_new_revision_or_run_again_overlap(self):
        self.prompt()
        run = self.store.reserve_run(self.owner, 'prompt', dict(revision=1, request_id='first'))
        self.assertError('run_active', self.store.reserve_run, self.owner, 'prompt', dict(revision=1, request_id='again', run_again=True))
        lease = self.store.lease(self.owner, 'prompt')
        self.store.update_prompt(self.owner, 'prompt', dict(base_revision=1, lease_token=lease['lease_token'], instruction='Follow-up', title='Chat', blocks=[]))
        self.assertError('run_active', self.store.reserve_run, self.owner, 'prompt', dict(revision=2, request_id='new-revision'))
        self.workers.tick()
        self.fake.state = 'completed'
        self.workers.tick()
        second = self.store.reserve_run(self.owner, 'prompt', dict(revision=2, request_id='new-revision'))
        self.assertNotEqual(run['id'], second['id'])
        self.workers.tick()
        self.assertEqual(self.fake.submissions[0][0]['session_id'], self.fake.submissions[1][0]['session_id'])

    def test_unchanged_save_keeps_revision_and_attaches_to_confirmed_submission(self):
        prompt = self.prompt()
        run = self.store.reserve_run(self.owner, 'prompt', dict(revision=1, request_id='first'))
        self.workers.tick()
        self.fake.state = 'completed'
        self.workers.tick()
        lease = self.store.lease(self.owner, 'prompt')
        saved = self.store.update_prompt(self.owner, 'prompt', dict(prompt, base_revision=1, lease_token=lease['lease_token']))
        self.assertEqual(1, saved['revision'])
        self.assertNotIn('state', saved)
        self.assertNotIn('changed_since_run', saved)
        self.assertNotIn('last_run_revision', saved)
        attached = self.store.reserve_run(self.owner, 'prompt', dict(revision=saved['revision'], request_id='second'))
        self.assertEqual(run['id'], attached['id'])
        self.workers.tick()
        self.assertEqual(1, len(self.fake.submissions))

    def test_prompt_receipts_preserve_editing_control_and_exclude_other_prompts(self):
        self.prompt()
        lease = self.store.lease(self.a['workspace'], 'prompt')
        first = self.store.reserve_run(self.owner, 'prompt', dict(revision=1, request_id='first'))
        self.store.create_prompt(self.owner, dict(id='other', instruction='Another prompt'))
        self.store.reserve_run(self.owner, 'other', dict(revision=1, request_id='other-request'))
        receipts = self.store.prompt_submissions(self.b['workspace'], 'prompt')['items']
        self.assertEqual([first['id']], [r['id'] for r in receipts])
        self.assertEqual(lease['lease_token'], self.store.lease(self.a['workspace'], 'prompt')['lease_token'])
        self.assertError('wrong_scope', self.store.prompt_submissions, self.a['recovery'], 'prompt')
        self.store.patch_device(self.owner, self.b['device_id'], dict(base_revision=1, lost=True))
        self.assertError('device_quarantined', self.store.prompt_submissions, self.b['workspace'], 'prompt')

    def test_receipt_links_confirmed_hermes_identity_without_exposing_transport_secrets(self):
        self.prompt()
        run = self.store.reserve_run(self.owner, 'prompt', dict(revision=1, request_id='first'))
        self.assertEqual({'run_id': None, 'session_id': None}, run['hermes'])
        self.workers.tick()
        remote_id = 'remote-' + run['id']
        with patch.object(self.fake, 'hermes', return_value={'run_id': 'wrong-run', 'status': 'running',
                         'session_id': 'unrelated-session'}):
            self.workers.tick()
        self.assertIsNone(self.store.runs(self.owner, run['id'])['hermes']['session_id'])
        with patch.object(self.fake, 'hermes', return_value={'run_id': remote_id, 'status': 'running',
                         'session_id': 'hermes:confirmed-session'}):
            self.workers.tick()
        with patch.object(self.fake, 'hermes', return_value={'run_id': remote_id, 'status': 'completed',
                         'output': 'Canonical reply'}):
            self.workers.tick()
        receipt = self.store.prompt_submissions(self.owner, 'prompt')['items'][0]
        self.assertEqual({'run_id': remote_id, 'session_id': 'hermes:confirmed-session'}, receipt['hermes'])
        self.assertEqual('Canonical reply', receipt['output'])
        self.assertTrue({'upstream_payload', 'upstream_identity', 'requester', 'capabilities'}.isdisjoint(receipt))
        self.assertEqual(receipt['hermes'], Store(self.config).runs(self.owner, run['id'])['hermes'])

    def test_optional_session_reference_does_not_block_confirmed_completion(self):
        self.prompt()
        run = self.store.reserve_run(self.owner, 'prompt', dict(revision=1, request_id='first'))
        self.workers.tick()
        with patch.object(self.fake, 'hermes', return_value={'run_id': 'remote-' + run['id'], 'status': 'completed',
                         'output': 'Confirmed reply', 'session_id': {'unexpected': 'shape'}}):
            self.workers.tick()
        receipt = self.store.runs(self.owner, run['id'])
        self.assertEqual('completed', receipt['status'])
        self.assertIsNone(receipt['hermes']['session_id'])

    def test_upgrade_preserves_existing_completed_reply_without_inventing_session_id(self):
        self.prompt()
        run = self.store.reserve_run(self.owner, 'prompt', dict(revision=1, request_id='first'))
        self.workers.tick()
        self.fake.state = 'completed'
        self.workers.tick()
        with self.store.connection() as db:
            db.execute('ALTER TABLE runs DROP COLUMN hermes_session_id')
            db.execute('PRAGMA user_version=2')
        upgraded = Store(self.config).runs(self.owner, run['id'])
        self.assertEqual('completed', upgraded['status'])
        self.assertEqual('saved result', upgraded['output'])
        self.assertEqual({'run_id': 'remote-' + run['id'], 'session_id': None}, upgraded['hermes'])

    def test_completed_requires_an_explicit_string_result(self):
        self.prompt()
        run = self.store.reserve_run(self.owner, 'prompt', dict(revision=1, request_id='first'))
        self.workers.tick()
        remote = 'remote-' + run['id']
        for invalid in ({'run_id': remote, 'status': 'completed'}, {'run_id': remote, 'status': 'completed', 'output': None}):
            with patch.object(self.fake, 'hermes', return_value=invalid):
                self.workers.tick()
            self.assertEqual('running', self.store.runs(self.owner, run['id'])['status'])
        self.fake.state = 'completed'
        self.workers.tick()
        self.assertEqual('completed', self.store.runs(self.owner, run['id'])['status'])
        self.assertEqual('saved result', self.store.runs(self.owner, run['id'])['output'])

    def test_malformed_hermes_capabilities_are_rejected_explicitly(self):
        client = Client(self.config)
        for mutation in ({'features': {'runs_idempotency': []}}, {'features': {'runs_idempotency': {'retention_seconds': '3600'}}},
                         {'features': {'runs_idempotency': {'retention_seconds': float('inf')}}}, {'features': {}, 'endpoints': []}):
            value = dict(object='hermes.api_server.capabilities', **mutation)
            with patch.object(client, 'hermes', return_value=value):
                self.assertError('unsupported_hermes', client.capabilities)

    def test_controls_return_run_shape_and_stable_receipt(self):
        self.prompt()
        run = self.store.reserve_run(self.owner, 'prompt', dict(revision=1, request_id='first'))
        self.workers.tick()
        stopped = self.workers.control(self.owner, run['id'], 'stop', {'request_id': 'stop-identity'})
        self.assertEqual(run['id'], stopped['id'])
        self.assertEqual('cancelled', stopped['status'])
        self.assertEqual('applied', stopped['control']['status'])
        self.assertEqual(stopped, self.workers.control(self.owner, run['id'], 'stop', {'request_id': 'stop-identity'}))

    def test_clear_command_immediately_revokes_workspace_and_preserves_recovery(self):
        self.checkin()
        token, _ = self.store.login(self.config.owner_password)
        actor = self.store.session(token)
        self.store.create_command(actor, self.a['device_id'], dict(id='clear', type='clear_workspace', payload={}, expires_at=now()+60000, confirmation=self.a['device_id']))
        self.assertError('device_quarantined', self.store.authenticate_token, self.a['device_token'])
        self.assertTrue(self.checkin()['revoked'])
        policy = self.checkin()
        self.assertEqual([], policy['commands'])
        self.assertEqual('clear', self.checkin(applied_policy_revision=policy['policy_revision'], security={'workspace_restricted': True})['commands'][0]['id'])

    def test_run_preview_matches_visible_text_including_whitespace(self):
        self.store.create_prompt(self.owner, dict(id='prompt', title='Exact preview', instruction='  Instruction\n',
                                                 blocks=[dict(id='text', kind='text', text='  first line\nsecond line  '),
                                                         dict(id='blank', kind='text', text=' \n ')]))
        run = self.store.reserve_run(self.owner, 'prompt', dict(revision=1, request_id='preview'))
        expected = '  Instruction\n\n\n  first line\nsecond line  '
        self.assertEqual(expected, run['snapshot']['preview'])
        self.workers.tick()
        self.assertEqual(expected, self.fake.submissions[0][0]['input'])

    def test_immutable_run_snapshot_survives_prompt_edit(self):
        self.prompt()
        run = self.store.reserve_run(self.a['workspace'], 'prompt', dict(revision=1, request_id='first'))
        lease = self.store.lease(self.a['workspace'], 'prompt')
        self.store.update_prompt(self.a['workspace'], 'prompt', dict(base_revision=1, lease_token=lease['lease_token'], title='changed', instruction='New instructions', blocks=[]))
        self.workers.tick()
        self.assertEqual('Do the work', self.fake.submissions[0][0]['input'])
        self.assertEqual('Do the work', self.store.runs(self.owner, run['id'])['snapshot']['instruction'])
        self.assertEqual(2, self.store.prompts(self.owner, 'prompt')['revision'])

    def test_prompt_attachment_provenance_is_immutable_and_new_chats_have_new_sessions(self):
        source = self.prompt()
        provenance = dict(id=source['id'], revision=source['revision'], title=source['title'])
        receipts = []
        for conversation in ('shared-first', 'shared-second'):
            self.store.create_prompt(self.owner, dict(id=conversation, title=source['title'], instruction='',
                blocks=[dict(id=conversation + '-message', kind='text', text='Do the work', prompt=provenance)]))
            receipts.append(self.store.reserve_run(self.owner, conversation, dict(revision=1, request_id=conversation)))
        self.workers.tick()
        self.assertEqual(2, len(self.fake.submissions))
        self.assertNotEqual(self.fake.submissions[0][0]['session_id'], self.fake.submissions[1][0]['session_id'])
        lease = self.store.lease(self.owner, 'prompt')
        self.store.update_prompt(self.owner, 'prompt', dict(base_revision=1, lease_token=lease['lease_token'], title='Edited', instruction='New instruction', blocks=[]))
        first = self.store.runs(self.owner, receipts[0]['id'])
        self.assertEqual(provenance, first['snapshot']['blocks'][0]['prompt'])
        self.assertEqual('Do the work', first['snapshot']['preview'])
        self.assertEqual('Do the work', self.fake.submissions[0][0]['input'])

    def test_prompt_attachment_rejects_invalid_provenance(self):
        for provenance in ('bad', dict(id='source', revision=0), dict(id='../bad', revision=1)):
            with self.assertRaises(HubError):
                self.store.create_prompt(self.owner, dict(id='invalid', title='Invalid', instruction='',
                    blocks=[dict(id='text', kind='text', text='Text', prompt=provenance)]))

    def test_unknown_submission_never_blindly_retried_and_safe_reconciliation(self):
        self.prompt()
        run = self.store.reserve_run(self.a['workspace'], 'prompt', dict(revision=1, request_id='first'))
        self.fake.fail_submission = True
        self.workers.tick()
        self.assertEqual('submission_unknown', self.store.runs(self.owner, run['id'])['status'])
        self.workers.tick()
        self.assertEqual(1, len(self.fake.submissions))
        self.assertError('submission_unknown', self.store.reserve_run, self.owner, 'prompt', dict(revision=1, request_id='second', run_again=True))
        self.fake.durable = False
        self.assertError('unsafe_retry', self.workers.reconcile, self.owner, run['id'])
        self.fake.durable = True
        self.fake.fail_submission = False
        self.workers.reconcile(self.owner, run['id'])
        self.assertEqual(self.fake.submissions[0], self.fake.submissions[1])
        self.assertEqual('running', self.store.runs(self.owner, run['id'])['status'])

    def test_reconcile_expired_retention_rejected(self):
        self.prompt()
        run = self.store.reserve_run(self.owner, 'prompt', dict(revision=1, request_id='first'))
        self.fake.fail_submission = True
        self.workers.tick()
        with self.store.transaction() as db:
            db.execute('UPDATE runs SET submitted_at=?', (now() - 3600001,))
        self.assertError('unsafe_retry', self.workers.reconcile, self.owner, run['id'])

    def test_restart_turns_inflight_submission_unknown(self):
        self.prompt()
        run = self.store.reserve_run(self.owner, 'prompt', dict(revision=1, request_id='first'))
        with self.store.transaction() as db:
            db.execute("UPDATE runs SET status='submitting'")
        reopened = Store(self.config)
        self.assertEqual('submission_unknown', reopened.runs(self.owner, run['id'])['status'])
        self.workers.tick()
        self.assertEqual([], self.fake.submissions)

    def test_audio_idempotence_transcription_and_source_preservation(self):
        media = self.store.save_media(self.a['workspace'], 'audio-one', 'audio/wav', b'RIFFexample')
        self.assertEqual(media, self.store.save_media(self.b['workspace'], 'audio-one', 'audio/wav', b'RIFFexample'))
        self.assertError('idempotency_conflict', self.store.save_media, self.owner, 'audio-one', 'audio/wav', b'different')
        note = self.store.create_note(self.a['workspace'], dict(id='voice', kind='audio', title='Voice', media_id='audio-one'))
        prompt = self.store.create_prompt(self.owner, dict(id='audio-prompt', blocks=[dict(id='block', kind='audio', note_id='voice', media_id='audio-one', text='')]))
        self.assertError('transcript_required', self.store.reserve_run, self.owner, prompt['id'], dict(request_id='no-transcript', revision=1))
        updated = self.workers.transcribe(self.b['workspace'], note['id'], {'base_revision': 1})
        self.assertEqual('transcribed words', updated['text'])
        self.store.delete_note(self.owner, 'voice', 2)
        self.assertEqual(b'RIFFexample', self.store.read_media(self.b['workspace'], 'audio-one')[1])
        self.assertEqual('voice', self.store.prompts(self.owner, 'audio-prompt')['blocks'][0]['note_id'])

    def test_transcription_does_not_overwrite_concurrent_note_edit(self):
        self.store.save_media(self.owner, 'audio', 'audio/wav', b'audio')
        self.store.create_note(self.owner, dict(id='voice', kind='audio', media_id='audio'))
        self.fake.on_transcribe = lambda: self.store.update_note(self.owner, 'voice', dict(base_revision=1, title='Edited', text='hand corrected'))
        self.assertError('revision_conflict', self.workers.transcribe, self.owner, 'voice', {'base_revision': 1})
        self.assertEqual('hand corrected', self.store.notes(self.owner, 'voice')['text'])

    def test_media_failure_never_exposes_partial_file(self):
        with patch('robotos_hub.store.os.fsync', side_effect=OSError('disk failure')):
            with self.assertRaises(OSError):
                self.store.save_media(self.owner, 'interrupted', 'audio/wav', b'audio')
        self.assertError('not_found', self.store.read_media, self.owner, 'interrupted')
        self.assertEqual([], list(self.store.media_dir.glob('.upload-*')))

    def test_lost_revokes_workspace_and_leases_but_preserves_recovery(self):
        self.prompt()
        self.store.lease(self.a['workspace'], 'prompt')
        self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=1, lost=True, lost_message='Call owner'))
        self.assertError('device_quarantined', self.store.authenticate_token, self.a['device_token'])
        self.assertError('device_quarantined', self.store.notes, self.a['workspace'])
        result = self.checkin()
        self.assertTrue(result['lost'])
        self.assertEqual('Call owner', result['lost_message'])
        self.assertEqual('prompt', self.store.prompts(self.b['workspace'])['items'][0]['id'])
        with self.store.connection() as db:
            self.assertEqual(0, db.execute('SELECT count(*) FROM leases').fetchone()[0])

    def test_lost_cancels_queued_device_run_before_upstream(self):
        self.prompt()
        run = self.store.reserve_run(self.a['workspace'], 'prompt', dict(revision=1, request_id='first'))
        self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=1, revoked=True))
        self.workers.tick()
        self.assertEqual([], self.fake.submissions)
        self.assertEqual('cancelled', self.store.runs(self.owner, run['id'])['status'])

    def test_offline_lost_policy_survives_restart_and_precedes_recovery_commands(self):
        self.checkin()
        self.store.create_command(self.owner, self.a['device_id'], dict(id='ring-offline', type='ring', payload={}, expires_at=now() + 60000))
        policy = self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=1, lost=True, lost_message='Return to owner'))
        self.store = Store(self.config)
        first = self.checkin(lost_applied=True)  # A legacy/stale bool is not a current policy acknowledgement.
        self.assertEqual(policy['policy_revision'], first['policy_revision'])
        self.assertTrue(first['lost'])
        self.assertEqual([], first['commands'])
        self.assertEqual(0, self.store.devices(self.owner, self.a['device_id'])['applied_policy_revision'])
        self.assertError('policy_not_applied', self.store.ack, self.a['recovery'], 'ring-offline', {'status': 'received'})
        self.assertError('policy_not_applied', self.checkin, applied_policy_revision=policy['policy_revision'])
        next_check = self.checkin(applied_policy_revision=policy['policy_revision'], security={'workspace_restricted': True})
        self.assertEqual(['ring-offline'], [command['id'] for command in next_check['commands']])
        receipt = self.store.ack(self.a['recovery'], 'ring-offline', {'status': 'received'})
        self.assertTrue(receipt['lost'])
        self.assertEqual(policy['policy_revision'], receipt['policy_revision'])
        self.assertIsNotNone(self.store.devices(self.owner, self.a['device_id'])['policy_applied_at'])

    def test_policy_acknowledgement_fences_later_loss_message_and_found_state(self):
        policy = self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=1, lost=True))
        revision = policy['policy_revision']
        self.checkin(applied_policy_revision=revision, security={'workspace_restricted': True})
        renamed = self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=policy['revision'], name='Renamed'))
        self.assertEqual(revision, renamed['policy_revision'])
        changed = self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=renamed['revision'], lost_message='New return details'))
        self.assertEqual(revision + 1, changed['policy_revision'])
        stale = self.checkin(applied_policy_revision=revision, lost_applied=True, security={'workspace_restricted': True})
        self.assertNotEqual(stale['policy_revision'], stale['applied_policy_revision'])
        found = self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=changed['revision'], lost=False))
        self.assertFalse(found['lost'])
        self.assertNotEqual(found['policy_revision'], found['applied_policy_revision'])
        self.checkin(applied_policy_revision=found['policy_revision'], security={'workspace_restricted': False})
        # A check-in sent before finding the R1 cannot restore the old restriction report.
        self.checkin(applied_policy_revision=revision, security={'workspace_restricted': True})
        latest = self.store.devices(self.owner, self.a['device_id'])
        self.assertEqual(found['policy_revision'], latest['applied_policy_revision'])
        self.assertFalse(latest['security']['workspace_restricted'])

    def test_quarantine_fences_received_action_admission_without_claiming_cancellation(self):
        self.checkin()
        action = dict(id='inflight', device_id=self.a['device_id'], tool='set_volume', arguments={'request_id': 'inflight', 'level': 20}, expires_at=now() + 60000)
        self.store.device_action(self.mcp, action)
        self.store.ack(self.a['recovery'], 'inflight', {'status': 'received'})
        self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=1, revoked=True))
        self.assertEqual('unknown', self.store.device_action(self.mcp, action_id='inflight')['status'])
        denied = self.assertError('device_quarantined', self.store.ack, self.a['recovery'], 'inflight', {'status': 'received'})
        self.assertTrue(denied.details['revoked'])
        self.assertEqual([], self.checkin()['commands'])
        # A final receipt from an action already admitted before revocation remains evidence.
        self.store.ack(self.a['recovery'], 'inflight', {'status': 'applied', 'result': {'volume': 20}})
        self.assertEqual('applied', self.store.device_action(self.mcp, action_id='inflight')['status'])

    def test_quarantine_blocks_maintenance_and_preserves_permitted_recovery(self):
        self.checkin(settings={'remote_management': True, 'allow_updates': True, 'allow_reboot': True})
        session, _ = self.store.login(self.config.owner_password)
        recent = self.store.session(session)
        for kind in ('update_app', 'reboot'):
            self.store.create_command(recent, self.a['device_id'], dict(id=kind, type=kind,
                payload={'version_code': 5000} if kind == 'update_app' else {}, expires_at=now() + 60000, confirmation=self.a['device_id']))
        self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=1, lost=True))
        self.assertEqual({'cancelled'}, {item['status'] for item in self.store.commands(self.owner, self.a['device_id'])['items']})
        self.assertError('device_quarantined', self.store.create_command, recent, self.a['device_id'],
                         dict(id='restart-lost', type='reboot', payload={}, expires_at=now() + 60000, confirmation=self.a['device_id']))
        self.assertEqual('queued', self.store.create_command(self.owner, self.a['device_id'],
                         dict(id='find', type='ring', payload={}, expires_at=now() + 60000))['status'])

    def test_only_owner_recovery_commands_survive_quarantine(self):
        self.checkin()
        for command_id, actor in [('owner-ring', self.owner), ('mcp-ring', self.mcp), ('mcp-received', self.mcp), ('legacy-ring', self.owner)]:
            self.store.create_command(actor, self.a['device_id'], dict(id=command_id, type='ring', payload={}, expires_at=now() + 60000))
        self.store.ack(self.a['recovery'], 'mcp-received', {'status': 'received'})
        with self.store.transaction() as db:
            db.execute("UPDATE commands SET issuer_role='' WHERE id='legacy-ring'")
        policy = self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=1, lost=True))
        receipts = {item['id']: item['status'] for item in self.store.commands(self.owner, self.a['device_id'])['items']}
        self.assertEqual({'owner-ring': 'queued', 'mcp-ring': 'cancelled', 'mcp-received': 'unknown', 'legacy-ring': 'cancelled'}, receipts)
        self.assertError('device_quarantined', self.store.create_command, self.mcp, self.a['device_id'],
                         dict(id='mcp-new', type='ring', payload={}, expires_at=now() + 60000))
        commands = self.checkin(applied_policy_revision=policy['policy_revision'], security={'workspace_restricted': True})['commands']
        self.assertEqual(['owner-ring'], [command['id'] for command in commands])
        self.assertError('device_quarantined', self.store.ack, self.a['recovery'], 'mcp-received', {'status': 'received'})

    def test_security_posture_refresh_does_not_grant_local_permissions(self):
        self.checkin(applied_policy_revision=1, security={'app_restriction_available': True, 'secure_lock_configured': False},
                     capabilities={'lock': False}, settings={'remote_management': False})
        self.checkin(applied_policy_revision=1, security={'app_restriction_available': True, 'secure_lock_configured': True,
                     'device_admin_active': True, 'secure_lock_available': False}, capabilities={'lock': False}, settings={'remote_management': False})
        device = self.store.devices(self.owner, self.a['device_id'])
        self.assertTrue(device['security']['secure_lock_configured'])
        self.assertFalse(device['security']['secure_lock_available'])
        self.assertFalse(device['applied_settings']['remote_management'])
        self.assertError('invalid_security', self.checkin, security={'secure_lock_configured': 'true'})
        self.assertError('invalid_security', self.checkin, security={'password': True})
        self.assertError('policy_revision_conflict', self.checkin, applied_policy_revision=100)

    def test_ordinary_settings_are_disabled_until_owner_restores_access(self):
        policy = self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=1, lost=True))
        self.assertError('device_quarantined', self.store.patch_device, self.owner, self.a['device_id'],
                         dict(base_revision=policy['revision'], settings={'brightness': 150}))
        renamed = self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=policy['revision'], name='Lost R1'))
        restored = self.store.patch_device(self.owner, self.a['device_id'],
                                          dict(base_revision=renamed['revision'], lost=False, settings={'brightness': 150}))
        self.assertEqual({'brightness': 150}, restored['desired_settings'])

    def test_command_handoff_rechecks_expiration_and_local_capability(self):
        self.checkin()
        self.store.create_command(self.owner, self.a['device_id'], dict(id='ring-race', type='ring', payload={}, expires_at=now() + 60000))
        self.checkin(capabilities={'ring': False})
        self.assertError('unsupported_capability', self.store.ack, self.a['recovery'], 'ring-race', {'status': 'received'})
        self.checkin()
        with self.store.transaction() as db:
            db.execute('UPDATE commands SET expires_at=0 WHERE id=?', ('ring-race',))
        self.assertError('command_expired', self.store.ack, self.a['recovery'], 'ring-race', {'status': 'received'})

    def test_v1_database_migration_preserves_offline_lost_devices(self):
        self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=1, lost=True, lost_message='Keep this policy'))
        with self.store.connection() as db:
            for column in ('policy_revision', 'applied_policy_revision', 'policy_applied_at', 'security'):
                db.execute('ALTER TABLE devices DROP COLUMN ' + column)
            db.execute('PRAGMA user_version=1')
            db.execute('ALTER TABLE commands DROP COLUMN issuer_role')
        self.store = Store(self.config)
        device = self.store.devices(self.owner, self.a['device_id'])
        self.assertTrue(device['lost'])
        self.assertEqual('Keep this policy', device['lost_message'])
        self.assertEqual(1, device['policy_revision'])
        self.assertEqual(0, device['applied_policy_revision'])
        self.assertError('device_quarantined', self.store.authenticate_token, self.a['device_token'])

    def test_commands_require_capability_local_permission_recent_auth_exact_target(self):
        command = dict(id='clear', type='clear_workspace', payload={}, expires_at=now() + 60000, confirmation=self.a['device_id'])
        self.assertError('recent_auth_required', self.store.create_command, self.owner, self.a['device_id'], command)
        session, _ = self.store.login(self.config.owner_password)
        recent = self.store.session(session)
        self.assertError('unsupported_capability', self.store.create_command, recent, self.a['device_id'], command)
        self.checkin()
        self.assertError('confirmation_required', self.store.create_command, recent, self.a['device_id'], dict(command, confirmation=self.b['device_id']))
        created = self.store.create_command(recent, self.a['device_id'], command)
        self.assertEqual('queued', created['status'])
        self.assertError('wrong_scope', self.store.create_command, self.mcp, self.a['device_id'], command)
        self.assertEqual(created, self.store.create_command(recent, self.a['device_id'], command))
        self.assertError('idempotency_conflict', self.store.create_command, recent, self.a['device_id'], dict(command, payload={'x': 1}))

    def test_command_receipts_exact_target_and_unknown_not_reissued(self):
        self.checkin()
        created = self.store.create_command(self.owner, self.a['device_id'], dict(id='ring', type='ring', payload={}, expires_at=now() + 60000))
        self.assertError('not_found', self.store.ack, self.b['recovery'], created['id'], {'status': 'applied'})
        self.store.ack(self.a['recovery'], created['id'], {'status': 'received'})
        self.store.ack(self.a['recovery'], created['id'], {'status': 'unknown'})
        self.assertEqual([], self.checkin()['commands'])
        self.assertError('receipt_conflict', self.store.ack, self.a['recovery'], created['id'], {'status': 'received'})
        self.store.ack(self.a['recovery'], created['id'], {'status': 'applied', 'result': {'stopped': True}})
        self.assertError('receipt_conflict', self.store.ack, self.a['recovery'], created['id'], {'status': 'failed'})

    def test_location_consent_and_expiry_honest_status(self):
        location = dict(latitude=1.2, longitude=3.4, accuracy=10, observed_at=now())
        self.checkin(location=location)
        self.assertEqual(location, self.store.devices(self.owner, self.a['device_id'])['location'])
        self.checkin(settings={'remote_management': True, 'location_reporting': False})
        self.assertIsNone(self.store.devices(self.owner, self.a['device_id'])['location'])
        self.assertError('location_not_permitted', self.checkin, settings={'location_reporting': False}, location=location)
        self.checkin()
        self.store.create_command(self.owner, self.a['device_id'], dict(id='ring', type='ring', payload={}, expires_at=now() + 1000))
        with self.store.transaction() as db:
            db.execute('UPDATE commands SET expires_at=0')
        self.assertEqual('expired', self.store.commands(self.owner, self.a['device_id'])['items'][0]['status'])

    def test_owner_name_is_canonical_across_stale_device_checkins(self):
        self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=1, name='New owner name'))
        checkin = self.checkin(name='Stale device name')
        self.assertEqual('New owner name', checkin['name'])
        self.assertEqual('New owner name', self.store.devices(self.owner, self.a['device_id'])['name'])

    def test_full_snapshot_lists_do_not_silently_discard_old_saved_items(self):
        with self.store.transaction() as db:
            for index in range(1005):
                identity = 'item-' + str(index)
                db.execute("INSERT INTO notes VALUES(?,?,?,'',NULL,1,0,0,'hash')", (identity, 'Title', 'text'))
                db.execute("INSERT INTO prompts VALUES(?,?,'','[]',1,0,0,'hash')", (identity, 'Title'))
                db.execute("INSERT INTO runs(id,prompt_id,prompt_revision,snapshot,status,created_at,updated_at) VALUES(?,?,1,'{}','completed',0,0)", (identity, identity))
        self.assertEqual(1005, len(self.store.notes(self.owner)['items']))
        self.assertEqual(1005, len(self.store.prompts(self.owner)['items']))
        self.assertEqual(1005, len(self.store.runs(self.owner)['items']))

    def test_safe_settings_only_and_applied_state_separate(self):
        self.assertError('local_permission_required', self.store.patch_device, self.owner, self.a['device_id'], dict(base_revision=1, settings={'remote_management': True}))
        self.assertError('invalid_settings', self.store.patch_device, self.owner, self.a['device_id'], dict(base_revision=1, settings={'credential': 'bad'}))
        row = self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=1, settings={'brightness': 100}))
        self.assertEqual({'brightness': 100}, row['desired_settings'])
        self.assertEqual({}, row['applied_settings'])
        self.checkin(settings_revision=1, settings={'brightness': 100})
        self.assertEqual(1, self.store.devices(self.owner, self.a['device_id'])['applied_settings_revision'])

    def test_device_tools_allowlist_identity_and_revocation(self):
        self.checkin()
        action = dict(id='speak', device_id=self.a['device_id'], tool='speak', arguments={'text': 'hello', 'request_id': 'speak'}, expires_at=now() + 60000)
        first = self.store.device_action(self.mcp, action)
        self.assertEqual('queued', first['status'])
        self.assertEqual(first, self.store.device_action(self.mcp, dict(action, expires_at=action['expires_at'] + 30000)))
        self.assertError('request_identity_required', self.store.device_action, self.mcp, dict(action, id='other'))
        self.assertError('tool_not_allowed', self.store.device_action, self.mcp, dict(action, tool='factory_reset'))
        self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=1, lost=True))
        self.assertEqual('cancelled', self.store.device_action(self.mcp, action_id='speak')['status'])
        self.assertError('device_quarantined', self.store.device_action, self.mcp, action)

    def test_native_device_tools_independent_of_mdm_opt_in(self):
        self.checkin(settings={'remote_management': False}, capabilities={'device_tool': True, 'ring': True})
        self.assertEqual('queued', self.store.device_action(self.mcp, dict(id='status', device_id=self.a['device_id'], tool='r1_status', arguments={}, expires_at=now()+60000))['status'])
        self.assertError('local_permission_required', self.store.create_command, self.owner, self.a['device_id'], dict(id='ring', type='ring', payload={}, expires_at=now()+60000))

    def test_open_app_uses_native_permissions_and_durable_command_identity(self):
        self.checkin(settings={'remote_management': False}, capabilities={'device_tool': True})
        action = dict(id='open-notes-1', device_id=self.a['device_id'], tool='open_app',
                      arguments={'app': 'notes', 'item_id': 'note-1', 'request_id': 'open-notes-1'}, expires_at=now()+60000)
        first = self.store.device_action(self.mcp, action)
        self.assertEqual('queued', first['status'])
        self.assertEqual(first, self.store.device_action(self.mcp, action))
        self.assertError('request_identity_required', self.store.device_action, self.mcp, dict(action, id='changed'))
        self.assertError('invalid_id', self.store.device_action, self.mcp, dict(action, arguments={'app': 'notes', 'item_id': '../notes', 'request_id': 'open-notes-1'}))
        self.assertError('invalid_arguments', self.store.device_action, self.mcp, dict(action, arguments={'app': 'com.android.settings', 'request_id': 'open-notes-1'}))
        self.assertError('invalid_arguments', self.store.device_action, self.mcp, dict(action, arguments={'app': 'settings', 'item_id': 'note-1', 'request_id': 'open-notes-1'}))
        self.store.patch_device(self.owner, self.a['device_id'], dict(base_revision=1, lost=True))
        self.assertEqual('cancelled', self.store.device_action(self.mcp, action_id='open-notes-1')['status'])
        self.assertError('device_quarantined', self.store.device_action, self.mcp, action)

    def test_resume_revision_and_change_feed(self):
        self.prompt()
        self.store.resume(self.a['workspace'], dict(base_revision=0, prompt_id='prompt', scroll_y=20))
        self.assertError('revision_conflict', self.store.resume, self.b['workspace'], dict(base_revision=0, prompt_id='prompt'))
        self.assertEqual(20, self.store.resume(self.b['workspace'])['scroll_y'])
        changes = self.store.changes(self.b['workspace'], 0)
        self.assertTrue(changes['cursor'] > 0)
        self.assertNotIn('device', {item['kind'] for item in changes['items']})

    def test_heartbeat_check_ins_add_no_change_rows(self):
        def device_changes():
            with self.store.connection() as db:
                return db.execute("SELECT COUNT(*) FROM changes WHERE kind='device'").fetchone()[0]
        self.checkin()
        before = device_changes()
        first_seen = self.store.devices(self.owner, self.a['device_id'])['last_seen']
        with patch('robotos_hub.store.now', return_value=now() + 30000):
            self.checkin()
            self.checkin()
        self.assertEqual(before, device_changes())
        self.assertTrue(self.store.devices(self.owner, self.a['device_id'])['last_seen'] > first_seen)
        self.checkin(battery=69)
        self.assertEqual(before + 1, device_changes())

    def test_change_feed_starts_at_head_and_resets_after_missed_history(self):
        self.prompt()
        latest = self.store.changes(self.a['workspace'], 'latest')
        head = latest['cursor']
        self.assertEqual({'cursor': head, 'more': False, 'items': []}, latest)
        self.assertEqual({'cursor': head, 'more': False, 'items': []}, self.store.changes(self.a['workspace'], head))
        self.store.resume(self.a['workspace'], dict(base_revision=0, prompt_id='prompt'))
        page = self.store.changes(self.a['workspace'], head)
        self.assertEqual(['resume'], [item['kind'] for item in page['items']])
        # A cursor past the head belongs to a replaced database.
        self.assertTrue(self.store.changes(self.a['workspace'], page['cursor'] + 5)['reset'])
        # Rows up to the old head age out; a client that read them continues, one that didn't reloads.
        with self.store.transaction() as db:
            db.execute('UPDATE changes SET created_at=0 WHERE seq<=?', (head,))
            self.store._prune_changes(db)
        self.assertNotIn('reset', self.store.changes(self.a['workspace'], head))
        reset = self.store.changes(self.a['workspace'], 0)
        self.assertEqual({'cursor': page['cursor'], 'more': False, 'reset': True, 'items': []}, reset)
        # With every row pruned, only a client at the head is current.
        with self.store.transaction() as db:
            db.execute('UPDATE changes SET created_at=0')
            self.store._prune_changes(db)
        self.assertNotIn('reset', self.store.changes(self.a['workspace'], page['cursor']))
        self.assertTrue(self.store.changes(self.a['workspace'], page['cursor'] - 1)['reset'])

    def test_change_feed_pages_say_when_more_follow(self):
        with self.store.transaction() as db:
            for i in range(1001):
                self.store.change(db, 'note', 'n%d' % i)
        first = self.store.changes(self.owner, 0)
        self.assertTrue(first['more'])
        self.assertEqual(1000, len(first['items']))
        second = self.store.changes(self.owner, first['cursor'])
        self.assertFalse(second['more'])
        self.assertEqual(self.store.changes(self.owner, 'latest')['cursor'], second['cursor'])


class HttpTest(HubFixture):
    def setUp(self):
        super().setUp()
        self.server = HubServer(self.config, self.store, self.workers)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_address[1]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        super().tearDown()

    def request(self, method, path, body=None, headers=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=5)
        headers = dict(headers or {})
        if body is not None and not isinstance(body, bytes):
            headers.setdefault('Content-Type', 'application/json')
            body = canonical(body).encode()
        connection.request(method, '/api/v1/' + path, body=body, headers=headers)
        result = connection.getresponse()
        data = result.read()
        status, response_headers = result.status, dict(result.getheaders())
        connection.close()
        return status, json.loads(data), response_headers

    def test_readiness_requires_database_and_running_worker_but_liveness_remains(self):
        self.assertEqual(503, self.request('GET', 'ready')[0])
        self.workers.start()
        try:
            self.assertEqual((200, {'status': 'ok'}), self.request('GET', 'ready')[:2])
            with patch.object(self.store, 'connection', side_effect=sqlite3.OperationalError('private database error')):
                self.assertEqual((503, {'status': 'not_ready'}), self.request('GET', 'ready')[:2])
                self.assertEqual(200, self.request('GET', 'health')[0])
        finally:
            self.workers.stop()
        self.assertEqual(503, self.request('GET', 'ready')[0])

    def test_cookie_csrf_origin_and_no_secret_session_body(self):
        status, body, headers = self.request('POST', 'session', {'password': self.config.owner_password})
        self.assertEqual(200, status)
        cookie = headers['Set-Cookie'].split(';')[0]
        self.assertIn('HttpOnly', headers['Set-Cookie'])
        self.assertIn('SameSite=Strict', headers['Set-Cookie'])
        self.assertNotIn(self.config.owner_password, canonical(body))
        self.assertEqual(403, self.request('POST', 'enrollments', {}, {'Cookie': cookie})[0])
        self.assertEqual(403, self.request('POST', 'enrollments', {}, {'Cookie': cookie, 'X-CSRF-Token': body['csrf_token'], 'Origin': 'https://evil.example'})[0])
        self.assertEqual(200, self.request('POST', 'enrollments', {}, {'Cookie': cookie, 'X-CSRF-Token': body['csrf_token']})[0])
        self.assertEqual(200, self.request('GET', 'session', headers={'Cookie': cookie})[0])
        self.assertEqual(200, self.request('POST', 'logout', {}, {'Cookie': cookie, 'X-CSRF-Token': body['csrf_token']})[0])
        self.assertEqual(401, self.request('GET', 'session', headers={'Cookie': cookie})[0])

    def test_change_cursor_accepts_latest_and_rejects_other_words(self):
        headers = {'Authorization': 'Bearer ' + self.a['device_token']}
        status, body, _ = self.request('GET', 'changes?after=latest', headers=headers)
        self.assertEqual(200, status)
        self.assertEqual([], body['items'])
        self.assertEqual(200, self.request('GET', 'changes?after=' + str(body['cursor']), headers=headers)[0])
        self.assertEqual(400, self.request('GET', 'changes?after=newest', headers=headers)[0])

    def test_http_scopes_dns_rebinding_limits_and_upload(self):
        self.assertEqual(421, self.request('GET', 'health', headers={'Host': 'evil.example'})[0])
        self.assertEqual(403, self.request('GET', 'notes', headers={'Authorization': 'Bearer ' + self.a['recovery_token']})[0])
        self.assertEqual(413, self.request('POST', 'notes', b'', {'Authorization': 'Bearer ' + self.a['device_token'], 'Content-Type': 'application/json', 'Content-Length': str(self.config.max_json_bytes + 1)})[0])
        status, media, _ = self.request('POST', 'media', b'audio', {'Authorization': 'Bearer ' + self.a['device_token'], 'Content-Type': 'audio/wav', 'X-Upload-Id': 'voice'})
        self.assertEqual(200, status)
        self.assertEqual(5, media['size'])
        self.assertEqual(403, self.request('GET', 'media/voice', headers={'Authorization': 'Bearer ' + self.a['recovery_token']})[0])

    def test_real_mcp_http_adapter_preserves_expiry_on_duplicate_and_receipt(self):
        from types import SimpleNamespace
        from server.robotos_mcp import TOOLS
        from server.robotos_mcp.hub import Hub
        self.checkin()
        adapter = Hub('http://127.0.0.1:' + str(self.port), self.config.mcp_token, TOOLS, receipt_wait_seconds=0)
        arguments = {'device_id': self.a['device_id'], 'text': 'Hello', 'request_id': 'speak-request'}
        timestamp = now() / 1000
        with patch('server.robotos_mcp.hub.time', SimpleNamespace(time=lambda: timestamp)):
            _, failed, first = adapter.run_tool('speak', arguments)
        self.assertFalse(failed, first)
        with patch('server.robotos_mcp.hub.time', SimpleNamespace(time=lambda: timestamp + 30)):
            _, failed, repeated = adapter.run_tool('speak', arguments)
        self.assertFalse(failed, repeated)
        self.assertEqual(first['expires_at'], repeated['expires_at'])
        self.assertEqual(first['id'], repeated['id'])
        headers = {'Authorization': 'Bearer ' + self.a['recovery_token']}
        status, _, _ = self.request('POST', 'devices/self/commands/speak-request/ack',
                                   {'status': 'applied', 'result': {'http_status': 200, 'body': {'spoken': True}}}, headers)
        self.assertEqual(200, status)
        _, failed, receipt = adapter.run_tool('device_action_status', {'id': 'speak-request'})
        self.assertFalse(failed)
        self.assertEqual('applied', receipt['status'])
        self.assertEqual({'spoken': True}, receipt['result']['body'])

    def test_interrupted_http_upload_not_registered(self):
        client = socket.create_connection(('127.0.0.1', self.port))
        request = ('POST /api/v1/media HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nAuthorization: Bearer %s\r\nContent-Type: audio/wav\r\nX-Upload-Id: truncated\r\nContent-Length: 100\r\n\r\nabc' % (self.port, self.a['device_token'])).encode()
        client.sendall(request)
        client.shutdown(socket.SHUT_WR)
        response = client.recv(4096)
        client.close()
        self.assertIn(b'400', response)
        self.assertError('not_found', self.store.read_media, self.owner, 'truncated')


if __name__ == '__main__':
    unittest.main()
