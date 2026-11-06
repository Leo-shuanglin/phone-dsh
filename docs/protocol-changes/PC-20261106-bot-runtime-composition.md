<!-- SPDX-License-Identifier: MIT -->

# phoneDSH Bot 生产组合根与设备执行接线

```yaml
change: PC-20261106-bot-runtime-composition
contracts: [P07, P09, P16, P18, P23, P24, P25]
status: frozen
base: 293f191574c466fa670451235bb2a0c97471e7c1
contract_revision: PC-20261106-bot-device-event-envelope@1 + PC-20261106-bot-runtime-composition@1
version_decision: 复用既有 wire、Worker env、BotEvent v1、BotRows/UserAction；同 App bundle 配对升级，不新增公共协议版本。
```

Protocol impact: P07; P09; P16; P18; P23; P24; P25。

## 1. 单一组合根

- `BotRuntimeComposition` 在 `ChatModel` 的既有 Host 创建前启动唯一 `BotMCPHTTPServer`，把现有 `PDSH_PHONE_MCP_URL` 和 `PDSH_PHONE_MCP_TOKEN` 写入 Worker environment。没有新增 env、wire 字段或依赖。
- Host 通过既有 `MCPEgressPolicy` 仅允许实际 loopback origin 的 `/mcp`、`/identity/register`、`/identity/cancel`、`/approval`。Worker Authorization 由宿主移除后，宿主按内存中的精确路由注入 Bot MCP token；不写入用户设置或日志，不放宽其他出站地址。
- 同一 App 只持有一个 Host/Worker、一个 SQLiteDomainStore、一个主 Bot session、一个 Task executor、一个 routine scheduler 和一个共享 effect runner。DSH 工作台仍走既有多 session 路径。
- volatile provider 后绑定解决 Memory/Routine 依赖环；`BotDSHRemoteRuntime` 创建后接管 Task executor，不再以 Unimplemented executor 作为生产默认。主 session 创建/恢复后才连接 `BotProductionEnvironment`。
- `attachTransport` 在 Host/Worker 恢复时终止旧回合、清理旧队列和身份代次；保留主对话 ID，旧 Worker 的输出不能进入新代次。
- 未配置模型、Worker 启动失败、writer 不存在均明确报错，不回退到 Swift 主循环或虚假 planner。

## 2. 动作与卡片

- 用户 chat、卡片选择、关闭、审批和停止任务均先走真实 remote runtime，再由 SQLite nonce 事务提交接受事件；失败不消费 nonce，重复 nonce 不重放。
- question/steps 仅接受 `cardID + optionID`，选项必须在原卡片中。自由文本只走普通 chat；仅 question 可本地关闭，steps 没有关闭按钮。保留一个非 danger recommended。
- `mcp__phone__bluetooth.scan` 和 `mcp__phone__network.browse` 使用既有全局 PhoneCapabilityBridge；效果分类为 `read + ask`，不能绕过 Grant 或全局 PhoneCapabilityGate。原始扫描结果和 mDNS 名称不成为配对身份。

## 3. 效果与恢复

- routine 结果以稳定 delivery key 进入共享 SQLite outbox，生成 automation 行后才 ack；失败保留重试/对账，不能把接受当成已执行。
- 设备回复以原 `dev:<msg_id>` 作为 effect key，writer 使用同一 lease。未知结果进入 `needs_reconciliation`，不换 ID 盲发。下行 seq 由连接器分配，不能复制上行 seq。
- 设备 ingress 使用 P07 envelope，持久接收先于唤醒；同 msg_id 去重、冲突拒绝，每设备按 seq 连续释放。音频未知字段 fail-closed，长度按 schema 字符数计算。
- 无真实连接器/envelope writer 时不注册设备写入能力。真实蓝牙/LAN 权限与断连恢复不由模拟器或静态检查推断。

## 4. 验收

- `swift test --filter PhoneDSHBotTests`：Bot 全量测试。
- `swift test --filter BotDeviceProviderTests`：真实 SQLite、重复/冲突、乱序、重开、stage 恢复、standing deny/once、共享身份和回复对账。
- `python3 spec/bot/v1/tools/validate_vectors.py`：真实 schema 与正反向 golden。
- `bash scripts/ci/check.sh --only phone-spec,phone-p0-conformance,phone-cross-tree`：契约与登记。
- `bash scripts/ci/check.sh --only phone-plugins,phone-image,phone-bundle-fresh,phone-no-dsh-patch,phone-compat,phone-license,phone-modules`：Worker/插件/模块/许可证。
- `bash scripts/ci/bot-e2e/run.sh --simulator --scenario production-composition`：真实 PhoneDSHHost、WKWebView Worker、ChannelDSHTransport 与脚本上游；覆盖 chat、错误 token 401、volatile、真实 Task session、routine outbox、Worker 重启与 nonce 去重。不是 scripted transport，不是实际模型或外部账号。

真机 WP1、真实模型、外部账号、系统通知实投和真实设备蓝牙/LAN 保持 NOT_RUN。PR 必须附实际结果，不把此前测试数量当成本轮通过。
