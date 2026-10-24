#!/usr/bin/env python3
"""Read-only upstream adapter. JSON documents are used as a strict YAML subset."""
import argparse
import contextlib
import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import socket
import subprocess
import sys
import tempfile

ADAPTER = Path(__file__).resolve().parents[1]
REPO = ADAPTER.parents[1]
EXCLUDED = {'node_modules', 'build', 'target', '__pycache__', '.git', '.DS_Store', '.env'}
FORBIDDEN = re.compile(r'ZA21|LT99\.09|bee-starter-|EnableAuthClient|com\.tencentcloud\.|jdbc:tdsql-mysql|bee\.(?:persistence|auth)')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_read(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'duplicate JSON/YAML key: ' + key)
            result[key] = value
        return result
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique)


def encode(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode()


def relative(value):
    require(isinstance(value, str) and value and '\\' not in value, 'unsafe relative path: ' + str(value))
    path = PurePosixPath(value)
    require(not path.is_absolute() and all(p not in ('', '.', '..') for p in value.split('/'))
            and ':' not in value and not any(ord(c) < 32 for c in value), 'unsafe relative path: ' + value)
    return path


def safe_file(root, value, exists=True):
    relative(value)
    path = root / value
    for part in [path] + list(path.parents):
        if part == root.parent:
            break
        require(not part.is_symlink(), 'symlink forbidden: ' + str(part))
    require(path.resolve().is_relative_to(root.resolve()), 'path escapes root: ' + value)
    if exists:
        require(path.is_file(), 'missing regular file: ' + str(path))
    return path


def tree(root):
    require(root.is_dir() and not root.is_symlink(), 'missing/unsafe tree: ' + str(root))
    output, folded = {}, set()
    for directory, names, files in os.walk(root, followlinks=False):
        for name in names + files:
            require(not (Path(directory) / name).is_symlink(), 'symlink forbidden: ' + str(Path(directory) / name))
        names[:] = sorted(n for n in names if n not in EXCLUDED)
        for name in sorted(files):
            if name in EXCLUDED:
                continue
            path = Path(directory) / name
            key = path.relative_to(root).as_posix()
            relative(key)
            require(key.casefold() not in folded, 'case collision: ' + key)
            require(path.is_file(), 'not regular: ' + key)
            content = path.read_bytes()
            require(b'\x00' not in content, 'binary input: ' + key)
            content.decode('utf-8')
            folded.add(key.casefold())
            output[key] = content
    return output


def runtime_files(code):
    # Same ownership roots as CodeTemplateReleaseLoader; never traverse workspace/assembly.
    output = {'template-revision.txt': safe_file(code, 'template-revision.txt').read_bytes()}
    for part in ('contracts', 'frontend/base', 'backend/base'):
        output.update({part + '/' + key: value for key, value in tree(code / part).items()})
    for side in ('frontend', 'backend'):
        root = code / side / 'extensions'
        require(root.is_dir() and not root.is_symlink(), 'missing extensions: ' + str(root))
        for extension in sorted(root.iterdir()):
            if extension.name in EXCLUDED:
                continue
            require(extension.is_dir() and not extension.is_symlink(), 'invalid extension: ' + str(extension))
            prefix = side + '/extensions/' + extension.name + '/'
            output[prefix + 'extension.yaml'] = safe_file(extension, 'extension.yaml').read_bytes()
            children = ('src',) if side == 'frontend' else ('src', 'docs', 'migrations', 'scripts', 'sql')
            for child in children:
                path = extension / child
                if path.exists() or path.is_symlink():
                    output.update({prefix + child + '/' + key: value for key, value in tree(path).items()})
    validate_files(output)
    return output


def validate_files(files):
    folded = set()
    for key, data in files.items():
        relative(key)
        require(key.casefold() not in folded, 'case collision: ' + key)
        folded.add(key.casefold())
        require(b'\x00' not in data, 'binary input: ' + key)
        data.decode('utf-8')
        parts = PurePosixPath(key).parts
        require(not any('/'.join(parts[:i]) in files for i in range(1, len(parts))), 'file/directory collision: ' + key)


def inventory(files):
    return {key: digest(value) for key, value in sorted(files.items())}


def revision(value):
    match = re.fullmatch(r'(\d{4})\.(\d{2})\.(\d{2})\.([1-9]\d*)', value)
    require(match is not None, 'revision must be YYYY.MM.DD.N: ' + value)
    datetime.date(*map(int, match.groups()[:3]))
    return int(match.group(4))


def release(adapter, source):
    value = json_read(safe_file(adapter, 'release.yaml'))
    require(value.get('schemaVersion') == 1, 'invalid release schemaVersion')
    for key in ('sourceRevision', 'standaloneRevision'):
        revision(value[key])
    require(value['sourceRevision'] == source['template-revision.txt'].decode().strip(), 'sourceRevision mismatch; review release.yaml')
    require(value['standaloneRevision'] != value['sourceRevision'], 'standaloneRevision must differ from sourceRevision')
    require(1000 <= revision(value['standaloneRevision']) <= 999999, 'standalone sequence must use reserved local range 1000..999999')
    return value


def apply(source, adapter, manifest):
    require(manifest.get('schemaVersion') == 1 and isinstance(manifest.get('operations'), list), 'invalid manifest')
    output, changes, touched = dict(source), [], set()
    for op in manifest['operations']:
        target, kind = op['target'], op['type']
        relative(target)
        require(target not in touched and target != 'template-revision.txt', 'duplicate/reserved target: ' + target)
        require(target.startswith(('backend/base/', 'backend/extensions/', 'frontend/base/', 'frontend/extensions/', 'contracts/')), 'target outside Runtime roots: ' + target)
        require(op.get('reason'), 'missing reason: ' + target)
        require(kind in ('add', 'replace', 'remove', 'patch'), 'unknown operation: ' + kind)
        touched.add(target)
        before = output.get(target)
        if kind == 'add':
            require(before is None, 'add target exists: ' + target)
        else:
            require(before is not None, 'missing operation target: ' + target)
            require(digest(before) == op.get('expectedSourceSha256'), 'upstream SHA256 mismatch: ' + target)
        after = None
        if kind != 'remove':
            payload = safe_file(adapter, op['source']).read_bytes()
            if kind == 'patch':
                # Exact fragment patch, no context guessing, fuzzy matching or external patch program.
                patch = json_read(safe_file(adapter, op['source']))
                old, new = patch['before'].encode(), patch['after'].encode()
                require(old and before.count(old) == 1, 'patch must match exactly once: ' + target)
                after = before.replace(old, new, 1)
                require(digest(after) == op.get('expectedResultSha256'), 'patch result SHA256 mismatch: ' + target)
            else:
                after = payload
            output[target] = after
        else:
            del output[target]
        changes.append({'target': target, 'type': kind, 'reason': op['reason'],
                        'beforeSha256': digest(before) if before is not None else None,
                        'afterSha256': digest(after) if after is not None else None})
    validate_files(output)
    return output, changes


def audit_files(files):
    for path, content in files.items():
        if path.startswith('backend/') and (path.endswith(('.java', '.yml', '.yaml', '.xml', '.sh', '.ps1'))):
            match = FORBIDDEN.search(content.decode())
            require(not match, 'private runtime dependency/config remains: ' + path + ' (' + (match.group() if match else '') + ')')


def validate_contract_parity(original, adapted):
    """Fail closed on manifest drift without implementing another YAML parser.

    Exact equality outside the two reviewed runtime contributions also protects
    id/requires and every current or future configuration subtree and value.
    The real Engine Loader is exercised separately by HTTP verification.
    """
    login = 'backend/extensions/login/extension.yaml'
    private = (b'  applicationAnnotations:\n'
               b'  - annotationClass: com.cmb.bee.auth.client.config.EnableAuthClient\n'
               b'  mavenDependencies:\n'
               b'  - groupId: ZA21\n    artifactId: bee-starter-auth\n')
    public = b'  applicationAnnotations: []\n  mavenDependencies: []\n'
    require(login in original and original[login].count(private) == 1,
            'Login runtime contribution changed; review adapter allowlist')
    require(adapted.get(login) == original[login].replace(private, public, 1),
            'Login contract drift: only reviewed annotation/Maven removals are allowed')
    authorization = 'backend/extensions/authorization/extension.yaml'
    require(authorization in original and adapted.get(authorization) == original[authorization],
            'Authorization manifest contract drift')
    require(b'requires:\n- login\n' in original[authorization], 'Authorization must require Login')
    for path, token in (
        ('backend/base/pom.xml', b'<artifactId>spring-boot-starter-parent</artifactId>'),
        ('backend/base/src/main/resources/application.yml', b'driver-class-name: com.mysql.cj.jdbc.Driver'),
        ('backend/base/src/main/resources/application.yml', b'${STANDALONE_MOCK_ENABLED:true}'),
        ('backend/extensions/login/src/main/java/com/cmbchina/backend/standalone/login/MockLoginConfiguration.java',
         b'@Profile({"dev", "local"})'),
        ('backend/extensions/login/src/main/java/com/cmbchina/backend/standalone/login/MockIdentityService.java',
         b'class MockIdentityService')):
        require(token in adapted.get(path, b''), 'Standalone runtime adaptation missing: ' + path)


def git_sha(repo):
    return subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip()


def ensure_output(repo, build):
    require(build == repo / '.standalone-build', 'output must be repository .standalone-build')
    require(not build.is_symlink(), 'build root cannot be symlink')
    build.mkdir(exist_ok=True)
    for name in ('template-source', 'staging', 'releases', 'reports', '.release-lock'):
        require(not (build / name).is_symlink(), 'unsafe managed output: ' + name)


@contextlib.contextmanager
def lock(build):
    # mkdir is portable and atomic. Never steal an ambiguous/incompletely written lock.
    path = build / '.release-lock'
    try:
        path.mkdir()
    except FileExistsError:
        recovery = build / '.lock-recovery'
        try:
            recovery.mkdir()
        except FileExistsError as error:
            raise ValueError('Another process is inspecting the release lock; retry later') from error
        try:
            owner = json_read(safe_file(path, 'owner.json'))
            require(owner['host'] == socket.gethostname(), 'release lock belongs to another host')
            try:
                os.kill(owner['pid'], 0)
            except ProcessLookupError:
                stale = build / ('.stale-lock-' + str(os.getpid()))
                path.rename(stale)
                shutil.rmtree(stale)
                path.mkdir()
            else:
                raise ValueError('Release in use by PID %s; stop the old process first' % owner['pid'])
        except (OSError, KeyError, json.JSONDecodeError) as error:
            raise ValueError('Cannot safely recover release lock; inspect owner.json') from error
        finally:
            recovery.rmdir()
    (path / 'owner.json').write_bytes(encode({'pid': os.getpid(), 'host': socket.gethostname()}))
    try:
        yield
    finally:
        if path.exists() and json_read(path / 'owner.json').get('pid') == os.getpid():
            shutil.rmtree(path)


def prepare(repo=REPO, adapter=ADAPTER, build=None):
    build = build or repo / '.standalone-build'
    ensure_output(repo, build)
    source = runtime_files(repo / 'template-source/code')
    config = release(adapter, source)
    manifest_path = safe_file(adapter, 'manifest.yaml')
    manifest = json_read(manifest_path)
    result, changes = apply(source, adapter, manifest)
    validate_contract_parity(source, result)
    result['template-revision.txt'] = (config['standaloneRevision'] + '\n').encode()
    audit_files(result)
    inputs = {op['source']: digest(safe_file(adapter, op['source']).read_bytes())
              for op in manifest['operations'] if 'source' in op}
    for name in ('scripts/adapter.py', 'run-engine.sh', 'build.sh'):
        inputs[name] = digest(safe_file(adapter, name).read_bytes())
    identity = {'sourceFiles': inventory(source), 'manifestSha256': digest(manifest_path.read_bytes()),
                'overlayFiles': inputs, 'release': config}
    fingerprint = digest(encode(identity))
    report = dict(identity, fingerprint=fingerprint, upstreamGitSha=git_sha(repo),
                  outputFiles=inventory(result), changes=changes)
    build.joinpath('releases').mkdir(exist_ok=True)
    ledger = build / 'releases' / (config['standaloneRevision'] + '.json')
    if ledger.exists():
        previous = json_read(safe_file(build / 'releases', ledger.name))
        require(previous['fingerprint'] == fingerprint, 'Release content changed without standaloneRevision bump')
        if previous['upstreamGitSha'] != report['upstreamGitSha']:
            report['reuseReason'] = 'Git SHA changed; Runtime Source and adapter inputs unchanged'
    staging_root = build / 'staging'
    staging_root.mkdir(exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix='release-', dir=str(staging_root)))
    try:
        for key, data in result.items():
            path = staging / 'code' / key
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            if key.endswith('.sh'):
                path.chmod(0o755)
        (staging / 'build-fingerprint.json').write_bytes(encode(report))
        audit(staging, repo, adapter)
        require(inventory(runtime_files(repo / 'template-source/code')) == identity['sourceFiles'], 'upstream changed during prepare')
        # Reserve identity before activation, so a crash after activation cannot permit revision reuse.
        if not ledger.exists():
            ledger.write_bytes(encode(report))
        active, backup = build / 'template-source', build / 'previous-template-source'
        require(not backup.exists() and not backup.is_symlink(), 'interrupted commit: inspect previous-template-source before retrying')
        if active.exists():
            active.rename(backup)
        try:
            staging.rename(active)
        except BaseException:
            if backup.exists():
                backup.rename(active)
            raise
        if backup.exists():
            shutil.rmtree(backup)
        reports = build / 'reports'
        reports.mkdir(exist_ok=True)
        safe_file(reports, 'build-fingerprint.json', exists=False).write_bytes(encode(report))
        print('Prepared standalone revision=' + config['standaloneRevision'] + ' source=' + str(active))
        return report
    finally:
        if staging.exists():
            shutil.rmtree(staging)


def audit(source=None, repo=REPO, adapter=ADAPTER):
    source = source or repo / '.standalone-build/template-source'
    report = json_read(safe_file(source, 'build-fingerprint.json'))
    actual = tree(source / 'code')
    require(inventory(actual) == report['outputFiles'], 'prepared file inventory/hash mismatch')
    original = runtime_files(repo / 'template-source/code')
    config = release(adapter, original)
    expected, _ = apply(original, adapter, json_read(safe_file(adapter, 'manifest.yaml')))
    expected['template-revision.txt'] = (config['standaloneRevision'] + '\n').encode()
    require(inventory(expected) == inventory(actual), 'prepared source no longer matches upstream/overlay')
    require(report['manifestSha256'] == digest((adapter / 'manifest.yaml').read_bytes()), 'manifest changed since prepare')
    validate_contract_parity(original, actual)
    audit_files(actual)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=('prepare', 'audit', 'run'))
    args = parser.parse_args()
    build = REPO / '.standalone-build'
    if args.command == 'audit':
        report = audit()
        print('Audit passed revision=' + report['release']['standaloneRevision'])
        return
    if args.command == 'run':
        port = int(os.environ.get('TEMPLATE_ENGINE_PORT', '18080'))
        require(1 <= port <= 65535, 'invalid port')
        with socket.socket() as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(('127.0.0.1', port))  # Never mistake an older service for the new instance.
    ensure_output(REPO, build)
    with lock(build):
        report = prepare()
        if args.command == 'run':
            report = audit()
            os.environ['TEMPLATE_ENGINE_SOURCE_ROOT'] = str(build / 'template-source')
            print('mode=standalone source=%s revision=%s upstream=%s manifest=%s' % (
                os.environ['TEMPLATE_ENGINE_SOURCE_ROOT'], report['release']['standaloneRevision'],
                report['upstreamGitSha'], report['manifestSha256']), flush=True)
            os.execvp('java', ['java', '-jar', str(REPO / 'template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar'),
                              '--spring.config.additional-location=file:' + str(REPO / 'template-engine/engine-service/config/application-local.yml')])


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        print('Standalone adapter: ' + str(error), file=sys.stderr)
        sys.exit(1)
