#!/usr/bin/env python3
"""Focused regression tests for Template Release input discovery."""

import importlib.util
import pathlib
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("release_inputs", SCRIPT_DIR / "list-template-release-inputs.py")
RELEASE_INPUTS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE_INPUTS)


class TemplateReleaseInputTest(unittest.TestCase):
    def test_manifest_references_include_docs_and_migration_consumer(self):
        with tempfile.TemporaryDirectory() as workspace:
            source = pathlib.Path(workspace) / "template-source"
            base = source / "base"
            capability = source / "capabilities" / "login"
            (base / "backend/docs").mkdir(parents=True)
            (capability / "backend/src/main/java/com/cmbchina/backend/auth/bootstrap").mkdir(parents=True)
            (base / "base.yaml").write_text("files:\n  - source: backend/docs/project-structure.md\n    target: backend/docs/project-structure.md\n")
            (base / "backend/docs/project-structure.md").write_text("generated documentation")
            (source / "strategy-registry-v2.yaml").write_text("targets:\n  - path: backend/docs/project-structure.md\n")
            (capability / "capability-v2.yaml").write_text("additions: []\nmigrations:\n  - source: backend/migration.sql\n")
            (capability / "backend/migration.sql").write_text("select 1;")
            consumer = capability / "backend/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java"
            consumer.write_text("migration consumer")

            inputs = RELEASE_INPUTS.collect_inputs(source)

            self.assertIn("template-source/base/backend/docs/project-structure.md", inputs)
            self.assertIn("template-source/capabilities/login/backend/migration.sql", inputs)
            self.assertIn("template-source/capabilities/login/backend/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java", inputs)


if __name__ == "__main__":
    unittest.main()
