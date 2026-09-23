# Template Engine Contract Index / Migration Boundary

The repository maintains two isolated Template Engine releases during migration.

## Legacy V2

- `POST /v1/generate` and `POST /v1/update` remain the Legacy V2 API during this stage.
- HTTP contract: `template-engine/engine-service/src/main/resources/openapi/engine-service-v1.yaml`.
- Semantic contract: the existing V2 implementation and its fixtures/tests.
- Runtime source: `template-source/base/**`, `template-source/capabilities/**`, and `template-source/strategy-registry-v2.yaml`.
- Revision: `template-source/template-revision.txt`, governed by `scripts/ci/verify-template-release-revision.sh`.
- V2 State and Update Package semantics are unchanged. Service remains stateless and never applies packages to a caller workspace.
- `capability-authoring` stages and commits only Legacy V2-owned paths. It preserves `template-source/code/**` and all other non-V2 content when compiling or publishing a V2 capability.

## Extension V3 Preview

- `POST /v1/generate-next` and `POST /v1/update-next` are the V3 Preview API. They do not change the Legacy V2 endpoints.
- HTTP contract: the V3 paths and request/error schemas in `engine-service-v1.yaml`.
- Semantic contract: [docs/v3.md](v3.md).
- V3 Update ZIP JSON contract: `template-engine/engine-service/src/main/resources/contracts/v3/*.schema.json`.
- Runtime source: `template-source/code/frontend/base/**`, `template-source/code/frontend/extensions/*/extension.yaml`, `template-source/code/frontend/extensions/*/src/**`, `template-source/code/backend/base/**`, `template-source/code/backend/extensions/*/extension.yaml`, `template-source/code/backend/extensions/*/{src,docs,migrations}/**`, and `template-source/code/template-revision.txt`.
- Revision: `template-source/code/template-revision.txt`, governed independently by `scripts/ci/verify-code-template-release-revision.sh`.
- V3 State and Package remain caller-owned. Workspace apply, package rollback, builds/tests, and State commit are external DevAgentStudio/caller responsibilities. The engine may use a test-only reference executor to verify the package contract; it must not add a production workspace executor.

## Migration boundary

V2 and V3 have separate Runtime Source, Revision, State schema, and Update Package protocols. Changes to one source tree do not implicitly require synchronizing the other. A product change that intentionally updates both must be represented and revision-gated as two independent source changes.

During this preview stage, do not route `/v1/generate` or `/v1/update` to V3, remove V2 contracts, or delete Legacy source. Formal cutover is a later change.
