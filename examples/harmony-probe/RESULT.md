# HarmonyOS 6 核心链路验证结果

2026-11-04 在本机官方手机模拟器上实测通过。

| 项目 | 实测结果 |
| --- | --- |
| 模拟器镜像 | 官方 HarmonyOS 6.0.0 / API 20 手机 ARM64 镜像 |
| 设备系统值 | `OpenHarmony-6.0.0.47`，API `20` |
| Web 引擎 | `ArkWeb/6.0.0.47`，`Chrome/132.0.0.0` |
| 测试应用 | `com.phonedsh.harmonyprobe`，正常 `hdc install` 安装未签名测试 HAP |
| WebAssembly | 实际执行 `add(19,23)`，返回 `42` |
| phoneDSH | 现有 Worker 包启动，收到 `pdsh-ready` |
| 有序通道 | 真实 WebSocket HELLO，测试宿主校验 proto、role 和 boot_id |
| Shell | 子 Worker 执行 printf 写入、cat 回读，退出码 `0` |
| jq WASI | 生产引擎执行 `6 * 7`，返回 `42`，退出码 `0` |
| 文件与写入记录 | 随机标记回读一致，Mac 宿主 WAL fsync 后确认 level 1 |
| DSH 页面启动 API | 通过同一源码版本的生产 WorkerTunnel 读取 `/__boot__`，HTTP `200`，含 `injections` |

实际 Worker 文件 SHA-256 为 `73782b1b79a558cd11b79cebf61fcbdf89273a2a337743ea33c46fa24e598e15`。DSH 源码和镜像提交均为 `7be5505b0be06009d74891c869063c9cf19a0df0`。

本次证明现有 phoneDSH 计算核心可以在原生鸿蒙 6 模拟器的 ArkWeb 内运行。页面资源和有序通道宿主由 Mac 经回环端口提供。没有验证鸿蒙原生持久化、掉电恢复、后台保活、JNI/FFI、模型推理或 nova 15 真机。

原始本机证据位于仓库 `.audit/harmony-probe/emulator-final/`。其中 `result.json` 保存任务输出，`host-wal.ndjson` 保存接收帧，`hilog.txt` 保存设备日志，`emulator.jpeg` 是实际模拟器截图。完整运行脚本的通过记录另存于 `.audit/harmony-probe/emulator-script.log`。
