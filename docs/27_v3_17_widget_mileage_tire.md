# 27. v3.17 组件总里程/胎压实验（v3.20.0 已删除，归档）

## 结论

v3.17 试图在桌面组件显示“总里程 + 胎压”，该功能已撤回，相关代码已在 v3.20.0 删除：

- 删除 `VehicleStatusSnapshot` 的 `tireFl/tireFr/tireRl/tireRr/tireUnit/odometerKm`；
- 删除 `PhevRangeSnapshot.configName/configValue` 与 `wg_tv_config_*` 解析；
- 删除 `KeyRadarService` 的 `publishWidgetTire`、`publishWidgetOdometer`、里程历史与 `onMileageObserved`；
- 删除 `PhevTripRecord` 的 `startOdometerKm/endOdometerKm/odometerDistKm`；
- 删除 `GeelyStatusAccessibilityService`、无障碍配置与组件设置入口；
- 组件不再显示 `总里程`、`胎压`。

## 为什么撤回

- `wg_tv_mileage` 的 1238 是**综合续航**（纯电 89 + 燃油 1149），不是行驶总里程；
- 真实总里程/胎压在 `PlatformVehicleStatus.vehicleRunningStatus.odometer`、
  `vehicleTyreStatus.*`，位于银河进程/TSP；
- 银河的小组件 `WidgetUiStatus` 只投影续航、警告和控制状态，不包含 odometer/胎压；
- 第三方 App 没有公开 Binder/Provider 可读取完整 `PlatformVehicleStatus`；
- 无障碍只能读取银河“车辆状态”页可见内容，打开页面才更新，无法后台自动刷新。

## 保留

油电续航、车辆状态、蓝牙雷达、锁态、摇一摇、电源键三连、行程续航折扣和日志均保留。
