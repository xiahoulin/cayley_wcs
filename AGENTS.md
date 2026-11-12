# CayleyWCS 项目 - AGENTS

本目录是凯利智飞 **WCS（仓库控制系统）** 项目，受 CTO（`cayley_project/`）管辖。定位在 WMS（上位）与设备（下位 PLC/堆垛机）之间，负责多协议设备通讯、连接治理、任务调度与实时监控。

## 架构基线（对齐 CayleyWMS）

- 后端：Java 25 · Spring Boot 4.0.6 · MyBatis-Plus · PostgreSQL 17 · Redis 7.4 · Flyway · springdoc。
- 前端（M6）：Vue 3 · Vite · Vuetify 3 · VXE Table。
- 根包 `com.cayleywcs`；分层见 `README.md`。

## 编码铁律（同 WMS）

1. 业务接口一律 `POST` + `application/json`。
2. 管理端 JWT 仅在 `Authorization: Bearer`；对接应用通过 `POST /system/token` 获取 access_token，`/open/**` 采用 app_id、app_key、access_token、request_id、data 的 JSON 封装，认证后继续校验设备绑定权限。
3. Service interface + `impl/*ServiceImpl`；MyBatis-Plus + Flyway。
4. 统一响应 `ApiResponse<T>` / 分页 `PageData<T>`；异常经 `GlobalExceptionHandler`。
5. 改完代码必须同步更新 `README.md`。

## 端口

backend `20021`、frontend `5184`、PostgreSQL host `5433`、Redis host `6380`。

## 可见性

仅访问本目录及子目录；跨项目协调走 CTO。
