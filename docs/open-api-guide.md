# CayleyWCS 开放接口对接指南（第三方系统集成）

面向 **WMS / ERP / 其他厂商系统** 接入 CayleyWCS 的 `/open/**` 开放接口。集成 = 「注册一个上位应用（拿 AppKey）+ 按本文签名调用 + 可选订阅 Webhook」。

OpenAPI（仅开放接口）：`GET /v3/api-docs/open`；Swagger UI 选 **open** 分组。

## 1. 注册上位应用

管理端为对接方建一个 `direction=upstream` 的应用，得到 `app_key` / `app_secret`（`/application/create` → `/application/reset-secret` 取密钥）。再在「绑定授权」页授权它可指挥的下位设备（`/binding/grant`）——否则 dispatch 返回 FORBIDDEN。

## 2. 请求签名（AppKey）

所有 `/open/**` 请求带鉴权头：

| 头 | 说明 |
|---|---|
| `X-App-Key` | 应用 app_key |
| `X-Timestamp` | Unix 秒（与服务器偏差需 ≤ `signature-max-skew-seconds`，默认 300s） |
| `X-Nonce` | 一次性随机串（同 appKey+nonce 防重放，窗口内不可复用） |
| `X-Sign` | HMAC-SHA256 签名（见下） |
| `X-Sign-Version` | 签名版本，缺省 `1`；`2` 为 body 纳签 |

### v1 签名（默认）

```
canonical = appKey + "\n" + timestamp + "\n" + nonce + "\n" + method + "\n" + path
X-Sign    = hex( HMAC_SHA256(app_secret, canonical) )
```

`method` 大写（如 `POST`），`path` 为请求路径（如 `/open/task/dispatch`，不含 query）。

### v2 签名（body 纳签，推荐；`min_sign_version=2` 时强制）

v1 基础上追加 body 的 SHA-256，闭合「坐标/任务类型被中间人篡改」的边界：

```
bodyHash  = hex( SHA256(rawRequestBody) )
canonical = appKey + "\n" + timestamp + "\n" + nonce + "\n" + method + "\n" + path + "\n" + bodyHash
X-Sign    = hex( HMAC_SHA256(app_secret, canonical) )
X-Sign-Version: 2
```

> 应用 `min_sign_version=2` 时，v1 请求被拒（`签名版本过低`）。灰度：先双跑 v1/v2，再逐个应用切 2。

### 失败返回

鉴权失败返回 HTTP 200 + `ApiResponse`（`isSuccess=false`）：`APPKEY_INVALID`（头缺失/应用停用）、`APPKEY_REPLAY`（时间戳过期/重放）、`APPKEY_SIGNATURE_INVALID`（签名错/版本过低）、`FORBIDDEN`（未授权指挥该设备）。

## 3. 核心接口

所有接口 `POST` + `application/json`。

| 接口 | body | 说明 |
|---|---|---|
| `/open/task/dispatch` | `{appId, taskNo, taskType, takeRow.., putRow.., portNum, priority, wmsRef, payload}` | 下发单任务（同 appId+taskNo 幂等）。非堆垛机设备参数走 `payload`（如输送线 `{"toStation":7}`） |
| `/open/chain/dispatch` | `{chainNo, priority, selectStrategy, segments:[{seq, appId?\|candidateAppIds[], taskType, payload, 坐标}]}` | 下发任务链（一对多/多对多；chainNo 幂等，每段独立授权校验） |
| `/open/task/query` | `{appId?, sinceMillis, limit}` | 增量拉任务（授权范围内） |
| `/open/alarm/query` | `{appId?, sinceMillis, limit}` | 增量拉报警 |
| `/open/chain/query` | `{sinceMillis, limit}` | 增量拉本方下发的链 |
| `/open/snapshot` | `{appId}` | 断线重连后一把全量重同步 |

## 4. 对账算法（命令走 REST、遥测走 WS、关键事件 REST 对账）

- 稳态：读 WS `/ws/monitor`（每秒帧带单调 `seq`），`seq` 连续即可。
- 发现断号/重连：调 `/open/snapshot` 全量重同步，之后增量 `query`。
- **水位线取值**：下次 `sinceMillis` 传**已处理最后一行的 `last_update_time`**（不是响应的 `serverTimeMillis`）——当 `count == limit`（截断）时用 serverTimeMillis 会漏行。查询含等值重拉，**按 id 去重**兜底。

## 5. Webhook 主动回调（免轮询，推荐）

注册 Webhook（`/webhook/create`）后，任务/链/报警的关键事件由 WCS 主动 POST 到你的 URL：

```json
{ "name":"wms-callback", "url":"https://wms.example.com/wcs/hook", "secret":"<你的密钥>",
  "eventTypes":["task.completed","task.failed","chain.completed","chain.failed","alarm.raised"],
  "scopeAppIds":[] }
```

事件类型：`task.{completed,failed,cancelled}`、`chain.{completed,failed,cancelled}`、`alarm.{raised,cleared}`。`scopeAppIds` 为空=全部设备。

回调请求头与验签（与请求签名对称）：

```
X-Webhook-Timestamp, X-Webhook-Nonce
X-Webhook-Sign = hex( HMAC_SHA256(secret, timestamp + "\n" + nonce + "\n" + sha256Hex(body)) )
```

你的接收端：用注册时的 `secret` 复算 `X-Webhook-Sign` 比对；返回 2xx 即确认，否则 WCS 指数退避重投（默认最多 6 次），超限置 failed（`/webhook/deliveries` 可查）。回调**至少一次**语义——请按 `bizId`+`event` 幂等处理。

payload 示例（task.completed）：

```json
{ "event":"task.completed", "bizId":1234, "timestamp":1712345678901,
  "taskNo":"T-1001", "appId":5, "status":"completed", "errorCode":0, "chainId":null, "wmsRef":"WMS-REF-1" }
```

> 投递走**游标 outbox**（DB keyset 全精度水位线扫描 + 滞后窗口），非内存事件监听——WCS 崩溃/重启不丢事件，事务延迟提交也不漏。**订阅起点=注册时刻**：只投递 Webhook 创建后发生的事件，不回放历史（后补新 event_type 源会补投该源自注册以来的事件）。事件源为可变状态表，极快的 raised→cleared 可能只外发当前态（持久故障能捕获 raised）。

## 6. 错误码速查

`ApiResponse.code`：200 成功；400 参数错；403 越权（FORBIDDEN）；404 不存在；409 冲突；40101 AppKey 无效；40102 重放；40103 签名错。具体以 `/v3/api-docs` 与 `common/exception/ErrorCode` 为准。
