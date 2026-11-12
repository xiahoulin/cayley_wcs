# 仙工 AGV IP 配置与站点导航

任务：`TASK-20261112-DBAB1D0A`。

## 使用

在「应用管理 → 仙工 AGV → 编辑」维护车辆 IPv4 地址。地址保存到该应用的 `conn_params.ip`，状态查询和导航共用；原有端点引用、超时、发布点位及其他应用配置保留。原连接处于开启状态时，保存连接参数后自动重连。存在尚未核对的导航下发记录时，禁止修改连接参数或切换协议。

在「连接监控 → 仙工 AGV → 导航至站点」填写**当前地图站点 ID**，例如 `AP0`，点击「发送导航指令」。这里的名称不是地图显示标签，不做模糊匹配。站点附带的动作仍由车辆地图配置决定。系统不抢占控制权，不自动重发运动命令。

下发接口仅面向已登录管理员，并限制在当前账户所属应用；检查当前发布版本、配置代次、应用启用状态、连接、未完成任务和未清理的设备任务号。发送前重新查询定位、速度及导航状态，要求定位置信度有效、任务已结束或空闲且底盘停稳。配置不足、设备拒绝、应答无法确认都会明确提示。

成功或结果不确定的下发保留锁定。下一次下发前，操作员须核对车辆实际状态，并在窗口中点击「核对车辆状态并解除锁定」；后台再次确认任务已结束且停稳。这是**单次导航下发与回执功能**，并未接入通用任务队列、自动到站完成判定、自动重试、暂停、取消或顶升编排。

## 协议与点位

协议类型仍为通用 `tcp`，应用适配器为 `seer_agv`。`SeerConnectionSettings` 解析应用地址，`SeerTcpAdapter` 保持三类状态查询，`SeerNavigationSession` 负责独立导航连接的组包和回执校验。

| 连接参数 | 作用 |
| --- | --- |
| `ip` | 应用维护的 IPv4 地址，优先于历史部署端点的地址 |
| `endpointRef` | 可选历史端点引用，保留租户归属校验及原有兼容配置 |
| `statusPort` | 可选状态端口，未指定时沿用历史端点端口，否则默认 19204 |
| `navigationPort` | 可选导航端口，默认 19206 |
| `timeoutMs` | 可选整帧超时，100–10000 ms；默认沿用原端点，否则 1000 ms |

站点导航使用 API `3051`，请求体为 `{"id":"AP0"}`，16 字节 v1 头和 UTF-8 数据区，正常响应 API `13051`。请求序号、响应编号、长度、保留区和显式整数 `ret_code` 均须有效。仅 `ret_code=0` 表示设备确认接收；状态查询的“缺省返回码兼容”不应用于导航。通用错误 API `60000` 按失败处理。

导航 V3 合同保留 V2 的 43 个状态/诊断点位，增加以下两点：

| 点位编码 | 中文名称 | 方向 / 类型 | 含义 |
| --- | --- | --- | --- |
| `nav_station` | 导航目标站点 | 请求 / STRING | 当前地图站点 ID，通过专用导航操作提交 |
| `nav_send_success` | 最近导航下发是否成功 | 响应 / BOOL，可空 | 成功应答为 true，明确拒绝或未发送为 false，应答未知或当前连接尚无下发记录为空 |

已有应用通过 `/agv/navigation/enable` 克隆所属协议、发布 V3 并显式切换，旧协议及不可变快照保留；重复启用不重复创建。原有状态同步入口不会把 V3 降级。普通点位写接口继续拒绝 AGV 运动写入，避免绕过专用流程。

接口定义参考[仙工官方历史 TCP API 手册](https://github.com/seer-robotics/Robokit_TCP_API_py/blob/master/robotkit-netprotocol-l-1.2.1.pdf)及[官方 Java SDK 端口路由](https://github.com/seer-robotics/SeerSdk4j/blob/master/src/main/java/com/seer/sdk/rbk/RbkClient.java)，并按 `seer-agv-operator` 技能区分设备接收与到站完成。模拟器通过不等于实车运动验收。

## 管理接口

全部 `POST application/json`，使用管理端 Bearer JWT。`/agv/navigation/state`、`/enable` 请求为 `{"appId":23}`；AppID 必须取本平台实际分配值。

`/agv/navigation/send` 请求示例：

```json
{"appId":23,"revisionId":18,"configurationEpoch":4,"requestId":"unique-command-001","stationName":"AP0"}
```

版本和配置代次取 `/agv/navigation/state` 的当前返回，不使用示例固定值。成功响应信封的 `data.sent` 为是否收到成功应答，`data.status` 的含义如下：

| 状态 | sent | 含义 |
| --- | --- | --- |
| `accepted` | true | 设备确认接收，不代表到站 |
| `rejected` | false | 设备明确拒绝，附返回码和说明 |
| `failed` | false | 导航连接未建立，未发送指令 |
| `unknown` | false | 写入后超时或应答无效，可能已被执行，不可据此重发 |
| `sending` | false | 已持久登记，尚无持久回执；重启后仍须核对 |

同一应用的相同请求编号始终返回原结果，不再次发送；相同编号配不同站点或版本会被拒绝。`/agv/navigation/acknowledge` 请求为 `{"appId":23,"requestId":"unique-command-001"}`，仅解除人工核对锁定，不修改原下发结果，不声称导航已到达目标。

若浏览器请求中断，窗口保留原请求编号和站点；「重试本次导航请求」仍是显式导航请求，后台已登记的指令不会重发。「刷新下发结果」只查询记录，不发送导航。

V27 新增 `wcs_agv_navigation_command`，发送前提交记录，回执单独保存，避免进程退出后自动重发。导航历史引用的应用和发布版本禁止删除；该表不作为瞬时缓存清空。现有数据库业务记录无需改写。

## 验证入口

- `backend/src/test/java/com/cayleywcs/agv/SeerNavigationTest.java`：真实 TCP，UTF-8、分片、异常应答、拒绝、超时及不重发。
- `backend/src/test/java/com/cayleywcs/agv/AgvNavigationIntegrationTest.java`：完整 HTTP + 数据库 + 双端口模拟器，IP、版本升级、权限、请求去重、状态核对和删除保护。
- `scripts/postgres_migration_rehearsal.py`：支持 V26 → V27，只在数据库副本升级并比较全部原有业务数据。

真实车辆验证限于只读状态查询；本任务不发送实车导航。部署备份、现场 IP 和原始响应保留在仓库外。

2026-11-12 已部署至现有 WCS Docker：相关测试 60 项、PostgreSQL 副本 HTTP 联调 3 项、升级保留检查 17 项、部署检查 46 项均通过；连续 121 秒共 348 次生产只读接口检查通过。浏览器确认了 IP 字段与导航入口。详见[验收证据](operations/2026-11-12-agv-navigation-evidence.json)及[工作记录](../.logs/2026-11-12-agv-navigation.md)。
