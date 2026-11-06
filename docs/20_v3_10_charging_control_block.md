# 20. v3.10：插充电线后禁止解闭锁

## 一、规则

- **默认开启**：手机插上充电线后，默认认为人在驾驶室，禁止解锁/上锁。
- 检测类型：**USB / AC 有线充电**；
  - 无线充电不触发（用户明确是“数据线/充电线”）；
  - 车载 USB-C PD 可能上报 AC，同样会触发。
- 设置开关：`🔌 [手机侧] 插充电线后禁止解闭锁`
  - Pref：`geely_key_radar_prefs / block_control_when_charging`
  - 默认 `true`
  - 可手动关闭。

## 二、拦截范围

| 路径 | 行为 |
| :--- | :--- |
| 手动「立即解锁/上锁」 | 按钮置灰，服务端再拦一次 |
| 摇一摇解锁/上锁 | 识别前直接忽略，不震动/不改锁态 |
| 电源键三连 | 硬门控拦截 |
| 快捷设置磁贴 | 硬门控拦截，状态显示“已插充电线” |
| 桌面组件解锁/上锁 | 硬门控拦截；刷新/设置不受影响 |
| 自动靠近解锁 / 远离上锁 | RSSI 状态机进入充电分支，不执行 |
| 通知栏按钮队列 | 插线后挂起的解锁/上锁不再补发 |
| 后备箱 / 哨兵 / 刷新 | **不受影响** |

## 三、实现

### 1. 充电检测

`KeyRadarService.refreshWiredCharging()`：

```text
registerReceiver(null, ACTION_BATTERY_CHANGED)
→ EXTRA_PLUGGED
→ BATTERY_PLUGGED_USB / BATTERY_PLUGGED_AC
```

- 同时动态监听 `ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED`；
- 服务启动时用 sticky broadcast 初始化，避免“已经插着线启动”漏判；
- 无线 `BATTERY_PLUGGED_WIRELESS` 明确排除。

### 2. 服务端硬门控

`evaluateChargingBlockReason()` 返回：

```text
手机已插充电线（默认在驾驶室），禁止解闭锁
```

生效位置：

- `handleExternalAction()`：磁贴/组件/电源键；
- `handleShakeToggle()`：摇一摇前置；
- `performManualControl()`：仅对 `CTRL_UNLOCK / CTRL_LOCK`；
- `flushPendingManual()`：取消挂起的解锁/上锁；
- `onFeatureDataResult()`：跳过车旁反误锁重解；
- `evaluateRadarState()`：自动解锁/上锁进入充电分支；
- `KeyNotificationBridgeService`：静态 ControlGate 拦住通知栏 `triggerUnlock/triggerLock` 及连接后补发队列。

### 3. UI

- 解锁/上锁按钮：
  - `🛑 插线禁解`
  - `🛑 插线禁锁`
  - `setEnabled(false)` + 半透明
- 车辆状态行：
  - `🔌 手机已插充电线（默认在驾驶室），禁止解闭锁`
  - 标题显示 `· 🛑禁控保护`
- 后备箱/哨兵仍按原行车安全门控，不受充电影响。

## 四、实机验证

手机通过 USB 连接电脑时：

```text
[14:07:04.121] 🔌 当前充电状态: 已插充电线 | 充电禁止解闭锁=true
```

UI：

```text
🔌 手机已插充电线（默认在驾驶室），禁止解闭锁
```

- 版本：3.10.1
- 前台服务正常、无 crash

> 注意：USB 连电脑调试也会被视为“插充电线”，此时解闭锁会被禁用；拔线或在设置里关闭开关即可恢复。

## 五、边界

- 家里有线充电也会触发（默认按“在驾驶室”处理）；可关闭开关；
- 无线充电不触发；
- 车机 USB-C PD 若系统上报 AC，会触发；若某些车机把有线充电上报为 WIRELESS（少见），则不会触发；
- 充电线插拔瞬间可能有一次状态刷新，属于正常。
