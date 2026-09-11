package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.ingest.EntityPageEntryParser;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.EntityPageEntryParser.Entry;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.EntityPageEntryParser.ParseResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntityPageEntryParserTest {

    private static final String SAMPLE = """
        > 实体页概要

        ## 基本信息

        - 成立时间为 2010 年（来源：招股说明书）
        - 总部位于上海（来源：年报；招股说明书）
        - 注册资本 10 亿元

        ## 关联关系

        - 控股股东为甲集团（来源：年报）

        非条目行不参与解析（来源：年报）
        """;

    @Test
    void shouldParseEntriesWithSectionsAndSourcesWhenParsing() {
        ParseResult result = EntityPageEntryParser.parse(SAMPLE);

        assertEquals(4, result.entries().size());
        Entry first = result.entries().get(0);
        assertEquals("基本信息", first.section());
        assertEquals("成立时间为 2010 年", first.claim());
        assertEquals(List.of("招股说明书"), first.sources());
        assertEquals(List.of("年报", "招股说明书"), result.entries().get(1).sources());
        assertTrue(result.entries().get(2).sources().isEmpty());

        Entry last = result.entries().get(3);
        assertEquals("关联关系", last.section());
        assertEquals("控股股东为甲集团", last.claim());
    }

    @Test
    void shouldKeepLinesReconstructableWhenParsing() {
        ParseResult result = EntityPageEntryParser.parse(SAMPLE);
        assertEquals(SAMPLE, String.join("\n", result.lines()));
    }

    @Test
    void shouldFormatSourceSuffixAndPromptListing() {
        assertEquals("（来源：A；B）", EntityPageEntryParser.formatSourceSuffix(List.of("A", "B")));
        assertEquals("", EntityPageEntryParser.formatSourceSuffix(List.of()));

        ParseResult result = EntityPageEntryParser.parse(SAMPLE);
        String prompt = EntityPageEntryParser.formatForPrompt(result.entries());
        assertTrue(prompt.contains("- [0] 成立时间为 2010 年（来源：招股说明书）"), prompt);
        assertTrue(prompt.contains("- [2] 注册资本 10 亿元"), prompt);
        assertTrue(prompt.contains("【基本信息】"), prompt);
        assertTrue(prompt.contains("【关联关系】"), prompt);
    }

    @Test
    void shouldHandleEmptyContentWhenParsing() {
        assertTrue(EntityPageEntryParser.parse("").entries().isEmpty());
        assertTrue(EntityPageEntryParser.parse(null).entries().isEmpty());
    }
}
