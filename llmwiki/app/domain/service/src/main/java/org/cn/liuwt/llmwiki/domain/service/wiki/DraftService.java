package org.cn.liuwt.llmwiki.domain.service.wiki;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDraftDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageDraftMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.facade.model.CreateDraftRequest;
import org.cn.liuwt.llmwiki.facade.model.UpdateDraftRequest;
import org.cn.liuwt.llmwiki.facade.model.WikiPageDraftInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
public class DraftService {

    private static final Logger log = LoggerFactory.getLogger(DraftService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private WikiPageDraftMapper draftMapper;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    public WikiPageDraftInfo createDraft(Long scopeId, Long userId, CreateDraftRequest request) {
        WikiPageDraftDO draft = new WikiPageDraftDO();
        draft.setScopeId(scopeId);
        draft.setPageId(request.getPageId());
        draft.setTitle(request.getTitle() != null ? request.getTitle() : "未命名草稿");
        draft.setContent(request.getContent() != null ? request.getContent() : "");
        draft.setCategory(request.getCategory());
        draft.setPageType(request.getPageType() != null ? request.getPageType() : "manual");
        draft.setStatus("draft");
        draft.setCreatedBy(userId);
        draft.setCreatedAt(LocalDateTime.now());
        draft.setUpdatedAt(LocalDateTime.now());

        if (request.getTags() != null && !request.getTags().isEmpty()) {
            try {
                draft.setTags(objectMapper.writeValueAsString(request.getTags()));
            } catch (Exception e) {
                log.warn("Failed to serialize tags", e);
            }
        }

        if (request.getPageId() != null) {
            WikiPageDO page = wikiPageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getId, request.getPageId())
                    .eq(WikiPageDO::getScopeId, scopeId)
            );
            if (page != null) {
                draft.setBaseContentHash(page.getContentHash());
                if (draft.getContent().isEmpty() && page.getFilePath() != null) {
                    // fork content from existing page handled by caller
                }
            }
        }

        draftMapper.insert(draft);
        return toInfo(draft);
    }

    public WikiPageDraftInfo getDraft(Long draftId, Long scopeId) {
        WikiPageDraftDO draft = draftMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDraftDO>()
                .eq(WikiPageDraftDO::getId, draftId)
                .eq(WikiPageDraftDO::getScopeId, scopeId)
        );
        if (draft == null) {
            throw new BusinessException(ErrorCode.WIKI_DRAFT_NOT_FOUND);
        }
        return toInfo(draft);
    }

    public List<WikiPageDraftInfo> listDrafts(Long scopeId) {
        List<WikiPageDraftDO> drafts = draftMapper.selectList(
            new LambdaQueryWrapper<WikiPageDraftDO>()
                .eq(WikiPageDraftDO::getScopeId, scopeId)
                .eq(WikiPageDraftDO::getStatus, "draft")
                .orderByDesc(WikiPageDraftDO::getUpdatedAt)
        );
        return drafts.stream().map(this::toInfoLite).toList();
    }

    public WikiPageDraftInfo updateDraft(Long draftId, Long scopeId, UpdateDraftRequest request) {
        WikiPageDraftDO draft = draftMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDraftDO>()
                .eq(WikiPageDraftDO::getId, draftId)
                .eq(WikiPageDraftDO::getScopeId, scopeId)
        );
        if (draft == null) {
            throw new BusinessException(ErrorCode.WIKI_DRAFT_NOT_FOUND);
        }

        if (request.getTitle() != null) draft.setTitle(request.getTitle());
        if (request.getContent() != null) draft.setContent(request.getContent());
        if (request.getCategory() != null) draft.setCategory(request.getCategory());
        if (request.getTags() != null) {
            try {
                draft.setTags(objectMapper.writeValueAsString(request.getTags()));
            } catch (Exception e) {
                log.warn("Failed to serialize tags", e);
            }
        }
        draft.setUpdatedAt(LocalDateTime.now());
        draftMapper.updateById(draft);
        return toInfo(draft);
    }

    public void deleteDraft(Long draftId, Long scopeId) {
        int deleted = draftMapper.delete(
            new LambdaQueryWrapper<WikiPageDraftDO>()
                .eq(WikiPageDraftDO::getId, draftId)
                .eq(WikiPageDraftDO::getScopeId, scopeId)
        );
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.WIKI_DRAFT_NOT_FOUND);
        }
    }

    public static String computeHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute hash", e);
        }
    }

    private WikiPageDraftInfo toInfo(WikiPageDraftDO draft) {
        WikiPageDraftInfo info = new WikiPageDraftInfo();
        info.setId(draft.getId());
        info.setScopeId(draft.getScopeId());
        info.setPageId(draft.getPageId());
        info.setBaseContentHash(draft.getBaseContentHash());
        info.setTitle(draft.getTitle());
        info.setContent(draft.getContent());
        info.setCategory(draft.getCategory());
        info.setPageType(draft.getPageType());
        info.setStatus(draft.getStatus());
        info.setCreatedAt(draft.getCreatedAt());
        info.setUpdatedAt(draft.getUpdatedAt());
        info.setTags(parseTags(draft.getTags()));
        return info;
    }

    private WikiPageDraftInfo toInfoLite(WikiPageDraftDO draft) {
        WikiPageDraftInfo info = toInfo(draft);
        info.setContent(null);
        return info;
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
