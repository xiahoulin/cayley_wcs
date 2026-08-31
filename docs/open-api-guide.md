# CayleyWCS HTTP 接入 API 文档

> 文档版本：1.0
>
> 核对日期：2026-07-23
>
> 适用对象：WMS、ERP、MES、第三方调度系统
>
> 事实源：CayleyWCS 后端 `/open/**` 控制器、AppKey 鉴权与 Webhook 实现

## 1. 接入范围

本文描述上位系统通过 HTTP 接入 CayleyWCS 的生产接口，包括：

- 单任务下发；
- 任务链下发；
- 任务、报警、任务链增量查询；
- 断线后的设备快照同步；
- WCS 主动 Webhook 回调；
- AppKey/HMAC 鉴权、幂等、错误处理和联调方法。

本文不包含：

- 管理端 JWT 接口；
- WCS 到 PLC/设备侧的 OPC UA、Modbus、S7、MQTT、TCP 等协议；
- WebSocket 实时看板协议的完整字段定义。

开放接口的机器可读定义：

- OpenAPI JSON：`GET /v3/api-docs/open`
- Swagger UI：`/swagger-ui.html`，选择 `open` 分组

OpenAPI 地址通常只在 WCS 内网或直连后端开放，不应为了第三方接入而暴露全部管理端 API。

## 2. 地址与调用约定

### 2.1 Base URL

| 环境 | 请求地址示例 | 签名中的 path |
|---|---|---|
| 直连 WCS 后端 | `http://127.0.0.1:20021/open/task/dispatch` | `/open/task/dispatch` |
| 经 Cayley Gateway | `https://<gateway>/api/wcs/open/task/dispatch` | `/open/task/dispatch` |

Cayley Gateway 当前会把外部前缀 `/api/wcs/` 改写为后端 `/`。因此经网关请求时，URL 使用 `/api/wcs/open/**`，但 HMAC 规范串仍使用 WCS 实际收到的 `/open/**`。

若部署方修改了反向代理规则，签名中的 `path` 必须与 WCS 后端 `requestURI` 完全一致。

### 2.2 HTTP 约定

除健康检查外，开放业务接口统一使用：

```http
POST /open/...
Content-Type: application/json
```

字符编码使用 UTF-8。当前开放接口没有 query 参数；HMAC 的 `path` 不包含域名和 query string。

WCS 业务层通常返回 HTTP 200，业务是否成功必须读取响应体的 `isSuccess`。网关限流、代理故障、网络超时等基础设施错误仍可能返回非 200。

### 2.3 统一响应

```json
{
  "isSuccess": true,
  "code": 200,
  "errorMessage": "",
  "data": {}
}
```

失败示例：

```json
{
  "isSuccess": false,
  "code": 403,
  "errorMessage": "应用未获授权指挥目标设备",
  "data": null
}
```

调用方必须先判断 `isSuccess`，不能只判断 HTTP 状态码。

## 3. 接入准备

上线前由 WCS 管理员完成以下配置：

1. 创建一个代表调用方的上位应用，建议 `direction=upstream`；
2. 生成并安全交付 `app_key`、`app_secret`；
3. 把该上位应用绑定到允许指挥的下位设备应用；
4. 生产环境建议把应用的 `min_sign_version` 设为 `2`；
5. 如需主动通知，由管理员配置 Webhook URL、密钥、事件类型和作用域。

绑定模型为：

```text
调用方上位应用 --wcs_app_binding--> 可指挥的下位设备应用
```

下发单任务或任务链时，WCS 会对目标 `appId` 逐一做绑定授权校验。任意一个目标未授权，整个请求失败。

`app_secret` 只应通过安全渠道交付，不应写入前端、日志、URL、工单正文或公开仓库。

## 4. AppKey/HMAC 请求鉴权

所有 `/open/**` 请求必须携带：

| Header | 必填 | 说明 |
|---|---:|---|
| `X-App-Key` | 是 | WCS 分配的调用方应用 AppKey |
| `X-Timestamp` | 是 | Unix 秒时间戳 |
| `X-Nonce` | 是 | 每次请求唯一的随机串 |
| `X-Sign` | 是 | 小写十六进制 HMAC-SHA256 |
| `X-Sign-Version` | 建议 | `2`；不传或非法值按 v1 处理 |

服务器默认允许时间偏差为 300 秒。相同 `appKey + nonce` 在防重放窗口内只能使用一次。

### 4.1 v2 签名（生产推荐）

v2 把原始请求体纳入签名：

```text
bodyHash  = lowercase_hex(SHA256(rawRequestBodyBytes))

canonical = appKey
          + "\n" + timestamp
          + "\n" + nonce
          + "\n" + UPPERCASE_HTTP_METHOD
          + "\n" + backendRequestPath
          + "\n" + bodyHash

sign = lowercase_hex(HMAC_SHA256(appSecret, UTF8(canonical)))
```

示例规范串：

```text
ak_wms_main
1784793600
8e9485866d4348e8a8df9050990d44af
POST
/open/task/dispatch
9ea7b9b3f93d9d533685fddbba1f7fd34e6c06b0aede0db0da47d81ecfa742ca
```

关键要求：

- `bodyHash` 必须基于实际发送的原始字节；
- 计算签名后不得再次格式化 JSON；
- JSON 空格、换行、字段顺序或转义变化都会改变 v2 签名；
- 方法必须大写；
- 路径必须是 WCS 后端实际收到的路径；
- v2 请求体默认上限为 1 MiB，超过后返回业务码 400。

### 4.2 v1 签名（兼容模式）

v1 不签请求体，仅用于旧系统过渡：

```text
canonical = appKey
          + "\n" + timestamp
          + "\n" + nonce
          + "\n" + UPPERCASE_HTTP_METHOD
          + "\n" + backendRequestPath
```

当应用 `min_sign_version=2` 时，v1 会被拒绝。新接入必须使用 v2。

### 4.3 Python v2 完整调用示例

下面示例可直连 WCS。经 Cayley Gateway 时只需把 `request_url` 改为外部 `/api/wcs/open/...`，`canonical_path` 仍保持 `/open/...`。

```python
import hashlib
import hmac
import json
import secrets
import time
import urllib.request

base_url = "http://127.0.0.1:20021"
canonical_path = "/open/task/dispatch"
request_url = base_url + canonical_path

app_key = "由 WCS 分配"
app_secret = "由 WCS 分配"

payload = {
    "appId": 999001,
    "taskNo": "WMS-20260723-0001",
    "taskType": "1",
    "takeRow": 1,
    "takeColumn": 2,
    "takeFloor": 3,
    "putRow": 2,
    "putColumn": 5,
    "putFloor": 6,
    "portNum": 1,
    "priority": 0,
    "wmsRef": "IN-20260723-0001"
}

# 这组 body 字节既用于 hash，也原样发送。
body = json.dumps(
    payload,
    ensure_ascii=False,
    separators=(",", ":")
).encode("utf-8")

timestamp = str(int(time.time()))
nonce = secrets.token_hex(16)
body_hash = hashlib.sha256(body).hexdigest()
canonical = "\n".join([
    app_key,
    timestamp,
    nonce,
    "POST",
    canonical_path,
    body_hash
])
sign = hmac.new(
    app_secret.encode("utf-8"),
    canonical.encode("utf-8"),
    hashlib.sha256
).hexdigest()

request = urllib.request.Request(
    request_url,
    data=body,
    method="POST",
    headers={
        "Content-Type": "application/json",
        "X-App-Key": app_key,
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Sign-Version": "2",
        "X-Sign": sign
    }
)

with urllib.request.urlopen(request, timeout=30) as response:
    result = json.loads(response.read().decode("utf-8"))

if not result["isSuccess"]:
    raise RuntimeError(
        f'WCS error {result["code"]}: {result["errorMessage"]}'
    )

print(result["data"])
```

## 5. 开放接口总览

| 接口 | 用途 | 幂等/可见范围 |
|---|---|---|
| `POST /open/task/dispatch` | 下发一个设备任务 | `appId + taskNo` 幂等 |
| `POST /open/chain/dispatch` | 下发串行任务链 | 同租户 `chainNo` 幂等 |
| `POST /open/task/query` | 增量查询任务 | 自身及已授权设备 |
| `POST /open/alarm/query` | 增量查询报警 | 自身及已授权设备 |
| `POST /open/chain/query` | 增量查询任务链 | 仅当前 AppKey 下发的链 |
| `POST /open/snapshot` | 获取单设备重连快照 | 自身或已授权设备 |

## 6. 单任务下发

### 6.1 `POST /open/task/dispatch`

请求字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `appId` | integer | 是 | 目标设备应用 ID，必须已授权 |
| `taskNo` | string | 是 | 调用方任务号；同一设备内的幂等键 |
| `taskType` | string | 是 | 设备任务类型；标准堆垛机使用字符串数字 `1`～`6` |
| `takeRow` | integer | 条件 | 取货排，堆垛机/码垛机使用 |
| `takeColumn` | integer | 条件 | 取货列 |
| `takeFloor` | integer | 条件 | 取货层 |
| `putRow` | integer | 条件 | 放货排 |
| `putColumn` | integer | 条件 | 放货列 |
| `putFloor` | integer | 条件 | 放货层 |
| `portNum` | integer | 条件 | 取放货口 |
| `priority` | integer | 否 | 优先级，默认 `0`；数值越大越优先 |
| `wmsRef` | string | 否 | WMS/ERP 业务单据引用 |
| `payload` | object | 条件 | 非堆垛机设备的专用参数 |

虽然当前服务层只显式检查 `appId`，数据库和设备执行链要求 `taskNo`、`taskType` 有效。生产调用应把三者都视为必填。

标准堆垛机种子协议的 `taskType`：

| 值 | 语义 |
|---:|---|
| `"0"` | 无任务 |
| `"1"` | 入库 |
| `"2"` | 出库 |
| `"3"` | 移库 |
| `"4"` | 移动 |
| `"5"` | 急停 |
| `"6"` | 解警 |
| `"7"` | 充电，当前仅预留 |

标准堆垛机种子协议的坐标范围为：排 `1～2`、列 `1～7`、层 `1～9`、货口 `1～2`。实际项目以设备对应协议点位配置为准。

请求示例：

```json
{
  "appId": 999001,
  "taskNo": "WMS-20260723-0001",
  "taskType": "1",
  "takeRow": 1,
  "takeColumn": 2,
  "takeFloor": 3,
  "putRow": 2,
  "putColumn": 5,
  "putFloor": 6,
  "portNum": 1,
  "priority": 10,
  "wmsRef": "IN-20260723-0001",
  "payload": {
    "containerCode": "LPN-000001"
  }
}
```

其他内置设备的 `payload`：

| `device_type` | 必填参数 | 示例 |
|---|---|---|
| `conveyor` | `toStation` | `{"toStation":7,"fromStation":1}` |
| `lift` | `targetFloor` | `{"targetFloor":3}` |
| `auto_door` | `action`，仅 `open`/`close` | `{"action":"open"}` |

`auto_door.action` 当前实现没有默认值，缺失或非法会使任务失败。

成功响应示例：

```json
{
  "isSuccess": true,
  "code": 200,
  "errorMessage": "",
  "data": {
    "id": 1234,
    "task_no": "WMS-20260723-0001",
    "app_id": 999001,
    "wms_ref": "IN-20260723-0001",
    "task_type": "1",
    "priority": 10,
    "status": "pending",
    "handshake_step": "CREATED",
    "error_code": 0,
    "payload": {
      "containerCode": "LPN-000001"
    },
    "chain_id": null,
    "seq_no": null,
    "retry_count": 0,
    "version": 0,
    "create_time": "2026-07-23T16:40:00",
    "last_update_time": "2026-07-23T16:40:00"
  }
}
```

请求字段使用 camelCase；实体响应字段当前使用 snake_case。

### 6.2 幂等规则

相同 `appId + taskNo` 重复下发独立任务时，WCS 返回第一次创建的任务，不会重复建任务。

调用方必须保证：

- 重试时复用原 `taskNo`；
- 不得用同一个 `taskNo` 发送不同坐标、类型或 payload；
- 超时后应先按同一个 `taskNo` 重试或查询，不能立即生成新任务号。

## 7. 任务链下发

### 7.1 `POST /open/chain/dispatch`

任务链用于多个设备按段串行执行。上一段完成后，WCS 才会物化下一段任务。

链级字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `chainNo` | string | 是 | 幂等键，最大 100 字符 |
| `priority` | integer | 否 | 默认 `0` |
| `selectStrategy` | string | 否 | `idle_first` 或 `round_robin`，默认 `idle_first` |
| `wmsRef` | string | 否 | 上位业务引用 |
| `payload` | object | 否 | 链级透传信息 |
| `segments` | array | 是 | 至少一段 |

段字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `seq` | integer | 否 | 正整数且链内唯一；缺省按数组顺序从 1 编号 |
| `appId` | integer | 二选一 | 固定目标设备 |
| `candidateAppIds` | integer[] | 二选一 | 候选设备组；与 `appId` 不能同时出现 |
| `taskType` | string | 是 | 任务类型 |
| 坐标与 `portNum` | integer | 条件 | 与单任务一致 |
| `payload` | object | 条件 | 与单任务一致 |

选机策略：

- `idle_first`：只在连接为 `RUNNING` 且工作模式为 `normal` 的候选中选择；优先空闲设备，否则选未完成任务最少者；
- `round_robin`：按 `chainId + seq` 确定性轮询，不感知当前连接状态，选中设备未连接时任务会排队。

固定设备链示例：

```json
{
  "chainNo": "WMS-CHAIN-20260723-0001",
  "priority": 10,
  "selectStrategy": "idle_first",
  "wmsRef": "OUT-20260723-0008",
  "payload": {
    "containerCode": "LPN-000001"
  },
  "segments": [
    {
      "seq": 1,
      "appId": 999001,
      "taskType": "2",
      "takeRow": 1,
      "takeColumn": 2,
      "takeFloor": 3,
      "putRow": 1,
      "putColumn": 1,
      "putFloor": 1,
      "portNum": 1
    },
    {
      "seq": 2,
      "appId": 999002,
      "taskType": "move",
      "payload": {
        "toStation": 7
      }
    },
    {
      "seq": 3,
      "appId": 999003,
      "taskType": "move",
      "payload": {
        "targetFloor": 3
      }
    }
  ]
}
```

候选设备组示例：

```json
{
  "chainNo": "WMS-CHAIN-20260723-0002",
  "selectStrategy": "idle_first",
  "segments": [
    {
      "candidateAppIds": [999101, 999102],
      "taskType": "1",
      "takeRow": 1,
      "takeColumn": 1,
      "takeFloor": 1,
      "putRow": 2,
      "putColumn": 2,
      "putFloor": 2,
      "portNum": 1
    }
  ]
}
```

WCS 会校验每段固定设备以及候选组中的每一个设备是否已授权；任一设备未授权，整链拒绝。

### 7.2 链幂等与状态

同租户相同 `chainNo` 重复下发时返回已有链。调用方不得用同一 `chainNo` 修改段定义。

链状态：

```text
pending -> running -> completed
                   \-> failed
pending/running ----> cancelled
```

链响应的主要字段：

```json
{
  "id": 88,
  "chain_no": "WMS-CHAIN-20260723-0001",
  "upstream_app_id": 7,
  "status": "pending",
  "segments": [],
  "select_strategy": "idle_first",
  "priority": 10,
  "payload": {},
  "wms_ref": "OUT-20260723-0008",
  "error_message": "",
  "finish_time": null,
  "version": 0,
  "last_update_time": "2026-07-23T16:45:00"
}
```

## 8. 增量对账接口

### 8.1 `POST /open/task/query`

```json
{
  "appId": 999001,
  "sinceMillis": 0,
  "limit": 200
}
```

### 8.2 `POST /open/alarm/query`

```json
{
  "appId": 999001,
  "sinceMillis": 0,
  "limit": 200
}
```

### 8.3 `POST /open/chain/query`

```json
{
  "sinceMillis": 0,
  "limit": 200
}
```

公共查询字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `appId` | integer/null | 任务、报警查询可选；指定时必须是自身或已授权设备 |
| `sinceMillis` | integer | Unix 毫秒水位线；`0` 表示从最早记录开始 |
| `limit` | integer | `<=0` 时默认 200，最大 2000 |

任务或报警查询不传 `appId` 时，可见范围收敛为“当前调用方应用自身 + 已授权下位应用”，不会返回全库数据。

链查询只返回当前 AppKey 下发的链。

响应结构：

```json
{
  "isSuccess": true,
  "code": 200,
  "errorMessage": "",
  "data": {
    "serverTimeMillis": 1784793600123,
    "count": 2,
    "rows": []
  }
}
```

查询条件为：

```text
last_update_time >= sinceMillis
ORDER BY last_update_time ASC, id ASC
LIMIT clamp(limit, 1..2000)
```

### 8.4 水位线算法

正确做法：

1. 按响应顺序处理 `rows`；
2. 用 `(resourceType, id, last_update_time)` 做幂等去重；
3. 下次 `sinceMillis` 取本批最后一行 `last_update_time` 对应的 Unix 毫秒；
4. 查询使用 `>=`，边界行会再次出现，调用方按 ID 和更新时间去重；
5. 当 `count == limit` 时立即继续拉下一页。

不能把响应的 `serverTimeMillis` 直接作为下一次水位线。结果被 `limit` 截断时，这样会永久跳过尚未返回的记录。

实体 `last_update_time` 当前是无时区偏移的 ISO 本地时间，例如 `2026-07-23T16:45:00.123456`。转换为 Unix 毫秒时必须按 WCS 主机时区解释；生产部署应统一并书面约定 WCS 与调用方时区。

## 9. 断线快照

### 9.1 `POST /open/snapshot`

请求：

```json
{
  "appId": 999001
}
```

`appId` 必填，并且必须是调用方自身或已授权设备。

响应：

```json
{
  "isSuccess": true,
  "code": 200,
  "errorMessage": "",
  "data": {
    "serverTimeMillis": 1784793600123,
    "connection": {
      "appId": 999001,
      "appCode": "PALLET_SIM",
      "appName": "码垛机 OPC UA 仿真",
      "deviceType": "palletizer",
      "protocolType": "opcua",
      "state": "RUNNING",
      "lastHeartbeatAt": 1784793599900,
      "retryCount": 0,
      "latest": {}
    },
    "alarms": [],
    "tasks": []
  }
}
```

说明：

- 设备未打开连接时 `connection` 为 `null`；
- `alarms` 为该设备当前活动报警；
- `tasks` 当前最多返回 50 条任务，并非完整历史；
- 快照后仍应继续调用 `/open/task/query` 和 `/open/alarm/query` 完成增量对账。

推荐恢复流程：

```text
检测到 WS 断号或网络重连
  -> 调 /open/snapshot 恢复当前连接态、活动报警和部分任务
  -> 从持久化水位线继续调 task/query、alarm/query、chain/query
  -> 按 id + last_update_time 去重
```

## 10. 状态与主要响应字段

### 10.1 任务状态

| 状态 | 说明 |
|---|---|
| `pending` | 待调度 |
| `waiting` | 链段等待激活 |
| `dispatched` | 已下发 |
| `executing` | 设备执行中 |
| `completed` | 完成 |
| `failed` | 失败 |
| `cancelled` | 已取消 |

任务结果常用字段：

| 字段 | 说明 |
|---|---|
| `id` | WCS 内部任务 ID |
| `task_no` | 调用方任务号 |
| `app_id` | 目标设备应用 ID |
| `wms_ref` | 调用方业务引用 |
| `status` | 任务状态 |
| `handshake_step` | 设备处理器内部步骤 |
| `error_code` | 设备故障码；`0` 表示未记录设备故障 |
| `dispatch_time` | 实际发往设备的时间 |
| `finish_time` | 完成、失败或取消时间 |
| `chain_id` / `seq_no` | 所属任务链和段号 |
| `retry_count` / `max_retry` | 重试计数和上限 |
| `last_update_time` | 对账水位字段 |

### 10.2 报警状态

| 状态 | 说明 |
|---|---|
| `raised` | 已产生 |
| `ack` | 已确认 |
| `cleared` | 已清除 |

报警结果常用字段：`id`、`app_id`、`fault_code`、`level`、`message`、`suggestion`、`status`、`raised_time`、`ack_time`、`cleared_time`、`last_update_time`。

## 11. Webhook 主动回调

Webhook 用于让 WCS 主动通知任务、链和报警的关键终态，减少轮询延迟。Webhook 管理接口 `/webhook/**` 使用管理端 JWT，不使用 AppKey；通常由 WCS 管理员配置。

订阅示例：

```json
{
  "name": "wms-callback",
  "url": "https://wms.example.com/wcs/hook",
  "secret": "由接收方生成的高强度随机密钥",
  "event_types": [
    "task.completed",
    "task.failed",
    "task.cancelled",
    "chain.completed",
    "chain.failed",
    "chain.cancelled",
    "alarm.raised",
    "alarm.cleared"
  ],
  "scope_app_ids": [],
  "enabled": true,
  "remark": "WMS 生产回调"
}
```

注意：管理端实体字段使用 snake_case，即 `event_types`、`scope_app_ids`。

### 11.1 回调请求

```http
POST <订阅 URL>
Content-Type: application/json
X-Webhook-Timestamp: 1784793600123
X-Webhook-Nonce: 91d25d8b0cb398d05d9aaad5
X-Webhook-Sign: <lowercase hex hmac-sha256>
```

Webhook 的时间戳单位是毫秒；AppKey 请求的 `X-Timestamp` 单位是秒，两者不要混用。

验签算法：

```text
bodyHash = lowercase_hex(SHA256(rawRequestBodyBytes))

canonical = timestampMillis
          + "\n" + nonce
          + "\n" + bodyHash

sign = lowercase_hex(HMAC_SHA256(webhookSecret, UTF8(canonical)))
```

接收端必须：

1. 用原始请求体字节计算 hash；
2. 常量时间比较签名；
3. 校验时间戳容忍窗口；
4. 对 nonce 或事件幂等键做防重放；
5. 业务成功后返回任意 2xx；
6. 处理失败时返回非 2xx，让 WCS 重试。

### 11.2 回调事件

任务事件：

```json
{
  "event": "task.completed",
  "bizId": 1234,
  "timestamp": 1784793600123,
  "taskNo": "WMS-20260723-0001",
  "appId": 999001,
  "status": "completed",
  "errorCode": 0,
  "chainId": null,
  "wmsRef": "IN-20260723-0001"
}
```

任务链事件：

```json
{
  "event": "chain.completed",
  "bizId": 88,
  "timestamp": 1784793600123,
  "chainNo": "WMS-CHAIN-20260723-0001",
  "upstreamAppId": 7,
  "status": "completed"
}
```

报警事件：

```json
{
  "event": "alarm.raised",
  "bizId": 901,
  "timestamp": 1784793600123,
  "appId": 999001,
  "faultCode": 8,
  "level": "error",
  "message": "设备故障",
  "status": "raised"
}
```

### 11.3 事件幂等、作用域与重试

- Webhook 为至少一次投递，接收方用 `event + bizId` 做幂等；
- 任意 2xx 表示成功，响应 body 当前不参与判断；
- 非 2xx 或连接异常触发指数退避；
- 默认最多投递 6 次，超过后状态为 `failed`；
- `scope_app_ids` 为空表示全部；
- task/alarm 的作用域按设备 `appId` 匹配；
- chain 的作用域按 `upstreamAppId` 匹配，不是按链段设备匹配；
- 新订阅从创建时刻开始，不回放更早历史；
- 事件源是可变状态表，极短时间内发生 `alarm.raised -> alarm.cleared` 时可能只回调最终 `cleared`，关键业务仍须保留 REST 对账。

生产环境应为 Webhook 配置 HTTPS、目标 host allowlist，并视网络环境启用私网/环回地址封禁。

## 12. 错误码

| `code` | 名称 | 说明 | 调用方处理 |
|---:|---|---|---|
| 200 | SUCCESS | 成功 | 正常处理 `data` |
| 400 | BAD_REQUEST | JSON、字段或请求体大小不合法 | 修正请求，不盲目重试 |
| 4011 | APPKEY_INVALID | 鉴权头缺失、AppKey 无效或应用停用 | 检查凭据和应用状态 |
| 4012 | APPKEY_SIGNATURE_INVALID | 签名错误或签名版本过低 | 核对原始 body、路径、密钥和版本 |
| 4013 | APPKEY_REPLAY | 时间戳过期、非法或 nonce 重放 | 校时并生成新 nonce 后重试 |
| 403 | FORBIDDEN | 目标设备未授权 | 申请绑定授权 |
| 404 | NOT_FOUND | 资源不存在 | 检查 ID |
| 409 | CONFLICT | 资源状态冲突 | 查询最新状态后决定 |
| 4241 | TASK_INVALID | 任务或任务链参数非法 | 修正任务内容 |
| 4242 | TASK_DUPLICATED | 任务号冲突 | 查询已有任务 |
| 4243 | TASK_STATE_ILLEGAL | 当前任务状态不允许操作 | 先查询最新状态 |
| 500 | INTERNAL_ERROR | WCS 未处理异常 | 保留 request ID/任务号并告警，有限重试 |

鉴权失败也返回统一 `ApiResponse`，当前实际 AppKey 错误码为 `4011`、`4012`、`4013`。

## 13. 超时、重试与幂等建议

建议调用方采用：

| 场景 | 建议 |
|---|---|
| 连接超时/读超时 | 使用相同 `taskNo` 或 `chainNo` 重试 |
| HTTP 200 且 `isSuccess=false` | 按业务码处理，不统一重试 |
| 4013 时间戳/nonce 问题 | 校时，生成新 timestamp/nonce/sign 后重试 |
| 500 | 指数退避有限重试，保持原幂等键 |
| Webhook 重复 | `event + bizId` 幂等 |
| 对账边界重复 | `id + last_update_time` 幂等 |

推荐 HTTP 客户端参数：

- 连接超时：3～5 秒；
- 单次请求总超时：15～30 秒；
- 自动重试：最多 3 次，指数退避并加随机抖动；
- 只对网络错误、超时、明确可重试的 5xx 做自动重试；
- 下发重试始终复用原 `taskNo`/`chainNo`。

## 14. 联调顺序

1. WCS 管理员创建上位应用并交付 AppKey/Secret；
2. 管理员授权目标仿真设备或真实设备；
3. 双方校时；
4. 使用 v2 签名调用 `/open/task/dispatch`；
5. 用相同 `taskNo` 重发一次，确认返回相同 `data.id`；
6. 调 `/open/task/query`，确认状态从 `pending` 推进到终态；
7. 调 `/open/snapshot`，验证断线恢复数据；
8. 注册 Webhook，验证签名、重复投递和非 2xx 重试；
9. 验证未授权设备返回 403；
10. 验证旧时间戳、重复 nonce、篡改 body 分别返回 4013、4013、4012。

仓库内 Postman 集合：`docs/CayleyWCS-WMS-Loop.postman_collection.json`。

## 15. 当前契约注意事项

- 请求 DTO 使用 camelCase，实体响应使用 snake_case；
- 业务错误通常使用 HTTP 200 包装，必须检查 `isSuccess`；
- `last_update_time` 没有时区偏移，双方必须约定 WCS 主机时区；
- `/open/snapshot` 的任务列表当前上限为 50，不能替代增量查询；
- Webhook 事件来自状态表扫描，不是完整 append-only 事件日志；
- `taskNo`/`chainNo` 幂等命中后直接返回已有对象，不比较新旧请求体；
- v2 签名必须对“最终发送的原始 JSON 字节”计算 SHA-256。
