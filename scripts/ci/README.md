# CI scripts

- `verify-template-release-revision.sh <base-ref>` enforces a strictly increasing `YYYY.MM.DD.N` Template Release revision when any runtime input changes. It derives inputs from both `<base-ref>` and `HEAD`, so deleted inputs are covered as well.
- `verify-base-frontend.sh` builds the declared Base frontend in an isolated temporary directory.

The revision gate requires Python 3 with PyYAML. Every script resolves the repository root from its own path and may be run from any working directory.
