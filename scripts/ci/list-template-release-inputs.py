#!/usr/bin/env python3
"""Print the runtime Template Release inputs declared by a Template Source tree."""

import argparse
import pathlib
import sys

try:
    import yaml
except ImportError as exc:
    raise SystemExit("PyYAML is required to inspect Template Release inputs: %s" % exc)


def fail(message):
    raise ValueError(message)


def load_yaml(path):
    try:
        with path.open("r", encoding="utf-8") as stream:
            value = yaml.safe_load(stream)
    except (OSError, yaml.YAMLError) as exc:
        fail("cannot parse %s: %s" % (path, exc))
    if not isinstance(value, dict):
        fail("expected YAML object in %s" % path)
    return value


def relative_file(template_source, declaration_root, relative):
    if not isinstance(relative, str) or not relative.strip():
        fail("invalid referenced path: %r" % (relative,))
    path = (declaration_root / relative).resolve()
    try:
        path.relative_to(declaration_root.resolve())
    except ValueError:
        fail("referenced path escapes Template Source: %s" % relative)
    if not path.is_file():
        fail("referenced Runtime input does not exist: %s" % relative)
    return path.relative_to(template_source.parent.resolve()).as_posix()


def declared_sources(manifest, field):
    values = manifest.get(field, [])
    if not isinstance(values, list):
        fail("%s must be a list" % field)
    for value in values:
        if not isinstance(value, dict):
            fail("%s entry must be an object" % field)
        yield value


def collect_inputs(template_source):
    """Return sorted repo-relative Template Source runtime input paths.

    `template_source` is the template-source directory. Paths are reported with
    the `template-source/` prefix so they can be compared to `git diff` output.
    """
    root = pathlib.Path(template_source).resolve()
    if not root.is_dir() or root.name != "template-source":
        fail("expected a template-source directory, got %s" % root)

    inputs = {"template-source/base/base.yaml", "template-source/strategy-registry-v2.yaml"}
    base_root = root / "base"
    base = load_yaml(base_root / "base.yaml")
    for entry in declared_sources(base, "files"):
        inputs.add(relative_file(root, base_root, entry.get("source")))

    registry = load_yaml(root / "strategy-registry-v2.yaml")
    for target in declared_sources(registry, "targets"):
        inputs.add(relative_file(root, base_root, target.get("path")))

    capabilities_root = root / "capabilities"
    if not capabilities_root.is_dir():
        fail("missing capabilities directory")
    for capability_root in sorted(path for path in capabilities_root.iterdir() if path.is_dir()):
        manifest_path = capability_root / "capability-v2.yaml"
        if not manifest_path.is_file():
            fail("missing capability-v2.yaml for %s" % capability_root.name)
        inputs.add(manifest_path.relative_to(root.parent).as_posix())
        manifest = load_yaml(manifest_path)
        for entry in declared_sources(manifest, "additions"):
            inputs.add(relative_file(root, capability_root, entry.get("source")))
        migrations = list(declared_sources(manifest, "migrations"))
        for entry in migrations:
            inputs.add(relative_file(root, capability_root, entry.get("source")))
        if migrations:
            # CapabilityV2Loader reads this fixed consumer when validating every
            # migration trigger, so it is a Release input even though no manifest
            # field directly names it.
            inputs.add(relative_file(
                root,
                capability_root,
                "backend/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java"))

    return sorted(inputs)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("template_source", nargs="?", default="template-source")
    args = parser.parse_args()
    try:
        print("\n".join(collect_inputs(args.template_source)))
    except ValueError as exc:
        raise SystemExit("Template Release input discovery failed: %s" % exc)


if __name__ == "__main__":
    main()
