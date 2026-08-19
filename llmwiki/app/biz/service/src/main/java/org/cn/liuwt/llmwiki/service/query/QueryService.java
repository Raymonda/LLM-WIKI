package org.cn.liuwt.llmwiki.service.query;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.domain.service.harness.HarnessEngine;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.RateLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class QueryService {
    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    @Autowired
    private HarnessEngine harnessEngine;

    @Autowired
    private RateLimitService rateLimitService;

    public Flux<String> queryWikiStreaming(Long scopeId, String question, String sessionId, boolean deepMode) {
        return queryWikiStreaming(scopeId, question, sessionId, deepMode, null);
    }

    public Flux<String> queryWikiStreaming(Long scopeId, String question, String sessionId, boolean deepMode, String assumedIntent) {
        return harnessEngine.executeQueryStreaming(scopeId, question, sessionId, deepMode, assumedIntent);
    }

    public Flux<String> queryWikiStreamingMultiScope(List<Long> scopeIds, String question, String sessionId, boolean deepMode) {
        return queryWikiStreamingMultiScope(scopeIds, question, sessionId, deepMode, null);
    }

    public Flux<String> queryWikiStreamingMultiScope(List<Long> scopeIds, String question, String sessionId, boolean deepMode, String assumedIntent) {
        return harnessEngine.executeQueryStreamingMultiScope(scopeIds, question, sessionId, deepMode, assumedIntent);
    }

    public WikiPageDO saveAnswerToWiki(Long scopeId, String question, String answer, String sessionId) {
        return harnessEngine.executeSaveQueryResult(scopeId, question, answer, sessionId);
    }
}
