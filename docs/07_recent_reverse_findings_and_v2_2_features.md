# 07. 近期逆向核心发现与「银河钥匙雷达 v2.2」全特性总结

本文档汇总了针对 **吉利银河L6 (`LB378LEZ0XX000123` / 车型代号 `FS12` / 协议平台 `GEELY_DK15`)** 的最新逆向结论、底层协议真相与 App v2.2 全部新增工程特性。

---

## 一、为什么手机距车 5 米左右信号突然呈现“阶梯式跳变”，且 10 米外很少连接蓝牙？

通过逆向 `dk_core.dex`（`ZeekrBleSdkManager.java`）与实车 BLE 抓包，确认该现象并非距离平方反比定律失效，而是由 **车机双模式广播机制 + 吉利 SDK 5帧门槛 + 连接态信道切换** 三重机制共同导致的：

1. **车机 BLE 模块「休眠慢广播 -> 唤醒快广播」的硬件阶梯切换**
   - 当人远离车辆（`> 8~10m`）时，车机蓝牙模块处于低功耗慢速广播模式（Advertising Interval 约 `500ms ~ 1000ms`），且发射功率较低，加之地面多径反射相消（Two-Ray Ground Reflection），手机在 10 米外很难连续抓到广播包。
   - 当手机走到距车 **5 米左右**，或通过云端/广播触发唤醒时，车机切换至 `20ms ~ 100ms` 高频广播并切入已连接态（Connected），每秒样本数暴增 10 倍，导致滤波后的信号呈现瞬间“从无到有”的阶梯感。
2. **广播信道（37/38/39）与连接态数据信道（0~36 跳频）的 RSSI 基线跳变**
   - 未连接时，手机扫描的是 `2402 / 2426 / 2480 MHz` 三个固定广播信道（极易受 Wi-Fi 干扰）；
   - 在约 5 米处一旦建立 GATT/ACL 连接，车机立即**停止广播**，手机改为通过 `BluetoothGatt.readRemoteRssi()` 读取 37 个跳频数据信道上的连接态信号，底层芯片上报的 RSSI 往往比广播态跳升 `4 ~ 8 dB`。
3. **吉利银河 SDK 的软件硬门槛与 `piScanMacCache` 一次性死锁**
   - 在 `ZeekrBleSdkManager$scanCallback$1.onScanResult`（`0x66fc5a`）中，吉利官方代码写死了：必须连续攒够 **5 个广播包（`rssis.size() == 5`）** 才计算一次平均 RSSI，且平均 RSSI 必须大于 `connectRssi` 门槛才会调用 `connectVehicleBle`；
   - 更致命的是 `ZeekrBleSdkManager.onReceive`（`0x67085a`）：系统 `PendingIntent` 扫到车机 MAC 后，先把 MAC 塞进 `piScanMacCache` 集合；如果第一次在 10 米外信号弱未连上，后续走近到 5 米内时，因为 `piScanMacCache.contains(mac)` 返回 `true` 直接 `return` 丢弃，导致官方 App 经常走到车门边也不自动连蓝牙。

---

## 二、vivo 手机钱包车钥匙与吉利银河 App 的真实依赖关系

针对「将银河车钥匙添加到 vivo 手机钱包后，卸载吉利银河 App 能否解锁车辆？我们的 App 是否依赖吉利银河 App？」的逆向结论（见 `dk_vivo.dex` 与 `vivo_wallet.apk`）：

1. **NFC 卡片钥匙独立于吉利 App，但 BLE 蓝牙无感钥匙强依赖吉利银河 App**
   - **NFC 刷卡钥匙**：写入手机内置安全芯片（eSE），刷 B 柱 NFC 读卡器由硬件直接交互，哪怕手机关机或卸载吉利银河 App 仍可刷卡解锁。
   - **BLE 蓝牙钥匙（靠近解锁 / 通知栏解闭锁）**：vivo 钱包自身**不持有**吉利专有 DK1.5 蓝牙滚动鉴权密钥算法。逆向 `dk_vivo.dex` 可见，vivo 钱包/智能车载在后台是通过显式 Action `"com.geely.geometry.miuiservice.action"` 绑定吉利银河 App 导出的 `com.ecarx.blekey.thirdadapter.service.MiuiService` 来完成蓝牙鉴权与控车的。
   - 因此：**一旦卸载吉利银河 App，vivo 钱包的蓝牙车钥匙也会立即失效**；我们的 `GeelyKeyRadarApp` 同样通过唤醒并桥接吉利银河的 `DigitalKeyBgService` 与 `MiuiService` 工作，必须保留吉利银河 App 安装在手机上。

---

## 三、通知栏「解锁/上锁」按钮底层原理与 DK 1.5 命令反转修复

### 1. 为什么车机未开启「靠近解锁远离上锁」时，调用靠近解锁接口无效？
- 当车机大屏关闭了原生「靠近解锁/远离上锁」被动 PE 开关时，车机拒绝响应被动 PE 触发，**但始终响应吉利银河常驻通知栏（Notification ID = `5`）上的「解锁」和「上锁」主动 RKE 按钮**。

### 2. 吉利银河常驻通知栏 (`_aaa_ge_dk_notification_view`) 逆向结构
逆向 `dk_core.dex` 中的 `com.geely.snc.digitalkey.base.dk.notification.DkRemoteView.buildRemoteView`（`0x5011e8`）：
- **布局资源 ID**：`_aaa_ge_dk_notification_view = 0x7f0d0003`
- **关键控件 ID**：
  - `0x7f0a10c8` (`notif_tv_status_conn`)：显示 `"已连接"`（连接态 `VISIBLE = 0`）
  - `0x7f0a10c9` (`notif_tv_status_disconn`)：显示 `"未连接"`
  - `0x7f0a10c6` (`notif_layout_lock_unlock`)：解闭锁按钮容器（连接态 `VISIBLE = 0`）
  - **`0x7f0a10cb` (`notif_tv_unlock`)**：通知栏 **「解锁」** 按钮
  - **`0x7f0a10c7` (`notif_tv_lock`)**：通知栏 **「上锁」** 按钮

### 3. 核心陷阱：DK 1.5 与 DK 2.0 的解闭锁命令枚举完全反转！
逆向 `DkRemoteView.java` 与 `DkBleFastCtrlImpl.doAction`（`0x604100`）发现了一个极其隐蔽的协议反转：
- 在 **DK 2.0（极氪等）** 中：`Unlock = 1`，`Lock = 2`；
- 但在 **DK 1.5（吉利银河L6 `FS12`）** 中：
  - `DkControlType15.Unlock` -> **`CMD_OPEN_DOOR15 = 2`**（通知栏「解锁」按钮绑定的 `PendingIntent` `requestCode = 2`，`com.extra.cmd = 2`）
  - `DkControlType15.Lock` -> **`CMD_CLOSE_DOOR15 = 1`**（通知栏「上锁」按钮绑定的 `PendingIntent` `requestCode = 1`，`com.extra.cmd = 1`）
- **修复方案（`KeyNotificationBridgeService.java`）**：
  1. 在 Android 14/15/16 上摒弃被系统黑名单拦截的 `RemoteViews.mActions` 反射，改为在主线程调用公开 API `RemoteViews.apply(geelyPkgContext, container)` 真实渲染出吉利通知栏视图树；
  2. 直接通过 `findViewById(0x7f0a10cb)`（解锁）和 `findViewById(0x7f0a10c7)`（上锁）拿到真实按钮 View，并在触发时直接调用 `view.performClick()`，100% 等同于手指点击通知栏上的「解锁 / 上锁」按钮；
  3. 若触发解锁时吉利通知栏尚处于 `"未连接"` 状态，自动进入 **8 秒待连接排队队列 (`pendingActionOnConnect`)**，并立即唤醒吉利蓝牙连接；一旦通知栏变为 `"已连接"`（`0x7f0a10c8 == VISIBLE`），在 **120ms 内自动点击通知栏「解锁」按钮**。

---

## 四、车机侧校准档位真相（为什么没有 5 个档位）与车机原始参数自动备份

### 1. 车机上默认有 5 个档位，还是早期 UI 自己编的？
- **结论：此前界面上的“1挡(近) ~ 5挡(远)，默认3挡”是早期 UI 设计时自己编的假设挡位，吉利银河L6 车机固件中并不存在这 5 个挡位。**
- **逆向代码证据（`dk_core.dex` -> `DkBleConfigImpl.doAction` @ `0x602efc`）**：
  - `action = 368`（`0x0170 CMD_A2V_CALIBRATION_GEAR`，校准挡位）以及 `action = 5393 / 5394` 在入口处第一行即执行：
    ```java
    if (!DkContext.INSTANCE.isDkVersion20()) {
        return;
    }
    ```
  - 吉利银河L6（`FS12`）的 `VehiclePlatform` 为 `GEELY_DK15 = 1`（`isDkVersion20() == false`），因此在银河L6上调用 `0x0170` 会直接返回，不会向车机发送任何指令；且即便在极氪 DK 2.0 上，`CalibGearParamPayload.packData`（`0x60ccb4`）打包的也是多字节标定数组（`byte[]`）而非 `1~5` 整数。

### 2. 吉利银河L6 (DK 1.5) 车机侧真正支持读写与自动备份的参数
在 `DkBleConfigImpl.doAction(int action, int value, IDkBleDataListener)`（`0x602b68` ~ `0x602e68`）中，银河L6（`GEELY_DK15`）车机唯一支持的配置指令是 **`action = 5392`（`0x1510 ACT_PE15_SWITCH` -> 蓝牙帧 `0x0501 CMD_PE_SWITCH15`）**：
- `value = 0`（`PEClose`）：关闭车机原生无感 PE 总开关
- `value = 1`（`PEOpen`）：开启车机原生无感 PE 总开关
- **`value = 2`（`PEQuery`）：查询车机当前无感 PE 总开关的真实状态**
- **车机 4 字节回执解析（`PERspPayload15.fromBin([B)` @ `0x60bf1c`）**：
  - `byte[0]`：请求的操作类型（`0=关, 1=开, 2=查询`）
  - `byte[1]`：**车机当前真实的无感 PE 开关状态（`0=关闭, 1=开启`）**
  - `byte[2..3]`：执行结果码（`0x0000 = EEC_CMD_SUCCESS`）
- **手机端自动备份与实时展示（`GeelyAidlBridge.java` & `MainActivity.java`）**：
  - 连接车机或点击「🔍 读取车机真实参数并备份」时，自动下发 `5392=2`（`0x0501 PEQuery`）与 `272=0`（`0x0121` 9字节车况查询）；
  - 将实读到的车机 `0x0501` PE 开关状态、`PERspPayload15` 原始 Hex、`0x0121` 9字节 `DkVehicleInfo15` 原始帧、`UsedDkId`、`VIN`、`BLE MAC`、混动油电续航快照自动备份到：
    - `SharedPreferences("vehicle_param_backup_prefs")`
    - `/sdcard/Android/data/com.luca.geelykeyradar/files/vehicle_param_backup.json`
  - 在 App 第三卡片的 `📋 车机原始参数实时读数 & 手机本地备份详情` 面板中实时显示，并支持一键还原原厂状态。

---

## 五、车辆实时状态识别（已驻车/行驶中/充电中）与阈值+驻留状态机（v2.3）

### 1. 吉利银河 App 车辆状态枚举逆向 (`CarBusiness$Companion.getVehicleParkBrakeStatus`)
逆向吉利银河主包 `geely_sub_0.dex`（`0x75599c`）与 `dk_core.dex` 中的 `DkVehicleInfo15.parse`（`0x60190c`，9字节 BLE 状态帧 `0x0121`）：
- **BLE 9字节状态帧 (`DkVehicleInfo15`) 位域定义**：
  - `byte[4] bits[0..1]`：`doorLockStatus`（`0=未上锁, 1=已上锁`）
  - `byte[5] bits[2..4]`：**`ePBStatus`（电子手刹驻车状态：`1`或`2` = `已驻车`，`0` = `行驶中/手刹释放`）**
  - `byte[5] bit[5]`：**`eptReadyStatus`（动力系统 Ready：`1` = `行驶中/PT Ready`）**
  - `byte[5] bits[6..7]`：`engineStatus`（发动机运行状态）
  - `byte[8] bits[0..2]`：`pepsPowerMode`（车辆电源挡位）
- **5 种车辆状态映射**：
  1. **`已驻车` (`PARKED`, anim=4)**：`ePBStatus in (1, 2)` 且非 Ready
  2. **`行驶中` (`DRIVING`, anim=3)**：`ePBStatus == 0` 或 `eptReadyStatus == 1` 或 `GPS车速 >= 6.5 km/h`
  3. **`充电中` (`CHARGING`, anim=1)**：`stateOfCharge == 1`
  4. **`放电中` (`DISCHARGING`, anim=2)**：`stateOfCharge == 4`
  5. **`已插枪` (`PLUGGED_IN`, anim=0)**：充电枪已连接但未充放电
- **行车安全硬锁**：一旦识别为 **`行驶中`**，App 立即锁定一切自动/手动解锁与上锁操作（手动按钮自动灰化并提示 `🛑 行驶中禁控`）。

### 2. 方向性移动轨迹状态机（v2.3 已删除，改为纯阈值 + 持续驻留）
- **v2.2 原规则**：靠近解锁必须具备 `远 -> 近` 轨迹（`approachOutsideSampleCount >= 1` 或当前为 `LOCKED`），远离上锁必须具备 `近 -> 远` 过渡带采样（`transitionZoneSampleCount >= 2`）；如果人已在车内/车旁，手动开关蓝牙或重启 App 时直接起手就是强信号，系统会静默同步为 `已解锁`。
- **实测问题**：BLE RSSI 在真实环境中会因多径、人体遮挡、广播/连接态信道切换出现 ±10~18 dB 跳变。远离时信号可能从 `-60 dBm` 直接跳到 `-85 dBm`，`transitionZoneSampleCount` 永远攒不到 2，导致**远离上锁被永久拦截**；锁屏下服务被 OEM 杀掉重启后，首帧强信号又会静默写入 `UNLOCKED`，导致**靠近解锁被吞掉**。
- **v2.3 现状**：删除全部方向性轨迹门控与 RSSI 跳变熔断（单帧 22dB 骤降、2.2s 座舱强信号跌落）。自动动作只保留：
  - 靠近解锁：`effectiveDbm >= -63 dBm`（v2.3 为连续 `0.8s`；**v2.4 起满足一次即触发**）；
  - 远离上锁：`effectiveDbm <= -82 dBm` 连续 `4.5s`；
  - 以及行车/车内音频/GPS 车速/蓝牙端口重置/强信号 ACL 闪断等**硬件级**硬联锁。
- 详细根因与锁屏保活修复见 **[08_threshold_dwell_and_screen_off.md](08_threshold_dwell_and_screen_off.md)**。

---

## 六、锁车后自动开启哨兵模式 (`VSTD_MODE`) 与手机提示音

1. **为什么需要锁车自动开哨兵？**
   - 吉利银河车机每次解锁上电后，都会自动将「哨兵模式」重置为关闭状态，导致每次离车锁车后都得打开银河 App 手动点开哨兵。
2. **免弹窗静默开启哨兵模式的逆向实现 (`GeelyAidlBridge.triggerSentinelModeViaWidget`)**
   - 逆向 `WgProviderMiddle` -> `MiddleNormalStateView.onConfirmClickAction`（`0x7fbdb4`）-> `WgCarControl.carControl(WidgetFunType.VSTD_MODE)`（`0x7fbe18`）-> `CarControlBusiness.vsMode`（`0x7f208c`）：
   - 当本 App 触发远离自动上锁或手动上锁后，延迟 **`+3.5 秒`**（等待车门完成落锁并设防），自动向 `com.geely.galaxy.car.weight.appwidget.WgProviderMiddle` 发送带确认标志的广播：
     - Action: `"com.geely.galaxy.customer.widget.event.CONFIRM"`
     - Extras: `WIDGET_EVENT_FLAG = true`, `WidgetConfirmType = "android.appwidget.action.confirm.ok"`, `clickType = "VSTD_MODE"`
   - 直接绕过二次确认弹窗，通过银河后台云端通道自动开启哨兵模式。
3. **手机解闭锁提示音 (`ToneGenerator`)**
   - 解锁成功播放清脆双升调提示音，上锁成功播放双降调确认音，哨兵开启播放三连守护音（可在开关中随时启停或试听）。

---

## 七、银河L6 混动 (PHEV) 行程记录仪 & 油电续航打折率分析

1. **无感提取银河 App 混动油电表显续航 (`startSilentWidgetMirror`)**
   - 通过应用内静默 `AppWidgetHost`（Host ID `0x474C`）绑定吉利银河 4x2 桌面小组件 `WgProviderMiddle`（布局 `app_widget_middle_normal = 0x7f0d007f`），自动提取：
     - **纯电续航 (`km`) & 电池电量 (`SOC %`)**：来自 `wg_energy_container_electric` -> `wg_tv_energy_percent` & `ProgressBar`
     - **燃油续航 (`km`) & 剩余油量 (`%`)**：来自 `wg_energy_container_oil` -> `wg_tv_energy_percent` & `ProgressBar`
     - **总里程 (`km`) & 更新时间**：来自 `wg_tv_mileage` & `wg_tv_update_time`
   - 支持发送 `"PULL_NEW_STATUS"` 广播主动触发银河 App 从云端拉取最新油电续航。
2. **行程核心指标与等效续航打折率计算 (`PhevTripRecord`)**
   - **自动/手动启停**：识别车机进入 `行驶中` 或速度达标自动开启行程；识别到锁车后自动结算行程（并在锁车后 30 秒内监听云端最新表显续航自动回填修正）。
   - **完整记录指标**：
     - **实际行驶里程 (`actualDistKm`)**：高精度 GPS 轨迹积分里程（过滤定位漂移）
     - **最高车速 (`maxSpeedKmh`) & 平均车速 (`avgSpeedKmh`)**：弥补银河官方行车日志无最高车速的缺失
     - **前后电池续航变化 ($\Delta\text{电} = \text{起电} - \text{终电}\text{ km}$) & 燃油续航变化 ($\Delta\text{油} = \text{起油} - \text{终油}\text{ km}$)**
     - **综合续航达成率（打折率）**：
       $$\text{综合续航达成率} = \frac{\text{实际行驶里程 } (\text{km})}{\Delta\text{电} + \Delta\text{油}} \times 100\%$$
   - **历史行程单条原位删除**：支持点击单条行程右侧 `✕ 删除` 按钮原位移除、下方条目平滑上移，无需整页重载。
