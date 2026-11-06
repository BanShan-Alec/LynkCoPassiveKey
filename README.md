# 银河无感钥匙 · GeelyPassiveKey

把吉利银河的「靠近解锁 / 远离上锁」重做一遍：**手机自己判断你在靠近还是离开，然后替你把银河 App 的蓝牙钥匙连上、把锁打开。**

车机里那个「无感进出」开关可以一直关着，本 App 不依赖它。

[⬇️ 下载 APK](https://github.com/yangyongyongyong/GeelyPassiveKey/releases/latest) · [v4.0 设计说明](docs/36_minimal_generic_passive_key.md) · [逆向知识库](docs/)

---

## 一、解决什么毛病

银河原生的靠近解锁 / 远离上锁，实车用起来两个高频事故：

### 1. 罚站

人已经走到车门边，车不解锁。掏手机 → 打开银河 App → 等它连蓝牙钥匙 → 再点解锁。后面排队的人看着你，你在车门口站着。

### 2. 车旁就被锁

停好车绕到车尾插充电枪，或者去后备箱拿个东西，离开驾驶位没几步，车「咔」一声自己锁了。想再打开还得从头来一遍。

**根因**：车机侧的被动 PE 只有单点 RSSI 阈值，判定粗糙——没有「一次接近只解锁一次」，没有近场保护，也没有驻留时间。而且吉利车机**只有一个 BLE 连接槽位**：银河 App 一旦连上，车机立刻停发广播，纯靠广播 RSSI 的判定当场踩空，人自然就罚站了。

---

## 二、我们怎么做（核心）

**不需要车机开启「靠近解锁 / 远离上锁」。** 车机侧的 `0x0501` 无感 PE 总开关可以一直关着；本 App 不碰它，也不写任何车机参数。

本 App 只做两件事：

**① 自己识别** —— 扫吉利车机的 BLE 广播（Company ID `0x01FE`、`GeelyVehicle<VIN后三位>`、`FDFD` 等特征），滤波后按 RSSI 判断靠近 / 远离；银河连上后改用连接态 `readRemoteRssi()` 继续判断。

**② 调用银河 App 的蓝牙钥匙** ——

- **蓝牙钥匙连接**：把扫到的车机广播注入银河官方 `MiuiService` / `DigitalKeyBgService`，官方钥匙 SDK 自己完成 V2 鉴权、自己连车。
- **蓝牙钥匙解锁 / 上锁**：找到银河常驻通知栏里的「解锁 / 上锁」按钮，按下它的 PendingIntent（失败时用 DK1.5 广播兜底）。

所以整条链路严格等效于：

```
你手动打开银河 App  →  等它连上蓝牙钥匙  →  下拉通知栏点「解锁」/「上锁」
```

只是这一串现在由本 App 在你息屏、手机在口袋里的状态下自动完成。

> **钥匙凭证、V2 鉴权、车控指令全部在银河官方 App 内完成。**
> 本 App 不生成钥匙、不伪造凭证、不读官方私有数据（SharedPreferences / MMKV / REA）、不需要 root、不需要 adb。

---

## 三、为什么这样反而不罚站

| 机制 | 作用 |
| :--- | :--- |
| **连接即解锁** | 银河蓝牙钥匙一变成「已连接」，本次接近立刻解锁一次，不等 RSSI 攒到阈值。车机停广播导致的踩空被直接绕开——**治「罚站」的关键**。 |
| **一次接近只解锁一次** | 进车后不会每 10~20 秒重复解锁；离开 8 秒且银河已断开 12 秒后才重新武装。 |
| **近场保护** | 最近 6 秒还在 `-78 dBm` 以上，不允许开始上锁倒计时 → **绕到车尾插枪不会被锁**。 |
| **上锁要驻留** | 默认 `≤ -82 dBm`（约 7~8 米）连续 4.5 秒、至少 3 个新样本才锁；在缓冲带里走动会取消倒计时。 |
| **断连不误锁** | GATT 断开 / 蓝牙重启后 10~15 秒内禁止自动上锁。宁可不锁，也不在你车旁误锁。 |
| **行车不动作** | 银河通知明确显示「行驶中」时，不解锁也不上锁。 |
| **插线禁控** | 手机插着有线充电（例如在车里用 USB 供电）时默认禁止自动解闭锁。 |

默认解锁阈值 `-75 dBm`（实车调过的提前解锁点）、上锁阈值 `-82 dBm`、驻留 `4.5 s`，都能在 App 里改。

---

## 四、v4.0 只做两件事

- ✅ 息屏靠近解锁
- ✅ 离开上锁

摇一摇、电源键三连、快捷磁贴、桌面组件、里程、胎压、哨兵、语音、充电门控、手动解闭锁……全部删除。旧版实现和它们的逆向记录仍留在 `docs/` 里备查。

不需要填 VIN、MAC 或车型：安装后用银河 App 正常连一次车，本 App 自动学习本车的蓝牙身份并记住。

---

## 五、安装

1. 银河官方 App 已登录、蓝牙钥匙已绑定（凭证在官方 App 里，本 App 不碰）；
2. 装 [GeelyPassiveKey.apk](https://github.com/yangyongyongyong/GeelyPassiveKey/releases/latest)；
3. 打开 App，授予：蓝牙权限、**通知使用权**、电池优化白名单；
4. 靠近车辆，让银河 App 成功连接一次；
5. 日志出现下面三行就成了：

```text
✓ 已自动识别本车蓝牙: GeelyVehicleXXX · XX:XX:... · VIN尾号 XXX
✓ 银河蓝牙钥匙已连接
✓ 已挂载车机 ACL，开始读取连接态 RSSI
```

之后保持后台运行，息屏放口袋即可。换车或换了蓝牙模块，点「重新学习本车」再连一次。

**没有通知使用权**时 App 仍能扫描、显示信号，但点不到官方通知栏的解锁按钮——这个权限是必需的。

- vivo / OriginOS：设置 → 电池 → 后台耗电管理 → 允许后台高耗电；i管家 → 自启动 → 允许。
- 其他 ROM 若息屏后暂停 BLE 扫描，本 App 会重建扫描器，但仍建议加白名单。

---

## 六、目录

```text
.
├── GeelyKeyRadarApp/
│   ├── build.sh                                # 单测 → 编译 → 签名 → 安装
│   ├── AndroidManifest.xml
│   ├── res/
│   ├── src/com/luca/geelykeyradar/
│   │   ├── PassiveKeyService.java              # 前台服务：扫描、学习、RSSI、GATT、保活
│   │   ├── PassiveKeyLogic.java                # 状态机：一次解锁、驻留上锁、断连不误锁
│   │   ├── GeelyBridge.java                    # 调官方 MiuiService / 通知控制兜底
│   │   ├── KeyNotificationBridgeService.java   # 读官方通知、点解锁/上锁按钮
│   │   ├── GeelyAdvertisement.java             # 吉利广播解析
│   │   ├── VehicleIdentity.java                # 本车蓝牙身份匹配
│   │   ├── VehicleIdentityStore.java           # 身份持久化 + 阈值开关
│   │   ├── RssiFilter.java                     # 中值 + Kalman + 非对称 EMA
│   │   ├── MainActivity.java                   # 极简配置页
│   │   └── BootReceiver.java                   # 开机 / 更新自动拉起
│   └── tests/                                  # 纯逻辑单测
└── docs/                                       # 逆向知识库 + 版本演进记录
```

APK 不入库（`.gitignore` 排除 `*.apk`），从 [Releases](https://github.com/yangyongyongyong/GeelyPassiveKey/releases) 下载。

---

## 七、文档

当前版本看 **36**；查协议看 **01**，查误锁根因看 **03**，查完整链路看 **18**。

| 文档 | 内容 |
| :--- | :--- |
| [36](docs/36_minimal_generic_passive_key.md) | **v4.0 极简版**：为什么不需要车机 PE 开关、通用身份学习、状态机与安全约束 |
| [01](docs/01_geely_ble_protocol_and_sdk.md) | 银河脱壳、`0x01FE` / `FF15` 广播格式、GATT UUID、AIDL、通知栏控件 ID |
| [02](docs/02_vivo_wallet_and_iccoa_key.md) | vivo 钱包 / ICCOA 开卡流程与测距公式 |
| [03](docs/03_root_causes_3m_lock_and_disconnect.md) | **3 米误锁、偶现不自动连蓝牙的源码级根因** |
| [06](docs/06_real_car_field_test_and_v2_ble_findings.md) | 实车联调：V2 3.07s 鉴权窗口、单槽位 ACL、5 米信号阶梯 |
| [18](docs/18_phone_key_workflow.md) | **手机钥匙完整链路图**（含 `docs/diagrams/` 流程图） |
| [07](docs/07_recent_reverse_findings_and_v2_2_features.md) | 逆向发现合集：DK1.5 枚举反转、车机 `0x0501` 开关真相、车辆状态枚举 |
| [08](docs/08_threshold_dwell_and_screen_off.md) | 阈值 + 驻留状态机，锁屏失效根因与保活 |
| [23](docs/23_v3_13_cabin_rssi_stabilizer.md) | 车内多径 RSSI 稳定化（Kalman / 中值 / 限速） |
| [26](docs/26_v3_16_ble_loss_no_false_lock.md) | 断连不误锁的取舍 |
| [30](docs/30_v3_20_1_own_car_ble_identity.md) · [34](docs/34_v3_20_5_target_duplicate_isolation.md) | 本车身份识别、多车隔离 |
| [35](docs/35_v3_20_6_approach_unlock_single_shot.md) | 一次接近只解锁一次 |
| [04](docs/04_geely_key_radar_app_architecture.md) · [05](docs/05_vivo_originos6_adb_optimization.md) · [09](docs/09_v2_4_shake_state_and_ui.md)–[29](docs/29_v3_19_accessibility_odometer_tire.md) | 旧版 v2.x–v3.x 功能与踩坑存档（摇一摇、组件、磁贴、里程、充电门控等，v4.0 已删除） |

---

## 八、构建

```bash
bash GeelyKeyRadarApp/build.sh
```

先跑 `PassiveKeyLogicTest`，再 `aapt2` → `javac` → `d8` → `apksigner`，检测到真机时自动 `adb install`。产物：`GeelyKeyRadarApp/GeelyPassiveKey.apk`。

调试：

```bash
adb logcat -s PassiveKey GeelyBridge KeyNotifBridge
adb shell run-as com.luca.geelykeyradar cat files/passive_key.log
```

---

## 九、已知边界

1. **必须有银河官方 App**：凭证、鉴权、车控按钮全在官方 App 里，本 App 只是「雷达 + 代点」。
2. **依赖官方通知栏控件**：按资源 ID + 「解锁/上锁」文本双路径查找，官方大改版后可能需要更新。
3. **首次要连一次车**：官方 App 被系统冻结时，先手动打开一次完成学习。
4. **多辆吉利车相邻**：未学习时只让一个主候选进 RSSI 流；即使注入错车，官方 SDK 也不会鉴权通过，本 App 会重试。
5. **ROM 差异**：部分 ROM 息屏会暂停 BLE 扫描，建议按第五节加后台白名单。

---

## 免责

仅供自己车辆个人使用。仓库内逆向内容为学习记录，不含钥匙凭证或账号信息。车控有风险，请自行确认安全边界。
