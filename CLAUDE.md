# CayleyWCS 项目 - 仓库控制系统

## 角色

本目录是凯利智飞 **WCS（仓库控制系统）** 项目，受 CTO（`cayley_project/`）管辖。定位在 WMS（上位）与设备（下位 PLC/堆垛机/输送线）之间，负责多协议设备通讯、连接治理、任务调度与实时监控。

## 架构基线（对齐 CayleyWMS）

- 后端：Java 25 · Spring Boot 4.0.6 · MyBatis-Plus · PostgreSQL 17 · Redis 7.4 · Flyway · springdoc。
- 前端（M6）：Vue 3 · Vite · Vuetify 3 · VXE Table。
- 根包 `com.cayleywcs`；分层见 `README.md`。

## 后端编码铁律（同 WMS）

1. 业务接口一律 `@PostMapping` + `application/json`；ID/查询/删除/分页参数走 JSON body。
2. JWT 仅在 `Authorization: Bearer <token>`；开放接口 `/open/**` 走独立 AppKey 鉴权。
3. Service 为 interface + `impl/*ServiceImpl`；Controller 依赖 interface。
4. 持久化用 MyBatis-Plus Mapper（不引入 JdbcTemplate 业务路径）；迁移用 Flyway。
5. 统一响应 `ApiResponse<T>`（`isSuccess/code/errorMessage/data`）、分页 `PageData<T>`。
6. 异常统一经 `GlobalExceptionHandler` 转 `ApiResponse.error`。

## 铁律 - 改完代码必须更新 README

任何代码改动后，同步更新本项目 `README.md`（结构、端口、表、接口、里程碑）。

## 端口

backend `20021`、frontend `5184`、PostgreSQL host `5433`、Redis host `6380`，避开 WMS。

## 可见性

仅访问本目录及子目录；不引用兄弟项目源码，跨项目协调走 CTO。
