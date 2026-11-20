package com.devagentstudio.template.engine.core.v3;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Collection;
import java.util.TreeMap;

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
    private static final String TREE = "<!-- DEVAGENTSTUDIO:FRONTEND_PROJECT_TREE -->";
    public static byte[] composeFrontend(byte[] base, Map<String, byte[]> orderedFragments, Collection<String> files) {
        compose(base, orderedFragments); // Retain the same strict source validation as backend.
        String value = normalize(base);
        if (count(value, TREE) != 1) throw invalid("expected one frontend tree placeholder");
        StringBuilder out = new StringBuilder(value.replace(TREE, frontendProjectTree(files)));
        for (Map.Entry<String, byte[]> item : orderedFragments.entrySet()) {
            String text = fragment(item.getKey(), item.getValue());
            String begin = "<!-- " + MARKER + item.getKey() + ":BEGIN -->\n";
            String end = "<!-- " + MARKER + item.getKey() + ":END -->\n";
            out.append('\n').append(normalize(text.substring(begin.length(), text.length() - end.length()).getBytes(StandardCharsets.UTF_8)));
        }
        if (out.indexOf("DEVAGENTSTUDIO:FRONTEND_PROJECT_TREE") >= 0) throw invalid("unexpected frontend tree placeholder");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static final class TreeNode {
        final Map<String, TreeNode> children = new TreeMap<String, TreeNode>();
    }
    static String frontendProjectTree(Collection<String> files) {
        TreeNode root = new TreeNode();
        for (String file : files) {
            if (file == null || file.matches(".*[\\\\\\x00-\\x1f\\x7f].*")) throw invalid("unsafe project path");
            for (String part : file.split("/", -1))
                if (part.isEmpty() || part.equals(".") || part.equals("..")) throw invalid("unsafe project path");
            if (!file.matches("(AGENTS\\.md|package\\.json|pnpm-lock\\.yaml|rsbuild\\.config\\.ts)")
                    && !file.matches("src/(App\\.tsx|index\\.tsx|(?:apis|pages|components|hooks|constants|typings|context|config|routes|platform|providers|layout|bootstrap|observability|utils|extensions)/.+\\.tsx?)")) continue;
            if (file.matches(".*(?:^|/)(?:__tests__|tests|assets|public)/.*") || file.matches(".*\\.(test|spec)\\.tsx?")) continue;
            TreeNode node = root;
            for (String part : file.split("/")) {
                if (!node.children.containsKey(part)) node.children.put(part, new TreeNode());
                node = node.children.get(part);
            }
        }
        StringBuilder out = new StringBuilder("```text\nfrontend/\n");
        appendTree(out, root, "");
        return out.append("```").toString();
    }
    private static void appendTree(StringBuilder out, TreeNode node, String prefix) {
        int index = 0;
        for (Map.Entry<String, TreeNode> entry : node.children.entrySet()) {
            boolean last = ++index == node.children.size();
            boolean directory = !entry.getValue().children.isEmpty();
            out.append(prefix).append(last ? "└── " : "├── ").append(entry.getKey()).append(directory ? "/" : "").append('\n');
            appendTree(out, entry.getValue(), prefix + (last ? "    " : "│   "));
        }
    }
    private static int count(String value, String token) {
        int count = 0, index = 0;
        while ((index = value.indexOf(token, index)) >= 0) { count++; index += token.length(); }
        return count;
    }
    private static RuntimeException invalid(String message) { return new V3Exception("TEMPLATE_SOURCE_INVALID", message, 500); }
}
