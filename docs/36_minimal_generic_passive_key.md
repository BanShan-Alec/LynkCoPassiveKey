# 36. v4.0 极简通用无感钥匙：只保留息屏靠近解锁 / 离开上锁

## 1. 目标

把原「银河钥匙雷达 v3.20.6」裁剪成一个功能极简、可通用分发的 App：

- 只保留：**息屏靠近解锁、离开上锁**；
- 不要求用户填写 VIN、MAC、车型或任何逆向参数；
- 不依赖电脑 adb、REA、私有数据或 root；
- 用户安装 App、用银河官方 App 连接一次车辆后，本 App 自动识别本车蓝牙身份；
- 保留原包名 `com.luca.geelykeyradar`，升级安装后通知监听授权可以继续沿用。

当前版本：`v4.0.0-min / versionCode 100`。

---

## 2. 删除的功能

以下 v3.x 功能已从新源码移除：

- 手动解锁/上锁按钮；
- 摇一摇解闭锁；
- 电源键三连、音量键、无障碍按键；
- 快捷设置磁贴；
- 桌面 4x2 组件、组件二次确认、组件设置；
- 总里程、胎压、行程记录、油电续航折扣；
- 哨兵模式、语音播报、提示音/震动；
- 车机侧 PE 参数读写、备份恢复；
- 锁态云端/本地仲裁、0x0121 锁态强一致；
- GPS 车速监控、A/C 充电判断、A2DP 行驶判定；
- 手动“一键唤醒/刷新锁态”等调试入口。

旧版源码与 APK 归档在：

```text
GeelyKeyRadarApp/legacy/v3.20.6/
```

---

## 3. 新代码结构

```text
GeelyKeyRadarApp/src/com/luca/geelykeyradar/
├── PassiveKeyService.java          # 前台服务：BLE 扫描、身份学习、RSSI、状态机、GATT、WakeLock
├── PassiveKeyLogic.java            # 纯逻辑：靠近一次解锁、远离驻留上锁、断连不误锁
├── RssiFilter.java                 # 中值限幅 + Kalman + 非对称 EMA
├── GeelyAdvertisement.java         # 解析 0x01FE / 0xFDFD / GeelyVehicle 名称 / 15FF
├── VehicleIdentity.java            # 本车蓝牙身份与匹配规则
├── VehicleIdentityStore.java       # 身份持久化 + 两个自动开关
├── GeelyBridge.java                # 最小化调用银河官方导出组件
├── KeyNotificationBridgeService.java # 读取官方通知状态 + 点击官方解锁/上锁按钮
├── MainActivity.java               # 极简配置页：状态、学习、权限、开关、日志
└── BootReceiver.java               # 开机/更新自动拉起
```

---

## 4. 通用化：如何在不读私有数据的情况下识别本车

### 4.1 广播识别

`GeelyAdvertisement` 兼容以下常见吉利数字钥匙广播特征：

| 特征 | 用途 |
| :--- | :--- |
| Company ID `0x01FE`（510） | UAES/吉利 V2 厂商数据；第 4~5 字节可提取 VIN 后两位 |
| 设备名 `GeelyVehicle<VIN后三位>` | 最稳定的跨车型身份之一 |
| Service UUID `0xFDFD` + 吉利设备名 | 部分车型/固件只暴露服务 UUID |
| 原始 `15FF` 厂商帧 | 老平台/V1 广播兜底 |

**息屏扫描关键点（参考开源项目 `borconi/openzeekr` Issue #21 与 AOSP `ScanManager`）**：
- Android 在屏幕熄灭时会直接挂起无 `ScanFilter` 的扫描（`Cannot start unfiltered scan in screen-off`）；
- 因此 `PassiveKeyService.buildHardwareScanFilters()` 默认使用通用硬件过滤（Company ID `0x01FE`、UUID `0xFDFD`、Company ID `0x06FE`，以及已学习的本车名称/MAC），既不需要写死任何车型参数，又能保证息屏状态下 BLE 扫描不被系统冻结。

解析结果只保存广播里本来就公开的字段：

- BLE 地址；
- 广播名称；
- VIN 后两位/后三位；
- 厂商数据前 8 字节签名。

**不保存 VIN 全码、账号、token、证书或吉利私有存储。**
运行时不读取 `/sdcard/Android/data/com.geelygalaxy.customer/`、官方 App 私有 SharedPreferences/MMKV 或 REA 导出的内存数据。

### 4.2 本车身份自动学习

学习时机与多通道来源（无需用户手工配置，也支持可选填入车架号后 3 位）：

1. **系统级 BLE GATT / ACL 广播监听**：监听 `android.bluetooth.gatt.profile.action.CONNECTION_STATE_CHANGED`（含 `bd_addr`）与 `ACTION_ACL_CONNECTED`，当银河 `:dkservice` 连上车机时 0 秒捕获车机当前 BLE MAC（即使车机刚轮换过静态随机地址且已停止广播也能捕获）；
2. **系统 `BluetoothManager.getConnectedDevices(GATT)`**：匹配 `GeelyVehicle*` 名称；
3. **近场单一吉利广播自动锁定**：未学习状态下若扫到唯一 `GeelyVehicle*` / `0x01FE` 广播（`>= -88 dBm`），直接自动学习并唤醒银河；
4. **本机旧版配置无感迁移**：若检测到本机旧版 SharedPreferences 已有车辆尾号/MAC，首次启动自动迁移；
5. **可选手动输入车架号后 3 位**：UI 提供可选按钮，输入 3 位尾号（如 `123`）即可直接生成 `GeelyVehicle123` + 尾号 `23` 过滤。

学习后优先按以下顺序匹配：

1. BLE 地址完全一致；
2. 广播名称完全一致；
3. VIN 后三位 + 厂商数据签名一致；
4. VIN 后两位 + 厂商数据签名一致。

车机蓝牙模块重启导致 MAC 轮换时，名称/VIN 尾号仍能命中；下一次广播出现会自动更新持久化地址。

### 4.3 为什么不需要完整 VIN

对当前银河 1.56.1 反编译 `com.ecarx.blekey.thirdadapter.service.MiuiService` 后确认：

- `onStartCommand` 只要求 `setCarId` 非空、`ScanResult` 非空；
- `MiCarLinkInteractorImpl.connectDevice(carId, scanResultJson)` 把 `ScanResult` 交给官方钥匙 SDK；
- `BaseInteractor.checkDevice()` 默认返回 `true`，`carId` 只用于日志/回执标识；
- 真正的车辆身份校验、钥匙凭证和 V2 鉴权仍在银河官方 App 内完成。

因此本 App 传入广播名（如 `GeelyVehicle123`）作为非空 `carId` 即可，不需要、也不应该写死用户 VIN。

---

## 5. 运行流程

```text
BleScanner 扫描
  → 解析吉利广播
  → 身份未学习：固定一个主候选 12s，避免多车 RSSI 混流
  → RSSI ≥ -95dBm：把 ScanResult 注入官方 MiuiService / MiuiReceiver / GLDkConnectionService
  → 官方 App 完成 V2 鉴权并显示“已连接”
  → 本 App 自动学习本车身份
  → 车机停止广播 ≥3.2s 后，挂载同一条 ACL 的 GATT，500ms 读一次 readRemoteRssi()
  → 有效 RSSI ≥ -63dBm：触发一次“解锁”
  → 有效 RSSI ≤ -82dBm 连续 4.5s / ≥3 样本：触发“上锁”
```

车控路径（含 **DK1.5 指令反转映射** 与 **3.07s V2 鉴权窗口 5 阶护航重发**）：

```text
KeyNotificationBridgeService
  → inflate 官方通知 RemoteViews
  → 找到“解锁/上锁”按钮的 PendingIntent
  → PendingIntent.send() + 主线程 View.performClick() 双保险
  → 若银河尚在 V2 握手中：启动 +1.2s / +2.5s / +3.6s / +4.8s / +6.2s 护航重发
  → 兜底广播 com.snc.dk.rv.notification.ctrl 使用 DK1.5 真实枚举 (Unlock=2, Lock=1)
```

**防罚站双触发机制**：
- 吉利车机只有 **1 个 BLE 连接槽位**，一旦银河 App 抢先连上车机，车机立即停止发送广播。如果此时人还没走到 `-63 dBm` 以内，单靠广播 RSSI 会因为“广播消失”而漏触发。
- 因此当银河蓝牙钥匙刚变为“已连接”（`onOfficialConnected`）且本次接近尚未解锁时，会**立即触发一次解锁**，并在离开断连满 12 秒无近场信号后自动重新武装（`onTick`）。

通知按钮点击等于用户手动点击官方通知栏按钮，不依赖车机“靠近解锁”被动 PE 开关。

---

## 6. 状态机与安全约束

`PassiveKeyLogic` 的核心规则：

| 规则 | 说明 |
| :--- | :--- |
| 服务总开关 | 支持在 App 内一键关闭「无感进出服务总开关」，关闭后立即停止扫描、断开 GATT、取消所有待发重发任务 |
| 插充电线禁控 | 默认开启「插数据线/充电线后禁止解闭锁」：检测到 USB/AC 有线充电时（无线充电不拦截），禁止一切自动解锁与上锁 |
| 解锁（支持自定义） | 默认有效 RSSI ≥ `-75 dBm`（实车验证最佳 3 米提前解锁阈值，可在 App 内自定义），一次接近只触发一次 |
| 上锁（支持自定义） | 默认有效 RSSI ≤ `-82 dBm`（约 7~8 米），连续 `4.5s`（可自定义）且至少 3 个新样本；首次点中通知栏按钮后不再重复护航重发，杜绝二次上锁 |
| 无信号过期清零 | 超过 6 秒无新 RSSI 样本立即回退显示 `-- · 不在车辆附近 (无信号)`，不再残留断连前的最后一条弱信号（如 `-93 dBm`） |
| 固定日志容量 | 底部日志仅记录区间跨越与关键事件（不再每 0.5~3s 刷屏），UI 固定最多显示最近 10 条（最新在上），支持一键清空 |
| 近场保护 | 最近 6 秒内仍处于 `-78 dBm` 以上，不允许开始上锁驻留，避免车内多径深衰落误锁 |
| 冷启动 | 从未接近过车辆时，一直在远处不会补发上锁 |
| 断连 | GATT/扫描失败立即清空驻留，并禁止自动上锁 10 秒 |
| 蓝牙关闭 | 禁止自动上锁 15 秒，避免把蓝牙端口重启当成“人已走远” |
| 行车 | 银河通知明确显示“行驶中”时，不上锁、不重新武装；也不下发解锁 |
| 重新武装 | 解锁后必须“持续远场 8 秒 + 银河已断开”才允许下一次接近解锁 |

滤波：中值限幅（±14dB）+ Kalman（q=0.08, r=2.2）+ 非对称 EMA（靠近 0.48 / 远离 0.16）+ 40dB/s 限速。手机距离传感器被遮挡时额外 `+8dBm`，抵消口袋/人体衰减。

息屏保活：

- `connectedDevice` 前台服务；
- 屏幕关闭且最近 5 分钟见过车辆/已连接时持有 `PARTIAL_WAKE_LOCK`；
- 屏幕关闭后 45 秒无广播、银河未连接时重建一次 BLE 扫描器（90 秒节流）；
- 通知监听服务掉线时每 60 秒请求系统重绑；
- 开机、应用更新自动拉起。

---

## 7. 权限

必需：

- 蓝牙扫描/连接（Android 12+：`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`；Android 11 及以下：精确位置）；
- 通知使用权（系统设置 → 通知使用权 → 银河无感钥匙）；
- 电池优化白名单。

建议：

- OriginOS/vivo：设置 → 电池 → 后台耗电管理 → 允许后台高耗电；
- i管家 → 自启动 → 允许本应用。

没有通知使用权时，App 仍能扫描和显示信号，但无法点击官方通知栏的解锁/上锁按钮。

---

## 8. 首次使用

1. 安装并登录银河官方 App，确认蓝牙钥匙已绑定；
2. 安装 `GeelyPassiveKey.apk`；
3. 打开 App，授予蓝牙权限、通知使用权、电池白名单；
4. 靠近车辆，让银河官方 App 成功连接一次；
5. 本 App 日志出现：

```text
✓ 已自动识别本车蓝牙: GeelyVehicle123 · XX:XX:... · VIN尾号 123
✓ 银河蓝牙钥匙已连接
✓ 已挂载车机 ACL，开始读取连接态 RSSI
```

6. 之后保持本 App 后台运行即可。息屏放口袋，靠近解锁，离开上锁。

换车或更换蓝牙模块后，点「重新学习本车」，再用银河 App 连接一次。

---

## 9. 构建与测试

```bash
bash /path/to/car_key_re/GeelyKeyRadarApp/build.sh
```

输出：

```text
/path/to/car_key_re/GeelyKeyRadarApp/GeelyPassiveKey.apk
```

脚本会先运行纯逻辑单测 `PassiveKeyLogicTest`，再编译资源、Java、DEX、签名，并在检测到真机时尝试安装。

实车调试：

```bash
adb logcat -s PassiveKey GeelyBridge KeyNotifBridge
adb shell run-as com.luca.geelykeyradar cat files/passive_key.log
```

单测覆盖：

- 一次接近只解锁一次；
- 远离驻留/样本数不足不上锁；
- 冷启动远处不补锁；
- 断连不误锁；
- 真正离开后重新武装；
- 行车中不上锁、不发解锁；
- 中间缓冲带取消上锁倒计时；
- 车内近场保护窗口内不误锁。

---

## 10. 已知边界

1. **必须有银河官方 App**：车钥匙凭证、V2 鉴权和通知栏按钮都来自官方 App；本 App 只做雷达和桥接。
2. **通知栏控件依赖官方版本**：当前按资源 ID `0x7f0a10c7/0x7f0a10cb` + 文本“解锁/上锁”双路径查找；官方大版本改版后可能需要更新 ID。
3. **首次学习需要官方 App 连接一次**：如果官方 App 在后台已经被系统冻结，先手动打开一次。
4. **多辆吉利车相邻**：身份未学习时只让一个主候选进入 RSSI 流；官方 App 的钥匙 SDK 仍会做最终车辆校验。若注入错车，官方 App 不会鉴权成功，本 App 会在主候选变化后重试。
5. **ROM 差异**：部分 ROM 息屏会暂停 BLE 扫描；App 会重建扫描器，但仍建议按第 7 节设置后台高耗电。

---

## 11. 与旧版的关系

- 包名不变：`com.luca.geelykeyradar`；
- 通知监听组件名不变：`KeyNotificationBridgeService`，升级安装可保留授权；
- 旧版源码/资源/APK 在 `GeelyKeyRadarApp/legacy/v3.20.6/`；
- 旧版文档 `docs/01` ~ `docs/35` 仍保留，作为逆向知识与排查参考；
- 新版只覆盖 `GeelyKeyRadarApp/src`、`res`、`AndroidManifest.xml`、`build.sh`。
