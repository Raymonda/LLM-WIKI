package org.cn.liuwt.llmwiki.domain.service.harness.edit;

import org.cn.liuwt.llmwiki.domain.service.wiki.DraftService;
import org.cn.liuwt.llmwiki.domain.service.wiki.EditSessionService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.model.wiki.WikiPageModel;
import org.cn.liuwt.llmwiki.facade.model.AiEditRequest;
import org.cn.liuwt.llmwiki.facade.model.AiEditResponse;
import org.cn.liuwt.llmwiki.facade.model.CommitStepResult;
import org.cn.liuwt.llmwiki.facade.model.EditSessionInfo;
import org.cn.liuwt.llmwiki.facade.model.EditStepInfo;
import org.cn.liuwt.llmwiki.facade.model.FailedBlockInfo;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AiEditService {

    private static final Logger log = LoggerFactory.getLogger(AiEditService.class);

    private static final int MAX_HISTORY_STEPS = 3;
    private static final int EMPTY_CONTENT_THRESHOLD = 50;
    private static final int MAX_KNOWLEDGE_CONTEXT_CHARS = 15000;
    private static final int MAX_KNOWLEDGE_PAGE_CHARS = 4000;

    private static final Pattern FENCE_PATTERN = Pattern.compile(
        "```markdown\\s*\\n([\\s\\S]*?)```", Pattern.MULTILINE);

    private static final String EXPLANATION_SEPARATOR = "---EXPLANATION---";

    private static final int MAX_EDIT_ROUNDS = 3;

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private EditSessionService editSessionService;

    @Autowired(required = false)
    private WikiFileServiceImpl wikiFileService;

    @Autowired
    private SearchReplaceEngine searchReplaceEngine;

    @Autowired
    private SelectionAnchorer selectionAnchorer;

    @Autowired
    private EditContextBuilder editContextBuilder;

    @Autowired(required = false)
    private SchemaInjector schemaInjector;

    public boolean isAvailable() {
        return chatClient != null && chatClient.isAvailable();
    }

    public Flux<AiEditResponse> editWithContext(Long scopeId, AiEditRequest request) {
        if (!isAvailable()) {
            return Flux.just(errorEvent("AI 服务不可用，请降级到 Markdown 源码编辑"));
        }

        Long sessionId = request.getSessionId();
        EditSessionInfo session = editSessionService.getSession(sessionId, scopeId, true);
        if (session == null || session.getCurrentContent() == null) {
            return Flux.just(errorEvent("编辑会话不存在或内容为空"));
        }

        String expectedHash = request.getExpectedHash();
        if (expectedHash != null && !expectedHash.isBlank()
                && !expectedHash.equals(session.getContentHash())) {
            log.warn("AI edit rejected due to hash conflict: sessionId={} expected={} current={}",
                sessionId, expectedHash, session.getContentHash());
            AiEditResponse conflict = errorEvent("页面内容在你编辑期间发生了变化，请先查看最新内容再发起 AI 修改");
            conflict.setCode("HASH_CONFLICT");
            return Flux.just(conflict);
        }

        String currentContent = session.getCurrentContent();
        String outline = session.getOutline() != null ? session.getOutline() : "";
        boolean hasSelection = request.getSelectedLines() != null && !request.getSelectedLines().isBlank();
        boolean hasConfirmedKnowledge = request.getConfirmedKnowledgeIds() != null
            && !request.getConfirmedKnowledgeIds().isEmpty();

        String knowledgeContext = "";
        if (hasConfirmedKnowledge) {
            knowledgeContext = buildKnowledgeContextFromPages(
                scopeId, request.getConfirmedKnowledgeIds(), session.getPageId());
            if (!knowledgeContext.isEmpty()) {
                log.info("Confirmed knowledge context injected: sessionId={} length={}",
                    sessionId, knowledgeContext.length());
            }
        }

        boolean isCreateMode = isContentEmptyOrMinimal(currentContent);

        SelectionAnchorer.AnchorResult anchor = null;
        if (hasSelection) {
            anchor = selectionAnchorer.anchor(
                currentContent, request.getSelectedLines(), request.getSelectedText());
        }

        boolean useFocusedMode = anchor != null
            && !hasConfirmedKnowledge
            && !isCreateMode
            && knowledgeContext.isEmpty()
            && isFocusedSpan(anchor.endLine() - anchor.startLine() + 1);

        if (useFocusedMode) {
            log.info("Using focused mode for sessionId={}, anchor=L{}-L{} status={}",
                sessionId, anchor.startLine(), anchor.endLine(), anchor.status());
            return prependAnchorEvent(anchor,
                executeFocusedEdit(scopeId, sessionId, session, currentContent, request, anchor));
        }

        return prependAnchorEvent(anchor,
            executeFullEdit(scopeId, sessionId, session, currentContent, outline,
                hasSelection, knowledgeContext, hasConfirmedKnowledge, isCreateMode, request, anchor));
    }

    private Flux<AiEditResponse> prependAnchorEvent(SelectionAnchorer.AnchorResult anchor,
            Flux<AiEditResponse> stream) {
        if (anchor == null) {
            return stream;
        }
        AiEditResponse anchorEvent = new AiEditResponse();
        anchorEvent.setType("anchor");
        anchorEvent.setAnchorLines("L" + anchor.startLine() + "-L" + anchor.endLine());
        anchorEvent.setAnchorStatus(anchor.status());
        return Flux.just(anchorEvent).concatWith(stream);
    }

    private boolean isFocusedSpan(int span) {
        // 选区太短（≤5 行，通常是标题/标签），需要全文上下文理解用户意图
        return span > 5 && span <= 20;
    }

    private String prependSchema(Long scopeId, String prompt) {
        if (schemaInjector == null) {
            return prompt;
        }
        return schemaInjector.prepend(scopeId, prompt);
    }

    private Flux<AiEditResponse> executeFocusedEdit(Long scopeId, Long sessionId,
            EditSessionInfo session, String currentContent, AiEditRequest request,
            SelectionAnchorer.AnchorResult anchor) {
        String contextWindow = editContextBuilder.buildFocusedContext(
            currentContent, anchor.startLine(), anchor.endLine());
        String systemPrompt = prependSchema(scopeId, assembleFocusedPrompt(contextWindow, "", false));
        String userMessage = assembleUserMessage(request);

        TokenUsageContext.set(scopeId, "ai-edit-focused");

        return buildStreamingEditFlux(
            chatClient.streamChat(systemPrompt, userMessage),
            systemPrompt, userMessage, currentContent, session, false
        ).doFinally(signal -> TokenUsageContext.clear());
    }

    private Flux<AiEditResponse> executeFullEdit(Long scopeId, Long sessionId,
            EditSessionInfo session, String currentContent, String outline,
            boolean hasSelection, String knowledgeContext, boolean hasConfirmedKnowledge,
            boolean isCreateMode, AiEditRequest request, SelectionAnchorer.AnchorResult anchor) {

        int anchorStart = anchor != null ? anchor.startLine() : 1;
        int anchorEnd = anchor != null ? anchor.endLine() : currentContent.split("\n", -1).length;
        String contextWindow = editContextBuilder.buildFullContext(currentContent, anchorStart, anchorEnd);
        String historySummary = session.getStepCount() > 0
            ? buildHistorySummary(sessionId, session.getStepCount())
            : "";
        String systemPrompt = prependSchema(scopeId, assembleSystemPrompt(outline, contextWindow,
            historySummary, hasSelection, knowledgeContext, hasConfirmedKnowledge, isCreateMode));
        String userMessage = assembleUserMessage(request);

        TokenUsageContext.set(scopeId, "ai-edit");

        return buildStreamingEditFlux(
            chatClient.streamChat(systemPrompt, userMessage),
            systemPrompt, userMessage, currentContent, session, isCreateMode
        ).doFinally(signal -> TokenUsageContext.clear());
    }

    private Flux<AiEditResponse> buildStreamingEditFlux(
            Flux<String> chatStream, String systemPrompt, String userMessage,
            String originalContent, EditSessionInfo session, boolean isCreateMode) {
        return buildRoundFlux(chatStream, originalContent, session, isCreateMode,
            1, systemPrompt, userMessage, List.of());
    }

    private Flux<AiEditResponse> buildRoundFlux(Flux<String> chatStream, String originalContent,
            EditSessionInfo session, boolean isCreateMode, int round,
            String systemPrompt, String userMessage,
            List<SearchReplaceEngine.SearchReplaceBlock> carriedBlocks) {

        String baseDoc = applyCarriedBlocks(originalContent, carriedBlocks);
        StringBuilder buffer = new StringBuilder();
        String[] currentDoc = {baseDoc};
        StringBuilder explanationBuf = new StringBuilder();
        boolean[] inExplanation = {false};
        List<SearchReplaceEngine.SearchReplaceBlock> appliedBlocks = new ArrayList<>();
        int[] handledCount = {0};
        AtomicReference<Flux<AiEditResponse>> nextRound = new AtomicReference<>();

        return chatStream
            .concatWith(Flux.just("\u0000"))
            .flatMap(token -> {
                List<AiEditResponse> events = new ArrayList<>();

                if ("\u0000".equals(token)) {
                    String fullResponse = buffer.toString();
                    String editPart = fullResponse;
                    String explanation = explanationBuf.toString().trim();
                    int explIdx = fullResponse.indexOf(EXPLANATION_SEPARATOR);
                    if (explIdx >= 0) {
                        editPart = fullResponse.substring(0, explIdx);
                        if (explanation.isEmpty()) {
                            explanation = fullResponse
                                .substring(explIdx + EXPLANATION_SEPARATOR.length()).trim();
                        }
                    }

                    if (isCreateMode) {
                        String resultContent = extractNewContent(editPart, originalContent);
                        events.add(buildDoneEvent(session, resultContent, explanation,
                            "create", List.of()));
                        return Flux.fromIterable(events);
                    }

                    List<SearchReplaceEngine.SearchReplaceBlock> roundBlocks =
                        searchReplaceEngine.parseBlocks(editPart);
                    List<SearchReplaceEngine.SearchReplaceBlock> deduped =
                        dedupeCarried(roundBlocks, carriedBlocks);
                    String beforeReparse = currentDoc[0];
                    SearchReplaceEngine.ApplyResult reparse =
                        searchReplaceEngine.applyBlocks(baseDoc, deduped);
                    List<FailedBlockInfo> finalFailures = reparse.failedBlocks();
                    currentDoc[0] = reparse.content();
                    if (!reparse.content().equals(beforeReparse)) {
                        events.add(buildPatchEvent(reparse.content()));
                    }

                    if (!finalFailures.isEmpty() && round < MAX_EDIT_ROUNDS) {
                        AiEditResponse retryEvent = new AiEditResponse();
                        retryEvent.setType("retry");
                        retryEvent.setRetryRound(round + 1);
                        retryEvent.setFailedBlockCount(finalFailures.size());
                        retryEvent.setContent(String.valueOf(finalFailures.size()));
                        events.add(retryEvent);

                        List<SearchReplaceEngine.SearchReplaceBlock> mergedCarried =
                            new ArrayList<>(carriedBlocks);
                        mergedCarried.addAll(successfulBlocks(reparse));
                        String retryMessage = buildRetryUserMessage(userMessage, finalFailures);
                        nextRound.set(buildRoundFlux(
                            chatClient.streamChat(systemPrompt, retryMessage),
                            originalContent, session, false, round + 1,
                            systemPrompt, retryMessage, mergedCarried));
                    } else {
                        events.add(buildDoneEvent(session, currentDoc[0], explanation,
                            "edit", finalFailures));
                    }
                    return Flux.fromIterable(events);
                }

                buffer.append(token);
                String accumulated = buffer.toString();

                if (inExplanation[0]) {
                    explanationBuf.append(token);
                    AiEditResponse event = new AiEditResponse();
                    event.setType("token");
                    event.setContent(token);
                    events.add(event);
                    return Flux.fromIterable(events);
                }

                if (accumulated.contains(EXPLANATION_SEPARATOR)) {
                    inExplanation[0] = true;
                    int sepIdx = accumulated.indexOf(EXPLANATION_SEPARATOR);
                    String editPart = accumulated.substring(0, sepIdx);
                    events.addAll(applyNewCompletedBlocks(editPart, currentDoc,
                        appliedBlocks, handledCount, carriedBlocks));
                    String afterSep = accumulated.substring(sepIdx + EXPLANATION_SEPARATOR.length());
                    if (!afterSep.isEmpty()) {
                        explanationBuf.append(afterSep);
                        AiEditResponse tokenEvent = new AiEditResponse();
                        tokenEvent.setType("token");
                        tokenEvent.setContent(afterSep);
                        events.add(tokenEvent);
                    }
                    return Flux.fromIterable(events);
                }

                events.addAll(applyNewCompletedBlocks(accumulated, currentDoc,
                    appliedBlocks, handledCount, carriedBlocks));
                return Flux.fromIterable(events);
            })
            .concatWith(Flux.defer(() ->
                nextRound.get() != null ? nextRound.get() : Flux.empty()));
    }

    private List<AiEditResponse> applyNewCompletedBlocks(String text, String[] currentDoc,
            List<SearchReplaceEngine.SearchReplaceBlock> appliedBlocks, int[] handledCount,
            List<SearchReplaceEngine.SearchReplaceBlock> carriedBlocks) {
        List<AiEditResponse> events = new ArrayList<>();
        List<SearchReplaceEngine.SearchReplaceBlock> blocks = searchReplaceEngine.parseBlocks(text);
        for (int i = handledCount[0]; i < blocks.size(); i++) {
            SearchReplaceEngine.SearchReplaceBlock block = blocks.get(i);
            handledCount[0]++;
            if (containsBlock(carriedBlocks, block) || containsBlock(appliedBlocks, block)) {
                continue;
            }
            SearchReplaceEngine.ApplyResult result =
                searchReplaceEngine.applyBlocks(currentDoc[0], List.of(block));
            if (result.appliedCount() > 0) {
                currentDoc[0] = result.content();
                appliedBlocks.add(block);
                events.add(buildPatchEvent(result.content()));
            } else {
                for (FailedBlockInfo failure : result.failedBlocks()) {
                    log.warn("Streaming S/R block failed ({}): {}",
                        failure.getReason(), failure.getSearchPreview());
                }
            }
        }
        return events;
    }

    private String applyCarriedBlocks(String originalContent,
            List<SearchReplaceEngine.SearchReplaceBlock> carriedBlocks) {
        if (carriedBlocks.isEmpty()) {
            return originalContent;
        }
        SearchReplaceEngine.ApplyResult result =
            searchReplaceEngine.applyBlocks(originalContent, carriedBlocks);
        if (!result.failedBlocks().isEmpty()) {
            log.warn("Carried block replay failed unexpectedly: failedCount={}",
                result.failedBlocks().size());
        }
        return result.content();
    }

    private List<SearchReplaceEngine.SearchReplaceBlock> dedupeCarried(
            List<SearchReplaceEngine.SearchReplaceBlock> blocks,
            List<SearchReplaceEngine.SearchReplaceBlock> carriedBlocks) {
        List<SearchReplaceEngine.SearchReplaceBlock> result = new ArrayList<>();
        for (SearchReplaceEngine.SearchReplaceBlock block : blocks) {
            if (!containsBlock(carriedBlocks, block) && !containsBlock(result, block)) {
                result.add(block);
            }
        }
        return result;
    }

    private boolean containsBlock(List<SearchReplaceEngine.SearchReplaceBlock> list,
            SearchReplaceEngine.SearchReplaceBlock block) {
        for (SearchReplaceEngine.SearchReplaceBlock candidate : list) {
            if (candidate.search().equals(block.search())
                    && candidate.replace().equals(block.replace())) {
                return true;
            }
        }
        return false;
    }

    private List<SearchReplaceEngine.SearchReplaceBlock> successfulBlocks(
            SearchReplaceEngine.ApplyResult result) {
        List<SearchReplaceEngine.SearchReplaceBlock> successes = new ArrayList<>();
        for (SearchReplaceEngine.BlockOutcome outcome : result.outcomes()) {
            if (outcome.applied()) {
                successes.add(outcome.block());
            }
        }
        return successes;
    }

    private String buildRetryUserMessage(String originalInstruction, List<FailedBlockInfo> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append("上一轮的部分修改未能应用到文档，请修正后重新输出完整的 SEARCH/REPLACE 块。\n\n");
        sb.append("失败的修改：\n");
        for (FailedBlockInfo failure : failures) {
            if ("AMBIGUOUS".equals(failure.getReason())) {
                String lines = failure.getMatchLines() == null ? ""
                    : failure.getMatchLines().stream()
                        .map(String::valueOf).collect(Collectors.joining(","));
                sb.append("- 内容「").append(failure.getSearchPreview())
                    .append("」匹配到 ").append(failure.getMatchCount())
                    .append(" 处相同内容（第 ").append(lines)
                    .append(" 行），需要在 SEARCH 块中加入更多上下文以锁定唯一位置\n");
            } else {
                sb.append("- 内容「").append(failure.getSearchPreview())
                    .append("」未在文档中找到，请核对文档实际内容后重新编写 SEARCH 块\n");
            }
        }
        sb.append("\n原始修改指令：\n").append(originalInstruction);
        return sb.toString();
    }

    private AiEditResponse buildDoneEvent(EditSessionInfo session, String content,
            String explanation, String mode, List<FailedBlockInfo> failedBlocks) {
        AiEditResponse doneEvent = new AiEditResponse();
        doneEvent.setType("done");
        doneEvent.setStepNumber(session.getStepCount() + 1);
        doneEvent.setContent(content);
        doneEvent.setExplanation(explanation);
        doneEvent.setMode(mode);
        doneEvent.setFailedBlocks(failedBlocks);
        doneEvent.setFailedBlockCount(failedBlocks.size());
        return doneEvent;
    }

    private AiEditResponse buildPatchEvent(String currentDocContent) {
        AiEditResponse event = new AiEditResponse();
        event.setType("patch");
        event.setContent(currentDocContent);
        return event;
    }

    public CommitStepResult commitStep(Long sessionId, Long scopeId, String newContent,
                                       String instruction, String selectedLines,
                                       String selectedText, String expectedHash) {
        String currentContent = editSessionService.getCurrentContent(sessionId);

        boolean versionBehind = false;
        if (expectedHash != null && !expectedHash.isBlank() && currentContent != null
                && !expectedHash.equals(DraftService.computeHash(currentContent))) {
            versionBehind = true;
            log.warn("Commit step with version conflict (last-write-wins): sessionId={} expectedHash={}",
                sessionId, expectedHash);
        }

        String diffRemoved = selectedText != null ? selectedText : "";
        String diffAdded = computeDiffAdded(newContent, diffRemoved, currentContent != null ? currentContent : "");
        editSessionService.updateSessionContent(
            sessionId, newContent, diffRemoved, diffAdded, selectedLines, instruction);

        CommitStepResult result = new CommitStepResult();
        result.setSession(editSessionService.getSession(sessionId, scopeId, true));
        result.setVersionBehind(versionBehind);
        return result;
    }

    String assembleFocusedPrompt(String contextWindow, String knowledgeContext, boolean confirmedKnowledge) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Wiki 编辑助手。根据用户指令修改选中区域（>> 标记的行）。\n\n");
        sb.append("规则：\n");
        sb.append("1. 结合指令理解选中内容，决定修改范围\n");
        sb.append("2. 用 Search/Replace 格式输出：\n\n");
        sb.append("<<<<<<< SEARCH\n原文（精确匹配）\n=======\n新文本\n>>>>>>> REPLACE\n\n");
        sb.append("3. SEARCH 必须精确匹配原文\n");
        sb.append("4. 所有编辑完成后，在 ---EXPLANATION--- 分隔符之后，简要说明每处修改的原因和依据\n\n");

        if (knowledgeContext != null && !knowledgeContext.isEmpty()) {
            if (confirmedKnowledge) {
                sb.append("以下是用户确认的参考资料，请优先使用这些材料：\n\n");
            }
            sb.append(knowledgeContext).append("\n\n");
        }

        sb.append(contextWindow);
        return sb.toString();
    }

    public String buildHistorySummary(Long sessionId, int currentStepCount) {
        if (currentStepCount == 0) return "（首次编辑）";

        List<EditStepInfo> steps = editSessionService.listStepsLite(sessionId);
        if (steps.isEmpty()) return "（首次编辑）";

        StringBuilder sb = new StringBuilder();
        sb.append("已进行 ").append(steps.size()).append(" 步编辑。\n");

        int showStart = Math.max(0, steps.size() - MAX_HISTORY_STEPS);
        if (showStart > 0) {
            sb.append("（前 ").append(showStart).append(" 步已折叠）\n");
        }

        for (int i = showStart; i < steps.size(); i++) {
            EditStepInfo step = steps.get(i);
            sb.append("步骤 ").append(step.getStepNumber()).append(": ");
            sb.append(step.getInstruction() != null ? truncate(step.getInstruction(), 80) : "编辑");
            if (step.getSelectedLines() != null && !step.getSelectedLines().isBlank()) {
                sb.append(" [").append(step.getSelectedLines()).append("]");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    String assembleSystemPrompt(String outline, String contextWindow, String history,
                                 boolean hasSelection, String knowledgeContext,
                                 boolean confirmedKnowledge, boolean isCreateMode) {
        StringBuilder sb = new StringBuilder();
        boolean isEmptyDoc = contextWindow == null || contextWindow.isEmpty();

        if (isEmptyDoc && knowledgeContext != null && !knowledgeContext.isEmpty()) {
            sb.append("你是一个专业的 Wiki 文档编辑助手。当前页面内容为空或极少，用户需要你基于知识库中的相关资料来编写新内容。\n\n");
            sb.append("## 规则\n");
            if (confirmedKnowledge) {
                sb.append("1. 以下参考资料已经过用户确认，请优先使用这些材料编写内容，确保准确、有据可查\n");
            } else {
                sb.append("1. 优先使用下方「知识库参考资料」中的信息来编写内容，确保内容准确、有据可查\n");
            }
            sb.append("2. 如果参考资料不足以完成用户指令，可以结合通用知识补充，但要区分引用来源和自行补充的内容\n");
            sb.append("3. 使用 Markdown 格式直接输出完整文档内容（因为文档为空，无需 Search/Replace 格式）\n");
            sb.append("4. 保持结构清晰，使用合理的标题层级\n");
            sb.append("5. 在适当位置使用 `[[页面名]]` 格式引用知识库中的相关页面\n\n");
        } else {
            sb.append("你是一个专业的 Wiki 文档编辑助手。你的任务是根据用户指令精确修改 Markdown 文档。\n\n");
            sb.append("## 规则\n");
            if (hasSelection) {
                sb.append("1. 用户选中了部分文本（用 `>>` 标记的行）作为参考定位点，帮助你理解编辑意图\n");
                sb.append("2. 选中的文本不一定是要修改的区域 — 请根据指令意图决定实际修改范围，可能涉及文档的任何部分\n");
                sb.append("3. 你拥有完整文档内容，不要说\"缺少上下文\"或\"无法定位\"，请直接根据全文执行修改\n");
            } else {
                sb.append("1. 根据用户指令修改文档，只改需要改的部分\n");
                sb.append("2. 保持文档其余内容不变\n");
                sb.append("3. ");
            }
            sb.append("使用以下 Search/Replace 格式输出修改操作，每组修改用三个标记分隔：\n\n");
            sb.append("   <<<<<<< SEARCH\n");
            sb.append("   要替换的原始文本（必须与文档中的内容完全匹配）\n");
            sb.append("   =======\n");
            sb.append("   替换后的新文本\n");
            sb.append("   >>>>>>> REPLACE\n\n");
            sb.append(hasSelection ? "4" : "3");
            sb.append(". 可以多组 SEARCH/REPLACE，按从上到下的顺序依次应用\n");
            sb.append(hasSelection ? "5" : "4");
            sb.append(". SEARCH 块中的文本必须与原文**精确匹配**（包括空格和换行）\n");
            sb.append(hasSelection ? "6" : "5");
            sb.append(". 保持原有的 Markdown 格式和标题层级\n");
            sb.append(hasSelection ? "7" : "6");
            sb.append(". 不要添加不必要的注释或解释\n");
            sb.append(hasSelection ? "8" : "7");
            sb.append(". **绝对不要输出解释性文字代替编辑结果** — 必须输出 Search/Replace 格式的修改操作\n\n");

            if (!isCreateMode) {
                sb.append(hasSelection ? "9" : "8");
                sb.append(". 所有编辑完成后，在 `---EXPLANATION---` 分隔符之后，简要说明每处修改的原因和依据\n\n");
            }

            if (knowledgeContext != null && !knowledgeContext.isEmpty()) {
                int knowledgeRuleNum = isCreateMode ? (hasSelection ? 9 : 8) : (hasSelection ? 10 : 9);
                if (confirmedKnowledge) {
                    sb.append(knowledgeRuleNum).append(". 以下参考资料已经过用户确认，请在编辑中优先参考和使用这些材料\n\n");
                } else {
                    sb.append(knowledgeRuleNum).append(". 下方提供了知识库中的相关资料，如果用户指令涉及引用、关联或补充内容，请优先参考这些资料\n\n");
                }
            }
        }

        if (knowledgeContext != null && !knowledgeContext.isEmpty()) {
            sb.append(knowledgeContext).append("\n\n");
        }

        if (!outline.isBlank()) {
            sb.append("## 文档大纲\n");
            sb.append(outline).append("\n\n");
        }

        if (!isEmptyDoc) {
            sb.append("## 文档内容（带行号");
            if (hasSelection) {
                sb.append("，>> 标记选中区域");
            }
            sb.append("）\n");
            sb.append(contextWindow).append("\n\n");
        }

        if (!history.isBlank()) {
            sb.append("## 编辑历史\n");
            sb.append(history).append("\n\n");
        }

        return sb.toString();
    }

    String assembleUserMessage(AiEditRequest request) {
        StringBuilder sb = new StringBuilder();

        if (request.getSelectedText() != null && !request.getSelectedText().isBlank()) {
            sb.append("选中的文本");
            if (request.getSelectedLines() != null) {
                sb.append("（").append(request.getSelectedLines()).append("）");
            }
            sb.append("：\n> ").append(request.getSelectedText().replace("\n", "\n> ")).append("\n\n");
        }

        sb.append("编辑指令：").append(request.getInstruction());
        return sb.toString();
    }

    String extractNewContent(String aiResponse, String currentContent) {
        if (aiResponse == null || aiResponse.isBlank()) {
            return currentContent;
        }

        String editPart = aiResponse;
        int explIdx = aiResponse.indexOf(EXPLANATION_SEPARATOR);
        if (explIdx >= 0) {
            editPart = aiResponse.substring(0, explIdx);
        }

        if (containsUnparsedSearchReplaceMarkers(editPart)) {
            log.warn("AI response contains unparsed SEARCH/REPLACE markers, keeping original content unchanged");
            return currentContent;
        }

        Matcher matcher = FENCE_PATTERN.matcher(editPart);
        if (matcher.find()) {
            String extracted = matcher.group(1).trim();
            if (isSafeReplacement(extracted, currentContent)) {
                return extracted;
            }
            log.warn("Extracted content from fenced block too short ({} chars vs {} original), keeping original", extracted.length(), currentContent.length());
            return currentContent;
        }

        Pattern plainFence = Pattern.compile("```\\s*\\n([\\s\\S]*?)```", Pattern.MULTILINE);
        Matcher plainMatcher = plainFence.matcher(editPart);
        if (plainMatcher.find()) {
            String extracted = plainMatcher.group(1).trim();
            if (isSafeReplacement(extracted, currentContent)) {
                return extracted;
            }
            log.warn("Extracted content from plain fence too short ({} chars vs {} original), keeping original", extracted.length(), currentContent.length());
            return currentContent;
        }

        String trimmed = editPart.trim();
        if (isSafeReplacement(trimmed, currentContent)) {
            return trimmed;
        }
        log.warn("AI response too short to be a valid edit ({} chars vs {} original), keeping original content unchanged", trimmed.length(), currentContent.length());
        return currentContent;
    }

    private static final Pattern SR_MARKER_PATTERN = Pattern.compile(
        "<{4,}\\s*SEARCH|>{4,}\\s*REPLACE|^={4,}\\s*$",
        Pattern.MULTILINE);

    boolean containsUnparsedSearchReplaceMarkers(String text) {
        return SR_MARKER_PATTERN.matcher(text).find();
    }

    boolean isSafeReplacement(String newContent, String currentContent) {
        if (currentContent == null || currentContent.length() < 100) {
            return true;
        }
        return newContent.length() >= currentContent.length() / 3;
    }

    String computeDiffAdded(String newContent, String diffRemoved, String currentContent) {
        if (newContent.equals(currentContent)) {
            return "";
        }
        if (diffRemoved == null || diffRemoved.isEmpty()) {
            return newContent;
        }

        int oldIdx = currentContent.indexOf(diffRemoved);
        if (oldIdx < 0) {
            return newContent;
        }

        String before = currentContent.substring(0, oldIdx);
        String after = currentContent.substring(oldIdx + diffRemoved.length());

        int newStart = before.length();
        int newEnd = newContent.length() - after.length();

        if (newStart >= 0 && newEnd >= newStart && newEnd <= newContent.length()) {
            return newContent.substring(newStart, newEnd);
        }
        return newContent;
    }

    boolean isContentEmptyOrMinimal(String content) {
        if (content == null || content.isBlank()) return true;
        String stripped = content.replaceAll("[#*_\\->\\[\\]`~\\s]+", "");
        return stripped.length() < EMPTY_CONTENT_THRESHOLD;
    }

    String buildKnowledgeContextFromPages(Long scopeId, List<Long> pageIds, Long currentPageId) {
        if (wikiFileService == null || pageIds == null || pageIds.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 知识库参考资料（用户已确认）\n\n");

        int charBudget = MAX_KNOWLEDGE_CONTEXT_CHARS;
        int index = 1;
        for (Long pageId : pageIds) {
            if (currentPageId != null && currentPageId.equals(pageId)) continue;

            WikiPageModel page = wikiFileService.readPageById(pageId, scopeId);
            if (page == null || page.getContent() == null) continue;

            String entry = formatPageAsKnowledge(index, page);
            if (charBudget - entry.length() < 0 && index > 1) break;

            sb.append(entry);
            charBudget -= entry.length();
            index++;
        }

        return index > 1 ? sb.toString() : "";
    }

    String formatPageAsKnowledge(int index, WikiPageModel page) {
        StringBuilder sb = new StringBuilder();
        sb.append("### [").append(index).append("] ").append(page.getTitle()).append("\n");
        if (page.getCategory() != null && !page.getCategory().isBlank()) {
            sb.append("分类: ").append(page.getCategory()).append("\n");
        }
        String content = truncate(page.getContent(), MAX_KNOWLEDGE_PAGE_CHARS);
        sb.append(content).append("\n\n");
        if (page.getPath() != null) {
            sb.append("页面路径: [[").append(page.getPath()).append("]]\n\n");
        }
        return sb.toString();
    }

    private AiEditResponse errorEvent(String message) {
        AiEditResponse event = new AiEditResponse();
        event.setType("error");
        event.setContent(message);
        return event;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n... (truncated)";
    }
}
