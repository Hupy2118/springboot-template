#!/usr/bin/env python3
"""Enforce a strictly increasing V3 Code Template Revision for Runtime changes."""

import argparse
import datetime
import importlib.util
import pathlib
import re
import subprocess
import tarfile
import tempfile

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("code_release_inputs", SCRIPT_DIR / "list-code-template-release-inputs.py")
CODE_RELEASE_INPUTS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CODE_RELEASE_INPUTS)
REVISION_RE = re.compile(r"^(\d{4})\.(\d{2})\.(\d{2})\.([1-9]\d*)$")
REVISION_PATH = "template-source/code/template-revision.txt"


def fail(message):
    raise SystemExit("Code Template Release revision gate failed: %s" % message)


def git(root, *args, allow_failure=False):
    completed = subprocess.run(["git", *args], cwd=str(root), text=True,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if completed.returncode and not allow_failure:
        fail("git %s: %s" % (" ".join(args), completed.stderr.strip()))
    return completed


def revision_tuple(value, label):
    value = value.strip()
    match = REVISION_RE.fullmatch(value)
    if not match:
        fail("%s revision must use YYYY.MM.DD.N with N >= 1: %r" % (label, value))
    parts = tuple(int(part) for part in match.groups())
    try:
        datetime.date(parts[0], parts[1], parts[2])
    except ValueError:
        fail("%s revision has an invalid calendar date: %s" % (label, value))
    return parts


def revision_at(root, revision):
    probe = git(root, "cat-file", "-e", "%s:%s" % (revision, REVISION_PATH), allow_failure=True)
    if probe.returncode:
        return None
    return git(root, "show", "%s:%s" % (revision, REVISION_PATH)).stdout.strip()


def export_revision(root, revision, destination):
    archive = subprocess.run(["git", "archive", "--format=tar", revision], cwd=str(root),
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if archive.returncode:
        fail("cannot read %s: %s" % (revision, archive.stderr.decode("utf-8", "replace").strip()))
    try:
        with tarfile.open(fileobj=__import__("io").BytesIO(archive.stdout)) as contents:
            contents.extractall(destination)
    except (tarfile.TarError, OSError) as exc:
        fail("cannot extract %s: %s" % (revision, exc))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("base_ref")
    args = parser.parse_args()
    root = SCRIPT_DIR.parent.parent

    revision_path = root / REVISION_PATH
    if revision_path.is_symlink() or not revision_path.is_file():
        fail("working tree is missing or has an unsafe %s" % REVISION_PATH)
    try:
        head_text = revision_path.read_text(encoding="utf-8").strip()
    except (OSError, UnicodeError) as exc:
        fail("cannot read working-tree Revision: %s" % exc)
    head_revision = revision_tuple(head_text, "HEAD")
    base_text = revision_at(root, args.base_ref)
    base_revision = revision_tuple(base_text, "base") if base_text is not None else (0, 0, 0, 0)

    with tempfile.TemporaryDirectory(prefix="code-template-release-inputs-") as workspace:
        workspace = pathlib.Path(workspace)
        base_tree = workspace / "base"
        base_tree.mkdir()
        export_revision(root, args.base_ref, base_tree)
        try:
            managed_inputs = set(CODE_RELEASE_INPUTS.collect_inputs(base_tree / "template-source"))
            managed_inputs.update(CODE_RELEASE_INPUTS.collect_inputs(root / "template-source"))
        except (OSError, ValueError) as exc:
            fail(str(exc))

    changed = set(git(root, "diff", "--name-only", args.base_ref).stdout.splitlines())
    changed.update(git(root, "ls-files", "--others", "--exclude-standard").stdout.splitlines())
    managed_changes = sorted(changed.intersection(managed_inputs))
    if managed_changes and head_revision <= base_revision:
        fail("V3 Runtime Source changed but code/template-revision.txt did not strictly increase "
             "(%s <= %s):\n%s" % (head_text, base_text or "<absent>", "\n".join(managed_changes)))


if __name__ == "__main__":
    main()
