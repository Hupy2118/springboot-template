import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = pathlib.Path(__file__).with_name("list-code-template-release-inputs.py")
SPEC = importlib.util.spec_from_file_location("code_release_inputs_tested", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def touch(root, relative, content="text\n"):
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


class CodeTemplateReleaseInputsTest(unittest.TestCase):
    def root(self, directory):
        root = directory / "template-source"
        for relative in ("code/frontend/base", "code/frontend/extensions", "code/backend/base", "code/backend/extensions"):
            (root / relative).mkdir(parents=True, exist_ok=True)
        return root

    def test_collects_only_runtime_inputs_and_includes_revision(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.root(pathlib.Path(temporary))
            touch(root, "code/frontend/base/src/index.tsx")
            touch(root, "code/frontend/extensions/login/extension.yaml", "id: login\nrequires: []\n")
            touch(root, "code/frontend/extensions/login/src/Login.tsx")
            touch(root, "code/backend/base/pom.xml", "<project/>\n")
            touch(root, "code/backend/extensions/login/extension.yaml", "id: login\nrequires: []\n")
            touch(root, "code/backend/extensions/login/src/Login.java")
            touch(root, "code/backend/extensions/login/docs/login.md")
            touch(root, "code/backend/extensions/login/migrations/001.sql")
            touch(root, "code/template-revision.txt", "2026.09.23.2\n")
            touch(root, "code/frontend/workspace/ignored.ts")
            touch(root, "code/frontend/assembly/ignored.mjs")
            touch(root, "code/frontend/profiles.json", "{}\n")
            touch(root, "code/frontend/node_modules/pkg/index.js")
            touch(root, "code/frontend/build/app.js")

            touch(root, "code/backend/extensions/login/scripts/bootstrap.sh")
            touch(root, "code/backend/extensions/login/scripts/bootstrap.ps1")
            touch(root, "code/backend/extensions/authorization/extension.yaml", "id: authorization\nrequires: [login]\n")
            touch(root, "code/backend/extensions/authorization/sql/authorization/schema.sql", "CREATE TABLE test(id int);\n")
            inputs = MODULE.collect_inputs(root)

            self.assertIn("template-source/code/frontend/base/src/index.tsx", inputs)
            self.assertIn("template-source/code/frontend/extensions/login/extension.yaml", inputs)
            self.assertIn("template-source/code/backend/extensions/login/docs/login.md", inputs)
            self.assertIn("template-source/code/template-revision.txt", inputs)
            self.assertFalse(any("/workspace/" in item or "/assembly/" in item or "/node_modules/" in item or "/build/" in item for item in inputs))
            self.assertFalse(any("profiles.json" in item for item in inputs))
            self.assertIn("template-source/code/backend/extensions/login/scripts/bootstrap.sh", inputs)
            self.assertIn("template-source/code/backend/extensions/login/scripts/bootstrap.ps1", inputs)
            self.assertIn("template-source/code/backend/extensions/authorization/sql/authorization/schema.sql", inputs)
            self.assertIn("template-source/code/backend/extensions/login/migrations/001.sql", inputs)
            self.assertEqual(sorted(inputs), inputs)

    def test_sql_rejects_symlinks_binary_and_non_utf8_inputs(self):
        for invalid in ("symlink", b"\x00", b"\xff"):
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as temporary:
                root = self.root(pathlib.Path(temporary))
                touch(root, "code/backend/extensions/authorization/extension.yaml", "id: authorization\n")
                target = touch(root, "code/backend/extensions/authorization/sql/authorization/schema.sql")
                if invalid == "symlink":
                    target.unlink()
                    target.symlink_to(touch(root, "outside.sql"))
                else:
                    target.write_bytes(invalid)
                with self.assertRaisesRegex(ValueError, "symlink|binary|UTF-8"):
                    MODULE.collect_inputs(root)

    def test_sql_change_requires_revision_increase(self):
        spec = importlib.util.spec_from_file_location("revision_gate_tested", SCRIPT.with_name("verify-code-template-release-revision.py"))
        gate = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(gate)
        with tempfile.TemporaryDirectory() as temporary:
            repository = pathlib.Path(temporary)
            root = self.root(repository)
            # Git archives must retain the otherwise empty required directories.
            touch(root, "code/frontend/base/.gitkeep")
            touch(root, "code/frontend/extensions/.gitkeep")
            touch(root, "code/backend/base/.gitkeep")
            touch(root, "code/backend/extensions/authorization/extension.yaml", "id: authorization\n")
            ddl = touch(root, "code/backend/extensions/authorization/sql/authorization/schema.sql", "CREATE TABLE test(id int);\n")
            revision = touch(root, "code/template-revision.txt", "2026.10.23.1\n")
            for args in (("init", "-q"), ("add", "."), ("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "fixture")):
                subprocess.run(["git", *args], cwd=repository, check=True, capture_output=True)
            # Use real git/collection/revision checks on a fixture repository;
            # the protected-surface checker still runs against the actual source tree.
            with patch.object(gate, "SCRIPT_DIR", repository / "scripts/ci"), patch.object(sys, "argv", ["gate", "HEAD"]):
                gate.main()
                ddl.write_text("CREATE TABLE test(id bigint);\n", encoding="utf-8")
                with self.assertRaisesRegex(SystemExit, "did not strictly increase"):
                    gate.main()
                revision.write_text("2026.10.23.2\n", encoding="utf-8")
                gate.main()

    def test_rejects_symlinked_runtime_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.root(pathlib.Path(temporary))
            outside = pathlib.Path(temporary) / "outside.ts"
            outside.write_text("text\n", encoding="utf-8")
            linked = root / "code/frontend/base/linked.ts"
            linked.symlink_to(outside)
            with self.assertRaisesRegex(ValueError, "symlink"):
                MODULE.collect_inputs(root)

    def test_ignores_machine_metadata_and_installed_dependencies_inside_runtime_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.root(pathlib.Path(temporary))
            touch(root, "code/frontend/base/src/index.tsx")
            metadata = root / "code/frontend/base/.DS_Store"
            metadata.write_bytes(b"\x00metadata")
            installed = root / "code/frontend/base/node_modules/pkg/index.js"
            installed.parent.mkdir(parents=True, exist_ok=True)
            installed.write_bytes(b"\x00dependency")
            (root / "code/backend/extensions/.DS_Store").write_bytes(b"\x00metadata")
            (root / "code/backend/extensions/node_modules/pkg").mkdir(parents=True, exist_ok=True)

            inputs = MODULE.collect_inputs(root)

            self.assertIn("template-source/code/frontend/base/src/index.tsx", inputs)
            self.assertFalse(any(".DS_Store" in item or "/node_modules/" in item for item in inputs))

    def test_rejects_binary_runtime_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.root(pathlib.Path(temporary))
            target = root / "code/backend/base/image.bin"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b"\x00\x01")
            with self.assertRaisesRegex(ValueError, "binary"):
                MODULE.collect_inputs(root)

    def test_ignores_os_metadata_and_dependency_symlinks(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.root(pathlib.Path(temporary))
            touch(root, "code/frontend/base/src/index.tsx")
            touch(root, "code/backend/extensions/.DS_Store")
            dependency = touch(root, "code/frontend/base/node_modules/pkg/index.js")
            (dependency.parent / "linked").symlink_to(dependency)
            self.assertIn("template-source/code/frontend/base/src/index.tsx", MODULE.collect_inputs(root))


if __name__ == "__main__":
    unittest.main()
