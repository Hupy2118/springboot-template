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
        if ignored_runtime_entry(path, source):
            continue
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
        if ignored_runtime_entry(child, parent):
            continue
        if child.is_symlink():
            fail("symlink is not allowed in Runtime Source: %s" % child.relative_to(root).as_posix())
        if child.is_dir():
            directories.append(child)
        elif child.name != ".gitkeep":
            fail("unexpected file in extensions directory: %s" % child.relative_to(root).as_posix())
    return directories


def ignored_runtime_entry(path, root):
    """Dependency installations and Finder metadata are not V3 Runtime Source."""
    return path.name == ".DS_Store" or any(part in ("node_modules", "build") for part in path.relative_to(root).parts)


def runtime_entry_exists(path):
    # Include dangling symlinks so the Runtime Source validators reject them.
    return path.exists() or path.is_symlink()


def add_extension_inputs(root, extensions, result, *, required_trees=(), optional_trees=()):
    for extension in extension_directories(root, extensions):
        relative = extension.relative_to(root)
        add_file(root, relative / "extension.yaml", result)

        fragment = relative / "AGENTS.fragment.md"
        if runtime_entry_exists(root / fragment):
            add_file(root, fragment, result)

        for directory in required_trees:
            add_tree(root, relative / directory, result)
        for directory in optional_trees:
            candidate = relative / directory
            if runtime_entry_exists(root / candidate):
                add_tree(root, candidate, result)


def collect_inputs(template_source):
    """枚举双端源码与路由契约，供当前发布输入和 Revision 校验使用。"""
    root = pathlib.Path(template_source).resolve()
    if not root.is_dir() or root.name != "template-source":
        fail("expected a template-source directory, got %s" % root)

    result = set()
    code = pathlib.Path("code")
    contracts = code / "contracts"
    if runtime_entry_exists(root / contracts):
        add_tree(root, contracts, result)

    frontend = code / "frontend"
    backend = code / "backend"
    add_tree(root, frontend / "base", result)
    add_tree(root, backend / "base", result)
    add_extension_inputs(root, frontend / "extensions", result, required_trees=("src",))
    add_extension_inputs(
        root, backend / "extensions", result,
        optional_trees=("src", "docs", "migrations", "scripts", "sql"),
    )

    revision = code / "template-revision.txt"
    if runtime_entry_exists(root / revision):
        add_file(root, revision, result)
    return sorted(result)


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
