package org.cn.liuwt.llmwiki.web.mcp;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel;
import org.cn.liuwt.llmwiki.domain.model.wiki.WikiPageModel;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.domain.service.wiki.SourceService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.facade.model.SearchResultInfo;
import org.cn.liuwt.llmwiki.service.harness.mq.ExecutionNodeRegistry;
import org.cn.liuwt.llmwiki.service.ingest.IngestOrchestrationService;
import org.cn.liuwt.llmwiki.service.ingest.IngestService;
import org.cn.liuwt.llmwiki.service.query.QueryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 工具面（streamable-http /mcp，SYNC 模式）。
 * scope 上下文来自认证 filter 写入的 request attributes：
 * JwtAuthenticationFilter（JWT）或 ApiKeyAuthFilter（API Key）。
 * 红线：scope 一律从认证上下文解析，绝不作为工具参数暴露。
 */
@Component
public class WikiMcpTools {

    @Autowired
    private SearchService searchService;

    @Autowired
    private WikiFileServiceImpl wikiFileService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private SourceService sourceService;

    @Autowired
    private IngestOrchestrationService ingestOrchestrationService;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private ExecutionNodeRegistry registry;

    private static final Duration ASK_TIMEOUT_QUICK = Duration.ofSeconds(55);
    private static final Duration ASK_TIMEOUT_DEEP = Duration.ofSeconds(280);
    private static final String STEP_PREFIX = "__STEP__:";

    @Tool(name = "wiki_search", description = "在当前 scope 的知识库中检索 wiki 页面。返回 pageId、标题、路径、摘要与相关度评分；先用本工具定位，再用 wiki_read_page 精读。")
    public List<Map<String, Object>> wikiSearch(
            @ToolParam(description = "检索关键词或问题") String query,
            @ToolParam(required = false, description = "分类过滤（如 guide/concept），不填则全部分类") String category,
            @ToolParam(required = false, description = "返回条数上限，默认 5，最大 10") Integer limit) {
        Long scopeId = currentScopeId();
        int max = limit == null ? 5 : Math.min(Math.max(limit, 1), 10);
        return searchService.search(scopeId, query, category).stream()
                .filter(r -> !"DEPRECATED".equals(r.getLifecycleStatus()))
                .limit(max)
                .map(r -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("pageId", r.getId());
                    item.put("title", r.getTitle());
                    item.put("path", r.getPath());
                    item.put("category", r.getCategory());
                    item.put("summary", r.getSummary());
                    item.put("score", r.getScore());
                    if (r.getSourceScopeName() != null) {
                        item.put("sourceScopeName", r.getSourceScopeName());
                    }
                    return item;
                })
                .toList();
    }

    Long currentScopeId() {
        return (Long) currentAttributes().getRequest().getAttribute("scopeId");
    }

    Long currentUserId() {
        return (Long) currentAttributes().getRequest().getAttribute("userId");
    }

    @Tool(name = "wiki_read_page", description = "按 pageId 或文件路径读取一个 wiki 页面的完整 Markdown 内容，并列出支撑它的来源。pageId 与 filePath 二选一。")
    public Map<String, Object> wikiReadPage(
            @ToolParam(required = false, description = "wiki_search 返回的 pageId") Long pageId,
            @ToolParam(required = false, description = "wiki 页面文件路径（如 docs/quickstart.md），与 pageId 二选一") String filePath) {
        Long scopeId = currentScopeId();
        WikiPageModel page;
        if (pageId != null) {
            page = wikiFileService.readPageById(pageId, scopeId);
        } else if (filePath != null && !filePath.isBlank()) {
            page = wikiFileService.readPageByFilePath(filePath.trim(), scopeId);
        } else {
            throw new IllegalArgumentException("pageId 与 filePath 必须提供其一");
        }
        if (page == null) {
            throw new IllegalArgumentException("页面不存在或不在当前 scope 内: "
                    + (pageId != null ? "pageId=" + pageId : "filePath=" + filePath));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageId", page.getId());
        result.put("title", page.getTitle());
        result.put("path", page.getPath());
        result.put("category", page.getCategory());
        result.put("summary", page.getSummary());
        result.put("content", page.getContent());
        List<Map<String, Object>> sources = new ArrayList<>();
        for (SourceDO s : wikiFileService.getPageSources(page.getId(), scopeId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceId", s.getId());
            item.put("name", s.getName());
            if (s.getStatus() != null) {
                item.put("status", s.getStatus());
            }
            sources.add(item);
        }
        result.put("sources", sources);
        return result;
    }

    @Tool(name = "wiki_ask", description = "向知识库提问，获得基于 wiki 内容的生成答案。quick（默认）较快；deep 多步检索更慢但更全面。超时会返回已生成的部分答案与 timedOut=true。")
    public Map<String, Object> wikiAsk(
            @ToolParam(description = "问题") String question,
            @ToolParam(required = false, description = "quick（默认）或 deep") String mode) {
        Long scopeId = currentScopeId();
        boolean deep = "deep".equalsIgnoreCase(mode);
        String sessionId = "mcp-" + UUID.randomUUID();
        Flux<String> stream = queryService.queryWikiStreaming(scopeId, question, sessionId, deep);
        AskResult collected = collectAnswer(stream, deep ? ASK_TIMEOUT_DEEP : ASK_TIMEOUT_QUICK);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", collected.answer());
        result.put("steps", collected.steps());
        result.put("timedOut", collected.timedOut());
        return result;
    }

    /** wikiAsk 的三态结果：答案 / 步骤名列表 / 是否因超时提前返回。 */
    record AskResult(String answer, List<String> steps, boolean timedOut) {}

    /**
     * 聚合 queryWikiStreaming 的 Flux：__STEP__: 前缀的 chunk 归入 steps，其余拼接为答案。
     * blockLast 超时抛 IllegalStateException，捕获后按"部分答案 + timedOut"返回（防御式三态独立报告）。
     */
    static AskResult collectAnswer(Flux<String> stream, Duration timeout) {
        List<String> steps = new ArrayList<>();
        StringBuilder answer = new StringBuilder();
        boolean[] timedOut = {false};
        try {
            stream.doOnNext(chunk -> {
                if (chunk.startsWith(STEP_PREFIX)) {
                    steps.add(chunk.substring(STEP_PREFIX.length()));
                } else {
                    answer.append(chunk);
                }
            }).blockLast(timeout);
        } catch (IllegalStateException e) {
            timedOut[0] = true;
        }
        return new AskResult(answer.toString(), List.copyOf(steps), timedOut[0]);
    }

    @Tool(name = "wiki_ingest_text", description = "把一段 Markdown 文本作为新来源摄入知识库：写入 raw/ 存储并启动完整摄入流水线（分析、编译、链接）。摄入是长任务，本工具立即返回 executionId；用 wiki_ingest_status 查询进度。")
    public Map<String, Object> wikiIngestText(
            @ToolParam(description = "来源标题（无需 .md 后缀）") String title,
            @ToolParam(description = "Markdown 正文") String content,
            @ToolParam(required = false, description = "给摄入管线的额外指引（如重点提取什么）") String guidance) {
        Long scopeId = currentScopeId();
        Long userId = currentUserId();
        SourceModel source = sourceService.uploadTextSource(title, content, scopeId, userId);
        ExecutionModel execution = ingestOrchestrationService.startIngest(scopeId, source.getId(), guidance);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", execution.getId());
        result.put("sourceId", source.getId());
        result.put("status", execution.getStatus());
        if (source.getDuplicateInfo() != null) {
            result.put("duplicateWarning", source.getDuplicateInfo().getMessage());
        }
        result.put("hint", "摄入为长任务：用 wiki_ingest_status 轮询，或在 deepseek-harness 侧用 llmwiki_follow_ingest 跟随完成事件。");
        return result;
    }

    @Tool(name = "wiki_ingest_status", description = "查询一次摄入执行的进度：总体状态、当前步骤、步骤清单与错误信息。")
    public Map<String, Object> wikiIngestStatus(
            @ToolParam(description = "wiki_ingest_text 返回的 executionId") Long executionId) {
        Long scopeId = currentScopeId();
        ExecutionModel execution = ingestService.getProgress(executionId);
        if (execution == null || !scopeId.equals(execution.getScopeId())) {
            throw new IllegalArgumentException("execution 不存在或不在当前 scope 内: " + executionId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", executionId);
        result.put("status", execution.getStatus());
        result.put("currentStep", currentStepName(execution));
        if (execution.getErrorMessage() != null) {
            result.put("errorMessage", execution.getErrorMessage());
        }
        List<String> steps = new ArrayList<>();
        if (execution.getSteps() != null) {
            for (ExecutionModel.ExecutionStepModel s : execution.getSteps()) {
                steps.add(s.getStepName() + ":" + s.getStatus());
            }
        }
        result.put("steps", steps);
        return result;
    }

    @Tool(name = "wiki_cancel_ingest", description = "取消一次进行中的摄入执行。已完成/已失败/已取消的执行不可再取消。")
    public Map<String, Object> wikiCancelIngest(
            @ToolParam(description = "要取消的 executionId") Long executionId) {
        Long scopeId = currentScopeId();
        ExecutionModel execution = ingestService.getProgress(executionId);
        if (execution == null || !scopeId.equals(execution.getScopeId())) {
            throw new IllegalArgumentException("execution 不存在或不在当前 scope 内: " + executionId);
        }
        String status = execution.getStatus();
        if ("completed".equals(status) || "failed".equals(status)
                || "cancelled".equals(status) || "budget_exhausted".equals(status)) {
            throw new IllegalStateException("execution 已结束，状态为 " + status + "，不可取消");
        }
        // 与 IngestController.cancelIngest 的单机路径对齐：落库取消 + 中断本地线程。
        // 已知限制：多机 MQ 部署下跨节点中断依赖 ControlMessage 广播（仅 Web 端点发送），
        // 多机部署请用 Web UI 取消——首期分期决策（读写优先）。
        ingestService.cancelExecution(executionId, execution.getScopeId());
        registry.cancelAndRemoveFuture(executionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", executionId);
        result.put("status", "cancelled");
        return result;
    }

    private String currentStepName(ExecutionModel execution) {
        List<ExecutionModel.ExecutionStepModel> steps = execution.getSteps();
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        return steps.stream()
                .filter(s -> !"completed".equals(s.getStatus()))
                .map(ExecutionModel.ExecutionStepModel::getStepName)
                .findFirst()
                .orElseGet(() -> steps.get(steps.size() - 1).getStepName());
    }

    private static ServletRequestAttributes currentAttributes() {
        return (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
    }
}
