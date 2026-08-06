package org.cn.liuwt.llmwiki.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.model.system.ScopeModel;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/activity")
public class ActivityController {

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ScopeService scopeService;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private ScopeMapper scopeMapper;

    @GetMapping("/feed")
    public Result<List<Map<String, Object>>> getFeed(@RequestParam(defaultValue = "20") int limit) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        List<ScopeModel> userScopes = scopeService.listScopesByUserId(userId);
        if (userScopes.isEmpty()) {
            return Result.success(List.of());
        }
        List<Long> scopeIds = userScopes.stream().map(ScopeModel::getId).toList();
        List<WikiPageDO> pages = wikiPageMapper.selectList(new LambdaQueryWrapper<WikiPageDO>()
                .in(WikiPageDO::getScopeId, scopeIds)
                .eq(WikiPageDO::getLifecycleStatus, "ACTIVE")
                .orderByDesc(WikiPageDO::getUpdatedAt)
                .last("LIMIT " + Math.min(limit, 50)));
        Map<Long, String> scopeNameCache = new HashMap<>();
        List<Map<String, Object>> feed = new ArrayList<>();
        for (WikiPageDO page : pages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pageId", page.getId());
            item.put("title", page.getTitle());
            item.put("category", page.getCategory());
            item.put("scopeId", page.getScopeId());
            item.put("scopeName", scopeNameCache.computeIfAbsent(page.getScopeId(), id -> {
                ScopeDO s = scopeMapper.selectById(id);
                return s != null ? s.getName() : "";
            }));
            item.put("updatedAt", page.getUpdatedAt());
            feed.add(item);
        }
        return Result.success(feed);
    }
}
