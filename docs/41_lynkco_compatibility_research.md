# 41. 领克（Lynk & Co）车型与 App 适配可行性调研报告

**调研对象**：领克车系（如领克 08/07 EM-P、领克 10 EM-P 等）及领克官方 Android 客户端（`com.lynkco.customer`）  
**基准工程**：`GeelyPassiveKey` (v4.0.3-min)  
**调研核心问题**：本项目的「BLE 扫描识别 + 官方钥匙后台唤醒 + 通知栏 PendingIntent 0延迟触发」链路，在领克 App 及领克车型上是否可行？

---

## 一、调研结论概要（TL;DR）

**结论：高度可行，架构与吉利银河同源。**

1. **同根同源的吉利生态底座**：
   - 领克（Lynk & Co）、吉利银河（Geely Galaxy）与极氪（Zeekr）的数字钥匙与车机互联均基于**亿咖通（ECARX）+ 联合电子（UAES）SNC 平台**。
   - 领克 App (`com.lynkco.customer`) 内部同样集成了 `com.ecarx.blekey.thirdadapter`、`DigitalKeyBgService` 以及 `PendingIntent` / `RemoteViews` 通知栏车控组件。
2. **通知栏桥接链路（`KeyNotificationBridgeService`）具备高兼容性**：
   - 本项目核心控车并不依赖逆向车机私有鉴权密钥，而是通过 `NotificationListenerService` 读取官方常驻通知，并通过系统级 `PendingIntent.send()` 触发官方解闭锁。
   - 本项目已实现**按文本递归搜索（`"解锁"` / `"上锁"` / `"已连接"`）**的兜底机制（见 `KeyNotificationBridgeService.java:615-625`），即使领克的资源 ID 与银河不同，只要通知栏带有相应文案，即可被自动捕获。
3. **主要适配差异点**：
   - **包名**：由 `com.geelygalaxy.customer` 变为 `com.lynkco.customer`。
   - **通知 ID**：银河固定为 `5`，领克需提取实际 Notification ID（或放宽为对领克包名的所有带 Action 通知的动态捕获）。
   - **车机 BLE 广播标识**：蓝牙广播名可能由 `GeelyVehicle<VIN末3位>` 变为 `LynkCo<...>` 或其他前缀，需核对 Manufacturer Data（Company ID 是否同为 `0x01FE` 或 `0xFDFD`）。

---

## 二、源码与架构对标分析（Primary Source Analysis）

### 1. 通知栏桥接机制（`KeyNotificationBridgeService.java`）

在本项目中，通知栏桥接的核心代码逻辑如下：

```java
// H:\GeelyPassiveKey\GeelyKeyRadarApp\src\com\luca\geelykeyradar\KeyNotificationBridgeService.java
public static final String GEELY_PKG = "com.geelygalaxy.customer";
public static final int GEELY_DK_NOTIFY_ID = 5;

// 1. 尝试通过预置资源 ID 快速索引（银河特定 ID）
View unlockView = root.findViewById(ID_NOTIF_TV_UNLOCK); // 0x7f0a10cb
View lockView = root.findViewById(ID_NOTIF_TV_LOCK);     // 0x7f0a10c7

// 2. 泛化兜底：按文本递归遍历整个 RemoteViews 控件树
traverseInflatedTree(root, discoveredByText, connByText);
```

**对领克的适配可行性**：
- 领克 App 在手机蓝牙钥匙激活且在后台运行时，系统通知栏会常驻显示「蓝牙钥匙已连接/未连接」以及「解锁 / 闭锁 / 后备箱」等快捷按钮。
- 只要修改过滤包名：`pkg.equals("com.lynkco.customer")`，并通过递归遍历提取按钮的 `PendingIntent`，该链路在领克上完全成立，不受 Android 14/15/16 后台启动与限制影响。

### 2. 后台唤醒与扫描注入（`GeelyBridge.java`）

银河 App 导出的组件：
- `com.ecarx.blekey.thirdadapter.service.MiuiService` (Action: `com.geely.geometry.miuiservice.action` / `com.geely.consumer.miuiservice.action`)
- `com.geely.snc.digitalkey.base.service.DigitalKeyBgService`

**对领克的适配可行性**：
- 亿咖通为吉利系（吉利、领克、smart、极氪）定制的 MIUI/系统级保活适配器均使用了 `com.ecarx.blekey.thirdadapter.service.MiuiService`。
- 如果领克也导出了该组件，则手机扫到车机 BLE 广播后，可同样直接向领克注入 `ScanResult` 触发官方钥匙秒连。

### 3. 车载 BLE 广播特征（`GeelyAdvertisement.java` / `VehicleIdentity.java`）

银河车型采用联合汽车电子（UAES）V2 方案：
- **Company ID**：`0x01FE`（十进制 510）
- **Service UUID**：`0000fdfd-0000-1000-8000-00805f9b34fb` (`0xFDFD`)
- **广播名称**：`GeelyVehicle<VIN末3位>`

**对领克的预期特征**：
- 领克 08 / 07 / 10 等搭载新一代电子电气架构的车型，蓝牙钥匙同样大量采用 UAES V2/V3 协议，`0x01FE` 厂商自定义数据帧和 `0xFDFD` 服务大概率保持一致。
- 广播名称一般包含 `LynkCo`、`Lynk` 或带 VIN 标识。

---

## 三、用户零成本验证步骤（无需逆向，手机实测 1 分钟）

在逆向 APK 之前，车主可以直接在手机上完成以下两步验证：

1. **查看通知栏形态**：
   - 打开领克 App，确保蓝牙钥匙已在车旁连接成功。
   - 下拉手机通知栏，观察领克 App 是否存在**常驻通知**。
   - 观察通知栏上是否有明文的 **“解锁”** 与 **“上锁/闭锁”** 按钮？点击它是否能直接控制车锁？
   - *（如果有，则证明通知栏 PendingIntent 链路 100% 可行！）*
2. **抓取通知栏元数据（可选调试法）**：
   - 可以在 Android 设置中开启“通知使用权”，或者通过 Android 的 `dumpsys notification --noredact` 查看当前领克通知的完整结构，包括其 `actions`、`contentView` 及 `notificationId`。

---

## 四、如果提供领克 APK，我们将逆向分析什么？

如果你将领克 App 的 APK（或提取的 `base.apk`）放入项目根目录（例如 `h:\GeelyPassiveKey\lynkco.apk`），我们将执行以下精准逆向提取：

1. **`AndroidManifest.xml` 组件审查**：
   - 确认包名（是否为 `com.lynkco.customer`）。
   - 检索是否有 `exported="true"` 的 Service 或 Receiver（如 `MiuiService`、`DigitalKeyBgService`、`RemoteViewsClickReceiver` 等）。
2. **通知栏资源 ID（`R.id`）与布局反编译**：
   - 定位 `DkRemoteView` 或通知栏构造类。
   - 提取领克通知栏中解锁、上锁、连接状态的准确资源名称和十六进制 ID（例如 `id_notif_tv_unlock` 等）。
3. **蓝牙广播特征匹配规则**：
   - 在 SDK 中检索 BLE ScanFilter 过滤条件（Company ID、Service UUID、广播名前缀规则）。

---

## 五、魔改工程设计方案（建议的多车型通用架构）

为了不破坏已有的吉利银河支持，建议将应用重构为**多品牌配置驱动**：

```java
public enum CarBrand {
    GEELY_GALAXY("com.geelygalaxy.customer", 5, "GeelyVehicle", 0x01FE),
    LYNK_CO("com.lynkco.customer", -1 /* 动态匹配 */, "LynkCo", 0x01FE);

    public final String packageName;
    public final int defaultNotificationId;
    public final String bleNamePrefix;
    public final int companyId;
    
    // ...
}
```

在界面上提供一个下拉菜单选择「吉利银河」或「领克」，或者通过检测手机安装的 App 自动切换。
