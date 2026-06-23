package com.cayleywcs.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cayleywcs.auth.JwtUser;
import com.cayleywcs.binding.entity.AppBindingEntity;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * 应用绑定授权（上位侧→下位侧，越权防护）：
 * 无绑定越权拒绝(FORBIDDEN) / 整批授权对账 / 禁用绑定拒绝 / 重复冲突 / 禁止自绑定 / 复用软删行 / 租户隔离 / scope 收敛。
 * 不锁 direction：任意应用都可作上位侧或下位侧。
 */
@SpringBootTest
@ActiveProfiles("test-flyway")
class BindingTest {

    @Autowired
    private BindingService bindingService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void authenticate() {
        // 管理 CRUD 需要 currentUser()，注入一个测试 admin 安全上下文。
        JwtUser admin = new JwtUser(1L, "admin", "admin", "admin", 1L, 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private long appId(String appKey) {
        return jdbcTemplate.queryForObject(
                "select \"id\" from \"wcs_application\" where \"app_key\" = ?", Long.class, appKey);
    }

    @Test
    void noSelfBindingsSeededAndSelfBindingForbidden() {
        // V8 已删除全部自绑定。
        Integer selfBindings = jdbcTemplate.queryForObject(
                "select count(*) from \"wcs_app_binding\" where \"upstream_app_id\" = \"downstream_app_id\"",
                Integer.class);
        assertThat(selfBindings).isEqualTo(0);

        // 自绑定被拒：应用不能指挥自己。
        long me = appId("ak_pallet_opcua");
        assertThatThrownBy(() -> bindingService.create(binding(me, me)))
                .isInstanceOf(WcsException.class)
                .satisfies(ex -> assertThat(((WcsException) ex).getCode()).isEqualTo(ErrorCode.BAD_REQUEST.code()));
        assertThat(bindingService.isAllowed(me, me)).isFalse();
    }

    @Test
    void crossBindingDeniedUntilGranted() {
        long upstream = appId("ak_pallet_sim");
        long downstream = appId("ak_pallet_opcua");
        // 无绑定：越权被拒。
        assertThat(bindingService.isAllowed(upstream, downstream)).isFalse();
        assertThatThrownBy(() -> bindingService.assertAllowed(upstream, downstream))
                .isInstanceOf(WcsException.class)
                .satisfies(ex -> assertThat(((WcsException) ex).getCode()).isEqualTo(ErrorCode.FORBIDDEN.code()));
    }

    @Test
    void createBindingAllowsThenDisableDenies() {
        long upstream = appId("ak_stacker_demo");
        long downstream = appId("ak_pallet_opcua");

        assertThat(bindingService.isAllowed(upstream, downstream)).isFalse();

        AppBindingEntity created = bindingService.create(binding(upstream, downstream));
        assertThat(created.getId()).isNotNull();
        assertThat(created.getScope()).isEqualTo("dispatch");
        assertThat(bindingService.isAllowed(upstream, downstream)).isTrue();

        // 活跃重复绑定冲突。
        assertThatThrownBy(() -> bindingService.create(binding(upstream, downstream)))
                .isInstanceOf(WcsException.class)
                .satisfies(ex -> assertThat(((WcsException) ex).getCode()).isEqualTo(ErrorCode.CONFLICT.code()));

        // 禁用后立即失效。
        created.setEnabled(false);
        bindingService.update(created);
        assertThat(bindingService.isAllowed(upstream, downstream)).isFalse();

        // 软删后亦失效。
        created.setEnabled(true);
        bindingService.update(created);
        assertThat(bindingService.isAllowed(upstream, downstream)).isTrue();
        bindingService.delete(created.getId());
        assertThat(bindingService.isAllowed(upstream, downstream)).isFalse();
    }

    @Test
    void grantReconcilesDownstreamSetAndIgnoresSelf() {
        long upstream = 91001L; // 合成上位侧 id（grant 仅写绑定关系，不要求应用存在）
        long d1 = appId("ak_pallet_opcua");
        long d2 = appId("ak_stacker_demo");

        // 授权两个下位侧（含自指 upstream，应被忽略）。
        List<Long> after = bindingService.grant(upstream, List.of(d1, d2, upstream), "dispatch");
        assertThat(after).containsExactlyInAnyOrder(d1, d2); // 自指被忽略
        assertThat(bindingService.isAllowed(upstream, d1)).isTrue();
        assertThat(bindingService.isAllowed(upstream, d2)).isTrue();
        assertThat(bindingService.isAllowed(upstream, upstream)).isFalse(); // 无自绑定

        // 重设为只授权 d1 → d2 被撤销（幂等对账）。
        List<Long> reconciled = bindingService.grant(upstream, List.of(d1), "dispatch");
        assertThat(reconciled).containsExactly(d1);
        assertThat(bindingService.isAllowed(upstream, d2)).isFalse();

        // 再次授权 d2 → 复用并重新启用（不抛重复冲突）。
        List<Long> regranted = bindingService.grant(upstream, List.of(d1, d2), "dispatch");
        assertThat(regranted).containsExactlyInAnyOrder(d1, d2);

        // 清空 → 全撤销。
        assertThat(bindingService.grant(upstream, List.of(), "dispatch")).isEmpty();
        assertThat(bindingService.grantedDownstreamIds(upstream)).isEmpty();
    }

    @Test
    void seedWmsUpstreamIsGrantedDownstream() {
        long wms = appId("ak_wms_main"); // V7 种子上位侧应用（WMS-MAIN）
        List<Long> granted = bindingService.grantedDownstreamIds(wms);
        assertThat(granted).isNotEmpty();
        // WMS-MAIN 被授权指挥 PALLET01 等下位侧应用
        assertThat(granted).contains(appId("ak_pallet_opcua"));
    }

    @Test
    void createReusesSoftDeletedRowInsteadOfHittingUniqueIndex() {
        // 合成 pair，避免与其它用例/种子互相干扰（@SpringBootTest 无事务回滚）。
        long up = 95001L;
        long down = 95002L;

        AppBindingEntity first = bindingService.create(binding(up, down));
        Long id = first.getId();
        assertThat(bindingService.isAllowed(up, down)).isTrue();

        bindingService.delete(id); // 软删：行仍占唯一槽位 (up,down,tenant)
        assertThat(bindingService.isAllowed(up, down)).isFalse();

        // 再 create 同一对：复活既有行而非 insert，绝不撞唯一索引。
        AppBindingEntity revived = bindingService.create(binding(up, down));
        assertThat(revived.getId()).isEqualTo(id);
        assertThat(bindingService.isAllowed(up, down)).isTrue();

        // 活跃重复 → 干净 CONFLICT（不是裸 DB 异常）。
        assertThatThrownBy(() -> bindingService.create(binding(up, down)))
                .isInstanceOf(WcsException.class)
                .satisfies(ex -> assertThat(((WcsException) ex).getCode()).isEqualTo(ErrorCode.CONFLICT.code()));

        bindingService.delete(id);
    }

    @Test
    void grantIsTenantScopedAndDoesNotTouchOtherTenant() {
        long up = 96001L;
        long down = 96002L;
        // 一条属于租户 2 的绑定（唯一索引含 tenant，故可与租户1并存）。
        jdbcTemplate.update("insert into \"wcs_app_binding\" "
                + "(\"upstream_app_id\",\"downstream_app_id\",\"scope\",\"enabled\",\"creator\",\"is_valid\",\"tenant_id\") "
                + "values (?,?,?,true,'t2',true,2)", up, down, "dispatch");

        // 管理员(租户1) 对该上位侧授权空集合：只应对账租户1，绝不软删租户2的行。
        bindingService.grant(up, List.of(), "dispatch");

        Integer tenant2Alive = jdbcTemplate.queryForObject(
                "select count(*) from \"wcs_app_binding\" where \"upstream_app_id\"=? and \"downstream_app_id\"=? "
                        + "and \"tenant_id\"=2 and \"is_valid\"=true", Integer.class, up, down);
        assertThat(tenant2Alive).isEqualTo(1);
    }

    @Test
    void grantConvergesScopeOnAlreadyActiveRow() {
        long up = 97001L;
        long down = 97002L;
        bindingService.grant(up, List.of(down), "dispatch");
        assertThat(scopeOf(up, down)).isEqualTo("dispatch");

        // 同下位侧再授权但改 scope：声明式应把已激活行的 scope 收敛到入参。
        bindingService.grant(up, List.of(down), "read");
        assertThat(scopeOf(up, down)).isEqualTo("read");

        bindingService.grant(up, List.of(), "dispatch");
    }

    @Test
    void updateToSoftDeletedPairReturnsCleanConflict() {
        long up = 98001L;
        long active = 98002L;
        long ghost = 98003L;
        AppBindingEntity x = bindingService.create(binding(up, active)); // 活跃行 (up,active)
        AppBindingEntity y = bindingService.create(binding(up, ghost));
        bindingService.delete(y.getId()); // 软删 (up,ghost)，仍占唯一槽位

        // 把活跃行的下位侧改成 ghost → 撞软删行的唯一索引；应返回干净 CONFLICT，而非裸 DB 异常。
        x.setDownstream_app_id(ghost);
        assertThatThrownBy(() -> bindingService.update(x))
                .isInstanceOf(WcsException.class)
                .satisfies(ex -> assertThat(((WcsException) ex).getCode()).isEqualTo(ErrorCode.CONFLICT.code()));

        bindingService.delete(x.getId());
    }

    @Test
    void isAllowedIsTenantScopedForDispatch() {
        long up = 99001L;
        long down = 99002L;
        // 一条租户 2 的绑定。
        jdbcTemplate.update("insert into \"wcs_app_binding\" "
                + "(\"upstream_app_id\",\"downstream_app_id\",\"scope\",\"enabled\",\"creator\",\"is_valid\",\"tenant_id\") "
                + "values (?,?,?,true,'t2',true,2)", up, down, "dispatch");

        // 租户1 的下发鉴权(tenant=1) 不应被租户2的绑定放行。
        assertThat(bindingService.isAllowed(up, down, 1L)).isFalse();
        assertThatThrownBy(() -> bindingService.assertAllowed(up, down, 1L))
                .isInstanceOf(WcsException.class)
                .satisfies(ex -> assertThat(((WcsException) ex).getCode()).isEqualTo(ErrorCode.FORBIDDEN.code()));

        // 租户2 视角放行；不限租户的内部重载也放行。
        assertThat(bindingService.isAllowed(up, down, 2L)).isTrue();
        assertThat(bindingService.isAllowed(up, down)).isTrue();
    }

    private String scopeOf(long up, long down) {
        return jdbcTemplate.queryForObject(
                "select \"scope\" from \"wcs_app_binding\" where \"upstream_app_id\"=? and \"downstream_app_id\"=? "
                        + "and \"is_valid\"=true", String.class, up, down);
    }

    private static AppBindingEntity binding(long up, long down) {
        AppBindingEntity b = new AppBindingEntity();
        b.setUpstream_app_id(up);
        b.setDownstream_app_id(down);
        return b;
    }
}
