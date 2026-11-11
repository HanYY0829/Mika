#!/usr/bin/env python3
"""同步状态通知与每月一次的独立分支保活；不依赖第三方 Python 包。"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess


def api(endpoint, method='GET', body=None):
    args = ['gh', 'api', endpoint, '--method', method]
    data = None
    if body is not None:
        args += ['--input', '-']
        data = json.dumps(body).encode()
    p = subprocess.run(args, input=data, capture_output=True)
    if p.returncode:
        raise RuntimeError(p.stderr.decode('utf-8', 'replace'))
    return json.loads(p.stdout) if p.stdout.strip() else None


def issue(repo, marker, title, body, active):
    found = None
    for page in range(1, 101):
        items = api(f'repos/{repo}/issues?state=all&creator=github-actions%5Bbot%5D&per_page=100&page={page}')
        found = next((x for x in items if (x.get('body') or '').startswith(marker)), None)
        if found or len(items) < 100:
            break
    if active:
        content = marker + '\n' + body
        payload = {'title': title, 'body': content, 'state': 'open'}
        if found:
            if found['body'] != content or found['state'] != 'open':
                api(f'repos/{repo}/issues/{found["number"]}', 'PATCH', payload)
        else:
            api(f'repos/{repo}/issues', 'POST', payload)
    elif found and found['state'] == 'open':
        api(f'repos/{repo}/issues/{found["number"]}', 'PATCH', {'state': 'closed', 'state_reason': 'completed'})


def notify(repo, folder, outcome):
    path = folder / 'result.json'
    result = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {'status': 'failed', 'error': '同步步骤未完成；查看运行日志（可能是检出、网络或授权错误）。'}
    failed = outcome != 'success' or result['status'] == 'failed'
    url = f'https://github.com/{repo}/actions/runs/{os.environ["GITHUB_RUN_ID"]}'
    failure = f'自动同步失败，未视为完整成功。\n\n运行记录：{url}\n\n'
    failure += result.get('error', '查看运行日志及诊断产物。')
    issue(repo, '<!-- mika-upstream-sync:failure -->', 'Mika 上游同步失败，需要处理', failure, failed)
    if not failed:
        pending = bool(result.get('pending_paths'))
        report = folder / 'pending.md'
        body = report.read_text(encoding='utf-8') if report.exists() else ''
        issue(repo, '<!-- mika-upstream-sync:review -->', 'Mika 上游工作流待审核（源码同步正常）',
              body + '\n\n运行产物入口：' + url, pending)


def heartbeat(repo):
    # 公开仓库 60 天无活动可能停用定时任务；每月只在专用分支保活一次。
    branch = 'automation/upstream-sync-heartbeat'
    month = datetime.now(timezone.utc).strftime('%Y-%m')
    message = f'chore: keep upstream sync schedule active ({month})'
    refs = api(f'repos/{repo}/git/matching-refs/heads/{branch}')
    exact = next((x for x in refs if x['ref'] == 'refs/heads/' + branch), None)
    parent = exact['object']['sha'] if exact else api(f'repos/{repo}/git/ref/heads/main')['object']['sha']
    commit = api(f'repos/{repo}/git/commits/{parent}')
    if commit['message'] == message:
        print('本月已经保活，无需提交。')
        return
    created = api(f'repos/{repo}/git/commits', 'POST',
                  {'message': message, 'tree': commit['tree']['sha'], 'parents': [parent]})
    if exact:
        api(f'repos/{repo}/git/refs/heads/{branch}', 'PATCH', {'sha': created['sha'], 'force': False})
    else:
        api(f'repos/{repo}/git/refs', 'POST', {'ref': 'refs/heads/' + branch, 'sha': created['sha']})
    print('已更新独立保活分支。')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['notify', 'heartbeat'])
    parser.add_argument('--repo', required=True)
    parser.add_argument('--artifacts', type=Path)
    parser.add_argument('--outcome', default='failure')
    args = parser.parse_args()
    if args.action == 'notify':
        notify(args.repo, args.artifacts, args.outcome)
    else:
        heartbeat(args.repo)
