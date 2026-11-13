package com.cmbchina.managedb.infrastructure.identity;

import com.cmbchina.managedb.domain.DatasourceKeyGenerator;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * 使用时间戳与新随机串的 SHA-256 摘要生成 d 前缀加十一位十六进制编码，
 * 总长度保持十二位不变，前缀保证首字符为字母，避免下游标识符以数字开头引发的反引号问题。
 */
public final class HashDatasourceKeyGenerator implements DatasourceKeyGenerator {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final String PREFIX = "d";
    private final SecureRandom random = new SecureRandom();

    /**
     * 摘要对象按调用创建，避免并发共享非线程安全状态。
     */
    @Override
    public DatasourceKey generate() {
        byte[] entropy = new byte[16];
        random.nextBytes(entropy);
        String input = System.currentTimeMillis() + ":" + hex(entropy);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return new DatasourceKey(PREFIX + hex(digest).substring(0, 11));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256");
        }
    }

    /**
     * 保留前导零，按字节生成完整小写十六进制摘要。
     */
    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            chars[index * 2] = HEX[(bytes[index] & 0xff) >>> 4];
            chars[index * 2 + 1] = HEX[bytes[index] & 0x0f];
        }
        return new String(chars);
    }
}
