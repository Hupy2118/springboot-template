package com.devagentstudio.template.engine.core.v3;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strict application-configuration validation and projections for generated projects. */
public final class ApplicationConfigProjector {
    public static final String FRONTEND_TARGET = "frontend/.env";
    public static final String BACKEND_TARGET = "backend/src/main/resources/application-dev.yml";
    public static final String DEFAULT_END_URL = "https://one-account-gateway.paasuat.cmbchina.cn/auth-server/endsession";
    public static final String DEFAULT_LOGOUT_SHARE_JS = "https://one-account-gateway.paasuat.cmbchina.cn/logout-share/js/logoutShare.js";
    private ApplicationConfigProjector() { }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> resolve(Map<String, Object> raw, Map<String, Map<String, Object>> capabilities) {
        if (raw == null) return null;
        requireOnly(raw, "appName", "appIcon", "theme", "auth", "authorization");
        if (!raw.containsKey("appName") || !raw.containsKey("appIcon") || !raw.containsKey("theme")) bad("applicationConfig fields are invalid");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("appName", value(raw.get("appName"), "appName", 128));
        result.put("appIcon", value(raw.get("appIcon"), "appIcon", 128));
        Map<String, Object> theme = map(raw.get("theme"), "theme"); requireExactly(theme, "primaryColor");
        String color = value(theme.get("primaryColor"), "theme.primaryColor", 7);
        if (!color.matches("#[0-9A-Fa-f]{6}")) bad("theme.primaryColor must be a six-digit hex color");
        result.put("primaryColor", color);
        if (raw.containsKey("auth")) resolveAuth(map(raw.get("auth"), "auth"), capabilities, result);
        if (raw.containsKey("authorization")) resolveAuthorization(map(raw.get("authorization"), "authorization"), capabilities, result);
        return result;
    }

    private static void resolveAuth(Map<String, Object> auth, Map<String, Map<String, Object>> capabilities, Map<String, Object> result) {
        requireExactly(auth, "enable", "authnSource", "yht");
        if (!(auth.get("enable") instanceof Boolean)) bad("auth.enable must be boolean");
        String source = value(auth.get("authnSource"), "auth.authnSource", 32);
        if (!Boolean.TRUE.equals(auth.get("enable"))) {
            Map<String, Object> login = capabilities.get("login");
            if (login != null && Boolean.TRUE.equals(login.get("enabled"))) bad("enabled login conflicts with auth.enable=false");
            return;
        }
        if (!"yst".equals(source)) return;
        Map<String, Object> login = capabilities.get("login");
        if (login == null || !Boolean.TRUE.equals(login.get("enabled"))) bad("YHT auth requires enabled login capability");
        Map<String, Object> yht = map(auth.get("yht"), "auth.yht");
        requireOnly(yht, "clientId", "privateKey", "publicKey", "centerPublicKey", "centerUri", "authorizeUri", "redirectUri", "endUrl", "logoutShareJs");
        for (String key : new String[] { "clientId", "privateKey", "publicKey", "centerPublicKey" }) if (!yht.containsKey(key)) bad("auth.yht." + key + " is required");
        result.put("yhtClientId", value(yht.get("clientId"), "auth.yht.clientId", 256));
        result.put("privateKey", value(yht.get("privateKey"), "auth.yht.privateKey", 16384));
        result.put("publicKey", value(yht.get("publicKey"), "auth.yht.publicKey", 16384));
        result.put("centerPublicKey", value(yht.get("centerPublicKey"), "auth.yht.centerPublicKey", 16384));
        optional(yht, result, "centerUri", "auth.yht.centerUri", 2048);
        optional(yht, result, "authorizeUri", "auth.yht.authorizeUri", 2048);
        optional(yht, result, "redirectUri", "auth.yht.redirectUri", 2048);
        optional(yht, result, "endUrl", "auth.yht.endUrl", 2048);
        optional(yht, result, "logoutShareJs", "auth.yht.logoutShareJs", 2048);
    }

    private static void resolveAuthorization(Map<String, Object> authorization, Map<String, Map<String, Object>> capabilities, Map<String, Object> result) {
        Map<String, Object> enabled = capabilities.get("authorization");
        if (enabled == null || !Boolean.TRUE.equals(enabled.get("enabled"))) bad("authorization config requires enabled authorization capability");
        requireExactly(authorization, "api");
        Map<String, Object> api = map(authorization.get("api"), "authorization.api");
        requireOnly(api, "baseUrl", "viewId");
        optional(api, result, "baseUrl", "authorization.api.baseUrl", 2048);
        optional(api, result, "viewId", "authorization.api.viewId", 512);
    }

    private static void optional(Map<String, Object> source, Map<String, Object> target, String key, String label, int max) {
        if (source.containsKey(key)) target.put(key, value(source.get(key), label, max));
    }

    /** Applies only explicitly supplied leaf values; extension manifests own the default structure. */
    public static void applyToProject(Map<String, byte[]> files, Map<String, Object> config) {
        if (config == null) return;
        Map<String, Object> dev = yamlMap(files, BACKEND_TARGET);
        StringBuilder env = new StringBuilder("# BEGIN DEVAGENTSTUDIO MANAGED: app-frontend\n")
                .append("PUBLIC_APP_NAME=").append(dotenv((String) config.get("appName"))).append("\n")
                .append("PUBLIC_APP_ICON=").append(dotenv((String) config.get("appIcon"))).append("\n")
                .append("PUBLIC_THEME_PRIMARY_COLOR=").append(dotenv((String) config.get("primaryColor"))).append("\n");
        if (config.containsKey("yhtClientId")) {
            putScalar(dev, "yht.authentication.client-id", config.get("yhtClientId"));
            putScalar(dev, "yht.authentication.private-key", config.get("privateKey"));
            putScalar(dev, "yht.authentication.public-key", config.get("publicKey"));
            putScalar(dev, "yht.authentication.center-public-key", config.get("centerPublicKey"));
            override(dev, "yht.authentication.center-uri", config, "centerUri");
            override(dev, "yht.authentication.authorize-uri", config, "authorizeUri");
            override(dev, "yht.authentication.redirect-uri", config, "redirectUri");
            env.append("PUBLIC_YHT_CLIENT_ID=").append(dotenv((String) config.get("yhtClientId"))).append("\n")
                    .append("PUBLIC_YHT_AUTHORIZE_URI=").append(dotenv(stringAt(dev, "yht.authentication.authorize-uri"))).append("\n")
                    .append("PUBLIC_YHT_END_URL=").append(dotenv(config.containsKey("endUrl") ? (String) config.get("endUrl") : DEFAULT_END_URL)).append("\n")
                    .append("PUBLIC_YHT_LOGOUT_SHARE_JS=").append(dotenv(config.containsKey("logoutShareJs") ? (String) config.get("logoutShareJs") : DEFAULT_LOGOUT_SHARE_JS)).append("\n");
        }
        if (config.containsKey("baseUrl")) putScalar(dev, "yht.api.base-url", config.get("baseUrl"));
        if (config.containsKey("viewId")) putScalar(dev, "yht.api.view-id", config.get("viewId"));
        env.append("# END DEVAGENTSTUDIO MANAGED: app-frontend\n");
        files.put(FRONTEND_TARGET, env.toString().getBytes(StandardCharsets.UTF_8));
        if (config.containsKey("yhtClientId") || config.containsKey("baseUrl") || config.containsKey("viewId")) files.put(BACKEND_TARGET, dump(dev));
    }

    /** Default-only Login env fragment for V4 capability installation. */
    public static byte[] loginEnvDefaults(Map<String, Object> dev) {
        String value = "# BEGIN DEVAGENTSTUDIO MANAGED: login-frontend\n"
                + "PUBLIC_YHT_CLIENT_ID=" + dotenv(stringAt(dev, "yht.authentication.client-id")) + "\n"
                + "PUBLIC_YHT_AUTHORIZE_URI=" + dotenv(stringAt(dev, "yht.authentication.authorize-uri")) + "\n"
                + "PUBLIC_YHT_END_URL=" + dotenv(DEFAULT_END_URL) + "\n"
                + "PUBLIC_YHT_LOGOUT_SHARE_JS=" + dotenv(DEFAULT_LOGOUT_SHARE_JS) + "\n"
                + "# END DEVAGENTSTUDIO MANAGED: login-frontend\n";
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked") public static Map<String, Object> yamlMap(Map<String, byte[]> files, String target) {
        byte[] bytes = files.get(target); if (bytes == null) throw conflict("backend YAML target is required: " + target);
        Object value = new Yaml().load(new String(bytes, StandardCharsets.UTF_8));
        if (!(value instanceof Map)) throw conflict("backend YAML root must be an object: " + target);
        return (Map<String, Object>) value;
    }
    public static byte[] dump(Map<String, Object> value) { DumperOptions options = new DumperOptions(); options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); options.setPrettyFlow(false); return new Yaml(options).dump(value).getBytes(StandardCharsets.UTF_8); }
    @SuppressWarnings("unchecked") private static void putScalar(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\."); Map<String, Object> parent = root;
        for (int i = 0; i < parts.length - 1; i++) { Object next = parent.get(parts[i]); if (!(next instanceof Map)) throw conflict("application YAML has no declared map path: " + path); parent = (Map<String, Object>) next; }
        Object prior = parent.get(parts[parts.length - 1]); if (prior == null || prior instanceof Map || prior instanceof java.util.List) throw conflict("application YAML has no declared scalar path: " + path);
        parent.put(parts[parts.length - 1], value);
    }
    private static void override(Map<String, Object> dev, String path, Map<String, Object> config, String key) { if (config.containsKey(key)) putScalar(dev, path, config.get(key)); }
    @SuppressWarnings("unchecked") private static String stringAt(Map<String, Object> root, String path) { Object value = root; for (String part : path.split("\\.")) { if (!(value instanceof Map)) throw conflict("application YAML has no declared scalar path: " + path); value = ((Map<String, Object>) value).get(part); } if (!(value instanceof String) || ((String) value).isEmpty()) throw conflict("application YAML has no declared scalar path: " + path); return (String) value; }
    private static String dotenv(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
    private static void requireExactly(Map<String, Object> map, String... keys) { if (map.size() != keys.length) bad("applicationConfig fields are invalid"); for (String key : keys) if (!map.containsKey(key)) bad("applicationConfig fields are invalid"); }
    private static void requireOnly(Map<String, Object> map, String... keys) { java.util.HashSet<String> allowed = new java.util.HashSet<String>(java.util.Arrays.asList(keys)); if (!allowed.containsAll(map.keySet())) bad("applicationConfig fields are invalid"); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value, String label) { if (!(value instanceof Map)) bad(label + " must be an object"); return (Map<String, Object>) value; }
    private static String value(Object value, String label, int max) { if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > max) bad(label + " is invalid"); String text = (String) value; for (int i = 0; i < text.length(); i++) { char ch = text.charAt(i); if (ch < 0x20 || ch == 0x7f || ch == 0x85 || ch == 0x2028 || ch == 0x2029) bad(label + " is invalid"); } return text; }
    private static V3Exception conflict(String message) { return new V3Exception("CONFIG_TARGET_CONFLICT", message, 409); }
    private static void bad(String message) { throw new V3Exception("BAD_REQUEST", message, 400); }
}
