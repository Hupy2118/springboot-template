#!/usr/bin/env python3
"""Real ZIP validation, Executor/transaction Apply and application-config flows.

Only fresh isolated generated workspaces are mutated. XCodeAgent is imported
read-only, with bytecode and implicit .env loading disabled. No replacement
validator, executor or metadata is supplied by this adapter.
"""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import traceback
from unittest.mock import patch
from urllib.parse import urlparse

from adapter import ADAPTER, REPO, audit, encode, require, runtime_files, inventory
from http_smoke import caps, check_update, check_config_operations
from verification import post, zip_response, unpack

STATE = '.devagentstudio/template-state.json'
SURFACES = ('backend/src/main/resources/application.yml',
            'backend/src/main/resources/application-dev.yml', 'frontend/.env')


def git_snapshot(root):
    return {key: subprocess.check_output(['git', '-C', str(root)] + args, text=True).strip()
            for key, args in [('head', ['rev-parse', 'HEAD']), ('branch', ['branch', '--show-current']),
                              ('status', ['status', '--porcelain'])]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--xcodeagent-backend', required=True, type=Path)
    parser.add_argument('--engine-url', default='http://127.0.0.1:18080')
    parser.add_argument('--skip-build', action='store_true', help='Report generated backend build/runtime as NOT_RUN')
    args = parser.parse_args()
    backend = args.xcodeagent_backend.resolve()
    # Prefer the existing dependency environment without installing or changing it.
    python = backend / '.venv/bin/python'
    if python.is_file() and Path(sys.prefix).resolve() != (backend / '.venv').resolve():
        os.execv(str(python), [str(python), '-B', str(Path(__file__).resolve())] + sys.argv[1:])
    parsed = urlparse(args.engine_url)
    require(parsed.scheme == 'http' and parsed.hostname in ('127.0.0.1', 'localhost', '::1')
            and not parsed.username, 'verification requires a local HTTP Engine')
    reports = REPO / '.standalone-build/reports'
    reports.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix='xcodeagent-contract-', dir=reports))
    report = {'status': 'RUNNING', 'artifacts': str(out), 'python': sys.version,
              'platform': platform.platform(), 'checks': [], 'database': 'NOT_RUN: manual test database required'}
    exit_code = 1
    before = inventory(runtime_files(REPO / 'template-source/code'))
    try:
        require((backend / 'app/services/template_reconcile/update_package_v4.py').is_file(),
                'BLOCKED: XCodeAgent backend/real V4 executor not available')
        report['template'] = git_snapshot(REPO)
        report['xcodeagent'] = git_snapshot(backend)
        sys.dont_write_bytecode = True
        os.environ['DEVAGENTSTUDIO_BACKEND_ENV_FILE'] = str(out / 'no-environment-file')
        sys.path.insert(0, str(backend))
        try:
            import pydantic
            import yaml
            from app.config import Settings
            from app.services.template_reconcile.update_package_v4 import validate_update_package_v4
            from app.services.template_reconcile.config_surfaces import validate_ensure_package, validate_working_surfaces, CONTRACT
            from app.services.template_reconcile.executor_v3 import WorkingCopyStoreV3, UpdatePackageExecutorV3
            from app.services.template_reconcile.transaction_v4 import apply_working_copy_v4, recover_v4_transaction
            from app.services.template_reconcile import transaction_v4
            from app.services.template_reconcile.service import TemplateReconcileService
            from app.services.application_config_orchestrator import ApplicationConfigOrchestrator
            from app.services.application_config.schema import normalize_application_configuration
            from app.services.workspace_bootstrap.models import ArchiveLimits
        except ImportError as error:
            report['status'] = 'BLOCKED'
            raise RuntimeError('BLOCKED: XCodeAgent Python dependency unavailable: ' + str(error)) from error
        report['dependencies'] = {'pydantic': pydantic.__version__, 'PyYAML': yaml.__version__}
        revision = audit()['release']['standaloneRevision']
        report['revision'] = revision
        limits = ArchiveLimits(128*1024*1024, 15000, 128*1024*1024)
        def snapshot(root):
            return {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob('*') if p.is_file()}

        def generate(name):
            requested = caps()
            requested['applicationConfig'] = {
                'appName': 'Fixture', 'appIcon': 'AppstoreOutlined', 'theme': {'primaryColor': '#3366CC'},
                'auth': {'enable': False, 'authnSource': 'yst', 'yht': {}}}
            response = post(args.engine_url, '/v1/generate', {'requestedConfig': requested})
            files = zip_response(*response)
            (out / (name + '-generate.zip')).write_bytes(response[2])
            require(json.loads(files[STATE])['templateRevision'] == revision, 'wrong Engine mode/revision')
            root = out / name
            unpack(files, root)
            for target in SURFACES:
                p = root / target
                p.write_bytes(b'# user comment retained\n' + p.read_bytes())
                with p.open('ab') as stream:
                    stream.write(b'USER_VALUE=keep\n' if target.endswith('.env') else b'user-owned:\n  value: keep\n')
            return root

        def install(root, names, label, rollback=False):
            current = json.loads((root / STATE).read_bytes())
            response = post(args.engine_url, '/v1/update', {'protocolVersion': '4', 'mode': 'APPLY',
                            'currentTemplateState': current, 'requestedConfig': caps(*names)})
            files = zip_response(*response)
            archive = out / (label + '.zip')
            archive.write_bytes(response[2])
            metadata = check_update(files, current, names, current['stateSequence'])
            newly = set(metadata['nextTemplateState']['effective']) - set(current['effective'])
            check_config_operations(files, metadata, newly)
            validated = validate_update_package_v4(archive, limits)
            package = validated.package
            validate_ensure_package(package, validated.payloads, newly)
            store = WorkingCopyStoreV3(root)
            prior = snapshot(root)
            UpdatePackageExecutorV3().execute(package, store, validated.payloads)
            validate_working_surfaces(store, newly)
            store.entry(STATE).working = encode(package.nextTemplateState.model_dump(mode='json', exclude={'managedConfiguration'}))
            if rollback:
                real_replace = transaction_v4._atomic_replace
                injected = False
                count = 0
                def fail_once(target, content, *, mode):
                    nonlocal injected, count
                    if 'template-update-v4-journal' not in target.parts:
                        count += 1
                        if count == 2 and not injected:
                            injected = True
                            raise OSError('injected transaction write failure')
                    return real_replace(target, content, mode=mode)
                with patch.object(transaction_v4, '_atomic_replace', side_effect=fail_once):
                    try:
                        apply_working_copy_v4(root, store)
                    except OSError:
                        pass
                require(injected and snapshot(root) == prior, 'transaction rollback did not restore original files/state')
                report['checks'].append(label + ': real transaction rollback PASS')
            apply_working_copy_v4(root, store)
            recover_v4_transaction(root)
            validate_working_surfaces(WorkingCopyStoreV3(root), set(names))
            for target in SURFACES:
                require(b'# user comment retained' in (root / target).read_bytes(), 'user comment overwritten')
            require(b'USER_VALUE=keep' in (root / 'frontend/.env').read_bytes(), 'user env value overwritten')
            diff = [{'target': p, 'beforeSha256': hashlib.sha256(prior[p]).hexdigest() if p in prior else None,
                     'afterSha256': hashlib.sha256(data).hexdigest()}
                    for p, data in snapshot(root).items() if prior.get(p) != data]
            (out / (label + '-diff.json')).write_bytes(encode(diff))
            (out / (label + '-state.json')).write_bytes((root / STATE).read_bytes())
            report['checks'].append(label + ': ZIP/ensure/executor/working surfaces/transaction/state PASS')
            report.setdefault('operations', {})[label] = [
                {'target': op['target'], 'type': op['type'], 'parameters': op['parameters']} for op in metadata['operations']]
            status, _, data = post(args.engine_url, '/v1/update', {'protocolVersion': '4', 'mode': 'APPLY',
                                  'currentTemplateState': json.loads((root / STATE).read_bytes()), 'requestedConfig': caps(*names)})
            require(status == 204 and data == b'', 'repeated APPLY must be HTTP 204')

        chain = generate('executor-login-authorization')
        install(chain, ('login',), 'base-login', rollback=True)
        # Existing user Login values must survive the subsequent Authorization install.
        dev = chain / SURFACES[1]
        dev.write_text(dev.read_text().replace('client-id: client-id', 'client-id: user-client'))
        install(chain, ('login', 'authorization'), 'login-authorization')
        require('user-client' in dev.read_text(), 'existing Login parameter overwritten')
        direct = generate('executor-base-authorization')
        install(direct, ('login', 'authorization'), 'base-authorization')
        report['managedPaths'] = {cap: {target: sorted(paths) for target, paths in surfaces.items()}
                                  for cap, surfaces in CONTRACT.items()}

        # Exercise the actual application configuration entry point, including
        # dependency expansion, parameter projection and local NO_CHANGE.
        settings = Settings(model_base_url='', model_api_key='', model_name='', template_engine_base_url=args.engine_url)
        async def application_flow(name, steps):
            root = generate(name)
            application = normalize_application_configuration({
                'schemaVersion': 6, 'configRevision': 1, 'appName': 'Fixture', 'appIcon': 'AppstoreOutlined',
                'theme': {'primaryColor': '#3366CC'}, 'datasource': {'type': 'database'},
                'auth': {'enable': False, 'yht': dict.fromkeys(('clientId', 'privateKey', 'publicKey', 'centerPublicKey'), 'fixture-value')},
                'authorization': {'enabled': False, 'initialAdministratorSubjects': []}})
            (root / '.devagentstudio/application.json').write_bytes(encode(application))
            orchestrator = ApplicationConfigOrchestrator(settings)
            for index, changes in enumerate(steps):
                result = await orchestrator.apply(root, operation_id='fixture-step-' + str(index),
                                                  expected_revision=index+1, changes=changes)
                require(result['status'] == 'APPLIED', 'application-config flow failed')
                committed = snapshot(root)
                with patch('app.services.template_reconcile.service.TemplateEngineClient', side_effect=AssertionError('repeat submission accessed Engine')):
                    repeated = await orchestrator.apply(root, operation_id='fixture-step-' + str(index),
                                                        expected_revision=index+1, changes=changes)
                require(repeated == result and snapshot(root) == committed, 'repeated config submission changed workspace')
            state_before = (root / STATE).read_bytes()
            require(json.loads(state_before)['stateSequence'] == len(steps), 'wrong application-flow StateSequence')
            before_files = snapshot(root)
            with patch('app.services.template_reconcile.service.TemplateEngineClient', side_effect=AssertionError('NO_CHANGE accessed Engine')):
                require(await TemplateReconcileService(settings).reconcile_capabilities(root) == 'NO_CHANGE', 'expected local NO_CHANGE')
                require(snapshot(root) == before_files, 'NO_CHANGE modified files')
                result = await orchestrator.apply(root, operation_id='fixture-parameters', expected_revision=len(steps)+1,
                                                  changes={'authorization.api.viewId': 'fixture-new-view'})
                require(result['status'] == 'APPLIED' and not result['installedCapabilities'], 'parameter-only update installed capability')
            require((root / STATE).read_bytes() == state_before, 'parameter update changed TemplateState')
            require('fixture-new-view' in (root / SURFACES[1]).read_text(), 'parameter projection failed')
            require('fixture-value' in (root / SURFACES[1]).read_text(), 'Login parameter projection failed')
            require(b'USER_VALUE=keep' in (root / 'frontend/.env').read_bytes(), 'parameter update overwrote user env')
            report['checks'].append(name + ': real config orchestrator, parameter projection, local NO_CHANGE PASS')
        auth_changes = {'authorization.enabled': True, 'authorization.initialAdministratorSubjects': ['member-001'],
                        'authorization.api.baseUrl': 'http://127.0.0.1:9/api',
                        'authorization.api.viewId': 'fixture-view'}
        asyncio.run(application_flow('application-login-authorization', [{'auth.enable': True}, auth_changes]))
        asyncio.run(application_flow('application-base-authorization', [auth_changes]))

        if not args.skip_build:
            from verify import command, maven_args, start_smoke, bootstrap_regression
            for name, root in [('executor-chain', chain), ('executor-direct', direct)]:
                shutil.copytree(ADAPTER / 'tests/java/base', root / 'backend/src/test/java', dirs_exist_ok=True)
                shutil.copytree(ADAPTER / 'tests/java/login', root / 'backend/src/test/java', dirs_exist_ok=True)
                command(maven_args(root / 'backend/pom.xml') + ['clean', 'package'], out / (name + '-build.log'))
                start_smoke(root, 'authorization', out)
                bootstrap_regression(root, 'authorization', out)
                report['checks'].append(name + ': public Maven/tests/JAR/Mock/anonymous RBAC/Bootstrap dry-run PASS; real DB NOT_RUN')
        else:
            report['checks'].append('Post-Executor backend build/runtime: NOT_RUN (--skip-build)')
        require(inventory(runtime_files(REPO / 'template-source/code')) == before, 'formal source changed')
        require(git_snapshot(backend) == report['xcodeagent'], 'XCodeAgent worktree changed')
        report['status'] = 'PASS_SCOPED'
        exit_code = 0
    except Exception as error:
        if 'BLOCKED' in str(error):
            report['status'] = 'BLOCKED'
            exit_code = 3
        else:
            report['status'] = 'FAIL'
        report['error'] = str(error)
        (out / 'failure-stack.txt').write_text(traceback.format_exc())
    finally:
        (out / 'report.json').write_bytes(encode(report))
        (reports / 'xcodeagent-contract-latest.json').write_bytes(encode(report))
        print(report['status'] + ': ' + str(out / 'report.json'))
        print('Real database/DB-backed RBAC/Bootstrap SQL execution: NOT_RUN (manual acceptance).')
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
