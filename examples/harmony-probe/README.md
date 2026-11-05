# 用鸿蒙模拟器验证 phoneDSH Worker

这个诊断壳在原生 HarmonyOS ArkWeb 中运行仓库现有的 Worker 包。Mac 提供静态资源和有序通道测试宿主，`hdc rport` 把模拟器的 `127.0.0.1:18765` 接到 Mac。没有修改生产 Worker，也不需要模型密钥。

验收包含模块 Worker 启动、真实 HELLO、子 Worker 中的 Shell、文件写入和回读、生产 jq WASI 引擎计算、通过生产 WorkerTunnel 读取 DSH 的 `/__boot__`，以及 Mac 测试宿主收到并 fsync 的写入记录。结果用每次宿主启动生成的随机标记匹配。

这次验证不覆盖鸿蒙原生持久化、后台保活、JNI/FFI 移植或模型推理。桌面 Chrome 只校准探针，不能证明鸿蒙兼容。模拟器通过也不等于 nova 15 真机已经通过。

## 运行探针

1. 准备 Node.js 22.19 或更新版本，运行仓库 `plugins` 的依赖安装。准备 `examples/probe-app/Bundle/worker` 下的实际运行包。宿主使用运行包记录的 DSH 源码目录打包生产 `WorkerTunnel` 客户端；源码位置改变时，设置 `PDSH_DSH_CHECKOUT`。
2. 从仓库根目录启动宿主。每次正式模拟器运行前重启宿主，并使用新的证据目录。

```sh
node examples/harmony-probe/tools/serve.mjs --evidence .audit/harmony-probe/emulator
```

3. 安装官方 DevEco 工具和 HarmonyOS 手机模拟器镜像。Command Line Tools 用户设置 `DEVECO_CLI_CLT_PATH`。启动模拟器。本次官方 API 20 模拟器实测接受未签名测试 HAP，不需要 CLI 账号授权。
4. 指定工具路径并运行。多个设备连接时，先只保留目标模拟器。

```sh
DEVECO_CLI=/absolute/path/to/devecocli \
HDC_BIN=/absolute/path/to/hdc \
bash examples/harmony-probe/tools/run-emulator.sh
```

脚本检查 ArkTS、构建未签名 HAP，然后通过正常的 `hdc install` 安装并启动。本次没有关闭系统签名检查或修改模拟器系统。真机安装另需正式调试签名，不在本轮范围内。

`result.json` 记录结果、用户代理、Worker SHA-256、每一步输出和证据范围。`host-wal.ndjson` 记录实际 HELLO 和 VFS 帧。日志与签名材料不提交。

## 校准探针

启动宿主后运行下面的命令。它驱动桌面 Chrome 并保存页面截图及 JSON，结果始终标为 `desktop-control-only`。

```sh
node examples/harmony-probe/tools/control.mjs
```

原生工程来自华为官方 `@deveco/deveco-cli-common` 的应用模板，使用 ArkWeb，只请求联网权限。页面加载事件不会被当作 Worker 成功。
