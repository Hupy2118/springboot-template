#!/usr/bin/env python3
"""L1 verification only: update planning is never reported as workspace Apply."""
import argparse
import io
import json
from pathlib import Path
import sys
import zipfile
from urllib.parse import urlparse
from adapter import ADAPTER, REPO, require, audit_files, encode, runtime_files, inventory, json_read, relative
from verification import post, zip_response, unpack

ASSETS=('backend/scripts/bootstrap-authorization.sh','backend/scripts/bootstrap-authorization.ps1',
        'backend/sql/authorization/schema.sql',
        'backend/src/main/java/com/cmbchina/backend/auth/bootstrap/BootstrapDataSource.java',
        'backend/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java')


def caps(*names):
    return {'capabilities':{name:{'enabled':True,'config':{}} for name in names}}


def check_update(files, current, expected, sequence, mode='APPLY'):
    require('extension-update-package.json' in files,'missing update metadata')
    data=json.loads(files['extension-update-package.json'])
    require(data['protocolVersion']=='4' and data['mode']==mode,'wrong update protocol/mode')
    require(data['sourceRevision']==current['templateRevision'],'cross-release update rejected by test client')
    require(data['expectedStateSequence']==sequence,'incorrect expectedStateSequence')
    next_state=data['nextTemplateState']
    require(next_state['stateSequence']==(sequence+1 if mode=='APPLY' else sequence),'incorrect next stateSequence')
    require(set(next_state['effective'])==set(expected),'wrong next capability closure')
    seen, refs=set(),set()
    for index,op in enumerate(data['operations']):
        require(op['index']==index and op['operationId'] not in seen,'non-contiguous index/duplicate operation ID')
        seen.add(op['operationId']);relative(op['target'])
        payload=op.get('payloadRef')
        if payload:
            relative(payload)
            require(payload.startswith('payload/') and payload in files,'missing/unsafe payloadRef')
            refs.add(payload)
    require(refs=={key for key in files if key.startswith('payload/')},'unreferenced payload')
    require(set(data['payloadManifest'])==refs,'payload manifest mismatch')
    for key in refs:require(data['payloadManifest'][key]['size']==len(files[key]),'payload size mismatch')
    # Inspect actual payload plus contribution parameters. Compatibility package names are legitimate.
    audit_files({key.removeprefix('payload/'):value for key,value in files.items() if key.startswith('payload/backend/')})
    from adapter import FORBIDDEN
    require(not FORBIDDEN.search(json.dumps(data['operations'])),'private contribution in update operations')
    return data


def run(base,mode,out):
    parsed=urlparse(base)
    require(parsed.scheme=='http' and parsed.hostname in ('127.0.0.1','localhost','::1') and not parsed.username,'smoke tests require local HTTP engine')
    require(out.resolve().is_relative_to((REPO/'.standalone-build').resolve()),'reports must stay in .standalone-build')
    require(not out.is_symlink(),'output cannot be symlink')
    out.mkdir(parents=True,exist_ok=True)
    for directory in ('requests','metadata'):
        require(not (out/directory).is_symlink(),'unsafe report directory');(out/directory).mkdir(exist_ok=True)
    before=inventory(runtime_files(REPO/'template-source/code'))
    revision=json_read(ADAPTER/'release.yaml')['standaloneRevision'] if mode=='standalone' else (REPO/'template-source/code/template-revision.txt').read_text().strip()
    checks=[]
    def request(name,path,body):
        (out/'requests'/(name+'.json')).write_bytes(encode(body))
        status,kind,data=post(base,path,body)
        (out/'metadata'/(name+'-http.json')).write_bytes(encode({'status':status,'contentType':kind}))
        (out/(name+('.zip' if 'application/zip' in kind else '.body'))).write_bytes(data)
        return status,kind,data
    try:
        states={};projects={}
        for name,names in [('base',()),('login',('login',)),('authorization',('authorization',)),('full',('login','authorization'))]:
            body={'requestedConfig':caps(*names)}
            status,kind,data=request('generate-'+name,'/v1/generate',body)
            files=zip_response(status,kind,data)
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                require(all(info.date_time==(1980,1,2,0,0,0) for info in archive.infolist()),
                        'ZIP entries must use the fixed positive DOS timestamp')
            _,_,again=request('generate-'+name+'-repeat','/v1/generate',body)
            require(data==again,'Generate ZIP bytes are not deterministic')
            state=json.loads(files['.devagentstudio/template-state.json'])
            expected=set(names)|({'login'} if 'authorization' in names else set())
            require(state['templateRevision']==revision and state['stateSequence']==0,'wrong generated revision/sequence')
            require(set(state['requested'])==set(names) and set(state['effective'])==expected,'wrong requested/effective closure')
            pom=files['backend/pom.xml'].decode()
            require(any(k.startswith('frontend/') for k in files),'missing frontend')
            if mode=='standalone':
                require('spring-boot-starter-parent' in pom and 'ZA21' not in pom,'wrong standalone parent')
                audit_files(files)
                if name=='base':require(not any('/auth/' in key or '/standalone/login/' in key for key in files),'Base contains Login/Authorization')
                else:
                    require(any('/standalone/login/MockIdentityService.java' in key for key in files),'missing Mock login')
                    require('${STANDALONE_MOCK_ENABLED:true}' in files['backend/src/main/resources/application.yml'].decode(),
                            'generated Mock login is not enabled by default')
                    require("export function logoutApi()" in files['frontend/src/apis/login.ts'].decode()
                            and "'/api/standalone/logout'" in files['frontend/src/apis/login.ts'].decode(),
                            'Standalone frontend logout contract is missing')
            else:require('ZA21' in pom and 'bee-starter-parent' in pom,'wrong native parent')
            if 'authorization' in names:
                for key in ASSETS:require(key in files,'missing authorization asset: '+key)
            (out/'metadata'/(name+'-state.json')).write_bytes(encode(state))
            (out/'metadata'/(name+'-origin.json')).write_bytes(encode({'mode':mode,'revision':revision,'source':'fresh Generate in this verification run'}))
            states[name]=state;projects[name]=files
            checks.append(name+' Generate: PASS (HTTP/ZIP/Revision/closure/determinism)')
        # The existing resolver supplies the Login dependency before configuration projection.
        projection={'requestedConfig':caps('authorization')}
        projection['requestedConfig']['applicationConfig']={'appName':'Standalone 测试工程','appIcon':'AppstoreOutlined',
            'theme':{'primaryColor':'#3366CC'},'auth':{'enable':True,'authnSource':'yst','yht':{
                'clientId':'standalone-test-client','privateKey':'test-value-only','publicKey':'test-value-only','centerPublicKey':'test-value-only'}}}
        files=zip_response(*request('generate-config','/v1/generate',projection))
        env=files['frontend/.env'].decode();dev=files['backend/src/main/resources/application-dev.yml'].decode()
        require('Standalone 测试工程' in env and 'standalone-test-client' in env,'frontend config projection failed')
        require('standalone-test-client' in dev and 'test-value-only' in dev,'backend config projection failed')
        require('test-value-only' not in files['backend/src/main/resources/application.yml'].decode(),'test secret leaked to base YAML')
        checks.append('ApplicationConfig projection: PASS (authorization-only request, automatic Login dependency; test values only)')
        bad={'requestedConfig':caps(),'standalone':True}
        status,_,_=request('reject-request-mode','/v1/generate',bad)
        require(status==400,'request-level mode should return 400')
        if mode=='standalone':
            current=states['base']
            for seq,(name,names) in enumerate([('login',('login',)),('authorization',('login','authorization'))]):
                body={'protocolVersion':'4','currentTemplateState':current,'requestedConfig':caps(*names),'mode':'APPLY'}
                files=zip_response(*request('update-'+name,'/v1/update',body))
                package=check_update(files,current,names,seq)
                (out/'metadata'/('update-'+name+'.json')).write_bytes(encode(package))
                if name=='login':
                    logout_target='frontend/src/apis/login.ts'
                    logout_ops=[op for op in package['operations'] if op['target']==logout_target and op['type']=='ADD_FILE']
                    require(len(logout_ops)==1 and files[logout_ops[0]['payloadRef']]==projects['login'][logout_target],
                            'V4 Login API differs from Generate')
                    configs=[op for op in package['operations'] if op['type']=='ENSURE_CAPABILITY_CONFIG'
                             and op['target']=='backend/src/main/resources/application.yml']
                    require(len(configs)==1 and '${STANDALONE_MOCK_ENABLED:true}' in files[configs[0]['payloadRef']].decode(),
                            'V4 Login contribution does not enable Mock by default')
                if name=='authorization':
                    targets={op['target']:op for op in package['operations']}
                    for asset in ASSETS:
                        require(asset in targets,'missing update asset: '+asset)
                        op=targets[asset]
                        require(op['type']=='ADD_FILE','asset should be ADD_FILE')
                        require(files[op['payloadRef']]==projects['authorization'][asset],'update asset differs from Generate')
                current=package['nextTemplateState']
                checks.append('V4 '+name+' planning: PASS sequence '+str(seq)+'→'+str(seq+1))
            body={'protocolVersion':'4','currentTemplateState':current,'requestedConfig':caps('login','authorization'),'mode':'APPLY'}
            status,_,data=request('update-no-change','/v1/update',body)
            require(status==204 and data==b'','expected HTTP 204 with empty body')
            checks.append('V4 identical APPLY: PASS HTTP 204 empty body')
            body['mode']='RECONCILE'
            files=zip_response(*request('update-reconcile','/v1/update',body))
            repaired=check_update(files,current,('login','authorization'),2,'RECONCILE')
            require(repaired['nextTemplateState']==current,'RECONCILE changed state')
            require(all(op['type']!='ENSURE_CAPABILITY_CONFIG' for op in repaired['operations']),'RECONCILE must not overwrite user config')
            checks.append('V4 RECONCILE: PASS managed repair only')
            # V3 is tested separately with its own generated state, never with V4 state.
            body={'protocolVersion':'3','currentTemplateState':states['base'],'requestedConfig':caps('authorization'),'mode':'APPLY'}
            files=zip_response(*request('v3-update-authorization','/v1/update',body))
            require(json.loads(files['extension-update-package.json'])['protocolVersion']=='3','wrong V3 protocol')
            audit_files({key.removeprefix('payload/'):value for key,value in files.items() if key.startswith('payload/backend/')})
            checks.append('V3 planning regression: PASS')
        require(inventory(runtime_files(REPO/'template-source/code'))==before,'formal Runtime Source changed')
        checks.append('Formal Runtime Source hashes: PASS unchanged')
        return projects
    except Exception as error:
        checks.append('FAIL: '+str(error))
        raise
    finally:
        (out/'verification.md').write_text('# HTTP verification\n\nMode: '+mode+'; expected revision: '+revision+'\n\n'+ '\n'.join('- '+line for line in checks)+
            '\n\nL1: see individual assertions above.\n\nL2 build/start/RBAC/Bootstrap: NOT EXECUTED by this HTTP script.\n\nL3 actual Workspace Operation Executor Apply: NOT EXECUTED. The two update requests inspect server planning only.\n\nThe test client accepts only states generated in this run and matching this release. The native API has no Variant identity; it does not prevent all cross-mode updates.\n',encoding='utf-8')


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--base-url',required=True)
    parser.add_argument('--expected-mode',choices=('standalone','native'),required=True)
    parser.add_argument('--output-dir',type=Path,default=REPO/'.standalone-build/http-verification')
    args=parser.parse_args()
    run(args.base_url,args.expected_mode,args.output_dir.resolve())
    print('HTTP checks passed; database integration and actual workspace Apply remain separate.')

if __name__=='__main__':
    try:main()
    except (ValueError,OSError,KeyError) as error:
        print('HTTP verification failed: '+str(error),file=sys.stderr);sys.exit(1)
