# 19. v3.9：云端/本地锁态时间戳仲裁 + 组件锁图标

本文记录 v3.9.0：

1. 桌面组件的锁图标（解锁=开锁，上锁=闭锁）；
2. 修复“银河云端旧解锁状态覆盖本地新车锁状态”的问题。

---

## 一、REA/字节码结论：银河云端锁态没有自带时间戳

用 dexdump/REA 检查 `dk_core.dex`：

```text
Lcom/geely/snc/libdigitalkeydataengine/databean/vehicledata/DkDataVehicleStatus;
  fields: door, lock, lock15, windows, ...
```

- `DkDataVehicleStatus` 只有 `lock` / `lock15` 等车辆字段；
- **没有** `timestamp` / `updateTime` / `vehicleStatusUpdateTime` 这类“这条锁态何时产生”的字段；
- 云端刷新时间在 UI/状态层（`UpdateTimeAnimState.vehicleStatusUpdateTime`、小组件 `wg_tv_update_time`）单独存在，不等于锁态本身的产生时间。

结论：

> 银河的云端 `vehicleLock` 可能是一段时间前的旧值；它刷新到 App 时，数据本身没有携带“这个锁态是 1 点还是 3 点”的时间戳。  
> 因此 Geely 自己也无法可靠判断“云端 1 点解锁”是否比“本地 2 点直连上锁”更新。

---

## 二、我们原来的问题

`parseVehicleStatusPayload()` 解析云端 JSON 时：

```java
snap.timestampMs = System.currentTimeMillis(); // 收到时间，不是云端数据时间
snap.rawHex = "JSON(...)";
```

随后 `onVehicleStatusParsed()`：

- 只要 `snap.hasLockState()`，就可能把 `currentLockState` 改成 JSON 里的 `vehicleLock`；
- 没有区分“真实 0x0121 BLE 帧”和“云端 JSON”；
- 所以 3 点收到 1 点的旧解锁 JSON，会覆盖 2 点本地直连上锁。

---

## 三、v3.9 仲裁规则

### 1. 给快照加来源和时间

`VehicleStatusSnapshot` 新增：

```java
boolean isCloudSource;
long cloudTimestampMs;
```

- 原始 9 字节 `0x0121`：`isCloudSource=false`；
- JSON 云端：`isCloudSource=true`，并从 JSON 的 `updateTime/timestamp/statusUpdateTime/vehicleStatusUpdateTime` 尽力解析 `cloudTimestampMs`；
- WIDGET 状态：`isCloudSource=true`，没有锁时间。

### 2. 本地直连优先

新增 `lastRealBleLockEvidenceMs`（持久化 `last_real_ble_lock_ms`）：

```text
真实 0x0121 锁态帧 → lastRealBleLockEvidenceMs = now
```

云端锁态是否接受：

```text
没有本地直连证据          → 接受云端（唯一数据源）
云端有明确时间戳          → 仅接受 cloudTimestampMs > lastRealBleLockEvidenceMs
云端没有时间戳 + 有本地证据 → 拒绝云端，保持本地
```

> 关键：没有时间戳的云端数据无法证明“比本地新”，所以**本地直连永远优先**，避免地库出来后 1 点旧解锁覆盖 2 点上锁。

### 3. 被拒绝时的日志

```text
⚠ 忽略过期云端锁态：cloudTime=... / 无, localBleTime=...
```

### 4. 持久化

`lastRealBleLockEvidenceMs` 写入 SharedPreferences：

- App 重启后仍知道最近一次真实 BLE 锁态时间；
- 云端旧 JSON 不会因为“刚启动”而重新覆盖。

---

## 四、组件锁图标

- 布局新增 `widget_lock_icon`；
- 图标：
  - 上锁：`ic_widget_lock_closed.xml`（闭锁）
  - 解锁：`ic_widget_lock_open.xml`（开锁）
- 发布：

```text
KeyRadarService.publishWidgetLockState()
  → prefs widget_lock_locked = 1/0/-1
  → KeyWidgetProvider setImageViewResource()
```

- 1=闭锁，0=开锁，-1=未知（默认闭锁图标）。
- 解锁/上锁乐观切换时立即换图标；0x0121/云端仲裁后再次校正。

---

## 五、验证

- v3.9.0 已安装；
- 服务前台正常、无 crash；
- AppWidget `KeyWidgetProvider` 已注册；
- 锁态仲裁逻辑：
  - 本地真实帧更新 `lastRealBleLockEvidenceMs`；
  - 云端 JSON 无时间戳且本地有证据 → 忽略；
  - 云端有时间戳且更新 → 接受；
- 组件需手动重新添加/更新后观察图标。

---

## 六、边界

- 如果云端 JSON 没有可解析时间戳，且本地从未收到真实 0x0121，则只能接受云端（无对比基准）。
- 手机时钟与云端时钟偏差会影响时间戳比较；目前按毫秒 epoch 直接比较。
- 银河云端“刷新时间”不等于“锁态产生时间”；本方案只能尽力解析，核心仍是**本地直连优先**。
- 组件图标依赖 `currentLockState`；状态未知时显示闭锁图标，不伪造解锁。
