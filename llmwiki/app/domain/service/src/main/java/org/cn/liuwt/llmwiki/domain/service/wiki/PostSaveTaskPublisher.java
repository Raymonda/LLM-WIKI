package org.cn.liuwt.llmwiki.domain.service.wiki;

/**
 * 保存后异步任务发布接口。
 * domain/service 层定义，biz/service 层实现。
 * 实现必须始终存在：MQ 启用时走 RocketMQ，否则降级为本地线程池执行，
 * 保证 wiki_page.post_save_status 不会永久停留在 pending。
 */
public interface PostSaveTaskPublisher {

    void publish(Long scopeId, Long pageId, String content, String category, String contentHash);
}
