package com.cayleywcs.connection;

import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.config.WcsMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 连接自愈（M12 单机自愈）：把「期望态 open 但当前未连接」的应用拉起。
 * <ul>
 *   <li>启动恢复：{@link ApplicationRunner} 在后台线程跑一次（不阻塞启动，避免建连超时拖慢就绪）；</li>
 *   <li>周期对账：{@link Scheduled} 补开失败的启动恢复、以及运行期被释放的连接（desired=open && !isOpen）。</li>
 * </ul>
 * 职责边界：本类只处理**不在连接表**的应用（isOpen 判定）；在表中的 FAILED 连接由 {@code ConnectionManager}
 * 看门狗 rearm 就地重连（保连接实例与轮询 worker，续跑依赖 M11 再收养）。两者以 isOpen 互斥、不重复建连。
 */
@Component
public class ConnectionRecoveryRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ConnectionRecoveryRunner.class);
    /** 连接槽满的恢复告警码（复用通讯类 -1：连接成功后随 RUNNING 转换自动清除）。 */
    private static final long FAULT_CODE_RECOVERY = -1L;

    private final ApplicationService applicationService;
    private final ConnectionManager connectionManager;
    private final AlarmService alarmService;
    @Autowired private WcsMetrics metrics;

    public ConnectionRecoveryRunner(ApplicationService applicationService, ConnectionManager connectionManager,
                                    AlarmService alarmService) {
        this.applicationService = applicationService;
        this.connectionManager = connectionManager;
        this.alarmService = alarmService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 后台线程跑启动恢复：不阻塞 SpringApplication.run()（单个建连超时最长 connect-timeout 秒）
        Thread t = new Thread(this::reconcileOnce, "wcs-recovery-startup");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(initialDelayString = "${cayleywcs.connection.recovery-reconcile-interval-ms:30000}",
            fixedDelayString = "${cayleywcs.connection.recovery-reconcile-interval-ms:30000}", scheduler = "taskScheduler")
    public void reconcile() {
        WcsMetrics.tick(metrics, "connection-recovery", this::reconcileOnce);
    }

    /** 单轮对账（公开供测试驱动）。返回本轮成功建连数。 */
    public int reconcileOnce() {
        int opened = 0;
        for (ApplicationEntity app : applicationService.listAll()) {
            if (!Boolean.TRUE.equals(app.getEnabled()) || !"open".equals(app.getDesired_state())) {
                continue;
            }
            Long appId = app.getId();
            if (connectionManager.isOpen(appId)) {
                continue; // 已在连接表（含 FAILED，由看门狗 rearm 处理）
            }
            try {
                connectionManager.open(appId);
                opened++;
                log.info("自愈恢复连接 app {} (id={})", app.getApp_code(), appId);
            } catch (WcsException ex) {
                // 建连失败(超时/拒绝)已由 FAILED 转换触发 -1 报警；槽满不触发转换，此处补记
                if (ex.getCode() == ErrorCode.CONN_POOL_FULL.code()) {
                    alarmService.raise(appId, FAULT_CODE_RECOVERY, "error",
                            "自愈恢复失败：连接槽已满，app " + app.getApp_code(),
                            "增大 cayleywcs.connection.max-connections 或减少期望连接应用数");
                }
                log.warn("自愈恢复 app {} 失败: {}", app.getApp_code(), ex.getMessage());
            } catch (RuntimeException ex) {
                log.warn("自愈恢复 app {} 异常: {}", app.getApp_code(), ex.getMessage());
            }
        }
        return opened;
    }
}
