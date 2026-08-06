package org.cn.liuwt.llmwiki.domain.service.harness.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageLinkMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class UpdateLinksTool {

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageLinkMapper wikiPageLinkMapper;

    @Tool(description = "在链接图谱数据库中添加或更新链接关系。用于在 Wiki 页面之间建立交叉引用")
    public boolean updateLinks(
        @ToolParam(description = "知识库范围 ID") String scopeId,
        @ToolParam(description = "来源页面路径，如 'pages/microservice-architecture.md'") String fromPage,
        @ToolParam(description = "目标页面路径，如 'pages/distributed-systems.md'") String toPage,
        @ToolParam(description = "链接类型，如 'cross-reference'、'related'、'contradiction'") String linkType
    ) {
        Long scopeIdLong = Long.parseLong(scopeId);

        WikiPageDO fromPageDO = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeIdLong)
                .eq(WikiPageDO::getFilePath, fromPage)
        );

        WikiPageDO toPageDO = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeIdLong)
                .eq(WikiPageDO::getFilePath, toPage)
        );

        if (fromPageDO == null || toPageDO == null) {
            return false;
        }

        WikiPageLinkDO existingLink = wikiPageLinkMapper.selectOne(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeIdLong)
                .eq(WikiPageLinkDO::getFromPageId, fromPageDO.getId())
                .eq(WikiPageLinkDO::getToPageId, toPageDO.getId())
        );

        if (existingLink != null) {
            existingLink.setLinkType(linkType);
            wikiPageLinkMapper.updateById(existingLink);
        } else {
            WikiPageLinkDO newLink = new WikiPageLinkDO();
            newLink.setScopeId(scopeIdLong);
            newLink.setFromPageId(fromPageDO.getId());
            newLink.setToPageId(toPageDO.getId());
            newLink.setLinkType(linkType);
            wikiPageLinkMapper.insert(newLink);
        }

        return true;
    }
}