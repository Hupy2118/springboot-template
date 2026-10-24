"""Real adapter inputs and negative contract-drift regressions."""
import tempfile
import unittest
from pathlib import Path
from test_adapter import a


class ContractParityTests(unittest.TestCase):
    def setUp(self):
        self.source = a.runtime_files(a.REPO / 'template-source/code')
        self.manifest = a.json_read(a.ADAPTER / 'manifest.yaml')
        self.result, _ = a.apply(self.source, a.ADAPTER, self.manifest)
        self.login = 'backend/extensions/login/extension.yaml'

    def test_exact_contract_and_private_dependency_isolation(self):
        before = a.inventory(self.source)
        a.validate_contract_parity(self.source, self.result)
        a.audit_files(self.result)
        again, _ = a.apply(self.source, a.ADAPTER, self.manifest)
        self.assertEqual(a.inventory(self.result), a.inventory(again))
        self.assertEqual(before, a.inventory(a.runtime_files(a.REPO / 'template-source/code')))
        for marker in (b'  applicationYaml:', b'  applicationProfileYaml:'):
            self.assertEqual(self.source[self.login].split(marker, 1)[1],
                             self.result[self.login].split(marker, 1)[1])

    def test_configuration_identity_and_dependency_drift_fail_closed(self):
        content = self.result[self.login]
        for changed in (
            content.replace(b'  applicationYaml:\n', b'  applicationYaml:\n    standalone:\n      mock: true\n'),
            content.replace(b'        client-id: ${yht.authentication.client-id}\n', b''),
            content.replace(b'client-id: client-id', b'client-id: local-client'),
            content.replace(b'requires: []', b'requires: [authorization]'),
            content.replace(b'id: login', b'id: other'),
        ):
            with self.subTest(changed=changed), self.assertRaisesRegex(ValueError, 'contract drift'):
                a.validate_contract_parity(self.source, dict(self.result, **{self.login: changed}))
        auth = 'backend/extensions/authorization/extension.yaml'
        with self.assertRaisesRegex(ValueError, 'Authorization manifest'):
            a.validate_contract_parity(self.source, dict(self.result, **{auth: b'id: authorization\n'}))

    def test_upstream_manifest_change_rejects_old_patch(self):
        changed = dict(self.source)
        changed[self.login] += b'# upstream change\n'
        with self.assertRaisesRegex(ValueError, 'upstream SHA256 mismatch'):
            a.apply(changed, a.ADAPTER, self.manifest)
        # Prepare uses this same fail-closed apply path before activation.

    def test_missing_runtime_adaptation_is_rejected(self):
        for path in ('backend/base/pom.xml', 'backend/base/src/main/resources/application.yml',
                     'backend/extensions/login/src/main/java/com/cmbchina/backend/standalone/login/MockLoginConfiguration.java'):
            changed = dict(self.result)
            del changed[path]
            with self.assertRaisesRegex(ValueError, 'adaptation missing'):
                a.validate_contract_parity(self.source, changed)

    def test_prepare_reproducibility_and_source_drift(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            for key, data in self.source.items():
                path = repo / 'template-source/code' / key
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            with patch.object(a, 'git_sha', return_value='test-head'):
                one = a.prepare(repo, a.ADAPTER)
                two = a.prepare(repo, a.ADAPTER)
                self.assertEqual(one, two)
                path = repo / 'template-source/code' / self.login
                path.write_bytes(path.read_bytes() + b'# changed\n')
                with self.assertRaisesRegex(ValueError, 'upstream SHA256 mismatch'):
                    a.prepare(repo, a.ADAPTER)
                self.assertEqual(a.json_read(repo / '.standalone-build/template-source/build-fingerprint.json'), one)
