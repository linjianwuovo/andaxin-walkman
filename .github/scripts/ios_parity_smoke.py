#!/usr/bin/env python3
"""Capture actual production screens on the CI simulator without account credentials."""
import argparse
import json
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--udid', required=True)
parser.add_argument('--artifacts', required=True, type=Path)
args = parser.parse_args()
args.artifacts.mkdir(parents=True, exist_ok=True)
commands = []

def run(*argv, check=True):
    result = subprocess.run(argv, capture_output=True, text=True, timeout=40)
    commands.append({'argv': argv, 'exit': result.returncode, 'stderr': result.stderr[-500:]})
    if check and result.returncode:
        raise RuntimeError(f'{argv[0]} failed: {result.stderr[-500:]}')
    return result.stdout.strip()

cases = [('ai-light', 'aiclass', 'light', 'large', None),
         ('ai-large-dark', 'aiclass', 'dark', 'accessibility-extra-extra-extra-large', None),
         ('notice', 'notice', 'light', 'large', None),
         ('music-podcast', 'music', 'light', 'large', '播客'),
         ('music-library-large', 'music', 'dark', 'accessibility-extra-extra-extra-large', '音乐库'),
         ('music-downloads', 'music', 'light', 'large', '下载'),
         ('music-cloud-login', 'music', 'light', 'large', '云盘'),
         ('notify', 'notify', 'light', 'large', None),
         ('notify-large-dark', 'notify', 'dark', 'accessibility-extra-extra-extra-large', None)]
results = []
try:
    for label, route, appearance, size, tab in cases:
        run('xcrun', 'simctl', 'terminate', args.udid, 'com.andaxin.ios', check=False)
        run('xcrun', 'simctl', 'ui', args.udid, 'appearance', appearance)
        run('xcrun', 'simctl', 'ui', args.udid, 'content_size', size)
        with (args.artifacts / f'{label}.console.txt').open('wb') as log:
            process = subprocess.Popen(['xcrun', 'simctl', 'launch', '--console', '--terminate-running-process', args.udid,
                                        'com.andaxin.ios', '-AndaXinGuest', '-AndaXinOpen', route,
                                        '-music.tabSelected', tab or '首页', '-music.tabVisibility', '下载:1,云盘:1,历史:1', '-music.playing', 'NO'], stdout=log, stderr=subprocess.STDOUT)
            try:
                time.sleep(12)
                alive = process.poll() is None
                run('xcrun', 'simctl', 'io', args.udid, 'screenshot', str(args.artifacts / f'{label}.png'))
                results.append({'case': label, 'route': route, 'appearance': appearance, 'content_size': size, 'alive': alive})
                if not alive:
                    raise RuntimeError(f'{label}: application exited before capture')
            finally:
                run('xcrun', 'simctl', 'terminate', args.udid, 'com.andaxin.ios', check=False)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=10)
        text = (args.artifacts / f'{label}.console.txt').read_text(errors='replace')
        if 'Uncaught Kotlin exception' in text or 'Unhandled Kotlin exception' in text:
            raise RuntimeError(f'{label}: unhandled exception in console')
finally:
    run('xcrun', 'simctl', 'ui', args.udid, 'appearance', 'light', check=False)
    run('xcrun', 'simctl', 'ui', args.udid, 'content_size', 'large', check=False)
    (args.artifacts / 'result.json').write_text(json.dumps({'cases': results, 'commands': commands,
        'limitations': ['Simulator only; no physical iPhone camera, account or campus writes tested',
                        'Music pages use live services or login/empty states; screenshots require independent inspection']}, indent=2, ensure_ascii=False))
