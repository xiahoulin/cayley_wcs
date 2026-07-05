package com.cayleywcs.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.application.impl.ApplicationServiceImpl;
import com.cayleywcs.application.mapper.ApplicationMapper;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.system.CurrentUserProvider;
import com.cayleywcs.task.StackerHandshakeStateMachine;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 设备类型 SPI（M8）：注册表多类型路由 + 应用保存时未知 device_type 校验（不静默）。
 */
class DeviceHandlerRegistryTest {

    private DeviceHandlerRegistry registry() {
        StackerHandshakeStateMachine stacker = new StackerHandshakeStateMachine(mock(ConnectionManager.class));
        return new DeviceHandlerRegistry(List.of(stacker));
    }

    @Test
    void routesStackerAndPalletizerToSameHandler() {
        DeviceHandlerRegistry registry = registry();
        assertThat(registry.find("stacker")).isPresent();
        assertThat(registry.find("palletizer")).isPresent();
        assertThat(registry.find("stacker").orElseThrow())
                .isSameAs(registry.find("palletizer").orElseThrow());
        assertThat(registry.find("STACKER")).as("类型码大小写不敏感").isPresent();
    }

    @Test
    void unknownTypeIsEmptyNotException() {
        DeviceHandlerRegistry registry = registry();
        assertThat(registry.find("conveyor")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.find(" ")).isEmpty();
        assertThat(registry.supports("stacker")).isTrue();
        assertThat(registry.supports("conveyor")).isFalse();
    }

    @Test
    void applicationSaveRejectsUnknownDeviceType() {
        DeviceHandlerRegistry registry = registry();
        @SuppressWarnings("unchecked")
        ObjectProvider<DeviceHandlerRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        when(users.currentUser()).thenReturn(null); // Audits 兜底 system
        ApplicationServiceImpl service =
                new ApplicationServiceImpl(mock(ApplicationMapper.class), users, provider);

        ApplicationEntity unknown = new ApplicationEntity();
        unknown.setApp_code("X1");
        unknown.setApp_name("未知设备");
        unknown.setProtocol_id(1L);
        unknown.setDevice_type("conveyor"); // M9 才有 handler
        assertThatThrownBy(() -> service.create(unknown))
                .isInstanceOf(WcsException.class)
                .hasMessageContaining("未注册的设备类型");

        ApplicationEntity ok = new ApplicationEntity();
        ok.setApp_code("X2");
        ok.setApp_name("堆垛机");
        ok.setProtocol_id(1L);
        ok.setDevice_type("stacker");
        service.create(ok); // 不抛

        ApplicationEntity upstream = new ApplicationEntity();
        upstream.setApp_code("X3");
        upstream.setApp_name("上位系统");
        upstream.setProtocol_id(1L);
        upstream.setDevice_type("none"); // 上位系统豁免
        service.create(upstream); // 不抛

        ApplicationEntity blank = new ApplicationEntity();
        blank.setApp_code("X4");
        blank.setApp_name("缺省类型");
        blank.setProtocol_id(1L);
        service.create(blank); // 空值走 DB 默认，不抛
    }
}
