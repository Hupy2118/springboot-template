#!/usr/bin/env python3
"""List and validate the Extension V3 Template Runtime inputs."""

import argparse
import pathlib
import sys


def fail(message):
    raise ValueError(message)


def add_tree(root, relative_root, result):
    source = root / relative_root
    if not source.is_dir() or source.is_symlink():
        fail("missing or unsafe Runtime Source directory: %s" % relative_root.as_posix())
    for path in sorted(source.rglob("*")):
        if path.is_symlink():
            fail("symlink is not allowed in Runtime Source: %s" % path.relative_to(root).as_posix())
        if path.is_dir():
            continue
        if not path.is_file():
            fail("unsupported Runtime Source entry: %s" % path.relative_to(root).as_posix())
        data = path.read_bytes()
        if b"\x00" in data:
            fail("binary Runtime Source is not allowed: %s" % path.relative_to(root).as_posix())
        try:
            data.decode("utf-8")
        except UnicodeDecodeError:
            fail("Runtime Source must be UTF-8 text: %s" % path.relative_to(root).as_posix())
        result.add(path.relative_to(root.parent).as_posix())


def add_file(root, relative, result):
    path = root / relative
    if path.is_symlink() or not path.is_file():
        fail("missing or unsafe Runtime Source file: %s" % relative.as_posix())
    data = path.read_bytes()
    if b"\x00" in data:
        fail("binary Runtime Source is not allowed: %s" % relative.as_posix())
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        fail("Runtime Source must be UTF-8 text: %s" % relative.as_posix())
    result.add(path.relative_to(root.parent).as_posix())


def extension_directories(root, relative_root):
    parent = root / relative_root
    if not parent.is_dir() or parent.is_symlink():
        fail("missing or unsafe extensions directory: %s" % relative_root.as_posix())
    children = sorted(parent.iterdir(), key=lambda item: item.name)
    directories = []
    for child in children:
        if child.is_symlink():
            fail("symlink is not allowed in Runtime Source: %s" % child.relative_to(root).as_posix())
        if child.is_dir():
            directories.append(child)
        elif child.name != ".gitkeep":
            fail("unexpected file in extensions directory: %s" % child.relative_to(root).as_posix())
    return directories


def collect_inputs(template_source):
    root = pathlib.Path(template_source).resolve()
    if not root.is_dir() or root.name != "template-source":
        fail("expected a template-source directory, got %s" % root)

    result = set()
    code = pathlib.Path("code")
    add_tree(root, code / "frontend" / "base", result)
    add_tree(root, code / "backend" / "base", result)

    for extension in extension_directories(root, code / "frontend" / "extensions"):
        add_file(root, extension.relative_to(root) / "extension.yaml", result)
        add_tree(root, extension.relative_to(root) / "src", result)

    for extension in extension_directories(root, code / "backend" / "extensions"):
        add_file(root, extension.relative_to(root) / "extension.yaml", result)
        for directory in ("src", "docs", "migrations"):
            candidate = extension / directory
            if candidate.exists() or candidate.is_symlink():
                add_tree(root, candidate.relative_to(root), result)

    revision = code / "template-revision.txt"
    if (root / revision).exists() or (root / revision).is_symlink():
        add_file(root, revision, result)
    prefix = "template-source/"
    return sorted(prefix + (path[len(prefix):] if path.startswith(prefix) else path) for path in result)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("template_source", nargs="?", default="template-source")
    args = parser.parse_args()
    try:
        print("\n".join(collect_inputs(args.template_source)))
    except (OSError, ValueError) as exc:
        raise SystemExit("Code Template Release input discovery failed: %s" % exc)


if __name__ == "__main__":
    main()
