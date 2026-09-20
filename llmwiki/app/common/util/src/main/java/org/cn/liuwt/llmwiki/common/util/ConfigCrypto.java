package org.cn.liuwt.llmwiki.common.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class ConfigCrypto {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ConfigCrypto(String secret) {
        try {
            byte[] raw = MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(raw, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("ConfigCrypto init failed", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + encrypted.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(encrypted, 0, out, IV_LEN, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Config encrypt failed", e);
        }
    }

    public String decrypt(String stored) {
        if (!isEncrypted(stored)) return stored;
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(all, IV_LEN, all.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Config decrypt failed", e);
        }
    }

    public boolean isEncrypted(String v) {
        return v != null && v.startsWith(PREFIX);
    }
}
