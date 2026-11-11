import json
import os
from pathlib import Path
import subprocess
import sys
import unittest

from test_sync import SyncTests

SCRIPT = Path(__file__).with_name('full_sync.py').resolve()


class FullSyncTests(SyncTests):
    # 不重复继承源码模式测试；只复用临时 Git 仓库的操作方法。
    def run_full(self, *args, ok=True):
        p = subprocess.run([sys.executable, str(SCRIPT), '--upstream', 'upstream',
                            '--artifacts', str(self.artifacts), *args], cwd=self.repo, capture_output=True,
                           env={**os.environ, 'GITHUB_OUTPUT': '', 'GITHUB_STEP_SUMMARY': ''})
        result = json.loads((self.artifacts / 'result.json').read_text(encoding='utf-8'))
        self.assertEqual(p.returncode == 0, ok, p.stderr.decode('utf-8', 'replace'))
        return result

    def test_full_imports_workflows_and_history_without_filtering(self):
        sha = self.upstream({'app.txt': 'new\n', '.github/workflows/new.yml': 'name: new\n'})
        self.run_full()
        self.assertEqual((self.repo / '.github/workflows/new.yml').read_text(), 'name: new\n')
        self.assertEqual(self.g('merge-base', 'HEAD', 'upstream'), sha)
        head = self.g('rev-parse', 'HEAD')
        self.assertFalse(self.run_full()['changed'])
        self.assertEqual(self.g('rev-parse', 'HEAD'), head)

    def test_migration_does_not_reapply_source_or_lose_personal_changes(self):
        self.upstream({'app.txt': 'v2\n', '.github/workflows/new.yml': 'name: v2\n'})
        self.run_sync()
        baseline = self.g('rev-parse', 'upstream')
        self.put('app.txt', 'personal after v2\n')
        self.commit('personal after source sync')
        latest = self.upstream({'another.txt': 'v3\n', '.github/workflows/new.yml': 'name: v3\n'})
        result = self.run_full()
        self.assertTrue(result['migration'])
        self.assertEqual((self.repo / 'app.txt').read_text(), 'personal after v2\n')
        self.assertEqual((self.repo / 'another.txt').read_text(), 'v3\n')
        self.assertEqual((self.repo / '.github/workflows/new.yml').read_text(), 'name: v3\n')
        self.assertEqual(self.g('merge-base', baseline, 'HEAD'), baseline)
        self.assertEqual(self.g('merge-base', latest, 'HEAD'), latest)

    def test_real_conflict_after_migration_never_pushes(self):
        self.upstream({'app.txt': 'v2\n'})
        self.run_sync()
        self.put('app.txt', 'personal\n')
        before = self.commit('personal')
        remote = self.root / 'origin.git'
        subprocess.run(['git', 'init', '--bare', str(remote)], check=True, capture_output=True)
        self.g('remote', 'add', 'origin', str(remote))
        self.g('push', 'origin', 'main')
        self.upstream({'app.txt': 'upstream v3\n'})
        self.run_full('--push', ok=False)
        actual = subprocess.check_output(['git', '--git-dir', str(remote), 'rev-parse', 'main']).decode().strip()
        self.assertEqual(actual, before)

    def test_migration_workflow_conflict_stops(self):
        self.upstream({'.github/workflows/old.yml': 'name: upstream\n'})
        self.run_sync()
        self.put('.github/workflows/old.yml', 'name: personal\n')
        self.commit('personal workflow')
        self.assertIn('冲突', self.run_full(ok=False)['error'])


# 只运行本文件定义的完整同步测试，避免重复运行父类的源码模式测试。
for name in list(SyncTests.__dict__):
    if name.startswith('test_'):
        setattr(FullSyncTests, name, None)

if __name__ == '__main__':
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(FullSyncTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    sys.exit(0 if result.wasSuccessful() else 1)
