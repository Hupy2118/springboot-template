#!/usr/bin/env python3
"""Reject accidental removal of the small XCodeAgent workflow surface.

This is intentionally an anchor check, not a file-content lock: protected code can
still be improved as long as its public workflow entry points remain available.
"""

import argparse
import pathlib


# Keep this list deliberately narrow.  Each entry is a workflow contract or the
# integration point that makes one usable; ordinary pages and business code are
# intentionally absent.
PROTECTED_SURFACES = (
    ("template-source/code/contracts/route-projector.json", (
        '"schemaVersion": "route-projector-contract.v2"',
        '"protocol": "route-projector.v2"',
        '"frontend/scripts/devagentstudio/route-projector.mjs"',
    )),
    ("template-source/code/contracts/route-projector-input.schema.json", (
        '"title": "Route Projector v2 input"', '"pageId"', '"resourceKey"',
    )),
    ("template-source/code/contracts/route-projector-output.schema.json", (
        '"title": "Route Projector v2 output"', '"appliedPageIds"', '"skippedPageIds"',
    )),
    ("template-source/code/frontend/base/scripts/devagentstudio/route-projector.mjs", (
        "const PROTOCOL = 'route-projector.v2'", 'export function parseRouteProjectorInput',
        'export function reconcileBusinessRoutes', 'export function writeRoutesAtomically',
        'DEVAGENTSTUDIO_BUSINESS_ROUTES_START', 'DEVAGENTSTUDIO_BUSINESS_ROUTES_END',
    )),
    ("template-source/code/frontend/base/devtools/elementInspector.ts", (
        "const INSPECTOR_CHANNEL = 'devagentstudio:element-inspector'",
        'export function installElementInspector', "type: 'element-selected'", "type: 'ready'",
    )),
    ("template-source/code/frontend/base/src/index.tsx", (
        "import('../devtools/elementInspector')", 'installElementInspector()', 'runAppInitializers',
    )),
    ("template-source/code/frontend/base/src/utils/pageIdentity.ts", (
        'export function pageDirectoryFromPath', 'export function pageRouteSegmentFromPath',
        'PAGE_ID_PATTERN',
    )),
    ("template-source/code/frontend/base/src/utils/pageIdentity.vectors.json", (
        '"pageDirectory"', '"routeSegment"', '"invalid"',
    )),
    ("template-source/code/frontend/base/src/constants/routes.tsx", (
        'export const PAGE_ROUTES', 'DEVAGENTSTUDIO_BUSINESS_ROUTES_START',
        'DEVAGENTSTUDIO_BUSINESS_ROUTES_END',
    )),
    ("template-source/code/frontend/base/src/bootstrap/appInitializers.ts", (
        "@/extensions/initializers", 'export async function runAppInitializers', 'extensionInitializers',
    )),
    ("template-source/code/frontend/extensions/authorization/extension.yaml", (
        '"id": "authorization"', '"authorization.provider"', '"authorization.management"',
        '"system_authorization_management"',
    )),
    ("template-source/code/backend/extensions/authorization/extension.yaml", (
        'id: authorization', 'applicationAnnotations:',
    )),
    ("template-source/code/backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java", (
        'public final class AuthorizationBootstrapCommand', 'public static void main(String[] args)',
        'loadBootstrapInput', 'authorization-manifest.v2', 'xcode_authorization_bootstrap',
    )),
    ("template-engine/engine-core/src/main/java/com/devagentstudio/template/engine/core/v3/V3ProjectMaterializer.java", (
        'REGISTRY_TARGETS', 'public Map<String, Object> templateState',
        'extensionInitializers', 'managedContributions',
    )),
    ("template-engine/engine-core/src/main/java/com/devagentstudio/template/engine/source/v3/CodeTemplateReleaseLoader.java", (
        'src/extensions/initializers.ts', 'Base may not own generated registry',
        'extension may not own generated registry',
    )),
    ("template-engine/engine-service/src/main/java/com/devagentstudio/template/engine/service/EngineMapper.java", (
        'static Map<String, Object> templateState', 'TEMPLATE_STATE_SCHEMA_UNSUPPORTED',
        'TEMPLATE_STATE_INVALID',
    )),
    ("template-engine/engine-service/src/main/resources/contracts/v3/template-state.schema.json", (
        '"title": "TemplateStateV3"', '"schemaVersion": { "const": 3 }',
        '"managedContributions"', '"installedArtifacts"',
    )),
)


def find_violations(root):
    """Return readable violations instead of locking protected file contents."""
    violations = []
    for relative, anchors in PROTECTED_SURFACES:
        path = root / relative
        if path.is_symlink() or not path.is_file():
            violations.append("missing or unsafe protected file: %s" % relative)
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            violations.append("cannot read protected file %s: %s" % (relative, exc))
            continue
        for anchor in anchors:
            if anchor not in content:
                violations.append("missing protected anchor in %s: %r" % (relative, anchor))
    return violations


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path,
                        help="repository root; defaults to this script's repository")
    args = parser.parse_args()
    root = (args.root or pathlib.Path(__file__).resolve().parents[2]).resolve()
    violations = find_violations(root)
    if violations:
        raise SystemExit("Protected XCodeAgent workflow surface check failed:\n- " + "\n- ".join(violations))
    print("Protected XCodeAgent workflow surfaces are present.")


if __name__ == "__main__":
    main()
