# 吉利银河 & vivo 钱包蓝牙车钥匙逆向工程与「银河无感钥匙」项目知识库

本仓库收录了针对 **吉利银河 App (`com.geelygalaxy.customer`)**、**vivo 钱包 (`com.vivo.wallet`)** 与 **vivo 智能车载 (`com.vivo.car.networking`)** 的完整逆向分析成果。

当前主应用已裁剪为 **「银河无感钥匙 (`com.luca.geelykeyradar` v4.0.0-min)」**：只保留 **息屏靠近解锁 / 离开上锁**，车辆蓝牙身份改为运行时自动学习，不再写死本车 VIN/MAC，也不再依赖电脑 adb 或 REA 私有数据。原功能繁多的 v3.20.6 源码、资源与 APK 已归档到 `GeelyKeyRadarApp/legacy/v3.20.6/`，其逆向结论仍保留在 `docs/01` ~ `docs/35`。

> 旧版 v3.20.6 曾围绕 **「车旁 3 米偶发误锁」**、**「后台偶现不自动连接蓝牙」**、**「锁屏后靠近解锁偶发失效/变慢」**、**「App 锁态与实车不一致」** 四大痛点做完整守护；这些逆向与排障记录仍可在旧文档中查阅。

> **目的**：所有逆向出的类路径、方法签名、BLE 广播结构、GATT UUID、车机标定公式、AIDL 接口编号、通知栏控件 ID 及问题根因均已归档在 `docs/` 下，后续迭代或排查问题时**无需再次从头脱壳、反编译 APK**。

---

## 🚗 v4.0 极简通用版（当前推荐）

**只做两件事：息屏靠近解锁、离开上锁。**

- 不再要求用户填写 VIN、MAC、车型或逆向参数；
- 用户安装 App、用银河官方 App 连接一次车辆后，本 App 自动识别本车蓝牙身份；
- 不读取银河私有数据，不依赖 adb / REA / root；
- 删除摇一摇、电源键、快捷磁贴、桌面组件、里程、哨兵、语音、充电门控、手动解闭锁等全部附加功能；
- 保留原包名 `com.luca.geelykeyradar`，升级安装可继续沿用通知监听授权。

构建与安装：

```bash
bash /path/to/car_key_re/GeelyKeyRadarApp/build.sh
```

输出 APK：

```text
/path/to/car_key_re/GeelyKeyRadarApp/GeelyPassiveKey.apk
```

完整设计、通用识别原理、权限与首次使用步骤见：

**[36_minimal_generic_passive_key.md](docs/36_minimal_generic_passive_key.md)**

---

## 📂 目录结构速览

```text
/path/to/car_key_re/
├── README.md                                  # 项目总览与快速索引（本文件）
├── docs/                                      # 分类逆向知识库与架构文档
│   ├── 01_geely_ble_protocol_and_sdk.md       # 吉利银河脱壳方法、V2(0x01FE)/V1(0xFF15)广播、GATT协议、AIDL与导出组件全集
│   ├── 02_vivo_wallet_and_iccoa_key.md        # vivo钱包 & vivo车载加钥匙流程、ICCOA协议与测距公式
│   ├── 03_root_causes_3m_lock_and_disconnect.md # 「3米误锁」、「偶现不自动连蓝牙」及两轮实车测试源码级根因分析
│   ├── 04_geely_key_radar_app_architecture.md # 「银河钥匙雷达」v2.5架构、V2鉴权窗口多阶重发、行车安全联锁与备份还原
│   ├── 05_vivo_originos6_adb_optimization.md  # iQOO Neo10 Pro (OriginOS 6) 6大无损优化清单与一键恢复命令
│   ├── 06_real_car_field_test_and_v2_ble_findings.md # 实车联调总结：UAES V2 3.07s鉴权时序、单槽位ACL共享与DEX极速检索规范
│   ├── 07_recent_reverse_findings_and_v2_2_features.md # 近期逆向核心发现（5m阶梯信号、DK1.5命令反转、0x0501车机真实参数备份、哨兵、混动行程打折率）
│   ├── 08_threshold_dwell_and_screen_off.md   # v2.3：删除方向性轨迹规则（纯阈值+持续时长）与锁屏失效/解锁慢根因修复
│   ├── 09_v2_4_shake_state_and_ui.md          # v2.4：锁屏摇一摇解闭锁、真实锁态一致性、数值输入与 UI 高度稳定化
│   ├── 10_v2_5_shake_tuning_and_early_connect.md # v2.5：双次摇一摇防误触、提前连接、关门后摇一摇上锁、开锁/闭锁独立开关
│   ├── 11_v2_6_shake_feedback_and_recorder_ui.md # v2.6：识别即1秒震动、记录按钮进度/完成状态、记录期间强制注册传感器
│   ├── 12_v2_7_power_triple_connect_first_voice.md # v2.7：电源键三连切换、未连接先连接后下发、语音播报
│   ├── 13_v2_9_stale_connecting_and_debug_log.md # v2.9：车旁首次失败后不再连接修复、陈旧 status=1、持久日志
├── GeelyKeyRadarApp/                          # 「银河无感钥匙」v4.0 极简版源码与构建产物
│   ├── AndroidManifest.xml                    # v4.0 清单（前台服务、NotificationListener、BootReceiver）
│   ├── build.sh                               # 一键单测、编译、签名、安装
│   ├── GeelyPassiveKey.apk                    # v4.0 已签名可直装 APK
│   ├── src/com/luca/geelykeyradar/
│   │   ├── PassiveKeyService.java             # 极简前台服务：扫描、身份学习、RSSI、GATT、WakeLock
│   │   ├── PassiveKeyLogic.java               # 靠近一次解锁 / 远离驻留上锁 / 断连不误锁
│   │   ├── GeelyAdvertisement.java             # 0x01FE / 0xFDFD / GeelyVehicle / 15FF 广播解析
│   │   ├── VehicleIdentity.java                # 本车蓝牙身份匹配
│   │   ├── VehicleIdentityStore.java           # 身份持久化与两个开关
│   │   ├── GeelyBridge.java                    # 最小化桥接官方 MiuiService / 导出组件
│   │   ├── KeyNotificationBridgeService.java   # 官方通知栏连接状态与解锁/上锁按钮
│   │   ├── MainActivity.java                   # 极简配置页
│   │   └── BootReceiver.java                   # 开机 / 更新自动拉起
│   └── legacy/v3.20.6/                         # 旧版完整源码、资源与 APK
├── apks/                                      # 从手机提取的原始 APK 备份
├── geely_dump/                                # 从运行态进程内存/数据目录提取的吉利银河脱壳 DEX (classes..classes22.dex)
└── extracted/                                 # vivo.wallet 与 vivo.car.networking 解包 DEX
```

---

## ⚡ 常用快速命令（免查手册）

### 1. 一键编译、签名并安装「银河无感钥匙 v4.0」
```bash
bash /path/to/car_key_re/GeelyKeyRadarApp/build.sh
```

脚本会先运行 `PassiveKeyLogicTest`，再编译、签名并尝试通过当前 adb server 安装。

### 1b. v3.20.6 legacy 手工编译命令（仅历史参考）
> 旧版源码已移到 `GeelyKeyRadarApp/legacy/v3.20.6/`，下面的命令默认仍指向旧路径，直接执行前需要把 `APP_DIR/src` 改到 legacy 目录。

```bash
APP_DIR="/path/to/car_key_re/GeelyKeyRadarApp"
BUILD_TOOLS="/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0"
ANDROID_JAR="/opt/homebrew/share/android-commandlinetools/platforms/android-36/android.jar"
ADB="adb"

rm -rf "$APP_DIR/build/classes" "$APP_DIR/build/dex" "$APP_DIR/build/gen" "$APP_DIR/build/unsigned_unaligned.apk" "$APP_DIR/build/aligned.apk"
mkdir -p "$APP_DIR/build/classes" "$APP_DIR/build/dex" "$APP_DIR/build/gen" "$APP_DIR/build/compiled_res"

"$BUILD_TOOLS/aapt2" compile --dir "$APP_DIR/res" -o "$APP_DIR/build/compiled_res/res.zip"
"$BUILD_TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest "$APP_DIR/AndroidManifest.xml" --java "$APP_DIR/build/gen" -o "$APP_DIR/build/unsigned_unaligned.apk" "$APP_DIR/build/compiled_res/res.zip"
javac --release 11 -classpath "$ANDROID_JAR" -d "$APP_DIR/build/classes" "$APP_DIR/build/gen/com/luca/geelykeyradar/R.java" "$APP_DIR/src/com/luca/geelykeyradar/"*.java
jar cf "$APP_DIR/build/classes.jar" -C "$APP_DIR/build/classes" .
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --output "$APP_DIR/build/dex" "$APP_DIR/build/classes.jar"
cp "$APP_DIR/build/dex/classes.dex" "$APP_DIR/build/classes.dex"
(cd "$APP_DIR/build" && zip -u unsigned_unaligned.apk classes.dex)
"$BUILD_TOOLS/zipalign" -f -p 4 "$APP_DIR/build/unsigned_unaligned.apk" "$APP_DIR/build/aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$APP_DIR/build/debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$APP_DIR/GeelyKeyRadar.apk" "$APP_DIR/build/aligned.apk"
"$ADB" -P 5038 install -r -g "$APP_DIR/GeelyKeyRadar.apk"
```

### 2. 一键加固后台常驻与通知监听授权
```bash
ADB="adb"
for pkg in com.luca.geelykeyradar com.geelygalaxy.customer; do
  "$ADB" -P 5038 shell dumpsys deviceidle whitelist +$pkg
  "$ADB" -P 5038 shell cmd appops set $pkg RUN_IN_BACKGROUND allow
  "$ADB" -P 5038 shell cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow
  "$ADB" -P 5038 shell cmd appops set $pkg WAKE_LOCK allow
  "$ADB" -P 5038 shell cmd appops set $pkg START_FOREGROUND allow
  "$ADB" -P 5038 shell am set-standby-bucket $pkg active
done
"$ADB" -P 5038 shell cmd notification allow_listener com.luca.geelykeyradar/com.luca.geelykeyradar.KeyNotificationBridgeService
```

### 3. 检查服务存活状态与车机备份文件
```bash
adb -P 5038 shell "dumpsys activity services com.luca.geelykeyradar | grep -E 'ServiceRecord|isForeground'"
```

```bash
adb -P 5038 shell "run-as com.luca.geelykeyradar cat files/vehicle_param_backup.json"
```

---

## 📚 详细分类文档导航

0. **[36_minimal_generic_passive_key.md](docs/36_minimal_generic_passive_key.md)**
   - **v4.0 极简通用版**：只保留息屏靠近解锁 / 离开上锁
   - **通用化**：运行时从 BLE 广播和官方 App 连接事件学习本车身份，不写死 VIN/MAC
   - **为什么不需要完整 VIN**：反编译确认 `MiuiService` 只要求非空 `carId`，真正鉴权在官方钥匙 SDK
   - **安全约束**：断连不误锁、行车不锁、一次接近只解锁一次、车内近场保护

1. **[01_geely_ble_protocol_and_sdk.md](docs/01_geely_ble_protocol_and_sdk.md)**
   - 吉利银河 APK 梆梆加固 (`libzky.so`) 免 Root 脱壳方法与 DEX 分布表
   - 车辆 BLE 广播包格式（`0xFF15` 厂商帧、VIN 后 8 位 ASCII、车辆 MAC `AA:BB:CC:44:55:66`）
   - 三代平台 GATT Service / Characteristic UUID 对照表
   - 车机端距离标定指令 `[64, 1, 20, ...]` 与闭锁区间计算公式
   - `IDKService` AIDL 完整 Feature Key、Action Code、Callback Code 与通知栏 `RemoteViews` 资源 ID
2. **[02_vivo_wallet_and_iccoa_key.md](docs/02_vivo_wallet_and_iccoa_key.md)**
   - 吉利银河与 vivo 钱包 (`com.vivo.wallet`)、vivo 智能车载 (`com.vivo.car.networking`) 钥匙开通与透传流程
   - vivo 车载蓝牙扫描器与距离换算公式 $d = 10^{(|\text{RSSI}| - A) / (10 \cdot n)}$
3. **[03_root_causes_3m_lock_and_disconnect.md](docs/03_root_causes_3m_lock_and_disconnect.md)**
   - **痛点一（3米误锁）**：为什么人在车旁 3 米拿东西或转身会触发锁车？
   - **痛点二（偶现不自动连蓝牙）**：`ZeekrBleSdkManager.piScanMacCache` 死锁 Bug 与 `DigitalKeyBgService.isWalletBind()` 自断保活 Bug 的源码级定位
4. **[04_geely_key_radar_app_architecture.md](docs/04_geely_key_radar_app_architecture.md)**
   - 「银河钥匙雷达」v3.20.0 完整设计：卡尔曼滤波、解锁单次触发/上锁驻留状态机、摇一摇解闭锁、真实锁态一致性、行车与闪断安全联锁、纯手机侧 vs 车机侧参数隔离、车机参数自动备份与一键还原、OriginOS 四重常驻架构、锁屏 PARTIAL_WAKE_LOCK 保活
5. **[05_vivo_originos6_adb_optimization.md](docs/05_vivo_originos6_adb_optimization.md)**
   - iQOO Neo10 Pro (`V2426A`) 已执行的 6 档系统优化清单与回滚指令
6. **[06_real_car_field_test_and_v2_ble_findings.md](docs/06_real_car_field_test_and_v2_ble_findings.md)**
   - 本车 (`LB378LEZ0XX000123` / `GeelyVehicle123` / `AA:BB:CC:11:22:33`) UAES V2 协议全貌
   - 五大实车联调壁垒与解决方案（单连接槽位零抢占、V2 3.07s 鉴权窗口 5 阶重发、取消 5.5s 误释放重连、HyperOS 连接态 RSSI 轮询兼容）
   - 大型加固 DEX 三步秒级检索规范（避免全文扫描）
7. **[07_recent_reverse_findings_and_v2_2_features.md](docs/07_recent_reverse_findings_and_v2_2_features.md)**
   - 手机距车 5 米信号阶梯跳变与 10 米外难连蓝牙的三重底层机制
   - vivo 手机钱包车钥匙对吉利银河 `MiuiService` 的真实依赖关系
   - 吉利银河常驻通知栏 (`_aaa_ge_dk_notification_view`) 真实视图树渲染点击与 DK 1.5 解闭锁枚举反转 (`Unlock=2, Lock=1`) 修复
   - 车机侧校准档位真相（银河L6 `GEELY_DK15` 无 5 挡位，仅支持 `0x0501` 无感 PE 总开关 `0/1/2`）与车机原始参数实时读取自动备份
   - 车辆 5 大状态枚举（`已驻车 / 行驶中 / 充电中 / 放电中 / 已插枪`）与行车硬安全锁；方向性轨迹规则已在 v2.3 删除
   - 锁车后 `+3.5s` 免弹窗静默开启哨兵模式 (`VSTD_MODE`) 与手机提示音
   - 银河L6 混动 (PHEV) 行程记录仪、最高/平均车速与综合续航打折率分析
8. **[08_threshold_dwell_and_screen_off.md](docs/08_threshold_dwell_and_screen_off.md)**
   - **为什么删除「必须由远及近/由近及远」方向性轨迹规则**：实测 BLE RSSI 的 ±10~18 dB 跳变使 `transitionZoneSampleCount` 长期停在 0/2，直接导致远离上锁不触发
   - v2.3 状态机：解锁 = 有效 RSSI ≥ 阈值；上锁 = 有效 RSSI ≤ 阈值连续 `4.5s`（v2.4 进一步把解锁改为单次满足即触发，取消 0.8s 驻留）
   - **锁屏失效/解锁慢根因与修复**：Doze/CPU 休眠、厂商息屏扫描降频、仅靠 450/2000ms UI 轮询、服务重启首帧静默同步 UNLOCKED、小组件状态帧伪「已解锁」、未申请电池白名单
   - 锁屏 `PARTIAL_WAKE_LOCK` + 每样本即时评估 + 45s 扫描心跳 + `deviceidle` 白名单 + OriginOS 后台高耗电设置清单
9. **[09_v2_4_shake_state_and_ui.md](docs/09_v2_4_shake_state_and_ui.md)**
   - **锁屏摇一摇切换上锁/解锁**：50Hz 加速度计三峰检测、非 wake-up 传感器与 PARTIAL_WAKE_LOCK 的配合、车辆附近才注册的省电策略；v3.20.0 锁屏触发连续震动 1 秒（亮屏已解锁时双短震）
   - **独立开关**：靠近自动解锁 (`auto_unlock`)、远离自动上锁 (`auto_lock`)、摇一摇 (`shake_toggle`) 互不影响，删除旧 `smart_takeover` 总开关；v2.5 进一步把摇一摇拆成解锁/上锁两个开关
   - **真实锁态一致性**：乐观指令 12s 无回执回退、3 分钟无真实 `0x0121` 帧不再显示「已解锁」、匹配帧提前确认、一键刷新真实锁态；并用 `dexdump` 核对 `vehicleLock = byte[7] bits[5..6]`
   - **UI 调整**：删除室内模拟信号发生器；三个参数改为数值输入；顶部状态文本固定行数、日志控制台固定 8 行，彻底消除页面高度抖动

10. **[10_v2_5_shake_tuning_and_early_connect.md](docs/10_v2_5_shake_tuning_and_early_connect.md)**
    - **双次摇一摇防误触**：高通线性加速度 + 同一主轴 4 峰/2 次反向/1.8s 窗口，阈值 20 m/s²；实测桌面静态噪声 max 0.11 m/s²
    - **提前建立吉利 V2 连接**：唤醒门槛 -95 dBm、动态 1.5/2.5s 重试、亮屏/App 回前台提前预连接，解决到车旁约 10s 后摇动才生效
    - **关门后摇一摇上锁**：手动/摇一摇改用窄口径行车门控，不再被 A2DP/PTReady 误拦；自动锁车仍保留全量安全门控
    - **开锁/闭锁开关分开**：自动解锁/自动上锁/摇一摇解锁/摇一摇上锁共 4 个独立开关
    - **摇一摇数据记录**：App 内一键录制 15s 原始加速度到 `shake_diag_*.csv`，用于继续调参

11. **[11_v2_6_shake_feedback_and_recorder_ui.md](docs/11_v2_6_shake_feedback_and_recorder_ui.md)**
    - **识别即震动**：双次摇一摇被识别后立即锁屏 1 秒震动，不再等车况/开关/行车门控通过；误摇不震动
    - **记录 UI 状态**：按钮显示剩余秒数并禁用，完成后状态行显示 `✅ 文件名 (样本数)`，不再“一直一个样子”
    - **记录期间强制开传感器**：在家/离车也能采集 15s 原始加速度，不再依赖车辆附近或 45s 窗口
    - **检测放宽**：阈值 20→15 m/s²、窗口 1.8→2.2s、按手势轴投影计数
    - **真实样本验证**：用户 CSV 中 18.2/35.1/15.5/26.7/35.7 峰序列在 0.599s 触发；v3.20.0 记录完成时显示“识别 N 次”

12. **[12_v2_7_power_triple_connect_first_voice.md](docs/12_v2_7_power_triple_connect_first_voice.md)**
    - **电源键连按三次切换解闭锁**：Android 不能直接拦电源键，改为 3s 内 3 次屏幕亮灭识别；已在 V2426A adb 实测触发
    - **未连接先连接再下发**：手动/摇一摇/电源键在蓝牙未连接时挂起指令，连接成功（V2 ACL status=2）自动补发；区分“银河正在连接/已发现未连接/车不在附近”，无车 6s 收尾
    - **语音播报**：已解锁/已锁车/蓝牙未连接/蓝牙连接中/蓝牙已连接/未发现车辆，独立开关

13. **[13_v2_9_stale_connecting_and_debug_log.md](docs/13_v2_9_stale_connecting_and_debug_log.md)**
    - **事故分析**：09:45 摇一摇/电源键三连震动但未解锁，银河钥匙未连接；首次太远失败留下 stale status=1，后续只等待/提前放弃
    - **v2.9 修复**：status=1 只在 8s 内可信；手动操作即使无广播也强制尝试一次；超时后强制重连（有车最多 3 次，无车 2 次），不再无限延长
    - **持久日志**：`keyradar_debug.log` 异步落盘，超过 1.5MB 轮转，事故后可直接 adb pull
    - **电源键三连稳定性**：2s→3s 窗口、冷却 3s→2s、逐次计数日志、固定 1 秒震动；实测 2041ms 三连旧版会漏、新版可触发


15. **[15_v3_3_quick_settings_tile.md](docs/15_v3_3_quick_settings_tile.md)**
    - **快捷设置磁贴「车锁切换」**：控制中心/锁屏快捷设置点一下，确定触发；磁贴副标题与常驻通知实时显示“蓝牙搜索中/连接中/解锁成功/锁车成功”
    - **复用链路**：与电源键三连共用行车门控、先连接后补发、语音、持久日志；磁贴触发不震动
    - **添加方式**：App 内一键请求；若厂商控制中心未放入面板，编辑里手动添加

16. **[16_v3_4_desktop_widget.md](docs/16_v3_4_desktop_widget.md)**
    - **4x2 桌面组件「银河车锁」**：深色卡片、右侧车图、左侧实时状态；v3.20.0 加预览图，应用挂件搜“银河”可命中
    - **解锁/上锁宽按钮**：两个按钮各占一半宽度；点击 45ms 短震动 + 实时状态
    - **状态链路**：蓝牙搜索中 → 连接中 → 正在解锁/上锁 → 解锁成功/锁车成功
    - **续航行**：电池/燃油/综合续航与百分比；信息行显示车辆状态（已驻车/行驶中/充电中）
    - **云端刷新**：组件头部「⟳ 刷新」触发银河云端 PULL_NEW_STATUS + 0x0121 车况查询
    - **显式动作**：新增 ACTION_EXTERNAL_UNLOCK/LOCK，不再只能切换

17. **[17_v3_7_widget_confirm_settings.md](docs/17_v3_7_widget_confirm_settings.md)**
    - **组件空白点击打开 App**：v3.20.0 根布局 PendingIntent → MainActivity；按钮/刷新/齿轮保持各自行为
    - **组件右上角 ⚙ 设置**：打开「解锁/上锁二次确认」开关
    - **防误触**：开启后点解锁/上锁先弹确认框，确认才执行
    - **不改刷新**：刷新按钮仍直接执行
    - **实现**：RemoteViews 按钮 PendingIntent → WidgetConfirmActivity → KeyRadarService

18. **[18_phone_key_workflow.md](docs/18_phone_key_workflow.md)**
    - **整体工作流**：云端发卡 → vivo 钱包/车联网 → 银河 MiuiService → 车机 BLE/V2 鉴权 → 被动 PE/主动 RKE
    - **我们 App 流程**：扫描 → 唤醒/注入 → 等 V2 3.07s → 点通知栏 → 0x0121 确认
    - **失败链路**：扫描缓存死锁、钱包绑定自断保活、单槽位、鉴权前指令丢失、车机 PE 开关
    - **图源**：`docs/diagrams/*.dot` + PNG/SVG

19. **[19_v3_9_cloud_lock_timestamp_and_widget_icon.md](docs/19_v3_9_cloud_lock_timestamp_and_widget_icon.md)**
    - **银河云端锁态无自带时间戳**：`DkDataVehicleStatus` 只有 lock/lock15，云端刷新时间在 UI 层
    - **本地直连优先**：真实 0x0121 锁态时间持久化；云端无时间戳/较旧时不覆盖本地
    - **组件锁图标**：上锁=闭锁、解锁=开锁，随 `widget_lock_locked` 切换

20. **[20_v3_10_charging_control_block.md](docs/20_v3_10_charging_control_block.md)**
    - **默认开启**：USB/AC 有线充电 → 认为在驾驶室 → 禁止解闭锁
    - **拦截所有解闭锁路径**：手动/摇一摇/电源键/磁贴/组件/自动/通知栏队列
    - **不影响**：后备箱、哨兵、刷新；无线充电不触发
    - **开关**：「🔌 插充电线后禁止解闭锁」可关闭

21. **[21_v3_11_lockscreen_unlock_duplicate_widget_status.md](docs/21_v3_11_lockscreen_unlock_duplicate_widget_status.md)**
    - **锁屏不解锁**：旧 GATT 只读一次 + 15s tick → 新增连接态 1Hz `readRemoteRssi`
    - **重复解锁**：12s 无回执回退 UNKNOWN 后再次触发 → 同方向 20s 静默，反方向不抑制
    - **组件卡“正在上锁”**：4s 后显示“指令已发送”，12s 后“锁态未知”；真实 0x0121 才显示成功
    - **启动清理**：服务启动时清掉 prefs 里残留的“正在/搜索中”

22. **[22_v3_12_mileage_history.md](docs/22_v3_12_mileage_history.md)**
    - **已删除（v3.20.0）**：总里程/历史点/行程 odometer 字段全部移除
    - **保留**：油电续航与行程折扣仍按 `PhevRangeSnapshot` 计算
    - **原因**：`wg_tv_mileage` 是综合续航，不是真实行驶总里程

23. **[23_v3_13_cabin_rssi_stabilizer.md](docs/23_v3_13_cabin_rssi_stabilizer.md)**
    - **原因**：车内金属多径、天线零点、人体遮挡、BLE 信道跳频；原始 RSSI 本身就会 -50/-90 跳
    - **修复**：车内模式 4s 中值 + ±8dB 钳位 + Kalman q=0.02 + EMA 0.18 + 10dB/s 限速
    - **保留**：原始信号仍显示真实波动；稳定的是平滑/有效信号
    - **车外**：仍用快速跟踪，避免靠近解锁变慢

24. **[24_v3_14_park_state_false_driving.md](docs/24_v3_14_park_state_false_driving.md)**
    - **根因**：A2DP/HFP 下车后仍连接被当成行驶中；通知栏“行驶中”无时效；BLE 旧快照信任 5 分钟
    - **修复**：移除 A2DP 行驶判定；通知栏“行驶中”90s 时效；BLE“已驻车”优先；30s 行驶时效；低速 stale 状态兜底 PARKED
    - **效果**：P 档/人下车后显示“已驻车”，自动/手动上锁不再被误挡

25. **[25_v3_15_ui_refactor.md](docs/25_v3_15_ui_refactor.md)**
    - **结构**：品牌 Header + Hero 锁态/信号/主操作 + 控制/行程/设置/日志四页签
    - **视觉**：深色驾驶舱、18dp 卡片、青色强调、44–48dp 触摸目标
    - **清理**：静态标签/按钮/开关去 emoji，统一系统 sans 字体层级
    - **截图**：`mockups/app_ui_v3151_*.png`

26. **[26_v3_16_ble_loss_no_false_lock.md](docs/26_v3_16_ble_loss_no_false_lock.md)**
    - **问题**：旧逻辑 8 秒 signalFresh + 单条弱 RSSI 可能走完 3.2 秒驻留，蓝牙消失时误锁
    - **修复**：ACL 断开/蓝牙关闭立即清零并禁用 10–15 秒；自动上锁只认 3 秒内新样本；至少 3 个样本
    - **取舍**：蓝牙关闭期间自动上锁禁用，宁可不锁也不误锁；手动锁需蓝牙恢复

27. **[27_v3_17_widget_mileage_tire.md](docs/27_v3_17_widget_mileage_tire.md)**
    - **已删除（v3.20.0）**：v3.17 的总里程/胎压、无障碍读取、组件字段与行程 odometer 全部移除
    - **原因**：`wg_tv_mileage` 是综合续航；真实 odometer/胎压在银河进程/TSP，第三方无公开接口
    - **现状**：组件显示“车辆状态 + 更新时间”，不再显示总里程/胎压

28. **[28_v3_18_odometer_correction.md](docs/28_v3_18_odometer_correction.md)**
    - **纠正**：`wg_tv_mileage=1238` 是综合续航（89+1149），不是 5 万 km 总里程
    - **真实字段**：`VehicleRunningStatus.odometer` / `VehicleTyreStatus.*`，位于 TSP 的 `PlatformVehicleStatus`
    - **v3.20.0**：总里程/胎压相关代码、组件字段和行程里程字段全部删除

29. **[29_v3_19_accessibility_odometer_tire.md](docs/29_v3_19_accessibility_odometer_tire.md)**
    - **已删除（v3.20.0）**：只读无障碍服务、配置、设置入口与组件字段全部移除
    - **REA 边界**：银河进程内 `DataProvider` 有完整 `PlatformVehicleStatus`，但第三方无公开 Binder/Provider；MMKV 私有；widget 投影仅含续航/警告/控制状态
    - **结论**：普通第三方权限无法在不打开银河的情况下后台读取总里程/胎压
    - **若要继续**：需吉利官方 API/SDK 或导出服务


30. **[30_v3_20_1_own_car_ble_identity.md](docs/30_v3_20_1_own_car_ble_identity.md)**
    - **根因**：`0x01FE` 任意吉利广播、裸 `0xFDFD`、`GeelyVehicle*` 前缀、`matchAnyGeely15FF=true` 都当成本车
    - **修复**：按本车 VIN 后 2/3 位、精确名称 `GeelyVehicle123`、本车 MAC 严格识别；其他车名直接否决
    - **锁态**：20 秒无本车 BLE 信号且吉利未连接时，`已解锁` 回退为 `未知/待命`
    - **结果**：别人车旁不再触发 RSSI/自动解锁


31. **[31_v3_20_2_widget_range_regression.md](docs/31_v3_20_2_widget_range_regression.md)**
    - **回归**：v3.20.0 清理总里程/胎压时，误删组件读取 `widget_ev/fuel/total` 的绑定
    - **修复**：恢复 `widget_ev/widget_fuel/widget_total` 的 `rangeText` 绑定，续航数据未丢
    - **实测**：手机桌面组件显示电池 89 km/70%、燃油 1149 km/98%、综合 1238 km


32. **[32_v3_20_3_approach_unlock_check.md](docs/32_v3_20_3_approach_unlock_check.md)**
    - **检查**：靠近自动解锁开关为开；但手机 USB 供电，且“插充电线后禁止解闭锁”为开，自动解锁被既有安全联锁阻止
    - **匹配加固**：本车名称从完全等于 `GeelyVehicle123` 放宽为 `GeelyVehicle` 前缀 + VIN 后三位，避免误拒本车
    - **日志**：识别到本车时输出 `🎯 本车BLE信号源`
    - **测试**：拔掉 USB 或关闭“插线禁解”后再靠近


33. **[33_v3_20_4_lockscreen_unlock_audit.md](docs/33_v3_20_4_lockscreen_unlock_audit.md)**
    - **锁屏本身不拦截**：无 Keyguard 限制，前台服务 + BLE 扫描 + WakeLock
    - **当前实际阻挡**：USB 供电 + “插充电线后禁止解闭锁”开启
    - **其他场景**：10s 冷却、旧“行驶中”、口袋补偿关闭、锁屏超 5 分钟 WakeLock 释放、银河占用 BLE、身份不匹配
    - **修复**：通知栏“行驶中”加 90 秒时效，停车后旧通知不再永久禁控


34. **[34_v3_20_5_target_duplicate_isolation.md](docs/34_v3_20_5_target_duplicate_isolation.md)**
    - **乱跳原因**：旧版任何吉利广播都进入同一 RSSI 滤波/阈值流；同品牌多车会造成跳变和误解锁
    - **多目标隔离**：30 秒候选、12 秒主目标保持；2 个以上目标只取主目标，其余 RSSI 隔离并写日志
    - **车钥匙不可见**：数字钥匙是 BLE GATT（随机地址、App 管理），不是 Classic 多媒体配对；连接后车机停止广播


35. **[35_v3_20_6_approach_unlock_single_shot.md](docs/35_v3_20_6_approach_unlock_single_shot.md)**
    - **现象**：靠近解锁一次后，进入车内每 10~20 秒重复解锁
    - **根因**：锁态回写 `LOCKED/UNKNOWN` 后重新满足解锁条件；10 秒冷却/20 秒静默不能表达“一次靠近”
    - **修复**：一次接近只自动解锁一次；车机重复落锁通知每会话最多纠偏一次；连续远离 8 秒后才重新武装
    - **构建**：`aapt2 --java` 生成 `R.java`，不再依赖残留 `R.class`

### v3.20.0 删除说明

- 删除总里程、胎压、四轮 JSON、无障碍读取服务和组件设置入口。
- `VehicleStatusSnapshot`、`PhevRangeSnapshot`、`PhevTripRecord` 不再携带 odometer/tire 字段。
- 组件副标题恢复为连接/车辆状态；信息行显示车辆状态与更新时间。
- 保留：油电续航、蓝牙雷达、锁态、摇一摇、电源键三连、行程续航折扣、日志。

### v3.2 删除说明

- 音量键无障碍方案（三连/长按、音量变化兜底、音量上+下组合键）经实机验证不稳定，
  已在 v3.20.0 完整移除：无障碍服务、清单声明、配置、UI 开关、KeyRadarService 外部触发与日志文件。
- 保留：摇一摇、电源键三连、手动按钮、自动靠近/远离、语音、先连接后补发、持久调试日志。
- 无障碍设置中的「银河钥匙雷达 · 音量键触发」已清除。
