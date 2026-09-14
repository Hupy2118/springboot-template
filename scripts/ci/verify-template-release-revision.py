#!/usr/bin/env python3
"""Enforce monotonic Template Release revisions for runtime input changes."""

import argparse
import datetime
import importlib.util
import pathlib
import subprocess
import sys
import tarfile
import tempfile
import re

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("release_inputs", SCRIPT_DIR / "list-template-release-inputs.py")
RELEASE_INPUTS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE_INPUTS)

REVISION_RE = re.compile(r"^(\d{4})\.(\d{2})\.(\d{2})\.([1-9]\d*)$")


def fail(message):
    raise SystemExit("Template Release revision gate failed: %s" % message)


def parse_revision(value, label):
    match = REVISION_RE.fullmatch(value.strip())
    if not match:
        fail("%s revision must use YYYY.MM.DD.N with N >= 1: %r" % (label, value.strip()))
    year, month, day, sequence = (int(part) for part in match.groups())
    try:
        datetime.date(year, month, day)
    except ValueError:
        fail("%s revision has an invalid calendar date: %s" % (label, value.strip()))
    return year, month, day, sequence


def git(root, *args):
    completed = subprocess.run(["git", *args], cwd=str(root), text=True,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if completed.returncode:
        fail("git %s: %s" % (" ".join(args), completed.stderr.strip()))
    return completed.stdout


def export_revision(root, revision, destination):
    archive = subprocess.run(["git", "archive", "--format=tar", revision], cwd=str(root),
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if archive.returncode:
        fail("cannot read %s: %s" % (revision, archive.stderr.decode().strip()))
    try:
        with tarfile.open(fileobj=__import__("io").BytesIO(archive.stdout)) as contents:
            contents.extractall(destination)
    except (tarfile.TarError, OSError) as exc:
        fail("cannot extract %s: %s" % (revision, exc))


def revision_at(root, revision):
    return git(root, "show", "%s:template-source/template-revision.txt" % revision).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("base_ref")
    args = parser.parse_args()
    root = SCRIPT_DIR.parent.parent
    base_revision = parse_revision(revision_at(root, args.base_ref), "base")
    head_revision = parse_revision(revision_at(root, "HEAD"), "HEAD")

    with tempfile.TemporaryDirectory(prefix="template-release-inputs-") as workspace:
        workspace = pathlib.Path(workspace)
        base_tree = workspace / "base"
        head_tree = workspace / "head"
        base_tree.mkdir()
        head_tree.mkdir()
        export_revision(root, args.base_ref, base_tree)
        export_revision(root, "HEAD", head_tree)
        try:
            managed_inputs = set(RELEASE_INPUTS.collect_inputs(base_tree / "template-source"))
            managed_inputs.update(RELEASE_INPUTS.collect_inputs(head_tree / "template-source"))
        except ValueError as exc:
            fail(str(exc))

    changed = set(git(root, "diff", "--name-only", "%s...HEAD" % args.base_ref).splitlines())
    managed_changes = sorted(changed.intersection(managed_inputs))
    if not managed_changes:
        return
    if head_revision <= base_revision:
        fail("managed Template Source inputs changed but HEAD revision is not greater than base "
             "(%s <= %s):\n%s" % (revision_at(root, "HEAD"), revision_at(root, args.base_ref),
                                    "\n".join(managed_changes)))


if __name__ == "__main__":
    main()
