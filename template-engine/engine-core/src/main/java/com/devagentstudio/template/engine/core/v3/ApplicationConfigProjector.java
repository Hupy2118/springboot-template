package com.devagentstudio.template.engine.core.v3;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validates the application-level request and owns its two runtime projections. */
public final class ApplicationConfigProjector {
    public static final String FRONTEND_TARGET = "frontend/.env";
    public static final String BACKEND_TARGET = "backend/src/main/resources/application.yml";
    private ApplicationConfigProjector() { }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> resolve(Map<String, Object> raw, Map<String, Map<String, Object>> capabilities) {
        if (raw == null) return null;
        if (!raw.containsKey("appName") || !raw.containsKey("appIcon") || !raw.containsKey("theme") || raw.size() > 4) bad("applicationConfig fields are invalid");
        String name = value(raw.get("appName"), "appName", 128);
        String icon = value(raw.get("appIcon"), "appIcon", 128);
        Map<String, Object> theme = map(raw.get("theme"), "theme"); requireKeys(theme, "primaryColor");
        String color = value(theme.get("primaryColor"), "theme.primaryColor", 7);
        if (!color.matches("#[0-9A-Fa-f]{6}")) bad("theme.primaryColor must be a six-digit hex color");
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("appName", name); result.put("appIcon", icon); result.put("primaryColor", color);
        Object authRaw = raw.get("auth");
        if (authRaw == null) return result;
        Map<String, Object> auth = map(authRaw, "auth"); requireKeys(auth, "enable", "authnSource", "yht");
        if (!(auth.get("enable") instanceof Boolean)) bad("auth.enable must be boolean");
        String source = value(auth.get("authnSource"), "auth.authnSource", 32);
        if (!Boolean.TRUE.equals(auth.get("enable"))) {
            Map<String, Object> login = capabilities.get("login");
            if (login != null && Boolean.TRUE.equals(login.get("enabled"))) bad("enabled login conflicts with auth.enable=false");
            return result;
        }
        if (!"yst".equals(source)) return result;
        Map<String, Object> login = capabilities.get("login");
        if (login == null || !Boolean.TRUE.equals(login.get("enabled"))) bad("YHT auth requires enabled login capability");
        Map<String, Object> yht = map(auth.get("yht"), "auth.yht"); requireKeys(yht, "clientId", "privateKey", "publicKey", "centerPublicKey");
        String clientId = value(yht.get("clientId"), "auth.yht.clientId", 256);
        String publicKey = value(yht.get("publicKey"), "auth.yht.publicKey", 16384);
        String centerPublicKey = value(yht.get("centerPublicKey"), "auth.yht.centerPublicKey", 16384);
        String privateKey = value(yht.get("privateKey"), "auth.yht.privateKey", 16384);
        result.put("yhtClientId", clientId); result.put("privateKey", privateKey); result.put("publicKey", publicKey); result.put("centerPublicKey", centerPublicKey);
        return result;
    }
    public static Map<String, byte[]> project(Map<String, byte[]> files, Map<String, Object> config) {
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>(); if (config == null) return result;
        String env = "# BEGIN DEVAGENTSTUDIO MANAGED: app-frontend\nPUBLIC_APP_NAME=" + dotenv((String) config.get("appName")) + "\nPUBLIC_APP_ICON=" + dotenv((String) config.get("appIcon")) + "\nPUBLIC_THEME_PRIMARY_COLOR=" + dotenv((String) config.get("primaryColor")) + "\n";
        if (config.containsKey("yhtClientId")) env += "PUBLIC_YHT_CLIENT_ID=" + dotenv((String) config.get("yhtClientId")) + "\n";
        env += "# END DEVAGENTSTUDIO MANAGED: app-frontend\n"; result.put(FRONTEND_TARGET, env.getBytes(StandardCharsets.UTF_8));
        if (config.containsKey("yhtClientId")) {
            String yaml = "# BEGIN DEVAGENTSTUDIO MANAGED: login-auth\nauth:\n  client-id: " + yaml((String) config.get("yhtClientId")) + "\n  private-key: " + yaml((String) config.get("privateKey")) + "\n  public-key: " + yaml((String) config.get("publicKey")) + "\n  center-public-key: " + yaml((String) config.get("centerPublicKey")) + "\n# END DEVAGENTSTUDIO MANAGED: login-auth\n";
            result.put(BACKEND_TARGET, yaml.getBytes(StandardCharsets.UTF_8));
        } return result;
    }
    public static void applyToProject(Map<String, byte[]> files, Map<String, Object> config) {
        for (Map.Entry<String, byte[]> item : project(files, config).entrySet()) {
            if (FRONTEND_TARGET.equals(item.getKey())) files.put(item.getKey(), item.getValue());
            else { String prior = new String(files.get(item.getKey()), StandardCharsets.UTF_8); if (prior.matches("(?sm).*^auth:.*")) throw new V3Exception("CONFIG_TARGET_CONFLICT", "application.yml already has a non-managed auth root", 409); files.put(item.getKey(), (prior + "\n" + new String(item.getValue(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)); }
        }
    }
    private static String dotenv(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
    private static String yaml(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
    private static void requireKeys(Map<String,Object> map, String... keys) { if (map.size()!=keys.length) bad("applicationConfig fields are invalid"); for(String key:keys) if(!map.containsKey(key)) bad("applicationConfig fields are invalid"); }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value,String label) { if(!(value instanceof Map)) bad(label + " must be an object"); return (Map<String,Object>) value; }
    private static String value(Object value,String label,int max) { if(!(value instanceof String) || ((String)value).isEmpty() || ((String)value).length()>max || ((String)value).contains("\n") || ((String)value).contains("\r")) bad(label + " is invalid"); return (String)value; }
    private static void bad(String message) { throw new V3Exception("BAD_REQUEST", message, 400); }
}
