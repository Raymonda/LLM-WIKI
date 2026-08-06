package org.cn.liuwt.llmwiki.domain.service.wiki;

/**
 * 保存后异步任务发布接口。
 * domain/service 层定义，biz/service 层实现（RocketMQ）。
 * 无实现时降级为同步执行。
 */
public interface PostSaveTaskPublisher {

    void publish(Long scopeId, Long pageId, String content, String category, String contentHash);
}
