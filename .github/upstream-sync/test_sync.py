"""使用临时 Git 仓库测试真实补丁、提交、冲突与远端推送，不访问生产仓库。"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('sync.py').resolve()


class SyncTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        self.g('init', '-b', 'main')
        self.g('config', 'user.name', 'Test')
        self.g('config', 'user.email', 'test@example.invalid')
        self.g('config', 'core.autocrlf', 'false')
        self.put('app.txt', 'base\n')
        self.put('.github/workflows/old.yml', 'name: old\n')
        self.base = self.commit('base')
        self.g('branch', 'upstream')
        self.put('.github/upstream-sync-state', self.base + '\n')
        self.put('.github/workflows/sync-upstream.yml', 'name: protected\n')
        self.commit('fork')
        self.artifacts = self.root / 'artifacts'

    def g(self, *args, check=True):
        p = subprocess.run(['git', *args], cwd=self.repo, capture_output=True)
        if check and p.returncode:
            self.fail(p.stderr.decode('utf-8', 'replace'))
        return p.stdout.decode('utf-8', 'replace').strip()

    def put(self, path, value):
        p = self.repo / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(value, encoding='utf-8', newline='\n')

    def commit(self, message):
        self.g('add', '-A')
        self.g('commit', '-m', message)
        return self.g('rev-parse', 'HEAD')

    def upstream(self, edits):
        self.g('checkout', 'upstream')
        for path, value in edits.items():
            if value is None:
                (self.repo / path).unlink()
            else:
                self.put(path, value)
        sha = self.commit('upstream change')
        self.g('checkout', 'main')
        return sha

    def run_sync(self, *args, ok=True):
        p = subprocess.run([sys.executable, str(SCRIPT), '--upstream', 'upstream',
                            '--artifacts', str(self.artifacts), *args],
                           cwd=self.repo, capture_output=True, env={
                               **os.environ, 'GITHUB_OUTPUT': '', 'GITHUB_STEP_SUMMARY': ''})
        result = json.loads((self.artifacts / 'result.json').read_text(encoding='utf-8'))
        self.assertEqual(p.returncode == 0, ok, p.stderr.decode('utf-8', 'replace'))
        return result

    def test_source_preserves_personal_changes_and_is_idempotent(self):
        self.put('personal.txt', 'keep me\n')
        self.commit('personal')
        sha = self.upstream({'app.txt': 'new\n'})
        first = self.run_sync()
        self.assertEqual(first['status'], 'complete')
        self.assertEqual((self.repo / 'app.txt').read_text(), 'new\n')
        self.assertEqual((self.repo / 'personal.txt').read_text(), 'keep me\n')
        self.assertEqual((self.repo / '.github/upstream-sync-state').read_text().strip(), sha)
        head = self.g('rev-parse', 'HEAD')
        second = self.run_sync()
        self.assertFalse(second['changed'])
        self.assertEqual(head, self.g('rev-parse', 'HEAD'))

    def test_workflow_add_modify_delete_rename_requires_exact_review(self):
        sha = self.upstream({'.github/workflows/old.yml': None,
                             '.github/workflows/new.yml': 'name: renamed\n',
                             '.github/actions/local/action.yml': 'name: local\n',
                             'app.txt': 'updated\n'})
        result = self.run_sync()
        self.assertEqual(result['status'], 'pending_review')
        self.assertEqual(len(result['pending_paths']), 3)
        self.assertTrue((self.repo / '.github/workflows/old.yml').exists())
        self.assertFalse((self.repo / '.github/workflows/new.yml').exists())
        self.run_sync('--mode', 'review-workflows', '--upstream-sha', sha,
                      '--review-digest', '0' * 64, ok=False)
        applied = self.run_sync('--mode', 'review-workflows', '--upstream-sha', sha,
                                '--review-digest', result['review_digest'])
        self.assertEqual(applied['status'], 'complete')
        self.assertFalse((self.repo / '.github/workflows/old.yml').exists())
        self.assertTrue((self.repo / '.github/workflows/new.yml').exists())
        self.assertEqual((self.repo / '.github/workflows/sync-upstream.yml').read_text(), 'name: protected\n')
        head = self.g('rev-parse', 'HEAD')
        self.assertFalse(self.run_sync()['changed'])
        self.assertEqual(head, self.g('rev-parse', 'HEAD'))

    def test_conflict_never_commits_or_advances_state(self):
        self.put('app.txt', 'my version\n')
        head = self.commit('personal')
        self.upstream({'app.txt': 'their version\n'})
        result = self.run_sync(ok=False)
        self.assertEqual(result['status'], 'failed')
        self.assertEqual(head, self.g('rev-parse', 'HEAD'))
        self.assertEqual((self.repo / '.github/upstream-sync-state').read_text().strip(), self.base)
        self.assertIn('app.txt', (self.artifacts / 'conflicts.txt').read_text())

    def test_disjoint_edits_same_file_merge(self):
        self.put('app.txt', 'a\nb\nc\nd\ne\nf\ng\n')
        base = self.commit('long base')
        self.g('branch', '-f', 'upstream', base)
        self.put('.github/upstream-sync-state', base + '\n')
        self.put('app.txt', 'personal\nb\nc\nd\ne\nf\ng\n')
        self.commit('personal')
        self.upstream({'app.txt': 'a\nb\nc\nd\ne\nf\nupstream\n'})
        self.run_sync()
        value = (self.repo / 'app.txt').read_text()
        self.assertIn('personal', value)
        self.assertIn('upstream', value)

    def test_rewritten_history_stops(self):
        sha = self.upstream({'app.txt': 'v2\n'})
        self.run_sync()
        self.g('branch', '-f', 'upstream', self.base)
        result = self.run_sync(ok=False)
        self.assertIn('历史重写', result['error'])
        self.assertEqual((self.repo / '.github/upstream-sync-state').read_text().strip(), sha)

    def test_malformed_state_and_dirty_tree_stop(self):
        self.put('.github/upstream-sync-state', 'not-a-sha\n')
        self.commit('broken metadata')
        self.assertIn('40', self.run_sync(ok=False)['error'])
        self.put('untracked.txt', 'do not delete')
        self.assertIn('不干净', self.run_sync(ok=False)['error'])

    def test_reserved_engine_path_collision_stops(self):
        self.upstream({'.github/workflows/sync-upstream.yml': 'malicious override\n'})
        self.assertIn('保留路径', self.run_sync(ok=False)['error'])

    def test_personal_workflow_change_invalidates_review(self):
        sha = self.upstream({'.github/workflows/old.yml': 'name: upstream\n'})
        result = self.run_sync()
        self.put('.github/workflows/old.yml', 'name: personal\n')
        self.commit('personal workflow')
        self.run_sync('--mode', 'review-workflows', '--upstream-sha', sha,
                      '--review-digest', result['review_digest'], ok=False)
        fresh = self.run_sync()
        self.assertNotEqual(result['review_digest'], fresh['review_digest'])
        self.run_sync('--mode', 'review-workflows', '--upstream-sha', sha,
                      '--review-digest', fresh['review_digest'], ok=False)
        self.assertEqual(self.g('show', 'HEAD:.github/workflows/old.yml'), 'name: personal')

    def test_upstream_moved_after_review_does_not_sneak_in(self):
        sha = self.upstream({'.github/workflows/new.yml': 'name: reviewed\n'})
        result = self.run_sync()
        self.upstream({'.github/workflows/new.yml': 'name: unreviewed\n'})
        self.run_sync('--mode', 'review-workflows', '--upstream-sha', sha,
                      '--review-digest', result['review_digest'])
        self.assertEqual((self.repo / '.github/workflows/new.yml').read_text(), 'name: reviewed\n')
        self.assertEqual(self.run_sync()['status'], 'pending_review')

    def test_binary_and_source_deletion(self):
        self.g('checkout', 'upstream')
        (self.repo / 'binary.bin').write_bytes(bytes(range(256)))
        (self.repo / 'app.txt').unlink()
        self.commit('binary and deletion')
        self.g('checkout', 'main')
        self.run_sync()
        self.assertEqual((self.repo / 'binary.bin').read_bytes(), bytes(range(256)))
        self.assertFalse((self.repo / 'app.txt').exists())

    def test_upstream_markdown_line_breaks_preserve_exact_bytes(self):
        self.upstream({'notes.md': 'Markdown hard break  \nnext line\n'})
        self.run_sync()
        self.assertEqual((self.repo / 'notes.md').read_bytes(), b'Markdown hard break  \nnext line\n')

    def test_real_push_and_competing_remote_ref_are_safe(self):
        remote = self.root / 'origin.git'
        subprocess.run(['git', 'init', '--bare', str(remote)], check=True, capture_output=True)
        self.g('remote', 'add', 'origin', str(remote))
        self.g('push', 'origin', 'main')
        self.upstream({'app.txt': 'new\n'})
        result = self.run_sync('--push')
        self.assertTrue(result['pushed'])
        self.assertEqual(self.g('rev-parse', 'HEAD'), self.g('rev-parse', 'origin/main'))
        # 另一写入者推进远端，运行仍基于旧检出；普通推送必须被拒绝。
        head = self.g('rev-parse', 'HEAD')
        self.g('checkout', '-b', 'competitor')
        self.put('personal.txt', 'concurrent change\n')
        competitor = self.commit('concurrent')
        self.g('push', 'origin', 'HEAD:main')
        self.g('checkout', 'main')
        self.upstream({'app.txt': 'newer\n'})
        result = self.run_sync('--push', ok=False)
        self.assertIn('推送失败', result['error'])
        actual = subprocess.check_output(['git', '--git-dir', str(remote), 'rev-parse', 'main']).decode().strip()
        self.assertEqual(actual, competitor)
        self.assertNotEqual(head, self.g('rev-parse', 'HEAD'))


if __name__ == '__main__':
    unittest.main()
