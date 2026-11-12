# WCS HTTP 上位到 OPC UA 堆垛机对接

完整接口字段、返回模型、字典、错误码与实际调用实例见 [礁盘工业WCS开放API接口文档](礁盘工业WCS开放API接口文档.docx)。本页作为代码库快速入口，与2026-10-13的点位合同一致。

点位读写依据方向、用途和驱动流程：业务请求写入设备参数，业务响应读取设备反馈，内部握手由驱动读回、确认和清零。点位没有独立的物理访问权限配置；设备原生访问拒绝仍返回协议错误。应用绑定授权和业务点位校验保持生效。

应用固定选择已发布的协议版本。管理员发布完整HTTP上位模板和OPC UA堆垛机模板后，建立上位到下位应用的绑定；系统逐项核对请求和响应点位的字段、逻辑类型与约束。绑定未授权或版本不符时，任务不会进入设备。

所有开放调用使用POST和JSON。先用应用标识 app_id、应用公钥 app_key 和应用密钥 app_secret，将令牌获取方式 grant_type 设为应用凭据模式 client_credentials，调用 `/system/token` 获取访问令牌 access_token；随后发送统一封装：

```json
{
  "app_id": "应用ID",
  "app_key": "APP_KEY_EXAMPLE",
  "access_token": "ACCESS_TOKEN_EXAMPLE",
  "request_id": "接入应用请求编号",
  "data": {
    "appId": 3,
    "taskNo": "IN-20251013-001",
    "priority": 0,
    "wmsRef": "业务单据编号",
    "payload": {
      "cmd_TaskType": 1,
      "cmd_TakeCoor_Row": 0,
      "cmd_TakeCoor_Column": 0,
      "cmd_TakeCoor_Floor": 0,
      "cmd_PutCoor_Row": 1,
      "cmd_PutCoor_Column": 2,
      "cmd_PutCoor_Floor": 1,
      "cmd_PortNum": 1
    }
  }
}
```

该请求发送到 `/open/task/dispatch`。设备业务点位全部在请求点位对象 data.payload 中，8个业务点位均必填；旧平铺字段、未知点位或内部控制点被拒绝。任务类型1为入库、2为出库、3为移库、4为移动、5为急停、6为解除警报；按对应任务要求提供有效取货、放货坐标及货口，未使用坐标为0。内部任务号、启动、完成确认、心跳及故障复位由WCS驱动维护。

业务任务号 taskNo 由接入应用提供，可包含字母。WCS另行分配原生设备任务号，并保留至完成确认和清理成功。相同设备、调用应用、业务编号与请求内容的重试返回原任务；内容或来源不同返回409。任务完成时先保存任务采集结果 result，其中包含采集质量 quality、有效采集时间 capturedAt 和响应点位对象 payload，再清设备反馈；后续查询和回调引用该持久结果。

| 接口 | 用途 |
| --- | --- |
| `/system/token` | 获取应用令牌 |
| `/open/task/dispatch` | 下发单个任务 |
| `/open/task/query` | 按时间水位查询任务及持久结果 |
| `/open/alarm/query` | 查询报警变化 |
| `/open/snapshot` | 读取当前连接及响应点位 |
| `/open/chain/dispatch` | 顺序下发任务链，每段同样使用请求点位对象 payload |
| `/open/chain/query` | 查询调用应用的任务链 |

统一返回字段为接口结果码 errcode、接口结果说明 errmsg、调用追踪号 request_id、响应生成时间 timestamp 和业务返回对象 data；接口结果码 errcode 为0表示成功。已处理时间水位 sinceMillis 是自1970年起的毫秒数，查询按最后更新时间 last_update_time 和记录编号排序，并包含与水位相等的记录。完成本批处理后去重，再以最后一条已处理记录的业务更新时间推进水位。若同刻记录已达本次最大条数 limit，应提高该上限重新读取，不得跳过同刻记录，也不能直接用服务端查询时间 serverTimeMillis 推进水位。

快照点位经过响应类型、范围及数组长度验证，采集质量 quality 不合格时返回无效状态 bad，点位对象 payload 为空值 null。堆垛机故障数组为60个16位有符号整数 Int16。协议类型和编码错误码4223/4224/4225、任务参数4241、权限403及版本冲突409的完整说明见Word文档。当前驱动要求缩放系数 scale 为1，OPC UA的64位无符号整数 UInt64 在定义发布前明确拒绝。

[Postman调用集合](CayleyWCS-WMS-Loop.postman_collection.json) · [真实协议仿真](../scripts/PROTOCOL_SIMULATION.md) · [已审核实施计划](plans/2026-10-13-protocol-point-contract-plan.md)
