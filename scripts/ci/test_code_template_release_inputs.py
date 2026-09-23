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


if __name__ == "__main__":
    unittest.main()
