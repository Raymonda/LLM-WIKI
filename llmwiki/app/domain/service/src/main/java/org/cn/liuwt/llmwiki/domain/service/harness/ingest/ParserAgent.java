package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.util.FileFormatValidator;
import org.cn.liuwt.llmwiki.domain.service.harness.DocumentChunker;
import org.cn.liuwt.llmwiki.domain.service.harness.DocumentProfile;
import org.cn.liuwt.llmwiki.domain.service.harness.DocumentStructureAnalyzer;
import org.cn.liuwt.llmwiki.domain.service.harness.ExecutionStrategy;
import org.cn.liuwt.llmwiki.domain.service.harness.IngestionStrategyAdvisor;
import org.cn.liuwt.llmwiki.domain.service.harness.PythonProcessRunner;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.integration.ai.AiSlotRouter;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import org.cn.liuwt.llmwiki.domain.service.harness.ParsedSourceIndex;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class ParserAgent {

    private static final Logger log = LoggerFactory.getLogger(ParserAgent.class);

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private OcrProperties ocrProperties;

    @Autowired
    private DiagramProperties diagramProperties;

    @Autowired
    private SearchService searchService;

    @Autowired
    private AiSlotRouter slotRouter;

    @Value("${spring.ai.openai.api-key:}")
    private String dashscopeApiKey;

    @Value("${llmwiki.ai.multimodal:false}")
    private boolean multimodalMainEnabled;

    @Value("${llmwiki.ai.multimodal-model:qwen3.6-flash}")
    private String multimodalModelName;

    public void parseAndLoad(IngestContext context) {
        Long scopeId = context.getScopeId();
        Long sourceId = context.getSourceId();
        String scopeIdStr = String.valueOf(scopeId);

        SourceDO sourceDO = sourceMapper.selectById(sourceId);
        if (sourceDO == null) {
            throw new RuntimeException("Source not found: id=" + sourceId);
        }

        context.setSourceName(sourceDO.getName());

        boolean needsParse = needsDocumentParsing(sourceDO.getFormat());

        if (needsParse) {
            byte[] fileBytes = storageProvider.read(scopeIdStr, sourceDO.getFilePath());
            if (fileBytes == null) {
                throw new RuntimeException("Source file not found in storage: " + sourceDO.getFilePath());
            }
            if (!FileFormatValidator.isValidFormat(sourceDO.getFormat(), fileBytes)) {
                throw new RuntimeException("文件格式校验失败");
            }

            String format = sourceDO.getFormat();

            boolean ocrEnable = !multimodalMainEnabled && ocrProperties.isEnabled()
                && ("pdf".equals(format.toLowerCase()) || "docx".equals(format.toLowerCase()) || "doc".equals(format.toLowerCase())
                    || "pptx".equals(format.toLowerCase()) || "ppt".equals(format.toLowerCase()));
            boolean multimodalMain = multimodalMainEnabled
                && ("pdf".equals(format.toLowerCase()) || "docx".equals(format.toLowerCase()) || "doc".equals(format.toLowerCase())
                    || "pptx".equals(format.toLowerCase()) || "ppt".equals(format.toLowerCase()));
            boolean diagramEnable = diagramProperties.isEnabled()
                && ("pdf".equals(format.toLowerCase()) || "docx".equals(format.toLowerCase()) || "doc".equals(format.toLowerCase())
                    || "pptx".equals(format.toLowerCase()) || "ppt".equals(format.toLowerCase()));
            String ocrModel = ocrProperties.getModel();
            String apiKey = dashscopeApiKey;
            boolean needsAssets = multimodalMain || diagramEnable
                || "pdf".equalsIgnoreCase(format) || "docx".equalsIgnoreCase(format)
                || "doc".equalsIgnoreCase(format) || "pptx".equalsIgnoreCase(format)
                || "ppt".equalsIgnoreCase(format);
            String assetsDir = needsAssets ? getStorageAbsolutePath(scopeIdStr, "assets") : null;
            String diagramApiKey = diagramProperties.getApiKey();
            if (diagramApiKey == null || diagramApiKey.isBlank()) {
                diagramApiKey = apiKey;
            }

            long timeout = diagramEnable ? Math.max(120, diagramProperties.getTimeoutMs() / 1000) : 120;
            PythonProcessRunner runner = new PythonProcessRunner(timeout);
            AiSlotRouter.Endpoint ocrEndpoint = slotRouter.getEndpoint("ocr");
            AiSlotRouter.Endpoint diagramEndpoint = slotRouter.getEndpoint("diagram");
            runner.setOcrBaseUrl(ocrEndpoint.baseUrl());
            runner.setDiagramBaseUrl(diagramEndpoint.baseUrl());
            String filePath = getStorageAbsolutePath(scopeIdStr, sourceDO.getFilePath());

            String jsonOutput = runner.run(filePath, format, ocrEnable, apiKey, ocrModel,
                ocrProperties.getMaxPages(), ocrProperties.getScanThreshold(), multimodalMain, assetsDir,
                diagramEnable, diagramApiKey, diagramProperties.getModel(), diagramProperties.getMaxImages(),
                diagramProperties.getDpi(), diagramProperties.getJpegQuality(), diagramProperties.getConcurrency(),
                diagramProperties.getScoreThreshold(), diagramProperties.getLargeDrawingRatio(),
                diagramProperties.getSignificantImageRatio(), diagramProperties.getPayloadGateMb(),
                multimodalModelName);

            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                java.util.Map<String, Object> parsed = mapper.readValue(jsonOutput, java.util.Map.class);
                if (parsed.containsKey("error")) {
                    throw new RuntimeException(parsed.get("error").toString());
                }

                String parsedContent = (String) parsed.get("content");

                Object ocrPagesObj = parsed.get("ocrPages");
                if (ocrPagesObj != null) {
                    int ocrPages = ((Number) ocrPagesObj).intValue();
                    log.info("OCR recognized {} scan pages for scopeId={}, sourceId={}", ocrPages, scopeId, sourceId);
                }
                Object ocrImageCountObj = parsed.get("ocrImageCount");
                if (ocrImageCountObj != null) {
                    int ocrImageCount = ((Number) ocrImageCountObj).intValue();
                    log.info("OCR recognized {} PPTX images for scopeId={}, sourceId={}", ocrImageCount, scopeId, sourceId);
                }

                Object chartImagesObj = parsed.get("chartImages");
                if (chartImagesObj instanceof java.util.List) {
                    @SuppressWarnings("unchecked")
                    java.util.List<java.util.Map<String, Object>> chartList = (java.util.List<java.util.Map<String, Object>>) chartImagesObj;
                    java.util.List<IngestContext.ChartInfo> charts = new java.util.ArrayList<>();
                    for (java.util.Map<String, Object> chart : chartList) {
                        String relativePath = (String) chart.get("relativePath");
                        String description = (String) chart.get("description");
                        String chartSource = (String) chart.get("source");
                        Object pageObj = chart.get("page");
                        int page = pageObj instanceof Number ? ((Number) pageObj).intValue() : 0;
                        if (relativePath != null && !relativePath.isBlank()) {
                            charts.add(new IngestContext.ChartInfo(relativePath, description != null ? description : "", chartSource != null ? chartSource : "", page));
                        }
                    }
                    context.setChartInfos(charts);
                    if (!charts.isEmpty()) {
                        log.info("Chart images detected: count={}, scopeId={}, sourceId={}", charts.size(), scopeId, sourceId);
                    }
                }

                Object diagramCountObj = parsed.get("diagramCount");
                if (diagramCountObj != null) {
                    int diagramCount = ((Number) diagramCountObj).intValue();
                    String diagramEngine = (String) parsed.get("diagramEngine");
                    log.info("Diagram analysis found {} structural diagrams for scopeId={}, sourceId={}, engine={}",
                        diagramCount, scopeId, sourceId, diagramEngine);
                }
                Object diagramFailedObj = parsed.get("diagramFailedCount");
                if (diagramFailedObj != null) {
                    int diagramFailed = ((Number) diagramFailedObj).intValue();
                    if (diagramFailed > 0) {
                        log.warn("Diagram analysis failed on {} images for scopeId={}, sourceId={}",
                            diagramFailed, scopeId, sourceId);
                    }
                }

                Object extractedImagesObj = parsed.get("extractedImages");
                if (extractedImagesObj instanceof java.util.List) {
                    @SuppressWarnings("unchecked")
                    java.util.List<java.util.Map<String, Object>> imgList = (java.util.List<java.util.Map<String, Object>>) extractedImagesObj;
                    java.util.List<IngestContext.ExtractedImage> images = new java.util.ArrayList<>();
                    for (java.util.Map<String, Object> img : imgList) {
                        String path = (String) img.get("path");
                        String desc = (String) img.get("description");
                        if (path != null) {
                            images.add(new IngestContext.ExtractedImage(path, desc != null ? desc : ""));
                        }
                    }
                    context.setExtractedImages(images);
                    log.info("Multimodal: saved {} images for scopeId={}, sourceId={}", images.size(), scopeId, sourceId);
                }

                String parsedPath = "parsed/" + sourceId + ".parsed.md";
                storageProvider.write(scopeIdStr, parsedPath, parsedContent.getBytes(StandardCharsets.UTF_8));
                context.setParsedContent(parsedContent);
                context.setSourceContent(parsedContent);

                // Extract chart description blocks for entity/summary enrichment
                List<String> chartBlocks = extractChartDescriptionBlocks(parsedContent);
                context.setChartDescriptionBlocks(chartBlocks);
                if (!chartBlocks.isEmpty()) {
                    log.info("Extracted {} chart description blocks from parsed content for scopeId={}, sourceId={}",
                        chartBlocks.size(), scopeId, sourceId);
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("解析 Python 输出失败: " + e.getMessage());
            }

            sourceDO.setStatus("processing");
            sourceMapper.updateById(sourceDO);
        }

        if (context.getSourceContent() == null) {
            String readPath = "parsed/" + sourceId + ".parsed.md";
            byte[] parsedContent = storageProvider.read(scopeIdStr, readPath);

            if (parsedContent != null) {
                context.setSourceContent(new String(parsedContent, StandardCharsets.UTF_8));
            } else {
                byte[] sourceContent = storageProvider.read(scopeIdStr, sourceDO.getFilePath());
                if (sourceContent == null) {
                    throw new RuntimeException("Source file not found: " + sourceDO.getFilePath());
                }
                context.setSourceContent(new String(sourceContent, StandardCharsets.UTF_8));
                sourceDO.setStatus("processing");
                sourceMapper.updateById(sourceDO);
            }
        }

        DocumentChunker chunker = new DocumentChunker();
        java.util.List<DocumentChunker.Chunk> initialChunks = chunker.chunk(context.getSourceContent());

        DocumentStructureAnalyzer structureAnalyzer = new DocumentStructureAnalyzer();
        DocumentStructureAnalyzer.StructureReport report = structureAnalyzer.analyze(context.getSourceContent(), initialChunks, context.getSourceName());
        context.setDocumentType(report.type());
        context.setChapters(report.chapters());
        context.setTotalChapterCount(report.chapters().size());
        log.info("DocumentStructure: type={}, chapters={}, H1={}, H2={}, ruleDensity={}, codeRatio={}, tableRatio={}",
            report.type(), report.chapters().size(), report.totalH1(), report.totalH2(),
            String.format("%.3f", report.ruleDensity()),
            String.format("%.2f%%", report.codeBlockRatio() * 100),
            String.format("%.2f%%", report.tableRatio() * 100));

        writeParsedSourceIndex(scopeIdStr, sourceId, sourceDO, report, context.getSourceContent());

        java.util.List<DocumentChunker.Chunk> chunks;
        if (report.chapters() != null && !report.chapters().isEmpty()) {
            chunks = chunker.chunk(context.getSourceContent(), report.chapters());
            log.info("Chapter-aware chunking: {} chunks (from {} initial chunks)", chunks.size(), initialChunks.size());
        } else {
            chunks = initialChunks;
        }
        context.setChunks(chunks);
        context.setChunkCount(chunks.size());

        DocumentProfile profile = buildProfile(report, context, chunks);
        context.setDocumentProfile(profile);

        IngestionStrategyAdvisor advisor = new IngestionStrategyAdvisor();
        ExecutionStrategy strategy = advisor.selectStrategy(profile);
        context.setStrategy(strategy);
    }

    private DocumentProfile buildProfile(DocumentStructureAnalyzer.StructureReport report,
                                          IngestContext context,
                                          java.util.List<DocumentChunker.Chunk> chunks) {
        String sourceContent = context.getSourceContent();
        int totalLength = sourceContent != null ? sourceContent.length() : 0;
        boolean hasImages = context.hasExtractedImages();

        String topHeadings = "";
        if (report.chapters() != null && !report.chapters().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int limit = Math.min(report.chapters().size(), 10);
            for (int i = 0; i < limit; i++) {
                if (i > 0) sb.append(", ");
                sb.append(report.chapters().get(i).title());
            }
            topHeadings = sb.toString();
        }

        return new DocumentProfile(
            totalLength,
            chunks.size(),
            report.totalH1(),
            report.totalH2(),
            report.totalH3(),
            report.ruleDensity(),
            report.codeBlockRatio(),
            report.tableRatio(),
            hasImages,
            topHeadings,
            report.type(),
            report.chapters()
        );
    }

    private boolean needsDocumentParsing(String format) {
        if (format == null) return false;
        String lower = format.toLowerCase();
        return "pdf".equals(lower) || "docx".equals(lower) || "xlsx".equals(lower)
            || "pptx".equals(lower) || "doc".equals(lower) || "xls".equals(lower)
            || "ppt".equals(lower);
    }

    private void writeParsedSourceIndex(String scopeIdStr, Long sourceId, SourceDO sourceDO,
                                         DocumentStructureAnalyzer.StructureReport report, String sourceContent) {
        try {
            String indexJson = ParsedSourceIndex.buildIndexJson(
                sourceId, sourceDO.getName(), sourceDO.getFormat(),
                sourceContent != null ? sourceContent.length() : 0,
                report.type().name(), report.chapters(), sourceContent);
            String indexPath = "parsed/" + sourceId + ".index.json";
            storageProvider.write(scopeIdStr, indexPath, indexJson.getBytes(StandardCharsets.UTF_8));
            log.info("Wrote parsed source index: sourceId={}, headings={}, chapters={}",
                sourceId,
                report.chapters().stream().mapToInt(c -> c.subChapters().size() + 1).sum(),
                report.chapters().size());
        } catch (Exception e) {
            log.warn("Failed to write parsed source index for sourceId={}: {}", sourceId, e.getMessage());
        }
    }

    private String getStorageAbsolutePath(String scopeIdStr, String relativePath) {
        return storageProvider.getUrl(scopeIdStr, relativePath);
    }

    /**
     * Extract VL-generated chart description blocks from parsed content.
     * These blocks contain structured data (tables, key points, trends) that are
     * critical for entity/summary page writing but often missed by name-based chunk matching.
     *
     * Chart blocks are identified by heading markers (### or bold patterns) followed by
     * GFM tables or chart-related keywords.
     */
    private List<String> extractChartDescriptionBlocks(String content) {
        if (content == null || content.isEmpty()) return List.of();
        List<String> blocks = new java.util.ArrayList<>();
        String[] lines = content.split("\n");
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            boolean isChartHeader = false;

            // Pattern 1: ### heading containing chart keywords
            if (line.startsWith("### ") && containsChartKeyword(line)) {
                isChartHeader = true;
            }
            // Pattern 2: > **bold heading** (VL description blockquote format)
            if (line.startsWith("> **") && (containsChartKeyword(line) || hasNextTable(lines, i))) {
                isChartHeader = true;
            }
            // Pattern 3: Heading followed by chart image reference
            if ((line.startsWith("## ") || line.startsWith("### ")) && containsChartKeyword(line)) {
                isChartHeader = true;
            }

            if (isChartHeader) {
                int start = i;
                i++;
                // Consume the block: until double blank line or next major heading
                int blankCount = 0;
                while (i < lines.length) {
                    String l = lines[i].trim();
                    if (l.isEmpty()) {
                        blankCount++;
                        if (blankCount >= 2) break;
                    } else {
                        blankCount = 0;
                        // Stop at next ## heading (but not ### which may be part of chart)
                        if (l.startsWith("## ") && !l.startsWith("### ")) break;
                        // Stop at page separator
                        if (l.equals("---")) break;
                    }
                    i++;
                }
                StringBuilder block = new StringBuilder();
                for (int j = start; j < i; j++) {
                    block.append(lines[j]).append("\n");
                }
                String blockStr = block.toString().trim();
                if (!blockStr.isEmpty()) {
                    blocks.add(blockStr);
                }
            } else {
                i++;
            }
        }
        return blocks;
    }

    private boolean containsChartKeyword(String line) {
        String lower = line.toLowerCase();
        return lower.contains("图表") || lower.contains("数据图表") || lower.contains("柱状图")
            || lower.contains("折线图") || lower.contains("饼图") || lower.contains("面积图")
            || lower.contains("散点图") || lower.contains("走势图") || lower.contains("流程图")
            || lower.contains("架构图") || lower.contains("趋势") || lower.contains("走势")
            || lower.contains("分布") || lower.contains("对比") || lower.contains("统计");
    }

    private boolean hasNextTable(String[] lines, int currentIdx) {
        for (int j = currentIdx + 1; j < Math.min(currentIdx + 5, lines.length); j++) {
            if (lines[j].trim().startsWith("| ") && lines[j].contains("---")) return true;
        }
        return false;
    }
}