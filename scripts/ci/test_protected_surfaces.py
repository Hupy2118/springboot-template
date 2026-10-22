import importlib.util
import pathlib
import shutil
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).with_name("check-protected-surfaces.py")
SPEC = importlib.util.spec_from_file_location("protected_surfaces", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
REPOSITORY = SCRIPT.parents[2]


class ProtectedSurfacesTest(unittest.TestCase):
    def fixture(self, temporary):
        root = pathlib.Path(temporary)
        for relative, _ in MODULE.PROTECTED_SURFACES:
            source = REPOSITORY / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(str(source), str(target))
        return root

    def test_current_workflow_surface_passes(self):
        self.assertEqual([], MODULE.find_violations(REPOSITORY))

    def test_missing_protected_file_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.fixture(temporary)
            (root / "template-source/code/contracts/route-projector.json").unlink()
            self.assertTrue(any("missing or unsafe protected file" in item
                                for item in MODULE.find_violations(root)))

    def test_missing_protected_anchor_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.fixture(temporary)
            target = root / "template-source/code/frontend/base/src/constants/routes.tsx"
            target.write_text(target.read_text(encoding="utf-8").replace(
                "DEVAGENTSTUDIO_BUSINESS_ROUTES_START", "BUSINESS_ROUTES_START"), encoding="utf-8")
            self.assertTrue(any("missing protected anchor" in item
                                for item in MODULE.find_violations(root)))

    def test_unprotected_business_file_does_not_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = self.fixture(temporary)
            business = root / "template-source/code/frontend/base/src/pages/Orders/index.tsx"
            business.parent.mkdir(parents=True, exist_ok=True)
            business.write_text("export default function Orders() { return null; }\n", encoding="utf-8")
            self.assertEqual([], MODULE.find_violations(root))


if __name__ == "__main__":
    unittest.main()
