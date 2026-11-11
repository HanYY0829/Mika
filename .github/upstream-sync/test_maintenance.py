import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('maintenance', Path(__file__).with_name('maintenance.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class MaintenanceTests(unittest.TestCase):
    def test_unchanged_issue_does_not_repeat_notification(self):
        item = {'number': 7, 'body': '<!-- marker -->\nbody', 'state': 'open'}
        with patch.object(m, 'api', return_value=[item]) as api:
            m.issue('owner/repo', '<!-- marker -->', 'title', 'body', True)
            self.assertEqual(api.call_count, 1)

    def test_closed_issue_reopens_instead_of_creating_duplicate(self):
        item = {'number': 7, 'body': '<!-- marker -->\nbody', 'state': 'closed'}
        with patch.object(m, 'api', side_effect=[[item], {}]) as api:
            m.issue('owner/repo', '<!-- marker -->', 'title', 'body', True)
            self.assertEqual(api.call_args.args[0], 'repos/owner/repo/issues/7')
            self.assertEqual(api.call_args.args[2]['state'], 'open')

    def test_recovery_closes_failure(self):
        item = {'number': 7, 'body': '<!-- marker -->\nbody', 'state': 'open'}
        with patch.object(m, 'api', side_effect=[[item], {}]) as api:
            m.issue('owner/repo', '<!-- marker -->', 'title', '', False)
            self.assertEqual(api.call_args.args[2]['state'], 'closed')

    def test_issue_search_paginates(self):
        page = [{'number': i, 'body': None} for i in range(100)]
        item = {'number': 107, 'body': '<!-- marker -->\nbody', 'state': 'open'}
        with patch.object(m, 'api', side_effect=[page, [item]]) as api:
            m.issue('owner/repo', '<!-- marker -->', 'title', 'body', True)
            self.assertEqual(api.call_count, 2)
            self.assertIn('page=2', api.call_args.args[0])

    def test_monthly_heartbeat_is_idempotent(self):
        month = m.datetime.now(m.timezone.utc).strftime('%Y-%m')
        ref = {'ref': 'refs/heads/automation/upstream-sync-heartbeat', 'object': {'sha': 'a' * 40}}
        with patch.object(m, 'api', side_effect=[[ref], {'message': f'chore: keep upstream sync schedule active ({month})'}]) as api:
            m.heartbeat('owner/repo')
            self.assertEqual(api.call_count, 2)

    def test_heartbeat_push_never_forces(self):
        ref = {'ref': 'refs/heads/automation/upstream-sync-heartbeat', 'object': {'sha': 'a' * 40}}
        with patch.object(m, 'api', side_effect=[[ref], {'message': 'old', 'tree': {'sha': 'b' * 40}}, {'sha': 'c' * 40}, {}]) as api:
            m.heartbeat('owner/repo')
            self.assertIs(api.call_args.args[2]['force'], False)
            self.assertNotIn('refs/heads/main', api.call_args.args[0])


if __name__ == '__main__':
    unittest.main()
