package com.stellarink.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.stellarink.sharedmodel.auth.AuthVersion;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.user.mapper.UserMapper;
import com.stellarink.user.pojo.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InternalSessionControllerTest {
    @Test
    void oldDevicesMissingVersionsAndRoleMismatchAreRejected() {
        var mapper = mock(UserMapper.class);
        var controller = new InternalSessionController(mapper);
        User user = new User();
        user.setRole("AUTHOR");
        user.setAuthVersion(7L);
        when(mapper.selectById(1L)).thenReturn(user);
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            stp.when(() -> StpUtil.getExtra(Role.JWT_KEY)).thenReturn("AUTHOR");
            stp.when(() -> StpUtil.getExtra(AuthVersion.JWT_KEY)).thenReturn(7L);
            assertThat(controller.validate().getData()).isTrue();
            user.setAuthVersion(8L);
            assertThat(controller.validate().getData()).isFalse();
            stp.when(() -> StpUtil.getExtra(AuthVersion.JWT_KEY)).thenReturn(null);
            assertThat(controller.validate().getData()).isFalse();
            stp.when(() -> StpUtil.getExtra(AuthVersion.JWT_KEY)).thenReturn(8L);
            user.setRole("READER");
            assertThat(controller.validate().getData()).isFalse();
        }
    }
}
