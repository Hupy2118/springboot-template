import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / 'scripts/adapter.py'
spec = importlib.util.spec_from_file_location('adapter', MODULE)
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)


class OverlayTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.adapter = self.root / 'devtools/standalone-adapter'
        self.adapter.mkdir(parents=True)
        self.source = {'backend/base/a.txt':b'one\ntwo\n'}
        (self.adapter/'new.txt').write_bytes(b'new\n')

    def tearDown(self):
        self.tmp.cleanup()

    def op(self, kind, **extra):
        value = {'type':kind,'target':'backend/base/a.txt','reason':'test', 'source':'new.txt',
                 'expectedSourceSha256':a.digest(self.source['backend/base/a.txt'])}
        value.update(extra)
        return value

    def apply(self, *ops):
        return a.apply(self.source,self.adapter,{'schemaVersion':1,'operations':list(ops)})[0]

    def test_exact_operations_and_patch(self):
        self.assertEqual(self.apply(self.op('replace'))['backend/base/a.txt'],b'new\n')
        self.assertNotIn('backend/base/a.txt',self.apply(self.op('remove')))
        self.assertIn('backend/base/b.txt',self.apply(self.op('add',target='backend/base/b.txt')))
        (self.adapter/'change.patch').write_text(json.dumps({'before':'two\n','after':'three\n'}))
        op=self.op('patch',source='change.patch',expectedResultSha256=a.digest(b'one\nthree\n'))
        self.assertEqual(self.apply(op)['backend/base/a.txt'],b'one\nthree\n')
        op['expectedResultSha256']='0'*64
        with self.assertRaises(ValueError):self.apply(op)

    def test_missing_hash_duplicate_and_path_failures(self):
        for op in (self.op('replace',expectedSourceSha256='0'*64),self.op('replace',target='backend/base/missing'),
                   self.op('add'),self.op('add',target='../escape'),self.op('add',target='/tmp/escape'),
                   self.op('add',target='backend/base/../escape'), self.op('add',source='../escape',target='backend/base/b')):
            with self.subTest(op=op), self.assertRaises(ValueError):self.apply(op)
        with self.assertRaises(ValueError):self.apply(self.op('replace'),self.op('remove'))

    def test_symlink_case_and_binary_rejected(self):
        (self.adapter/'link').symlink_to(self.adapter/'new.txt')
        with self.assertRaises(ValueError): self.apply(self.op('replace',source='link'))
        with self.assertRaises(ValueError): a.validate_files({'A':b'a','a':b'b'})
        with self.assertRaises(ValueError): a.validate_files({'a':b'\x00'})
        with self.assertRaises(ValueError): a.tree(self.adapter)

    def test_private_residue_is_a_hard_failure(self):
        with self.assertRaises(ValueError):a.audit_files({'backend/base/pom.xml':b'<artifactId>bee-starter-auth</artifactId>'})
        a.audit_files({'backend/base/src/App.java':b'package com.cmbchina.backend;'})

    def fixture(self):
        code=self.root/'template-source/code'
        for name in ('contracts','frontend/base','backend/base','frontend/extensions','backend/extensions'):
            (code/name).mkdir(parents=True,exist_ok=True)
        (code/'template-revision.txt').write_text('2026.10.23.2\n')
        (code/'backend/base/a.txt').write_bytes(self.source['backend/base/a.txt'])
        for name in ('scripts/adapter.py','run-engine.sh','build.sh'):
            p=self.adapter/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('test')
        (self.adapter/'release.yaml').write_text(json.dumps({'schemaVersion':1,'sourceRevision':'2026.10.23.2','standaloneRevision':'2026.10.23.1002'}))
        (self.adapter/'manifest.yaml').write_text(json.dumps({'schemaVersion':1,'operations':[self.op('replace')]}))
        return code

    @patch.object(a,'git_sha',return_value='baseline')
    def test_reproducible_prepare_audit_and_rollback(self, _):
        code=self.fixture()
        build=self.root/'.standalone-build'
        a.ensure_output(self.root,build)
        with a.lock(build):one=a.prepare(self.root,self.adapter)
        with a.lock(build):two=a.prepare(self.root,self.adapter)
        self.assertEqual(one,two)
        self.assertEqual(a.audit(build/'template-source',self.root,self.adapter)['fingerprint'],one['fingerprint'])
        self.assertEqual((code/'backend/base/a.txt').read_bytes(),b'one\ntwo\n')
        prior=a.tree(build/'template-source')
        (self.adapter/'new.txt').write_text('changed')
        with a.lock(build),self.assertRaisesRegex(ValueError,'Revision bump'):a.prepare(self.root,self.adapter)
        self.assertEqual(a.tree(build/'template-source'),prior)
        with self.assertRaises(ValueError):a.audit(build/'template-source',self.root,self.adapter)

    def test_revision_and_live_lock_failures(self):
        self.fixture()
        source=a.runtime_files(self.root/'template-source/code')
        p=self.adapter/'release.yaml'
        config=json.loads(p.read_text())
        for field,value in [('sourceRevision','2026.10.23.3'),('standaloneRevision','2026.10.23.2'),('standaloneRevision','2026.02.30.1002')]:
            bad=dict(config);bad[field]=value;p.write_text(json.dumps(bad))
            with self.assertRaises(ValueError):a.release(self.adapter,source)
        build=self.root/'.standalone-build';build.mkdir()
        with a.lock(build):
            with self.assertRaisesRegex(ValueError,'Release in use'):
                with a.lock(build):pass


if __name__=='__main__':unittest.main()
