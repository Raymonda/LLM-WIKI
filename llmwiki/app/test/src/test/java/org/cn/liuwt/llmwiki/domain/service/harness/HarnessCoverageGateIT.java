package org.cn.liuwt.llmwiki.domain.service.harness;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessCoverageGateIT {

    private static final double LINE_COVERAGE_THRESHOLD = 0.70;

    private static final Set<String> GATED_CLASS_PREFIXES = Set.of(
        "org/cn/liuwt/llmwiki/domain/service/harness/pipeline/",
        "org/cn/liuwt/llmwiki/domain/service/harness/eventlog/",
        "org/cn/liuwt/llmwiki/domain/service/harness/plugin/");

    private static final Set<String> GATED_CLASSES = Set.of(
        "org/cn/liuwt/llmwiki/domain/service/harness/tool/TodoTool",
        "org/cn/liuwt/llmwiki/domain/service/harness/SpillService",
        "org/cn/liuwt/llmwiki/domain/service/harness/SpillProperties",
        "org/cn/liuwt/llmwiki/domain/service/harness/CompactionService",
        "org/cn/liuwt/llmwiki/domain/service/harness/CompactionProperties");

    @Test
    void coreHarnessPackagesLineCoverageMeetsThreshold() throws Exception {
        Path execFile = Path.of("target", "jacoco.exec");
        assertTrue(Files.exists(execFile), "缺少 jacoco.exec（surefire 未注入 agent 或测试未运行），覆盖率门禁失效");

        ExecFileLoader loader = new ExecFileLoader();
        loader.load(execFile.toFile());
        ExecutionDataStore store = loader.getExecutionDataStore();

        CoverageBuilder builder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(store, builder);
        List<String> analyzedDirs = new ArrayList<>();
        for (Path classesDir : List.of(
            Path.of("..", "domain", "service", "target", "classes"),
            Path.of("..", "domain", "model", "target", "classes"))) {
            if (Files.exists(classesDir)) {
                analyzer.analyzeAll(classesDir.toFile());
                analyzedDirs.add(classesDir.toString());
            }
        }
        assertTrue(!analyzedDirs.isEmpty(), "未找到任何待分析的 classes 目录");

        long covered = 0;
        long missed = 0;
        List<String> gatedNames = new ArrayList<>();
        for (IClassCoverage clazz : builder.getClasses()) {
            if (!isGated(clazz.getName())) {
                continue;
            }
            gatedNames.add(clazz.getName());
            ICounter line = clazz.getLineCounter();
            covered += line.getCoveredCount();
            missed += line.getMissedCount();
        }
        long total = covered + missed;
        assertTrue(total > 0, "jacoco.exec 中无门控范围内的类数据（" + gatedNames.size() + " 个类被识别但无行数据）");
        double ratio = (double) covered / total;
        assertTrue(ratio >= LINE_COVERAGE_THRESHOLD,
            "harness 核心包行覆盖率 " + String.format("%.2f", ratio) + "（covered=" + covered
                + ", missed=" + missed + "）低于阈值 " + LINE_COVERAGE_THRESHOLD
                + "，涉及 " + gatedNames.size() + " 个类");
    }

    private static boolean isGated(String classVmName) {
        for (String prefix : GATED_CLASS_PREFIXES) {
            if (classVmName.startsWith(prefix)) {
                return true;
            }
        }
        return GATED_CLASSES.contains(classVmName);
    }
}
