# CI scripts

The repository has independent release revision gates for the two Template Engine source models:

- `verify-template-release-revision.sh <base-ref>` checks Legacy V2 Runtime Inputs and `template-source/template-revision.txt`.
- `verify-code-template-release-revision.sh <base-ref>` checks Extension V3 Runtime Inputs and `template-source/code/template-revision.txt`.
- `verify-base-frontend.sh` builds the declared Legacy V2 Base frontend in an isolated temporary directory.

Both revision gates include additions, modifications, and deletions. A change in one Runtime Source does not implicitly bump the other Revision. V3 Maintenance-only files (`code/**/workspace`, `assembly`, profiles, maintenance package files, and authoring documentation) do not bump the V3 Revision.

The revision gates require Python 3; the Legacy V2 gate also requires PyYAML. Every script resolves the repository root from its own path and may be run from any working directory.
