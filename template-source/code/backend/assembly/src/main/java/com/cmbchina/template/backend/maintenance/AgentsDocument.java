package com.cmbchina.template.backend.maintenance;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Minimal UTF-8/LF document composition; callers supply resolver dependency order. */
public final class AgentsDocument {
    private static final String MARKER = "DEVAGENTSTUDIO:CAPABILITY:";
    private AgentsDocument() { }
    public static String normalize(byte[] bytes) {
        if (bytes == null) throw invalid("missing AGENTS source");
        String value;
        try { value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (Exception e) { throw invalid("AGENTS source must be UTF-8"); }
        value = value.replace("\r\n", "\n").replace('\r', '\n');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == 0x7f || ch == 0xfeff || ch < 0x20 && ch != '\n' && ch != '\t')
                throw invalid("illegal AGENTS character");
        }
        value = value.replaceFirst("[ \t\n]+$", "");
        if (value.isEmpty()) throw invalid("empty AGENTS source");
        return value + "\n";
    }
    public static String fragment(String id, byte[] bytes) {
        if (!id.matches("[a-z0-9][a-z0-9_-]*")) throw invalid("invalid capability id: " + id);
        String value = normalize(bytes);
        String begin = "<!-- " + MARKER + id + ":BEGIN -->\n";
        String end = "<!-- " + MARKER + id + ":END -->\n";
        if (!value.startsWith(begin) || !value.endsWith(end) || count(value, MARKER) != 2
                || !value.substring(begin.length(), value.length() - end.length()).startsWith("## "))
            throw invalid("incomplete or duplicate AGENTS markers: " + id);
        return value;
    }
    public static byte[] compose(byte[] base, Map<String, byte[]> orderedFragments) {
        StringBuilder out = new StringBuilder(normalize(base));
        if (out.indexOf(MARKER) >= 0) throw invalid("Base AGENTS may not contain capability markers");
        for (Map.Entry<String, byte[]> item : orderedFragments.entrySet())
            out.append('\n').append(fragment(item.getKey(), item.getValue()));
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static int count(String value, String token) {
        int count = 0, index = 0;
        while ((index = value.indexOf(token, index)) >= 0) { count++; index += token.length(); }
        return count;
    }
    private static RuntimeException invalid(String message) { return new TemplateException("TEMPLATE_SOURCE_INVALID", message); }
}
