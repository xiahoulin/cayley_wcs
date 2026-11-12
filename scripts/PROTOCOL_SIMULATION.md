# 独立协议网络仿真

这些脚手架只连接本机隔离端口。HTTP/TCP/Milo由Java测试自行启动，Modbus/S7/MQTT由以下服务提供；不连接现场设备，不复用正式数据库或认证信息。

## 准备和运行

1. 在仓库外建Python虚拟环境，以该环境的Python安装 `scripts/protocol-sim-requirements.txt`。
2. 启动MQTT：`docker compose -f scripts/compose.protocol-sim.yml up -d`。
3. 各运行一个独立进程（下列命令中的 `python` 指该虚拟环境）：

```text
python scripts/protocol_simulators.py --kind s7 --port 21102
python scripts/protocol_simulators.py --kind modbus --port 21502
python scripts/protocol_simulators.py --kind mqtt --port 28883
```

4. 进入 `backend`，设置 `WCS_EXTERNAL_SIMULATORS=true` 后运行：

```text
mvn -Dtest=ExternalPlcTransportTest,ExternalMqttTransportTest,PointMessageTransportTest,OpcUaNativeTransportTest test
```

PowerShell使用 `$env:WCS_EXTERNAL_SIMULATORS='true'`；Linux使用 `WCS_EXTERNAL_SIMULATORS=true mvn ...`。未显式启用时，依赖外部服务的两类测试会跳过，跳过不能作为网络测试通过证据。

## 可验证结果

| 协议 | 真网络验证 |
|---|---|
| HTTP | POST JSON payload、状态码、畸形JSON、关联号、响应长度及慢响应总超时 |
| TCP | 4字节网络字节序长度前缀JSON、拆包、读写、错误关联、长度上限、整帧超时与断线 |
| MQTT | 实际broker和独立仿真客户端、QoS1、关联读写、retained旧响应过滤及超时 |
| Modbus TCP | 实际保持寄存器和线圈、有符号16/32位、多寄存器、四种字节/字序组合与原始UINT寄存器对照、异常地址 |
| S7 | Snap7 DB100、原生INT、有符号边界与三元素数组、越界拒绝 |
| OPC UA | PLC4X→Milo，真实Int16/UInt16/Int32/Int64/Float/Double/Boolean/String/数组、节点类型和权限拒绝、堆垛机握手与60元素故障数组 |

适配器错误码：4222为协议收发或设备拒绝，4224为原生编码或值错误，4225为响应结构、关联、时效、类型或回显错误。点位无需维护独立读写权限，请求、响应和内部握手依方向、用途及驱动流程处理；设备原生访问拒绝仍按协议错误返回。编码失败发生在实际写入前，响应失败不会被视为设备操作成功。

OPC UA的UINT64暂不发布：真实全值域试验发现PLC4X 0.13.1写入 `18446744073709551615` 后Milo实际收到 `4294967295`。已在协议能力目录拒绝该类型、别名和数组，测试断言4224且设备值保持原样。HTTP协议的无损64位数据定义仍可维护。堆垛机任务号使用UInt16，不受此限制影响。

依赖锁定在Python清单和compose镜像digest；Java驱动使用仓库pom的PLC4X统一版本。PLC4X 0.12的S7多路复用输出存在空消息错误，已使用0.13.1修复版，适配器不再对已经自动连接的PlcConnection执行第二次connect。

## 清理

停止自己启动的三个Python进程，再运行 `docker compose -f scripts/compose.protocol-sim.yml stop`。保留Surefire报告及脱敏结果；不停止WMS/WCS正式容器。
