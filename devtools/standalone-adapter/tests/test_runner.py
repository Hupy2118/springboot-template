import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ADAPTER=Path(__file__).resolve().parents[1]


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.root=Path(self.tmp.name)
        self.adapter=self.root/'devtools/standalone-adapter'
        self.adapter.mkdir(parents=True)
        shutil.copy2(ADAPTER/'run-engine.sh',self.adapter/'run-engine.sh')
        source=self.root/'template-source/code';source.mkdir(parents=True)
        (source/'template-revision.txt').write_text('2026.10.23.2\n')
        jar=self.root/'template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar'
        jar.parent.mkdir(parents=True);jar.touch()
        binary=self.root/'bin';binary.mkdir()
        for cmd,body in {'java':'printf "%s\\n" "$TEMPLATE_ENGINE_SOURCE_ROOT" "$@"','git':'echo baseline','python3':'echo python-must-not-run >&2; exit 90'}.items():
            p=binary/cmd;p.write_text('#!/bin/sh\n'+body+'\n');p.chmod(0o755)
        self.env=dict(os.environ,PATH=str(binary)+os.pathsep+os.environ['PATH'])
        for key in ('TEMPLATE_ENGINE_STANDALONE_ENABLED','TEMPLATE_ENGINE_SOURCE_ROOT'):
            self.env.pop(key,None)

    def tearDown(self):self.tmp.cleanup()

    def run_script(self,**env):
        return subprocess.run([str(self.adapter/'run-engine.sh')],env=dict(self.env,**env),capture_output=True,text=True)

    def test_default_and_false_need_no_adapter_files_or_python(self):
        for mode in (None,'false',''):
            kwargs={} if mode is None else {'TEMPLATE_ENGINE_STANDALONE_ENABLED':mode}
            result=self.run_script(**kwargs)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn('mode=native',result.stdout)
            self.assertIn(str(self.root/'template-source'),result.stdout)
            self.assertFalse((self.root/'.standalone-build').exists())

    def test_invalid_and_conflicting_modes_rejected(self):
        for kwargs in ({'TEMPLATE_ENGINE_STANDALONE_ENABLED':'TRUE'}, {'TEMPLATE_ENGINE_SOURCE_ROOT':'/wrong'},
                       {'TEMPLATE_ENGINE_STANDALONE_ENABLED':'true','TEMPLATE_ENGINE_SOURCE_ROOT':str(self.root/'template-source')}):
            result=self.run_script(**kwargs)
            self.assertNotEqual(result.returncode,0)
            self.assertNotIn('mode=native',result.stdout)

    def test_standalone_preparation_failure_never_falls_back(self):
        result=self.run_script(TEMPLATE_ENGINE_STANDALONE_ENABLED='true')
        self.assertEqual(result.returncode,90)
        self.assertNotIn('-jar',result.stdout)
