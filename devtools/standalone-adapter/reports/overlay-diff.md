# Overlay operations

| Operation | Target | Upstream SHA256 | Reason |
| --- | --- | --- | --- |
| replace | `frontend/extensions/login/src/apis/login.ts` | `0211f181c01feb26e980214878362e2ac9d53cb519ede9da61307579221c7e71` | Export logoutApi using the existing Standalone cookie logout endpoint |
| replace | `frontend/extensions/login/src/pages/Logout/index.tsx` | `4f6cb79998552c06cf71da4e7762e8ddf77573307a509db3ffbac75dad1d2920` | Clear local identity after server logout and return to the local application |
| replace | `backend/base/pom.xml` | `ed0a54877a0124c850f647bab0c775098022e81318405ae37ec126c370cd81c6` | Use public Maven/MySQL locally; preserve formal source and projection contract |
| replace | `backend/base/src/main/resources/application-dev.yml` | `403b1f82efbebc9595a7ccab288548f332c316cb9ce7182906898b90daefc86b` | Use public Maven/MySQL locally; preserve formal source and projection contract |
| replace | `backend/base/src/main/resources/application.yml` | `2b71f5dee95490417c15bb8182b719d919a59ce008183e766dbc7a5a75a9a6e0` | Use public Maven/MySQL locally; preserve formal source and projection contract |
| replace | `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/bootstrap/BootstrapDataSource.java` | `5461d75d38615413d996321695014b58e45315a19d32eea1c3f50b5b0e79fd2c` | Use public Maven/MySQL locally; preserve formal source and projection contract |
| replace | `backend/extensions/login/extension.yaml` | `7be5fa4cff0593091724168c8780d7519310cc2c3c80076addffdd164ca9b7c5` | Use public Maven/MySQL locally; preserve formal source and projection contract |
| add | `backend/base/src/main/java/com/cmb/bee/commons/errorcodes/IBizErrorCode.java` | `absent (add)` | Limited compatibility API consumed by unchanged 1016 Java source |
| add | `backend/base/src/main/java/com/cmb/bee/commons/errorcodes/ISysErrorCode.java` | `absent (add)` | Limited compatibility API consumed by unchanged 1016 Java source |
| add | `backend/base/src/main/java/com/cmb/bee/commons/exceptions/BizException.java` | `absent (add)` | Limited compatibility API consumed by unchanged 1016 Java source |
| add | `backend/base/src/main/java/com/cmb/bee/commons/exceptions/SysException.java` | `absent (add)` | Limited compatibility API consumed by unchanged 1016 Java source |
| add | `backend/base/src/main/java/com/cmb/bee/web/entity/BeeResponseEntity.java` | `absent (add)` | Limited compatibility API consumed by unchanged 1016 Java source |
| patch | `backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java` | `dbb525922df2b12e15bb4f0efcf39ff70624c3f43965bc0e370f22cd4354ac7f` | Change only JDBC URL family and its error label; retain manifest v3 business validation |
| add | `backend/extensions/login/src/main/java/com/cmb/oa/one/account/oauth/client/login/context/TokenContextHolder.java` | `absent (add)` | Development-only signed identity and limited request context compatibility; unchanged RBAC enforcement |
| add | `backend/extensions/login/src/main/java/com/cmb/oa/one/account/oauth/client/login/model/LocalToken.java` | `absent (add)` | Development-only signed identity and limited request context compatibility; unchanged RBAC enforcement |
| add | `backend/extensions/login/src/main/java/com/cmbchina/backend/standalone/login/MockIdentityFilter.java` | `absent (add)` | Development-only signed identity and limited request context compatibility; unchanged RBAC enforcement |
| add | `backend/extensions/login/src/main/java/com/cmbchina/backend/standalone/login/MockIdentityService.java` | `absent (add)` | Development-only signed identity and limited request context compatibility; unchanged RBAC enforcement |
| add | `backend/extensions/login/src/main/java/com/cmbchina/backend/standalone/login/MockLoginConfiguration.java` | `absent (add)` | Development-only signed identity and limited request context compatibility; unchanged RBAC enforcement |
| add | `backend/extensions/login/src/main/java/com/cmbchina/backend/standalone/login/MockLoginController.java` | `absent (add)` | Development-only signed identity and limited request context compatibility; unchanged RBAC enforcement |
| add | `backend/base/src/main/resources/application-local.yml` | `absent (add)` | Explicit local profile for Mock login and standalone Bootstrap |
| replace | `backend/extensions/login/docs/login.md` | `0b7e9ebbecd9c281be2aae27b3430d79124e3ce8450a1178d2df4d84b8386e99` | Document the local /api/sso/login response and cookie contract without retaining enterprise-only login guidance |
