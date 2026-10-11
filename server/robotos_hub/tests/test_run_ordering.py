"""Concurrent worker and HTTP control responses must not resurrect resolved approvals."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import tempfile
import threading
import unittest

from robotos_hub.config import Config
from robotos_hub.store import Store
from robotos_hub.upstream import Workers


class DelayedHermes:
    identity = 'ordering-fixture'

    def __init__(self):
        self.delayed_poll_started = threading.Event()
        self.release_delayed_poll = threading.Event()
        self.approval_sent = threading.Event()
        self.approved = False
        self.delay_next_poll = False

    def capabilities(self):
        return dict(runs=True, durable=True, retention_seconds=3600, stop=True, approval=True)

    def hermes(self, method, path, payload=None, headers=None):
        if path == '/v1/runs':
            return {'run_id': 'remote-run'}
        if path.endswith('/approval'):
            self.approved = True
            self.approval_sent.set()
            return {'run_id': 'remote-run', 'request_id': 'approval-1', 'resolved': 1}
        result = {'run_id': 'remote-run', 'status': 'running' if self.approved else 'waiting_for_approval',
                  'output': '', 'error': '', 'approval': None if self.approved else {'request_id': 'approval-1'}}
        if self.delay_next_poll:
            self.delay_next_poll = False
            # This response contains the pre-approval snapshot, even if its delivery is delayed.
            self.delayed_poll_started.set()
            if not self.release_delayed_poll.wait(5):
                raise AssertionError('The test did not release the delayed status response')
        return result


class RunOrderingTest(unittest.TestCase):
    def test_approval_control_cannot_overtake_an_older_poll_and_resurrect_approval(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Config(Path(directory), 'ordering-test-password', owner_token='o' * 40))
            owner = store.authenticate_token('o' * 40)
            store.create_prompt(owner, {'id': 'prompt', 'instruction': 'A test request'})
            run = store.reserve_run(owner, 'prompt', {'request_id': 'submit-1', 'revision': 1})
            hermes = DelayedHermes()
            workers = Workers(store, hermes)
            workers.submit(run['id'])
            with store.connection() as db:
                row = dict(store._row(db, 'runs', run['id']))
            workers.poll(row)
            self.assertEqual('waiting_for_approval', store.runs(owner, run['id'])['status'])

            hermes.delay_next_poll = True
            control_started = threading.Event()

            def approve():
                control_started.set()
                return workers.control(owner, run['id'], 'approval', {
                    'request_id': 'control-1', 'approval_id': 'approval-1', 'choice': 'once'})

            with ThreadPoolExecutor(max_workers=2) as pool:
                delayed = pool.submit(workers.poll, row)
                self.assertTrue(hermes.delayed_poll_started.wait(3))
                control = pool.submit(approve)
                self.assertTrue(control_started.wait(3))
                try:
                    overtook_poll = hermes.approval_sent.wait(0.5)
                finally:
                    hermes.release_delayed_poll.set()
                delayed.result(timeout=3)
                confirmed = control.result(timeout=3)

            self.assertFalse(overtook_poll, 'Control crossed the network before the preceding poll committed')
            self.assertEqual('applied', confirmed['control']['status'])
            final = store.runs(owner, run['id'])
            self.assertEqual('running', final['status'])
            self.assertIsNone(final['approval'])


if __name__ == '__main__':
    unittest.main()
