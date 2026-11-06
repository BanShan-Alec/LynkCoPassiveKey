# 31. v3.20.2：恢复桌面组件电池/燃油/综合续航

## 问题

v3.20.0 删除总里程/胎压时，误删了 `KeyWidgetProvider.updateWidget()` 中读取和绑定：

```text
widget_ev_range / widget_ev_percent
widget_fuel_range / widget_fuel_percent
widget_total_range
```

数据仍在 `publishWidgetRange()` 写入，但组件不再显示，表现为“电池续航没了”。

## 修复

恢复 `KeyWidgetProvider` 对三个续航区域的绑定：

```java
rv.setTextViewText(R.id.widget_ev, rangeText(evRange, evPct));
rv.setTextViewText(R.id.widget_fuel, rangeText(fuelRange, fuelPct));
rv.setTextViewText(R.id.widget_total, totalRange >= 0 ? totalRange + " km" : "-- km");
```

保留 v3.20.0 的删除项：总里程、胎压、无障碍读取不恢复。

## 实测

v3.20.2 已安装到手机，桌面第 5 页组件显示：

```text
电池 89 km · 70%
燃油 1149 km · 98%
综合 1238 km
```

## 版本

v3.20.2 / versionCode 55。
