package org.cn.liuwt.llmwiki.domain.service.wiki;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.UserDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.UserMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.facade.model.ContributorInfo;
import org.cn.liuwt.llmwiki.facade.model.PromotedPageInfo;
import org.cn.liuwt.llmwiki.facade.model.PromotionStats;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class PromotionServiceImpl implements PromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionServiceImpl.class);

    private static final String RECALL_STUB_TEMPLATE =
        "# {title}\n\n此知识条目因贡献者选择隐私保护而暂停展示。\n\n*（标题保留以维护知识图谱完整性，内容已移除）*";

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private ScopeMapper scopeMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private NotificationService notificationService;

    @Override
    public void updateVisibility(Long scopeId, String pagePath, String visibility, Long userId) {
        WikiPageDO pageDO = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .eq(WikiPageDO::getFilePath, pagePath)
        );
        if (pageDO == null) {
            throw new BusinessException(ErrorCode.WIKI_PAGE_NOT_FOUND);
        }

        if (pageDO.getPromotedFromScopeId() != null) {
            throw new BusinessException(ErrorCode.WIKI_PROMOTED_VISIBILITY_LOCKED);
        }

        String oldVisibility = pageDO.getVisibility();
        pageDO.setVisibility(visibility);
        wikiPageMapper.updateById(pageDO);

        if ("private".equals(visibility) && "open".equals(oldVisibility)) {
            softRecall(scopeId, pagePath, userId);
        }

        log.info("Page visibility updated: scopeId={}, path={}, old={}, new={}", scopeId, pagePath, oldVisibility, visibility);
    }

    @Override
    public void softRecall(Long scopeId, String pagePath, Long userId) {
        WikiPageDO sourcePage = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .eq(WikiPageDO::getFilePath, pagePath)
        );
        if (sourcePage == null) return;

        List<WikiPageDO> promotedPages = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getPromotedFromScopeId, scopeId)
                .eq(WikiPageDO::getPromotedFromPageId, sourcePage.getId())
        );

        for (WikiPageDO promotedPage : promotedPages) {
            String stubContent = RECALL_STUB_TEMPLATE.replace("{title}", promotedPage.getTitle());
            String targetScopeIdStr = String.valueOf(promotedPage.getScopeId());

            storageProvider.write(targetScopeIdStr, "wiki/" + promotedPage.getFilePath(),
                stubContent.getBytes(StandardCharsets.UTF_8));

            promotedPage.setSummary("此知识条目因贡献者选择隐私保护而暂停展示");
            promotedPage.setHealthStatus("recalled");
            promotedPage.setPromotedFromScopeId(null);
            promotedPage.setPromotedFromPageId(null);
            promotedPage.setPromotedFromUsername(null);
            wikiPageMapper.updateById(promotedPage);

            ScopeDO targetScope = scopeMapper.selectById(promotedPage.getScopeId());
            if (targetScope != null) {
                UserDO owner = userMapper.selectById(targetScope.getOwnerId());
                if (owner != null) {
                    notificationService.createNotification(
                        owner.getId(),
                        "page_recalled",
                        "知识页面被召回",
                        "贡献者将知识《" + promotedPage.getTitle() + "》标记为 private，页面已替换为存根",
                        promotedPage.getScopeId(),
                        promotedPage.getId()
                    );
                }
            }
        }

        log.info("Soft recall completed: source scopeId={}, pageId={}, recalled {} promoted pages",
            scopeId, sourcePage.getId(), promotedPages.size());
    }

    @Override
    public PromotionStats getPromotionStats(Long userId) {
        List<WikiPageDO> promotedPages = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getPromotedFromUsername,
                    userMapper.selectById(userId).getUsername())
        );

        Set<Long> adoptedScopeIds = new HashSet<>();
        List<PromotedPageInfo> recentPages = new ArrayList<>();

        for (WikiPageDO page : promotedPages) {
            adoptedScopeIds.add(page.getScopeId());
            PromotedPageInfo info = new PromotedPageInfo();
            info.setPageId(page.getId());
            info.setTitle(page.getTitle());
            info.setPath(page.getFilePath());
            info.setTargetScopeId(page.getScopeId());
            ScopeDO scope = scopeMapper.selectById(page.getScopeId());
            info.setTargetScopeName(scope != null ? scope.getName() : "");
            info.setPromotedAt(page.getCreatedAt() != null ? page.getCreatedAt().toString() : "");
            recentPages.add(info);
        }

        PromotionStats stats = new PromotionStats();
        stats.setAdoptedTeamCount(adoptedScopeIds.size());
        stats.setPromotedPageCount(promotedPages.size());
        stats.setRecentPromotedPages(recentPages);
        return stats;
    }

    @Override
    public List<ContributorInfo> getContributors(Long scopeId) {
        List<WikiPageDO> promotedPages = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .isNotNull(WikiPageDO::getPromotedFromUsername)
        );

        java.util.Map<String, Integer> contributorCounts = new java.util.HashMap<>();
        for (WikiPageDO page : promotedPages) {
            String username = page.getPromotedFromUsername();
            contributorCounts.merge(username, 1, Integer::sum);
        }

        List<ContributorInfo> contributors = new ArrayList<>();
        for (java.util.Map.Entry<String, Integer> entry : contributorCounts.entrySet()) {
            UserDO user = userMapper.selectOne(
                new LambdaQueryWrapper<UserDO>().eq(UserDO::getUsername, entry.getKey())
            );
            ContributorInfo info = new ContributorInfo();
            info.setUserId(user != null ? user.getId() : null);
            info.setUserName(entry.getKey());
            info.setPromotedPageCount(entry.getValue());
            contributors.add(info);
        }

        contributors.sort((a, b) -> b.getPromotedPageCount() - a.getPromotedPageCount());
        return contributors;
    }
}