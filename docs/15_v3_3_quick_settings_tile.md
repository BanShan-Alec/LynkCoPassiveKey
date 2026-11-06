# 15. v3.3：快捷设置磁贴「车锁切换」与实时状态

本文档记录 v3.3.x 新增的 Quick Settings 磁贴与实时状态。

## 一、方案

- 新增 `KeyTileService`（`TileService`），在快捷设置/控制中心显示为「车锁切换」。
- 点一下磁贴：
  - 启动 `KeyRadarService`，携带 `ACTION_EXTERNAL_TOGGLE`；
  - `toggleLockFromExternal("快捷设置磁贴")`：
    - 行车门控；
    - 按当前锁态决定上锁/解锁；
    - 未连接先连接、连接后补发；
    - 1 秒震动、语音、持久日志。
- 磁贴与电源键三连、摇一摇、手动按钮共用同一条链路。

## 二、添加磁贴

App →「🟢 手机侧」→ 点「🧩 添加快捷设置磁贴「车锁切换」」：

- Android 13+ 调用 `StatusBarManager.requestAddTileService()`，系统弹窗/直接添加；
- 返回结果码 1（已添加/已请求）；
- 若控制中心里没有，手动操作：
  1. 下拉控制中心/快捷设置；
  2. 点右上角编辑（铅笔）；
  3. 在可用磁贴里找到「车锁切换」并拖到面板。

> `adb shell cmd statusbar add-tile/click-tile` 在 OriginOS 上可能不生效，最终以 UI 添加为准。

## 三、磁贴行为

- 标签：`车锁切换`
- 副标题：`点按切换解闭锁`
- 点按后 1.2 秒显示 `已触发，正在连接...`，随后恢复。
- 服务未运行时，磁贴点击会尝试 `startForegroundService`；若系统限制后台启动，先打开一次 App 即可。
- 锁屏上是否可用取决于系统“锁屏快捷设置”开关；控制中心本身通常可在锁屏下拉。

## 四、代码

- `KeyTileService.java`
  - `onStartListening()` 更新磁贴；
  - `onClick()` 启动 `KeyRadarService` 并传 `ACTION_EXTERNAL_TOGGLE`；
- `AndroidManifest.xml`
  - `BIND_QUICK_SETTINGS_TILE` 权限；
  - `android.service.quicksettings.action.QS_TILE` intent-filter；
- `KeyRadarService`
  - `onStartCommand()` 处理外部切换；
  - `toggleLockFromExternal()` 复用电源键三连逻辑；
- `MainActivity`
  - 「添加快捷设置磁贴」按钮与 `requestAddTileService`。

## 五、验证

- v3.3.0 已安装。
- `dumpsys activity services com.luca.geelykeyradar` 可见 `KeyTileService` 被 `com.android.systemui` 绑定，说明系统已识别磁贴服务。
- App 内点击添加按钮，Toast 返回 `磁贴添加请求结果: 1`。
- 由于 OriginOS 控制中心是厂商自定义大磁贴布局，`cmd statusbar add-tile/click-tile` 未把磁贴放进面板；请在控制中心编辑里手动添加后点按验证。
- 点按后日志应出现：
  - `★ 快捷设置磁贴触发【解锁/上锁】`
  - `手动操作[快捷设置磁贴上锁/解锁]...`

## 六、实时状态（v3.3.2）

磁贴不再只显示静态文字；`KeyRadarService.publishTileStatus()` 会更新：

- 磁贴副标题（`Tile.setSubtitle`）；
- 磁贴状态描述（`setStateDescription`）；
- 常驻通知正文（`银河钥匙雷达 | <状态>`）；
- 持久日志（`🧩 磁贴状态: ...`）。

状态序列：

```text
点按磁贴
→ 蓝牙搜索中...
→ 蓝牙连接中...
→ 已连接，正在解锁/上锁...
→ 解锁成功 / 锁车成功
失败：
→ 连接失败，重试中...
→ 未发现车辆 / 连接失败
```

- 磁贴来源（source=`快捷设置磁贴`）**不再触发震动**；
- 成功/失败以车机真实 `0x0121` 锁态帧为准，不用乐观状态冒充“成功”；
- 自动靠近/远离触发时也会更新磁贴/通知状态。

### 实机验证（无车环境）

```text
[11:09:55.862] 🧩 磁贴状态: 蓝牙搜索中...
[11:09:55.866] 🧩 磁贴状态: 蓝牙连接中...
[11:10:02.899] 🧩 磁贴状态: 连接失败，重试中...
[11:10:09.960] 🧩 磁贴状态: 未发现车辆 / 连接失败
```

常驻通知同步为：

```text
银河钥匙雷达 | 未发现车辆 / 连接失败
```

## 七、关于“大磁贴/长磁贴”

标准 Android `TileService`/`Tile` **没有宽高 API**，第三方磁贴的尺寸由系统/厂商控制；无法强制做成 2x1 或 4x1。OriginOS 的大磁贴目前只对系统/厂商磁贴开放。

当前可做的：

- 磁贴副标题实时显示状态；
- 常驻通知同步显示完整状态；
- 如果必须要“长条大卡片”，应改用 **桌面 AppWidget（4x1）**，它的大小可控，但不是控制中心磁贴。

需要的话可以再加 4x1 桌面小组件版本。
