# CayleyWCS 双机热备设计（仅设计，未实现）

M12 已落地**单机自愈**（连接期望态持久化 + 重启恢复 + 看门狗 rearm + 任务续跑再收养）。本文给出**双机热备（Active-Standby）** 的演进设计，供后续实现；不改变现有单机行为。

## 目标与非目标

- **目标**：主实例宕机时，备实例在秒级接管全部设备连接与任务调度，不重复驱动设备、不丢任务终态。
- **非目标**：多主并发（Active-Active 分片）留待更大规模；本设计是「一写一备」，同一时刻只有一个实例驱动设备。

## 为什么单机自愈是热备的基座

热备的备机接管 = 「在另一台机器上做一次重启恢复」。M12 的三块能力正是接管所需：
1. **desired_state 持久化**：备机读 `wcs_application.desired_state='open'` 即知该连哪些设备（意图在 DB，不在内存）。
2. **握手重入安全 + 再收养**（M8 F3 + M11）：备机重建连接后，DB 里 `dispatched/executing` 的在飞任务按 `handshake_step` 续推；设备若已自主完成停在 `status_Task=2`，`doCheck` 按任务号**再收养**补发完成确认——不重复执行物理动作。
3. **乐观锁 + 幂等**：任务/链的所有写走 `version`，chain 物化确定性 `task_no` + 唯一索引——两实例短暂重叠期也不产生重复子任务。

## 核心机制

### 1. Leader 选举（Redis 租约 + fencing token）

```
SET wcs:leader <instanceId> NX PX <leaseTtlMs>        # 抢锁，仅一个成功
# leader 周期续租：PEXPIRE / 重设，续租失败即让位
# fencing token：每次成功获得 leadership 递增一个单调计数（Redis INCR wcs:leader:epoch）
```

- 只有 leader 运行：`TaskEngine`、`TaskOrchestrator`、`TaskRecoveryScheduler`、`ConnectionRecoveryRunner.reconcile`、看门狗、`MonitorPusher`。备机这些 `@Scheduled` 空转（首行判 `if (!leadership.isLeader()) return;`）。
- leader 崩溃 → 租约 TTL 到期 → 备机抢到锁 → 成为新 leader → 跑一次 `reconcileOnce()` 重建连接 + 引擎接管在飞任务。
- **fencing**：设备写命令携带/校验 epoch，防「假死 leader 复活后仍在写设备」造成双写（见脑裂防护）。

### 2. 接管流程（备机成为 leader）

```
1) 赢得租约 + epoch++
2) ConnectionRecoveryRunner.reconcileOnce()：对 desired_state='open' 的应用逐个 open()
   —— 复用 M12 现成代码，零新增
3) TaskEngine 下一 tick 起对 RUNNING 连接推进 DB 中的在飞任务（handshake_step 续推 + 再收养）
4) TaskOrchestrator 下一 tick 起从 DB 派生链状态继续物化/推进
5) MonitorPusher 恢复 WS 推送（seq 从 0 重新计数 —— WMS 侧 seq 断号触发 /open/snapshot 全量重同步，既有机制）
```

老 leader 的连接随其进程消失自动断开（TCP/OPC UA 会话关闭），无需备机主动清理。

### 3. appId 分片（可选，Active-Active 演进）

单纯热备下所有设备归 leader。若要负载均衡：
- 每设备按 `appId % N` 或一致性哈希分配到某实例；每个实例只 leader 自己分片的设备（分片级租约 `wcs:leader:shard:<k>`）。
- 任务/链的 DB 写不变（乐观锁全局有效）；引擎/编排器只处理本分片 `app_id` 的行（查询加分片过滤）。
- 再平衡（实例增减）走「分片租约到期→重抢」，接管流程同上。

## 脑裂防护（双写设备是红线）

WCS 驱动物理设备，**两实例同时写同一设备**可能造成碰撞/损坏，必须硬防：

1. **fencing token**：每次设备写前校验本实例持有的 leadership epoch == Redis 当前 epoch（或把 epoch 写入设备一个保留寄存器，PLC 侧比对——需设备配合）。旧 leader 网络分区恢复后 epoch 已过期，写被拒。
2. **设备侧单闸**：网络分段使每台设备只接受来自 WCS VIP 的连接；VIP 只指向当前 leader（配合 keepalived / 云 LB 健康检查）。这是**不依赖应用正确性**的物理兜底。
3. **租约 TTL > 最坏 GC 停顿**：TTL 太短会因 leader 长 GC 误判宕机→备机接管→双 leader。TTL 取 `max(GC暂停上限, 网络抖动) × 2`（如 10s），接管延迟换安全。
4. **写前续租检查**：leader 在每个设备写批次前确认租约仍持有（`PTTL > 阈值`），临界则暂停写、让位。

## 部署形态

- 两实例 + 共享 PostgreSQL（主）+ Redis（租约）。DB/Redis 自身高可用（PG 流复制 + Redis 哨兵）另行保障。
- 设备网络：VIP 指向 leader；或 frp/L4 LB 做设备侧唯一入口（对齐 gateway 的内网穿透方案）。
- 配置开关：`cayleywcs.ha.enabled`（默认 false=单机）、`cayleywcs.ha.lease-ttl-ms`、`cayleywcs.ha.instance-id`。

## 实施顺序（后续里程碑）

1. `LeadershipService`（Redis 租约 + epoch + `isLeader()`）+ 所有 `@Scheduled` 加 leader 门。
2. 设备写前 fencing 校验（`ConnectionManager.writePoint` 挂钩）。
3. 接管冒烟：双实例部署，kill leader，验证备机秒级接管 + 在飞任务续跑到完成 + 无重复物化。
4. （可选）appId 分片 → Active-Active。

> 现状（M12）：`cayleywcs.ha.enabled` 尚未引入，系统为单实例 + 单机自愈。本设计的所有接管动作都复用 M8–M12 已实现且已过对抗审查的能力，热备实现主要是「加一层 leader 门 + fencing」，不改核心调度逻辑。
