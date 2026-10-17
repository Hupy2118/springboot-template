# CI scripts

The repository has a workflow-surface gate and one release revision gate for the V3 Template Engine source model:

- `check-protected-surfaces.py` verifies the small, explicit XCodeAgent workflow surface: file presence plus stable functions, protocol strings, schema fields, and managed-region markers. It does not use content hashes and deliberately excludes ordinary business code.
- `verify-code-template-release-revision.sh <base-ref>` invokes `check-protected-surfaces.py` as a fixed first step. Thus deleting the checker itself, a protected file, or a protected anchor fails the existing CI gate.

The revision gate then checks V3 Runtime Inputs and `template-source/code/template-revision.txt`.

The revision gate includes additions, modifications, and deletions. V3 Maintenance-only files (`code/**/workspace`, `assembly`, profiles, maintenance package files, and authoring documentation) do not bump the V3 Revision.

The revision gate requires Python 3. Every script resolves the repository root from its own path and may be run from any working directory.
