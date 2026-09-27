package com.github.lechandonga.idempotentsettlementledgerjava.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 哈希工具：用于分录完整性哈希、哈希链与请求指纹。
 *
 * <p>所有参与校验的字段都以确定性的字符串拼接方式喂给哈希，
 * 字段之间用 '|' 分隔，null 以空字符串表示，避免字段边界歧义。
 */
public final class LedgerHasher {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private LedgerHasher() {
    }

    /** 对多个字段的管道拼接结果计算 SHA-256，返回小写十六进制。 */
    public static String sha256Hex(String... fields) {
        String joined = String.join("|", fields);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[raw.length * 2];
            for (int i = 0; i < raw.length; i++) {
                int v = raw[i] & 0xff;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0f];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
