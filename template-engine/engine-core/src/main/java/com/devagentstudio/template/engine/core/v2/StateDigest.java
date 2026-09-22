package com.devagentstudio.template.engine.core.v2;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The protocol's compact, recursively key-sorted TemplateStateV2 digest. */
public final class StateDigest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private StateDigest() { }

    public static String of(Object value) {
        try { return sha256(JSON.writeValueAsBytes(canonical(value))); }
        catch (IOException e) { throw new IllegalStateException("cannot serialize canonical state", e); }
    }

    public static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder output = new StringBuilder("sha256:");
            for (byte value : hash) output.append(String.format("%02x", value & 0xff));
            return output.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    private static Object canonical(Object value) {
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<String, Object>();
            for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) sorted.put(String.valueOf(item.getKey()), canonical(item.getValue()));
            return sorted;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<Object>) value) result.add(canonical(item));
            return result;
        }
        return value;
    }
}
