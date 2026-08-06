package org.cn.liuwt.llmwiki.service.query;

import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FunFactService {

    private static final Logger log = LoggerFactory.getLogger(FunFactService.class);
    private static final long TIMEOUT_SECONDS = 15;

    @Autowired
    private LlmClient chatClient;

    public CompletableFuture<List<FunFact>> generateFunFactsAsync(String question) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return generateFunFacts(question);
            } catch (Exception e) {
                log.warn("Failed to generate fun facts: {}", e.getMessage());
                return List.of();
            }
        });
    }

    private List<FunFact> generateFunFacts(String question) {
        String prompt = buildPrompt(question);
        String response = chatClient.chat(prompt);
        return parseFunFacts(response);
    }

    private String buildPrompt(String question) {
        return """
            你是一个知识科普助手。用户正在等待AI回答他们的问题，需要你生成3-5条与问题相关的趣味科普知识，帮助用户在等待时也能"开智"。

            用户问题：%s

            要求：
            1. 每条科普必须与用户问题主题相关，能拓展用户对该领域的认知
            2. 内容要有趣、令人惊讶、有启发性
            3. 语言简洁生动，每条50-100字
            4. 使用以下JSON格式输出（不要有其他内容）：

            ```json
            [
              {"icon": "lightbulb", "text": "科普内容1"},
              {"icon": "brain", "text": "科普内容2"},
              {"icon": "atom", "text": "科普内容3"}
            ]
            ```

            icon可选值：lightbulb（灯泡/灵感）、brain（大脑/认知）、atom（原子/科学）、globe（地球/世界）、sparkles（闪光/奇妙）、puzzle（拼图/思维）、book（书本/知识）、rocket（火箭/探索）

            请直接输出JSON，不要添加其他说明。
            """.formatted(question);
    }

    private static final Pattern JSON_PATTERN = Pattern.compile("\\[[\\s\\S]*?\\]");

    private List<FunFact> parseFunFacts(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }

        Matcher matcher = JSON_PATTERN.matcher(response);
        if (!matcher.find()) {
            log.warn("No JSON array found in fun facts response");
            return List.of();
        }

        String json = matcher.group();
        List<FunFact> facts = new ArrayList<>();

        try {
            var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var array = objectMapper.readTree(json);
            for (var node : array) {
                String icon = node.has("icon") ? node.get("icon").asText() : "lightbulb";
                String text = node.has("text") ? node.get("text").asText() : "";
                if (!text.isBlank()) {
                    facts.add(new FunFact(icon, text));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse fun facts JSON: {}", e.getMessage());
        }

        return facts;
    }

    public record FunFact(String icon, String text) {}
}
