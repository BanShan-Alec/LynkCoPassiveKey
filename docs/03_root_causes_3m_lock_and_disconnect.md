# 03. 「车旁 3 米误锁」与「后台偶现不自动连蓝牙」源码级根因分析

本文档总结了通过逆向 `com.geelygalaxy.customer` 与 `com.vivo.car.networking` 定位出的两大核心体验缺陷的源码级根因。

---

## 一、痛点一：为什么在车附近 3 米处就会偶发触发自动上锁？

### 1. 车机侧闭锁迟滞区间 (`lockInterval`) 设计过窄
根据 `classes19.dex` 中 `com.init.ble.f.c` 的车机标定协议：
$$\text{lockRange} = \text{unlockInterval} + \text{lockInterval} - \text{rssiAdjust}$$
- 原厂默认配置中，解锁点对应的 `unlockInterval` 约为 `-60 ~ -62 dBm`（约 1.5~1.8 米），而闭锁迟滞增量 `lockInterval` 仅设置了 **`4 ~ 6 dB`**。
- 这意味着只要车机或手机测得的 RSSI 跌破 **`-68 ~ -70 dBm`**，原厂状态机就会认为用户已经离开闭锁线。

### 2. 人体遮挡与多径效应（Multipath Fading）带来 `-12 ~ -18 dB` 瞬时衰落
- 2.4GHz BLE 电磁波极易被人体水分吸收。
- 当你站在车旁 **2.5 ~ 3.5 米** 处（例如在车尾拿东西、在车边等人聊天）：
  - 手机拿在手里正对车时，信号约为 `-65 dBm`；
  - **一旦你背对车辆，或把手机揣进裤兜**，人体遮挡会让 RSSI 在 0.5 秒内瞬间从 `-65 dBm` 暴跌至 **`-78 ~ -83 dBm`**（等效于突然退到了 8 米开外）！

### 3. 原厂滤波滑窗 (`rssiWindow = 3`) 过短且缺乏防抖确认
- 原厂仅对最近 3~5 个广播包做简单均值（约 `0.6 ~ 1.0 秒`），没有卡尔曼滤波，也没有“持续低于闭锁阈值满 N 秒才允许锁车”的防抖计时器，更没有结合手机侧距离传感器（口袋检测）做补偿。

---

## 二、痛点二：为什么吉利银河 App 偶现靠近车辆不自动连接蓝牙，必须手动打开 App 点「连接蓝牙」？

通过对 `classes2.dex` (`DigitalKeyBgService.java`) 和 `classes19.dex` (`ZeekrBleSdkManager.java`) 的字节码反编译，发现了 **两个致命的逻辑死锁 Bug**：

### 根因 A：`ZeekrBleSdkManager.startBgBleScan()` 的 `piScanMacCache` 缓存死锁 Bug（最常见）
在 `com.zeekr.sdk.snc.ble.ZeekrBleSdkManager` 中，吉利使用 Android 的 `BluetoothLeScanner.startScan(filters, settings, PendingIntent)` 注册后台 `-95 dBm` 扫描：
1. 当调用 `startBgBleScan(mac)` 时，代码**先将车辆 MAC 写入了内存集合 `piScanMacCache`**。
2. 如果此时蓝牙刚开关过、或系统扫描槽位繁忙、或信号处于 `-95 dBm` 极弱边缘，底层回调返回 **`ERROR_10005` (`SCAN_FAILED_INTERNAL_ERROR`)** 或连接超时断开。
3. **致命 Bug**：失败回调中没有清空 `piScanMacCache`！之后当后台定时器再次尝试调用 `startBgBleScan(mac)` 时，第一行判断：
   ```java
   if (this.piScanMacCache.contains(mac)) {
       Log.d(TAG, "has started intent scan for mac: " + mac);
       return; // 直接跳过！永远不再重新注册后台蓝牙扫描！
   }
   ```
4. 于是吉利后台彻底成了“瞎子”，直到你手动打开吉利银河 App 点击界面上的「连接蓝牙」，触发 `connectVehicle()` 绕过 `startBgBleScan` 直接按 MAC 发起重连，才恢复正常。

### 根因 B：`DigitalKeyBgService.java` 第 253 行的 `isWalletBind()` 自断保活 Bug
在 `com.geely.snc.digitalkey.service.DigitalKeyBgService` 的源码中（行号 `246~275`）：
```java
if (DkWalletManager.getInstance().isWalletBind()) {
    // 吉利认为：既然你绑定了 vivo 钱包车钥匙，吉利自己的后台保活就可以停掉了！
    stopKeepAlive(); 
    unregisterScreenUnlockReceiver();
}
```
- **致命后果**：一旦你把车钥匙添加到了 vivo 钱包，`isWalletBind()` 返回 `true`，吉利银河 App 就会**主动关闭自己的 `MiuiService` 双进程保活和亮屏解锁唤醒广播 (`ACTION_USER_PRESENT`)**！
- 而当你在吉利 App 里查看状态或依赖吉利 App 的蓝牙通道时，它的后台服务早已休眠或断链，导致靠近车辆毫无反应。

### 根因 C：Android 14~16 对 `PendingIntent` 被动扫描的节流冻结
- 在息屏超过 30 分钟后，OriginOS / HyperOS 会降低普通 App 的 `PendingIntent` 蓝牙广播投递频率，如果吉利没有拿到 `deviceidle whitelist` 和 `RUN_ANY_IN_BACKGROUND` 豁免，即使扫到广播也无法及时在后台完成 GATT 握手鉴权。

---

## 三、实车测试复盘（`19:27:23`–`19:31:24` 靠近车辆到达解锁阈值未触发解锁的 5 大根因与修复）

通过对实车（VIN: `LB378LEZ0XX000123`，BLE 名称: `GeelyVehicle123`，MAC: `AA:BB:CC:77:88:99`）测试期间的 `logcat` 完整抓包与 `dk_core.dex` / `dk_icce.dex` 字节码追踪，定位出首次实车测试未触发解锁的 5 个具体根因：

### 1. 车机 BLE 外设为「单连接槽位」模式，抢先发起 `connectGatt` 导致车机停止广播 4 分钟
- **实测日志证据**：
  - `19:27:23.816`：`KeyRadarService` 扫到第一帧车机广播（`AA:BB:CC:77:88:99`，`-88 dBm`），随即在 `checkAndAutoWakeGeelyOnApproach()` 中调用了 `carDevice.connectGatt(...)`。
  - `19:27:24.339`：`KeyRadarService` 的 `activeGatt` 连上车机（`status=0, newState=2`）。
  - **关键现象**：从 `19:27:24.339` 到 `19:31:24.482` 整整 4 分钟内，车机因唯一的外设 GATT 槽位被占用而**完全停止发送 BLE 广播**！
  - 结果吉利银河 App (`com.geelygalaxy.customer`) 的 `BleScanner` 持续报 `BleScan Failure, errorCode: 2, Scan timeout`，无法扫到车机，也无法建立数字钥匙鉴权连接。
- **修复方案**：
  - 靠近车辆阶段（当吉利银河尚未完成连接鉴权时），`KeyRadarService` **绝不主动调用 `connectGatt` 抢占车机连接槽位**，而是将实时扫到的 `ScanResult` 直接注入给吉利银河的 `MiuiService`。
  - 仅在吉利银河已连接（或车机因被银河连接而停止广播超过 `3.2s`）后才挂载 `activeGatt` 读取连接态 RSSI；且内置 **5.5 秒安全释放阀**：若挂载 `activeGatt` 满 5.5 秒但银河仍未完成鉴权，立即主动断开 `activeGatt` 冷却 4.5 秒，让车机恢复广播供银河重连。

### 2. `Binder` 线程直接回调 UI 导致 `CalledFromWrongThreadException` 中断首次 `readRemoteRssi()`
- **实测日志证据**：
  - `19:27:24.339`：`BluetoothGattCallback.onConnectionStateChange` 运行在 `Binder:6302_2 (TID 7013)` 线程，调用 `appendLog` -> `uiListener.onLogUpdated` -> `MainActivity.java:446` 直接操作 `TextView`，抛出 `android.view.ViewRootImpl$CalledFromWrongThreadException`，导致下一行 `gatt.readRemoteRssi()` 被异常中断。
- **修复方案**：
  - 在 `KeyRadarService.appendLogRaw()`、`onServiceBindChanged()`、`onVehicleBleStateChanged()` 中统一检查 `Looper.myLooper() == Looper.getMainLooper()`，非主线程一律通过 `handler.post(...)` 投递到主线程执行。

### 3. 小米 HyperOS 上 `bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)` 返回空列表导致连接态 RSSI 停更
- **实测日志证据**：
  - 在 `pollConnectedGattRssi()` 中，旧逻辑将 `activeGatt.readRemoteRssi()` 放在 `if (! bluetoothManager.getConnectedDevices(BluetoothProfile.GATT).isEmpty())` 内部。
  - 在小米 HyperOS 上，該系统 API 对 BLE 客户端连接返回空列表，导致 `19:27:23.816` 之后再也没有调用过一次 `readRemoteRssi()`，8 秒后 `signalFresh` 变为 `false`，雷达信号定格在 `-88 dBm`（显示“等待车辆蓝牙信号...”），哪怕站在车门边也无法触发 `-63 dBm` 解锁状态机。
- **修复方案**：
  - 移除 `getConnectedDevices(BluetoothProfile.GATT)` 前置门控，只要 `activeGatt != null && activeGattConnected` 即每 `450ms` 直接调用 `activeGatt.readRemoteRssi()`。

### 4. `MiuiService.onStartCommand` 缺少 `"setCarId"` 与 `"ScanResult"` Extra 参数
- **实测日志与字节码证据**：
  - 日志报错：`MiuiService onStartCommand error: 小米扫描的vin码或者scanResult为空！`。
  - 逆向 `dk_icce.dex` (`MiuiService.java` @ `0x7ed864`) 与 `dk_core.dex` (`VehicleV2BleStrategy.kt` @ `0x5efafc`) 确认完整调用链：
    `MiuiService.onStartCommand(intent)` 读取 `intent.getStringExtra("setCarId")` 和 `intent.getParcelableExtra("ScanResult")` $\rightarrow$ `ThirdAdapterApi.connectBle(scanResult, callback)` $\rightarrow$ `VehicleV2BleStrategy.subConnectBle(scanResult)` $\rightarrow$ `CarOrderBusiness.INSTANCE.connectBle(scanResult)` $\rightarrow$ `DigitalKey.INSTANCE.connectVehicle(scanResult)` $\rightarrow$ `DKConnector.INSTANCE.connectVehicle(userId, vin, version, scanResult)`。
- **修复方案**：
  - `GeelyAidlBridge.wakeAndConnectGeelyBle(reason, latestScanResult)` 在启动 `MiuiService`（同时覆盖 `"com.geely.consumer.miuiservice.action"` 与 `"com.geely.geometry.miuiservice.action"`）及 `MiuiReceiver` 时，显式填入 `putExtra("setCarId", currentVin)` 与 `putExtra("ScanResult", resolvedScanResult)`，使吉利银河无需自行扫描即可直接拿我们扫到的 `ScanResult` 发起 V2 蓝牙鉴权连接。
  - 同时在 `sendFastControl()` 中增加**握手补偿重发保障**：若触发解锁/上锁瞬间银河正在握手中，自动在 `+1.3s` 与 `+2.8s` 补发指令，确保走快时也能稳妥解锁。

### 5. 实车 V2 广播帧多特征全兼容匹配
- 实车 `LB378LEZ0XX000123` 的 BLE 广播包含：
  - **厂商自定义数据 (`ManufacturerSpecificData`)**：Company ID `0x01FE` (`510`)，18 字节载荷 `[6, 2, 0, 20, 52, 53, -83, 0, 75, 3, 60, 55, 60, 53, 53, 59, 62, 49]`，其中 `payload[0]=6 (UAES)`、`payload[1]=2 (V2)`、`payload[4..5]="45"`（VIN 末两位 ASCII）；
  - **服务 UUID**：`0000fdfd-0000-1000-8000-00805f9b34fb` (`0xFDFD`)；
  - **设备名 (`mDeviceName`)**：`"GeelyVehicle123"`（VIN 末三位）。
- `KeyRadarService` 已升级为四重特征匹配（`0x01FE` 厂商数据 + `0xFDFD` 服务 UUID / `GeelyVehicle123` 名称 + `15FF` 原始字节 + 已知车机 MAC `AA:BB:CC:11:22:33` / `AA:BB:CC:77:88:99`）。

---

## 四、第二次实车测试复盘（`12:57:25`–`12:57:40` 听到车上钥匙连接提示音但实际未解锁、App 显示“已解锁”的 2 大根因与修复）

在修复了第一次实车测试的槽位抢占问题后，第二次实车测试（`12:57`）成功在靠近车辆时唤醒了吉利银河并连上了车机（车端发出了蓝牙钥匙连接提示音，雷达也实时读到了 `-57 ~ -71 dBm` 的连接态 RSSI），但车门实际并未解锁。通过比对 `GeelyKeyRadar` 与吉利银河 `ICC_CB_Alive` 的毫秒级日志，定位出以下两个深层根因：

### 1. V2 蓝牙握手鉴权耗时 `3.07 秒`，而旧版补偿重发在 `2.8 秒` 前被 `isEffectivelyConnectedToCar()==false` 提前截断
- **毫秒级实测时间线还原**：
  - `12:57:25.270`：`KeyRadarService` 扫到车机 `AA:BB:CC:11:22:33` (`-71 dBm`)，立即注入 `ScanResult` 调用 `MiuiService` $\rightarrow$ `VehicleV2BleStrategy.subConnectBle()` 开始连接车机。
  - `12:57:25.814`（`+0.54s`）：平滑信号达到 `-63.0 dBm`（满足 $\ge -63\text{ dBm}$），状态机切换为 `UNLOCKED`（UI 显示“已解锁”），并发出第 1 次解锁指令——**此时银河 V2 蓝牙才刚开始建链 0.5 秒，尚未完成鉴权，指令无效**。
  - `12:57:27.115`（`+1.84s`）：触发 `+1.3s` 握手补偿重发 #1——**此时银河 V2 蓝牙建链进行到 1.8 秒，仍未完成鉴权，指令再次落空**。
  - `12:57:28.347`（**`+3.07s`**）：吉利银河打印 `ICC_CB_Alive: v2 isConnectDevice true latest CONNECT`，车机 V2 蓝牙鉴权正式完成，**此时车主在车旁听到了车上发出的钥匙连接成功提示音**！
  - `12:57:28.615`（`+3.34s`）：原本 `+2.8s` 的补偿重发 #2 定时器到期，但旧代码写了门控判断 `if (isEffectivelyConnectedToCar())`；由于 `isEffectivelyConnectedToCar()` 只检查了 V1 的 `DigitalKeyBgService` 绑定状态与通知栏文本，在 V2 架构下**永远返回 `false`**，导致 `12:57:28.615` 的重发被直接跳过！在 `12:57:28.347` 车机真正连上之后，**没有任何一条解锁指令被发出**。
- **修复方案**：
  - 移除 `sendFastControl()` 中对补偿重发的 `isEffectivelyConnectedToCar()` 前置拦截，改为按序列号 (`fastCtrlSeq`) 无条件在 **`+1.2s, +2.5s, +3.6s, +4.8s, +6.2s`** 执行 5 轮阶梯护航重发，完整覆盖 `3.07s` 的 V2 鉴权窗口。
  - 同时扩展 `dispatchFastControlOnce()` 下发通道，每次并列触发：通知栏签名 `PendingIntent`、AIDL、`com.snc.dk.rv.notification.ctrl`、导出接收器 `com.geely.cockpit.ble.RemoteViewClick` 以及桌面小组件确认接收器 `WgProviderMiddle` (`com.geely.galaxy.customer.widget.event.CONFIRM`)。

### 2. `isEffectivelyConnectedToCar()` 未识别 V2 ACL 连接态，导致每 `5.5 秒` 误触发“释放槽位后重连”
- **实测日志证据**：
  - `12:57:28.347` 银河 V2 连上车机后，车机停止广播；3.2 秒后（`12:57:32.337`）我们的 `activeGatt` 正常挂载到 `AA:BB:CC:11:22:33` 并开始每 450ms 读取 `GATT连接态直读` RSSI。
  - 然而因为 `geelyConnected` (`isEffectivelyConnectedToCar()`) 误判为 `false`，5.5 秒后（`12:57:37.902`）触发了旧版安全阀：`🔄 银河尚未完成鉴权，主动释放 GATT 读信号槽位 4.5s 让车机恢复广播供银河连接`，并再次调用 `wakeAndConnectGeelyBle("释放槽位后重连")`！
  - 这导致每过 5.5 秒就会向已经连好的 `VehicleV2BleStrategy` 重复轰炸一次 `subConnectBle`，干扰已建立的 V2 会话。
- **修复方案**：
  - 在 `GeelyAidlBridge` 中新增 `v2AclConnected` 状态位；当车机停止广播后 `activeGatt` 成功连上车机 ACL 链路时，立即调用 `aidlBridge.setV2AclConnected(true)`，使 `isEffectivelyConnectedToCar()` 正确返回 `true`（状态码 `2`）。
  - 移除 `pollConnectedGattRssi()` 中每隔 5.5 秒主动断开 `activeGatt` 并重发 `wakeAndConnectGeelyBle` 的逻辑（因为 `activeGatt` 本就只在车机停止广播 $\ge 3.2\text{s}$ 即银河已占住连接槽位后才会挂载）。


