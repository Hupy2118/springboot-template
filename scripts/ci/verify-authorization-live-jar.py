#!/usr/bin/env python3
"""Read-only HTTP contract acceptance against an already started local Engine JAR."""
import argparse
import json,urllib.request,urllib.error,io,zipfile,pathlib,time,hashlib
ROOT = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--url', default='http://127.0.0.1:18081')
parser.add_argument('--output', type=pathlib.Path, help='Optional directory for inspecting the generated authorization project')
options = parser.parse_args()
url = options.url.rstrip('/')
assets=['backend/scripts/bootstrap-authorization.sh','backend/scripts/bootstrap-authorization.ps1','backend/sql/authorization/schema.sql','backend/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java','backend/src/main/java/com/cmbchina/backend/auth/bootstrap/BootstrapDataSource.java','backend/src/main/java/com/cmbchina/backend/common/config/WebMvcConfiguration.java']
sql_target='backend/sql/authorization/schema.sql'
sql_source=(ROOT/'template-source/code/backend/extensions/authorization/sql/authorization/schema.sql').read_bytes()
legacy=['backend/docs/auth/sql/ddl.sql','backend/migrations/001-schema.sql']
def post(path,body):
 req=urllib.request.Request(url+path,json.dumps(body).encode(),{'Content-Type':'application/json','Accept':'application/zip'})
 with urllib.request.urlopen(req,timeout=30) as r:return r.status,r.read(),r.headers
for attempt in range(50):
 try:
  status,data,headers=post('/v1/generate',{'requestedConfig':{'capabilities':{}}}); break
 except (OSError,urllib.error.URLError):time.sleep(.3)
else:raise RuntimeError('JAR failed to start')
states={}
generated_sql={}
for profile in ['base','login','authorization','full']:
 caps={} if profile=='base' else {profile:{'enabled':True,'config':{}}} if profile!='full' else {c:{'enabled':True,'config':{}} for c in ['login','authorization']}
 body={'requestedConfig':{'capabilities':caps}}
 status,data,headers=post('/v1/generate',body); assert status==200 and headers.get_content_type()=='application/zip'
 assert data==post('/v1/generate',body)[1]
 z=zipfile.ZipFile(io.BytesIO(data)); names=z.namelist(); assert names==sorted(names)
 for asset in assets: assert (asset in names)==(profile in ['authorization','full']),asset
 for asset in legacy: assert asset not in names,asset
 if profile in ['authorization','full']:
  generated_sql[profile]=z.read(sql_target);assert generated_sql[profile]==sql_source
  print(profile,'Generate SQL SHA-256:',hashlib.sha256(generated_sql[profile]).hexdigest())
  print(profile,'Generate authorization assets:',json.dumps(assets))
 states[profile]=json.loads(z.read('.devagentstudio/template-state.json'))
 if profile=='authorization' and options.output:
  options.output.mkdir(parents=True, exist_ok=True);z.extractall(options.output)
 print(profile,'Generate assets, deterministic ZIP: PASS')
for profile in ['base','login']:
 body={'protocolVersion':'4','currentTemplateState':states[profile],'requestedConfig':{'capabilities':{'authorization':{'enabled':True,'config':{}}}},'mode':'APPLY'}
 status,data,_=post('/v1/update',body);assert status==200
 z=zipfile.ZipFile(io.BytesIO(data)); info=json.loads(z.read('extension-update-package.json'))
 assert not any(o['target'] in legacy for o in info['operations'])
 for asset in assets:
  ops=[o for o in info['operations'] if o['target']==asset];assert len(ops)==1 and ops[0]['type']=='ADD_FILE';assert z.read(ops[0]['payloadRef'])
  if asset==sql_target:
   assert z.read(ops[0]['payloadRef'])==sql_source==generated_sql['authorization']
   assert 'backend:authorization:sql/authorization/schema.sql' in info['nextTemplateState']['installedArtifacts']
   print(profile,'SQL ADD_FILE:',json.dumps(ops[0]),'payload SHA-256:',hashlib.sha256(z.read(ops[0]['payloadRef'])).hexdigest())
 assert sum(o['type']=='ENSURE_CAPABILITY_CONFIG' for o in info['operations'])==(3 if profile=='base' else 2)
 body['currentTemplateState']=info['nextTemplateState'];assert post('/v1/update',body)[0]==204
 print(profile,'-> authorization V4 ADD_FILE/config/no-change: PASS')
print('LIVE JAR CONTRACT PASS')
