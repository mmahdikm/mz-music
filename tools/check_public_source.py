"""Check staged and working source before publication; never print matched secrets.

This is a defense-in-depth guard, not proof of absence of private information.
Review all new content manually, including Git author/committer metadata.
"""
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
allowed_roots = {'app', 'gradle', 'tools', 'tests', 'design'}
allowed_files = {'.gitignore', '.gitattributes', 'README.md', 'SECURITY.md', 'VALIDATION.md',
                 'PUBLIC_SOURCE.md', 'build.gradle.kts', 'settings.gradle.kts',
                 'gradle.properties', 'gradlew', 'gradlew.bat'}
blocked_parts = {'.local-signing', '.tooling', '.gradle', '.kotlin', '.idea', '.vscode',
                 '__pycache__', 'build', 'dist', 'validation', '.git', '.venv'}
allowed_suffixes = {'.kt', '.kts', '.java', '.xml', '.json', '.py', '.sh', '.properties',
                    '.lockfile', '.svg'}
patterns = {
    'private key': rb'-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----',
    'GitHub credential': rb'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b',
    'AWS access key': rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b',
    'Google API key': rb'\bAIza[0-9A-Za-z_-]{30,}\b',
    'Slack credential': rb'\bxox[baprs]-[0-9A-Za-z-]{16,}\b',
    'home directory': rb'/(?:Users|home)/[A-Za-z0-9_.-]+/',
    'Windows home directory': rb'[A-Za-z]:\\Users\\[A-Za-z0-9_.-]+\\',
    'credential in URL': rb'https?://[^\s/@:]+:[^\s/@]+@',
    'email address': rb'\b(?!this@|return@)[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b',
}

def issues(name, data):
    path = PurePosixPath(name)
    found = []
    if (any(part in blocked_parts for part in path.parts)
            or name not in allowed_files and
            (path.parts[0] not in allowed_roots or path.suffix not in allowed_suffixes)
            and name != 'gradle/wrapper/gradle-wrapper.jar'):
        found.append('unapproved file type or location')
    if name == 'gradle/wrapper/gradle-wrapper.jar':
        # The only binary allowed; audited separately against the original wrapper.
        return found
    try:
        data.decode('utf-8')
    except UnicodeDecodeError:
        found.append('unexpected binary data')
    for label, pattern in patterns.items():
        if re.search(pattern, data):
            found.append(label)
    return found

def main():
    entries = subprocess.check_output(['git', 'ls-files', '--stage', '-z'], cwd=root).split(b'\0')
    problems = []
    count = 0
    for entry in filter(None, entries):
        metadata, raw_name = entry.split(b'\t', 1)
        mode, blob, stage = metadata.decode().split()
        name = raw_name.decode()
        count += 1
        if mode not in {'100644', '100755'} or stage != '0':
            problems.append((name, 'symlink, submodule or unresolved index entry'))
            continue
        staged = subprocess.check_output(['git', 'cat-file', 'blob', blob], cwd=root)
        for label in issues(name, staged):
            problems.append((name, 'staged: ' + label))
        path = root / name
        if path.is_symlink() or not path.is_file():
            problems.append((name, 'working file missing or symlink'))
        else:
            for label in issues(name, path.read_bytes()):
                problems.append((name, 'working: ' + label))
    if not count:
        raise SystemExit('No staged/tracked source found. Stage the reviewed source first.')
    for name, label in problems:
        print(f'{name}: {label}', file=sys.stderr)
    if problems:
        raise SystemExit('Public-source check failed. Review locally; no matched values were printed.')
    print(f'Public-source checks passed for {count} staged and working files.')

if __name__ == '__main__':
    main()
