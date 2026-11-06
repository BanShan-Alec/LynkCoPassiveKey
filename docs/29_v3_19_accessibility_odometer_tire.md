# 29. v3.19 只读无障碍读取总里程/胎压（v3.20.0 已删除，归档）

## 结论

v3.19 曾增加只读 `AccessibilityService`，在用户打开银河“车辆状态”页时读取
`VehicleRunningStatus.odometer` 和 `tv_car_tyre_*_num`，写入桌面组件。

该功能已在 v3.20.0 完整删除：

- `GeelyStatusAccessibilityService.java`
- `res/xml/accessibility_service_config.xml`
- `AndroidManifest.xml` 中的无障碍服务声明
- `WidgetSettingsActivity` 的开启/打开入口
- `widget_total_mileage`、`widget_tire_text` 及相关解析

## REA 边界

- 银河进程内的 `DataProvider.getVehicleStatus()` 有完整 `PlatformVehicleStatus`；
- 但 `DigitalKeyBgService` 不可导出，`CommonProvider.query()` 返回 null，
  完整状态只存在银河私有进程和 MMKV；
- 银河小组件 `WidgetUiStatus` 只投影续航、警告、控制状态，不含 odometer/胎压；
- 无障碍只能读取可见页面，必须打开银河状态页才有内容事件，不能后台自动更新。

因此普通第三方权限无法实现“不打开银河也自动更新总里程/胎压”。若以后继续，需要吉利官方
API/SDK 或银河提供受权限保护的导出服务。
