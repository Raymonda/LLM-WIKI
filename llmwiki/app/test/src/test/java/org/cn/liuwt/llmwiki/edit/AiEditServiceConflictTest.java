package org.cn.liuwt.llmwiki.edit;

import org.cn.liuwt.llmwiki.domain.service.harness.edit.AiEditService;
import org.cn.liuwt.llmwiki.domain.service.harness.edit.EditContextBuilder;
import org.cn.liuwt.llmwiki.domain.service.harness.edit.SearchReplaceEngine;
import org.cn.liuwt.llmwiki.domain.service.harness.edit.SelectionAnchorer;
import org.cn.liuwt.llmwiki.domain.service.wiki.DraftService;
import org.cn.liuwt.llmwiki.domain.service.wiki.EditSessionService;
import org.cn.liuwt.llmwiki.facade.model.AiEditRequest;
import org.cn.liuwt.llmwiki.facade.model.AiEditResponse;
import org.cn.liuwt.llmwiki.facade.model.CommitStepResult;
import org.cn.liuwt.llmwiki.facade.model.EditSessionInfo;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiEditServiceConflictTest {

    private AiEditService service;
    private EditSessionService sessionService;
    private LlmClient client;

    @BeforeEach
    void setUp() {
        service = new AiEditService();
        sessionService = mock(EditSessionService.class);
        client = mock(LlmClient.class);
        SearchReplaceEngine engine = new SearchReplaceEngine();
        ReflectionTestUtils.setField(service, "editSessionService", sessionService);
        ReflectionTestUtils.setField(service, "chatClient", client);
        ReflectionTestUtils.setField(service, "searchReplaceEngine", engine);
        ReflectionTestUtils.setField(service, "selectionAnchorer", new SelectionAnchorer(engine));
        ReflectionTestUtils.setField(service, "editContextBuilder", new EditContextBuilder(24000));
        when(client.isAvailable()).thenReturn(true);
    }

    private EditSessionInfo mockSession() {
        EditSessionInfo session = new EditSessionInfo();
        session.setId(1L);
        session.setCurrentContent("# Title\nhello\n"
            + "This page describes the greeting workflow used by the demo wiki project, "
            + "covering the greeting rules and examples.");
        session.setContentHash("hash-v2");
        session.setStepCount(0);
        when(sessionService.getSession(1L, 10L, true)).thenReturn(session);
        return session;
    }

    @Test
    void editRejectedWhenExpectedHashMismatch() {
        mockSession();
        AiEditRequest request = new AiEditRequest();
        request.setSessionId(1L);
        request.setInstruction("改写问候语");
        request.setExpectedHash("hash-v1");

        List<AiEditResponse> events = service.editWithContext(10L, request)
            .collectList().block();

        assertEquals(1, events.size());
        assertEquals("error", events.get(0).getType());
        assertEquals("HASH_CONFLICT", events.get(0).getCode());
        verify(client, never()).streamChat(anyString(), anyString());
    }

    @Test
    void anchorEventEmittedBeforeEditStream() {
        mockSession();
        when(client.streamChat(anyString(), anyString())).thenReturn(Flux.empty());

        AiEditRequest request = new AiEditRequest();
        request.setSessionId(1L);
        request.setInstruction("把 hello 改成你好");
        request.setSelectedLines("L2");
        request.setSelectedText("hello");
        request.setExpectedHash("hash-v2");

        List<AiEditResponse> events = service.editWithContext(10L, request)
            .collectList().block();

        assertEquals("anchor", events.get(0).getType());
        assertEquals("L2-L2", events.get(0).getAnchorLines());
        assertEquals("exact", events.get(0).getAnchorStatus());
    }

    @Test
    void multiRoundRetryAppliesCorrectedBlock() {
        mockSession();
        String round1 = "<<<<<<< SEARCH\nhelll\n=======\n你好\n>>>>>>> REPLACE\n---EXPLANATION---\n修正拼写";
        String round2 = "<<<<<<< SEARCH\nhello\n=======\n你好\n>>>>>>> REPLACE\n---EXPLANATION---\n已修正";
        when(client.streamChat(anyString(), anyString()))
            .thenReturn(Flux.just(round1))
            .thenReturn(Flux.just(round2));

        AiEditRequest request = new AiEditRequest();
        request.setSessionId(1L);
        request.setInstruction("把 hello 改成你好");
        request.setSelectedLines("L2");
        request.setSelectedText("hello");
        request.setExpectedHash("hash-v2");

        List<AiEditResponse> events = service.editWithContext(10L, request)
            .collectList().block();

        List<String> types = events.stream().map(AiEditResponse::getType).toList();
        assertEquals(List.of("anchor", "token", "retry", "patch", "token", "done"), types);
        AiEditResponse retry = events.get(2);
        assertEquals(2, retry.getRetryRound());
        assertEquals(1, retry.getFailedBlockCount());
        AiEditResponse done = events.get(5);
        assertEquals("# Title\n你好\n"
            + "This page describes the greeting workflow used by the demo wiki project, "
            + "covering the greeting rules and examples.", done.getContent());
        assertEquals(0, done.getFailedBlockCount());
        assertEquals(0, done.getFailedBlocks().size());
    }

    @Test
    void carriedBlocksSurvivePreviewCollisionOnRetry() {
        EditSessionInfo session = new EditSessionInfo();
        session.setId(1L);
        session.setCurrentContent("# Title\nhello\n"
            + "alpha bravo charlie delta echo foxtrot golf for enough length here\n"
            + "hello\ntail-line\nhello\n");
        session.setContentHash("hash-v2");
        session.setStepCount(0);
        when(sessionService.getSession(1L, 10L, true)).thenReturn(session);

        String round1 = "<<<<<<< SEARCH\nhello\nalpha bravo charlie delta echo foxtrot golf for enough length here\n"
            + "=======\nhi\nalpha bravo charlie delta echo foxtrot golf for enough length here\n>>>>>>> REPLACE\n"
            + "<<<<<<< SEARCH\nhello\n=======\n你好\n>>>>>>> REPLACE\n---EXPLANATION---\n修正";
        String round2 = "<<<<<<< SEARCH\nhello\ntail-line\n=======\n你好\ntail-line\n>>>>>>> REPLACE\n"
            + "---EXPLANATION---\n已修正";
        when(client.streamChat(anyString(), anyString()))
            .thenReturn(Flux.just(round1))
            .thenReturn(Flux.just(round2));

        AiEditRequest request = new AiEditRequest();
        request.setSessionId(1L);
        request.setInstruction("把 hello 改成你好");
        request.setExpectedHash("hash-v2");

        List<AiEditResponse> events = service.editWithContext(10L, request)
            .collectList().block();

        List<String> types = events.stream().map(AiEditResponse::getType).toList();
        assertEquals(List.of("patch", "token", "retry", "patch", "token", "done"), types);
        AiEditResponse retry = events.get(2);
        assertEquals(2, retry.getRetryRound());
        assertEquals(1, retry.getFailedBlockCount());
        AiEditResponse done = events.get(5);
        assertEquals("# Title\nhi\n"
            + "alpha bravo charlie delta echo foxtrot golf for enough length here\n"
            + "你好\ntail-line\nhello\n", done.getContent());
        assertEquals(0, done.getFailedBlockCount());
    }

    @Test
    void commitStepLastWriteWinsOnHashMismatch() {
        EditSessionInfo session = mockSession();
        when(sessionService.getCurrentContent(1L)).thenReturn("# Title\nhello");
        doAnswer(invocation -> {
            session.setCurrentContent(invocation.getArgument(1));
            return null;
        }).when(sessionService).updateSessionContent(any(), any(), any(), any(), any(), any());

        CommitStepResult result = service.commitStep(1L, 10L, "# Title\n你好",
            "改写问候语", "L2", "hello", "stale-hash");

        assertEquals(true, result.isVersionBehind());
        assertEquals("# Title\n你好", result.getSession().getCurrentContent());
        verify(sessionService).updateSessionContent(
            any(), any(), any(), any(), any(), any());
    }

    @Test
    void commitStepSucceedsWhenHashMatches() {
        mockSession();
        when(sessionService.getCurrentContent(1L)).thenReturn("# Title\nhello");
        String matchingHash = DraftService.computeHash("# Title\nhello");

        CommitStepResult result = service.commitStep(1L, 10L, "# Title\n你好",
            "改写问候语", "L2", "hello", matchingHash);

        assertEquals(false, result.isVersionBehind());
        verify(sessionService).updateSessionContent(
            any(), any(), any(), any(), any(), any());
    }
}
