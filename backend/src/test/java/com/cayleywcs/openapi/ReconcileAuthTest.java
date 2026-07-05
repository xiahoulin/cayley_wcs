package com.cayleywcs.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.application.mapper.ApplicationMapper;
import com.cayleywcs.application.security.AppKeyAuthFilter;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.task.TaskService;
import com.cayleywcs.task.entity.TaskEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 开放对账接口越权防护（F8）：调用方只能查自己或已绑定授权的应用；appId 缺省收敛为可见集合；snapshot 必填 appId。
 * 依赖种子：V3 STACKER01(下位)、V7 WMS-MAIN(上位, 已授权指挥全部存量下位应用)。
 */
@SpringBootTest
@ActiveProfiles("test-flyway")
class ReconcileAuthTest {

    @Autowired
    private OpenReconcileController controller;
    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private ApplicationMapper applicationMapper;
    @Autowired
    private TaskService taskService;

    private ApplicationEntity byCode(String appCode) {
        return applicationService.listAll().stream()
                .filter(a -> appCode.equals(a.getApp_code()))
                .findFirst().orElseThrow();
    }

    private MockHttpServletRequest as(ApplicationEntity caller) {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute(AppKeyAuthFilter.ATTR_APP_ID, caller.getId());
        http.setAttribute(AppKeyAuthFilter.ATTR_APP_TENANT_ID, caller.getTenant_id());
        return http;
    }

    @Test
    void upstreamWithBindingCanQueryDownstream() {
        ApplicationEntity wms = byCode("WMS-MAIN");
        ApplicationEntity stacker = byCode("STACKER01");
        var resp = controller.queryTasks(
                new OpenReconcileController.ReconcileQuery(stacker.getId(), 0, 100), as(wms));
        assertThat(resp.success()).isTrue();
    }

    @Test
    void unboundCallerIsForbidden() {
        ApplicationEntity wms = byCode("WMS-MAIN");
        ApplicationEntity stacker = byCode("STACKER01");
        // 反向：下位设备的 AppKey 试图查上位系统 → 无绑定，拒绝
        assertThatThrownBy(() -> controller.queryTasks(
                new OpenReconcileController.ReconcileQuery(wms.getId(), 0, 100), as(stacker)))
                .isInstanceOf(WcsException.class)
                .hasMessageContaining("未授权");
        assertThatThrownBy(() -> controller.queryAlarms(
                new OpenReconcileController.ReconcileQuery(wms.getId(), 0, 100), as(stacker)))
                .isInstanceOf(WcsException.class);
        assertThatThrownBy(() -> controller.snapshot(
                new OpenReconcileController.SnapshotQuery(wms.getId()), as(stacker)))
                .isInstanceOf(WcsException.class);
    }

    @Test
    void callerCanAlwaysQueryItself() {
        ApplicationEntity stacker = byCode("STACKER01");
        var resp = controller.queryTasks(
                new OpenReconcileController.ReconcileQuery(stacker.getId(), 0, 100), as(stacker));
        assertThat(resp.success()).isTrue();
    }

    @Test
    void nullAppIdNarrowsToVisibleSetInsteadOfFullDump() {
        ApplicationEntity wms = byCode("WMS-MAIN");
        ApplicationEntity stacker = byCode("STACKER01");

        // 授权集内的任务可见
        TaskEntity visible = new TaskEntity();
        visible.setApp_id(stacker.getId());
        visible.setTask_no("AUTH-VISIBLE-1");
        visible.setTask_type("1");
        taskService.create(visible);

        // 新建一个未授权给 WMS-MAIN 的下位应用（V7 整批授权只覆盖当时存量）。
        // 直插 Mapper：绕过管理端 create() 的登录用户要求，测试只需要这行数据存在。
        ApplicationEntity stranger = new ApplicationEntity();
        stranger.setApp_code("AUTH-STRANGER");
        stranger.setApp_name("未授权设备");
        stranger.setApp_key("ak_auth_stranger_test");
        stranger.setApp_secret("test-secret");
        stranger.setProtocol_id(stacker.getProtocol_id());
        stranger.setDirection("downstream");
        stranger.setDevice_type("stacker");
        stranger.setEnabled(true);
        stranger.setStatus("idle");
        stranger.setCreator("test");
        stranger.setCreate_time(LocalDateTime.now());
        stranger.setLast_update_time(LocalDateTime.now());
        stranger.setIs_valid(true);
        stranger.setTenant_id(1L);
        applicationMapper.insert(stranger);
        TaskEntity hidden = new TaskEntity();
        hidden.setApp_id(stranger.getId());
        hidden.setTask_no("AUTH-HIDDEN-1");
        hidden.setTask_type("1");
        taskService.create(hidden);

        var resp = controller.queryTasks(
                new OpenReconcileController.ReconcileQuery(null, 0, 500), as(wms));
        assertThat(resp.success()).isTrue();
        var taskNos = resp.data().rows().stream().map(TaskEntity::getTask_no).toList();
        assertThat(taskNos).contains("AUTH-VISIBLE-1");
        assertThat(taskNos).as("appId 缺省不得全量吐出未授权应用的任务").doesNotContain("AUTH-HIDDEN-1");
    }

    @Test
    void snapshotRequiresAppId() {
        ApplicationEntity wms = byCode("WMS-MAIN");
        assertThatThrownBy(() -> controller.snapshot(
                new OpenReconcileController.SnapshotQuery(null), as(wms)))
                .isInstanceOf(WcsException.class)
                .hasMessageContaining("appId");
    }
}
