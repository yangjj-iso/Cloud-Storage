package com.cloudchunk.core.auth.service;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 口令哈希器。
 *
 * <p>新口令使用 <b>Argon2id</b>（抗 GPU/ASIC 暴力破解的内存硬函数，OWASP 首选），
 * 输出自描述的 PHC 字符串 {@code $argon2id$v=19$m=...,t=...,p=...$salt$hash}，校验时可从串内解析参数。</p>
 *
 * <p>为平滑迁移，仍<b>兼容校验旧的 PBKDF2 哈希</b>（{@code pbkdf2$iter$salt$hash}）。旧格式在下一次
 * 成功登录时由 {@code AuthService} 透明重算为 Argon2id（见 {@link #needsUpgrade}）。</p>
 */
@Component
public class PasswordHasher {

    // Argon2id 参数：saltLen=16B, hashLen=32B, parallelism=1, memory=19456KiB(≈19MiB), iterations=2（OWASP 建议档）
    private final Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

    // --- 旧 PBKDF2（仅用于校验历史哈希） ---
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";

    public String hash(String rawPassword) {
        return argon2.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encoded) {
        if (rawPassword == null || encoded == null || encoded.isBlank()) {
            return false;
        }
        if (encoded.startsWith("$argon2")) {
            return argon2.matches(rawPassword, encoded);
        }
        // 历史遗留：旧 PBKDF2 哈希
        return matchesPbkdf2(rawPassword, encoded);
    }

    /**
     * 该哈希是否需要升级（非 Argon2id 即视为待升级）。调用方应在校验通过后用新算法重算并落库。
     */
    public boolean needsUpgrade(String encoded) {
        return encoded == null || !encoded.startsWith("$argon2");
    }

    private boolean matchesPbkdf2(String rawPassword, String encoded) {
        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyBits);
            return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("password hash failed", e);
        }
    }
}
