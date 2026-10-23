#!/usr/bin/env python3
"""Explicit user-run Bootstrap helper; never invoked by automatic verification."""
import argparse
import os
from pathlib import Path
import sys
from urllib.parse import urlparse,parse_qs
from adapter import ADAPTER, REPO, require, json_read, safe_file


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--project',type=Path,required=True,help='Generate output root containing backend/ and .devagentstudio/')
    parser.add_argument('--dry-run',action='store_true')
    args=parser.parse_args()
    project=args.project.absolute()
    require(project.resolve().is_relative_to((REPO/'.standalone-build').resolve()),'manual fixture must stay in .standalone-build')
    state=json_read(safe_file(project,'.devagentstudio/template-state.json'))
    release=json_read(ADAPTER/'release.yaml')
    require(state['templateRevision']==release['standaloneRevision'] and 'authorization' in state['effective'],'requires current standalone Authorization project')
    raw=os.environ.get('DB_URL','')
    uri=urlparse(raw.removeprefix('jdbc:'))
    query={k.lower() for k in parse_qs(uri.query)}
    require(raw.startswith('jdbc:mysql://') and uri.hostname in ('127.0.0.1','localhost','::1') and uri.path.endswith('_standalone_test')
            and uri.path[1:].replace('_','').isalnum() and not uri.username and not query.intersection({'user','username','password','allowloadlocalinfile','autodeserialize'}),
            'DB_URL must identify a local *_standalone_test database without inline credentials')
    require(os.environ.get('DB_USERNAME') and 'DB_PASSWORD' in os.environ,'set DB_USERNAME and DB_PASSWORD in this terminal')
    backend=project/'backend'
    command=[str(safe_file(backend,'scripts/bootstrap-authorization.sh')),
             '--technical-plan='+str(ADAPTER/'tests/fixture/technical-plan.json'),
             '--application='+str(ADAPTER/'tests/fixture/application.json'),
             '--application-yml='+str(backend/'src/main/resources/application.yml'),
             '--ddl='+str(safe_file(backend,'sql/authorization/schema.sql')),'--profile=local']
    if args.dry_run:command.append('--dry-run')
    os.environ['MAVEN_CMD']=str(ADAPTER/'scripts/mvn-public.sh')
    os.chdir(backend)
    os.execv(command[0],command)

if __name__=='__main__':
    try:main()
    except (ValueError,OSError,KeyError) as error:
        print('Manual database check: '+str(error),file=sys.stderr);sys.exit(1)
