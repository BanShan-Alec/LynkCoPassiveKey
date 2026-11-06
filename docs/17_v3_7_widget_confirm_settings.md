# 17. v3.7：桌面组件二次确认与右上角设置

本文档记录 v3.7.0 的组件防误触设置。

## 一、功能

- 组件右上角新增 **`⚙` 设置按钮**；
- 点开后出现「银河车锁 · 组件设置」：
  - 开关：`解锁/上锁二次确认（防误触）`
  - 默认关闭；
- 开启后，点组件的「解锁」或「上锁」：
  1. 先弹出确认对话框；
  2. 点「确认解锁/确认上锁」才执行；
  3. 点「取消」/返回不执行；
- 关闭时恢复原来的一键直接执行。

## 二、实现

- prefs：`geely_key_radar_prefs` → `widget_confirm_actions`（默认 false）
- `WidgetSettingsActivity`
  - 由组件 `⚙` 的 PendingIntent 打开；
  - Switch 改变即写入 prefs；
  - 广播 `ACTION_TILE_STATUS` 让组件刷新 PendingIntent；
- `KeyWidgetProvider`
  - 读取 `widget_confirm_actions`；
  - 关闭：解锁/上锁按钮 PendingIntent 直接指向 `KeyRadarService`；
  - 开启：PendingIntent 指向 `WidgetConfirmActivity`；
  - 刷新按钮不受影响（始终直接刷新）。
- `WidgetConfirmActivity`
  - 透明/对话框 Activity；
  - 二次确认开启时显示 AlertDialog；
  - 确认后启动 `KeyRadarService` 的 `ACTION_EXTERNAL_UNLOCK` / `ACTION_EXTERNAL_LOCK`；
  - 服务端再给 45ms 短震动并显示实时状态。

## 三、为什么用 Activity 确认

RemoteViews 本身不能弹 Dialog，所以：

- 组件按钮 → PendingIntent；
- 开启二次确认时 PendingIntent 指向确认 Activity；
- Activity 弹系统对话框，确认后才启动服务。

这样不需要常驻悬浮窗权限，也不会误触直接执行。

## 四、使用

1. 桌面组件右上角点 `⚙`；
2. 打开「解锁/上锁二次确认」；
3. 点「完成」；
4. 点「解锁」或「上锁」测试：
   - 弹「确认解锁车辆？」/「确认上锁车辆？」；
   - 确认后执行，取消不执行；
5. 想恢复一键执行，再进 `⚙` 关闭开关。

## 五、验证状态

- v3.7.0 已安装；
- `WidgetConfirmActivity` / `WidgetSettingsActivity` 已加入 Manifest；
- 组件未由我自动添加，需要你手动添加/更新组件后测试；
- 若之前添加过旧组件，建议删掉重新拖一次以加载新布局（齿轮/车辆状态/刷新）。

## 六、边界

- 二次确认关闭时，组件按钮仍是一键执行（保持原体验）；
- 刷新按钮不做二次确认，避免刷新也要确认；
- 确认对话框使用系统 Dialog，深色/浅色跟随系统主题；
- 组件 PendingIntent 每次刷新会按当前开关重建，切换开关后立即生效。

## 七、组件空白处点击打开 App（v3.7.1）

- 组件根布局 `widget_root` 设置 `PendingIntent` 打开 `MainActivity`；
- 点组件空白区域 → 打开「银河钥匙雷达」App；
- 按钮/刷新/齿轮是子 View，保留各自点击行为，不会误跳 App；
- 如果旧组件没有这个行为，删掉重新拖一次即可。

## 八、REA 环境

- `rea` CLI 已全局安装：`/opt/homebrew/bin/rea`，版本 `6.8.0`；
- 技能 `reverse-engineer-anything` 已从 `~/.claude/skills` / `~/.codex/skills` 软链到 `~/.config/magpie/library/skills/reverse-engineer-anything`；
- 当前会话技能目录已包含该技能，无需额外软链。
