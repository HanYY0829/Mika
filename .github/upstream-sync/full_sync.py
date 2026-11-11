#!/usr/bin/env python3
"""完整合并上游；一次性迁移旧补丁记录，之后使用普通 Git 合并。"""
import argparse
import json
import os
from pathlib import Path
import sys

from sync import (SyncError, SOURCE_STATE, WORKFLOW_STATE, PENDING, RESERVED,
                  SENSITIVE, git, text, valid_sha, ancestor, changed, matches,
                  patch, write, publish)


def stage_state(target):
    write(SOURCE_STATE, target + '\n')
    write(WORKFLOW_STATE, target + '\n')
    if Path(PENDING).exists():
        Path(PENDING).unlink()
    git('add', '--', SOURCE_STATE, WORKFLOW_STATE)
    if git('ls-files', '--', PENDING).stdout:
        git('add', '-A', '--', PENDING)


def checkpoint(message):
    if git('ls-files', '--unmerged', '-z').stdout:
        raise SyncError('仍有未解决冲突；不提交或推送。')
    merge_head = git('rev-parse', '--verify', 'MERGE_HEAD', check=False).returncode == 0
    staged = bool(git('diff', '--cached', '--name-only', '-z').stdout)
    if merge_head or staged:
        git('commit', '-m', message)


def apply_full(args, result):
    if text('status', '--porcelain'):
        raise SyncError('工作区不干净；拒绝覆盖个人修改。')
    if text('rev-parse', '--is-shallow-repository') != 'false':
        raise SyncError('完整同步需要完整提交历史。')
    initial = text('rev-parse', 'HEAD')
    target = valid_sha(text('rev-parse', args.upstream))
    common = valid_sha(text('merge-base', 'HEAD', target))
    source_base = valid_sha(Path(SOURCE_STATE).read_text().strip()) if Path(SOURCE_STATE).exists() else common
    workflow_base = valid_sha(Path(WORKFLOW_STATE).read_text().strip()) if Path(WORKFLOW_STATE).exists() else common
    ancestor(source_base, target)
    ancestor(workflow_base, source_base)
    result.update(initial_head=initial, upstream=target, source_base=source_base,
                  workflow_base=workflow_base, migration=False)
    collisions = [p for p in changed(source_base, target) if matches(p, RESERVED)]
    if collisions:
        raise SyncError('上游修改同步引擎的保留路径，需要人工处理：' + repr(collisions))
    git('config', 'user.name', 'github-actions[bot]')
    git('config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
    args.artifacts.mkdir(parents=True, exist_ok=True)

    # 旧方案已应用普通源码，但没有记录上游为祖先；不能直接重新合并旧历史。
    missing_history = git('merge-base', '--is-ancestor', source_base, 'HEAD', check=False).returncode != 0
    if missing_history or workflow_base != source_base:
        paths = [p for p in changed(workflow_base, source_base, SENSITIVE) if not matches(p, RESERVED)]
        old_workflows = patch(workflow_base, source_base, paths) if paths else b''
        (args.artifacts / 'migration-workflows.patch').write_bytes(old_workflows)
        if missing_history:
            # 仅用于已由旧状态确认同步过的基线；不会用此策略跳过基线之后的新源码。
            git('merge', '--no-commit', '--no-ff', '--strategy=ours', source_base)
        if old_workflows:
            applied = git('apply', '--3way', '--index', '--whitespace=nowarn', '-', data=old_workflows, check=False)
            (args.artifacts / 'migration-apply.log').write_bytes(applied.stderr)
            if applied.returncode:
                raise SyncError('补齐旧工作流时与个人修改冲突；不推送，不推进远端记录。')
        stage_state(source_base)
        checkpoint(f'Migrate recorded upstream baseline {source_base[:12]} to full-history sync')
        result['migration'] = True

    # 从基线以后的变化使用真正的三方合并，与网页同步按钮的合并语义一致。
    merge = git('merge', '--no-commit', '--no-ff', target, check=False)
    (args.artifacts / 'merge.log').write_bytes(merge.stdout + merge.stderr)
    if merge.returncode:
        raise SyncError('完整合并遇到冲突；已保留现场，远端未改变。')
    stage_state(target)
    checkpoint(f'Sync full upstream Mika to {target[:12]}')
    ancestor(target, text('rev-parse', 'HEAD'))
    result.update(status='complete', changed=initial != text('rev-parse', 'HEAD'),
                  commit=text('rev-parse', 'HEAD'), pushed=False)
    if args.push and result['changed']:
        publish(args.remote, args.branch, initial)
        result['pushed'] = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', default='upstream/main')
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--push', action='store_true')
    parser.add_argument('--remote', default='origin')
    parser.add_argument('--branch', default='main')
    args = parser.parse_args()
    result = {'status': 'failed', 'mode': 'full', 'pending_paths': [], 'pushed': False}
    code = 0
    try:
        apply_full(args, result)
    except (SyncError, OSError, UnicodeError) as exc:
        result.update(status='failed', error=str(exc))
        print(str(exc), file=sys.stderr)
        code = 1
    finally:
        args.artifacts.mkdir(parents=True, exist_ok=True)
        (args.artifacts / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        (args.artifacts / 'git-status.txt').write_bytes(git('status', '--porcelain').stdout)
        (args.artifacts / 'conflicted.diff').write_bytes(git('diff').stdout)
        (args.artifacts / 'staged.diff').write_bytes(git('diff', '--cached').stdout)
        summary = '# Mika 完整上游同步\n\n' + ('完整同步成功（包含工作流及上游历史）。' if code == 0 else '同步失败，不能视为完成。\n\n' + result['error'])
        summary += f'\n\n上游版本：`{result.get("upstream", "未知")}`\n'
        (args.artifacts / 'summary.md').write_text(summary, encoding='utf-8')
        if os.getenv('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as f:
                f.write(summary)
    return code


if __name__ == '__main__':
    sys.exit(main())
