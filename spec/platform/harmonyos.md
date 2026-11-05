# HarmonyOS 平台状态

Status: partial。目标为原生 ArkUI 产品壳与 ArkWeb Worker，使用官方 API20 手机模拟器。没有 HarmonyOS 真机结果，模拟器不替代硬件、后台、功耗或掉电测试。

契约和兼容性由 [PC-20261104-harmony-host](../../docs/protocol-changes/PC-20261104-harmony-host.md) 登记。完整实现与 Android 1:1 尚未完成。

| 能力 | 当前证据或限制 |
| --- | --- |
| 设备内资源、HTTP/WS、WAL、Worker | 真实 NAPI/HAP 已运行；不依赖 Mac 测试服务器。安装与恢复使用异步 NAPI，实际启动和恢复已回归。 |
| 模型服务 | 用户授权复用已有凭据，AssetStore 保存。实际模型回复和冷启动上下文续接已观察；实际工具回合也已通过。 |
| Shell、Python、jq、文件 | native smoke 实测；产品工具写入后的文件已由产品预览页读回。停止后延迟写入未发生，后续真实对话成功。 |
| 手机能力 | 不能因为模拟器存在或 UI 路由存在而声称通知、相机、定位等已授权；已读取应用自身权限声明、授权和通知状态，未声明能力仍报告未知。 |
| 后台、handoff、Ubuntu/proot | 未验证/未实现，不复用 Android 的支持声明。 |
| 文本渲染 | 原生表格、代码、RaTeX 和图片已显示；代码高亮、行内公式和窄屏表格仍有差异。 |
| 页面与偏好 | 必须逐项验证实际数据、写入结果和深浅色/语言/字号，不以路由数量作为通过证据。 |

实测范围、失败检查与限制详见 [模拟器验收记录](../../docs/design/harmony/VERIFICATION.md)。
