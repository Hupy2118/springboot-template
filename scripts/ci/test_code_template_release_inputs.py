import importlib.util
import pathlib
import tempfile
import unittest

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
    def root(self, directory, include_runtime=True):
        root = directory / "template-source"
        directories = ["code/frontend/base", "code/frontend/extensions", "code/backend/base", "code/backend/extensions"]
        if include_runtime:
            directories.extend(("code/agent-runtime/base", "code/agent-runtime/extensions"))
        for relative in directories:
            (root / relative).mkdir(parents=True, exist_ok=True)
        return root

    def test_legacy_baseline_without_runtime_keeps_existing_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.root(pathlib.Path(temporary), include_runtime=False)
            touch(root, "code/frontend/base/src/index.tsx")
            touch(root, "code/backend/base/pom.xml")
            touch(root, "code/template-revision.txt", "2026.10.19.1\n")
            self.assertEqual(MODULE.collect_inputs(root, allow_legacy_runtime=True), [
                "template-source/code/backend/base/pom.xml",
                "template-source/code/frontend/base/src/index.tsx",
                "template-source/code/template-revision.txt",
            ])
            with self.assertRaisesRegex(ValueError, "missing or unsafe Runtime Source directory"):
                MODULE.collect_inputs(root)

    def test_collects_runtime_inputs_even_in_legacy_mode(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.root(pathlib.Path(temporary))
            paths = (
                "code/agent-runtime/base/pyproject.toml",
                "code/agent-runtime/base/uv.lock",
                "code/agent-runtime/extensions/python_business_mysql/extension.yaml",
                "code/agent-runtime/extensions/python_business_mysql/files/src/app/infrastructure/database.py",
            )
            for path in paths:
                touch(root, path)
            expected = sorted("template-source/" + path for path in paths)
            self.assertEqual(MODULE.collect_inputs(root), expected)
            self.assertEqual(MODULE.collect_inputs(root, allow_legacy_runtime=True), expected)

    def test_legacy_mode_rejects_incomplete_runtime(self):
        for directory in ("base", "extensions"):
            with self.subTest(directory=directory), tempfile.TemporaryDirectory() as temporary:
                root = self.root(pathlib.Path(temporary), include_runtime=False)
                (root / "code/agent-runtime" / directory).mkdir(parents=True)
                with self.assertRaisesRegex(ValueError, "missing or unsafe"):
                    MODULE.collect_inputs(root, allow_legacy_runtime=True)

    def test_legacy_mode_rejects_runtime_symlink(self):
        for dangling in (False, True):
            with self.subTest(dangling=dangling), tempfile.TemporaryDirectory() as temporary:
                root = self.root(pathlib.Path(temporary), include_runtime=False)
                outside = pathlib.Path(temporary) / "outside"
                if not dangling:
                    outside.mkdir()
                (root / "code/agent-runtime").symlink_to(outside, target_is_directory=True)
                with self.assertRaisesRegex(ValueError, "symlink"):
                    MODULE.collect_inputs(root, allow_legacy_runtime=True)

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

            inputs = MODULE.collect_inputs(root)

            self.assertIn("template-source/code/frontend/base/src/index.tsx", inputs)
            self.assertIn("template-source/code/frontend/extensions/login/extension.yaml", inputs)
            self.assertIn("template-source/code/backend/extensions/login/docs/login.md", inputs)
            self.assertIn("template-source/code/template-revision.txt", inputs)
            self.assertFalse(any("/workspace/" in item or "/assembly/" in item or "/node_modules/" in item or "/build/" in item for item in inputs))
            self.assertFalse(any("profiles.json" in item for item in inputs))
            self.assertEqual(sorted(inputs), inputs)

    def test_rejects_symlinked_runtime_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.root(pathlib.Path(temporary))
            outside = pathlib.Path(temporary) / "outside.ts"
            outside.write_text("text\n", encoding="utf-8")
            linked = root / "code/frontend/base/linked.ts"
            linked.symlink_to(outside)
            with self.assertRaisesRegex(ValueError, "symlink"):
                MODULE.collect_inputs(root)

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
