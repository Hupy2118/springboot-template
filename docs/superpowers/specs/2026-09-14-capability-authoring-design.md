# Capability Authoring Developer Experience Design

## Goal

Implement the four stages in `docs/Capability_Authoring_Guide.md` without changing Template Runtime or the `StrategyUpdatePackageV2` contract.

## Scope and boundaries

`capability-authoring` remains an offline producer of `template-source/`. Workbench data is local-only and ignored by Git. Engine Core and Engine Service neither read workbench files nor retain authoring state. Every change that cannot be expressed as an Addition, `ENSURE_IMPORT`, or `TEXT_ANCHOR_INSERT` is reported and blocks a build; no raw-patch fallback is introduced.

## Stage 1: command-line entry and read-only status

A repository-root executable `./capability` locates the repository, builds or invokes the `capability-authoring` CLI, and preserves the process exit status. `CapabilityCli` supports a uniform help path for the root command and each public subcommand.

`status <id>` resolves the Workbench and uses `FileDiffer` plus `CapabilityAnalyzer` directly. It returns a new immutable `CapabilityStatusReport` with additions, strategies, unsupported changes, and `READY`/`BLOCKED`; unlike `capture`, it never throws merely because the draft is uncompilable. Its terminal renderer prints counts and every unsupported `path` plus `reason`. `capture`, `compile`, and `verify` retain their current diagnostic behavior. `.workbench/` is added to `.gitignore`, and README links to the authoring guide.

## Stage 2: transactional build

`CapabilityBuildService.build(id)` is the developer-facing orchestration point. It copies the current source tree to a sibling staging directory, performs Workbench analysis and contract planning against staging, then materializes the draft into staging. It invokes `DraftTemplateSourceValidator` and `RoundTripVerifier` against staging and the Workbench project. Only after both succeed does it atomically replace the source root; all failures recursively remove staging and leave the source tree unchanged.

`CapabilityCompiler` is refactored to expose a compile-into-existing-root operation for the Build Service. Its existing public compile path may retain its own atomic behavior for advanced `compile`, but `build` must never invoke it in a way that publishes before validation. Build output contains change counts, validation statuses, and a final READY/FAILED result. The first round-trip mismatch includes its first path through the existing exception code.

## Stage 3: repeatable Workbench drafts

The local `compile-state.yaml` is represented by `CompileState` and read/written by `CompileStateStore`. It records `capabilityId`, generated strategy IDs, and validator IDs. On a repeated build, Build Service loads this state and validates that all recorded resources exist and match the current Draft ownership. It removes only those recorded resources in staging before generating the replacement draft.

When `capabilities/<id>` already exists and no valid local ownership state proves it belongs to this Workbench, the build fails with `CAPABILITY_OWNERSHIP_CONFLICT`. A malformed state or a state that disagrees with the registry also fails closed. Published releases are never treated as drafts; a published target causes the same closed failure. The new state file is written only after the staging source has passed validation and the source swap succeeds, so a failed build cannot make a later build claim ownership of uncommitted content.

Stable IDs continue to be generated from the capability identity and target/anchor identity. Replacement removes old IDs before computing orders, preventing duplicate registry entries while preserving the identity of unchanged strategies and managed markers.

## Stage 4: diagnostics, documentation, and regression coverage

CLI output distinguishes unsupported capture changes, ownership conflicts, and the first round-trip mismatch. README links developers to the guide and describes the `init → status → build` entry point; advanced commands remain documented as debugging-only.

Tests cover status reporting with unsupported path/reason, root CLI help, successful build, failed validation/round-trip with byte-identical source preservation, repeat-build replacement without stale entries, and ownership conflicts. The complete module test suite, Stage 2 validation, and `git diff --check` are required before delivery.

## Failure semantics

All temporary paths are siblings of `template-source` so an atomic move is available when the filesystem supports it. Cleanup failures never replace the original build error. If an atomic move is unavailable, the existing guarded fallback restores the backup before surfacing an I/O error. No build operation modifies the real source root before validation completes.

## Non-goals

- No database, HTTP endpoint, Runtime flag, or second registry.
- No automatic inference of migrations from file names.
- No ability to rename/move baseline files or edit non-Surface Base files.
- No local Draft override of a published Capability.
