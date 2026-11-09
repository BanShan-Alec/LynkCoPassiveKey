# 42. 领克 App (v4.2.9) 静态逆向与数据特征分析报告

**目标文件**：`H:\GeelyPassiveKey\apk\lynkco-64-v4.2.9.apk` (大小: 317,216,307 字节)  
**分析日期**：2026-11-09  
**分析工具**：Python 3.12, androguard, pyaxmlparser, 自定义 ARSC/DEX 深度扫描器  

---

## 一、逆向核心结论（事实证明）

对领克官方 App (`com.lynkco.customer` v4.2.9) 的实包逆向结果表明：

1. **同构率 100% 的数字钥匙 SDK**：
   - 领克内部完全复用了吉利银河相同的数字钥匙平台类库：`com.geely.icc.common.business.alive.ble.VehicleV2BleStrategy`、`com.geely.snc.digitalkey.base.service.DigitalKeyBgService`、`com.geely.snc.digitalkey.base.notification.DkRemoteView`。
   - 蓝牙 V2 鉴权状态机（`connect real start V2`、`v2 isConnectDevice`、`subConnectBle`、`fastConnectBle`、`lastDisConnectTime`）与银河完全一致。
2. **导出的系统直连服务完全一致**：
   - 领克导出了 `com.ecarx.blekey.thirdadapter.service.MiuiService`（`exported="true"`），注册的 Action 同样为 `com.geely.geometry.miuiservice.action`。
   - 这意味着本项目（GeelyPassiveKey）在扫到车辆广播后，**向领克后台注入 `ScanResult` 触发官方建立蓝牙连接的机制完全成立**。
3. **通知栏控件与布局结构完全一致，仅编译资源 ID 偏移**：
   - 领克的常驻通知布局名称同样为 `_aaa_ge_dk_notification_view`。
   - 解锁、上锁、连接状态的控件名完全相同，且六个核心控件保持连续顺序分布。

---

## 二、关键逆向参数与映射对照表

### 1. 核心应用与服务参数

| 参数项 | 吉利银河 (`com.geelygalaxy.customer`) | 领克汽车 (`com.lynkco.customer`) | 适配方案 |
| :--- | :--- | :--- | :--- |
| **包名 (`PACKAGE_NAME`)** | `com.geelygalaxy.customer` | **`com.lynkco.customer`** | 支持包名切换或自适应探测 |
| **直连唤醒服务 (`MiuiService`)** | `com.ecarx.blekey.thirdadapter.service.MiuiService` | **`com.ecarx.blekey.thirdadapter.service.MiuiService`** | 类名完全相同，指向领克包名即可 |
| **直连 Action (`ACTION_MIUI`)** | `com.geely.geometry.miuiservice.action` | **`com.geely.geometry.miuiservice.action`** | 完全相同，无需修改 |
| **数字钥匙后台服务** | `com.geely.snc.digitalkey.base.service.DigitalKeyBgService` | **`com.geely.snc.digitalkey.base.service.DigitalKeyBgService`** | 完全相同 |

### 2. 通知栏资源 ID 对照表（逆向自 `resources.arsc`）

| 控件名称 | 资源类型 | 吉利银河 ID (Hex / Dec) | 领克 v4.2.9 ID (Hex / Dec) | 说明 |
| :--- | :---: | :--- | :--- | :--- |
| `_aaa_ge_dk_notification_view` | `layout` | `0x7f0d0003` (2131558403) | **`0x7f0d0000`** (2131558400) | 官方常驻通知栏根布局 |
| `notif_layout_lock_unlock` | `id` | `0x7f0a10c6` (2131366086) | **`0x7f0a1694`** (2131367572) | 解闭锁按钮父容器（Visible = 已连） |
| `notif_tv_lock` | `id` | `0x7f0a10c7` (2131366087) | **`0x7f0a1695`** (2131367573) | **「上锁」按钮（绑定 PendingIntent）** |
| `notif_tv_status_conn` | `id` | `0x7f0a10c8` (2131366088) | **`0x7f0a1696`** (2131367574) | 「蓝牙钥匙已连接」状态文本 |
| `notif_tv_status_disconn` | `id` | `0x7f0a10c9` (2131366089) | **`0x7f0a1697`** (2131367575) | 「蓝牙钥匙未连接」状态文本 |
| `notif_tv_status_no` | `id` | `0x7f0a10ca` (2131366090) | **`0x7f0a1698`** (2131367576) | 无钥匙状态文本 |
| `notif_tv_unlock` | `id` | `0x7f0a10cb` (2131366091) | **`0x7f0a1699`** (2131367577) | **「解锁」按钮（绑定 PendingIntent）** |

---

## 三、代码适配要点（工程落地建议）

在 `GeelyKeyRadarApp` 源码中，适配领克仅需以下几处改动：

### 1. `KeyNotificationBridgeService.java`
* **包名匹配**：
  ```java
  // 兼顾银河与领克
  public static boolean isTargetPackage(String pkg) {
      return "com.geelygalaxy.customer".equals(pkg) || "com.lynkco.customer".equals(pkg);
  }
  ```
* **资源 ID 动态化**：
  定义针对领克的资源 ID 常量，在 `inflateAndExtractFromRemoteViews` 中根据当前通知包名切换 ID（同时保留 `traverseInflatedTree` 文本兜底）。
* **通知 ID 过滤放宽**：
  若通知来源为目标包名且包含 `RemoteViews` 或按钮文本，放行解析，不再死板限制 `sbn.getId() == 5`。

### 2. `GeelyBridge.java`
* **目标包名动态路由**：
  ```java
  public static String getTargetPackage(Context context) {
      if (isAppInstalled(context, "com.lynkco.customer")) {
          return "com.lynkco.customer";
      }
      return "com.geelygalaxy.customer";
  }
  ```
* `MiuiService` 的 `ComponentName` 将包名替换为目标包名，Action 保持 `com.geely.geometry.miuiservice.action`。

### 3. `GeelyAdvertisement.java` / `VehicleIdentity.java`
* 领克车机广播前缀通常为 `LynkCo`、`Lynk` 或由 `VehicleIdentityStore` 在首次连接时自动捕获学习（本项目 v4.0 已具备广播指纹自动学习能力）。
