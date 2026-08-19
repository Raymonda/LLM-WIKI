package org.cn.liuwt.llmwiki.dal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.TableName;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ApiKeyDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ApiKeyMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyMapperTest {

    @Test
    void shouldExtendBaseMapperWhenLoaded() {
        assertTrue(BaseMapper.class.isAssignableFrom(ApiKeyMapper.class));
    }

    @Test
    void shouldCarryTableNameAnnotationWhenInspected() {
        assertEquals("api_key", ApiKeyDO.class.getAnnotation(TableName.class).value());
    }
}
