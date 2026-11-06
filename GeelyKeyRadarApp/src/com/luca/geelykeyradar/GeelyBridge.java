package com.luca.geelykeyradar;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanResult;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * 对吉利银河官方 App 的最小桥接。
 *
 * 只使用官方 App 已经导出的组件：
 * - MiuiService / MiuiReceiver：把本应用扫到的 ScanResult 交给官方钥匙 SDK；
 * - GLDkConnectionService / DigitalKeyBgService：唤醒官方后台钥匙进程；
 * - 通知栏控制广播：作为通知按钮点击失败时的兜底。
 *
 * 不读取官方 App 私有数据，不依赖 adb/REA，也不写死 VIN/MAC。
 */
final class GeelyBridge {

    private static final String TAG = "GeelyBridge";

    static final String GEELY_PKG = "com.geelygalaxy.customer";
    static final String GEELY_DK_SERVICE = "com.geely.snc.digitalkey.base.service.DigitalKeyBgService";
    static final String GEELY_MIUI_SERVICE = "com.ecarx.blekey.thirdadapter.service.MiuiService";
    static final String GEELY_MIUI_RECEIVER = "com.ecarx.blekey.thirdadapter.receiver.MiuiReceiver";
    static final String GEELY_GLDK_SERVICE = "com.geely.lib.dk.ble.GLDkConnectionService";

    static final String ACTION_DK_START = "com.zeekr.snc.service.action.DK_START";
    static final String ACTION_PENDING_SCAN = "com.geely.pendintent.scanresult";
    static final String ACTION_MIUI_SERVICE = "com.geely.geometry.miuiservice.action";
    static final String ACTION_MIUI_CONSUMER = "com.geely.consumer.miuiservice.action";
    static final String ACTION_NOTIFICATION_CTRL = "com.snc.dk.rv.notification.ctrl";
    static final String ACTION_REMOTE_VIEW_CLICK = "com.geely.cockpit.ble.RemoteViewClick";
    static final String EXTRA_CMD = "com.extra.cmd";

    // 逻辑控制类型
    static final int CTRL_QUERY = 0;
    static final int CTRL_UNLOCK = 1;
    static final int CTRL_LOCK = 2;

    /**
     * 将逻辑控制类型转换为吉利银河 DK 1.5 (DkControlType15 / DkCmdType15) 的真实广播命令值：
     * 在 DkRemoteView.buildRemoteView 中：
     * - DkControlType15.Lock   = CMD_CLOSE_DOOR15 = 1 (上锁)
     * - DkControlType15.Unlock = CMD_OPEN_DOOR15  = 2 (解锁)
     */
    static int toDk15Cmd(int logicalCtrlType) {
        if (logicalCtrlType == CTRL_UNLOCK) {
            return 2;
        }
        if (logicalCtrlType == CTRL_LOCK) {
            return 1;
        }
        return logicalCtrlType;
    }

    private static volatile boolean keepAliveBound = false;

    private static final ServiceConnection KEEP_ALIVE_CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "官方钥匙服务保活绑定成功: " + name);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "官方钥匙服务保活绑定断开: " + name);
            keepAliveBound = false;
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "官方钥匙服务保活绑定失效: " + name);
            keepAliveBound = false;
        }
    };

    private GeelyBridge() {
    }

    static boolean isGeelyInstalled(Context context) {
        if (context == null) return false;
        try {
            context.getPackageManager().getPackageInfo(GEELY_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 绑定官方钥匙服务做保活；重复调用是安全的。
     */
    static synchronized void bindKeepAlive(Context context) {
        if (context == null || !isGeelyInstalled(context)) return;
        if (keepAliveBound) return;

        try {
            Intent miui = new Intent(ACTION_MIUI_SERVICE);
            miui.setComponent(new ComponentName(GEELY_PKG, GEELY_MIUI_SERVICE));
            context.bindService(miui, KEEP_ALIVE_CONNECTION,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            keepAliveBound = true;
            Log.i(TAG, "已绑定银河 MiuiService 保活");
        } catch (Throwable t) {
            Log.w(TAG, "绑定 MiuiService 失败: " + t.getMessage());
        }

        try {
            Intent dk = new Intent(ACTION_DK_START);
            dk.setComponent(new ComponentName(GEELY_PKG, GEELY_DK_SERVICE));
            context.bindService(dk, KEEP_ALIVE_CONNECTION,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        } catch (Throwable ignored) {
            // DigitalKeyBgService 在新版清单里可能是非 exported，失败不影响 MiuiService 主链路。
        }
    }

    /**
     * 把一条真实扫描到的吉利车机广播交给官方 App，让官方 App 自己去完成 V2 鉴权。
     *
     * 注意：这里绝不主动 connectGatt 占车机唯一的连接槽位。
     */
    static void wakeAndConnect(Context context, ScanResult scanResult, String carId) {
        if (context == null || scanResult == null) return;
        if (!isGeelyInstalled(context)) {
            Log.w(TAG, "未安装吉利银河 App，无法触发蓝牙钥匙");
            return;
        }

        String safeCarId = (carId == null || carId.trim().isEmpty()) ? "geely-ble" : carId.trim();
        String mac = (scanResult.getDevice() != null) ? scanResult.getDevice().getAddress() : "unknown";
        Log.i(TAG, "注入 ScanResult 唤醒银河: carId=" + safeCarId + ", mac=" + mac);

        // 1. 官方 V2 直连核心入口：MiuiService + MiuiReceiver
        sendMiuiService(context, ACTION_MIUI_CONSUMER, safeCarId, scanResult);
        sendMiuiService(context, ACTION_MIUI_SERVICE, safeCarId, scanResult);
        sendMiuiReceiver(context, safeCarId, scanResult);

        // 2. 唤醒底层连接管理服务
        try {
            Intent gldk = new Intent();
            gldk.setComponent(new ComponentName(GEELY_PKG, GEELY_GLDK_SERVICE));
            gldk.putExtra("setCarId", safeCarId);
            context.startService(gldk);
        } catch (Throwable ignored) {
        }

        // 3. 显式启动 DigitalKeyBgService，突破部分 OEM 的后台启动限制
        try {
            Intent dk = new Intent(ACTION_DK_START);
            dk.setComponent(new ComponentName(GEELY_PKG, GEELY_DK_SERVICE));
            dk.putExtra("setCarId", safeCarId);
            dk.putExtra("ScanResult", scanResult);
            context.startService(dk);
        } catch (Throwable ignored) {
        }
        try {
            Intent pendingScan = new Intent(ACTION_PENDING_SCAN);
            pendingScan.setComponent(new ComponentName(GEELY_PKG, GEELY_DK_SERVICE));
            pendingScan.putExtra("setCarId", safeCarId);
            pendingScan.putExtra("ScanResult", scanResult);
            context.startService(pendingScan);
        } catch (Throwable ignored) {
        }

        bindKeepAlive(context);
    }

    private static void sendMiuiService(Context context, String action, String carId, ScanResult scanResult) {
        try {
            Intent intent = new Intent(action);
            intent.setComponent(new ComponentName(GEELY_PKG, GEELY_MIUI_SERVICE));
            intent.putExtra("setCarId", carId);
            intent.putExtra("ScanResult", scanResult);
            context.startService(intent);
        } catch (Throwable t) {
            Log.w(TAG, "启动 MiuiService(" + action + ") 失败: " + t.getMessage());
        }
    }

    private static void sendMiuiReceiver(Context context, String carId, ScanResult scanResult) {
        try {
            Intent intent = new Intent(ACTION_MIUI_CONSUMER);
            intent.setComponent(new ComponentName(GEELY_PKG, GEELY_MIUI_RECEIVER));
            intent.putExtra("setCarId", carId);
            intent.putExtra("ScanResult", scanResult);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "广播 MiuiReceiver 失败: " + t.getMessage());
        }
    }

    /**
     * 通知按钮点击失败时的兜底广播。不是主路径，避免重复下发。
     */
    static void sendControlFallback(Context context, int controlType) {
        if (context == null) return;
        int dk15Cmd = toDk15Cmd(controlType);
        try {
            Intent intent = new Intent(ACTION_NOTIFICATION_CTRL);
            intent.setPackage(GEELY_PKG);
            intent.putExtra(EXTRA_CMD, dk15Cmd);
            context.sendBroadcast(intent);
            Log.i(TAG, "已发送通知控制兜底广播 logical=" + controlType + " dk15Cmd=" + dk15Cmd);
        } catch (Throwable t) {
            Log.w(TAG, "通知控制兜底广播失败: " + t.getMessage());
        }
        try {
            Intent rvIntent = new Intent(ACTION_REMOTE_VIEW_CLICK);
            rvIntent.setPackage(GEELY_PKG);
            rvIntent.putExtra(EXTRA_CMD, dk15Cmd);
            rvIntent.putExtra("ctrlType", dk15Cmd);
            context.sendBroadcast(rvIntent);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 尝试从系统 GATT 连接列表里找出银河 App 正在连接的车机。
     *
     * 某些 ROM 能返回官方 App 的 GATT 客户端；返回空也不影响广播学习路径。
     */
    static List<BluetoothDevice> getConnectedGattDevices(Context context) {
        if (context == null) return Collections.emptyList();
        try {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager == null) return Collections.emptyList();
            List<BluetoothDevice> devices = manager.getConnectedDevices(BluetoothProfile.GATT);
            return devices == null ? Collections.<BluetoothDevice>emptyList() : devices;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }
}
