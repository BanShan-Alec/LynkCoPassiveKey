# 02. vivo 钱包 (`com.vivo.wallet`) 与 vivo 智能车载 (`com.vivo.car.networking`) 逆向手册

本文档记录了吉利银河如何将蓝牙车钥匙添加到 vivo 钱包，以及 vivo 车载组件如何执行 ICCOA / CCC 标准蓝牙测距与无感解闭锁。

---

## 一、吉利银河添加车钥匙到 vivo 钱包的完整调用链

在 `classes2.dex` (`com.geely.snc.digitalkey.wallet.DkWalletManager` 与 `com.geely.snc.digitalkey.manager.VehicleAdapterImpl`) 以及 `vivo.wallet` 的 DEX 中，逆向还原了开通钱包车钥匙的 4 个步骤：

### 1. 手机厂商与钱包支持性检测
- 吉利银河通过 `Build.MANUFACTURER` 识别 `vivo` / `iQOO` 设备。
- 检查系统中是否安装 `com.vivo.wallet` 与 `com.vivo.car.networking`。

### 2. 云端申请联合发卡凭证 (`cardToken`)
- 吉利银河向吉利 TSP 云端与 vivo 钱包服务端请求签发 ICCOA 数字车钥匙凭证（包含车辆 `vin`、`vehicleModel`、`bleMac`、长期对称/非对称密钥种子 `dkCert`）。

### 3. DeepLink / Intent 唤起 vivo 钱包透传页 (`TouchuanActivity`)
- 吉利银河构造隐式 Intent 跳转至 vivo 钱包：
  - **Scheme URI**：`vivowallet://car/directive?...`
  - **目标组件**：`com.vivo.wallet / com.vivo.wallet.car.TouchuanActivity`
  - **核心参数**：加密后的卡片开通指令包、车企代码 (`GEELY_GALAXY`)、蓝牙配对参数。

### 4. 写入安全单元与托管至 `com.vivo.car.networking`
- `com.vivo.wallet` 完成卡面渲染与 SE/TEE 密钥注入后，将后台蓝牙扫描与自动连接任务移交给系统级常驻服务 **`com.vivo.car.networking`**（vivo 智能车载）。

---

## 二、`com.vivo.car.networking` 蓝牙扫描与测距算法逆向

在 `/path/to/car_key_re/extracted/vivo_car/` 的 6 个 DEX 中，核心蓝牙钥匙逻辑位于：

### 1. 关键类清单
- `com.vivo.car.networking.car.ble.manager.BleConnectionManager`：负责管理与车辆 BLE 的 GATT 连接池、断开重连状态机。
- `com.vivo.car.networking.car.ble.scanner.NearCarScanListener`：处理靠近车辆时的低功耗蓝牙扫描回调。
- `com.vivo.car.networking.car.ble.scanner.ConnectScanner$b`：解析广播包中的车辆标识并触发连接。
- `com.vivo.car.networking.base.receiver.DigitalKeyReceiver`：接收来自系统蓝牙底座与钱包的广播事件。

### 2. vivo 车载蓝牙距离换算公式 (`b.f.b.a.d.c.i.a`)
在 `b.f.b.a.d.c.i` 类中，反编译出 vivo 计算手机到车辆物理距离（米）的标准对数路径损耗公式：

```java
public static double calcDistance(int rssi, int txPowerAt1m, double pathLossExponent) {
    // d = 10 ^ ((|RSSI| - A) / (10 * n))
    return Math.pow(10.0d, (Math.abs(rssi) - txPowerAt1m) / (10.0d * pathLossExponent));
}
```
其中：
- `rssi`：实时蓝牙信号强度（dBm，如 `-63`）。
- `A` (`txPowerAt1m`)：距离车机天线 1 米处的基准信号强度（吉利银河实测约为 **`-55 dBm`**）。
- `n` (`pathLossExponent`)：环境路径衰减指数（室外开阔地约 `2.0 ~ 2.4`，地下车库多径反射环境约 `2.2 ~ 2.8`）。

| 距离 (米) | 对应的理论 RSSI (`A = -55, n = 2.4`) | 实际场景含义 |
| :---: | :---: | :--- |
| `1.0 m` | `-55 dBm` | 紧贴主驾车门把手 |
| `1.8 m` | **`-61 ~ -63 dBm`** | **最佳迎宾解锁触发点**（伸手拉门前刚好解锁） |
| `3.0 m` | `-66 ~ -68 dBm` | 车旁站立/后备箱取物区（身体遮挡时瞬时跌至 `-76 dBm`） |
| `7.5 m` | **`-81 ~ -83 dBm`** | **最佳离开闭锁触发点**（确认真正走远） |
| `12.0 m` | `-87 ~ -89 dBm` | 远距靠近预唤醒区（提前建链鉴权） |

---

## 三、跨应用本地车控广播与服务接口

- **vivo 智能车载车控 Action**：`com.vivo.car.action.CONTROL`（Package: `com.vivo.car.networking`）
  - Extra 参数：`control_cmd = 1`（解锁）、`control_cmd = 2`（闭锁）、`vin = "...3245"`。
- **vivo 智能车载核心后台服务**：`com.vivo.car.networking.To VivoCar Service`。
