# AGV 只读与三传感器模块 Docker 部署记录

任务：`TASK-20261112-61E1BFAE`。部署到本机现有 `cayley_wcs` Compose 项目，仅替换 `cayleywcs-backend`、`cayleywcs-frontend`。沿用原访问端口、环境配置、网络及 PostgreSQL / Redis 容器和数据卷。

## 初次部署内容

| 项目 | 结果 |
| --- | --- |
| 后端镜像 | `cayley_wcs-backend:agv-readonly-20261112` |
| 前端镜像 | `cayley_wcs-frontend:agv-readonly-20261112` |
| 数据库 | Flyway V21 → V22，新增遥测表及协议字典 |
| AGV 档案 | `SEER_AGV_01`，状态端点引用 `existing-seer`，只读连接已开启 |
| 传感器档案 | `existing-seer-sensors`，Unit ID 80 / 1 / 2；红外寄存器 0、量程 0–600 ℃ |
| 传感器运行态 | DR164 端点未配置，采集停用，12 指标为 UNKNOWN，历史为空 |
| 运动控制 | 未开放；只读请求为 1004 / 1005 / 1020 |

使用已构建的 Java JAR 和前端 dist 打包，沿用原容器镜像的 Java / Nginx 运行层。新镜像不包含现场端点文件或登录凭据。后端通过只读挂载加载仓库外的 Spring 配置，原 simulator profile 保留。

## 验证结果

- 前后端构建完成，健康接口通过现有 Nginx 代理返回成功，页面内容与本次发布产物一致。
- V22 迁移成功；对原有 13 张业务及配置表按旧 ID 范围核对行数和摘要一致。应用表只排除运行时 status 和 last_update_time，其余字段仍参与比较。
- 现有 PostgreSQL / Redis 的容器 ID、数据卷挂载保持一致；旧业务任务未新增、删除或改写。
- 通过部署后的 HTTP 接口读取实车，连接 RUNNING、质量 GOOD，三个状态查询的接收时间持续推进。
- 后台重启后自动恢复 AGV 状态连接，原 JWT 仍有效；传感器默认档案与停用状态保留。
- 浏览器登录后，现有看板和 WebSocket 正常；“AGV 连续监测”显示实车状态、待配置的网关及 12 项未知指标。

本次验收覆盖容器部署、实车只读和默认档案。真实 DR164 及传感器连续采集仍待现场端点接入；此前模拟器采集验证不能替代实物验收。实现与接线配置说明见 [接入说明](../agv-readonly-telemetry.md)。

## 本机发布资料与后续启动

运行资料统一保存在仓库外的 `%LOCALAPPDATA%/CayleyWCS/deploy/20261112-agv-readonly/`：

- `cayleywcs-v21-before-agv.dump`：停止旧后台后生成的完整 PostgreSQL 自定义格式备份，已用 `pg_restore --list` 验证目录。
- `before.private.json`、`after.private.json`：容器快照；`database.before.private.json`：原业务记录校验基线。
- `override.private.json`、`compose.resolved.private.json`、`manifest.private.json`：本次发布配置和构建摘要。
- `acceptance.private.json`、`live-agv-state.private.json`、`backend-startup.private.log`：原始验证结果。

这些文件含运行参数、凭据或实车数据，不纳入版本库。端点源文件为 `%LOCALAPPDATA%/CayleyWCS/agv/application-agv-local.properties`，以只读方式挂载到后台。补齐 DR164 的真实地址后重新启动后台，再在页面开启采集；不要根据默认传感器档案推测网关地址。

同日按用户要求追加 AGV 看板中文标签更新（`TASK-20261112-D814E12A`），当次前端镜像为 `cayley_wcs-frontend:agv-zh-labels-20261112`。定位、运动、导航、连接、采集及存储状态统一使用中文说明；本车内嵌 JSON 字符串形式的运动说明也已覆盖。类型检查、构建及真实页面验证通过。仅更新前端，后台、PostgreSQL、Redis 的容器 ID 和启动时间保持不变。

这次页面更新的运行资料存放在 `%LOCALAPPDATA%/CayleyWCS/deploy/20261112-agv-labels/`，其中 manifest 继承初次部署配置并追加新的前端镜像 override；前端更新前的镜像保留为 `cayley_wcs-frontend:before-agv-labels-20261112`。

后续使用统一应用与状态设置的最新发布配置启动前后端，可在本机 PowerShell 执行：

```powershell
$releaseDir = Join-Path $env:LOCALAPPDATA 'CayleyWCS/deploy/20261112-jvm-c2-fix'
$releaseManifest = Get-Content (Join-Path $releaseDir 'manifest.private.json') -Raw | ConvertFrom-Json
$composeArgs = @($releaseManifest.compose_command | Select-Object -Skip 1)
& docker @composeArgs up -d --no-deps --no-build backend frontend
```

当前容器的 Compose 标签也保留了原配置和本次 override 的路径。单独使用仓库默认 Compose 文件会遗漏本次现场端点挂载；继续维护时应沿用上述发布配置。

旧镜像仍保留，并额外标记为 `cayley_wcs-backend:rollback-before-agv-20261112` 和 `cayley_wcs-frontend:rollback-before-agv-20261112`。未执行回退；如需回退，应先停用新模块并评估 V22 与旧版本兼容性，数据库恢复不能直接覆盖部署后的新业务数据。

## 统一应用与状态设置发布

任务 `TASK-20261112-8B65B9A9`，2026-11-12 15:02（Asia/Tokyo）验收完成。仍使用当前 Compose 项目及访问入口，只重建前后端容器。

| 项目 | 当前结果 |
| --- | --- |
| 后端 | `cayley_wcs-backend:unified-state-20261112` |
| 前端 | `cayley_wcs-frontend:unified-state-20261112` |
| 数据库 | V22 → V23，新增传感器应用关联及字典项 |
| 仙工 AGV | 原应用 ID 23；新发布版本 #14；40 业务状态点位 + 3 内部诊断报文点位 |
| 振动 / 噪声 / 红外 | 应用 ID 24 / 25 / 26，发布版本 #15 / #16 / #17，10 / 1 / 1 个只读点位 |
| 入口 | 连接监控 → 状态设置；可在弹窗切换不同应用 |
| 外设现场状态 | 网关地址未配置，三个应用显示待配置，未伪造测量值 |

实车只读状态连续两次接收时间推进，位置/速度/导航状态质量 GOOD；原 AGV 身份、凭据和端点引用校验一致。三个传感器应用注册幂等，默认站号/红外档案保留。浏览器验证了协议管理的 10 个振动点位、各应用状态切换、只读点位无编辑控件、现有 PLC 未进入维护模式时设置控件禁用、采集档案与分传感器历史入口。原 AGV 独立菜单已合并。

停止后台后生成 `cayleywcs-v22-before-unified-state.dump`（164003 字节），已通过 `pg_restore --list` 验证；未恢复或覆盖数据库。旧 ID 范围内的任务、任务链、应用（排除本次升级的 AGV 及运行态字段）、绑定、协议、点位、协议版本共 7 张表摘要一致。原 PostgreSQL / Redis 容器 ID 和数据挂载一致；现有 100 条业务任务保留。

完整发布资料位于 `%LOCALAPPDATA%/CayleyWCS/deploy/20261112-unified-state/`：manifest 继承前次发布并追加新镜像 override；前后快照、数据库摘要、验收原始状态、构建日志和界面截图均留在仓库外。旧镜像标记为 `cayley_wcs-backend:before-unified-state-20261112`、`cayley_wcs-frontend:before-unified-state-20261112`；回退需同时考虑应用所选 v2 协议和 V23 数据，不能仅替换旧后端镜像后继续打开新传感器应用。

## 点位面板与逐点设置发布

任务 `TASK-20261112-3BD0A95C`，2026-11-12。前端更新为 `cayley_wcs-frontend:state-panels-20261112`，后端仍为 `cayley_wcs-backend:unified-state-20261112`，数据库仍为 V23。仅重建前端容器，后台、PostgreSQL、Redis 的容器 ID 和启动时间均保持一致；HTTP 提供的页面入口与本次构建产物一致。

“状态设置”改为按内容分区的点位卡片，每个可写点位单独打开类型对应的编辑窗口。只读点位保持查看；暂不可写点位显示禁用按钮和原因。前端构建、类型检查、8 组输入与权限边界测试通过。独立页面夹具完成数值/布尔/枚举/文字/数组/64 位整数的 6 次逐点提交及 1 次自动门互斥联动，核对实际请求载荷、版本和配置代次；范围与长度错误、服务器拒绝、编辑期间撤权、断线及版本变化均验证。夹具写入不连接真实设备。

当前 Docker 页面验证：AGV 有 14/15/11 个定位/运动/导航卡片，内部诊断勾选后总计 43 个；三个传感器有 10/1/1 个只读卡片，未配置时不显示假测量值；现有 PLC 的 8 个可写点位在非维护条件下均禁用。原有协议、采集和业务接口未更改。

发布资料在 `%LOCALAPPDATA%/CayleyWCS/deploy/20261112-state-panels/`，包含继承的 Compose 命令、新前端 override、容器前后快照、构建入口摘要、页面截图及界面测试请求载荷。旧前端镜像保留为 `cayley_wcs-frontend:before-state-panels-20261112`；本次前端回退无需数据库迁移。

## 宽窗口与紧凑卡片发布

任务 `TASK-20261112-3E68DA3B`，2026-11-12。前端更新为 `cayley_wcs-frontend:state-panels-compact-20261112`。状态窗口取消固定宽度上限，四周保留 8px；压缩卡片字号与间距，沿用原有分组、网格规则和点位内容顺序。

前端构建、类型检查通过，当前 Docker 浏览器实测：1280×720 下窗口为 1264×704，同屏完整点位卡片由 8 个增至 12 个，首张卡片高度由约 187px 降至约 155px；1600×900 下窗口为 1584×884。两种尺寸均无点位区域横向溢出，AGV 三个分区与振动传感器 10 个点位显示正常；浏览器控制台无错误，临时测试视口已恢复。

仅重建前端容器，后台、PostgreSQL、Redis 的容器 ID 和启动时间均与发布前一致，页面入口与构建产物一致。仓库外发布目录为 `%LOCALAPPDATA%/CayleyWCS/deploy/20261112-state-panels-compact/`，包含继承前次配置的 manifest、新 override、容器快照、尺寸对比和截图。旧镜像保留为 `cayley_wcs-frontend:before-state-panels-compact-20261112`。

## 进一步缩小点位卡片发布

任务 `TASK-20261112-68C959A4`，2026-11-12。前端镜像 `cayley_wcs-frontend:state-panels-dense-20261112`，沿用接近全屏的状态窗口。卡片最小宽度 245→220px，内边距 6/10px，网格间距 6px，数值字号 19px；保持分组和内容顺序。

类型检查、构建及当前 Docker 页面验证通过。1280×720 下卡片为每行 5 个，首卡尺寸约 233×113px，前版首卡高度约 155px，同屏完整点位 12→19。40 个业务卡片和三分区完整，振动传感器 10 个点位正常；卡片无横向溢出，普通数值没有多余局部滚动条，控制台无错误；现有 PLC 8 个设置入口仍按原条件禁用。

仅更新前端，后台、PostgreSQL、Redis 的容器 ID 与启动时间保留，HTTP 首页与构建入口一致。发布资料在 `%LOCALAPPDATA%/CayleyWCS/deploy/20261112-state-panels-dense/`，包含继承的 manifest、override、前后快照、`layout-evidence.json` 和 `ui-agv-dense.jpg`。旧镜像保留为 `cayley_wcs-frontend:before-state-panels-dense-20261112`。

## 当前发布：实时看板与应用适配器

任务 `TASK-20261112-122104C1`，2026-11-12。当前前后端镜像分别为 `cayley_wcs-frontend:dashboard-adapters-20261112`、`cayley_wcs-backend:dashboard-adapters-20261112`，数据库 V23→V24。沿用当前 Compose、网络、端口、现场端点挂载和原 PostgreSQL/Redis 容器及卷。

部署前完成后端 371 项测试（361 通过、10 项环境门禁跳过、零失败/错误），前端构建、类型检查及 8 项点位测试。协议类型移除两种设备专用类型后，双向协议类型矩阵由 10×10 改为 8×8，保留全部常规协议与仿真覆盖。PG17 独立副本按构建 JAR 执行 V23→V24，17 项保留及重复执行检查全部通过，副本已停止。正式切换时停止后台，生成并验证 `cayleywcs-v23-before-dashboard-adapters.dump`；启动后 15 项逐字段检查通过，应用运行态 `status/last_update_time` 单独排除。所有原任务、链、点位、发布 ID/指纹、绑定及凭据保留。

浏览器验收：两块应用看板均为 400px 高、每行两点的有边框网格；长数组换行且内部滚动，无横向溢出；状态与应用名称同一行、编辑入口位于右上方。实际勾选 2 项、刷新保留、全选 43 项、清空以及恢复 8 个常用 AGV 点位均验证，全部中文名称正常。协议新建列表为 7 种常规/仿真类型，仙工与传感器现有协议均为 TCP；应用管理独立展示并选择适配器。

实车 1004/1005/1020 三包接收时间继续推进、质量 GOOD。AGV 43 个点位，振动/噪声/红外 10/1/1 个点位及默认档案保留，外设端点仍待配置。运行 JAR 与构建摘要、前端首页与构建产物一致。

该次首次启动遇到原 Temurin 25.0.4.1+1 JVM 的 `C2 CompilerThread1` 在编译 MyBatis `DynamicContext$ContextMap.get` 时 SIGSEGV，崩溃日志保存在仓库外。当次 override 曾加入 `JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1`，使用 C1 编译兼容恢复运行。同日后续发布已更换 JVM 并覆盖移除此限制，当前配置须使用上面的 `20261112-jvm-c2-fix` manifest；调查、验证和未证实事项见 [JVM 事故处理](2026-11-12-jvm-c2.md)。

资料目录 `%LOCALAPPDATA%/CayleyWCS/deploy/20261112-dashboard-adapters/` 包含 manifest、override、容器快照、备份、PG 演练及正式库证据、私有 API 结果、JVM 日志、`acceptance.json`、看板及编辑窗口截图。旧前后端镜像标记为 `before-dashboard-adapters-20261112`；V24 改变协议分类与应用适配器，回退须连同数据库兼容性评估，不直接覆盖当前业务库。
