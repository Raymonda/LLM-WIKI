package org.cn.liuwt.llmwiki.integration.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiBaseUrlNormalizerTest {

    @Test
    void shouldStripTrailingV1Suffix() {
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode",
            AiBaseUrlNormalizer.normalize("https://dashscope.aliyuncs.com/compatible-mode/v1"));
    }

    @Test
    void shouldStripTrailingV1WithSlashAndWhitespace() {
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode",
            AiBaseUrlNormalizer.normalize("  https://dashscope.aliyuncs.com/compatible-mode/v1/  "));
    }

    @Test
    void shouldStripUppercaseV1() {
        assertEquals("https://api.openai.com",
            AiBaseUrlNormalizer.normalize("https://api.openai.com/V1"));
    }

    @Test
    void shouldKeepPlainUrlUntouched() {
        assertEquals("https://api.deepseek.com",
            AiBaseUrlNormalizer.normalize("https://api.deepseek.com"));
    }

    @Test
    void shouldNotStripV1InMiddleOfPath() {
        assertEquals("https://gateway.example.com/v1/proxy",
            AiBaseUrlNormalizer.normalize("https://gateway.example.com/v1/proxy"));
    }

    @Test
    void shouldNotStripSimilarSuffixLikeV10() {
        assertEquals("https://gateway.example.com/v10",
            AiBaseUrlNormalizer.normalize("https://gateway.example.com/v10"));
    }

    @Test
    void shouldStripTrailingSlashOnly() {
        assertEquals("https://api.deepseek.com",
            AiBaseUrlNormalizer.normalize("https://api.deepseek.com/"));
    }

    @Test
    void shouldReturnNullWhenNull() {
        assertNull(AiBaseUrlNormalizer.normalize(null));
    }
}
