package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaPatchDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SchemaPatchMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaPatchModel;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaSkeletonValidator;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptRegistry;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema 补丁提案器。对应 AGENTS.md《Schema 共治宪法·规则 3》：
 * Ingest/Query/Lint 流程末尾可调用本服务，由 AI 对比"当前 Schema"与"本次知识产出"，产出候选补丁落库。
 * 不直接改 schema_config；用户审批后由 {@link SchemaPatchService#accept} 应用。
 */
@Service
public class SchemaPatchProposer {

    private static final Logger log = LoggerFactory.getLogger(SchemaPatchProposer.class);

    private static final Set<String> ALLOWED_SECTIONS = Set.of(
        "## 1. 领域定位",
        "## 2. 分类体系",
        "## 3. 页面模板",
        "## 4. 命名与引用约定",
        "## 5. 摄入工作流",
        "## 6. 健康检查规则"
    );

    private static final Set<String> ALLOWED_OPS = Set.of("ADD", "MODIFY", "DELETE");

    /** 置信度阈值：>= 该值且 evidence >= 3 条，入 PENDING（活动中心红点）；否则入 OBSERVING（观察期，不打扰用户）。 */
    private static final BigDecimal CONFIDENCE_THRESHOLD = new BigDecimal("0.70");
    private static final int EVIDENCE_THRESHOLD = 3;

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private SchemaInjector schemaInjector;

    @Autowired
    private SchemaPatchMapper schemaPatchMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 根据本次产出调用 AI 生成候选补丁并落库。调用方（PipelineOrchestrator）在 Ingest 末尾调用本方法。
     *
     * @param scopeId            数据隔离范围
     * @param executionId        触发本次提案的 execution
     * @param sourceType         SchemaPatchModel.SourceType 之一
     * @param knowledgeSummary   本次知识产出摘要（title/category/tags/新页面列表等，格式自由）
     * @return 已入库的 PENDING 补丁数量
     */
    public int propose(Long scopeId, Long executionId, SchemaPatchModel.SourceType sourceType, String knowledgeSummary) {
        if (scopeId == null || sourceType == null) {
            return 0;
        }
        TokenUsageContext.set(scopeId, "schema");
        try {
            return doPropose(scopeId, executionId, sourceType, knowledgeSummary);
        } finally {
            TokenUsageContext.clear();
        }
    }

    private int doPropose(Long scopeId, Long executionId, SchemaPatchModel.SourceType sourceType, String knowledgeSummary) {
        if (chatClient == null || !chatClient.isAvailable()) {
            log.debug("AI 不可用，跳过 Schema 补丁提案 scope={} exec={}", scopeId, executionId);
            return 0;
        }

        SchemaConfigDO schema = schemaManager.getSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        if (schema == null || schema.getConfigValue() == null || schema.getConfigValue().isBlank()) {
            log.warn("Schema 尚未冷启动，跳过补丁提案 scope={}", scopeId);
            return 0;
        }

        String raw;
        try {
            String system = schemaInjector.prepend(scopeId, PromptRegistry.forSchemaPatch().proposerSystemPrompt());
            String user = PromptRegistry.forSchemaPatch().proposerUserPrompt(schema.getConfigValue(), knowledgeSummary);
            raw = chatClient.chat(system, user);
        } catch (Exception e) {
            log.warn("Schema 补丁 LLM 调用失败 scope={} exec={}: {}", scopeId, executionId, e.getMessage());
            return 0;
        }

        List<SchemaPatchDO> parsed = parse(raw, scopeId, executionId, sourceType);
        if (parsed.isEmpty()) {
            log.info("Proposer 未产出候选补丁 scope={} exec={}", scopeId, executionId);
            return 0;
        }

        // 规则 3：第二段独立 LLM 守门审，两轮共识才入 PENDING。
        Map<Integer, GatekeeperDecision> decisions = runGatekeeper(scopeId, executionId, schema.getConfigValue(), parsed);

        int pending = 0, observing = 0, rejected = 0;
        List<SchemaPatchDO> admitted = new ArrayList<>(parsed.size());
        for (int i = 0; i < parsed.size(); i++) {
            SchemaPatchDO p = parsed.get(i);
            GatekeeperDecision g = decisions.get(i);
            if (g != null && "REJECT".equals(g.decision)) {
                rejected++;
                log.debug("Gatekeeper REJECT scope={} section={} reason={}", scopeId, p.getSectionTitle(), g.reason);
                continue;
            }
            // 共识规则：两轮都 PENDING/APPROVE 才 PENDING；否则降级 OBSERVING。
            if (g != null) {
                if ("OBSERVE".equals(g.decision)
                    && SchemaPatchModel.Status.PENDING.name().equals(p.getStatus())) {
                    p.setStatus(SchemaPatchModel.Status.OBSERVING.name());
                }
                // 结构化持久化 Gatekeeper 决定，供 P2-i 回流准确率指标
                p.setGatekeeperDecision(g.decision);
                if (g.reason != null && !g.reason.isBlank()) {
                    String trimmedReason = g.reason.trim();
                    p.setGatekeeperReason(trimmedReason.length() > 200
                        ? trimmedReason.substring(0, 200) : trimmedReason);
                }
            }
            admitted.add(p);
        }

        for (SchemaPatchDO p : admitted) {
            schemaPatchMapper.insert(p);
            if (SchemaPatchModel.Status.OBSERVING.name().equals(p.getStatus())) observing++;
            else pending++;
        }
        log.info("Schema 补丁提案入库 {} 条（pending={} observing={} rejected={}） scope={} exec={}",
            admitted.size(), pending, observing, rejected, scopeId, executionId);
        return admitted.size();
    }

    private List<SchemaPatchDO> parse(String raw, Long scopeId, Long executionId, SchemaPatchModel.SourceType sourceType) {
        List<SchemaPatchDO> list = new ArrayList<>();
        if (raw == null) return list;
        String cleaned = stripFences(raw).trim();
        if (cleaned.isEmpty() || "[]".equals(cleaned)) return list;
        try {
            JsonNode arr = objectMapper.readTree(cleaned);
            if (!arr.isArray()) return list;
            for (JsonNode node : arr) {
                String section = textOrNull(node, "sectionTitle");
                String op = textOrNull(node, "operation");
                if (section == null || op == null) continue;
                if (!ALLOWED_SECTIONS.contains(section.trim())) {
                    log.warn("Schema 补丁越界 section 被拒：{}", section);
                    continue;
                }
                if (!ALLOWED_OPS.contains(op.trim().toUpperCase())) {
                    log.warn("Schema 补丁越界 operation 被拒：{}", op);
                    continue;
                }
                SchemaPatchDO p = new SchemaPatchDO();
                p.setScopeId(scopeId);
                p.setSourceExecutionId(executionId);
                p.setSourceType(sourceType.name());
                p.setSectionTitle(section.trim());
                p.setOperation(op.trim().toUpperCase());
                p.setDiffBefore(textOrNull(node, "diffBefore"));
                p.setDiffAfter(textOrNull(node, "diffAfter"));
                p.setRationale(textOrNull(node, "rationale"));

                JsonNode evidenceNode = node.get("evidence");
                int evidenceCount = 0;
                if (evidenceNode != null && evidenceNode.isArray() && evidenceNode.size() > 0) {
                    try {
                        p.setEvidenceJson(objectMapper.writeValueAsString(evidenceNode));
                        evidenceCount = evidenceNode.size();
                    } catch (Exception ignore) {}
                }

                BigDecimal confidence = readConfidence(node);
                p.setConfidence(confidence);

                p.setStatus(classifyStatus(confidence, evidenceCount).name());
                list.add(p);
            }
        } catch (Exception e) {
            log.warn("Schema 补丁 JSON 解析失败 scope={}: {}", scopeId, e.getMessage());
        }
        return list;
    }

    /**
     * 分层：高置信进 PENDING 红点；其余进 OBSERVING 观察期。
     * 兼容历史数据：confidence 为空按 PENDING 处理（与 P2-b 前补丁等价）。
     */
    private SchemaPatchModel.Status classifyStatus(BigDecimal confidence, int evidenceCount) {
        if (confidence == null) return SchemaPatchModel.Status.PENDING;
        boolean highConfidence = confidence.compareTo(CONFIDENCE_THRESHOLD) >= 0;
        boolean enoughEvidence = evidenceCount >= EVIDENCE_THRESHOLD;
        return (highConfidence && enoughEvidence)
            ? SchemaPatchModel.Status.PENDING
            : SchemaPatchModel.Status.OBSERVING;
    }

    private BigDecimal readConfidence(JsonNode node) {
        JsonNode v = node.get("confidence");
        if (v == null || v.isNull()) return null;
        try {
            if (v.isNumber()) return v.decimalValue().setScale(2, RoundingMode.HALF_UP);
            return new BigDecimal(v.asText()).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String stripFences(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) {
                t = t.substring(firstNewline + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t;
    }

    /** 规则 3：批量调用 Gatekeeper 对候选补丁做独立终审。 */
    private Map<Integer, GatekeeperDecision> runGatekeeper(Long scopeId, Long executionId,
                                                           String currentSchema, List<SchemaPatchDO> candidates) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyMap();
        if (chatClient == null || !chatClient.isAvailable()) {
            log.debug("Gatekeeper AI 不可用，跳过守门审 scope={}", scopeId);
            return Collections.emptyMap();
        }

        String candidatesJson;
        try {
            ArrayNode arr = objectMapper.createArrayNode();
            for (int i = 0; i < candidates.size(); i++) {
                SchemaPatchDO p = candidates.get(i);
                ObjectNode node = objectMapper.createObjectNode();
                node.put("index", i);
                node.put("sectionTitle", p.getSectionTitle());
                node.put("operation", p.getOperation());
                node.put("diffBefore", p.getDiffBefore());
                node.put("diffAfter", p.getDiffAfter());
                node.put("rationale", p.getRationale());
                if (p.getConfidence() != null) node.put("confidence", p.getConfidence());
                if (p.getEvidenceJson() != null && !p.getEvidenceJson().isBlank()) {
                    try {
                        node.set("evidence", objectMapper.readTree(p.getEvidenceJson()));
                    } catch (Exception ignore) {
                        node.putArray("evidence");
                    }
                } else {
                    node.putArray("evidence");
                }
                arr.add(node);
            }
            candidatesJson = objectMapper.writeValueAsString(arr);
        } catch (Exception e) {
            log.warn("Gatekeeper 候选序列化失败 scope={}: {}", scopeId, e.getMessage());
            return Collections.emptyMap();
        }

        String raw;
        try {
            String system = schemaInjector.prepend(scopeId, PromptRegistry.forSchemaPatch().gatekeeperSystemPrompt());
            String user = PromptRegistry.forSchemaPatch().gatekeeperUserPrompt(currentSchema, candidatesJson);
            raw = chatClient.chat(system, user);
        } catch (Exception e) {
            log.warn("Gatekeeper LLM 调用失败 scope={} exec={}: {}", scopeId, executionId, e.getMessage());
            return Collections.emptyMap();
        }

        Map<Integer, GatekeeperDecision> out = new HashMap<>();
        if (raw == null) return out;
        String cleaned = stripFences(raw).trim();
        if (cleaned.isEmpty() || "[]".equals(cleaned)) return out;
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            if (!node.isArray()) return out;
            for (JsonNode n : node) {
                JsonNode idx = n.get("index");
                JsonNode dec = n.get("decision");
                if (idx == null || !idx.canConvertToInt() || dec == null) continue;
                String d = dec.asText("").trim().toUpperCase();
                if (!("APPROVE".equals(d) || "OBSERVE".equals(d) || "REJECT".equals(d))) continue;
                String reason = n.hasNonNull("reason") ? n.get("reason").asText() : null;
                out.put(idx.asInt(), new GatekeeperDecision(d, reason));
            }
        } catch (Exception e) {
            log.warn("Gatekeeper JSON 解析失败 scope={}: {}", scopeId, e.getMessage());
        }
        return out;
    }

    private static final class GatekeeperDecision {
        final String decision;
        final String reason;
        GatekeeperDecision(String decision, String reason) {
            this.decision = decision;
            this.reason = reason;
        }
    }
}
