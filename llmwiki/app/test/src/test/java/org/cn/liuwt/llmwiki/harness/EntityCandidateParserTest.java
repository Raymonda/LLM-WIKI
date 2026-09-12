package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.ingest.EntityCandidateParser;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.EntityCandidateParser.ParseOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EntityCandidateParserTest {

    @Test
    void shouldParseValidCandidatesWhenJsonWellFormed() {
        String json = """
            [
              {"section": "基本信息", "claim": "总部位于上海", "source": "年报", "quote": "", "relation": "duplicate_of:2"},
              {"section": "基本信息", "claim": "注册资本 12 亿元", "source": "招股说明书", "relation": "conflict_with:1"},
              {"section": "关联关系", "claim": "控股乙公司", "source": "年报", "relation": "new"}
            ]
            """;
        ParseOutcome outcome = EntityCandidateParser.parse(json);

        assertFalse(outcome.fatal());
        assertEquals(3, outcome.candidates().size());
        assertEquals("duplicate_of:2", outcome.candidates().get(0).relation());
        assertEquals("conflict_with:1", outcome.candidates().get(1).relation());
        assertEquals("new", outcome.candidates().get(2).relation());
        assertTrue(outcome.skipped().isEmpty());
    }

    @Test
    void shouldStripMarkdownFencesWhenParsing() {
        String json = "```json\n[{\"claim\":\"A\",\"source\":\"年报\",\"relation\":\"new\"}]\n```";
        ParseOutcome outcome = EntityCandidateParser.parse(json);

        assertFalse(outcome.fatal());
        assertEquals(1, outcome.candidates().size());
    }

    @Test
    void shouldSkipInvalidEntriesButKeepValidOnes() {
        String json = """
            [
              {"section": "基本信息", "claim": "有效条目", "source": "年报", "relation": "new"},
              {"section": "基本信息", "relation": "new"},
              {"section": "基本信息", "claim": "非法关系", "source": "年报", "relation": "replace"},
              "not-an-object"
            ]
            """;
        ParseOutcome outcome = EntityCandidateParser.parse(json);

        assertFalse(outcome.fatal());
        assertEquals(1, outcome.candidates().size());
        assertEquals(3, outcome.skipped().size());
    }

    @Test
    void shouldReportFatalWhenWholeJsonInvalid() {
        assertTrue(EntityCandidateParser.parse("这不是 JSON").fatal());
        assertTrue(EntityCandidateParser.parse("").fatal());
        assertTrue(EntityCandidateParser.parse("{\"claim\":\"A\"}").fatal());
    }
}
