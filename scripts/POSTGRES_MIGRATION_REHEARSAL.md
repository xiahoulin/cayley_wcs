# PostgreSQL 升级演练

`postgres_migration_rehearsal.py` 仅读取当前 PostgreSQL：使用 `pg_dump` 将完整库备份到仓库外私有目录，再恢复到带明确仿真标签的新 PostgreSQL 容器。正式容器和正式数据卷不停止、不改写。

脚本提取已构建后端jar的 `BOOT-INF/classes` 到私有目录，编译独立 `SchemaMigrationRunner.java`，运行同一套真实Flyway SQL及Java迁移并再次validate。它不启动Spring业务Bean、设备恢复器、任务引擎或Webhook，因此不会因副本中的设备地址和旧任务而发送现场命令。

## 运行

先由负责构建的agent生成版本明确的后端jar及Maven依赖classpath文件。多个agent共享工程时，Maven编译、打包和测试须统一串行调度；本脚本不运行Maven，也不清理或改写后端target目录。

```text
python scripts/postgres_migration_rehearsal.py \
  --work-dir <仓库外私有目录> \
  --backend <WCS/backend绝对路径> \
  --java-dir <JDK目录或JDK/bin目录> \
  --classpath-file <依赖classpath私有文件> \
  --source-version 19 \
  --target-version 20 \
  --evidence <脱敏证据JSON路径>
```

默认源容器是当前WCS PostgreSQL，目标是独立命名演练容器，目标端口仅绑定127.0.0.1。目标已有时不会自动覆盖。源版本参数 `--source-version` 与目标版本参数 `--target-version` 明确指定升级范围，支持V16到V20、V19到V20、V20到V21、V23到V24、V24到V25；默认V19到V20。版本不符时停止，不回退正式库。`--resume-restored` 仅用于本脚本拥有的、已完成指定版本恢复且尚未迁移的副本；会校验容器用途标签和恢复快照，不能用于正式容器。

数据库完整dump、原业务记录快照、随机生成的副本凭据和运行日志只写私有目录，不能提交到仓库。公开证据仅包含数量、检查结果和校验和，不包含请求内容、Token、AppKey、密码或数据库连接凭据。

## 已验证项目

- V24 到 V25：当前库的各应用独占协议，仅新增所属应用及历史模板分类；逐项比较原应用、凭据、点位、发布快照、任务、链、授权、计数和业务时间。参数为 `--source-version 24 --target-version 25`。该严格检查要求原有独占关系无须拆分；共享协议拆分另由 `ApplicationOwnedProtocolMigrationTest` 覆盖，不将变化默认为通过。

- V19源库恢复后真实迁移到V20：删除点位rw列、发布快照中的rw属性以及废弃字典，其他点位定义、版本编号、发布指纹、应用绑定、任务、任务链、原生任务号和业务时间保留。
- V20源库恢复后真实迁移到V21：删除点位分组字段、发布快照中的分组属性以及废弃分组字典，保留请求/响应方向、用途、原生地址、发布身份、绑定和业务记录。运行时使用 `--source-version 20 --target-version 21`。
- V23源库恢复后真实迁移到V24：将设备专用协议分类归并为TCP、将报文适配方式移入应用配置并新增看板偏好表。校验仅预期的分类/适配器/配置代次/协议行版本变化，点位、历史指纹、发布ID、凭据、连接参数、绑定、任务与链保持。运行时使用 `--source-version 23 --target-version 24`。
- 指定V16时，先验证旧任务、旧链段和设备协议转换，再迁移到V20。
- 任务、任务链、应用行号、业务时间和旧字段保持；原请求/原链段内容经脱敏后逐项匹配。
- 旧任务及链需要核对时保留held说明；已知HTTP身份重新归类且等待明确发布。
- V16升级时协议保持草稿，真实设备原生类型和地址不被推测修改；HTTP请求/响应字段集合与明确来源一致。读写由方向、用途和驱动流程处理。
- 原生任务号分配记录初始化，但不创建设备任务号租用或自动发布版本。
- 再次migrate/validate不改变数据或时间，源容器业务表在演练前后保持一致。实时轮询产生的连接日志和报文日志数量不作为业务表一致性判据。

早期V16到V19结果见 `docs/reviews/2026-10-13-postgres-migration-evidence.json`；V19到V20结果见 `docs/reviews/2026-10-13-rw-removal-migration-evidence.json`。迁移演练通过只证明升级和数据保留；正式Docker更新、业务运行、设备仿真和回退由部署负责人执行。

## 清理

演练完成后可以停止带 `com.cayleywcs.purpose=isolated-migration-rehearsal` 标签的目标容器，保留副本和私有dump供复核。停止或删除前必须确认名称与该标签，不能操作正式WMS/WCS PostgreSQL或其数据卷。
