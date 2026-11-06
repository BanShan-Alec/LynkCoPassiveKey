# 16. v3.6：4x2 桌面组件「银河车锁」、续航与云端刷新

本文档记录 v3.4.x~v3.6.x 的桌面 AppWidget。

## 一、样式

- 4x2（4 列 × 2 行），深色卡片，和顶部吉利组件同风格；
- 左侧：连接状态、实时状态大字、详细说明、更新时间；
- 右侧：车辆侧影；
- 底部：**两个等宽大按钮**「解锁」「上锁」——各占一半宽度，不再是窄按钮。

最终样式见：

- `mockups/widget_4x2_v2.png`

## 二、交互

- 点「解锁」/「上锁」：
  - 立即 **45ms 短震动**（不是电源键的 1 秒长震）；
  - 状态立即变为 `蓝牙搜索中...`；
  - 随后自动显示：
    - `蓝牙连接中...`
    - `已连接，正在解锁/上锁...`
    - `解锁成功` / `锁车成功`
    - 失败：`连接失败，重试中...` / `未发现车辆 / 连接失败`
- 状态由 `KeyRadarService.publishTileStatus()` 写入 SharedPreferences 并广播；
- `KeyWidgetProvider` 监听 `ACTION_TILE_STATUS` 实时刷新所有组件；
- 成功状态以真实车机 `0x0121` 锁态帧为准。

## 三、颜色

| 状态 | 颜色 |
| :--- | :--- |
| 搜索中/连接中 | 琥珀 |
| 正在解锁/上锁 | 青蓝 |
| 解锁成功/锁车成功 | 绿 |
| 未发现/连接失败 | 红 |

连接行（右上）独立显示：`蓝牙已连接` / `蓝牙连接中` / `扫描车辆中` / `蓝牙未连接`。

## 四、实现

- `KeyWidgetProvider`（AppWidgetProvider）
  - `onUpdate()` / `onReceive(ACTION_TILE_STATUS)` 刷新所有组件；
  - RemoteViews 设置状态文字、颜色、连接行、更新时间；
  - 两个按钮通过 `PendingIntent.getForegroundService()` 发送：
    - `ACTION_EXTERNAL_UNLOCK`
    - `ACTION_EXTERNAL_LOCK`
- `KeyRadarService`
  - 新增显式解锁/上锁入口，不再只能“切换”；
  - `handleExternalAction()` 复用行车门控、按状态决定、先连接后补发、语音、日志；
  - 来源 `桌面组件` 使用 45ms 短震动；磁贴不震动；电源键/摇一摇保留 1 秒。
- `res/layout/widget_lock_4x2.xml`
  - 宽按钮：两个 TextView 各 `layout_weight=1`，高度 38dp，占满卡片宽度；
  - 右侧车辆图 `res/drawable-nodpi/widget_car.png`。
- `res/xml/widget_lock_info.xml`
  - `targetCellWidth=4`、`targetCellHeight=2`、`minHeight=140dp`、`resizeMode=horizontal|vertical`、`previewLayout`。
- `res/values/strings.xml`
  - 组件名称「车锁控制」、描述。

## 五、添加组件

1. 桌面长按空白处 → 小组件/添加工具；
2. 找到「银河钥匙雷达」/「车锁控制」；
3. 拖到桌面（4x2）；
4. 点「解锁」或「上锁」测试。

> 桌面启动器的组件选择器是厂商自定义，无法用 adb 自动添加；需要手动拖一次。

## 六、验证

- v3.4.0 已安装；
- `dumpsys appwidget` 已注册：
  - `com.luca.geelykeyradar/com.luca.geelykeyradar.KeyWidgetProvider`
  - min 64001x35841（250x140dp，4x2）
- 组件本身需要手动添加到桌面后点按验证；
- 状态文字/短震动/显式解锁上锁逻辑与磁贴共用服务链路。

## 七、边界

- 4x1 紧凑版暂未单独提供；当前是 4x2（用户要求按钮加宽）。
- 未放续航/驻车行，保持钥匙状态为主；需要可加。
- 组件按钮直接启动前台服务，桌面点击属于用户交互；若系统限制后台启动，先打开一次 App。
- 厂商桌面可能对组件圆角/内边距做二次裁切，实际以桌面显示为准。

## 八、续航/百分比行（v3.5.0）

组件状态区下方新增三列：

```text
电池            燃油            综合
96 km · 76%     1149 km · 98%   1245 km
```

- 数据来自 `GeelyAidlBridge.PhevRangeSnapshot`：
  - `elecRangeKm` / `elecPercent`
  - `fuelRangeKm` / `fuelPercent`
  - `getResolvedTotalKm()`
- `KeyRadarService.publishWidgetRange()` 在 `onPhevRangeUpdated()` 和绑定成功时写入 SharedPreferences，并广播刷新组件；
- `KeyWidgetProvider` 读取：
  - `widget_ev_range` / `widget_ev_percent`
  - `widget_fuel_range` / `widget_fuel_percent`
  - `widget_total_range`
- 无数据时显示 `-- km · --%`，不会显示错误数字。

实机日志：

```text
[11:26:49.471] 🧩 组件续航: 电 96km/76% 油 1149km/98% 综合 1245km
```

当前版本 **v3.5.0**，组件 provider 已注册（250x156dp，4x2）。桌面组件由用户手动添加，不再自动尝试。

## 九、名称、预览图与原子组件（v3.5.1）

v3.5.1 调整：

- 组件名称：`车锁控制` → **`银河车锁`**；
- 新增 `previewImage`（600x280 预览图，`res/drawable-nodpi/widget_preview.png`）；
- 新增 `initialKeyguardLayout`；
- 目的：在「应用挂件」里搜索“银河”能直接命中。

### 关于 OriginOS「原子组件」

- 银河 App 的 `WgProviderMiddle` 本身也只是普通 `AppWidgetProvider`，Manifest 里没有 atomic 专属 SDK/meta-data；
- vivo launcher 的 `isShowAtomicWidget pkgName` / `hasAtomicWidget` 是**按包名/合作方白名单**判定的；
- 因此侧载 App 仅靠改 Manifest 无法保证进入“原子组件”搜索；
- 改名+预览图只能改善「应用挂件」里的可发现性，不能绕过 launcher 白名单。

如果仍只在「应用挂件」出现，属于 vivo 的组件分类策略，不是组件代码问题。

## 十、车辆状态与云端刷新（v3.6.x）

### 1. 车辆状态

组件信息行现在显示：

```text
车辆状态: 已驻车 · 11:39
```

- 来源：`currentVehicleParkState.label`，以及 `PhevRangeSnapshot.warnStatusText`；
- 写入 prefs `widget_vehicle_state`；
- `onVehicleStatusParsed()` / `onPhevRangeUpdated()` 时更新；
- 实机日志：

```text
[11:39:59.159] 🧩 组件车辆状态: 已驻车
[11:39:59.171] 🧩 组件续航: 电 95km/75% 油 1149km/98% 综合 1244km 车辆 已驻车
```

### 2. 云端刷新按钮

组件头部新增 **`⟳ 刷新`** 按钮：

- 点击 45ms 短震动；
- 状态显示 `云端刷新中...`；
- 调用：
  - `refreshVehicleStateNow()`：请求 0x0110 / 0x0121 真实车况；
  - `KeyAidlBridge.requestWidgetPullNewStatus()`：向银河云/小组件发送 `PULL_NEW_STATUS`，同步油电续航与车况；
- 成功后 6 秒显示 `云端刷新成功`，失败显示 `云端刷新失败`；
- 6 秒回调会检查 `lastLockCommandMs`，避免覆盖同一时间窗内的解锁/锁车结果。

### 3. 当前实机数据

```text
电池 95 km · 75%
燃油 1149 km · 98%
综合 1244 km
车辆状态 已驻车
```

> 云端刷新是否真正拉到新数据，取决于银河 App/车机 TSP 是否响应 `PULL_NEW_STATUS`；若权限被拒，日志会显示 `请求同步油电续航异常`，但本地 0x0121/缓存仍会更新。

### 4. 预览图

`res/drawable-nodpi/widget_preview.png` 已更新为带车辆状态/刷新按钮的 4x2 样式；设计稿：`mockups/widget_4x2_v4.png`。

### v3.6.1 布局微调

- 根 padding 8dp→6dp、刷新按钮上下 padding 2dp→1dp；
- 4x2 内容总高约 154dp，避免底部解锁/上锁按钮在 156dp 组件里被裁切。

## 十一、蓝牙信号强度（v3.8.0）

组件右上连接行现在显示：

```text
已连接 -62dBm
```

- 数据源：`evaluateRadarState()` 的 `effectiveDbm`（已含揣兜补偿）；
- `publishWidgetRssi()` 写入 `widget_rssi` / `widget_rssi_ms`，广播刷新组件；
- 刷新节流：约 **1 秒 / 变化 ≥2 dB**，避免 180ms RSSI 刷爆 RemoteViews；
- 断连/无信号：`已连接 --dBm` 或 `未连接`；
- 颜色：
  - ≥ -60 dBm：绿
  - ≥ -72 dBm：青
  - ≥ -82 dBm：琥珀
  - < -82 dBm：红

### 实机状态

v3.8.1 修复：连接行**始终**带信号占位符：

```text
未连接 --dBm
扫描 --dBm
已连接 -62dBm
```

v3.8.2 增加：App 服务启动时广播 `ACTION_TILE_STATUS`，强制已添加的旧组件重新读取 prefs，避免升级后仍显示旧文本。
