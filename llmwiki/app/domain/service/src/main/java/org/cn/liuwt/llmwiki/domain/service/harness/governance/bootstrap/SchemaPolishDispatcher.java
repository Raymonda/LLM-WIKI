package org.cn.liuwt.llmwiki.domain.service.harness.governance.bootstrap;

/**
 * Schema 初版润色任务的分发入口。
 *
 * domain 层不依赖消息队列基础设施，由 biz 层提供双实现：
 * MQ 可用时经 llmwiki-pipeline-task 投递（持久化 + 失败自动重投递），
 * 否则退化为本地线程池异步执行。行为收敛到 {@link SchemaPolishService#polishSchema}。
 */
public interface SchemaPolishDispatcher {

    void dispatch(Long scopeId);
}
