# 1016 Login contract inventory

Baseline: `e56e7676b300d45e01093cd7697186e449046feb`.

| Surface | Observed source behavior |
| --- | --- |
| frontend/extensions/login/src/apis/login.ts | `loginApi(code)` performs GET `/api/sso/login?code=<encoded>` |
| frontend/extensions/login/src/pages/Login/index.tsx | Reads URL `code`, calls loginApi, persists returned identity and navigates to saved URL |
| frontend/base/src/platform/identity/IdentityContext.tsx | Identity fields `userId`, `userName`, optional `avatar` |
| frontend/extensions/login/src/constants/yst.ts | Enterprise authorization/logout URLs and callback routes `/login`, `/logout` |
| frontend/extensions/login/src/hooks/useGuard.ts | YHT redirection requires a client ID and production mode; development skips that redirect |
| frontend/extensions/login/src/pages/Logout/index.tsx | Imports logoutApi from apis/login and invokes it; that module in this baseline exports only loginApi (existing frontend inconsistency) |
| frontend/base/src/apis/service.ts | Success `SUC0000`, unpacks returnCode/errorMsg/body |
| Backend formal login manifest | Bee EnableAuthClient and private starter provide enterprise login integration |

No existing `/api/login` or `/api/auth/login` is assumed. Since local revision 2026.10.23.1008, the adapter overrides the frontend Login API module and Logout page: logoutApi posts to the existing `/api/standalone/logout`, then the page clears local identity and cached Authorization and returns to the local home route. Other frontend runtime files retain their upstream content. Its local test client uses explicit `/api/standalone/login`, `/me`, `/logout`, signed HttpOnly cookie, and maps userId directly to LocalToken.sapId. Since local revision 2026.10.23.1006, `GET /api/sso/login?code=...` returns the configured fixed mock Identity and a signed `standalone_identity` cookie. The non-empty code is a mock placeholder, not enterprise verification or a user selector. Optional avatar is supported by userName/avatar objects in the users map; legacy string names remain supported. The original missing logoutApi export listed in the baseline inventory is repaired by the Standalone overlay; the enterprise source is unchanged.

Since local revision 2026.10.23.1007, Mock is enabled by default in the generated local/dev backend; STANDALONE_MOCK_ENABLED=false disables it, and other profiles alone do not register it. Without Mock environment variables, the callback selects member-001 / Local Admin from the default allowlist (also containing member-002 / Local Member). Signing and POST login secrets default to independent 32-byte SecureRandom values held only in memory; no generated files or logs contain them, and default cookies expire on process restart. Explicit non-empty secrets must still contain at least 32 characters. Empty secrets use random defaults; absent/blank users use the default allowlist, while invalid non-blank user configurations fail.

POST mock login still requires the local shared secret plus an allowlist. To use it, explicitly configure STANDALONE_MOCK_LOGIN_SECRET; the default random secret is not exposed. The GET SSO compatibility callback selects STANDALONE_MOCK_SSO_USER_ID (or the first configured/default user) without per-request credentials, under the existing dev/local loopback guard. Bootstrap fixture administrator is `member-001`; use the same ID in STANDALONE_MOCK_USERS and role_member.member_id. Database configuration and real Bootstrap remain external prerequisites. YHT parameters retained for projection do not enable real YHT/SSO.
