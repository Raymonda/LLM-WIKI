package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.modify.PageModifyOrchestrator;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Service
public class PageModifyService {

    private static final Logger log = LoggerFactory.getLogger(PageModifyService.class);

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private PageModifyOrchestrator pageModifyOrchestrator;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private SourceMapper sourceMapper;

    public SourceDO createUserFeedbackSource(Long scopeId, Long pageId, Long userId, String instruction) {
        WikiPageDO page = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getId, pageId)
                .eq(WikiPageDO::getScopeId, scopeId)
        );
        String pageTitle = page != null ? page.getTitle() : "Unknown";

        SourceDO sourceDO = new SourceDO();
        sourceDO.setName("用户修改指令 - " + pageTitle);
        sourceDO.setFormat("USER_FEEDBACK");
        sourceDO.setFilePath("");
        sourceDO.setSize((long) instruction.getBytes(StandardCharsets.UTF_8).length);
        sourceDO.setStatus("processed");
        sourceDO.setScopeId(scopeId);
        sourceDO.setUploadUserId(userId);
        sourceDO.setCreatedAt(LocalDateTime.now());
        sourceMapper.insert(sourceDO);
        return sourceDO;
    }

    public ExecutionModel createModifyExecution(Long scopeId, Long pageId, Long sourceId) {
        ExecutionModel execution = executionTracker.createExecution("page_modify", scopeId, sourceId, null);
        executionTracker.updateExecutionStatus(execution.getId(), "running");
        return execution;
    }

    @Async
    public void runModifyPipeline(Long executionId, Long scopeId, Long pageId,
                                   String instruction, Long sourceId, boolean skipComplianceCheck) {
        pageModifyOrchestrator.runPipeline(executionId, scopeId, pageId, instruction, sourceId, skipComplianceCheck);
    }
}
