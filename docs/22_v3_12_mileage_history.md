> **v3.20.0 已删除**：基于总里程的里程历史和行程 odometer 字段已移除；本页仅保留历史实现记录。

> **v3.18 重要更正**：`wg_tv_mileage` 的值 1238 正好等于纯电 89 + 燃油 1149，它其实是**综合续航**，不是行驶总里程。真正的行驶总里程在 `VehicleBasicStatus.odometer`（TSP/车辆状态页，5 万多 km），v3.12 把 1238 当总里程是错误的，v3.18 已清空旧数据。详见 [28_v3_18_odometer_correction.md](28_v3_18_odometer_correction.md)。

# 22. v3.12：总里程增量采集与行程里程

## 一、银河“行驶里程”到底从哪来？

REA/字节码结论：**不是手机与车机的 0x0121 BLE 帧直读**，而是车机 → TSP/云端 → 银河 App/小组件。

证据：

- `DkDataVehicleStatus`（0x0121 对应的车辆状态 bean）只有 `lock/door/window` 等字段，**没有 odometer/mileage**；
- `dk_icce.dex` 有：
  - `VehicleMileage`（`mileage`、`electricMileage`、`vehicleMileage`）
  - `Journey(startOdometer=..., endOdometer=...)`
  - `supportShowTotalMileage`
- `dk_vivo.dex` 有：
  - `TotalMileageResponse`
  - `ACTION_GET_VEHICLE_ODOMETER`
  - TSP 路径 `.../vehicle/status/ranking/odometer/vehicleModel/{vin}`
  - 本地缓存键 `car_mileage` / `car_lastmileage`
- 银河小组件里：
  - `wg_tv_mileage` = **综合续航**（不是总里程；v3.18 更正）
  - `wg_tv_update_time` = 云端刷新时间

所以它是**车机上传 TSP 后，App/小组件从云端缓存读取**；不是我们 GATT 直连能拿到的实时字段。

---

## 二、之前代码的 Bug

`traverseWidgetViewTree()` 原来写：

```java
if ("wg_tv_mileage".equals(entryName)) {
    out.totalRangeKm = v;   // v3.18 更正：这确实是综合续航
}
```

后果：

- `PhevRangeSnapshot` 没有独立里程字段；
- 总里程被当成 `totalRangeKm` 的 fallback；
- 行程记录只能拿 GPS 里程，无法用车机总里程。

---

## 三、v3.12 实现

### 1. 独立字段

```java
PhevRangeSnapshot.totalMileageKm
```

- `wg_tv_mileage` → `totalMileageKm`；
- `totalRangeKm` 不再被里程污染；
- 续航显示增加：

```text
🛣️总里程: 1238 km
```

### 2. 增量持久化

`KeyRadarService.onMileageObserved()`：

- 每次 `onPhevRangeUpdated()` 都检查 `totalMileageKm`；
- 只在新值 > 上次值时记录增量；
- 里程回退 >3 km 视为表重置/换源，重置基线并记录 `reset`；
- 写入 SharedPreferences：

```text
mileage_last_km
mileage_last_ms
mileage_last_delta
mileage_last_source
mileage_history_json   // 最多 200 个点 {t,km,d,src}
```

- 日志：

```text
🛣️ 首次同步总里程: 1238 km (11-05 13:36)
🛣️ 总里程 +5 km -> 1243 km (11-05 14:20)
```

### 3. 行程起止里程

`PhevTripRecord` 新增：

```java
startOdometerKm
endOdometerKm
odometerDistKm
```

- 行程开始：记录当前总里程；
- 行程中每次云端/组件更新：刷新 end；
- 行程结束：结算 `end - start`；
- 行程结束后 25 秒内的云端回填也会更新 end；
- `formatDeltaSummary()` 增加：

```text
🛣️总里程: 1238→1250 km (车机行驶 12 km)
```

- JSON 持久化兼容旧记录（缺失字段默认 -1）。

### 4. 数据频率

- 不是实时每公里；只在银河小组件/云端刷新时更新；
- 来源时间以 `wg_tv_update_time` 为准；
- 因此 `odometerDistKm` 是“云端总里程差”，适合行程结算，不适合秒级轨迹。

---

## 四、实机首次数据

```text
[14:20:20.558] 🛣️ 首次同步总里程: 1238 km (11-05 13:36)
```

- 版本：3.12.0
- 前台服务正常、无 crash

> 请核对一下车机仪表总里程是否也是 1238 km。如果仪表显示的是 1243 km 左右，那 `wg_tv_mileage` 可能是“综合续航”而不是总里程，需要再换字段；目前文档/资源名都指向总里程。

---

## 五、边界

- 银河云端/小组件更新有延迟，里程增量可能合并成一段；
- 车辆停放时总里程不变，不会产生历史点；
- 若 App 未收到小组件推送，本次行程可能没有车机里程，但 GPS 里程仍保留；
- `mileage_history_json` 存在 App 私有 SharedPreferences，卸载会丢失。
