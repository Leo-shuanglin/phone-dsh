# 協議變更單

把下列內容放進任務或 PR 即可；跨多個 PR 的變更才需要另存持久文件並互相連結。
無邊界行為變更的重構填 `Protocol impact: none` 和理由，不必填空表。

## 變更識別

- Change ID／contract IDs（P01…P13 或新增邊界）：
- Base commit／contract revision／implementation head：
- 協調者兼 contract owner／producer owner／consumer owner／integrator：
- 具體 trigger → 現況 → 期望：
- Owning spec/code、schema、shared fixture、所有受影響 caller/receiver：
- 狀態：proposed／contract reviewed／implemented／verified（分端列出）：

## 契約 delta

只列改動；未變語義引用 owning clause，不複製整份協議。

| 項目 | Before → After／來源 |
| --- | --- |
| fields／encoding／number domain／limits／default／null | |
| capability／version／unknown field／unknown method | |
| correlation／sequence scope／ordering／terminal outcome | |
| error／retryable conditions／deadline／cancel／late response | |
| idempotency／side effect unknown／ACK／durability／recovery | |
| origin／auth／scope／path／redaction／backpressure | |

## 相容性與發佈

| Producer → Consumer | 支援／拒絕及理由；具體測試 |
| --- | --- |
| old → new | |
| new → old | |
| new → new | |
| persisted old data → new／rollback | 不適用需註明原因 |

- 是否 breaking、根據哪個 owning version rule；版本／adapter 決定：
- bundle／image／native artifact 更新順序、rollback 限制與移除舊路徑條件：
- draft／target 條款中，本次實際實作與仍保留的差異：

## 協作與證據

- 共享 contract revision／fixture 交付位置；各 agent 獨占的檔案範圍：
- 如需改共享契約，由協調者更新此單並通知受影響端；不可私下分叉。
- 正常、無效輸入、未知值、數值邊界及本次涉及的取消／重連／恢復案例：
- 真實 producer／consumer 測試（區分 schema checker、unit、integration、device）：
- Exact commands → PASS／FAIL／SKIP／INCOMPLETE／NOT_RUN，環境及證據位置：
- 剩餘風險、未支援平台、後續 owner／關閉條件：
