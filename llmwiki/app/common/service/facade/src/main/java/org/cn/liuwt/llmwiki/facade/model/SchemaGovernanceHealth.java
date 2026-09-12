package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Schema 共治健康聚合指标。用于 Dashboard 看板，把 SystemView 里分散的"补丁数/迁移数"
 * 与新增的"Gatekeeper 一致率/观察期堆积/自动提升总数"统一输出。
 *
 * 宪法规则 3 / 5 / 7 的健康可视化入口：
 *   - pending/observing：规则 7 的分层堆积
 *   - supersededCount：规则 7 的观察期自动提升（被合并的副本数）
 *   - accepted/rejected/ignored：用户终态，用于 Gatekeeper 一致率分母
 *   - gatekeeperMatchRate：Gatekeeper APPROVE ↔ 用户 ACCEPT 的吻合率（0.00-1.00）
 *   - migrationUntagged/Outdated：规则 5 的遗留页面摘要
 */
@Data
public class SchemaGovernanceHealth {

    private Integer pendingCount;
    private Integer observingCount;
    private Integer acceptedCount;
    private Integer rejectedCount;
    private Integer ignoredCount;
    private Integer supersededCount;

    /** Gatekeeper 已评估样本数（gatekeeper_decision 非空 且 用户已终态）。 */
    private Integer gatekeeperEvaluated;

    /** Gatekeeper 吻合数（APPROVE+ACCEPTED、OBSERVE+IGNORED、REJECT+REJECTED）。 */
    private Integer gatekeeperMatched;

    /** Gatekeeper 一致率（0.00-1.00，样本不足时为 null）。 */
    private BigDecimal gatekeeperMatchRate;

    private Integer currentVersionNumber;
    private Integer migrationUntaggedCount;
    private Integer migrationOutdatedCount;

    /** 结构化模型当前模板数（系统运行时实际使用的模板数量）。 */
    private Integer templateCount;

    /** 模板结构缺陷总数：空模板 + 未解析模板块 + 结构化 JSON 落后于 markdown 的模板差。 */
    private Integer structureDefectCount;

    /** 模板结构缺陷明细（健康时为 null）。 */
    private String structureDefectDetail;
}
