package org.cn.liuwt.llmwiki.domain.service.harness.governance.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.*;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaManager;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaMarkdownRenderer;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaStructuredParser;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaSkeletonValidator;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jakarta.annotation.PreDestroy;

/**
 * Schema 冷启动 V2 结构化引导服务。对应 AGENTS.md《Schema 共治宪法·规则 4》。
 *
 * 流程（四步向导）：
 *   startV2(scopeId)                              -> 返回能力域列表
 *   selectCapabilities(sessionId, capabilityIds)  -> 融合能力域骨架
 *   submitCategoryTree(sessionId, treeJson)       -> 保存分类树
 *   submitPageBlueprint(sessionId, blueprintJson)  -> 保存页面蓝图
 *   submitAutonomy(sessionId, level, overrides)   -> 设定 AI 协作模式
 *   finalizeV2(sessionId)                         -> 合成 7 段 Markdown 并落盘
 *
 * 会话态：内存级 ConcurrentMap，2 小时过期；过期后调用方需重新 startV2。
 */
@Service
public class SchemaBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(SchemaBootstrapService.class);
    private static final long SESSION_TTL_SECONDS = 2 * 60 * 60L;
    private static final long LLM_CALL_TIMEOUT_SECONDS = 120L;

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private SchemaSkeletonValidator skeletonValidator;

    @Autowired
    private SchemaJsonSynthesizer schemaJsonSynthesizer;

    @Autowired
    private ParadigmCatalog paradigmCatalog;

    @Autowired
    private SchemaStructuredParser schemaStructuredParser;

    @Autowired
    private SchemaMarkdownRenderer schemaMarkdownRenderer;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentMap<String, BootstrapSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService bootstrapExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "bootstrap-" + r.hashCode());
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down bootstrapExecutor");
        bootstrapExecutor.shutdown();
        try {
            if (!bootstrapExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                bootstrapExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            bootstrapExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isBootstrapRequired(Long scopeId) {
        if (scopeId == null) return false;
        SchemaConfigDO schema = schemaManager.getSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        if (schema == null) return true;
        String value = schema.getConfigValue();
        return value == null || value.isBlank();
    }

    public List<Map<String, Object>> listParadigms() {
        return paradigmCatalog.listAll().stream()
            .map(ParadigmCatalog.Paradigm::toSummary)
            .collect(Collectors.toList());
    }

    // ===== V2 结构化引导 API =====

    public V2StartPayload startV2(Long scopeId) {
        if (scopeId == null) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_SCOPE_NULL);
        }
        if (!isBootstrapRequired(scopeId)) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_ALREADY_DONE);
        }

        purgeExpired();

        String sessionId = UUID.randomUUID().toString();
        BootstrapSession session = new BootstrapSession(scopeId);
        session.v2 = true;
        sessions.put(sessionId, session);

        List<Map<String, Object>> capabilities = paradigmCatalog.listAll().stream()
            .filter(p -> p.capabilityMeta != null)
            .map(p -> {
                Map<String, Object> m = new LinkedHashMap<>(p.toSummary());
                return m;
            })
            .collect(Collectors.toList());

        return new V2StartPayload(sessionId, capabilities);
    }

    public Map<String, Object> selectCapabilities(String sessionId, List<String> capabilityIds) {
        BootstrapSession session = requireSession(sessionId);
        if (capabilityIds == null || capabilityIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_EMPTY_CAPABILITIES);
        }

        List<SchemaStructuredModel> skeletons = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<String> validIds = new ArrayList<>();
        for (String cid : capabilityIds) {
            ParadigmCatalog.Paradigm p = paradigmCatalog.getById(cid);
            if (p != null && p.skeleton != null) {
                skeletons.add(p.skeleton);
                labels.add(p.label);
                validIds.add(cid);
            }
        }
        if (skeletons.isEmpty()) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_INVALID_CAPABILITIES);
        }

        session.capabilityIds = validIds;
        session.draftModel = fuseModels(skeletons, labels, validIds);

        String json = schemaStructuredParser.toJson(session.draftModel);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("selectedCapabilities", validIds);
        result.put("domainNarrative", session.draftModel.getDomainNarrative());
        result.put("categoryCount", countTaxonomyNodes(session.draftModel.getTaxonomy()));
        result.put("templateCount", session.draftModel.getTemplates() != null
            ? session.draftModel.getTemplates().getPageTemplates().size() : 0);
        result.put("draftJson", json);
        return result;
    }

    public Map<String, Object> submitCategoryTree(String sessionId, String treeJson) {
        BootstrapSession session = requireSession(sessionId);
        requireV2(session);
        if (session.draftModel == null) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_NO_CAPABILITIES);
        }

        Taxonomy taxonomy = parseTreeJson(treeJson);
        session.draftModel.setTaxonomy(taxonomy);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tree", taxonomy);
        result.put("nodeCount", countTaxonomyNodes(taxonomy));
        return result;
    }

    public Map<String, Object> submitPageBlueprint(String sessionId, String blueprintJson) {
        BootstrapSession session = requireSession(sessionId);
        requireV2(session);
        if (session.draftModel == null) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_NO_CAPABILITIES);
        }

        try {
            Templates templates = objectMapper.readValue(blueprintJson, Templates.class);
            session.draftModel.setTemplates(templates);
        } catch (Exception e) {
            log.warn("Failed to parse page blueprint JSON: {}", e.getMessage());
            throw new BusinessException(ErrorCode.BOOTSTRAP_INVALID_BLUEPRINT, e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templates", session.draftModel.getTemplates());
        return result;
    }

    public Map<String, Object> submitAutonomy(String sessionId, String level, Map<String, Object> overrides) {
        BootstrapSession session = requireSession(sessionId);
        requireV2(session);
        if (session.draftModel == null) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_NO_CAPABILITIES);
        }

        String normalizedLevel = level != null ? level.trim().toLowerCase() : "balanced";
        Workflow workflow = new Workflow();
        switch (normalizedLevel) {
            case "cautious":
                workflow.setDefaultApproval("CONFIRM");
                workflow.setNarrative("谨慎执行模式：所有知识变更需用户确认后执行，AI 仅做建议。");
                workflow.setConfirmTriggers(List.of(
                    "所有新页面创建需确认",
                    "所有页面内容更新需确认",
                    "删除任何页面需确认",
                    "交叉引用变更需确认"
                ));
                break;
            case "autonomous":
                workflow.setDefaultApproval("AUTO");
                workflow.setNarrative("高度自主模式：AI 自动处理日常知识管理事务，仅在高风险操作时请求确认。");
                workflow.setConfirmTriggers(List.of(
                    "删除已有深度分析页面时须确认",
                    "涉及制度性变更时须确认"
                ));
                break;
            case "balanced":
            default:
                workflow.setDefaultApproval("AUTO");
                workflow.setNarrative("平衡协作模式：AI 自动处理日常事务，重大决策和高风险操作请用户确认。");
                workflow.setConfirmTriggers(List.of(
                    "删除已有页面时须确认",
                    "发现内容矛盾时生成建议由用户确认",
                    "涉及跨分类的重大结构变更时须确认"
                ));
                break;
        }

        if (overrides != null && overrides.containsKey("confirmTriggers")) {
            Object triggers = overrides.get("confirmTriggers");
            if (triggers instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> triggerList = (List<String>) triggers;
                workflow.setConfirmTriggers(triggerList);
            }
        }

        session.draftModel.setWorkflow(workflow);

        LintRulesConfig lintRules = LintRulesConfig.buildDefaults();
        if ("cautious".equals(normalizedLevel)) {
            for (LintRulesConfig.DiagnosticRule rule : lintRules.getDiagnosticRules().values()) {
                rule.setAutoFixEnabled(false);
            }
        }
        session.draftModel.setLintRules(lintRules);

        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(buildRuleItem("auto_ingest", "新资料入库后，AI 自动提取和生成页面",
            !"cautious".equals(normalizedLevel), "✅"));
        rules.add(buildRuleItem("auto_crossref", "发现页面之间缺少链接，AI 自动补充",
            !"cautious".equals(normalizedLevel), "✅"));
        rules.add(buildRuleItem("conflict_review", "发现内容矛盾，AI 生成建议由你确认",
            true, "⚠️"));
        rules.add(buildRuleItem("gap_notify", "发现知识缺口，AI 提醒你补充",
            true, "⚠️"));
        rules.add(buildRuleItem("no_auto_delete", "AI 不会自动删除或大幅改写已有页面",
            true, "❌"));
        rules.add(buildRuleItem("stale_notify", "发现过期内容，AI 提醒你更新",
            true, "⚠️"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("level", normalizedLevel);
        result.put("workflow", workflow);
        result.put("rules", rules);
        return result;
    }

    public SchemaConfigDO finalizeV2(String sessionId) {
        BootstrapSession session = requireSession(sessionId);
        requireV2(session);
        TokenUsageContext.set(session.scopeId, "schema");
        try {
            return doFinalizeV2(sessionId, session);
        } finally {
            TokenUsageContext.clear();
        }
    }

    private SchemaConfigDO doFinalizeV2(String sessionId, BootstrapSession session) {
        if (session.draftModel == null) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_NO_DRAFT);
        }

        session.draftModel.setActiveCapabilities(
            session.capabilityIds != null ? session.capabilityIds : new ArrayList<>()
        );

        CapabilityRecord record = new CapabilityRecord();
        record.setCapability(String.join(",", session.capabilityIds != null ? session.capabilityIds : List.of()));
        record.setActivatedAt(LocalDate.now().toString());
        record.setSource("bootstrap");
        session.draftModel.setCapabilityHistory(List.of(record));

        String json = schemaStructuredParser.toJson(session.draftModel);

        String markdown;
        if (chatClient != null && chatClient.isAvailable()) {
            try {
                SchemaJsonSynthesizer.SynthesisResult synthResult =
                    schemaJsonSynthesizer.synthesizeFromStructured(session.draftModel, session.capabilityIds);
                markdown = synthResult.markdown;
            } catch (Exception e) {
                log.warn("LLM polish failed, falling back to direct render: {}", e.getMessage());
                markdown = schemaMarkdownRenderer.render(session.draftModel);
            }
        } else {
            markdown = schemaMarkdownRenderer.render(session.draftModel);
        }

        SchemaSkeletonValidator.ValidationResult validationResult = skeletonValidator.validate(
            SchemaSkeletonValidator.WIKI_SCHEMA_KEY, markdown
        );
        if (!validationResult.isValid()) {
            throw new BusinessException(ErrorCode.SCHEMA_SKELETON_INVALID, validationResult.getMessage());
        }

        SchemaConfigDO saved = schemaManager.saveSchema(
            session.scopeId,
            SchemaSkeletonValidator.WIKI_SCHEMA_KEY,
            markdown,
            json,
            "wiki",
            "冷启动生成的初版 Schema",
            SchemaManager.SOURCE_BOOTSTRAP,
            null,
            session.scopeId
        );
        sessions.remove(sessionId);
        log.info("Schema bootstrap V2 finalized: scopeId={}, sessionId={}", session.scopeId, sessionId);
        return saved;
    }

    private SchemaStructuredModel fuseModels(List<SchemaStructuredModel> skeletons,
                                               List<String> labels, List<String> capabilityIds) {
        if (skeletons.size() == 1) {
            return skeletons.get(0);
        }

        SchemaStructuredModel fused = new SchemaStructuredModel();

        StringBuilder narrative = new StringBuilder();
        narrative.append("复合知识库，融合以下能力域：");
        narrative.append(String.join("、", labels));
        narrative.append("。");
        fused.setDomainNarrative(narrative.toString());

        Taxonomy fusedTax = new Taxonomy();
        fusedTax.setNarrative("按能力域组织顶层分类，各能力域内部按自身逻辑细分");
        List<TaxonomyNode> roots = new ArrayList<>();
        for (int i = 0; i < skeletons.size(); i++) {
            TaxonomyNode domainRoot = new TaxonomyNode();
            domainRoot.setId(capabilityIds.get(i));
            domainRoot.setLabel(labels.get(i));
            domainRoot.setDescription(labels.get(i) + "相关知识");
            if (skeletons.get(i).getTaxonomy() != null && skeletons.get(i).getTaxonomy().getRoots() != null) {
                domainRoot.setChildren(skeletons.get(i).getTaxonomy().getRoots());
            }
            roots.add(domainRoot);
        }
        fusedTax.setRoots(roots);
        fused.setTaxonomy(fusedTax);

        Templates fusedTemplates = new Templates();
        List<PageTemplate> allTemplates = new ArrayList<>();
        for (SchemaStructuredModel s : skeletons) {
            if (s.getTemplates() != null && s.getTemplates().getPageTemplates() != null) {
                allTemplates.addAll(s.getTemplates().getPageTemplates());
            }
        }
        fusedTemplates.setPageTemplates(allTemplates);
        fused.setTemplates(fusedTemplates);

        SchemaStructuredModel first = skeletons.get(0);
        fused.setNaming(first.getNaming());

        Workflow fusedWorkflow = new Workflow();
        boolean anyConfirm = skeletons.stream()
            .anyMatch(s -> s.getWorkflow() != null && "CONFIRM".equals(s.getWorkflow().getDefaultApproval()));
        fusedWorkflow.setDefaultApproval(anyConfirm ? "CONFIRM" : "AUTO");
        List<String> allTriggers = new ArrayList<>();
        for (SchemaStructuredModel s : skeletons) {
            if (s.getWorkflow() != null && s.getWorkflow().getConfirmTriggers() != null) {
                allTriggers.addAll(s.getWorkflow().getConfirmTriggers());
            }
        }
        fusedWorkflow.setConfirmTriggers(allTriggers.stream().distinct().collect(Collectors.toList()));
        fused.setWorkflow(fusedWorkflow);

        fused.setLintRules(LintRulesConfig.buildDefaults());
        fused.setActiveCapabilities(capabilityIds);

        return fused;
    }

    private Taxonomy parseTreeJson(String treeJson) {
        try {
            return objectMapper.readValue(treeJson, Taxonomy.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_INVALID_TREE, e.getMessage());
        }
    }

    private int countTaxonomyNodes(Taxonomy taxonomy) {
        if (taxonomy == null || taxonomy.getRoots() == null) return 0;
        int count = 0;
        for (TaxonomyNode root : taxonomy.getRoots()) {
            count += countNode(root);
        }
        return count;
    }

    private int countNode(TaxonomyNode node) {
        if (node == null) return 0;
        int count = 1;
        if (node.getChildren() != null) {
            for (TaxonomyNode child : node.getChildren()) {
                count += countNode(child);
            }
        }
        return count;
    }

    private Map<String, Object> buildRuleItem(String id, String description, boolean enabled, String icon) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("description", description);
        item.put("enabled", enabled);
        item.put("icon", icon);
        return item;
    }

    private void requireV2(BootstrapSession session) {
        if (!session.v2) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_V1_SESSION);
        }
    }

    private BootstrapSession requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_SESSION_INVALID);
        }
        BootstrapSession session = sessions.get(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_SESSION_EXPIRED);
        }
        if (session.isExpired()) {
            sessions.remove(sessionId);
            throw new BusinessException(ErrorCode.BOOTSTRAP_SESSION_EXPIRED);
        }
        return session;
    }

    private void purgeExpired() {
        sessions.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    private static class BootstrapSession {
        final Long scopeId;
        final Instant createdAt = Instant.now();
        volatile SchemaStructuredModel draftModel;

        volatile boolean v2 = false;
        volatile List<String> capabilityIds;

        BootstrapSession(Long scopeId) {
            this.scopeId = scopeId;
        }

        boolean isExpired() {
            return Duration.between(createdAt, Instant.now()).getSeconds() > SESSION_TTL_SECONDS;
        }
    }

    public static class V2StartPayload {
        public final String sessionId;
        public final List<Map<String, Object>> capabilities;

        public V2StartPayload(String sessionId, List<Map<String, Object>> capabilities) {
            this.sessionId = sessionId;
            this.capabilities = capabilities;
        }
    }
}
