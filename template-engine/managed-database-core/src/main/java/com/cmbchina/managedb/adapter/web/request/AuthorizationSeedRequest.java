package com.cmbchina.managedb.adapter.web.request;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.AuthorizationSeedPayload;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 单入口严格解析，拒绝重复属性、隐式转换、尾随内容及超大请求。 */
@Getter
@RequiredArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class AuthorizationSeedRequest {
    private final String idempotencyKey;
    private final AuthorizationSeedPayload payload;

    /** 先限制原始字节和嵌套层级，再构造领域载荷。 */
    public static AuthorizationSeedRequest parse(String key, InputStream input, ObjectMapper mapper) {
        JsonNode root;
        try {
            String json = readUtf8(input);
            requireDepth(json, mapper);
            root = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
        } catch (IOException exception) {
            throw invalid();
        }
        requireFields(root, "purpose", "idempotencyKey", "tables");
        String idempotencyKey = AuthorizationSeedPayload.requireIdempotencyKey(text(root.get("idempotencyKey")));
        JsonNode tables = root.get("tables");
        if (!tables.isArray() || tables.size() != AuthorizationSeedPayload.Table.values().length) {
            throw invalid();
        }
        List<String> names = new ArrayList<>();
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (AuthorizationSeedPayload.Table table : AuthorizationSeedPayload.Table.values()) {
            JsonNode entry = tables.get(table.ordinal());
            requireFields(entry, "tableName", "rows");
            names.add(text(entry.get("tableName")));
            rows.add(readRows(entry.get("rows"), table));
        }
        return new AuthorizationSeedRequest(idempotencyKey,
                new AuthorizationSeedPayload(key, text(root.get("purpose")), names, rows));
    }

    /** 即使请求以 chunked 传输，也最多读上限再加一个字节。 */
    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int limit = AuthorizationSeedPayload.MAX_BYTES;
        while (output.size() <= limit) {
            int count = input.read(buffer, 0, Math.min(buffer.length, limit + 1 - output.size()));
            if (count == -1) {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(output.toByteArray())).toString();
            }
            output.write(buffer, 0, count);
        }
        throw new BizException(ResourceErrorCode.SEED_REQUEST_TOO_LARGE);
    }

    /** 流式检查固定的五层结构，防止构造深度异常的 JSON 树。 */
    private static void requireDepth(String json, ObjectMapper mapper) throws IOException {
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            int depth = 0;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token.isStructStart() && ++depth > 5) {
                    throw invalid();
                }
                if (token.isStructEnd()) {
                    depth--;
                }
            }
        }
    }

    /** 按白名单读取行，类型和值的最终规则由领域载荷统一执行。 */
    private static List<Map<String, Object>> readRows(JsonNode rows, AuthorizationSeedPayload.Table table) {
        if (!rows.isArray() || rows.size() < 1 || rows.size() > table.getMaxRows()) {
            throw invalid();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode row : rows) {
            requireFields(row, table.getColumns().keySet().toArray(new String[0]));
            Map<String, Object> values = new LinkedHashMap<>();
            table.getColumns().forEach((name, size) -> {
                JsonNode value = row.get(name);
                if (size == 0) {
                    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                        throw invalid();
                    }
                    values.put(name, value.intValue());
                } else {
                    values.put(name, text(value));
                }
            });
            result.add(values);
        }
        return result;
    }

    /** 校验对象包含且仅包含给定属性。 */
    private static void requireFields(JsonNode object, String... fields) {
        if (object == null || !object.isObject() || object.size() != fields.length
                || !Arrays.stream(fields).allMatch(object::has)) {
            throw invalid();
        }
    }

    /** 仅接受 JSON 字符串。 */
    private static String text(JsonNode value) {
        if (!value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    /** 拒绝原始字段内容进入日志和 HTTP 错误。 */
    private static BizException invalid() {
        return new BizException(ResourceErrorCode.INVALID_ARGUMENT);
    }
}
