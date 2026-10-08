# CI scripts

The repository has one release revision gate for the V3 Template Engine source model:

- `verify-code-template-release-revision.sh <base-ref>` checks V3 Runtime Inputs and `template-source/code/template-revision.txt`.

The revision gate includes additions, modifications, and deletions. V3 Maintenance-only files (`code/**/workspace`, `assembly`, profiles, maintenance package files, and authoring documentation) do not bump the V3 Revision.

The revision gate requires Python 3. Every script resolves the repository root from its own path and may be run from any working directory.
