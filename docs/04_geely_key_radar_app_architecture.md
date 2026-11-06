# 04. 「银河钥匙雷达 (`com.luca.geelykeyradar`)」v3.20.0 架构与安全设计手册

> **注意**：本文描述的是功能繁多的 v3.20.x legacy 架构，源码已归档到 `GeelyKeyRadarApp/legacy/v3.20.6/`。当前主应用已裁剪为只保留“息屏靠近解锁 / 离开上锁”的 v4.0 极简通用版，通用识别与新建构见 **[36_minimal_generic_passive_key.md](36_minimal_generic_passive_key.md)**。

本文档详细说明「银河钥匙雷达」App 的核心算法、解锁单次触发/上锁驻留状态机、摇一摇解闭锁、真实锁态一致性、行车防误锁安全联锁、锁屏保活、纯手机侧/车机侧参数隔离、DK 1.5 车机参数实时读取与备份还原以及后台常驻架构。（最新逆向发现与 v2.2 新增特性详见 **[07_recent_reverse_findings_and_v2_2_features.md](07_recent_reverse_findings_and_v2_2_features.md)**；v2.3 方向性轨迹删除与锁屏根因详见 **[08_threshold_dwell_and_screen_off.md](08_threshold_dwell_and_screen_off.md)**；v2.4 摇一摇/锁态一致性/UI 详见 **[09_v2_4_shake_state_and_ui.md](09_v2_4_shake_state_and_ui.md)**；v2.5 双次摇一摇/提前连接/关门后上锁详见 **[10_v2_5_shake_tuning_and_early_connect.md](10_v2_5_shake_tuning_and_early_connect.md)**；v2.6 识别即震动/记录 UI 详见 **[11_v2_6_shake_feedback_and_recorder_ui.md](11_v2_6_shake_feedback_and_recorder_ui.md)**）

---

## 一、核心信号处理与四阶段距离状态机 (`KeyRadarService.java`)

### 1. 一维卡尔曼滤波 + 双速率 EMA + 口袋遮挡补偿
手机接收到的原始 2.4GHz BLE RSSI 波动高达 $\pm 10\text{ dB}$。为了获得稳定的物理距离映射，`KeyRadarService` 实施了三步滤波：
1. **1D Kalman Filter（一维卡尔曼滤波）**：
   - 过程噪声协方差 $Q = 0.08$，测量噪声协方差 $R = 2.20$。
   - 抑制单帧多径突变毛刺。
2. **非对称双速率 EMA（指数滑动平均）**：
   - **信号增强（走向车辆）**：快速跟随系数 $\alpha_{\text{up}} = 0.48$，保证靠近车门时解锁不迟钝。
   - **信号减弱（离开或身体遮挡）**：慢速衰减系数 $\alpha_{\text{down}} = 0.16$，防止站在车旁转身时信号瞬跌触发误锁。
3. **手机距离传感器（口袋/遮挡）动态补偿**：
   - 监听 `Sensor.TYPE_PROXIMITY` 与重力姿态。当检测到手机处于裤兜、背包或贴耳遮挡状态时，自动叠加 **`+8 dB`** 遮挡补偿（`effectiveDbm = filteredDbm + 8`），抵消人体吸收造成的虚弱信号。

### 2. 解锁单次触发 / 上锁驻留状态机（v2.3 已删除方向性轨迹规则，v2.4 取消解锁驻留）

> **v2.3 核心变更**：实车测试发现 BLE RSSI 会因多径、人体遮挡、广播/连接态信道切换产生 ±10~18 dB 的剧烈跳变。原 v2.2 的「必须由远及近」「必须经过 -66~-82 dBm 过渡带」等方向性轨迹规则在真实环境中长期无法满足（`transitionZoneSampleCount` 经常为 `0/2`），直接导致**远离上锁不触发**；服务重启首帧强信号时又会静默同步 `UNLOCKED`，导致**锁屏靠近解锁被吞掉**。v2.3 已彻底移除这些轨迹门控。
>
> **v2.4 变更**：解锁改为**单次阈值满足即触发**（不再要求 0.8s 驻留），上锁仍保留 4.5s 连续驻留，兼顾「解锁要快」和「上锁不能因 RSSI 抖动误锁」。

| 阶段 | 距离区间 | 默认触发条件（可调） | 执行动作 |
| :--- | :---: | :--- | :--- |
| **Stage 1：远距预唤醒（零槽位抢占）** | 约 `10 ~ 15m` | 扫到本车 `0x01FE` / `0xFDFD` / `GeelyVehicle123` 广播且 `RSSI >= -90 dBm` | **不主动发起 `connectGatt`**（防止占住车机唯一连接槽位导致车机停止广播），直接将扫到的 `ScanResult` 与 `setCarId (VIN)` 注入吉利 `MiuiService` / `MiuiReceiver` / `GLDkConnectionService`，让吉利银河直接发起 V2 蓝牙鉴权连接 |
| **Stage 2：近距自动解锁（覆盖 3.07s V2 鉴权窗口的多阶段重发）** | 约 `1.8m` 内 | **仅**：`effectiveDbm >= -63 dBm` **满足一次即触发**（无驻留） | 触发本地蓝牙解锁（吉利通知栏签名 `PendingIntent` `0x7f0a10cb` + AIDL `feat_dk_fast_ctrl = 1` + `com.snc.dk.rv.notification.ctrl` + `RemoteViewsClickReceiver` + `WgProviderMiddle`）；并自动在 **`+1.2s / +2.5s / +3.6s / +4.8s / +6.2s`** 执行 5 轮阶梯护航重发，确保在车机 `~3.07s` 发出钥匙连接提示音后立即完成解锁 |
| **Stage 3：车旁 3米安全缓冲带** | 约 `2.0 ~ 7.0m` | `-82 dBm < effectiveDbm < -63 dBm` | **迟滞死区保护**：严禁下发锁车指令；一旦回到此区间立即清零任何锁车倒计时；若监测到原车固件在 3 米抢先误锁，可自动补发解锁抵消 |
| **Stage 4：离车自动上锁** | 约 `7.5m` 外 | **仅**：`effectiveDbm <= -82 dBm` 连续保持 `4.5s` | 必须同时通过下方硬件级安全联锁后，才触发本地蓝牙闭锁（同样支持多通道与阶梯重发保障）。**不再要求 `transitionZoneSampleCount >= 2` 的远离过渡轨迹** |

**实现要点**：
1. `evaluateRadarState()` 同时由 `periodicTickRunnable`（450ms / 2000ms 兜底）和**每一条新鲜 RSSI 样本**（`onNewRssiMeasurement()` → 180ms 去抖）触发；锁屏时不再依赖 UI 刷新频率。
2. 信号中断超过 8s 视为连续性被破坏，上锁驻留计时器清零并重新播种滤波器，避免用陈旧样本误判。
3. 冷启动/服务被 OEM 杀掉重启后，**不再**因首帧强信号静默写入 `UNLOCKED`；只要车机仍是锁定状态，解锁阈值满足（或上锁阈值+驻留满足）后就会正常下发对应指令。
4. 车机 `0x0121` 状态帧回传真实锁态后仍会同步 `LOCKED/UNLOCKED`（`onVehicleStatusParsed`），并在同步后立即重跑一次状态机，避免「命令丢了但 App 以为已解锁」；真实锁态不一致的完整处理见 **[09](09_v2_4_shake_state_and_ui.md)**。

---

## 二、硬件级「行车与蓝牙闪断防误锁安全联锁」（v2.3：三道）

为确保**开车过程中、车内通电休息时、蓝牙端口重置或瞬间闪断时**绝对不会下发锁车指令，`KeyRadarService.evaluateDrivingAndGlitchSafety()` 在每次准备闭锁前强制执行以下硬件级否决检查（任一命中即 `100%` 拦截闭锁）：

1. **联锁 1：车载娱乐/通话蓝牙 (`A2DP` / `HEADSET`) 连接检测**
   - 检查系统 `BluetoothAdapter.getProfileConnectionState()` 是否已连接车载音响 (`BluetoothProfile.A2DP`) 或免提电话 (`BluetoothProfile.HEADSET`)。
   - 只要车机已通电且连上车载多媒体蓝牙，说明车主在车内，**无条件拦截一切自动锁车**。
2. **联锁 2：GPS 移动车速检测 (`Speed >= 6.5 km/h`)**
   - 检查 `LocationManager` 最新速度。只要移动速度 $\ge 1.8\text{ m/s}$（`6.5 km/h`），判定为车辆行驶中，**无条件拦截一切自动锁车**。
3. **联锁 3：蓝牙适配器端口重置与近距强信号断崖闪断保护**
   - 监听 `BluetoothAdapter.ACTION_STATE_CHANGED`：蓝牙开关/重置后 `15 秒` 冷却期内禁止锁车。
   - **ACL 闪断检测**：如果车机 ACL 断开前最后一次滤波信号仍处于近场强信号区（`>= -74 dBm`，说明人就在车内或车门边），判定为**蓝牙端口闪断而非人走远**，熔断锁车 `10 秒`。这是硬件事件保护，不是 RSSI 轨迹判定。
4. ~~**联锁 4：正常「由近及远」衰减轨迹校验**~~（**v2.3 已删除**）
   - 原规则要求进入 `<= -82 dBm` 远距区之前采集至少 2 个中距离缓冲带样本。实测 RSSI 跳变会让该计数长期停在 `0/2`，导致合法的远离上锁被永久拦截；其防护价值已被「连续 `4.5s` 低于阈值」的驻留计时器替代。
   - 同时删除的还有「单帧骤降 > 22 dBm 熔断 6s」「座舱强信号 2.2s 内跌破锁车线熔断 8s」两条 RSSI 跳变启发式规则——它们与轨迹规则同源，会在信号正常抖动时误熔断。

---

## 三、🟢 纯手机侧参数 vs 🟠 车机侧参数隔离与备份还原

### 1. 参数分类原则
- **🟢【纯手机侧参数 · 不修改车机】（默认推荐，100% 零车机风险）**
  - 包含：手机侧靠近解锁阈值 (`-63 dBm`)、手机侧远离闭锁阈值 (`-82 dBm`)、闭锁防抖确认时长 (`4.5s`)、卡尔曼滤波与口袋遮挡补偿、10 米自动唤醒重连、行车安全联锁、单选预设档位（`推荐防误锁` / `宽容远距` / `紧凑近距`）。
  - 所有计算均在手机内存与本地 `SharedPreferences` 完成，不向车机写入任何配置帧。
- **🟠【车机侧参数 · 修改车辆设置】（默认安全锁定 🔒）**
  - 经逆向 `DkBleConfigImpl.doAction`（`0x602efc`）确认：吉利银河L6 (`FS12`) 属于 `GEELY_DK15` 平台（`isDkVersion20() == false`），车机固件不支持 DK 2.0 的 `0x0170` 校准挡位（早期 UI 上的 1~5 挡为假设挡位，已在 v2.2 移除），车机侧真正支持读写的是 **`action = 5392`（`0x1510 ACT_PE15_SWITCH` -> 蓝牙帧 `0x0501 CMD_PE_SWITCH15`：车机原生无感 PE 总开关 `0=关闭, 1=开启, 2=查询`）**。
  - 默认处于只读锁定状态，必须手动打开「允许修改车机蓝牙参数」安全开关后才允许操作。

### 2. 车机真实参数实时读取、自动备份与一键还原 (`GeelyAidlBridge.java`)
- **连接或查询时自动读取并备份**：通过下发 `5392=2`（`0x0501 PEQuery`）和 `272=0`（`0x0121` 9字节车况帧），将车机返回的 `PERspPayload15` 4字节回执（`byte[1]` 为车机真实 PE 开关状态）、`DkVehicleInfo15` 9字节原始帧、`UsedDkId`、`VIN`、`BLE MAC` 及混动油电表显快照双重持久化到：
  1. `/sdcard/Android/data/com.luca.geelykeyradar/files/vehicle_param_backup.json`（及内部目录 `/data/user/0/com.luca.geelykeyradar/files/vehicle_param_backup.json`）
  2. `SharedPreferences("vehicle_param_backup_prefs")`
- **一键还原 (`♻️ 一键还原车机原厂备份状态`)**：随时将备份的原厂 PE 开关状态重新写回车机并恢复安全锁。

---

## 四、如果吉利银河 App 不在后台，「银河钥匙雷达」还能生效吗？

**完全可以生效！** 原因与自动拉起链路如下：

1. **吉利银河的蓝牙钥匙服务是独立进程 `:dkservice`，不依赖吉利主界面 Activity**
   - 吉利的数字钥匙运行在独立后台服务 `com.geely.snc.digitalkey.base.service.DigitalKeyBgService`（进程名 `com.geelygalaxy.customer:dkservice`）和保活服务 `com.ecarx.blekey.thirdadapter.service.MiuiService` 中。
2. **两大服务在吉利 Manifest 中均为 `exported="true"`，支持外部显式/Action 跨应用冷启动**
   - `DigitalKeyBgService` 暴露了 Action：`"com.zeekr.snc.service.action.DK_START"` 与 `"com.geely.pendintent.scanresult"`。
   - `MiuiService` 暴露了 Action：`"com.geely.geometry.miuiservice.action"`。
3. **「银河钥匙雷达」在 12 米外扫到车时会自动冷启动吉利 `:dkservice` 进程**
   - 哪怕你把吉利银河 App 从后台多任务划掉了，只要你走向车辆（$\ge -88\text{ dBm}$），我们的常驻服务 `KeyRadarService` 扫到车辆 `15FF` 广播后，会立即调用 `GeelyAidlBridge.wakeAndConnectGeelyBle()`：
     1. 通过 `bindService` (`BIND_AUTO_CREATE | BIND_IMPORTANT`) + `startService` 发送 `com.zeekr.snc.service.action.DK_START` 和 `com.geely.geometry.miuiservice.action`，**直接把死掉的吉利 `:dkservice` 蓝牙钥匙进程在后台静默拉活**；
     2. 紧接着向 `:dkservice` 发送 `com.geely.pendintent.scanresult` 与 AIDL `feat_dk_ble_manual_con (289)`，命令它立即连上车机蓝牙；
     3. 待 1~2 秒内吉利 `:dkservice` 连上车机蓝牙后，你走到 1.8 米处即可正常触发本地蓝牙解锁！
   - **唯一例外**：如果你在系统设置里对吉利银河点了「强行停止 (Force Stop)」，Android 系统会暂时禁止一切隐式广播拉起，但我们的显式 ComponentName `bindService` (`com.geely.snc.digitalkey.base.service.DigitalKeyBgService`) 依然能够突破 Force-Stop 将 `:dkservice` 重新拉起。

---

## 五、锁屏（息屏）下为什么之前会失效/解锁慢？v2.3 如何修复

### 1. 根因链
1. **CPU 休眠 / Doze**：屏幕熄灭后设备会 suspend 或进入 Doze。`Handler.postDelayed(450ms)` 只是主线程消息，不是实时时钟；没有 `PARTIAL_WAKE_LOCK` 时，驻留计时、GATT `readRemoteRssi()` 与解闭锁下发都可能被推迟数秒。Doze IDLE 后，非白名单 App 的 wake lock 会被系统忽略。
2. **AOSP/OEM 息屏扫描降频**：Android Bluetooth `ScanManager` 有 screen-off scan mode 降级逻辑（AOSP 提交 `Allow the forced scan mode to be lower one during screen off`），vivo/Poco 等机型在息屏时甚至暂停 callback scan，导致车机广播样本骤减。
3. **原实现只在周期 tick 判定**：状态机只在 `periodicTickRunnable` 内执行；App 不在前台时 tick 为 2s，叠加 CPU 休眠会把「0.8s 解锁」放大成数秒。
4. **服务被杀重启后首帧静默 UNLOCKED**：手机锁屏久了，OEM 可能杀掉服务；重启时若首帧恰好是强信号，原逻辑直接标记 `UNLOCKED` 且不再下发解锁命令。
5. **小组件状态帧伪「已解锁」**：`VehicleStatusSnapshot.isUnlocked()` 曾把 `vehicleLock == -1 && doorLockMask == 0`（锁态未知）当成已解锁；`WgProviderMiddle` 只回传「充电中/已驻车」文字时正好命中该分支，`onVehicleStatusParsed()` 于是把雷达状态同步为 `UNLOCKED`，真实锁车时也不会再发解锁命令。
6. **未申请电池白名单**：只靠 adb 的 `deviceidle whitelist`，换机/重装/系统更新后容易丢失，且 OriginOS 还有独立的「后台高耗电」开关。

### 2. v2.3 修复
- `KeyRadarService` 新增 `PARTIAL_WAKE_LOCK`：仅在「屏幕关闭 + 车辆 5 分钟内出现过」或「刚锁屏 45s 引导窗口」持有；亮屏或车辆远离 5 分钟后立即释放，避免整夜耗电。
- **每个 RSSI 样本**都触发 180ms 去抖的 `evaluateRadarState()`；周期 tick 只做兜底，不再决定实时性。
- **息屏扫描心跳**：屏幕关闭、仍在扫描、银河未连接且 45s 无车机广播时，重建一次 `BluetoothLeScanner`（90s 节流），对抗厂商息屏暂停扫描。
- `MainActivity` 首次启动请求 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（Doze 白名单），失败时引导到电池设置页；同时提示 OriginOS 的「后台高耗电」设置。
- 删除首帧静默 `UNLOCKED`；冷启动或信号中断 >8s 时只重置上锁驻留计时器并重新播种滤波，让解锁单次阈值/上锁阈值+驻留重新决策。
- 修复小组件状态帧伪「已解锁」：`isUnlocked()` 只认显式 `vehicleLock == 0`，并新增 `hasLockState()` 门控；锁态未知时保持原状态。
- `appendLogRaw()` 同步输出 `Log.i("GeelyKeyRadar", ...)`（部分 OriginOS 版本会限制第三方 logcat 可见性，以 App 内日志控制台为准）。

### 3. 仍建议的额外保活（换机/重装后执行）
```bash
ADB="adb"
for pkg in com.luca.geelykeyradar com.geelygalaxy.customer; do
  "$ADB" shell dumpsys deviceidle whitelist +$pkg
  "$ADB" shell cmd appops set $pkg RUN_IN_BACKGROUND allow
  "$ADB" shell cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow
  "$ADB" shell cmd appops set $pkg WAKE_LOCK allow
  "$ADB" shell cmd appops set $pkg START_FOREGROUND allow
  "$ADB" shell am set-standby-bucket $pkg active
done
```
OriginOS 6 还需在「设置 → 电池 → 后台耗电管理」中把两个 App 设为**允许后台高耗电**，并在 i管家「自启动」中允许。

---

## 六、v2.4：摇一摇解闭锁、真实锁态一致性与 UI 稳定化

- **锁屏摇一摇**：`shakeListener` 以 50Hz 加速度计检测 900ms 内 3 个 ≥16 m/s² 的加速度峰；只在「车辆 5 分钟内出现过」或「刚锁屏 45s 引导窗口」注册传感器，此时雷达 `PARTIAL_WAKE_LOCK` 已持有，非 wake-up 传感器也能工作；触发后按当前/最后确认锁态取反，走与通知栏按钮相同的 `sendFastControl()` 多通道。
- **真实锁态一致性**：真实 9 字节 `0x0121` 帧才更新 `lastLockStateEvidenceMs`；乐观指令 12s 无匹配回执 → `UNKNOWN`；3 分钟无真实帧时只把 `UNLOCKED` 降级为 `UNKNOWN`（避免吞掉靠近解锁），`LOCKED` 保留为最后已知状态；新增「🔄 刷新真实锁态 & 车况 (0x0121)」按钮与前台恢复轻量查询。
- **UI**：删除室内模拟信号发生器；解锁/上锁阈值与驻留时长改为 `EditText` 数值输入 + 校验 + 应用按钮；顶部状态文本固定行数，日志控制台固定 8 行，解决页面高度抖动。
- 完整实现与实机验证记录见 **[09_v2_4_shake_state_and_ui.md](09_v2_4_shake_state_and_ui.md)**。

### v2.4.1 / v2.4.2 补丁

- **独立开关（v2.4.1）**：`auto_unlock`（靠近自动解锁）、`auto_lock`（远离自动上锁）、`shake_toggle`（摇一摇）三个开关互不影响；删除旧 `smart_takeover` 总开关，避免总开关关闭时子开关失效的困惑。
- **锁屏摇一摇震动（v2.4.2）**：锁屏/锁屏界面触发时连续震动 **1 秒**（幅度 255）；亮屏已解锁时为双短震（55/70/55ms）；摇一摇路径不再调用 `vibrateAlert(30/80/180)`，避免把 1 秒长震提前取消；解锁/上锁仍播放提示音。

### v2.5 补丁

- **双次摇一摇**：高通线性加速度、同轴 4 峰 + 2 次方向反转 + 1.8s 窗口、阈值 20 m/s²、3s 冷却；旧 3 峰/900ms 规则弃用。
- **提前连接**：唤醒门槛 -95 dBm、动态 1.5/2.5s 重试、亮屏/App 回前台提前预连接。
- **关门后摇一摇上锁**：手动/摇一摇改用 `evaluateHardDrivingState()` 窄口径门控（只看通知行驶中/车速/残余速度/EPB 释放移动），不再被 A2DP 或单独 PTReady 否决；自动解闭锁仍用全量安全门控。
- **4 个独立开关**：`auto_unlock` / `auto_lock` / `shake_unlock` / `shake_lock`。
- **数据记录**：App 内一键录制 15s 到 `shake_diag_*.csv`，用于继续调整摇一摇阈值。

### v2.6 补丁

- **识别即震动**：双次摇一摇识别后立即 1 秒震动（锁屏/锁屏界面），随后才做车况/开关/行车门控；误摇不进入 handler。
- **记录 UI**：15s 记录按钮显示倒计时并禁用，完成后状态行显示文件名与样本数。
- **记录期间强制注册传感器**：`now < shakeDiagUntilMs` 时不受车辆附近/45s 窗口限制，方便在家采集真实摇动数据。
- **检测放宽**：阈值 15 m/s²、窗口 2.2s、按第一个峰的手势轴投影计数。

### v2.6.1 补丁

- 记录完成状态增加 `识别 N 次`，可直接判断检测器是否命中。
- 真实样本验证：`shake_diag_20261104_225728.csv`（18.2/35.1/15.5/26.7/35.7 峰）在 0.599s 触发；旧 20 m/s² 阈值只看到 3 个峰导致漏触发。最终条件为 15 m/s² / 4 峰 / 2 反转 / 2.2s / 3s 冷却。

### v2.7 补丁

- **电源键三连**：2s 内 3 次屏幕亮灭切换解闭锁；新增 `power_triple` 开关；已在 V2426A 实测触发。
- **未连接先连接后下发**：`performManualControl()` 挂起指令，`onVehicleBleStateChanged(status=2)`/轮询确认后 `flushPendingManual()` 补发；20s 超时语音“未发现车辆”。
- **语音播报**：TTS 已解锁/已锁车/蓝牙未连接/蓝牙连接中/蓝牙已连接/未发现车辆；新增 `voice_announce` 开关。

### v2.8 / v2.8.1 补丁

- **四场景状态机**：①车不在附近：非 connecting 立即语音“未发现车辆”，若银河恰好 connecting 最多等 6s；②银河正在连接：挂起等待、不重复唤醒，车在附近等 15s；③已发现未连接：唤醒一次，9s 等待；④已连接：立即下发。
- **尊重银河 5s connecting**：`getBleConnectStatus()==1` / `lastBleStatus==1` 时不再重复 `wakeAndConnectGeelyBle`；只有失败回到 0/-1 后才重试，最多 2 次。
- **v2.8.1 no-car 实测**：电源键三连触发后 6 秒语音“未发现车辆，蓝牙连接超时”，不再等 20s。

### v2.9 补丁

- **陈旧 status=1**：只在 8s 内可信；超过后强制重新唤醒，不再无限延长等待。
- **无广播也尝试**：手动/摇一摇/电源键不再因 90s 无广播直接放弃，至少调用一次 wakeAndConnect。
- **强制重连**：有车最多 3 次尝试（初始 + 2 次重试），无车 2 次；每次重新 wake，而不是只等 Geely。
- **持久日志**：`keyradar_debug.log` 异步落盘、1.5MB 轮转，用于事故后 adb pull 分析。

### v2.9.1 补丁

- **电源键三连窗口 2s→3s**：实测 645ms+1395ms=2041ms 的自然三连旧版会漏，新版触发。
- **冷却 3s→2s**，冷却时清空计数，避免陈旧三连在冷却后误触发。
- **逐次日志**：`电源键状态变化 N/3 (+间隔ms)`，可区分“系统没给事件”和“窗口超时”。
- **固定 1 秒震动**：电源键三连不再使用亮屏双短震，反馈一致。

### v3.0 补丁

- **音量键三连/长按 2 秒**：`VolumeKeyAccessibilityService` 申请 `canRequestFilterKeyEvents`，3 次按下（1.5s 内）或按住 2s 触发。
- **音量恢复**：不消费音量键，触发后恢复 `STREAM_MUSIC` 原音量，普通调音量不受影响。
- **统一外部触发**：`ACTION_EXTERNAL_TOGGLE` + `toggleLockFromExternal()`，复用电源键三连的切换/连接/语音/日志。
- **UI**：音量键开关、无障碍状态行、未开启时跳转系统无障碍设置。

### v3.1 补丁

- **音量上+下组合键**：同时按住约 600ms 切换解闭锁；单按不再触发，避免与调音量冲突。
- 移除音量三连/长按和音量变化兜底触发，只保留组合键与诊断日志。
- 组合键触发后恢复媒体音量；复用先连接后补发、语音、1 秒震动与持久日志。

### v3.2 补丁：删除音量键无障碍方案

- 经实机测试，音量键三连/长按/上下组合在锁屏、音量调节和系统触感抢占下不稳定。
- 已完整移除 `VolumeKeyAccessibilityService`、无障碍清单声明、`res/xml` 配置、UI 开关、`KeyRadarService` 外部触发入口和 `volume_key_debug.log`。
- 保留摇一摇、电源键三连、手动按钮、自动靠近/远离、语音、先连接后补发与 `keyradar_debug.log`。

### v3.3 补丁：快捷设置磁贴

- 新增 `KeyTileService`，控制中心「车锁切换」磁贴一键触发。
- 复用 `ACTION_EXTERNAL_TOGGLE` + `toggleLockFromExternal()`，与电源键三连同链路。
- App 内新增「添加快捷设置磁贴」按钮（Android 13+ `requestAddTileService`）。
- 厂商控制中心未自动放入面板时，可在编辑里手动添加。

### v3.3.2 补丁

- 磁贴副标题/状态描述/常驻通知实时显示“蓝牙搜索中、蓝牙连接中、已连接正在解锁、解锁成功、锁车成功、连接失败、未发现车辆”。
- 磁贴来源不再震动；成功状态以真实 0x0121 锁态帧为准。
- 标准 QS 磁贴不支持自定义宽高；需要长条大卡片应使用 4x1 AppWidget。

### v3.4 补丁：4x2 桌面组件

- 新增 `KeyWidgetProvider` + `widget_lock_4x2.xml`：4x2 深色卡片、右侧车图、左侧实时状态、底部等宽大按钮「解锁/上锁」。
- 新增 `ACTION_EXTERNAL_UNLOCK/LOCK` 显式动作；桌面组件点击 45ms 短震动，状态实时刷新。
- 状态沿用 `publishTileStatus`：蓝牙搜索中/连接中/正在解锁/成功/失败，成功以真实 0x0121 帧为准。

### v3.5 补丁：组件续航行

- 4x2 组件新增三列：电池续航/百分比、燃油续航/百分比、综合续航。
- 数据从 `PhevRangeSnapshot` 经 `publishWidgetRange()` 写入 prefs 并广播刷新。
- 实机数据：电 96km/76%、油 1149km/98%、综合 1245km。

### v3.5.1 补丁：组件名称/预览

- 组件名改为「银河车锁」，新增 previewImage 与 initialKeyguardLayout，便于在应用挂件中搜索“银河”。
- “原子组件”由 vivo launcher 按包名/合作方白名单判定；标准 AppWidget 无法仅靠 Manifest 自行进入。

### v3.6 补丁：车辆状态与云端刷新

- 组件信息行显示 `车辆状态: 已驻车`；来源 `currentVehicleParkState` / `PhevRangeSnapshot.warnStatusText`。
- 头部新增 `⟳ 刷新`：`refreshVehicleStateNow()` + `requestWidgetPullNewStatus("PULL_NEW_STATUS")`，状态显示云端刷新中/成功/失败。
- 实机：电池 95km/75%、燃油 1149km/98%、综合 1244km、已驻车。

### v3.6.1 补丁

- 组件 padding 微调，4x2 内容约 154dp，避免底部按钮裁切。

### v3.7 补丁：组件二次确认

- 组件右上角 ⚙ 打开 `WidgetSettingsActivity`，开关 `widget_confirm_actions`。
- 开启后解锁/上锁 PendingIntent 改为 `WidgetConfirmActivity`，弹框确认后才启动服务；关闭时一键执行。
- 刷新按钮不受二次确认影响。

> 手机车钥匙完整工作流程图（整体架构 + 我们 App 主动 RKE）见 **[18_phone_key_workflow.md](18_phone_key_workflow.md)**。

### v3.7.1 补丁

- 组件根布局空白处 PendingIntent 打开 App；按钮/刷新/齿轮子 View 保留各自点击。

### v3.8 补丁：组件蓝牙信号

- 组件连接行显示 RSSI（如 `已连接 -62dBm`），颜色按 -60/-72/-82 dBm 分级。
- `publishWidgetRssi()` 节流约 1s/2dB；断连显示 `--dBm`。
- v3.7.1 的空白点击打开 App 一并包含。

### v3.8.1 / v3.8.2 补丁

- 组件连接行始终显示信号占位符：未连接/扫描/已连接都带 `--dBm` 或实际 dBm。
- App 服务启动时广播 `ACTION_TILE_STATUS`，强制已有组件重新读取 prefs。

### v3.9 补丁：云端/本地锁态仲裁与锁图标

- `VehicleStatusSnapshot` 增加 `isCloudSource/cloudTimestampMs`；云端 JSON 尽力解析 updateTime/timestamp。
- `lastRealBleLockEvidenceMs` 持久化；真实 0x0121 锁态优先，云端无时间戳/较旧时忽略，避免旧解锁覆盖新上锁。
- 组件新增开锁/闭锁图标，由 `widget_lock_locked` 驱动。

### v3.10 补丁：插充电线禁止解闭锁

- 新增开关 `block_control_when_charging`，默认开启；USB/AC 有线充电触发，无线不触发。
- 充电时拦截手动/摇一摇/电源键/磁贴/组件/自动靠近/通知栏队列的解锁与上锁；后备箱/哨兵/刷新不受影响。
- UI 解锁/上锁按钮置灰并显示“插线禁解/禁锁”，车辆状态行显示充电原因。

### v3.11 补丁：锁屏 RSSI / 重复指令 / 组件状态

- 连接态 GATT `readRemoteRssi` 提高到 1Hz，修复锁屏靠近直到亮屏才触发。
- 自动指令同方向 20s 静默，避免 12s 无回执回退后二次解锁；反方向不抑制。
- 组件命令 4s 后落到“指令已发送”，12s 后“锁态未知”；真实 0x0121 才显示“锁车成功/解锁成功”。
- 服务启动时清理残留的“正在/搜索中”状态。

### v3.12 补丁：总里程增量采集

- 修复 `wg_tv_mileage` 被误存为 `totalRangeKm`，新增 `PhevRangeSnapshot.totalMileageKm`。
- 每次银河小组件/云端刷新增量记录总里程，持久化 `mileage_last_km`、`mileage_history_json`（最多 200 点）。
- `PhevTripRecord` 增加 `startOdometerKm/endOdometerKm/odometerDistKm`，行程结算显示车机总里程差。

### v3.13 补丁：车内 RSSI 稳定滤波

- 车内/充电/车机连接时启用 4 秒中值窗口、±8 dB 异常钳位、Kalman q=0.02、对称 EMA 0.18、10 dB/s 限速。
- 车外保留快速跟踪；原始信号仍真实显示，稳定的是有效信号。

### v3.14 补丁：P 档/下车后误判行驶中

- 移除 A2DP/HFP 作为行驶证据；车机音频下车后仍连接，不再挡住上锁。
- 通知栏“行驶中”增加 90 秒时效；BLE“行驶中”只在 30 秒内可信；“已驻车”优先。
- 25 秒 hold 仅在仍有残余速度 >0.5 m/s 时生效；低速 stale DRIVING 兜底回 PARKED。

### v3.15 补丁：驾驶舱 UI 重构

- 单页改为 Header + Hero + 四页签（控制/行程/设置/日志），每页独立滚动。
- Hero 常驻锁态、有效信号、距离、解锁/上锁主按钮。
- 统一深色视觉 token、18dp 卡片、44–48dp 触摸目标，并清理静态标签 emoji。

### v3.16 补丁：BLE 丢失不误锁

- ACL 断开/蓝牙关闭/扫描失败立即 `resetAutoLockDwell()` 并禁用自动上锁 10–15 秒。
- 自动上锁要求 3 秒内的新 RSSI、至少 3 个新样本、驻留满足；旧的 8 秒 signalFresh 不再用于锁车。
- 蓝牙关闭期间自动上锁禁用，避免旧弱 RSSI 误锁。

### v3.17 补丁：组件总里程 + 胎压

- `wg_tv_mileage` 写入 `widget_total_mileage`，组件副标题显示总里程。
- `VehicleTyreStatus` 胎压属于平台/TSP，不在 0x0121；新增 JSON `tyreStatus*` 与 `wg_tv_config_*` best-effort 解析，取不到显示 `--`。
- 3.17.0 APK 已构建，安装时 USB 断开，待重装。

### v3.18 补丁：总里程语义纠正

- `wg_tv_mileage` 回退为综合续航；真实总里程是 `VehicleBasicStatus.odometer`（TSP/状态页）。
- 清空旧版误存的 1238km 总里程/历史/行程里程字段；组件改为 `总里程 --`，直到拿到真实 odometer。

### v3.19 补丁：只读无障碍读取总里程/胎压（v3.20.0 已删除）

- 曾新增 `GeelyStatusAccessibilityService`，只读银河车辆状态页。
- v3.20.0 已完整删除该服务、配置、设置入口、组件字段、Trip odometer 字段及 JSON 解析。
- 原因：无障碍只能在打开银河“车辆状态”页时读取，无法后台自动更新；普通第三方权限也无 Geely 公开车辆状态接口。
