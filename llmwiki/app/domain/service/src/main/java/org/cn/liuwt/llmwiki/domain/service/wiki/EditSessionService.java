package org.cn.liuwt.llmwiki.domain.service.wiki;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.EditSessionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.EditStepDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.EditSessionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.EditStepMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.facade.model.EditSessionInfo;
import org.cn.liuwt.llmwiki.facade.model.EditStepInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class EditSessionService {

    private static final Logger log = LoggerFactory.getLogger(EditSessionService.class);

    @Autowired
    private EditSessionMapper sessionMapper;

    @Autowired
    private EditStepMapper stepMapper;

    public EditSessionInfo createSession(Long scopeId, Long userId, Long pageId, Long draftId, String content) {
        String hash = DraftService.computeHash(content);
        String outline = buildOutline(content);

        EditSessionDO session = new EditSessionDO();
        session.setScopeId(scopeId);
        session.setPageId(pageId);
        session.setDraftId(draftId);
        session.setCurrentContent(content);
        session.setOriginalContent(content);
        session.setContentHash(hash);
        session.setOutline(outline);
        session.setHistorySummary("");
        session.setStepCount(0);
        session.setStatus("active");
        session.setCreatedBy(userId);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        sessionMapper.insert(session);
        return toInfo(session, true);
    }

    public EditSessionInfo getSession(Long sessionId, Long scopeId, boolean includeContent) {
        EditSessionDO session = sessionMapper.selectOne(
            new LambdaQueryWrapper<EditSessionDO>()
                .eq(EditSessionDO::getId, sessionId)
                .eq(EditSessionDO::getScopeId, scopeId)
        );
        if (session == null) {
            throw new BusinessException(ErrorCode.WIKI_EDIT_SESSION_NOT_FOUND);
        }
        return toInfo(session, includeContent);
    }

    public String getCurrentContent(Long sessionId) {
        EditSessionDO session = sessionMapper.selectOne(
            new LambdaQueryWrapper<EditSessionDO>()
                .select(EditSessionDO::getCurrentContent)
                .eq(EditSessionDO::getId, sessionId)
        );
        return session != null ? session.getCurrentContent() : null;
    }

    public void updateSessionContent(Long sessionId, String newContent, String diffRemoved, String diffAdded,
                                      String selectedLines, String instruction) {
        EditSessionDO session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.WIKI_EDIT_SESSION_NOT_FOUND);
        }

        String newHash = DraftService.computeHash(newContent);
        int newStepCount = session.getStepCount() + 1;

        EditStepDO step = new EditStepDO();
        step.setSessionId(sessionId);
        step.setStepNumber(newStepCount);
        step.setSelectedLines(selectedLines);
        step.setInstruction(instruction);
        step.setDiffRemoved(diffRemoved);
        step.setDiffAdded(diffAdded);
        step.setContentAfter(newContent);
        step.setCreatedAt(LocalDateTime.now());
        stepMapper.insert(step);

        session.setCurrentContent(newContent);
        session.setContentHash(newHash);
        session.setStepCount(newStepCount);
        session.setOutline(buildOutline(newContent));
        if (newStepCount > 3) {
            session.setHistorySummary(compressHistory(sessionId, newStepCount));
        }
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    public void undoStep(Long sessionId, Long stepId, Long scopeId) {
        EditSessionDO session = sessionMapper.selectOne(
            new LambdaQueryWrapper<EditSessionDO>()
                .eq(EditSessionDO::getId, sessionId)
                .eq(EditSessionDO::getScopeId, scopeId)
        );
        if (session == null) {
            throw new BusinessException(ErrorCode.WIKI_EDIT_SESSION_NOT_FOUND);
        }

        EditStepDO targetStep = stepMapper.selectOne(
            new LambdaQueryWrapper<EditStepDO>()
                .eq(EditStepDO::getId, stepId)
                .eq(EditStepDO::getSessionId, sessionId)
        );
        if (targetStep == null) {
            throw new BusinessException(ErrorCode.WIKI_EDIT_STEP_NOT_FOUND);
        }

        EditStepDO prevStep = stepMapper.selectOne(
            new LambdaQueryWrapper<EditStepDO>()
                .eq(EditStepDO::getSessionId, sessionId)
                .lt(EditStepDO::getStepNumber, targetStep.getStepNumber())
                .orderByDesc(EditStepDO::getStepNumber)
                .last("LIMIT 1")
        );

        String restoreContent = prevStep != null ? prevStep.getContentAfter() : getOriginalContent(session);
        session.setCurrentContent(restoreContent);
        session.setContentHash(DraftService.computeHash(restoreContent));
        session.setOutline(buildOutline(restoreContent));

        stepMapper.delete(
            new LambdaQueryWrapper<EditStepDO>()
                .eq(EditStepDO::getSessionId, sessionId)
                .ge(EditStepDO::getStepNumber, targetStep.getStepNumber())
        );

        int remaining = stepMapper.selectCount(
            new LambdaQueryWrapper<EditStepDO>()
                .eq(EditStepDO::getSessionId, sessionId)
        ).intValue();
        session.setStepCount(remaining);
        session.setHistorySummary(compressHistory(sessionId, remaining));
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    public void undoAll(Long sessionId, Long scopeId) {
        EditSessionDO session = sessionMapper.selectOne(
            new LambdaQueryWrapper<EditSessionDO>()
                .eq(EditSessionDO::getId, sessionId)
                .eq(EditSessionDO::getScopeId, scopeId)
        );
        if (session == null) return;

        String originalContent = getOriginalContent(session);
        session.setCurrentContent(originalContent);
        session.setContentHash(DraftService.computeHash(originalContent));
        session.setOutline(buildOutline(originalContent));
        session.setStepCount(0);
        session.setHistorySummary("");
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        stepMapper.delete(
            new LambdaQueryWrapper<EditStepDO>()
                .eq(EditStepDO::getSessionId, sessionId)
        );
    }

    public void abandonSession(Long sessionId, Long scopeId) {
        EditSessionDO session = sessionMapper.selectOne(
            new LambdaQueryWrapper<EditSessionDO>()
                .eq(EditSessionDO::getId, sessionId)
                .eq(EditSessionDO::getScopeId, scopeId)
        );
        if (session != null) {
            session.setStatus("abandoned");
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
        }
    }

    public void publishSession(Long sessionId, Long scopeId) {
        EditSessionDO session = sessionMapper.selectOne(
            new LambdaQueryWrapper<EditSessionDO>()
                .eq(EditSessionDO::getId, sessionId)
                .eq(EditSessionDO::getScopeId, scopeId)
        );
        if (session != null) {
            session.setStatus("published");
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);

            stepMapper.delete(
                new LambdaQueryWrapper<EditStepDO>()
                    .eq(EditStepDO::getSessionId, sessionId)
            );
        }
    }

    public List<EditStepInfo> listSteps(Long sessionId) {
        List<EditStepDO> steps = stepMapper.selectList(
            new LambdaQueryWrapper<EditStepDO>()
                .eq(EditStepDO::getSessionId, sessionId)
                .orderByAsc(EditStepDO::getStepNumber)
        );
        return steps.stream().map(this::toStepInfo).toList();
    }

    /**
     * 轻量版步骤列表 — 不加载 contentAfter (MEDIUMTEXT) 字段，用于历史摘要构建。
     */
    public List<EditStepInfo> listStepsLite(Long sessionId) {
        List<EditStepDO> steps = stepMapper.selectList(
            new LambdaQueryWrapper<EditStepDO>()
                .eq(EditStepDO::getSessionId, sessionId)
                .select(EditStepDO::getId, EditStepDO::getSessionId,
                        EditStepDO::getStepNumber, EditStepDO::getSelectedLines,
                        EditStepDO::getInstruction, EditStepDO::getDiffRemoved,
                        EditStepDO::getDiffAdded, EditStepDO::getCreatedAt)
                .orderByAsc(EditStepDO::getStepNumber)
        );
        return steps.stream().map(this::toStepInfo).toList();
    }

    @Scheduled(fixedRate = 3600000)
    public void cleanupExpiredSessions() {
        LocalDateTime activeCutoff = LocalDateTime.now().minusHours(4);
        LocalDateTime abandonedCutoff = LocalDateTime.now().minusHours(1);

        List<Long> expiredIds = sessionMapper.selectList(
            new LambdaQueryWrapper<EditSessionDO>()
                .and(w -> w
                    .and(a -> a.eq(EditSessionDO::getStatus, "active")
                               .lt(EditSessionDO::getUpdatedAt, activeCutoff))
                    .or(o -> o.eq(EditSessionDO::getStatus, "abandoned")
                              .lt(EditSessionDO::getUpdatedAt, abandonedCutoff))
                    .or(o -> o.eq(EditSessionDO::getStatus, "published"))
                )
                .select(EditSessionDO::getId)
        ).stream().map(EditSessionDO::getId).toList();

        if (expiredIds.isEmpty()) return;

        log.info("Cleaning up {} expired edit sessions", expiredIds.size());
        int batchSize = 100;
        for (int i = 0; i < expiredIds.size(); i += batchSize) {
            List<Long> batch = expiredIds.subList(i, Math.min(i + batchSize, expiredIds.size()));
            stepMapper.delete(new LambdaQueryWrapper<EditStepDO>()
                .in(EditStepDO::getSessionId, batch));
            sessionMapper.deleteBatchIds(batch);
        }
    }

    private String getOriginalContent(EditSessionDO session) {
        if (session.getOriginalContent() != null) {
            return session.getOriginalContent();
        }
        return session.getCurrentContent();
    }

    public static String buildOutline(String content) {
        if (content == null || content.isEmpty()) return "";
        StringBuilder outline = new StringBuilder();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') level++;
                String title = line.substring(level).trim();
                outline.append("L").append(i + 1).append(" ")
                       .append("#".repeat(Math.min(level, 6))).append(" ").append(title).append("\n");
            }
        }
        return outline.toString().trim();
    }

    private String compressHistory(Long sessionId, int currentStepCount) {
        if (currentStepCount <= 3) return "";

        List<EditStepDO> oldSteps = stepMapper.selectList(
            new LambdaQueryWrapper<EditStepDO>()
                .eq(EditStepDO::getSessionId, sessionId)
                .le(EditStepDO::getStepNumber, currentStepCount - 3)
                .orderByAsc(EditStepDO::getStepNumber)
        );

        if (oldSteps.isEmpty()) return "";

        StringBuilder summary = new StringBuilder();
        summary.append("已完成 ").append(oldSteps.size()).append(" 步编辑：");
        for (int i = 0; i < Math.min(oldSteps.size(), 3); i++) {
            EditStepDO s = oldSteps.get(i);
            if (i > 0) summary.append("，");
            summary.append(s.getInstruction() != null ? truncate(s.getInstruction(), 40) : "步骤" + s.getStepNumber());
        }
        if (oldSteps.size() > 3) {
            summary.append("等").append(oldSteps.size()).append("项修改");
        }
        return summary.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private EditSessionInfo toInfo(EditSessionDO session, boolean includeContent) {
        EditSessionInfo info = new EditSessionInfo();
        info.setId(session.getId());
        info.setScopeId(session.getScopeId());
        info.setPageId(session.getPageId());
        info.setDraftId(session.getDraftId());
        info.setContentHash(session.getContentHash());
        info.setOutline(session.getOutline());
        info.setStepCount(session.getStepCount());
        info.setStatus(session.getStatus());
        info.setCreatedAt(session.getCreatedAt());
        info.setUpdatedAt(session.getUpdatedAt());
        if (includeContent) {
            info.setCurrentContent(session.getCurrentContent());
        }
        return info;
    }

    private EditStepInfo toStepInfo(EditStepDO step) {
        EditStepInfo info = new EditStepInfo();
        info.setId(step.getId());
        info.setSessionId(step.getSessionId());
        info.setStepNumber(step.getStepNumber());
        info.setSelectedLines(step.getSelectedLines());
        info.setInstruction(step.getInstruction());
        info.setDiffRemoved(step.getDiffRemoved());
        info.setDiffAdded(step.getDiffAdded());
        info.setCreatedAt(step.getCreatedAt());
        return info;
    }
}
