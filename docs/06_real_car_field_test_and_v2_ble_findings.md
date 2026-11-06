# 06. 实车联调总结：UAES V2 蓝牙协议、连接态共享与快速逆向方法论

本文档专门汇总在实车（车架号 `LB378LEZ0XX000123`、蓝牙设备名 `GeelyVehicle123`）两轮现场测试中摸索出的关键底层机制、排障经验以及大型加固 DEX 的快速检索方法，避免后续调试重复踩坑。

---

## 一、本车 (`LB378LEZ0XX000123`) 蓝牙钥匙底层全貌

| 维度 | 实测结论与参数 |
| :--- | :--- |
| **协议代际** | **UAES V2 数字钥匙架构**（联合汽车电子 Vender = `6`, Version = `2`） |
| **核心管理类** | `com.geely.icc.common.business.alive.ble.VehicleV2BleStrategy`（位于 `dk_core.dex`） |
| **车控业务类** | `com.geely.icc.common.business.CarOrderBusiness` / `com.geely.digitalkey.sdk.DigitalKey` |
| **广播设备名** | `"GeelyVehicle123"`（`"GeelyVehicle"` + VIN 后 3 位） |
| **广播 Service UUID** | `0000fdfd-0000-1000-8000-00805f9b34fb` (`0xFDFD`) |
| **广播厂商数据** | Company ID = `0x01FE` (`510`)，Payload 18 字节：`[6, 2, 0, 20, 52, 53, ...]`（第 4~5 字节 `52, 53` 即 ASCII `"45"`，对应 VIN 末 2 位） |
| **车机 BLE MAC** | 当前实测为 **`AA:BB:CC:11:22:33`**（早期曾出现 `AA:BB:CC:77:88:99`、`AA:BB:CC:44:55:66`，车机蓝牙模块重启可能更换静态随机地址，因此必须优先按 `0x01FE` + `0xFDFD` + `GeelyVehicle123` 识别） |
| **外设连接槽位限制** | **单连接槽位**：只要任意一个 GATT Client 连上车机，车机立即停止发送 BLE 广播；断开后才恢复广播 |
| **V2 握手鉴权耗时** | 从调用 `VehicleV2BleStrategy.subConnectBle(ScanResult)` 到车机完成双向鉴权并响起钥匙连接声（日志 `v2 isConnectDevice true latest CONNECT`）恒定耗时约 **`3.07 秒`** |

---

## 二、五大核心技术壁垒与实测解决方案

### 壁垒 1：为什么我们的 App 不能在扫到广播时立即调用 `connectGatt`？
- **现象**：第一轮实测（`19:27`）中，`KeyRadarService` 在 `-88 dBm` 扫到第一帧广播时立即调用了 `carDevice.connectGatt()`。
- **后果**：车机外设只有一个连接槽位，被我们的未鉴权 GATT 连接占住后，车机立即**停止广播长达 4 分钟**；吉利银河 App 扫不到车机广播（报 `BleScan Failure, errorCode: 2, Scan timeout`），永远无法连上车钥匙。
- **正确做法（零槽位抢占 + 延迟共享 ACL）**：
  1. **扫描期（车机仍在发广播）**：绝不调用 `connectGatt()`，而是将扫到的 `ScanResult` 与 `setCarId` 通过 `MiuiService` 直接喂给吉利银河，让吉利银河去占这个连接槽位并完成 V2 鉴权。
  2. **连接期（车机停止广播 $\ge 3.2\text{s}$）**：当检测到车机广播停止超过 `3.2 秒`（说明吉利银河已经连上车机并建立了底层蓝牙 ACL 物理链路），此时再调用 `targetDev.connectGatt()` 挂载到**同一条已建立的 ACL 物理链路**上，仅调用 `readRemoteRssi()` 读取实时信号强度。

### 壁垒 2：为什么连接态挂载后不能每 5.5 秒主动断开重连？
- **现象**：第二轮实测（`12:57`）中，`GeelyAidlBridge.isEffectivelyConnectedToCar()` 只判断了 V1 的 `DigitalKeyBgService` 状态，在 V2 架构下恒为 `false`。
- **后果**：`pollConnectedGattRssi()` 里的 5.5 秒超时逻辑误以为“银河还没连上”，每 5.5 秒就执行一次 `closeActiveGatt()` + `wakeAndConnectGeelyBle("释放槽位后重连")`，不断向已经连好的 `VehicleV2BleStrategy` 重复发起重连请求。
- **正确做法**：
  - 当车机停止广播后 `activeGatt` 成功连上车机 ACL 链路时，立即调用 `aidlBridge.setV2AclConnected(true)` 同步 V2 在线状态；
  - 在 `activeGattConnected == true` 期间持续每 450ms 调用 `activeGatt.readRemoteRssi()`，绝不主动断开或重发 `wakeAndConnectGeelyBle`。

### 壁垒 3：为什么听到车上钥匙连接的声音了，但车门没解锁？
- **现象**：第二轮实测（`12:57`）中，人在 `12:57:25.27` 走近车辆触发唤醒，在 `12:57:25.81`（仅隔 `0.54s`）走到车门边达到 `-63 dBm` 触发解锁；而车机 V2 蓝牙直到 `12:57:28.347`（**`+3.07s`**）才完成鉴权并发出连接提示音。
- **后果**：`+0s` 和 `+1.3s` 发出的解锁指令都在 V2 鉴权完成前被丢弃，而 `+2.8s` 的重发不仅早于 `3.07s`，还被 `if (isEffectivelyConnectedToCar())` 拦截跳过，导致 `12:57:28.347` 鉴权成功后没有任何解锁指令发出。
- **正确做法（跨越 3.07 秒鉴权窗口的 5 阶护航重发）**：
  - `sendFastControl()` 触发解锁或上锁时，除立即下发第 0 次外，无条件调度 **`+1.2s`、`+2.5s`、`+3.6s`、`+4.8s`、`+6.2s`** 共 5 轮阶梯重发（若有新指令则通过 `fastCtrlSeq` 自动取消旧队列）。
  - 这样无论车主走得快（`0.5s` 就到车门边，等 `3.07s` 鉴权完成）还是走得慢（远距已提前完成鉴权，到车门边 `+0s` 即刻响应），都能保证车机在鉴权就绪后的第一时间执行解锁。

### 壁垒 4：小米 HyperOS / vivo OriginOS 系统兼容性陷阱
1. **`BluetoothManager.getConnectedDevices(BluetoothProfile.GATT)` 返回空列表**：
   - 在 HyperOS 上，即使 `BluetoothGatt` 已处于 `STATE_CONNECTED`，`getConnectedDevices(GATT)` 依然返回空集合 `[]`。因此轮询连接态 RSSI 时绝不能以 `getConnectedDevices(GATT)` 作为前置条件，直接依据 `activeGatt != null && activeGattConnected` 调用 `activeGatt.readRemoteRssi()`。
2. **`BluetoothGattCallback` 运行在 Binder 线程池**：
   - `onConnectionStateChange` 和 `onReadRemoteRssi` 均在 `Binder:xxxx` 线程回调，任何触发 UI 更新或 `TextView` 刷新的操作必须先通过 `Looper.getMainLooper()` `Handler` 切换回主线程，否则会抛出 `CalledFromWrongThreadException` 并中断后续蓝牙调用。

---

## 三、大型加固 DEX 极速检索规范（严禁全文扫描）

吉利银河脱壳后的 `geely_sub_0.dex` / `dk_core.dex` / `dk_icce.dex` 反汇编文本高达数百万行（100MB+）。在后续排查任何新方法或广播时，遵循以下 **「三步秒级定位法」**：

1. **第一步：先用 `grep -n` 精确匹配类名或日志关键字行号**
   ```bash
   # 示例：0.1秒定位 VehicleV2BleStrategy 所有方法定义行号
   grep -n "com.geely.icc.common.business.alive.ble.VehicleV2BleStrategy\." /private/tmp/dk_core_dump.txt
   ```
2. **第二步：用 `sed -n '起始行,结束行p'` 仅读取目标方法的 50~150 行字节码**
   ```bash
   # 示例：仅读取 fastConnectBle 方法体（1289160~1289260行）
   sed -n '1289160,1289260p' /private/tmp/dk_core_dump.txt
   ```
3. **第三步：用 `aapt2 dump xmltree` 检索导出组件 (`exported=true`)**
   ```bash
   /opt/homebrew/share/android-commandlinetools/build-tools/36.0.0/aapt2 dump xmltree /path/to/car_key_re/geely_galaxy.apk --file AndroidManifest.xml | grep -B 4 -A 15 'android:exported.*=.*true'
   ```
