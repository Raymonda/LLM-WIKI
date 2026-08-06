package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageKeywordDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageSourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageTagDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageKeywordMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageLinkMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageTagMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.cn.liuwt.llmwiki.domain.service.harness.HarnessEngine;
import org.cn.liuwt.llmwiki.domain.service.harness.PipelineOrchestrator;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.RateLimitService;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private PipelineOrchestrator pipelineOrchestrator;

    @Autowired
    private HarnessEngine harnessEngine;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private WikiPageSourceMapper wikiPageSourceMapper;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageTagMapper wikiPageTagMapper;

    @Autowired
    private WikiPageKeywordMapper wikiPageKeywordMapper;

    @Autowired
    private WikiPageLinkMapper wikiPageLinkMapper;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private SearchService searchService;

    @Autowired
    private LintFindingService lintFindingService;

    public ExecutionModel createExecution(Long scopeId, Long sourceId) {
        ExecutionModel execution = executionTracker.createExecution("ingest", scopeId, sourceId, null);
        executionTracker.updateExecutionStatus(execution.getId(), "running");
        return execution;
    }

    public ExecutionModel runIngestPipeline(Long executionId, Long scopeId, Long sourceId, String guidance) {
        return pipelineOrchestrator.runIngestPipelineWithExecution(executionId, scopeId, sourceId, guidance);
    }

    public ExecutionModel runIngestAnalysis(Long executionId, Long scopeId, Long sourceId, String guidance) {
        return pipelineOrchestrator.runIngestAnalysisWithExecution(executionId, scopeId, sourceId, guidance);
    }

    public ExecutionModel runIngestExecution(Long executionId, Long scopeId, Long sourceId, String guidance) {
        return pipelineOrchestrator.runIngestExecution(executionId, scopeId, sourceId, guidance);
    }

    public ExecutionModel resumeIngestAnalysis(Long executionId, Long scopeId, Long sourceId, String guidance) {
        return pipelineOrchestrator.resumeIngestAnalysis(executionId, scopeId, sourceId, guidance);
    }

    public ExecutionModel resumeIngestExecution(Long executionId, Long scopeId, Long sourceId, String guidance) {
        return pipelineOrchestrator.resumeIngestExecution(executionId, scopeId, sourceId, guidance);
    }

    public void failExecution(Long executionId) {
        executionTracker.failExecution(executionId, "Pipeline 执行失败");
        rollbackSourceStatus(executionId);
    }

    public void failExecution(Long executionId, String errorMessage) {
        executionTracker.failExecution(executionId, errorMessage);
        rollbackSourceStatus(executionId);
    }

    private void rollbackSourceStatus(Long executionId) {
        ExecutionModel exec = executionTracker.getExecution(executionId);
        if (exec == null || exec.getSourceId() == null) return;
        SourceDO sourceDO = sourceMapper.selectById(exec.getSourceId());
        if (sourceDO != null && "processing".equals(sourceDO.getStatus())) {
            sourceDO.setStatus("uploaded");
            sourceMapper.updateById(sourceDO);
            log.info("Rolled back source status to 'uploaded' for sourceId={} after executionId={} failed", sourceDO.getId(), executionId);
        }
    }

    public void cancelExecution(Long executionId, Long scopeId) {
        ExecutionModel exec = executionTracker.getExecution(executionId);
        if (exec == null) return;

        executionTracker.cancelExecution(executionId, "用户手动取消");

        List<ExecutionStepModel> steps = executionTracker.listSteps(executionId);
        for (ExecutionStepModel step : steps) {
            if ("running".equals(step.getStatus())) {
                executionTracker.updateStepStatus(step.getId(), "cancelled");
            }
        }

        cleanupHalfProducts(scopeId, exec.getSourceId());

        SourceDO sourceDO = sourceMapper.selectById(exec.getSourceId());
        if (sourceDO != null && !"processed".equals(sourceDO.getStatus())) {
            sourceDO.setStatus("cancelled");
            sourceMapper.updateById(sourceDO);
        }

        rateLimitService.unregisterPipeline(scopeId);

        log.info("Execution id={} cancelled and cleaned up for scopeId={}", executionId, scopeId);
    }

    public void pauseExecution(Long executionId, Long scopeId) {
        ExecutionModel exec = executionTracker.getExecution(executionId);
        if (exec == null) return;

        executionTracker.pauseExecution(executionId, "用户手动暂停");

        List<ExecutionStepModel> steps = executionTracker.listSteps(executionId);
        for (ExecutionStepModel step : steps) {
            if ("running".equals(step.getStatus())) {
                executionTracker.updateStepStatus(step.getId(), "paused");
            }
        }

        log.info("Execution id={} paused for scopeId={}", executionId, scopeId);
    }

    private void cleanupHalfProducts(Long scopeId, Long sourceId) {
        String scopeIdStr = String.valueOf(scopeId);

        List<WikiPageSourceDO> relations = wikiPageSourceMapper.selectList(
            new LambdaQueryWrapper<WikiPageSourceDO>()
                .eq(WikiPageSourceDO::getScopeId, scopeId)
                .eq(WikiPageSourceDO::getSourceId, sourceId)
        );

        for (WikiPageSourceDO rel : relations) {
            WikiPageDO page = wikiPageMapper.selectById(rel.getPageId());
            if (page == null) continue;

            if (page.getSourceCount() != null && page.getSourceCount() <= 1) {
                String filePath = page.getFilePath();
                if (filePath != null) {
                    try {
                        storageProvider.delete(scopeIdStr, "wiki/" + filePath);
                        log.info("Deleted half-product file: wiki/{} for scopeId={}", filePath, scopeId);
                    } catch (Exception e) {
                        log.warn("Failed to delete half-product file: wiki/{}, error: {}", filePath, e.getMessage());
                    }
                }

                wikiPageTagMapper.delete(
                    new LambdaQueryWrapper<WikiPageTagDO>()
                        .eq(WikiPageTagDO::getScopeId, scopeId)
                        .eq(WikiPageTagDO::getPageId, page.getId())
                );
                wikiPageKeywordMapper.delete(
                    new LambdaQueryWrapper<WikiPageKeywordDO>()
                        .eq(WikiPageKeywordDO::getScopeId, scopeId)
                        .eq(WikiPageKeywordDO::getPageId, page.getId())
                );
                wikiPageLinkMapper.delete(
                    new LambdaQueryWrapper<WikiPageLinkDO>()
                        .eq(WikiPageLinkDO::getScopeId, scopeId)
                        .and(w -> w.eq(WikiPageLinkDO::getFromPageId, page.getId())
                            .or().eq(WikiPageLinkDO::getToPageId, page.getId()))
                );
                wikiPageMapper.deleteById(page.getId());

                try {
                    searchService.removePage(scopeId, page.getId());
                } catch (Exception e) {
                    log.warn("Failed to remove page from search index: pageId={}, error: {}", page.getId(), e.getMessage());
                }

                log.info("Deleted half-product page: id={}, title={}, path={}", page.getId(), page.getTitle(), filePath);
            } else {
                wikiPageSourceMapper.deleteById(rel.getId());
                page.setSourceCount(page.getSourceCount() - 1);
                wikiPageMapper.updateById(page);
                log.info("Removed source relation from multi-source page: pageId={}, sourceId={}", page.getId(), sourceId);
            }
        }

        wikiPageSourceMapper.delete(
            new LambdaQueryWrapper<WikiPageSourceDO>()
                .eq(WikiPageSourceDO::getScopeId, scopeId)
                .eq(WikiPageSourceDO::getSourceId, sourceId)
        );
    }

    public ExecutionModel getProgress(Long executionId) {
        return harnessEngine.getExecution(executionId);
    }

    public ExecutionModel getResult(Long executionId) {
        return harnessEngine.getExecution(executionId);
    }

    @Async("staleRepairExecutor")
    public void triggerAsyncIngest(Long scopeId, Long sourceId, Long repairExecutionId, String guidance) {
        log.info("Async stale repair Ingest started: scopeId={}, sourceId={}, repairExecutionId={}", scopeId, sourceId, repairExecutionId);
        try {
            runIngestPipeline(repairExecutionId, scopeId, sourceId, guidance);
            log.info("Async stale repair Ingest completed: scopeId={}, sourceId={}, repairExecutionId={}", scopeId, sourceId, repairExecutionId);
        } catch (Exception e) {
            log.error("Async stale repair Ingest failed: scopeId={}, sourceId={}, repairExecutionId={}, error={}", scopeId, sourceId, repairExecutionId, e.getMessage());
            rollbackRepairingFindings(repairExecutionId);
            throw e;
        }
    }

    private void rollbackRepairingFindings(Long repairExecutionId) {
        List<LintFindingDO> repairingFindings = lintFindingService.findByRepairExecutionId(repairExecutionId);
        for (LintFindingDO f : repairingFindings) {
            lintFindingService.markRepairFailed(f.getId());
        }
        if (!repairingFindings.isEmpty()) {
            log.info("Rolled back {} repairing findings for failed executionId={}", repairingFindings.size(), repairExecutionId);
        }
    }
}