package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaPatchDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SchemaPatchMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaPatchModel;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel.*;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaMarkdownRenderer;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaStructuredParser;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaSkeletonValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schema 补丁审批服务。处理 {@link SchemaPatchProposer} 产出的候选补丁的人工审批。
 *
 * 状态机：PENDING -> ACCEPTED / REJECTED / IGNORED / SUPERSEDED
 *
 * accept 路径会读当前 Schema，按 sectionTitle 定位段落后应用 diff，再通过 {@link SchemaManager#saveSchema}
 * 保存（自动触发 {@link SchemaSkeletonValidator} 骨架校验）。骨架校验失败则整个 accept 回滚。
 */
@Service
public class SchemaPatchService {

    private static final Logger log = LoggerFactory.getLogger(SchemaPatchService.class);

    private static final Pattern SECTION_NUMBER_PATTERN = Pattern.compile("^##\\s*(\\d+)");
    private static final Pattern LABEL_FROM_DIFF = Pattern.compile("^[-*+]\\s+(.+?)[：:——]|^###\\s+(.+)|^\\d+[.、]\\s*(.+?)[（(]");

    @Autowired
    private SchemaPatchMapper schemaPatchMapper;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private SchemaStructuredParser schemaStructuredParser;

    @Autowired
    private SchemaMarkdownRenderer schemaMarkdownRenderer;

    public List<SchemaPatchModel> listPending(Long scopeId) {
        return listByStatusLight(scopeId, SchemaPatchModel.Status.PENDING);
    }

    public List<SchemaPatchModel> listObserving(Long scopeId) {
        return listByStatusLight(scopeId, SchemaPatchModel.Status.OBSERVING);
    }

    private List<SchemaPatchModel> listByStatusLight(Long scopeId, SchemaPatchModel.Status status) {
        if (scopeId == null) return List.of();
        List<SchemaPatchDO> rows = schemaPatchMapper.selectList(
            new LambdaQueryWrapper<SchemaPatchDO>()
                .select(SchemaPatchDO.class, fi ->
                    !"diff_before".equals(fi.getColumn()) && !"diff_after".equals(fi.getColumn()))
                .eq(SchemaPatchDO::getScopeId, scopeId)
                .eq(SchemaPatchDO::getStatus, status.name())
                .orderByDesc(SchemaPatchDO::getCreatedAt)
        );
        List<SchemaPatchModel> out = new ArrayList<>(rows.size());
        for (SchemaPatchDO r : rows) out.add(toModel(r));
        return out;
    }

    public SchemaPatchModel loadPatchDiff(Long patchId) {
        if (patchId == null) throw new BusinessException(ErrorCode.PATCH_ID_NULL);
        SchemaPatchDO patch = schemaPatchMapper.selectById(patchId);
        if (patch == null) throw new BusinessException(ErrorCode.PATCH_NOT_FOUND, patchId);
        return toModel(patch);
    }

    public int countPending(Long scopeId) {
        if (scopeId == null) return 0;
        Long n = schemaPatchMapper.selectCount(
            new LambdaQueryWrapper<SchemaPatchDO>()
                .eq(SchemaPatchDO::getScopeId, scopeId)
                .eq(SchemaPatchDO::getStatus, SchemaPatchModel.Status.PENDING.name())
        );
        return n == null ? 0 : n.intValue();
    }

    public SchemaPatchModel accept(Long patchId, Long userId) {
        SchemaPatchDO patch = loadPending(patchId);
        SchemaConfigDO schema = schemaManager.getSchema(patch.getScopeId(), SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        if (schema == null || schema.getConfigValue() == null || schema.getConfigValue().isBlank()) {
            throw new BusinessException(ErrorCode.PATCH_SCHEMA_NOT_READY);
        }

        SchemaStructuredModel structuredModel = schemaManager.getStructuredModel(patch.getScopeId());
        int sectionNumber = extractSectionNumber(patch.getSectionTitle());

        if (structuredModel != null && sectionNumber >= 1 && sectionNumber <= 5) {
            applyPatchToJson(structuredModel, patch, sectionNumber);
            String renderedMarkdown = schemaMarkdownRenderer.render(structuredModel);
            String json = schemaStructuredParser.toJson(structuredModel);
            schemaManager.saveSchema(
                patch.getScopeId(),
                SchemaSkeletonValidator.WIKI_SCHEMA_KEY,
                renderedMarkdown,
                json,
                schema.getConfigGroup(),
                schema.getDescription(),
                SchemaManager.SOURCE_PATCH,
                patch.getId(),
                userId
            );
            log.info("Schema 补丁已通过结构化路径应用 patchId={} scope={} section={}",
                patchId, patch.getScopeId(), patch.getSectionTitle());
        } else {
            String next = applyPatch(schema.getConfigValue(), patch);
            schemaManager.saveSchema(
                patch.getScopeId(),
                SchemaSkeletonValidator.WIKI_SCHEMA_KEY,
                next,
                schema.getConfigGroup(),
                schema.getDescription(),
                SchemaManager.SOURCE_PATCH,
                patch.getId(),
                userId
            );
            log.info("Schema 补丁已通过文本路径应用 patchId={} scope={} section={}",
                patchId, patch.getScopeId(), patch.getSectionTitle());
        }

        Long newVersionId = schemaManager.getCurrentVersionId(patch.getScopeId(), SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        patch.setStatus(SchemaPatchModel.Status.ACCEPTED.name());
        patch.setDecidedBy(userId);
        patch.setDecidedAt(LocalDateTime.now());
        patch.setAppliedSchemaId(newVersionId);
        schemaPatchMapper.updateById(patch);
        logGatekeeperOutcome(patch, SchemaPatchModel.Status.ACCEPTED);

        supersedeConflictingObserving(patch);

        log.info("Schema 补丁已应用 patchId={} scope={} user={} newVersionId={}",
            patchId, patch.getScopeId(), userId, newVersionId);
        return toModel(patch);
    }

    public SchemaPatchModel reject(Long patchId, Long userId) {
        return markDecided(patchId, userId, SchemaPatchModel.Status.REJECTED);
    }

    public SchemaPatchModel ignore(Long patchId, Long userId) {
        return markDecided(patchId, userId, SchemaPatchModel.Status.IGNORED);
    }

    public record BatchResult(int processed, int failed) {}

    public BatchResult batchAccept(List<Long> patchIds, Long userId) {
        if (patchIds == null || patchIds.isEmpty()) return new BatchResult(0, 0);

        List<SchemaPatchDO> patches = schemaPatchMapper.selectBatchIds(patchIds);
        List<SchemaPatchDO> valid = new ArrayList<>();
        int failed = 0;
        for (SchemaPatchDO p : patches) {
            if (p == null) { failed++; continue; }
            boolean open = SchemaPatchModel.Status.PENDING.name().equals(p.getStatus())
                || SchemaPatchModel.Status.OBSERVING.name().equals(p.getStatus());
            if (!open) {
                log.warn("批量采纳跳过非待处理补丁 patchId={} status={}", p.getId(), p.getStatus());
                failed++;
            } else {
                valid.add(p);
            }
        }
        if (valid.isEmpty()) return new BatchResult(0, failed);

        Long scopeId = valid.get(0).getScopeId();
        SchemaConfigDO schema = schemaManager.getSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        if (schema == null || schema.getConfigValue() == null || schema.getConfigValue().isBlank()) {
            throw new BusinessException(ErrorCode.PATCH_SCHEMA_NOT_READY);
        }

        SchemaStructuredModel structuredModel = schemaManager.getStructuredModel(scopeId);
        boolean usedStructured = (structuredModel != null);
        String textSchema = usedStructured ? null : schema.getConfigValue();

        List<SchemaPatchDO> applied = new ArrayList<>();
        for (SchemaPatchDO patch : valid) {
            try {
                int sectionNumber = extractSectionNumber(patch.getSectionTitle());
                if (usedStructured && sectionNumber >= 1 && sectionNumber <= 5) {
                    applyPatchToJson(structuredModel, patch, sectionNumber);
                } else {
                    textSchema = applyPatch(textSchema, patch);
                }
                applied.add(patch);
            } catch (Exception e) {
                log.warn("批量采纳补丁应用失败 patchId={}: {}", patch.getId(), e.getMessage());
                failed++;
            }
        }
        if (applied.isEmpty()) return new BatchResult(0, failed);

        Long newVersionId = null;
        try {
            if (usedStructured) {
                String renderedMarkdown = schemaMarkdownRenderer.render(structuredModel);
                String json = schemaStructuredParser.toJson(structuredModel);
                schemaManager.saveSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY,
                    renderedMarkdown, json, schema.getConfigGroup(), schema.getDescription(),
                    SchemaManager.SOURCE_PATCH, applied.get(0).getId(), userId);
            } else {
                schemaManager.saveSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY,
                    textSchema, schema.getConfigGroup(), schema.getDescription(),
                    SchemaManager.SOURCE_PATCH, applied.get(0).getId(), userId);
            }
            newVersionId = schemaManager.getCurrentVersionId(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        } catch (Exception e) {
            log.error("批量采纳 Schema 保存失败 scope={}: {}", scopeId, e.getMessage());
            return new BatchResult(0, applied.size() + failed);
        }

        LocalDateTime now = LocalDateTime.now();
        int processed = 0;
        for (SchemaPatchDO patch : applied) {
            try {
                patch.setStatus(SchemaPatchModel.Status.ACCEPTED.name());
                patch.setDecidedBy(userId);
                patch.setDecidedAt(now);
                patch.setAppliedSchemaId(newVersionId);
                schemaPatchMapper.updateById(patch);
                logGatekeeperOutcome(patch, SchemaPatchModel.Status.ACCEPTED);
                processed++;
            } catch (Exception e) {
                log.warn("批量采纳补丁状态更新失败 patchId={}: {}", patch.getId(), e.getMessage());
                failed++;
            }
        }

        Set<String> affectedSections = new LinkedHashSet<>();
        for (SchemaPatchDO p : applied) affectedSections.add(p.getSectionTitle());
        for (String section : affectedSections) {
            try {
                supersedeConflictingObservingBySection(scopeId, section, applied.get(0).getId());
            } catch (Exception e) {
                log.warn("批量采纳 supersede 失败 section={}: {}", section, e.getMessage());
            }
        }

        log.info("批量采纳补丁完成 processed={} failed={} user={} schemaVersions=1 (was {})",
            processed, failed, userId, applied.size());
        return new BatchResult(processed, failed);
    }

    public BatchResult batchReject(List<Long> patchIds, Long userId) {
        return batchMarkDecided(patchIds, userId, SchemaPatchModel.Status.REJECTED);
    }

    public BatchResult batchIgnore(List<Long> patchIds, Long userId) {
        return batchMarkDecided(patchIds, userId, SchemaPatchModel.Status.IGNORED);
    }

    private BatchResult batchMarkDecided(List<Long> patchIds, Long userId, SchemaPatchModel.Status status) {
        if (patchIds == null || patchIds.isEmpty()) return new BatchResult(0, 0);

        List<SchemaPatchDO> patches = schemaPatchMapper.selectBatchIds(patchIds);
        LocalDateTime now = LocalDateTime.now();
        int processed = 0, failed = 0;
        for (SchemaPatchDO patch : patches) {
            if (patch == null) { failed++; continue; }
            boolean open = SchemaPatchModel.Status.PENDING.name().equals(patch.getStatus())
                || SchemaPatchModel.Status.OBSERVING.name().equals(patch.getStatus());
            if (!open) {
                log.warn("批量{}跳过非待处理补丁 patchId={} status={}", status, patch.getId(), patch.getStatus());
                failed++;
                continue;
            }
            try {
                patch.setStatus(status.name());
                patch.setDecidedBy(userId);
                patch.setDecidedAt(now);
                schemaPatchMapper.updateById(patch);
                logGatekeeperOutcome(patch, status);
                processed++;
            } catch (Exception e) {
                log.warn("批量{}补丁失败 patchId={}: {}", status, patch.getId(), e.getMessage());
                failed++;
            }
        }
        log.info("批量{}补丁完成 processed={} failed={} user={}", status, processed, failed, userId);
        return new BatchResult(processed, failed);
    }

    private void supersedeConflictingObservingBySection(Long scopeId, String sectionTitle, Long acceptedPatchId) {
        List<SchemaPatchDO> observing = schemaPatchMapper.selectList(
            new LambdaQueryWrapper<SchemaPatchDO>()
                .eq(SchemaPatchDO::getScopeId, scopeId)
                .eq(SchemaPatchDO::getStatus, SchemaPatchModel.Status.OBSERVING.name())
                .eq(SchemaPatchDO::getSectionTitle, sectionTitle)
        );
        if (observing.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        for (SchemaPatchDO p : observing) {
            p.setStatus(SchemaPatchModel.Status.SUPERSEDED.name());
            p.setDecidedAt(now);
            String tag = "[AutoSupersede] 用户已采纳同section补丁#" + acceptedPatchId;
            String merged = p.getRationale();
            p.setRationale(merged == null || merged.isBlank() ? tag : tag + "\n" + merged);
            schemaPatchMapper.updateById(p);
        }
        log.info("批量采纳后自动SUPERSEDED {} 条同section观察期补丁 scope={} section={}",
            observing.size(), scopeId, sectionTitle);
    }

    public SchemaPatchModel promoteObserving(Long patchId, Long userId) {
        if (patchId == null) throw new BusinessException(ErrorCode.PATCH_ID_NULL);
        SchemaPatchDO patch = schemaPatchMapper.selectById(patchId);
        if (patch == null) throw new BusinessException(ErrorCode.PATCH_NOT_FOUND, patchId);
        if (!SchemaPatchModel.Status.OBSERVING.name().equals(patch.getStatus())) {
            throw new BusinessException(ErrorCode.PATCH_NOT_OBSERVING, patch.getStatus());
        }
        String tag = "[UserPromote] 用户手动提升至待审批";
        String merged = patch.getRationale();
        patch.setRationale(merged == null || merged.isBlank() ? tag : tag + "\n" + merged);
        patch.setStatus(SchemaPatchModel.Status.PENDING.name());
        schemaPatchMapper.updateById(patch);
        log.info("用户手动提升观察期补丁 patchId={} scope={} user={}", patchId, patch.getScopeId(), userId);
        return toModel(patch);
    }

    private SchemaPatchModel markDecided(Long patchId, Long userId, SchemaPatchModel.Status status) {
        SchemaPatchDO patch = loadPending(patchId);
        patch.setStatus(status.name());
        patch.setDecidedBy(userId);
        patch.setDecidedAt(LocalDateTime.now());
        schemaPatchMapper.updateById(patch);
        logGatekeeperOutcome(patch, status);
        return toModel(patch);
    }

    private SchemaPatchDO loadPending(Long patchId) {
        if (patchId == null) throw new BusinessException(ErrorCode.PATCH_ID_NULL);
        SchemaPatchDO patch = schemaPatchMapper.selectById(patchId);
        if (patch == null) throw new BusinessException(ErrorCode.PATCH_NOT_FOUND, patchId);
        boolean open = SchemaPatchModel.Status.PENDING.name().equals(patch.getStatus())
            || SchemaPatchModel.Status.OBSERVING.name().equals(patch.getStatus());
        if (!open) {
            throw new BusinessException(ErrorCode.PATCH_NOT_PENDING, patch.getStatus());
        }
        return patch;
    }

    /**
     * 按 sectionTitle 定位 7 段中的一段，在段内应用 ADD / MODIFY / DELETE。
     * 仅增删改 section 的**子项内容**，绝不触碰 section 标题本身。
     */
    private String applyPatch(String schemaText, SchemaPatchDO patch) {
        String normalized = schemaText.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        String target = patch.getSectionTitle().trim();

        int sectionStart = -1;
        int sectionEnd = lines.length;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(target)) {
                sectionStart = i;
                break;
            }
        }
        if (sectionStart < 0) {
            throw new BusinessException(ErrorCode.PATCH_SECTION_NOT_FOUND, target);
        }
        for (int i = sectionStart + 1; i < lines.length; i++) {
            if (lines[i].startsWith("## ")) {
                sectionEnd = i;
                break;
            }
        }

        StringBuilder sectionBuf = new StringBuilder();
        for (int i = sectionStart + 1; i < sectionEnd; i++) {
            sectionBuf.append(lines[i]);
            if (i < sectionEnd - 1) sectionBuf.append('\n');
        }
        String body = sectionBuf.toString();
        String newBody;

        switch (SchemaPatchModel.Operation.valueOf(patch.getOperation())) {
            case ADD -> {
                if (patch.getDiffAfter() == null || patch.getDiffAfter().isBlank()) {
                    throw new BusinessException(ErrorCode.PATCH_DIFF_EMPTY_ADD);
                }
                String trimmedBody = body.replaceAll("\\s+$", "");
                newBody = trimmedBody.isEmpty()
                    ? patch.getDiffAfter().stripTrailing() + "\n"
                    : trimmedBody + "\n" + patch.getDiffAfter().stripTrailing() + "\n";
            }
            case MODIFY -> {
                if (patch.getDiffBefore() == null || patch.getDiffAfter() == null) {
                    throw new BusinessException(ErrorCode.PATCH_DIFF_EMPTY_MODIFY);
                }
                if (!body.contains(patch.getDiffBefore())) {
                    throw new BusinessException(ErrorCode.PATCH_CONFLICT, "diffBefore not found in current Schema");
                }
                newBody = body.replace(patch.getDiffBefore(), patch.getDiffAfter());
            }
            case DELETE -> {
                if (patch.getDiffBefore() == null || patch.getDiffBefore().isBlank()) {
                    throw new BusinessException(ErrorCode.PATCH_DIFF_EMPTY_DELETE);
                }
                if (!body.contains(patch.getDiffBefore())) {
                    throw new BusinessException(ErrorCode.PATCH_CONFLICT, "diffBefore not found in current Schema");
                }
                newBody = body.replace(patch.getDiffBefore(), "");
            }
            default -> throw new BusinessException(ErrorCode.PATCH_OP_UNKNOWN, patch.getOperation());
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i <= sectionStart; i++) {
            out.append(lines[i]).append('\n');
        }
        out.append(newBody);
        if (!newBody.endsWith("\n")) out.append('\n');
        for (int i = sectionEnd; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private void applyPatchToJson(SchemaStructuredModel model, SchemaPatchDO patch, int sectionNumber) {
        SchemaPatchModel.Operation op = SchemaPatchModel.Operation.valueOf(patch.getOperation());
        switch (sectionNumber) {
            case 1 -> applySection1Patch(model, patch, op);
            case 2 -> applySection2Patch(model, patch, op);
            case 3 -> applySection3Patch(model, patch, op);
            case 4 -> applySection4Patch(model, patch, op);
            case 5 -> applySection5Patch(model, patch, op);
            default -> throw new BusinessException(ErrorCode.PATCH_SECTION_UNSUPPORTED,
                    sectionNumber, patch.getSectionTitle());
        }
    }

    private void applySection1Patch(SchemaStructuredModel model, SchemaPatchDO patch, SchemaPatchModel.Operation op) {
        switch (op) {
            case ADD -> {
                String current = model.getDomainNarrative() == null ? "" : model.getDomainNarrative();
                String addition = patch.getDiffAfter() == null ? "" : patch.getDiffAfter().stripTrailing();
                model.setDomainNarrative(current.isEmpty() ? addition : current + "\n" + addition);
            }
            case MODIFY -> {
                if (patch.getDiffBefore() == null || patch.getDiffAfter() == null) {
                    throw new BusinessException(ErrorCode.PATCH_DIFF_EMPTY_MODIFY);
                }
                String current = model.getDomainNarrative() == null ? "" : model.getDomainNarrative();
                if (!current.contains(patch.getDiffBefore())) {
                    throw new BusinessException(ErrorCode.PATCH_CONFLICT, "diffBefore not found in domain narrative");
                }
                model.setDomainNarrative(current.replace(patch.getDiffBefore(), patch.getDiffAfter()));
            }
            case DELETE -> {
                if (patch.getDiffBefore() == null || patch.getDiffBefore().isBlank()) {
                    throw new BusinessException(ErrorCode.PATCH_DIFF_EMPTY_DELETE);
                }
                String current = model.getDomainNarrative() == null ? "" : model.getDomainNarrative();
                if (!current.contains(patch.getDiffBefore())) {
                    throw new BusinessException(ErrorCode.PATCH_CONFLICT, "diffBefore not found in domain narrative");
                }
                model.setDomainNarrative(current.replace(patch.getDiffBefore(), "").stripTrailing());
            }
        }
    }

    private void applySection2Patch(SchemaStructuredModel model, SchemaPatchDO patch, SchemaPatchModel.Operation op) {
        Taxonomy taxonomy = model.getTaxonomy();
        if (taxonomy == null) {
            taxonomy = new Taxonomy();
            model.setTaxonomy(taxonomy);
        }
        String label = extractLabelFromDiff(patch);
        switch (op) {
            case ADD -> {
                TaxonomyNode node = new TaxonomyNode();
                node.setId(slugify(label));
                node.setLabel(label);
                node.setDescription(extractDescriptionFromDiff(patch.getDiffAfter()));
                taxonomy.getRoots().add(node);
            }
            case MODIFY -> {
                TaxonomyNode existing = findTaxonomyNodeByLabel(taxonomy.getRoots(), label);
                if (existing == null) {
                    String beforeLabel = extractLabelFromDiffBefore(patch);
                    existing = findTaxonomyNodeByLabel(taxonomy.getRoots(), beforeLabel);
                }
                if (existing == null) {
                    throw new BusinessException(ErrorCode.PATCH_CONFLICT, "Taxonomy node not found: " + label);
                }
                String newLabel = extractNewLabelFromDiffAfter(patch.getDiffAfter());
                if (newLabel != null) {
                    existing.setLabel(newLabel);
                    existing.setId(slugify(newLabel));
                }
                String newDesc = extractDescriptionFromDiff(patch.getDiffAfter());
                if (newDesc != null) {
                    existing.setDescription(newDesc);
                }
            }
            case DELETE -> {
                String deleteLabel = extractLabelFromDiffBefore(patch);
                if (deleteLabel == null || deleteLabel.isBlank()) {
                    deleteLabel = label;
                }
                boolean removed = removeTaxonomyNodeByLabel(taxonomy.getRoots(), deleteLabel);
                if (!removed) {
                    throw new BusinessException(ErrorCode.PATCH_CONFLICT, "Taxonomy node not found, cannot delete: " + deleteLabel);
                }
            }
        }
    }

    private void applySection3Patch(SchemaStructuredModel model, SchemaPatchDO patch, SchemaPatchModel.Operation op) {
        Templates templates = model.getTemplates();
        if (templates == null) {
            templates = new Templates();
            model.setTemplates(templates);
        }
        String label = extractLabelFromDiff(patch);
        switch (op) {
            case ADD -> {
                String diffAfter = patch.getDiffAfter() == null ? "" : patch.getDiffAfter();
                if (diffAfter.contains("###") || diffAfter.contains("章节") || diffAfter.contains("section")) {
                    PageTemplate pt = new PageTemplate();
                    pt.setType(slugify(label));
                    pt.setLabel(label);
                    List<SectionDef> sections = parseSectionDefsFromDiff(diffAfter);
                    pt.setSections(sections);
                    templates.getPageTemplates().add(pt);
                } else {
                    PageTemplate existing = templates.findByType(slugify(label));
                    if (existing == null) {
                        throw new BusinessException(ErrorCode.PATCH_CONFLICT, "Page template not found: " + label);
                    }
                    SectionDef sd = new SectionDef();
                    sd.setId(slugify(extractFirstLine(diffAfter)));
                    sd.setLabel(extractFirstLine(diffAfter));
                    sd.setRequired(!diffAfter.contains("可选"));
                    sd.setOrder(existing.getSections().size() + 1);
                    existing.getSections().add(sd);
                }
            }
            case MODIFY -> {
                String beforeLabel = extractLabelFromDiffBefore(patch);
                PageTemplate existing = templates.findByType(slugify(label));
                if (existing == null && beforeLabel != null) {
                    existing = templates.findByType(slugify(beforeLabel));
                }
                if (existing == null) {
                    throw new BusinessException(ErrorCode.PATCH_CONFLICT, "Page template not found: " + label);
                }
                String newLabel = extractNewLabelFromDiffAfter(patch.getDiffAfter());
                if (newLabel != null) {
                    existing.setLabel(newLabel);
                    existing.setType(slugify(newLabel));
                }
            }
            case DELETE -> {
                String rawDeleteLabel = extractLabelFromDiffBefore(patch);
                final String deleteLabel = (rawDeleteLabel == null || rawDeleteLabel.isBlank()) ? label : rawDeleteLabel;
                String slugType = slugify(deleteLabel);
                boolean removed = templates.getPageTemplates().removeIf(
                    pt -> slugType.equalsIgnoreCase(pt.getType()) || deleteLabel.equals(pt.getLabel()));
                if (!removed) {
                    Iterator<PageTemplate> it = templates.getPageTemplates().iterator();
                    while (it.hasNext()) {
                        PageTemplate pt = it.next();
                        boolean sectionRemoved = pt.getSections().removeIf(
                            sd -> deleteLabel.equals(sd.getLabel()) || slugType.equalsIgnoreCase(sd.getId()));
                        if (sectionRemoved) {
                            removed = true;
                            break;
                        }
                    }
                }
                if (!removed) {
                    throw new BusinessException(ErrorCode.PATCH_CONFLICT, "Page template or section not found, cannot delete: " + deleteLabel);
                }
            }
        }
    }

    private void applySection4Patch(SchemaStructuredModel model, SchemaPatchDO patch, SchemaPatchModel.Operation op) {
        Naming naming = model.getNaming();
        if (naming == null) {
            naming = new Naming();
            model.setNaming(naming);
        }
        String diffText = op == SchemaPatchModel.Operation.DELETE
            ? (patch.getDiffBefore() == null ? "" : patch.getDiffBefore())
            : (patch.getDiffAfter() == null ? "" : patch.getDiffAfter());

        NamingRule targetRule = diffText.contains("摘要") || diffText.contains("summary")
            ? naming.getSummary() : naming.getEntity();

        switch (op) {
            case ADD, MODIFY -> {
                applyNamingRuleFromDiff(targetRule, diffText);
                String example = extractExampleFromDiff(diffText);
                if (example != null) {
                    naming.getExamples().add(example);
                }
            }
            case DELETE -> {
                String deleteText = patch.getDiffBefore() == null ? "" : patch.getDiffBefore();
                naming.getExamples().removeIf(ex -> deleteText.contains(ex));
            }
        }
    }

    private void applySection5Patch(SchemaStructuredModel model, SchemaPatchDO patch, SchemaPatchModel.Operation op) {
        Workflow workflow = model.getWorkflow();
        if (workflow == null) {
            workflow = new Workflow();
            model.setWorkflow(workflow);
        }
        String diffText = op == SchemaPatchModel.Operation.DELETE
            ? (patch.getDiffBefore() == null ? "" : patch.getDiffBefore())
            : (patch.getDiffAfter() == null ? "" : patch.getDiffAfter());

        switch (op) {
            case ADD -> {
                if (diffText.contains("CONFIRM") || diffText.contains("确认") || diffText.contains("审批")) {
                    if (diffText.contains("触发") || diffText.contains("必须确认") || diffText.contains("需要审批")) {
                        workflow.getConfirmTriggers().add(extractFirstLine(diffText));
                    } else {
                        workflow.setDefaultApproval("CONFIRM");
                    }
                } else if (diffText.contains("AUTO") || diffText.contains("自动")) {
                    workflow.setDefaultApproval("AUTO");
                } else {
                    String current = workflow.getNarrative() == null ? "" : workflow.getNarrative();
                    workflow.setNarrative(current.isEmpty() ? diffText.stripTrailing() : current + "\n" + diffText.stripTrailing());
                }
            }
            case MODIFY -> {
                if (diffText.contains("CONFIRM")) {
                    workflow.setDefaultApproval("CONFIRM");
                } else if (diffText.contains("AUTO")) {
                    workflow.setDefaultApproval("AUTO");
                } else if (patch.getDiffBefore() != null && workflow.getNarrative() != null) {
                    workflow.setNarrative(workflow.getNarrative().replace(patch.getDiffBefore(), diffText));
                }
            }
            case DELETE -> {
                String deleteText = patch.getDiffBefore() == null ? "" : patch.getDiffBefore();
                workflow.getConfirmTriggers().removeIf(t -> t.contains(deleteText) || deleteText.contains(t));
            }
        }
    }

    private int extractSectionNumber(String sectionTitle) {
        if (sectionTitle == null) return -1;
        Matcher m = SECTION_NUMBER_PATTERN.matcher(sectionTitle.trim());
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private String extractLabelFromDiff(SchemaPatchDO patch) {
        String text = patch.getDiffAfter() != null && !patch.getDiffAfter().isBlank()
            ? patch.getDiffAfter() : patch.getDiffBefore();
        if (text == null || text.isBlank()) return "unknown";
        return extractFirstMeaningfulLabel(text);
    }

    private String extractLabelFromDiffBefore(SchemaPatchDO patch) {
        if (patch.getDiffBefore() == null || patch.getDiffBefore().isBlank()) return null;
        return extractFirstMeaningfulLabel(patch.getDiffBefore());
    }

    private String extractFirstMeaningfulLabel(String text) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("### ")) {
                return trimmed.substring(4).trim();
            }
            if (trimmed.startsWith("#### ")) {
                return trimmed.substring(5).trim();
            }

            Matcher m = LABEL_FROM_DIFF.matcher(trimmed);
            if (m.find()) {
                for (int i = 1; i <= m.groupCount(); i++) {
                    if (m.group(i) != null && !m.group(i).isBlank()) {
                        return m.group(i).trim();
                    }
                }
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                String content = trimmed.substring(2).trim();
                int colonIdx = content.indexOf('：');
                if (colonIdx < 0) colonIdx = content.indexOf(':');
                if (colonIdx > 0 && colonIdx < 30) return content.substring(0, colonIdx).trim();
                int dashIdx = content.indexOf("——");
                if (dashIdx < 0) dashIdx = content.indexOf(" - ");
                if (dashIdx > 0 && dashIdx < 30) return content.substring(0, dashIdx).trim();
                return content.length() <= 40 ? content : content.substring(0, 40);
            }
        }
        return text.length() <= 40 ? text.trim() : text.substring(0, 40).trim();
    }

    private String extractNewLabelFromDiffAfter(String diffAfter) {
        if (diffAfter == null || diffAfter.isBlank()) return null;
        return extractFirstMeaningfulLabel(diffAfter);
    }

    private String extractDescriptionFromDiff(String text) {
        if (text == null) return null;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            int colonIdx = trimmed.indexOf('：');
            if (colonIdx < 0) colonIdx = trimmed.indexOf(':');
            if (colonIdx > 0 && colonIdx < trimmed.length() - 1) {
                String desc = trimmed.substring(colonIdx + 1).trim();
                if (!desc.isEmpty()) return desc;
            }
            int dashIdx = trimmed.indexOf("——");
            if (dashIdx < 0) dashIdx = trimmed.indexOf(" - ");
            if (dashIdx > 0 && dashIdx < trimmed.length() - 2) {
                String desc = trimmed.substring(dashIdx + 2).trim();
                if (!desc.isEmpty()) return desc;
            }
        }
        return null;
    }

    private String extractFirstLine(String text) {
        if (text == null) return "";
        String trimmed = text.stripLeading();
        int nl = trimmed.indexOf('\n');
        String first = nl < 0 ? trimmed : trimmed.substring(0, nl);
        first = first.replaceAll("^[-*+]\\s+", "").replaceAll("^\\d+[.、]\\s*", "").trim();
        return first;
    }

    private String extractExampleFromDiff(String text) {
        if (text == null) return null;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.contains("例") || trimmed.contains("如") || trimmed.contains("example")) {
                String content = trimmed.replaceAll("^[-*+]\\s+", "").replaceAll("^\\d+[.、]\\s*", "").trim();
                if (content.length() < 80) return content;
            }
        }
        return null;
    }

    private void applyNamingRuleFromDiff(NamingRule rule, String text) {
        if (text == null) return;
        for (String line : text.split("\n")) {
            String trimmed = line.trim().toLowerCase();
            if (trimmed.contains("中文") || trimmed.contains("zh-cn")) {
                rule.setLanguage("zh-CN");
            } else if (trimmed.contains("english") || trimmed.contains("英文")) {
                rule.setLanguage("en");
            }
            Matcher maxLen = Pattern.compile("(\\d+)\\s*[字个字符]").matcher(trimmed);
            if (maxLen.find()) {
                rule.setMaxLength(Integer.parseInt(maxLen.group(1)));
            }
            if (trimmed.contains("禁止前缀") || trimmed.contains("forbidden")) {
                Matcher quoted = Pattern.compile("[「『\"'【](.+?)[」』\"'】]").matcher(line);
                while (quoted.find()) {
                    String prefix = quoted.group(1).trim();
                    if (!prefix.isEmpty() && !rule.getForbiddenPrefixes().contains(prefix)) {
                        rule.getForbiddenPrefixes().add(prefix);
                    }
                }
            }
        }
    }

    private List<SectionDef> parseSectionDefsFromDiff(String text) {
        List<SectionDef> defs = new ArrayList<>();
        int order = 0;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#### ")) {
                String heading = trimmed.substring(5).trim();
                SectionDef sd = new SectionDef();
                sd.setId(slugify(heading));
                sd.setLabel(heading);
                sd.setRequired(true);
                sd.setOrder(++order);
                defs.add(sd);
            } else if (trimmed.matches("^\\d+[.、].*")) {
                String content = trimmed.replaceFirst("^\\d+[.、]\\s*", "").trim();
                String label = content.replaceAll("[（(](?:必需|可选|必填)[）)]", "").trim();
                SectionDef sd = new SectionDef();
                sd.setId(slugify(label));
                sd.setLabel(label);
                sd.setRequired(!content.contains("可选") && !content.contains("optional"));
                sd.setOrder(++order);
                defs.add(sd);
            }
        }
        return defs;
    }

    private TaxonomyNode findTaxonomyNodeByLabel(List<TaxonomyNode> nodes, String label) {
        if (nodes == null || label == null) return null;
        for (TaxonomyNode node : nodes) {
            if (label.equalsIgnoreCase(node.getLabel()) || label.equalsIgnoreCase(node.getId())) {
                return node;
            }
            TaxonomyNode found = findTaxonomyNodeByLabel(node.getChildren(), label);
            if (found != null) return found;
        }
        return null;
    }

    private boolean removeTaxonomyNodeByLabel(List<TaxonomyNode> nodes, String label) {
        if (nodes == null || label == null) return false;
        Iterator<TaxonomyNode> it = nodes.iterator();
        while (it.hasNext()) {
            TaxonomyNode node = it.next();
            if (label.equalsIgnoreCase(node.getLabel()) || label.equalsIgnoreCase(node.getId())) {
                it.remove();
                return true;
            }
            if (removeTaxonomyNodeByLabel(node.getChildren(), label)) {
                return true;
            }
        }
        return false;
    }

    private String slugify(String label) {
        if (label == null) return "unknown";
        return label.trim()
            .toLowerCase()
            .replaceAll("[\\s_]+", "-")
            .replaceAll("[^\\w\\u4e00-\\u9fff-]", "")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
    }

    private SchemaPatchModel toModel(SchemaPatchDO r) {
        SchemaPatchModel m = new SchemaPatchModel();
        m.setId(r.getId());
        m.setScopeId(r.getScopeId());
        m.setSourceExecutionId(r.getSourceExecutionId());
        m.setSourceType(r.getSourceType());
        m.setSectionTitle(r.getSectionTitle());
        m.setOperation(r.getOperation());
        m.setDiffBefore(r.getDiffBefore());
        m.setDiffAfter(r.getDiffAfter());
        m.setRationale(r.getRationale());
        m.setEvidenceJson(r.getEvidenceJson());
        m.setConfidence(r.getConfidence());
        m.setStatus(r.getStatus());
        m.setDecidedBy(r.getDecidedBy());
        m.setDecidedAt(r.getDecidedAt());
        m.setAppliedSchemaId(r.getAppliedSchemaId());
        m.setGatekeeperDecision(r.getGatekeeperDecision());
        m.setGatekeeperReason(r.getGatekeeperReason());
        m.setCreatedAt(r.getCreatedAt());
        return m;
    }

    private void logGatekeeperOutcome(SchemaPatchDO patch, SchemaPatchModel.Status userDecision) {
        String gk = patch.getGatekeeperDecision();
        if (gk == null || gk.isBlank()) return;
        String outcome = switch (userDecision) {
            case ACCEPTED -> "APPROVE".equals(gk) ? "MATCH" : "MISS";
            case REJECTED -> "REJECT".equals(gk) ? "MATCH"
                : "OBSERVE".equals(gk) ? "SOFT_MISS" : "MISS";
            case IGNORED  -> "OBSERVE".equals(gk) ? "MATCH" : "MISS";
            default -> "N/A";
        };
        log.info("Gatekeeper 回流 patchId={} scope={} gatekeeper={} user={} outcome={}",
            patch.getId(), patch.getScopeId(), gk, userDecision.name(), outcome);
    }

    private void supersedeConflictingObserving(SchemaPatchDO acceptedPatch) {
        List<SchemaPatchDO> observing = schemaPatchMapper.selectList(
            new LambdaQueryWrapper<SchemaPatchDO>()
                .eq(SchemaPatchDO::getScopeId, acceptedPatch.getScopeId())
                .eq(SchemaPatchDO::getStatus, SchemaPatchModel.Status.OBSERVING.name())
                .eq(SchemaPatchDO::getSectionTitle, acceptedPatch.getSectionTitle())
        );
        if (observing.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (SchemaPatchDO p : observing) {
            p.setStatus(SchemaPatchModel.Status.SUPERSEDED.name());
            p.setDecidedAt(now);
            String tag = "[AutoSupersede] 用户已采纳同section补丁#" + acceptedPatch.getId();
            String merged = p.getRationale();
            p.setRationale(merged == null || merged.isBlank() ? tag : tag + "\n" + merged);
            schemaPatchMapper.updateById(p);
            count++;
        }
        log.info("接受补丁#{} 后自动SUPERSEDED {} 条同section观察期补丁 scope={} section={}",
            acceptedPatch.getId(), count, acceptedPatch.getScopeId(), acceptedPatch.getSectionTitle());
    }

    // ===== 宪法规则 7：SchemaLint 调度器相关支持方法 =====

    /** 调度器扫描所有有 OBSERVING 补丁的 scope。 */
    public List<Long> listScopesWithObserving() {
        List<SchemaPatchDO> rows = schemaPatchMapper.selectList(
            new LambdaQueryWrapper<SchemaPatchDO>()
                .eq(SchemaPatchDO::getStatus, SchemaPatchModel.Status.OBSERVING.name())
                .select(SchemaPatchDO::getScopeId)
        );
        return rows.stream()
            .map(SchemaPatchDO::getScopeId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    }

    /** 调度器用：拉 scope 全部 OBSERVING 补丁的 DO（避免 Model 转换损耗 DO 细节）。 */
    public List<SchemaPatchDO> listObservingRaw(Long scopeId) {
        if (scopeId == null) return List.of();
        return schemaPatchMapper.selectList(
            new LambdaQueryWrapper<SchemaPatchDO>()
                .eq(SchemaPatchDO::getScopeId, scopeId)
                .eq(SchemaPatchDO::getStatus, SchemaPatchModel.Status.OBSERVING.name())
                .orderByDesc(SchemaPatchDO::getCreatedAt)
        );
    }

    /**
     * 规则 7:观察期自动提升。将代表条升级为 PENDING 并在 rationale 前记录原因;
     * 其余同组条目标记为 SUPERSEDED(保留历史,不删除)。
     */
    public void autoPromote(SchemaPatchDO representative, List<SchemaPatchDO> superseded, String reason) {
        if (representative == null) return;
        LocalDateTime now = LocalDateTime.now();
        String tag = reason == null || reason.isBlank()
            ? "[AutoPromote]"
            : "[AutoPromote] " + reason.trim();
        String merged = representative.getRationale();
        representative.setRationale(merged == null || merged.isBlank() ? tag : tag + "\n" + merged);
        representative.setStatus(SchemaPatchModel.Status.PENDING.name());
        schemaPatchMapper.updateById(representative);
    
        if (superseded != null) {
            for (SchemaPatchDO s : superseded) {
                if (s.getId().equals(representative.getId())) continue;
                s.setStatus(SchemaPatchModel.Status.SUPERSEDED.name());
                s.setDecidedAt(now);
                schemaPatchMapper.updateById(s);
            }
        }
        log.info("自动提升补丁 patchId={} scope={} reason={} supersededCount={}",
            representative.getId(), representative.getScopeId(), reason,
            superseded == null ? 0 : Math.max(0, superseded.size() - 1));
    }
    
    /**
     * 规则 7:观察期超期归档。将置信度低且观察时间长的补丁标记为 EXPIRED,
     * 避免观察区成为永久垃圾堆。
     */
    public void expirePatch(SchemaPatchDO patch, String reason) {
        if (patch == null) return;
        LocalDateTime now = LocalDateTime.now();
        String tag = reason == null || reason.isBlank()
            ? "[AutoExpire]"
            : "[AutoExpire] " + reason.trim();
        String merged = patch.getRationale();
        patch.setRationale(merged == null || merged.isBlank() ? tag : tag + "\n" + merged);
        patch.setStatus(SchemaPatchModel.Status.EXPIRED.name());
        patch.setDecidedAt(now);
        schemaPatchMapper.updateById(patch);
        log.info("观察期补丁超期归档 patchId={} scope={} confidence={} days={}",
            patch.getId(), patch.getScopeId(), patch.getConfidence(), reason);
    }

    /**
     * 按状态计数。供 Dashboard Schema 共治健康面板聚合。
     */
    public int countByStatus(Long scopeId, SchemaPatchModel.Status status) {
        if (scopeId == null || status == null) return 0;
        Long n = schemaPatchMapper.selectCount(
            new LambdaQueryWrapper<SchemaPatchDO>()
                .eq(SchemaPatchDO::getScopeId, scopeId)
                .eq(SchemaPatchDO::getStatus, status.name())
        );
        return n == null ? 0 : n.intValue();
    }

    /**
     * 读取 scope 下所有用户已终态（ACCEPTED/REJECTED/IGNORED）且 gatekeeper 有决定的补丁，
     * 供 Dashboard 计算 Gatekeeper 一致率。为避免全表扫描，仅取必要字段。
     */
    public List<SchemaPatchDO> listGatekeeperEvaluated(Long scopeId) {
        if (scopeId == null) return List.of();
        return schemaPatchMapper.selectList(
            new LambdaQueryWrapper<SchemaPatchDO>()
                .eq(SchemaPatchDO::getScopeId, scopeId)
                .isNotNull(SchemaPatchDO::getGatekeeperDecision)
                .in(SchemaPatchDO::getStatus,
                    SchemaPatchModel.Status.ACCEPTED.name(),
                    SchemaPatchModel.Status.REJECTED.name(),
                    SchemaPatchModel.Status.IGNORED.name())
                .select(SchemaPatchDO::getGatekeeperDecision, SchemaPatchDO::getStatus)
        );
    }
}
