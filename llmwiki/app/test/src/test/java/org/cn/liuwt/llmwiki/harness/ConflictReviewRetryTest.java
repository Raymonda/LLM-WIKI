package org.cn.liuwt.llmwiki.harness;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ConflictReviewDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ConflictReviewMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictReviewService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConflictReviewRetryTest {

    @Mock
    private ConflictReviewMapper conflictReviewMapper;

    @InjectMocks
    private ConflictReviewService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConflictReviewDO.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldResetFailedReviewToPendingWhenPreparingForRetry() {
        ConflictReviewDO failed = new ConflictReviewDO();
        failed.setId(77L);
        failed.setStatus("failed");
        failed.setExecutionError("LLM timeout");
        when(conflictReviewMapper.selectById(77L)).thenReturn(failed);

        service.prepareForRetry(77L);

        ArgumentCaptor<LambdaUpdateWrapper<ConflictReviewDO>> captor =
            ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(conflictReviewMapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("status"));
        assertTrue(sqlSet.contains("execution_error"));
        assertTrue(sqlSet.contains("ruling_action"));
        assertTrue(sqlSet.contains("ruling_detail"));
        assertTrue(sqlSet.contains("decided_by"));
        assertTrue(sqlSet.contains("decided_at"));
        assertTrue(sqlSet.contains("executed_at"));
    }

    @Test
    void shouldSkipPrepareWhenReviewAlreadyPending() {
        ConflictReviewDO pending = new ConflictReviewDO();
        pending.setId(77L);
        pending.setStatus("pending");
        when(conflictReviewMapper.selectById(77L)).thenReturn(pending);

        service.prepareForRetry(77L);

        verify(conflictReviewMapper, never()).update(any(), any());
    }

    @Test
    void shouldReturnReviewById() {
        ConflictReviewDO review = new ConflictReviewDO();
        review.setId(77L);
        when(conflictReviewMapper.selectById(77L)).thenReturn(review);

        assertEquals(77L, service.getReview(77L).getId());
    }
}
