package org.cn.liuwt.llmwiki.common.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface LintFindingMapper extends BaseMapper<LintFindingDO> {

    @Select("""
        SELECT asset_id, finding_type, COUNT(*) AS cnt FROM lint_finding
        WHERE scope_id = #{scopeId}
        AND status IN ('open', 'awaiting_approval', 'repairing', 'deferred')
        AND archived_at IS NULL
        AND finding_type != 'schema_violation'
        AND asset_id IS NOT NULL
        GROUP BY asset_id, finding_type
        """)
    List<Map<String, Object>> selectOpenFindingAssetDistribution(@Param("scopeId") Long scopeId);

    @Select("""
        SELECT COUNT(*) FROM lint_finding
        WHERE scope_id = #{scopeId}
        AND finding_type = 'conflict'
        AND status = 'open'
        AND page_path = #{pagePath}
        AND LEFT(detail, 256) = LEFT(#{detailPrefix}, 256)
        """)
    int countOpenConflictByPageAndDetailPrefix(@Param("scopeId") Long scopeId,
                                               @Param("pagePath") String pagePath,
                                               @Param("detailPrefix") String detailPrefix);
}
