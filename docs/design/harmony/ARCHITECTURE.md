# 原生鸿蒙产品壳

用户目标是 Android 产品版 1:1 的原生鸿蒙壳。Android 基线固定为 bd1dea5，安装包 SHA-256 与截图保存在 .audit/harmony-product/reference.json。旧 checkout 的诊断壳不是本次基线。

比较了 ArkTS TCPSocketServer 自建协议栈和原生 POSIX 宿主两种结构。两份独立候选由 DeepSeek 生成，Codex 负责源码核对与最终设计；这不是多种模型生成的候选。选择原生宿主，复用 Rust pdsh-wire 和 pdsh-wal，C++ 只做 NAPI。OHOS Rust 标准库已安装，pdsh-wire 已成功为 aarch64-unknown-linux-ohos 编译。ArkTS WebSocketServer 在 API20 手机上不可用，不把较新的 API 当作可用能力。

1. Rust 独占运行时持久化目录和有序通道。HTTP 静态资源、本地 WS、WAL、恢复、overlay.tar.gz 都在设备内。禁止向 /dsh 之外映射路径。只承诺实际实现与验证过的 ACK 等级，掉电等级不宣称已测量。
2. C++ NAPI 传字符串和字节，使用线程安全回调把无凭据的 HTTP 代理请求交给 ArkTS。NAPI 不能从原生服务器线程访问 ArkUI。
3. ArkTS HostService 持有 UI 状态、会话列表、设置和密钥，使用系统网络模块发出模型请求。密钥存入 AssetStore，不进入 Worker、原生宿主、WAL 或日志。
4. 一个稳定的隐藏 ArkWeb 加载设备内资源并运行实际 Worker。页面桥复用生产 WorkerTunnel，实现 RPC 和 session/follow 流；原生界面不复制 DSH 服务端。
5. ArkUI 原生聊天、抽屉和页面只读取 ShellSnapshot，通过 ShellActions 提交动作。未知、加载失败和空列表分别表达，不预置演示会话或伪造成功。

共享 ArkTS 合约位于 entry/src/main/ets/model/Contracts.ets。任何变更先更新该合约，避免各自猜测方法。

原生模块导出名为 libpdshhost.so。

```ts
start(filesRoot: string, assetsRoot: string): Promise<string> // JSON {port,token,bootId,sessionEpoch,ackedMseq,recoveredBytes}
stop(): void
status(): string
setEventCallback(callback: (eventJson: string) => void): void
respond(requestId: number, kind: number, status: number, headersJson: string, body: ArrayBuffer): void
installBundle(zipFile: string, assetRoot: string): Promise<void>
```

HTTP 请求事件为 {type:'http',id,method,path,headers,bodyBase64}。respond kind 为 0=响应头、1=数据块、2=结束、3=错误。只处理本次 start 生成的请求，stop 后拒绝旧请求。

本地路由为 GET /app/index.html、GET /app/engine.mjs、GET /assets/*、GET /boot-config、GET /overlay.tar.gz、WS /channel、POST /v1/http。静态 app 文件位于 assetsRoot/app，Worker 包位于 assetsRoot/worker。ArkTS 在页面加载前配置 HttpOnly SameSite cookie，页面以同源 cookie 请求 boot-config，UI URL 不携带 token。boot-config 与 WS 校验 token。模型网关不允许任意目标，只有设置中已授权的 provider 地址可注入凭据。

boot-config 返回 imageURL、overlays、xl。xl 包含 channelURL、token、bootId、sessionEpoch、fence、env；env 只放非秘密占位符与路由事实。具体 provider 路由协议与 Android LoopbackModelProxy 对齐。

ArkTS 到页面调用 __PDSH_NATIVE_CALL__({id,method,args})，页面通过 PdshNative.postMessage 返回 {type:'reply',id,ok,value,error}。原始随访流为 {type:'event',streamId,value}，关闭为 {type:'stream-end',streamId,error}，就绪为 {type:'ready'}。特殊 method 为 $follow、$close、$writeFile、$readFile、$listFiles、$command、$diagnose；常规方法通过原始 DSH /api/<method> 的 client-request/server-response 信封调用。

实测范围与剩余差异集中记录在 [VERIFICATION.md](VERIFICATION.md)。队列、全部会话动作、自动化及各类错误仍须逐项验收。Android 的 Ubuntu/proot 与真实设备连接不能凭界面判等价，单独验证并记录平台限制。

设计复核为有条件接受。采纳独立复核的门禁：Rust/NAPI 实际装载和监听、frame/WAL golden、非法帧与截断恢复、AssetStore 真存取、NetworkKit 流式网络、冷启动与完整 UI 流程均需实际运行。源码侧会话文件只保存原生界面的标题、归属与展示投影，不是 Worker 会话日志的第二个写入者；Worker 会话事实来自随访流与持久化的 /dsh/home/sessions。$writeFile/$command 仅修改 Worker 内存 VFS，由生产 sink 发送有序帧，只有 Rust 写宿主 WAL/materialized。

respond 对同一请求严格按 head→chunk*→end 处理；重复 head/end、结束后 chunk 或上一启动代请求均拒绝。错误在 head 前变成有界 JSON 错误，在 head 后关闭连接并上报失败。请求体上限 4 MiB，流块上限 256 KiB，队列有界；断开、取消和截止时间解除等待。代理请求里原始 placeholder Authorization 会在 ArkTS 丢弃；真实密钥只在 NetworkKit 发出请求时注入，禁止写回 native.respond 的 headers。

NAPI 装载实测发现原生服务器会把首次带 token 的 URL 转成 HttpOnly SameSite cookie 后重定向。接纳该实现：页面仅以同源 cookie 请求 boot-config，获得通道 token 后放入 Worker init，地址栏不再携带 token。首次失败为页面错误要求 query token，已在页面接线层修复；不关闭服务器鉴权。

当前契约修订与验收状态见 [PC-20261104-harmony-host](../../protocol-changes/PC-20261104-harmony-host.md)。启动解压和 WAL 恢复移至 NAPI worker pool，主线程只完成 Promise 与 TSFN 回调。模型请求流迁移为 RemoteCommunicationKit，连接测试使用 NetworkKit 的同协议单次请求。完整 1:1 尚未验收。
