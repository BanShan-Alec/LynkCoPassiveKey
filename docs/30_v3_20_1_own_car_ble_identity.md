# 30. v3.20.1：只认本车蓝牙，修复别人车旁显示已解锁

> **v4.0 说明**：本文的“本车 VIN/MAC 写死”方案已被极简通用版取代。v4.0 从 BLE 广播和官方 App 连接事件自动学习本车身份，同时保留“多辆吉利车时只让主候选进入 RSSI 流”的隔离思路。新方案见 **[36_minimal_generic_passive_key.md](36_minimal_generic_passive_key.md)**。

## 根因

`KeyRadarService` 的 BLE 扫描匹配过宽：

```text
0x01FE 厂商数据：任何 Geely/UAES 广播都当成自己车
0xFDFD 服务 UUID：所有吉利车共用，也被当成自己车
设备名：只判断 startsWith("GeelyVehicle")
15FF：matchAnyGeely15FF = true，任何吉利的 15FF 帧都匹配
```

因此站在另一辆吉利车旁时，对方广播会进入 RSSI 流程，触发“靠近自动解锁”，
或者让界面显示“已解锁”。这不是信号强度测试问题，是**车辆身份判断**错误。

## 修复

只接受本车身份：

- `0x01FE`：MAC 属于本车，或名称精确等于 `GeelyVehicle< VIN后3位 >`，
  或 VIN 后两位匹配且没有出现其他车名；
- `0xFDFD`：必须同时有本车名称或本车 MAC，裸 UUID 不再匹配；
- `15FF`：必须 VIN 后两位匹配（且没有其他车名），或本车名称/MAC；
  删除 `matchAnyGeely15FF`；
- 已知 MAC 仅保留本车 MAC；
- `isLikelyCarDevice()` 不再接受任意 `GeelyVehicle*` 名称；
- 20 秒没有本车 BLE 信号且吉利未连接时，`UNLOCKED` 回退为 `UNKNOWN`，
  不再在别人车旁显示“已解锁”。

当前 VIN：`LB378LEZ0XX000123`，本车广播名 `GeelyVehicle123`，
VIN 尾号 `23`。

## 结果

- RSSI 波形/信号源只来自本车广播或本车 GATT 连接；
- 其他吉利车的广播不会再触发自动解锁；
- 本车无信号时显示“未知/待命”，不会假装已解锁。

## 版本

v3.20.1 / versionCode 54。
