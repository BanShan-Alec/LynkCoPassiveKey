# 08. v2.3：删除方向性轨迹规则（纯阈值 + 持续时长）与锁屏失效/解锁慢根因修复

本文档记录「银河钥匙雷达 (`com.luca.geelykeyradar`)」v2.3.0 的两项核心变更：

1. **删除「靠近解锁必须由远及近、远离上锁必须由近及远」的方向性轨迹规则**，自动解闭锁只保留 **有效 RSSI 阈值 + 持续时长**。

> **v2.4 更新**：解锁已改为 **单次阈值满足即触发**（不再要求 0.8s 连续驻留）；上锁仍保留 4.5s 连续驻留。另新增锁屏摇一摇、真实锁态一致性、数值输入与 UI 稳定化，详见 **[09_v2_4_shake_state_and_ui.md](09_v2_4_shake_state_and_ui.md)**。本文以下 v2.3 的「解锁 0.8s」描述仅为历史记录。
2. **修复「手机亮屏打开 App 就有效、锁屏则无效或解锁很慢」**：补齐锁屏 CPU 保活、每样本即时评估、息屏扫描心跳、Doze 白名单与首帧状态 bug。

---

## 一、为什么必须删除方向性轨迹规则

### 1. v2.2 的实际代码

`KeyRadarService.java`（v2.2）在解锁分支：

```java
boolean hasApproachTrajectory = simModeEnabled
        || approachOutsideSampleCount >= 1
        || currentLockState == LockState.LOCKED;
...
if (hasApproachTrajectory) {
    aidlBridge.sendFastControl(CTRL_UNLOCK, "智能阈值靠近自动解锁");
} else {
    // 静默同步为已解锁，不重复解锁
}
```

闭锁分支在 `evaluateDrivingAndGlitchSafety()` 中：

```java
if (!simModeEnabled && effectiveDbm <= lockThresholdDbm) {
    if ((now - lastStrongCabinSignalMs) < 2200L) { ...熔断 8s... }
    if (transitionZoneSampleCount < 2) {
        return "尚未检测到连续远离过渡轨迹 (防闪断验证中 " + transitionZoneSampleCount + "/2)";
    }
}
```

`transitionZoneSampleCount` 只有在 `-82 dBm < eff < -63 dBm` 时才会 `+1`，而 `eff >= -63` 或 `eff <= -82` 都会跳过/清零。

### 2. 实测现象与根因

实车 BLE RSSI 并不是平滑的 $1/r^2$ 曲线，而是叠加了：

| 跳变来源 | 典型幅度 | 后果 |
| :--- | :--- | :--- |
| 人体遮挡（背对车、手机揣兜） | `-12 ~ -18 dB`，0.5s 内 | 站在 3m 处也会瞬间读到 `-80 dBm` |
| 多径反射相消 | ±`6 ~ 12 dB` | 阈值附近来回穿越 |
| 广播信道 (37/38/39) ↔ 连接态数据信道 (0~36) 切换 | `4 ~ 8 dB` 基线跳升 | 5m 附近出现「阶梯」 |
| 车机慢广播 ↔ 快广播/连接态切换 | 样本率 1Hz ↔ 10Hz | 平滑值/斜率突变 |
| 手机厂商息屏扫描降频 | 样本率骤降 | 过渡带样本根本没采到 |

结果：

- **远离上锁**：从 `-60 dBm` 直接跳到 `-85 dBm` 时，`transitionZoneSampleCount` 仍是 `0`，`evaluateDrivingAndGlitchSafety()` 永远返回「尚未检测到连续远离过渡轨迹 (0/2)」，`belowLockStartMs` 被不断清零 → **永远不锁车**。这正是「轨迹必须越来越远」规则在实车上失败的主因。
- **靠近解锁**：虽然 `approachOutsideSampleCount` 通常能攒到 1，但冷启动/服务被 OEM 杀掉重启时，首帧若已在 `>= -63 dBm`，v2.2 会直接 `currentLockState = UNLOCKED` 且不发解锁命令 → **车还锁着，App 却以为已解锁**。
- **另一个更隐蔽的「假已解锁」**：`VehicleStatusSnapshot.isUnlocked()` 在 v2.2 写成 `vehicleLock == 0 || (vehicleLock == -1 && doorLockMask == 0)`。桌面小组件 `WgProviderMiddle` 只回传状态文字（充电中/已驻车）时没有锁位域，`vehicleLock` 默认 `-1`、`doorLockMask` 默认 `0`，于是被判定为「已解锁」；`onVehicleStatusParsed()` 随即把雷达状态同步成 `UNLOCKED`。**结果车明明锁着，雷达却显示「已解锁」并永久跳过靠近解锁命令**——这正是 v2.3 实车 UI 上「锁态未知 + 已解锁（车旁安全区）」同时出现的根因。v2.3 改为 `isUnlocked()` 只认显式 `vehicleLock == 0`，并新增 `hasLockState()` 门控。
- **同源的 RSSI 跳变熔断**：单帧骤降 > 22 dBm 熔断 6s、座舱强信号 2.2s 内跌破锁车线熔断 8s，在「疯狂跳变」环境下会频繁误触发，进一步推迟/吞掉合法上锁。

### 3. v2.3 新规则（唯一判定条件）

```text
有效信号 effectiveDbm = 平滑 RSSI + (揣兜遮挡 ? +8dB : 0)

解锁：effectiveDbm >= unlockThresholdDbm (-63 dBm) 连续 >= 0.8s   // v2.3；v2.4 已改为满足一次即触发
上锁：effectiveDbm <= lockThresholdDbm   (-82 dBm) 连续 >= 4.5s
```

- 删除：`approachOutsideSampleCount`、`transitionZoneSampleCount`、`lastStrongCabinSignalMs`、单帧 22dB 骤降熔断、2.2s 座舱跌落熔断。
- 保留的否决项只剩**硬件级**事件：车内 A2DP/HFP 音频、GPS 车速 ≥ 6.5 km/h、车辆状态「行驶中」、蓝牙适配器重置 15s、强信号 ACL 闪断 10s、无实时信号帧。
- 迟滞死区仍保留：`-82 ~ -63 dBm` 之间既不解锁也不上锁；从死区回到解锁区/闭锁区时重新开始计时。
- 冷启动/信号中断 >8s：只重置滤波器与驻留计时器，**不再**静默写入 `UNLOCKED`；阈值+驻留满足就正常下发。
- 车机 `0x0121` 状态帧同步真实锁态后，立即重跑一次状态机（`onVehicleStatusParsed()` → `scheduleImmediateEvaluation()`），避免「命令丢失但状态已同步」的假解锁。

---

## 二、锁屏失效 / 解锁慢的根因与修复

### 1. 根因链（按影响排序）

1. **CPU 休眠 / Doze 延迟 Handler**
   - `periodicTickRunnable` 是主线程 `postDelayed`，不是实时定时器。屏幕熄灭后 CPU 可 suspend，Doze IDLE 后非白名单 App 的 `PARTIAL_WAKE_LOCK` 也会被忽略。
   - 原 v2.2 **完全没有 WakeLock**（`grep WakeLock` 为 0），只靠前台服务通知，无法阻止 CPU 休眠。
2. **只在周期 tick 里做判定**
   - 无 UI 时 tick 间隔 2s（`highRateNeeded` 不含「屏幕状态」），叠加休眠后，0.8s 解锁窗口会被放大成数秒（v2.4 已改为单次阈值触发，进一步消除该延迟）。
3. **AOSP/OEM 息屏扫描降频/暂停**
   - Android Bluetooth `ScanManager` 在屏幕关闭时会降低强制扫描模式（AOSP 提交 `Allow the forced scan mode to be lower one during screen off`，单元测试里也有 `createScreenOnOffMessage(true)`）；vivo/Poco 等机型在息屏时甚至会暂停 callback scan。样本率下降直接拖慢阈值穿越与过渡带采样。
4. **服务被杀重启后首帧静默 UNLOCKED**
   - 见上文；锁屏久了 OEM 更容易杀服务，所以这个 bug 在锁屏场景下最明显。
5. **小组件状态帧伪「已解锁」**
   - `VehicleStatusSnapshot.isUnlocked()` 把 `vehicleLock == -1 && doorLockMask == 0`（锁态未知）当成已解锁；`WgProviderMiddle` 只回传状态文字时正好是这种默认值，导致 `onVehicleStatusParsed()` 无条件把 `currentLockState` 同步成 `UNLOCKED`。
6. **未申请电池优化白名单 / OEM 后台高耗电**
   - 依赖手工 adb 命令，换机、重装、系统更新后容易丢失；OriginOS 还有独立的「后台高耗电」与「自启动」开关。

### 2. v2.3 修复清单

| 修复 | 位置 | 说明 |
| :--- | :--- | :--- |
| `PARTIAL_WAKE_LOCK` | `KeyRadarService.initRadarWakeLock()` / `refreshRadarWakeLock()` | 仅「屏幕关闭 + 车辆 5 分钟内出现过」或「刚锁屏 45s 引导窗口」持有；亮屏或车辆远离 5 分钟后释放 |
| 每样本即时评估 | `onNewRssiMeasurement()` → `scheduleImmediateEvaluation()`（180ms 去抖）→ `evaluateRadarState()` | 不再等 450/2000ms tick；tick 只做兜底 |
| 息屏扫描心跳 | `runScreenOffScanWatchdog()` | 息屏、仍在扫描、银河未连接、45s 无车机广播 → 重建 `BluetoothLeScanner`，90s 节流 |
| Doze 白名单 | `MainActivity.maybeRequestBatteryOptimizationExemption()` | 首次启动请求 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；失败回退到电池设置页并 Toast 提示 OriginOS 后台高耗电 |
| 移除首帧静默 UNLOCKED | `onNewRssiMeasurement()` | 冷启动只重置滤波器/计时器 |
| 修复小组件伪解锁 | `GeelyAidlBridge.VehicleStatusSnapshot.isUnlocked()` / `hasLockState()`；`KeyRadarService.onVehicleStatusParsed()` | 只有显式锁位域才同步 `LOCKED/UNLOCKED`；锁态未知时保持原状态 |
| 状态同步后立即重跑 | `onVehicleStatusParsed()` | 车机回传真实锁态后重新评估，避免假解锁/漏锁 |
| logcat 镜像 | `appendLogRaw()` → `Log.i("GeelyKeyRadar", line)` | `adb logcat -s GeelyKeyRadar` 可验证锁屏行为 |

### 3. 锁屏 WakeLock 的持有策略

```text
need = !screenInteractive && (
         lastAdvPacketMs       within 5 min ||
         lastCarAclConnectedMs within 5 min ||
         lastSampleTimestamp   within 5 min ||
         now < bootstrapWakeLockUntilMs  // 锁屏后 45s 引导窗口
       )
```

这样既能覆盖「锁屏→走近→解锁」和「解锁→走远→锁车」两个关键窗口，又不会在车辆远离后整夜持有唤醒锁。注意：不把 `isEffectivelyConnectedToCar()` 放进持有条件，因为它可能仅凭银河通知栏「已连接」文字就返回 true，与真实距离无关，会导致锁屏保活长期不放。

---

## 三、验证方法

### 1. App 内日志

打开 App 查看日志控制台，应能看到：

- `🌙 屏幕熄灭 -> 锁屏雷达已进入 CPU 保活 + 扫描心跳守护`
- `🔒 锁屏保活：已持有 PARTIAL_WAKE_LOCK`
- `★ 触发【靠近自动解锁】(阈值+驻留判定): 有效信号 -62.0 dBm ... 连续驻留 0.8s`（v2.3 日志；v2.4 为「单次阈值满足」）
- `★ 触发【远离自动上锁】(阈值+驻留判定): 有效信号 -85.0 dBm ... 连续 4.5s`
- 长时间无广播时：`🔄 锁屏扫描心跳：45 秒无车机广播且银河未连接 ...`

### 2. adb logcat

```bash
ADB="adb"
"$ADB" logcat -c
"$ADB" logcat -s GeelyKeyRadar
```

### 3. 状态确认

```bash
"$ADB" shell dumpsys deviceidle whitelist | grep -E "geelykeyradar|geelygalaxy"
"$ADB" shell dumpsys power | grep -E "Wake Locks|GeelyKeyRadar"
```

`dumpsys power` 中出现 `GeelyKeyRadar:RadarScreenOffWakeLock` 表示锁屏保活生效；屏幕点亮后应消失。

---

## 四、已知边界与取舍

1. **AOSP/OEM 的息屏扫描降频无法从 App 层完全绕过**：App 能做的只有前台服务 + `SCAN_MODE_LOW_LATENCY` + WakeLock + 扫描心跳 + 电池白名单。真正靠近后，连接态 `Gatt.readRemoteRssi()` 不受扫描降频影响，因此「先扫到第一帧 → 注入 ScanResult 唤醒银河 → 用连接态 RSSI 判定」是锁屏下最可靠的路径。
2. **连续驻留仍是严格条件**：如果 RSSI 恰好在阈值附近高频抖动，计时器会被合法地重置。v2.3 先用卡尔曼+双速率 EMA 平滑；若实车仍觉得某档位难以触发，建议优先调整阈值（`推荐 -63/-82`、`宽容 -61/-86`、`紧凑 -65/-77`），而不是重新引入方向性规则。
3. **无实时信号帧时不会盲目锁车**：`signalFresh < 8s` 是硬条件，避免把「扫描暂停/蓝牙闪断」误判为「人走远」。
4. **强信号 ACL 闪断仍会熔断 10s**：这是硬件事件保护，不是轨迹规则；如果实车发现它造成明显延迟，可再单独讨论是否放宽。

---

## 五、变更文件

- `GeelyKeyRadarApp/src/com/luca/geelykeyradar/KeyRadarService.java`
  - 删除方向性轨迹状态（`approachOutsideSampleCount` / `transitionZoneSampleCount` / `lastStrongCabinSignalMs` / `prevRawDbm` / 单帧骤降熔断）
  - 新增 `evaluateRadarState()`、`refreshRadarWakeLock()`、`runScreenOffScanWatchdog()`、`scheduleImmediateEvaluation()`
  - 新增 `ACTION_SCREEN_ON/OFF/USER_PRESENT` 动态注册与 45s 引导 WakeLock
  - 移除冷启动首帧静默 `UNLOCKED`
- `GeelyKeyRadarApp/src/com/luca/geelykeyradar/MainActivity.java`
  - 新增 `maybeRequestBatteryOptimizationExemption()` 与 OriginOS 后台高耗电提示
  - 标题更新为 v2.3
- `GeelyKeyRadarApp/src/com/luca/geelykeyradar/GeelyAidlBridge.java`
  - `isUnlocked()` 只认显式 `vehicleLock == 0`；新增 `hasLockState()`，杜绝小组件状态帧伪「已解锁」
- `GeelyKeyRadarApp/AndroidManifest.xml`
  - `versionCode=4` / `versionName=2.3.0`（WakeLock 权限与前台服务类型此前已具备）
- `README.md` / `docs/04` / `docs/07`
  - 同步更新架构、状态机与锁屏说明
