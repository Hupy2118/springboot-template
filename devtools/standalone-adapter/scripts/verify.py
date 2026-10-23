#!/usr/bin/env python3
"""Verification driver. Database writes and actual Workspace Apply are deliberately external."""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from urllib.parse import urlparse
from adapter import ADAPTER, REPO, require, audit, encode, json_read, runtime_files, inventory
from http_smoke import caps, run as http_run
from verification import post, zip_response, unpack


def command(args,log,env=None):
    with log.open('w') as stream:
        result=subprocess.run(args,cwd=REPO,env=env,stdout=stream,stderr=subprocess.STDOUT)
    require(result.returncode==0,'command failed; inspect '+str(log))


def maven_args(pom):
    settings=str(ADAPTER/'settings-public.xml')
    return ['mvn','-s',settings,'-gs',settings,'-Dmaven.repo.local='+str(REPO/'.standalone-build/m2'),'-f',str(pom)]


def start_smoke(project,name,logs,scenario='defaults'):
    # This intentionally uses a CLOSED port and placeholder identity, never the user's MySQL.
    env={key:value for key,value in os.environ.items()
         if not key.startswith('STANDALONE_MOCK_') and key!='SPRING_PROFILES_ACTIVE'}
    env.update(DB_URL='jdbc:mysql://127.0.0.1:1/not_connected_standalone_test?connectTimeout=1000&socketTimeout=1000',
               DB_USERNAME='manual_verification_only',DB_PASSWORD='not-a-database-credential')
    env.update(SPRING_DATASOURCE_URL=env['DB_URL'],SPRING_DATASOURCE_USERNAME=env['DB_USERNAME'],
               SPRING_DATASOURCE_PASSWORD=env['DB_PASSWORD'])
    login_secret=secrets.token_urlsafe(36)
    expected_identity={'userId':'member-001','userName':'Local Admin'}
    if scenario=='overrides':
        env.update(SPRING_PROFILES_ACTIVE='dev',STANDALONE_MOCK_SIGNING_KEY=secrets.token_urlsafe(36),
            STANDALONE_MOCK_LOGIN_SECRET=login_secret,STANDALONE_MOCK_USERS=json.dumps({
                'member-001':{'userName':'Local Admin','avatar':'/test-avatar.svg'},'member-002':'Local Member'}),
            STANDALONE_MOCK_SSO_USER_ID='member-002')
        expected_identity={'userId':'member-002','userName':'Local Member'}
    elif scenario=='disabled':env['STANDALONE_MOCK_ENABLED']='false'
    elif scenario=='prod':env['SPRING_PROFILES_ACTIVE']='prod'
    else:require(scenario=='defaults','unknown startup scenario')
    mock_enabled=name!='base' and scenario not in ('disabled','prod')
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    env['BACKEND_PORT']=str(port)
    url='http://127.0.0.1:'+str(port)
    jar=project/'backend/target/xcode-agent-backend-1.0.0-SNAPSHOT.jar'
    log=logs/(name+('' if scenario=='defaults' else '-'+scenario)+'-start.log')
    with log.open('w') as stream:
        process=subprocess.Popen(['java','-jar',str(jar)],cwd=project/'backend',env=env,stdout=stream,stderr=subprocess.STDOUT)
        try:
            deadline=time.monotonic()+35
            while True:
                require(process.poll() is None,'generated backend exited; inspect '+str(log))
                try:
                    urllib.request.urlopen(url+('/api/standalone/me' if mock_enabled else '/'),timeout=1).close()
                    raise ValueError('unexpected successful anonymous readiness response')
                except urllib.error.HTTPError as error:
                    require(error.code==(401 if mock_enabled else 404),'unexpected generated backend readiness status')
                    break
                except OSError:
                    require(time.monotonic()<deadline,'generated backend startup timeout; inspect '+str(log))
                    time.sleep(.2)
            if not mock_enabled:
                for path in ('/api/sso/login?code=mock-code','/api/standalone/me'):
                    try:
                        urllib.request.urlopen(url+path,timeout=5).close()
                        raise ValueError('Mock endpoint registered when disabled or absent')
                    except urllib.error.HTTPError as error:require(error.code==404,'Mock endpoint must be absent')
            else:
                jar_cookies=http.cookiejar.CookieJar()
                client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar_cookies))
                def request(path,data=None):
                    body=json.dumps(data).encode() if data is not None else None
                    req=urllib.request.Request(url+path,data=body,headers={'Content-Type':'application/json'})
                    try:
                        with client.open(req,timeout=5) as response:return response.status,response.headers,response.read()
                    except urllib.error.HTTPError as error:return error.code,error.headers,error.read()
                status,_,_=request('/api/standalone/login',{'userId':'member-001','secret':'wrong'})
                require(status==401,'mock login accepted invalid credentials')
                status,headers,data=request('/api/sso/login?code=mock-browser-callback')
                require(status==200 and json.loads(data)['returnCode']=='SUC0000'
                        and json.loads(data)['body']==expected_identity,
                        'existing frontend SSO callback response mismatch')
                cookie=headers.get('Set-Cookie','')
                require('standalone_identity=' in cookie and 'HttpOnly' in cookie and 'SameSite=Lax' in cookie
                        and headers.get('Cache-Control')=='no-store','SSO callback did not issue the expected cookie')
                require(request('/api/standalone/me')[0]==200,'SSO cookie did not authenticate subsequent request')
                request('/api/standalone/logout',{})
                require(request('/api/standalone/me')[0]==401,'SSO cookie was not cleared on logout')
                if scenario=='overrides':
                    status,headers,data=request('/api/standalone/login',{'userId':'member-001','secret':login_secret})
                    require(status==200 and json.loads(data)['body']['userId']=='member-001','mock login failed')
                    require(json.loads(data)['body']['avatar']=='/test-avatar.svg','user/avatar override ignored')
                    cookie=headers.get('Set-Cookie','')
                    require('HttpOnly' in cookie and 'SameSite=Lax' in cookie,'unsafe mock cookie flags')
                    require(request('/api/standalone/me')[0]==200,'signed cookie identity missing')
                    request('/api/standalone/logout',{})
                    require(request('/api/standalone/me')[0]==401,'logout did not clear cookie identity')
                if name in ('authorization','full'):
                    status,_,data=request('/api/authorization/roles')
                    require(status==200 and json.loads(data)['returnCode']=='XCD1B11','unchanged RBAC must reject anonymous before database access')
        finally:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait()


def bootstrap_regression(project, name, logs):
    """Exercise the real Bootstrap entry point with --dry-run; no connection or SQL execution."""
    env=dict(os.environ,DB_URL='jdbc:mysql://127.0.0.1:1/not_connected_standalone_test',
             DB_USERNAME='fixture-only',DB_PASSWORD='test-placeholder')
    backend=project/'backend'
    jar=backend/'target/xcode-agent-backend-1.0.0-SNAPSHOT.jar'
    arguments=['java','-Dloader.main=com.cmbchina.backend.auth.bootstrap.AuthorizationBootstrapCommand',
               '-cp',str(jar),'org.springframework.boot.loader.PropertiesLauncher','--dry-run',
               '--technical-plan='+str(ADAPTER/'tests/fixture/technical-plan.json'),
               '--application='+str(ADAPTER/'tests/fixture/application.json'),
               '--application-yml='+str(backend/'src/main/resources/application.yml'),
               '--ddl='+str(backend/'sql/authorization/schema.sql')]
    for case,extra,expected in [('local',['--profile=local'],0),('dev',['--profile=dev'],0),
             ('missing-manifest',['--technical-plan='+str(project/'absent.json')],2),
             ('missing-sql',['--ddl='+str(project/'absent.sql')],2),('invalid-url',['--db-url=jdbc:h2:mem:test'],2)]:
        with (logs/(name+'-bootstrap-'+case+'.log')).open('w') as stream:
            result=subprocess.run(arguments+extra,cwd=backend,env=env,stdout=stream,stderr=subprocess.STDOUT)
        require(result.returncode==expected,'Bootstrap dry-run/negative regression failed: '+case)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('command',choices=('verify-base','verify-full','verify-engine','verify-all','verify-http-only'))
    parser.add_argument('--engine-url',default='http://127.0.0.1:18080')
    parser.add_argument('--mysql-test-url')
    parser.add_argument('--manual-database',action='store_true')
    parser.add_argument('--skip-integration',action='store_true')
    args=parser.parse_args()
    if args.mysql_test_url:
        parsed=urlparse(args.mysql_test_url.removeprefix('jdbc:'))
        require(parsed.scheme=='mysql' and parsed.hostname in ('localhost','127.0.0.1','::1')
                and parsed.path.endswith('_standalone_test') and not parsed.username,'only a local *_standalone_test database is permitted')
    build=REPO/'.standalone-build';build.mkdir(exist_ok=True)
    logs=build/'reports';logs.mkdir(exist_ok=True)
    before=inventory(runtime_files(REPO/'template-source/code'))
    if args.command=='verify-engine':
        env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1')
        command(['mvn','-f',str(REPO/'template-engine/pom.xml'),'verify'],logs/'engine-verify.log',env)
        command(['python3','-B',str(REPO/'scripts/ci/check-protected-surfaces.py')],logs/'protected-surfaces.log',env)
        command([str(REPO/'scripts/ci/verify-code-template-release-revision.sh'),'HEAD'],logs/'release-revision.log',env)
        print('Native engine verify and protection checks passed.');return 0
    report=audit()
    if args.command=='verify-all':
        command([str(ADAPTER/'build.sh'),'test'],logs/'adapter-tests.log')
    run_root=Path(tempfile.mkdtemp(prefix='verify-',dir=str(build)))
    notes=[]
    try:
        if args.command=='verify-base':
            response=post(args.engine_url,'/v1/generate',{'requestedConfig':caps()})
            projects={'base':zip_response(*response)}
            state=json.loads(projects['base']['.devagentstudio/template-state.json'])
            require(state['templateRevision']==report['release']['standaloneRevision'],'wrong mode/revision')
        else:
            projects=http_run(args.engine_url,'standalone',run_root/'http-verification')
            notes.append('L1 HTTP Generate/V3/V4 planning: PASS')
        if args.command=='verify-http-only':return 0
        for name,files in projects.items():
            from adapter import audit_files
            audit_files(files)
            target=run_root/name;unpack(files,target)
            fixtures=ADAPTER/'tests/java/base'
            import shutil
            shutil.copytree(fixtures,target/'backend/src/test/java',dirs_exist_ok=True)
            if name!='base':shutil.copytree(ADAPTER/'tests/java/login',target/'backend/src/test/java',dirs_exist_ok=True)
            command(maven_args(target/'backend/pom.xml')+['clean','package'],logs/(name+'-package.log'))
            notes.append(name+' public Maven build/unit tests: PASS')
            command(maven_args(target/'backend/pom.xml')+['dependency:tree','-DoutputFile='+str(logs/(name+'-dependencies.txt'))],logs/(name+'-dependency-tree.log'))
            start_smoke(target,name,logs)
            notes.append(name+' JAR startup with no Mock/profile environment / default SSO / anonymous RBAC rejection: PASS (no real database connection)')
            if name!='base':
                for scenario in ('overrides','disabled','prod'):start_smoke(target,name,logs,scenario)
                notes.append(name+' dev overrides / explicit Mock disable / prod profile exclusion: PASS')
            if name in ('authorization','full'):
                bootstrap_regression(target,name,logs)
                notes.append(name+' Bootstrap local/dev dry-run and missing Manifest/SQL/invalid URL rejection: PASS (no writes)')
        require(inventory(runtime_files(REPO/'template-source/code'))==before,'formal source changed during verification')
        notes.append('Formal source hash protection: PASS')
        if args.command=='verify-all':
            command([str(ADAPTER/'build.sh'),'verify-engine'],logs/'verify-engine-driver.log')
            notes.append('Native engine regression gates: PASS')
        print('Public builds/startup and scoped checks passed. MySQL integration and actual Workspace Apply are unverified.')
        return 3 if args.command=='verify-all' else 0
    except Exception as error:
        notes.append('FAIL: '+str(error));raise
    finally:
        notes.extend(['Real MySQL / DB-backed RBAC / Bootstrap: NOT EXECUTED; user requested manual verification.',
                      'L3 Workspace Operation Executor Apply: NOT EXECUTED; no connected executor.',
                      'verify-all returns 3 for incomplete integration acceptance, even when scoped checks pass.',
                      'Artifacts: '+str(run_root)])
        (logs/'verification-latest.md').write_text('# Verification\n\n'+'\n'.join('- '+line for line in notes)+'\n',encoding='utf-8')

if __name__=='__main__':
    try:sys.exit(main())
    except (ValueError,OSError,KeyError) as error:
        print('Verification failed: '+str(error),file=sys.stderr);sys.exit(1)
