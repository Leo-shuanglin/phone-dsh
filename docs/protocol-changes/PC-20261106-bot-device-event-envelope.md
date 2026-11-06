<!-- SPDX-License-Identifier: MIT -->

# CCR：phoneDSH Bot 设备事件编码与执行点授权

```yaml
change: PC-20261106-bot-device-event-envelope
contracts: [P07, P23, P24]
status: frozen
base: 293f191574c466fa670451235bb2a0c97471e7c1
contract_revision: PC-20261106-bot-device-ingress@1 + PC-20261106-bot-device-event-envelope@1
version_decision: BotEvent v1 保持 schemaVersion=1；仅实现 PC-20261106-bot-device-ingress 已冻结的事件来源和 principal kind；旧事件可继续读取；同 App bundle 配对升级。
```

Protocol impact: P07; P23; P24。

## CONTRACT CHANGE REQUEST

- Contract: P23/P24 @ `2126266a38921b295f9405773aed215e3766fdbb`
- Clause: `PC-20261106-bot-device-ingress.md` §2；`event-envelope.schema.json`。
- Problem: 已冻结的 `device.event` 要求 `principal.kind=device` 和 `origin.source=device:<transport>:<device_id>`，但事件 schema 和真实 Swift codec 只接受原有枚举。`device.reply.staged` 也未登记，真实 append 在唤醒前失败。
- Proposal: 增加 `device.event`、`device.reply.staged`、`device` kind；local origin 新增严格匹配的 `device:(bluetooth|lan):<P07 device_id>` 形式，不接受任意来源字符串。附正向 device event 与反向未知 transport golden。
- Affects: Swift BotEvent producer/consumer、SQLite replay、Python schema validator。旧 reader 不支持新 kind，不混部；旧数据不需要迁移。

同时修复实现缺陷：`BotExecutionAuthorizer` 对所有 `policy=ask` 的效果执行 Grant 匹配，包括只读蓝牙/LAN 发现；`read+auto` 仍按只读自动策略执行。该修复不改变 schema 或 Grant 类型，standing deny 仍优先，once 仍原子消费，未归属仍拒绝。

## 验收

- `swift test --filter BotDeviceProviderTests`：真实 SQLite 接收/恢复、stage 后重开、授权和回复效果。
- `swift test --filter PhoneDSHBotTests`：全量 Bot 回归。
- `python3 spec/bot/v1/tools/validate_vectors.py`：正向/反向 schema golden。
- `bash scripts/ci/check.sh --only phone-spec,phone-p0-conformance,phone-cross-tree`：契约及登记。

真机蓝牙/LAN 和系统权限未实现/未运行，不能由这些检查推断通过。
