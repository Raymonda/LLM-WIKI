package org.cn.liuwt.llmwiki.domain.service.harness.governance.bootstrap;

import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ParadigmCatalog {

    private final Map<String, Paradigm> paradigms = new ConcurrentHashMap<>();

    public ParadigmCatalog() {
        register(research());
        register(compliance());
        register(personalLearning());
        register(techDocs());
        register(projectKb());
        register(chronicle());
    }

    public List<Paradigm> listAll() {
        return new ArrayList<>(paradigms.values());
    }

    public Paradigm getById(String id) {
        return paradigms.get(id);
    }

    private void register(Paradigm p) {
        paradigms.put(p.id, p);
    }

    public static class Paradigm {
        public final String id;
        public final String label;
        public final String description;
        public final String icon;
        public final SchemaStructuredModel skeleton;
        public final CapabilityMeta capabilityMeta;

        public Paradigm(String id, String label, String description, String icon,
                         SchemaStructuredModel skeleton, CapabilityMeta capabilityMeta) {
            this.id = id;
            this.label = label;
            this.description = description;
            this.icon = icon;
            this.skeleton = skeleton;
            this.capabilityMeta = capabilityMeta;
        }

        public Map<String, Object> toSummary() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", id);
            m.put("label", label);
            m.put("description", description);
            m.put("icon", icon);
            if (capabilityMeta != null) {
                m.put("tags", capabilityMeta.getTags());
                m.put("sampleDocTypes", capabilityMeta.getSampleDocTypes());
                m.put("affectedSections", capabilityMeta.getAffectedSections());
            }
            return m;
        }
    }

    private Paradigm research() {
        SchemaStructuredModel m = new SchemaStructuredModel();
        m.setDomainNarrative("投研知识库，沉淀行业研究、公司分析和投资策略。面向研究团队，支撑投资决策和知识传承。");

        Taxonomy tax = new Taxonomy();
        tax.setNarrative("按研究领域和分析类型分类，支持多级细分");
        tax.setRoots(List.of(
            node("行业研究", "行业深度、产业链分析",
                child("宏观研究", "宏观经济与政策分析"),
                child("行业深度", "单行业深度报告"),
                child("产业链", "上下游产业链分析")),
            node("公司研究", "个股深度、财务分析",
                child("个股深度", "公司深度研究报告"),
                child("财务速览", "关键财务指标分析")),
            node("策略报告", "投资策略、市场展望")
        ));
        m.setTaxonomy(tax);

        m.setTemplates(buildTemplates(
            pageTemplate("行业研究", List.of(
                section("概述", true, 1),
                section("核心数据", true, 2),
                section("分析要点", true, 3),
                section("风险提示", false, 4),
                section("来源引用", true, 5)
            )),
            pageTemplate("公司研究", List.of(
                section("公司概况", true, 1),
                section("核心业务", true, 2),
                section("财务分析", true, 3),
                section("估值分析", false, 4),
                section("来源引用", true, 5)
            )),
            pageTemplate("策略报告", List.of(
                section("市场回顾", true, 1),
                section("核心观点", true, 2),
                section("投资建议", true, 3),
                section("来源引用", true, 4)
            ))
        ));

        m.setNaming(buildNaming("zh-CN", 30, "使用中文命名，突出研究主题和标的", List.of(
            "新能源行业深度报告2026", "宁德时代-财务速览", "A股二季度策略展望"
        )));

        m.setWorkflow(buildWorkflow("AUTO", List.of(
            "涉及未公开信息的页面须确认",
            "删除已有深度报告页面时必须确认"
        )));

        m.setLintRules(LintRulesConfig.buildDefaults());

        CapabilityMeta meta = buildCapabilityMeta(
            "research", "研究分析",
            "沉淀行业研究、公司分析和投资策略，面向研究团队",
            "Target", Set.of(1, 2, 3, 4, 5, 6),
            List.of("数据密集型", "来源追溯优先"),
            List.of("行业研报", "公司深度", "策略报告", "财务分析"),
            10
        );
        return new Paradigm("research", "研究分析", "沉淀行业研究、公司分析和投资策略，面向研究团队", "Target", m, meta);
    }

    private Paradigm techDocs() {
        SchemaStructuredModel m = new SchemaStructuredModel();
        m.setDomainNarrative("技术文档知识库，沉淀团队的技术方案、架构设计、API 文档和最佳实践。面向研发团队，帮助新成员快速上手，减少重复沟通。");

        Taxonomy tax = new Taxonomy();
        tax.setNarrative("按技术领域分类，支持多级子分类");
        tax.setRoots(List.of(
            node("架构设计", "系统架构、模块划分、技术选型",
                child("微服务", "服务拆分与通信"),
                child("数据库", "表设计与索引策略")),
            node("API 文档", "接口规范与调用示例"),
            node("运维手册", "部署、监控、故障排查"),
            node("最佳实践", "编码规范、安全实践、性能优化")
        ));
        m.setTaxonomy(tax);

        m.setTemplates(buildTemplates(
            pageTemplate("技术方案", List.of(
                section("背景与目标", true, 1),
                section("方案设计", true, 2),
                section("接口定义", false, 3),
                section("风险评估", false, 4),
                section("来源引用", true, 5)
            )),
            pageTemplate("API 文档", List.of(
                section("接口概述", true, 1),
                section("请求参数", true, 2),
                section("响应格式", true, 3),
                section("调用示例", true, 4),
                section("来源引用", true, 5)
            )),
            pageTemplate("故障排查", List.of(
                section("问题现象", true, 1),
                section("根因分析", true, 2),
                section("解决方案", true, 3),
                section("来源引用", true, 4)
            ))
        ));

        m.setNaming(buildNaming("zh-CN", 30, "使用中文命名，简洁明了", List.of(
            "用户服务架构设计", "订单API v2", "Redis缓存最佳实践"
        )));

        m.setWorkflow(buildWorkflow("CONFIRM", List.of(
            "涉及生产环境配置变更时必须确认",
            "删除已有页面时必须确认"
        )));

        m.setLintRules(LintRulesConfig.buildDefaults());
        CapabilityMeta meta = buildCapabilityMeta(
            "tech_docs", "技术文档库",
            "沉淀技术方案、架构设计、API 文档和最佳实践，面向研发团队",
            "Code", Set.of(1, 2, 3, 4, 5, 6),
            List.of("版本化管理", "代码关联"),
            List.of("技术方案", "API文档", "故障排查记录", "架构设计"),
            20
        );
        return new Paradigm("tech_docs", "技术文档库", "沉淀技术方案、架构设计、API 文档和最佳实践，面向研发团队", "Code", m, meta);
    }

    private Paradigm compliance() {
        SchemaStructuredModel m = new SchemaStructuredModel();
        m.setDomainNarrative("合规制度知识库，集中管理公司的规章制度、合规要求和操作流程。面向全体员工，确保制度可查、可追溯、版本可控。");

        Taxonomy tax = new Taxonomy();
        tax.setNarrative("按制度类型分类，重要制度标注适用范围");
        tax.setRoots(List.of(
            node("人事制度", "考勤、假期、薪酬、绩效"),
            node("财务制度", "报销、采购、预算、审计"),
            node("信息安全", "数据分级、访问控制、应急响应"),
            node("业务流程", "合同审批、供应商管理、客户管理"),
            node("法律法规", "行业监管要求、合规指南")
        ));
        m.setTaxonomy(tax);

        m.setTemplates(buildTemplates(
            pageTemplate("制度文件", List.of(
                section("适用范围", true, 1),
                section("制度正文", true, 2),
                section("违规处理", false, 3),
                section("修订记录", true, 4),
                section("来源引用", true, 5)
            )),
            pageTemplate("操作指南", List.of(
                section("操作目的", true, 1),
                section("操作步骤", true, 2),
                section("常见问题", false, 3),
                section("来源引用", true, 4)
            ))
        ));

        m.setNaming(buildNaming("zh-CN", 40, "使用正式制度名称，包含制度类型关键词", List.of(
            "员工考勤管理制度", "信息安全管理办法", "采购审批流程"
        )));

        m.setWorkflow(buildWorkflow("CONFIRM", List.of(
            "所有制度变更必须经管理员确认",
            "涉及法律法规的变更需二次确认"
        )));

        m.setLintRules(LintRulesConfig.buildDefaults());
        CapabilityMeta meta = buildCapabilityMeta(
            "compliance", "合规制度库",
            "集中管理制度文件、合规要求和操作流程，面向全体员工",
            "Shield", Set.of(1, 2, 3, 4, 5, 6),
            List.of("条款级引用", "合规性体检"),
            List.of("制度文件", "操作指南", "合规手册", "法规解读"),
            15
        );
        return new Paradigm("compliance", "合规制度库", "集中管理制度文件、合规要求和操作流程，面向全体员工", "Shield", m, meta);
    }

    private Paradigm projectKb() {
        SchemaStructuredModel m = new SchemaStructuredModel();
        m.setDomainNarrative("项目知识库，围绕具体项目沉淀需求文档、设计决策、会议纪要和项目复盘。面向项目团队，确保信息对齐和知识传承。");

        Taxonomy tax = new Taxonomy();
        tax.setNarrative("按项目阶段和文档类型分类");
        tax.setRoots(List.of(
            node("需求分析", "PRD、用户故事、需求评审"),
            node("设计决策", "ADR、技术方案、原型设计"),
            node("开发记录", "技术债务、代码评审、联调记录"),
            node("测试验收", "测试用例、验收报告、Bug 分析"),
            node("项目复盘", "Sprint 回顾、里程碑总结、经验教训")
        ));
        m.setTaxonomy(tax);

        m.setTemplates(buildTemplates(
            pageTemplate("需求文档", List.of(
                section("背景与目标", true, 1),
                section("功能需求", true, 2),
                section("非功能需求", false, 3),
                section("验收标准", true, 4),
                section("来源引用", true, 5)
            )),
            pageTemplate("设计决策", List.of(
                section("背景", true, 1),
                section("决策", true, 2),
                section("备选方案", false, 3),
                section("影响评估", false, 4),
                section("来源引用", true, 5)
            )),
            pageTemplate("复盘总结", List.of(
                section("做得好的", true, 1),
                section("需要改进的", true, 2),
                section("行动项", true, 3),
                section("来源引用", true, 4)
            ))
        ));

        m.setNaming(buildNaming("zh-CN", 25, "使用中文命名，包含项目或功能关键词", List.of(
            "用户登录需求PRD", "数据库选型ADR", "Sprint 12回顾"
        )));

        m.setWorkflow(buildWorkflow("AUTO", List.of(
            "删除已归档的复盘记录时须确认"
        )));

        m.setLintRules(LintRulesConfig.buildDefaults());
        CapabilityMeta meta = buildCapabilityMeta(
            "project", "项目知识库",
            "围绕项目沉淀需求、设计决策和项目复盘，面向项目团队",
            "FolderKanban", Set.of(1, 2, 3, 5),
            List.of("项目维度组织", "复盘驱动"),
            List.of("需求文档", "设计决策", "会议纪要", "复盘总结"),
            25
        );
        return new Paradigm("project", "项目知识库", "围绕项目沉淀需求、设计决策和项目复盘，面向项目团队", "FolderKanban", m, meta);
    }

    private Paradigm competitiveAnalysis() {
        SchemaStructuredModel m = new SchemaStructuredModel();
        m.setDomainNarrative("竞品分析知识库，系统化跟踪竞争对手的产品动态、市场策略和技术路线。面向产品和战略团队，支撑竞争决策。");

        Taxonomy tax = new Taxonomy();
        tax.setNarrative("按竞品维度和分析类型分类");
        tax.setRoots(List.of(
            node("竞品档案", "每个竞品一个子分类",
                child("竞品A", "竞品A 的产品信息"),
                child("竞品B", "竞品B 的产品信息")),
            node("产品对比", "功能对比、定价对比、用户评价"),
            node("市场动态", "融资、合作、市场扩张"),
            node("技术分析", "技术栈、专利、开源项目"),
            node("战略研判", "趋势预判、竞争策略建议")
        ));
        m.setTaxonomy(tax);

        m.setTemplates(buildTemplates(
            pageTemplate("竞品档案", List.of(
                section("公司概况", true, 1),
                section("核心产品", true, 2),
                section("商业模式", true, 3),
                section("优劣势分析", false, 4),
                section("来源引用", true, 5)
            )),
            pageTemplate("对比分析", List.of(
                section("对比维度", true, 1),
                section("对比表格", true, 2),
                section("分析结论", true, 3),
                section("来源引用", true, 4)
            ))
        ));

        m.setNaming(buildNaming("zh-CN", 25, "竞品名作为前缀，后跟分析主题", List.of(
            "竞品A-产品概览", "定价策略对比2026Q1", "AI赛道市场格局"
        )));

        m.setWorkflow(buildWorkflow("AUTO", List.of(
            "涉及未公开信息的页面须确认后再发布"
        )));

        m.setLintRules(LintRulesConfig.buildDefaults());
        return new Paradigm("competitive", "竞品分析库", "系统化跟踪竞品动态和市场策略，面向产品和战略团队", "Target", m, null);
    }

    private Paradigm personalLearning() {
        SchemaStructuredModel m = new SchemaStructuredModel();
        m.setDomainNarrative("个人学习知识库，整理读书笔记、课程笔记、技能学习和思考总结。面向个人，帮助构建系统化的知识体系。");

        Taxonomy tax = new Taxonomy();
        tax.setNarrative("按知识领域分类，支持灵活扩展");
        tax.setRoots(List.of(
            node("读书笔记", "按书名或主题归类"),
            node("技术学习", "编程语言、框架、工具"),
            node("商业洞察", "行业分析、商业模式、案例研究"),
            node("思维模型", "决策框架、认知方法、心理学"),
            node("生活技能", "健康管理、理财、沟通")
        ));
        m.setTaxonomy(tax);

        m.setTemplates(buildTemplates(
            pageTemplate("读书笔记", List.of(
                section("书籍信息", true, 1),
                section("核心观点", true, 2),
                section("精华摘录", false, 3),
                section("个人思考", false, 4),
                section("来源引用", true, 5)
            )),
            pageTemplate("学习笔记", List.of(
                section("学习主题", true, 1),
                section("关键概念", true, 2),
                section("实践案例", false, 3),
                section("待深入", false, 4),
                section("来源引用", true, 5)
            ))
        ));

        m.setNaming(buildNaming("zh-CN", 20, "使用简短中文标题，突出主题关键词", List.of(
            "思考快与慢", "Rust所有权机制", "金字塔原理"
        )));

        m.setWorkflow(buildWorkflow("AUTO", List.of()));

        m.setLintRules(LintRulesConfig.buildDefaults());
        CapabilityMeta meta = buildCapabilityMeta(
            "personal", "个人学习库",
            "整理读书笔记、课程笔记和技能学习，面向个人知识体系构建",
            "BookOpen", Set.of(1, 2, 3, 5),
            List.of("灵活扩展", "低门槛录入"),
            List.of("读书笔记", "课程笔记", "技能学习", "思考总结"),
            30
        );
        return new Paradigm("personal", "个人学习库", "整理读书笔记、课程笔记和技能学习，面向个人知识体系构建", "BookOpen", m, meta);
    }

    private Paradigm chronicle() {
        SchemaStructuredModel m = new SchemaStructuredModel();
        m.setDomainNarrative("过程记录知识库，管理高频更新的碎片化知识，如每日资讯、新闻简报、周度/月度市场回顾等。面向需要跟踪时效信息的团队，将碎片信息沉淀为结构化知识。");

        Taxonomy tax = new Taxonomy();
        tax.setNarrative("按时间周期和信息类型分类，支持快速检索历史内容");
        tax.setRoots(List.of(
            node("每日资讯", "晨会纪要、市场快讯、监管动态",
                child("晨会纪要", "每日晨会记录"),
                child("市场快讯", "当日重要市场动态")),
            node("周报", "周度回顾与展望"),
            node("月报", "月度总结与趋势分析",
                child("市场月报", "市场表现月度回顾"),
                child("行业月报", "行业动态月度跟踪")),
            node("专题解读", "热点事件解读、政策解读")
        ));
        m.setTaxonomy(tax);

        m.setTemplates(buildTemplates(
            pageTemplate("资讯摘要", List.of(
                section("要点摘要", true, 1),
                section("详细内容", true, 2),
                section("影响分析", false, 3),
                section("来源引用", true, 4)
            )),
            pageTemplate("周报/月报", List.of(
                section("本期概览", true, 1),
                section("重点事件", true, 2),
                section("数据回顾", true, 3),
                section("趋势展望", false, 4),
                section("来源引用", true, 5)
            )),
            pageTemplate("专题解读", List.of(
                section("事件背景", true, 1),
                section("核心分析", true, 2),
                section("影响评估", true, 3),
                section("来源引用", true, 4)
            ))
        ));

        m.setNaming(buildNaming("zh-CN", 35, "标题包含时间标识，突出主题关键词", List.of(
            "2026-05-28 晨会纪要", "2026年第21周市场周报", "美联储加息政策解读"
        )));

        m.setWorkflow(buildWorkflow("AUTO", List.of(
            "删除月度以上级别的回顾页面时须确认"
        )));

        m.setLintRules(LintRulesConfig.buildDefaults());

        CapabilityMeta meta = buildCapabilityMeta(
            "chronicle", "过程记录",
            "管理每日资讯、新闻简报、周报月报等高频碎片化知识",
            "Newspaper", Set.of(1, 2, 3, 5, 6),
            List.of("高时效性", "碎片聚合", "自动归档"),
            List.of("每日资讯", "晨会纪要", "周报", "月报", "专题解读"),
            35
        );
        return new Paradigm("chronicle", "过程记录", "管理每日资讯、新闻简报、周报月报等高频碎片化知识", "Newspaper", m, meta);
    }

    private CapabilityMeta buildCapabilityMeta(String id, String label, String description,
                                                 String icon, Set<Integer> affectedSections,
                                                 List<String> tags, List<String> sampleDocTypes,
                                                 int fusionPriority) {
        CapabilityMeta meta = new CapabilityMeta();
        meta.setId(id);
        meta.setLabel(label);
        meta.setDescription(description);
        meta.setIcon(icon);
        meta.setAffectedSections(affectedSections);
        meta.setTags(tags);
        meta.setSampleDocTypes(sampleDocTypes);
        meta.setFusionPriority(fusionPriority);
        return meta;
    }

    private TaxonomyNode node(String label, String description, TaxonomyNode... children) {
        TaxonomyNode n = new TaxonomyNode();
        n.setId(slugify(label));
        n.setLabel(label);
        n.setDescription(description);
        if (children.length > 0) {
            n.setChildren(List.of(children));
        }
        return n;
    }

    private TaxonomyNode child(String label, String description) {
        TaxonomyNode n = new TaxonomyNode();
        n.setId(slugify(label));
        n.setLabel(label);
        n.setDescription(description);
        return n;
    }

    private String slugify(String label) {
        return label.trim().toLowerCase()
            .replaceAll("\\s+", "_")
            .replaceAll("[^\\w\\u4e00-\\u9fa5_]", "")
            .replaceAll("_+", "_");
    }

    private Templates buildTemplates(PageTemplate... pts) {
        Templates t = new Templates();
        t.setPageTemplates(List.of(pts));
        return t;
    }

    private PageTemplate pageTemplate(String label, List<SectionDef> sections) {
        PageTemplate pt = new PageTemplate();
        pt.setType(slugify(label));
        pt.setLabel(label);
        pt.setSections(sections);
        return pt;
    }

    private SectionDef section(String label, boolean required, int order) {
        SectionDef s = new SectionDef();
        s.setId(slugify(label));
        s.setLabel(label);
        s.setRequired(required);
        s.setOrder(order);
        return s;
    }

    private Naming buildNaming(String language, int maxLength, String narrative, List<String> examples) {
        Naming n = new Naming();
        n.setNarrative(narrative);
        NamingRule entity = new NamingRule();
        entity.setLanguage(language);
        entity.setMaxLength(maxLength);
        n.setEntity(entity);
        NamingRule summary = new NamingRule();
        summary.setLanguage(language);
        summary.setMaxLength(maxLength + 10);
        n.setSummary(summary);
        n.setExamples(examples);
        return n;
    }

    private Workflow buildWorkflow(String defaultApproval, List<String> confirmTriggers) {
        Workflow w = new Workflow();
        w.setDefaultApproval(defaultApproval);
        w.setConfirmTriggers(confirmTriggers);
        return w;
    }
}
