package org.cn.liuwt.llmwiki.domain.service.harness.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.helper.ActivePageScope;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ListPagesTool {

    private static final Logger log = LoggerFactory.getLogger("harness.tool");

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Tool(description = "从数据库列出所有 Wiki 页面及其元数据，可选按分类筛选")
    public List<PageResult> listPages(
        @ToolParam(description = "知识库范围 ID") String scopeId,
        @ToolParam(description = "分类筛选（可选）") String category
    ) {
        Long scopeIdLong = Long.parseLong(scopeId);
        LambdaQueryWrapper<WikiPageDO> wrapper = ActivePageScope.active(scopeIdLong);

        if (category != null && !category.isEmpty()) {
            wrapper.eq(WikiPageDO::getCategory, category);
        }

        wrapper.orderByDesc(WikiPageDO::getContentUpdatedAt);
        List<WikiPageDO> pageDOs = wikiPageMapper.selectList(wrapper);
        log.info("tool=listPages scopeId={} category={} results={}", scopeId, category, pageDOs.size());

        List<PageResult> results = new ArrayList<>();
        for (WikiPageDO pageDO : pageDOs) {
            results.add(PageResult.from(pageDO));
        }
        return results;
    }

    public static class PageResult {
        public String title;
        public String path;
        public String category;
        public String summary;
        public String healthStatus;

        static PageResult from(WikiPageDO pageDO) {
            PageResult result = new PageResult();
            result.title = pageDO.getTitle();
            result.path = toReadPath(pageDO.getFilePath());
            result.category = pageDO.getCategory();
            result.summary = pageDO.getSummary();
            result.healthStatus = pageDO.getHealthStatus();
            return result;
        }

        private static String toReadPath(String dbPath) {
            if (dbPath != null && dbPath.startsWith("pages/")) {
                return "wiki/" + dbPath;
            }
            return dbPath;
        }
    }
}