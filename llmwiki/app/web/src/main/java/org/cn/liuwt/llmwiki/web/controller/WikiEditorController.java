package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.service.harness.edit.AiEditService;
import org.cn.liuwt.llmwiki.domain.service.wiki.DraftService;
import org.cn.liuwt.llmwiki.domain.service.wiki.EditSessionService;
import org.cn.liuwt.llmwiki.domain.service.wiki.PageSaveService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.facade.model.*;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/wiki")
public class WikiEditorController {

    private static final Logger log = LoggerFactory.getLogger(WikiEditorController.class);

    @Autowired
    private DraftService draftService;

    @Autowired
    private EditSessionService editSessionService;

    @Autowired
    private AiEditService aiEditService;

    @Autowired
    private PageSaveService pageSaveService;

    @Autowired
    private WikiFileServiceImpl wikiFileService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // ==================== 草稿 CRUD ====================

    @PostMapping("/drafts")
    public Result<WikiPageDraftInfo> createDraft(@RequestBody CreateDraftRequest request) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        Long userId = jwtTokenProvider.getCurrentUserId();
        return Result.success(draftService.createDraft(scopeId, userId, request));
    }

    @GetMapping("/drafts")
    public Result<List<WikiPageDraftInfo>> listDrafts() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        return Result.success(draftService.listDrafts(scopeId));
    }

    @GetMapping("/drafts/{id}")
    public Result<WikiPageDraftInfo> getDraft(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        return Result.success(draftService.getDraft(id, scopeId));
    }

    @PutMapping("/drafts/{id}")
    public Result<WikiPageDraftInfo> updateDraft(@PathVariable Long id, @RequestBody UpdateDraftRequest request) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        return Result.success(draftService.updateDraft(id, scopeId, request));
    }

    @DeleteMapping("/drafts/{id}")
    public Result<Void> deleteDraft(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        draftService.deleteDraft(id, scopeId);
        return Result.success(null);
    }

    // ==================== 编辑会话 ====================

    @PostMapping("/edit-sessions")
    public Result<EditSessionInfo> createSession(@RequestBody Map<String, Object> body) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        Long userId = jwtTokenProvider.getCurrentUserId();
        Long pageId = body.get("pageId") != null ? Long.valueOf(body.get("pageId").toString()) : null;
        Long draftId = body.get("draftId") != null ? Long.valueOf(body.get("draftId").toString()) : null;

        String content = null;
        if (pageId != null) {
            var page = wikiFileService.readPageById(pageId, scopeId);
            if (page != null) content = page.getContent();
        }
        if (content == null && draftId != null) {
            var draft = draftService.getDraft(draftId, scopeId);
            if (draft != null) content = draft.getContent();
        }
        if (content == null) {
            content = "";
        }

        return Result.success(editSessionService.createSession(scopeId, userId, pageId, draftId, content));
    }

    @GetMapping("/edit-sessions/{id}")
    public Result<EditSessionInfo> getSession(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        return Result.success(editSessionService.getSession(id, scopeId, true));
    }

    @DeleteMapping("/edit-sessions/{id}")
    public Result<Void> abandonSession(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        editSessionService.abandonSession(id, scopeId);
        return Result.success(null);
    }

    // ==================== AI 编辑（SSE 流式）====================

    @PostMapping(value = "/ai/edit", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter aiEdit(@RequestBody AiEditRequest request) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        SseEmitter emitter = new SseEmitter(300000L);

        emitter.onCompletion(() -> log.debug("AI edit SSE completed for sessionId={}", request.getSessionId()));
        emitter.onTimeout(() -> log.warn("AI edit SSE timed out for sessionId={}", request.getSessionId()));
        emitter.onError(e -> log.warn("AI edit SSE error: {}", e.getMessage()));

        aiEditService.editWithContext(scopeId, request)
            .subscribe(
                event -> {
                    try {
                        emitter.send(SseEmitter.event()
                            .name(event.getType())
                            .data(event));
                    } catch (Exception e) {
                        log.debug("Failed to send SSE event: {}", e.getMessage());
                    }
                },
                error -> {
                    try {
                        AiEditResponse errEvt = new AiEditResponse();
                        errEvt.setType("error");
                        errEvt.setContent(error.getMessage());
                        emitter.send(SseEmitter.event().name("error").data(errEvt));
                        emitter.complete();
                    } catch (Exception ignored) {
                        emitter.completeWithError(error);
                    }
                },
                emitter::complete
            );

        return emitter;
    }

    // ==================== 步骤管理 ====================

    @PostMapping("/edit-sessions/{id}/commit-step")
    public Result<CommitStepResult> commitStep(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        String newContent = (String) body.get("newContent");
        String instruction = (String) body.get("instruction");
        String selectedLines = (String) body.get("selectedLines");
        String selectedText = (String) body.get("selectedText");
        String expectedHash = (String) body.get("expectedHash");
        return Result.success(aiEditService.commitStep(id, scopeId, newContent, instruction,
            selectedLines, selectedText, expectedHash));
    }

    @GetMapping("/edit-sessions/{id}/steps")
    public Result<List<EditStepInfo>> listSteps(@PathVariable Long id) {
        return Result.success(editSessionService.listSteps(id));
    }

    @PostMapping("/edit-sessions/{id}/steps/{stepId}/undo")
    public Result<EditSessionInfo> undoStep(@PathVariable Long id, @PathVariable Long stepId) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        editSessionService.undoStep(id, stepId, scopeId);
        return Result.success(editSessionService.getSession(id, scopeId, true));
    }

    @PostMapping("/edit-sessions/{id}/undo-all")
    public Result<EditSessionInfo> undoAll(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        editSessionService.undoAll(id, scopeId);
        return Result.success(editSessionService.getSession(id, scopeId, true));
    }

    // ==================== 保存 / 发布 ====================

    @PostMapping("/pages/save")
    public Result<WikiPageInfo> savePage(@RequestBody SavePageRequest request) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        Long userId = jwtTokenProvider.getCurrentUserId();
        return Result.success(pageSaveService.save(scopeId, userId, request));
    }

    @PostMapping("/pages/pre-validate")
    public Result<Map<String, Object>> preValidate(@RequestBody Map<String, String> body) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        return Result.success(pageSaveService.preValidate(
            scopeId,
            body.get("title"),
            body.get("content"),
            body.get("category")
        ));
    }

    // ==================== AI 可用性 ====================

    @GetMapping("/ai/status")
    public Result<Map<String, Object>> aiStatus() {
        return Result.success(Map.of("available", aiEditService.isAvailable()));
    }
}
