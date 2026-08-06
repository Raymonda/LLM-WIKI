package org.cn.liuwt.llmwiki.common.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface WikiPageMapper extends BaseMapper<WikiPageDO> {

    @Select("SELECT DISTINCT scope_id FROM wiki_page")
    List<Long> selectDistinctScopeIds();

    @Select("""
        SELECT id FROM wiki_page
        WHERE scope_id = #{scopeId}
        AND page_type != 'reference'
        AND lifecycle_status = 'ACTIVE'
        AND created_at < #{ageThreshold}
        AND NOT EXISTS (
            SELECT 1 FROM wiki_page_link
            WHERE wiki_page_link.scope_id = #{scopeId}
            AND wiki_page_link.to_page_id = wiki_page.id
        )
        """)
    List<Long> selectOrphanCandidateIds(@Param("scopeId") Long scopeId, @Param("ageThreshold") LocalDateTime ageThreshold);

    @Select("""
        SELECT wp.id as page_id, wp.file_path, wp.title,
               COALESCE(wp.content_updated_at, wp.updated_at) as content_updated_at,
               MAX(s.file_modified_at) as newest_source_at,
               TIMESTAMPDIFF(DAY, COALESCE(wp.content_updated_at, wp.updated_at), MAX(s.file_modified_at)) as stale_days
        FROM wiki_page wp
        INNER JOIN wiki_page_source wps ON wps.page_id = wp.id AND wps.scope_id = #{scopeId}
        INNER JOIN source s ON s.id = wps.source_id
        WHERE wp.scope_id = #{scopeId}
        AND wp.page_type != 'reference'
        AND wp.lifecycle_status = 'ACTIVE'
        AND wps.source_id IS NOT NULL
        AND s.file_modified_at IS NOT NULL
        GROUP BY wp.id, wp.file_path, wp.title, wp.content_updated_at, wp.updated_at
        HAVING MAX(s.file_modified_at) > COALESCE(wp.content_updated_at, wp.updated_at)
        """)
    List<Map<String, Object>> selectStaleCandidateRows(@Param("scopeId") Long scopeId);

    @Select("""
        SELECT COUNT(*) FROM wiki_page wp
        WHERE wp.scope_id = #{scopeId}
        AND wp.lifecycle_status = 'ACTIVE'
        AND NOT EXISTS (
            SELECT 1 FROM wiki_page_link wpl
            WHERE wpl.scope_id = #{scopeId}
            AND wpl.to_page_id = wp.id
        )
        """)
    long selectOrphanCountByScope(@Param("scopeId") Long scopeId);

    @Select("""
        SELECT health_status, COUNT(*) AS cnt FROM wiki_page
        WHERE scope_id = #{scopeId}
        AND lifecycle_status = 'ACTIVE'
        GROUP BY health_status
        """)
    List<Map<String, Object>> selectHealthDistributionByScope(@Param("scopeId") Long scopeId);

    @Select("""
        SELECT id FROM wiki_page
        WHERE scope_id = #{scopeId}
        AND lifecycle_status = 'ACTIVE'
        AND health_status IN ('has-problems', 'needs-update')
        """)
    List<Long> selectUnhealthyPageIds(@Param("scopeId") Long scopeId);

    @Select("""
        SELECT a.id AS page_id_a, a.file_path AS file_path_a, a.title AS title_a,
               b.id AS page_id_b, b.file_path AS file_path_b, b.title AS title_b,
               COUNT(*) AS shared_keywords
        FROM wiki_page_keyword k1
        INNER JOIN wiki_page_keyword k2 ON k1.keyword = k2.keyword AND k1.page_id < k2.page_id
        INNER JOIN wiki_page a ON a.id = k1.page_id AND a.scope_id = #{scopeId}
        INNER JOIN wiki_page b ON b.id = k2.page_id AND b.scope_id = #{scopeId}
        LEFT JOIN wiki_page_link l ON l.from_page_id = a.id AND l.to_page_id = b.id AND l.scope_id = #{scopeId}
        WHERE l.id IS NULL
        AND a.page_type != 'reference' AND b.page_type != 'reference'
        AND a.lifecycle_status = 'ACTIVE' AND b.lifecycle_status = 'ACTIVE'
        GROUP BY a.id, a.file_path, a.title, b.id, b.file_path, b.title
        HAVING COUNT(*) >= #{minSharedKeywords}
        ORDER BY COUNT(*) DESC
        LIMIT #{limitCount}
        """)
    List<Map<String, Object>> selectConflictCandidatePairs(@Param("scopeId") Long scopeId,
                                                            @Param("minSharedKeywords") int minSharedKeywords,
                                                            @Param("limitCount") int limitCount);

    @Select("""
        SELECT a.id AS page_id_a, a.file_path AS file_path_a, a.title AS title_a,
               b.id AS page_id_b, b.file_path AS file_path_b, b.title AS title_b,
               COUNT(*) AS shared_keywords
        FROM wiki_page_keyword k1
        INNER JOIN wiki_page_keyword k2 ON k1.keyword = k2.keyword AND k1.page_id < k2.page_id
        INNER JOIN wiki_page a ON a.id = k1.page_id AND a.scope_id = #{scopeId}
        INNER JOIN wiki_page b ON b.id = k2.page_id AND b.scope_id = #{scopeId}
        INNER JOIN wiki_page_source wps_a ON wps_a.page_id = a.id AND wps_a.scope_id = #{scopeId}
        INNER JOIN wiki_page_source wps_b ON wps_b.page_id = b.id AND wps_b.scope_id = #{scopeId}
        LEFT JOIN wiki_page_link l ON l.from_page_id = a.id AND l.to_page_id = b.id AND l.scope_id = #{scopeId}
        WHERE l.id IS NULL
        AND a.page_type = 'reference' AND b.page_type = 'reference'
        AND a.lifecycle_status = 'ACTIVE' AND b.lifecycle_status = 'ACTIVE'
        AND wps_a.source_id = wps_b.source_id
        GROUP BY a.id, a.file_path, a.title, b.id, b.file_path, b.title
        HAVING COUNT(*) >= #{minSharedKeywords}
        ORDER BY COUNT(*) DESC
        LIMIT #{limitCount}
        """)
    List<Map<String, Object>> selectReferenceConflictPairs(@Param("scopeId") Long scopeId,
                                                            @Param("minSharedKeywords") int minSharedKeywords,
                                                            @Param("limitCount") int limitCount);

    @Select("""
        SELECT COALESCE(category, '未分类') AS category,
               COUNT(*) AS page_count,
               SUM(CASE WHEN health_status IN ('conflict-warning', 'has-problems') THEN 1 ELSE 0 END) AS conflict_count,
               SUM(CASE WHEN health_status = 'needs-update' THEN 1 ELSE 0 END) AS needs_update_count,
               SUM(CASE WHEN health_status = 'has-problems' THEN 1 ELSE 0 END) AS has_problems_count
        FROM wiki_page
        WHERE scope_id = #{scopeId}
        AND lifecycle_status = 'ACTIVE'
        GROUP BY category
        ORDER BY COUNT(*) DESC
        """)
    List<Map<String, Object>> selectCategoryStats(@Param("scopeId") Long scopeId);

    @Select("""
        SELECT p.category,
               SUM(CASE WHEN COALESCE(d.total_deg, 0) = 0 THEN 1 ELSE 0 END) AS orphan_count,
               SUM(CASE WHEN COALESCE(d.total_deg, 0) >= 5 THEN 1 ELSE 0 END) AS hub_count,
               AVG(COALESCE(d.total_deg, 0)) AS avg_degree
        FROM wiki_page p
        LEFT JOIN (
            SELECT page_id, SUM(deg) AS total_deg
            FROM (
                SELECT from_page_id AS page_id, COUNT(*) AS deg FROM wiki_page_link WHERE scope_id = #{scopeId} GROUP BY from_page_id
                UNION ALL
                SELECT to_page_id AS page_id, COUNT(*) AS deg FROM wiki_page_link WHERE scope_id = #{scopeId} GROUP BY to_page_id
            ) combined GROUP BY page_id
        ) d ON d.page_id = p.id
        WHERE p.scope_id = #{scopeId}
        AND p.lifecycle_status = 'ACTIVE'
        GROUP BY p.category
        """)
    List<Map<String, Object>> selectCategoryDegreeStats(@Param("scopeId") Long scopeId);

    @Select("""
        SELECT p1.category AS from_category, p2.category AS to_category, COUNT(*) AS link_count
        FROM wiki_page_link l
        INNER JOIN wiki_page p1 ON p1.id = l.from_page_id AND p1.lifecycle_status = 'ACTIVE'
        INNER JOIN wiki_page p2 ON p2.id = l.to_page_id AND p2.lifecycle_status = 'ACTIVE'
        WHERE l.scope_id = #{scopeId}
        AND p1.category IS NOT NULL AND p2.category IS NOT NULL
        GROUP BY p1.category, p2.category
        ORDER BY COUNT(*) DESC
        """)
    List<Map<String, Object>> selectInterCategoryEdges(@Param("scopeId") Long scopeId);

    @Select("""
        SELECT id, title, file_path, category, health_status, page_type, source_count, lifecycle_status
        FROM wiki_page
        WHERE scope_id = #{scopeId} AND category = #{category}
        AND lifecycle_status IN ('ACTIVE', 'DEPRECATED', 'MERGED')
        """)
    List<WikiPageDO> selectPagesByCategory(@Param("scopeId") Long scopeId, @Param("category") String category);

    @Select("""
        SELECT id, title, file_path, category, health_status, page_type, source_count, lifecycle_status
        FROM wiki_page
        WHERE scope_id = #{scopeId}
        AND lifecycle_status IN ('ACTIVE', 'DEPRECATED', 'MERGED')
        AND (title LIKE CONCAT('%', #{keyword}, '%') OR COALESCE(category, '') LIKE CONCAT('%', #{keyword}, '%'))
        LIMIT #{limit}
        """)
    List<WikiPageDO> searchPagesForGraph(@Param("scopeId") Long scopeId, @Param("keyword") String keyword, @Param("limit") int limit);

    @Select("""
        SELECT wp.id, wp.title, wp.file_path, wp.category, wp.summary, wp.page_type
        FROM wiki_page wp
        WHERE wp.scope_id = #{scopeId}
        AND wp.lifecycle_status = 'ACTIVE'
        AND wp.page_type != 'reference'
        AND NOT EXISTS (
            SELECT 1 FROM wiki_page_keyword wpk
            WHERE wpk.page_id = wp.id AND wpk.scope_id = #{scopeId}
        )
        LIMIT #{limit}
        """)
    List<WikiPageDO> selectPagesWithoutKeywords(@Param("scopeId") Long scopeId, @Param("limit") int limit);
}