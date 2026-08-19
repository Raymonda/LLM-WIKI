package org.cn.liuwt.llmwiki.domain.service.harness.governance.bootstrap;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaManager;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaSkeletonValidator;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Schema 冷启动初版的 LLM 润色执行器。
 *
 * 冷启动 finalize 落库的是确定性渲染版本，本服务在后台对其润色并追加为新版本。
 * 初版结构化模型已随 saveSchema 落库（schema_config.config_value_structured），
 * 因此本服务只依赖 scopeId 即可恢复全部上下文，可被任意调用方（本地线程池 /
 * RocketMQ 消费者）幂等执行。润色失败抛出异常交由调用方决定重试策略，
 * 确定性版本始终有效，不影响冷启动完成。
 */
@Service
public class SchemaPolishService {

    private static final Logger log = LoggerFactory.getLogger(SchemaPolishService.class);

    public static final String INITIAL_BOOTSTRAP_DESCRIPTION = "冷启动生成的初版 Schema";
    public static final String POLISH_DESCRIPTION = "冷启动初版 LLM 润色";

    private static final long POLISH_TIMEOUT_MS = 30_000L;
    private static final int POLISH_MAX_ATTEMPTS = 1;

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private SchemaSkeletonValidator skeletonValidator;

    @Autowired
    private SchemaJsonSynthesizer schemaJsonSynthesizer;

    public void polishSchema(Long scopeId) {
        if (chatClient == null || !chatClient.isAvailable()) {
            return;
        }
        SchemaConfigDO schema = schemaManager.getSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        if (schema == null || !INITIAL_BOOTSTRAP_DESCRIPTION.equals(schema.getDescription())) {
            return;
        }
        SchemaStructuredModel model = schemaManager.getStructuredModel(scopeId);
        if (model == null) {
            return;
        }
        List<String> capabilityIds = model.getActiveCapabilities();

        TokenUsageContext.set(scopeId, "schema");
        try {
            SchemaJsonSynthesizer.SynthesisResult synthResult =
                schemaJsonSynthesizer.synthesizeFromStructured(
                    model, capabilityIds, POLISH_TIMEOUT_MS, POLISH_MAX_ATTEMPTS);
            String polished = synthResult.markdown;
            if (polished == null || polished.isBlank()) {
                return;
            }
            SchemaSkeletonValidator.ValidationResult validation = skeletonValidator.validate(
                SchemaSkeletonValidator.WIKI_SCHEMA_KEY, polished
            );
            if (!validation.isValid()) {
                log.warn("Polished schema failed skeleton validation, keeping deterministic version: scopeId={}, reason={}",
                    scopeId, validation.getMessage());
                return;
            }
            schemaManager.saveSchema(
                scopeId,
                SchemaSkeletonValidator.WIKI_SCHEMA_KEY,
                polished,
                synthResult.json,
                "wiki",
                POLISH_DESCRIPTION,
                SchemaManager.SOURCE_BOOTSTRAP,
                null,
                scopeId
            );
            log.info("Schema bootstrap polish applied: scopeId={}", scopeId);
        } finally {
            TokenUsageContext.clear();
        }
    }
}
