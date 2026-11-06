# 09. v2.4：锁屏摇一摇解闭锁、真实锁态一致性、数值输入与 UI 稳定化

本文档记录「银河钥匙雷达 (`com.luca.geelykeyradar`)」v2.4.x 的迭代（v2.4.1 独立开关；v2.4.2 锁屏摇一摇 1 秒震动反馈）：

1. **锁屏摇一摇切换上锁/解锁**（加速度计 + 锁屏 WakeLock）。
2. **真实锁态一致性**：乐观指令 12s 无回执回退、3 分钟无真实锁态帧不再显示「已解锁」、新增一键刷新。
3. **解锁改为单次阈值满足即触发**；上锁仍保留连续驻留时长，防止 RSSI 波动误锁。
4. **删除室内模拟信号发生器**；三个参数改为 `EditText` 数值输入；修复顶部状态/日志导致的页面高度抖动。

---

## 一、摇一摇解闭锁（锁屏可用）

### 1. 实现

`KeyRadarService` 新增独立 `SensorEventListener shakeListener`：

- 加速度计 `magnitude = sqrt(x² + y² + z²)`，取 `|magnitude - 9.81|`。
- 单峰阈值 `16 m/s²`，900ms 窗口内至少 **3 个峰**，峰间隔 ≥110ms，触发后 2.5s 冷却，避免走路/放桌面误触。
- 触发后调用 `handleShakeToggle()`：
  - 优先按 `currentLockState` 取反；
  - 若状态未知，则用最近一次真实 `0x0121` 锁位（`lastConfirmedVehicleLocked`）取反；
  - 若仍未知，则按距离兜底：靠近（`eff >= unlockThreshold`）→ 解锁，远离 → 上锁；
  - 仍受行车/车内音频/车速/车机「行驶中」硬联锁拦截；
  - 通过 `GeelyAidlBridge.sendFastControl()` 走与通知栏按钮相同的多通道下发，并播放对应提示音/振动。
- UI 新增开关「摇一摇手机切换上锁/解锁」，设置持久化到 `shake_toggle`。

### 2. 为什么锁屏也能用

本机 `lsm6dsve_acc` 是 **非 wake-up** 传感器，直接注册在息屏后不会工作。v2.4 的处理是：

```text
shouldMonitorShake = shakeToggleEnabled &&
                     (车辆 5 分钟内出现过 || 刚锁屏 45s 引导窗口)
```

只有在「车辆附近」时才注册 50Hz 加速度计；这时 `PARTIAL_WAKE_LOCK` 已经持有，CPU 不会休眠，非 wake-up 传感器可以持续回调。车辆远离后传感器自动注销，避免整夜耗电。

adb 验证（实测 V2426A）：

```bash
adb shell dumpsys sensorservice | grep -A5 geelykeyradar.KeyRadarService
# 锁屏后出现：
#   lsm6dsve_acc 0x00000001 ... samplingPeriod=20000us
#   (lsm6dsve_acc, com.luca.geelykeyradar.KeyRadarService)
```

### 3. 边界

- 没有 wake-up 加速度计的机型，锁屏摇一摇依赖雷达 WakeLock；若车辆 BLE 完全不可见（不在附近/车机休眠），不会注册传感器，摇一摇不生效。
- 摇一摇是用户主动动作，不受「靠近自动解锁/远离自动上锁」开关影响，但仍受行车安全硬联锁影响。
- 状态未知且无实时信号时直接忽略并振动提示，避免在车外盲发指令。

### 4. v2.4.2 震动反馈

摇一摇被识别时立即触发震动反馈，按屏幕状态区分：

- **锁屏/息屏**（`!isScreenInteractive()`，或屏幕亮着但 `KeyguardManager.isKeyguardLocked()` 为 true）：**连续震动 1 秒**（`VibrationEffect.createOneShot(1000ms, 255)`），口袋/包里也能明显感知。
- **亮屏且已解锁**：保留双短震（`0, 55ms, 70ms, 55ms`，幅度 255），避免亮屏操作时震动过长。
- 摇一摇路径内不再调用 `vibrateAlert(30/80/180)`，避免后续震动把 1 秒长震提前取消；解锁/上锁仍播放提示音。
- 被行车/车况未知拦截时不追加短震，1 秒长震本身就是“已识别”反馈，日志同时给出拦截原因。

实现：`handleShakeToggle()` 开头的 `vibrateShakeDetected()` → `isScreenLocked()`。

---

## 二、真实锁态一致性（解决「车已上锁，App 显示已解锁」）

### 1. 根因回顾

- 小组件 `WgProviderMiddle` 只回传「充电中/已驻车」文字时没有锁位域，v2.2 的 `isUnlocked()` 把 `vehicleLock == -1 && doorLockMask == 0` 当成已解锁；v2.3 已改为只认显式 `vehicleLock == 0` 并新增 `hasLockState()`。
- 另一个来源是**乐观状态**：App 发出解锁/上锁后立刻把 `currentLockState` 改成目标值；若车机没执行或没回执，这个状态会一直保留，和实车不一致。

### 2. v2.4 机制

| 机制 | 行为 |
| :--- | :--- |
| 真实帧证据 | 只有 `rawHex` 为 18 位十六进制（9 字节 `0x0121`）且 `hasLockState()` 的帧才更新 `lastLockStateEvidenceMs` / `lastConfirmedVehicleLocked` |
| 乐观指令 12s 超时 | 发出解闭锁后 12s 内没有真实锁态帧（或没有匹配帧）→ `currentLockState = UNKNOWN`，并主动 `requestVehicleStatusQuery()` |
| 3 分钟过期 | **仅对 `UNLOCKED`**：3 分钟没有真实锁态帧 → 回退 `UNKNOWN`。不再显示「已解锁」以吞掉后续靠近解锁；`LOCKED` 作为最后已知状态保留（靠近时仍会正常发解锁） |
| 匹配帧提前确认 | 3.5s 保护窗口内若收到与指令一致的真实帧，立即取消乐观计时（避免成功命令被 12s 超时误判） |
| 手动刷新 | 车辆状态卡新增「🔄 刷新真实锁态 & 车况 (0x0121)」，调用 `refreshVehicleStateNow()`；App 回到前台也会轻量请求一次 |
| 真实锁位解析 | 已用 `dexdump` 核对 `DkVehicleInfo15.setStatusData`：`vehicleLock = byte[7] bits[5..6]`，四门锁 `byte[1]` 每 2 bit；代码解析与官方 DEX 一致，不是位域错误 |

### 3. 效果

- 车已上锁但 App 没有真实帧时：最多 3 分钟从「已解锁」回退「未知/待命」，不再长期显示错误状态。
- 车机真实 `0x0121` 帧到达时：立即同步「已上锁/已解锁」，并重跑一次阈值状态机。
- 指令失败时：12s 后回退未知，后续靠近仍可重新触发解锁。

---

## 三、解锁单次触发，上锁保留驻留

v2.4 状态机：

```text
解锁：effectiveDbm >= unlockThresholdDbm  —— 满足一次即触发（无驻留）
上锁：effectiveDbm <= lockThresholdDbm 连续 >= lockDebounceMs（默认 4.5s）
```

- 删除 `unlockConfirmMs`（0.8s）与 `aboveUnlockStartMs`。
- 上锁保留驻留，防止 RSSI 抖动导致误锁。
- 解锁仍受 10s 冷却、行车硬联锁、状态去重（`currentLockState != UNLOCKED`）约束。
- 锁屏/息屏下仍由每条 RSSI 样本 180ms 去抖即时评估，不依赖 UI 刷新。

---

## 四、UI 调整

### 1. 删除室内模拟信号发生器

`MainActivity` 中移除 `swSimMode` / `sbSimRssi` / `tvSimValue` 及整张卡片。服务端 `setSimMode()` 保留为无 UI 入口，不再影响正常流程。

### 2. 三个参数改为数值输入

- 靠近解锁阈值（dBm）、远离上锁阈值（dBm）、上锁驻留时长（秒）改为 `EditText`（支持负号/小数），点击「✅ 应用以上数值」生效。
- 校验：解锁 `-95 ~ -35`、上锁 `-105 ~ -45`、驻留 `0.3 ~ 30s`；上锁阈值必须比解锁阈值至少低 5 dB。
- 预设按钮仍然保留，点击后自动填充并应用。

### 3. 页面高度稳定化

- 顶部四条状态文本（`tvAidlBadge` / `tvDeviceSource` / `tvAutoWakeStatus` / `tvSafetyBanner`）固定 2 行，超长省略，不再随每 450ms 文本长度变化。
- `tvVehicleStateSub` 固定 2 行，`tvLockState` / `tvVehicleParkState` 固定 1 行。
- 日志控制台固定 8 行，只显示最近 8 条；`onLogUpdated` 不再顺带刷新备份/车机参数面板。
- `tvBackupStatus` 固定 2 行，`tvVehicleRealtimeParams` 固定 7 行。

### 4. 独立开关（v2.4.1）

原先只有一个「启用手机侧自定义蓝牙临界值自动解锁/上锁」总开关；v2.4.1 拆成三个互不影响的开关：

| 开关 | SharedPreferences key | 控制 |
| :--- | :--- | :--- |
| 靠近车辆自动解锁 | `auto_unlock` | 只控制「有效 RSSI ≥ 解锁阈值」的自动解锁（含 3 米缓冲带反误锁重解） |
| 离开车辆自动上锁 | `auto_lock` | 只控制「有效 RSSI ≤ 上锁阈值 + 驻留时长」的自动上锁 |
| 摇一摇手机切换上锁/解锁 | `shake_toggle` | 只控制摇一摇动作，独立于上面两个自动开关 |

旧的 `smart_takeover` 总开关及其 UI 已删除；手动按钮与摇一摇不受这两个自动开关影响，仍受行车硬联锁拦截。

---

## 五、验证

- 实测设备：iQOO Neo10 Pro (`V2426A` / Android 16)，v2.4.2 安装无 crash，前台服务正常。
- UI 实测：标题 v2.4.2、三个独立开关（靠近自动解锁 / 远离自动上锁 / 摇一摇）、三个数值框（当前用户值 `-78 / -83 / 3.2`）、应用按钮、刷新真实锁态按钮均正常显示。
- 锁屏传感器实测：`dumpsys sensorservice` 在屏幕 `Asleep` 时出现 `lsm6dsve_acc` 50Hz 注册记录（`samplingPeriod=20000us`）。
- 摇一摇震动：锁屏/锁屏界面触发时连续震动 1 秒（幅度 255）；亮屏已解锁时双短震；摇一摇路径不再追加 30/80/180ms 震动，避免取消 1 秒长震。
- 日志控制台固定 8 行，顶部状态行不再导致页面高度抖动。

## 六、已知边界

1. 锁屏摇一摇依赖「车辆附近」或「刚锁屏 45s」窗口；车辆 BLE 完全不可见时不会注册传感器（省电取舍）。
2. `0x0121` 真实锁态帧依赖吉利 `:dkservice` 蓝牙连接；完全无连接时 App 只能显示「未知/待命」，不能凭空知道车锁状态。
3. 上锁驻留仍是严格连续计时；若实车在阈值附近高频抖动导致偶发不锁，可把驻留时长调大或把上锁阈值调低，而不是重新引入轨迹规则。
