package org.cn.liuwt.llmwiki.common.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigCryptoTest {

    private final ConfigCrypto crypto = new ConfigCrypto("test-secret");

    @Test
    void shouldRoundTripWhenEncryptThenDecrypt() {
        String cipher = crypto.encrypt("sk-abc123");
        assertTrue(cipher.startsWith("enc:v1:"));
        assertNotEquals("sk-abc123", cipher);
        assertEquals("sk-abc123", crypto.decrypt(cipher));
    }

    @Test
    void shouldReturnPlainWhenDecryptingLegacyPlaintext() {
        assertEquals("sk-plain", crypto.decrypt("sk-plain"));
        assertFalse(crypto.isEncrypted("sk-plain"));
    }

    @Test
    void shouldPassThroughWhenInputNullOrBlank() {
        assertNull(crypto.encrypt(null));
        assertEquals("", crypto.encrypt(""));
    }

    @Test
    void shouldProduceDifferentCiphertextWhenEncryptingSamePlaintextTwice() {
        assertNotEquals(crypto.encrypt("sk-x"), crypto.encrypt("sk-x"));
    }
}
