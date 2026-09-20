package org.cn.liuwt.llmwiki.domain.service.system;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.UserMapper;
import org.cn.liuwt.llmwiki.domain.model.system.ScopeModel;
import org.cn.liuwt.llmwiki.facade.model.ScopeInfo;
import org.cn.liuwt.llmwiki.web.controller.ScopeController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
class ScopeIngestModeMappingTest {

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private ScopeController scopeController;

    private final ScopeServiceImpl scopeService = new ScopeServiceImpl();

    @Test
    void shouldMapIngestModeFieldsWhenConvertingDoToModel() throws Exception {
        ScopeDO scopeDO = new ScopeDO();
        scopeDO.setIngestMode("auto");
        scopeDO.setAutoSuspended(true);
        scopeDO.setAutoSuspendedReason("批次 #7 失败率 40%");

        Method method = ScopeServiceImpl.class.getDeclaredMethod("toScopeModel", ScopeDO.class);
        method.setAccessible(true);
        ScopeModel model = (ScopeModel) method.invoke(scopeService, scopeDO);

        assertEquals("auto", model.getIngestMode());
        assertEquals(Boolean.TRUE, model.getAutoSuspended());
        assertEquals("批次 #7 失败率 40%", model.getAutoSuspendedReason());
    }

    @Test
    void shouldMapIngestModeFieldsWhenConvertingModelToDo() throws Exception {
        ScopeModel model = new ScopeModel();
        model.setIngestMode("review");
        model.setAutoSuspended(false);
        model.setAutoSuspendedReason(null);

        Method method = ScopeServiceImpl.class.getDeclaredMethod("toScopeDO", ScopeModel.class);
        method.setAccessible(true);
        ScopeDO scopeDO = (ScopeDO) method.invoke(scopeService, model);

        assertEquals("review", scopeDO.getIngestMode());
        assertEquals(Boolean.FALSE, scopeDO.getAutoSuspended());
        assertNull(scopeDO.getAutoSuspendedReason());
    }

    @Test
    void shouldMapIngestModeFieldsWhenConvertingModelToInfo() throws Exception {
        ScopeModel model = new ScopeModel();
        model.setIngestMode("auto");
        model.setAutoSuspended(true);
        model.setAutoSuspendedReason("批次 #9 失败率 60%");

        Method method = ScopeController.class.getDeclaredMethod("toScopeInfo", ScopeModel.class);
        method.setAccessible(true);
        ScopeInfo info = (ScopeInfo) method.invoke(scopeController, model);

        assertEquals("auto", info.getIngestMode());
        assertEquals(Boolean.TRUE, info.getAutoSuspended());
        assertEquals("批次 #9 失败率 60%", info.getAutoSuspendedReason());
    }
}
