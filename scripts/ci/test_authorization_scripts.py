"""Script transport contract. PowerShell cases run on hosts with PowerShell installed."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'template-source/code/backend/extensions/authorization/scripts'
POWERSHELL = shutil.which('powershell.exe') or shutil.which('pwsh')


class AuthorizationScriptsTest(unittest.TestCase):
    def check_script(self, powershell=False):
        with tempfile.TemporaryDirectory(prefix='bootstrap space ') as directory:
            root = Path(directory)
            backend = root / 'backend'
            scripts = backend / 'scripts'
            shutil.copytree(SOURCE, scripts)
            dump = root / 'dump.json'
            env = dict(os.environ, BOOTSTRAP_TEST_DUMP=str(dump))
            arguments = ['--dry-run', '--technical-plan=a path with \'single\' and "double" quotes.json', '--ddl=C:\\some folder\\ddl.sql']
            if powershell:
                fake = root / 'fake maven.ps1'
                fake.write_text('''$values = @{}
Get-ChildItem Env:AUTHORIZATION_BOOTSTRAP_* | ForEach-Object { $values[$_.Name] = $_.Value }
@{ cwd = (Get-Location).Path; argv = @($args); env = $values } | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $env:BOOTSTRAP_TEST_DUMP
$global:LASTEXITCODE = 23
''', encoding='utf-8')
                command = [POWERSHELL, '-NoProfile', '-File', str(scripts / 'bootstrap-authorization.ps1')]
            else:
                fake = root / 'fake maven'
                fake.write_text('''#!/usr/bin/env python3
import json, os, sys
with open(os.environ['BOOTSTRAP_TEST_DUMP'], 'w') as stream:
    json.dump(dict(cwd=os.getcwd(), argv=sys.argv[1:], env=dict(os.environ)), stream)
sys.exit(23)
''', encoding='utf-8')
                fake.chmod(0o755)
                command = ['/bin/bash', str(scripts / 'bootstrap-authorization.sh')]
            env['MAVEN_CMD'] = str(fake)
            result = subprocess.run(command + arguments, cwd=root, env=env, capture_output=True, text=True)
            self.assertEqual(23, result.returncode, result.stderr)
            value = json.loads(dump.read_text(encoding='utf-8-sig'))
            self.assertEqual(str(backend.resolve()), value['cwd'])
            self.assertIn('-Dexec.mainClass=com.cmbchina.backend.auth.bootstrap.AuthorizationBootstrapCommand', value['argv'])
            self.assertIn('org.codehaus.mojo:exec-maven-plugin:3.1.0:java', value['argv'])
            self.assertEqual(str(len(arguments)), value['env']['AUTHORIZATION_BOOTSTRAP_ARGUMENT_COUNT'])
            for index, argument in enumerate(arguments):
                self.assertEqual(argument, value['env'][f'AUTHORIZATION_BOOTSTRAP_ARGUMENT_{index}'])
            self.assertNotIn('password', result.stdout + result.stderr)

    @unittest.skipUnless(Path('/bin/bash').exists(), 'Bash unavailable')
    def test_bash_arguments_cwd_main_class_and_failure_status(self):
        self.check_script()

    @unittest.skipUnless(POWERSHELL, 'PowerShell unavailable; run on Windows CI')
    def test_powershell_arguments_cwd_main_class_and_failure_status(self):
        self.check_script(powershell=True)


if __name__ == '__main__':
    unittest.main()
