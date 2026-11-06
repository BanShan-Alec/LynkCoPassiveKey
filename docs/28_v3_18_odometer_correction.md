> **v3.20.0 已删除**：真实 odometer/胎压读取代码与无障碍服务已移除。本页只保留字段语义纠正记录。

# 28. v3.18：总里程语义纠正（1238 是续航，不是 5 万 km）

## 一、纠正

之前把 `wg_tv_mileage = 1238` 当成“行驶总里程”，这是错误的。

`wg_tv_mileage` 的 1238 正好等于：

```text
纯电 89 km + 燃油 1149 km = 综合续航 1238 km
```

它是**综合续航**，不是车辆累计行驶总里程。

用户车辆真实总里程是 **5 万多 km**。

## 二、REA 找到的真实字段

真实总里程在：

```text
com.geely.cockpit.services.controlservice.bean.VehicleBasicStatus
  odometer   : String   // 行驶总里程
  tripMeter  : String   // 小计里程
```

以及：

```text
PlatformVehicleStatus.vehicleRunningStatus.odometer
TotalMileageResponse.totalMileage
ACTION_GET_VEHICLE_ODOMETER
TSP .../vehicle/status/ranking/odometer/vehicleModel/{vin}
```

数据来源是 **车机 → TSP/云端 → 银河车辆状态页**；不在 0x0121 的 9 字节 BLE 帧，也不在 `wg_tv_mileage` 里。

## 三、v3.18 修复

1. `wg_tv_mileage` 回退为 `totalRangeKm`（综合续航）；
2. 删除 `PhevRangeSnapshot.totalMileageKm` 的误用；
3. 新增真实字段：

```java
VehicleStatusSnapshot.odometerKm
```

仅在 JSON 里出现 `odometer` / `totalMileage` / `vehicleMileage` 时才采用；
4. 清理旧数据：

```text
mileage_last_km = -1
widget_total_mileage = -1
mileage_history_json = []
```

5. 旧行程的 `startOdometerKm/endOdometerKm/odometerDistKm` 全部清空（它们来自 1238 续航，不是里程）；
6. 组件改为：

```text
总里程 -- · 胎压 --
```

直到拿到真实 `odometer` / 胎压。

## 四、当前能力与限制

- **综合续航 1238 km**：可以正常显示（电池/燃油/综合）。
- **真实 5 万 km 总里程**：当前通知栏桥接模式拿不到 `PlatformVehicleStatus`，所以组件显示 `--`。
- **胎压**：同样属于 `VehicleTyreStatus`/TSP，当前显示 `--`。

要把 5 万 km 和胎压显示出来，需要以下之一：

1. **无障碍读取银河车辆状态页**（可行，需用户开启无障碍并打开一次“车辆状态”页）：
   - 总里程：`VehicleRunningStatus.odometer` 对应页面文本；
   - 胎压：`tv_car_tyre_*_num`。
2. **Geely AIDL/完整 PlatformVehicleStatus**：当前服务权限不足。
3. **TSP API**：需要吉利账号/凭证，不做。

## 五、当前安装

```text
版本：3.18.0
前台服务：正常
Crash：无
```

日志：

```text
⚠ 旧行程中的“总里程”字段来自综合续航，已清空，等待真实 odometer
⚠ 修正数据语义：wg_tv_mileage 是综合续航，旧版误存的 1238km 总里程已清空
```

组件需要重加/刷新后显示：

```text
总里程 -- · 胎压 --
```

## 六、下一步建议

如果一定要 5 万 km 真实总里程，建议做**只读无障碍读取**：

- 只监听 `com.geelygalaxy.customer`；
- 不点击、不改设置；
- 你打开一次银河“车辆状态”页后，抓取 `odometer` 和四轮胎压，写入组件；
- 之后组件显示真实值。

是否实现，你确认。
