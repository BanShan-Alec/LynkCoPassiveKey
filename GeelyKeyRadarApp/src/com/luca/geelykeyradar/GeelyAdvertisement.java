package com.luca.geelykeyradar;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;

import java.util.List;

import android.os.ParcelUuid;
import android.os.SystemClock;

/**
 * 一条吉利数字钥匙 BLE 广播。
 *
 * 兼容三代常见广播特征：
 * - Company ID 0x01FE（510）的 UAES/吉利 V2 厂商数据；
 * - 设备名 GeelyVehicle<VIN后三位>；
 * - Service UUID 0xFDFD + 吉利设备名；
 * - 原始 15FF 厂商帧。
 *
 * 不写死任何 VIN、MAC 或车型。
 */
final class GeelyAdvertisement {

    static final int COMPANY_ID_GEELY = 510; // 0x01FE

    final ScanResult scanResult;
    final String address;
    final String name;
    final int rssi;
    final long seenAtMs;
    final String vinTail2;
    final String vinTail3;
    final String mfgSignature;
    final String sourceTag;

    private GeelyAdvertisement(
            ScanResult scanResult,
            String address,
            String name,
            int rssi,
            long seenAtMs,
            String vinTail2,
            String vinTail3,
            String mfgSignature,
            String sourceTag
    ) {
        this.scanResult = scanResult;
        this.address = address == null ? "" : address;
        this.name = name == null ? "" : name.trim();
        this.rssi = rssi;
        this.seenAtMs = seenAtMs;
        this.vinTail2 = vinTail2 == null ? "" : vinTail2;
        this.vinTail3 = vinTail3 == null ? "" : vinTail3;
        this.mfgSignature = mfgSignature == null ? "" : mfgSignature;
        this.sourceTag = sourceTag == null ? "" : sourceTag;
    }

    /**
     * 从系统扫描结果解析；不是吉利钥匙广播时返回 null。
     */
    static GeelyAdvertisement from(ScanResult result) {
        if (result == null) return null;
        ScanRecord record = result.getScanRecord();
        BluetoothDevice device = result.getDevice();
        if (record == null && device == null) return null;

        String address = "";
        String name = "";
        try {
            if (device != null && device.getAddress() != null) {
                address = device.getAddress();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (record != null && record.getDeviceName() != null) {
                name = record.getDeviceName();
            } else if (device != null && device.getName() != null) {
                name = device.getName();
            }
        } catch (Throwable ignored) {
        }

        byte[] mfgPayload = null;
        if (record != null) {
            try {
                mfgPayload = record.getManufacturerSpecificData(COMPANY_ID_GEELY);
            } catch (Throwable ignored) {
            }
        }

        boolean geelyName = isGeelyVehicleName(name);
        boolean hasFdfd = hasServiceUuid(record, "0000fdfd-0000-1000-8000-00805f9b34fb");
        String rawHex = "";
        if (record != null && record.getBytes() != null) {
            rawHex = bytesToHex(record.getBytes());
        }
        int idx15ff = rawHex.indexOf("15FF");
        // 只需要 vendor/version/VIN 尾号所在的前 8 字节；不同车型的 15FF 帧长度不完全一致。
        boolean raw15ff = idx15ff >= 0 && idx15ff + 4 + 16 <= rawHex.length();

        boolean companyGeely = mfgPayload != null && mfgPayload.length >= 2;
        if (!companyGeely && !geelyName && !(hasFdfd && !name.isEmpty()) && !raw15ff) {
            return null;
        }

        String vinTail2 = "";
        String signature = "";
        String sourceTag;

        if (companyGeely) {
            if (mfgPayload.length >= 6
                    && isPrintableAscii(mfgPayload[4])
                    && isPrintableAscii(mfgPayload[5])) {
                vinTail2 = new String(new byte[]{mfgPayload[4], mfgPayload[5]});
            }
            // 0x01FE 的前 6 字节是 vendor/version/固定位 + VIN 尾号；后面的字节可能随状态变化。
            int sigLen = Math.min(6, mfgPayload.length);
            signature = bytesToHex(mfgPayload, 0, sigLen);
            sourceTag = "01FE";
        } else if (raw15ff) {
            String payloadHex = rawHex.substring(idx15ff + 4, idx15ff + 4 + 16);
            byte[] payload = hexToBytes(payloadHex);
            if (payload.length >= 8) {
                int vendor = payload[2] & 0xFF;
                int version = payload[3] & 0xFF;
                if (!geelyName && vendor != 6) {
                    return null;
                }
                if (isPrintableAscii(payload[6]) && isPrintableAscii(payload[7])) {
                    vinTail2 = new String(new byte[]{payload[6], payload[7]});
                }
                int sigLen = Math.min(8, payload.length);
                signature = bytesToHex(payload, 0, sigLen);
                sourceTag = "15FF v" + vendor + "." + version;
            } else {
                return null;
            }
        } else if (geelyName) {
            sourceTag = "NAME";
        } else {
            sourceTag = "FDFD";
        }

        String vinTail3 = vinTailFromName(name);
        if (address.isEmpty() && name.isEmpty() && vinTail2.isEmpty()) {
            return null;
        }

        return new GeelyAdvertisement(
                result,
                address,
                name,
                result.getRssi(),
                SystemClock.elapsedRealtime(),
                vinTail2,
                vinTail3,
                signature,
                sourceTag
        );
    }

    /**
     * 传给银河 MiuiService 的非空 carId。
     *
     * 官方 MiuiService 只用它做日志/回执标识，真正鉴权使用 ScanResult 和吉利自己的钥匙配置；
     * 因此这里不需要、也不应该写死用户 VIN。
     */
    String carId() {
        if (!name.isEmpty()) return name;
        if (!vinTail2.isEmpty()) return "lynkco-vin-" + vinTail2;
        return "lynkco-ble";
    }

    String stableKey() {
        return address + "|" + name + "|" + vinTail2 + "|" + mfgSignature;
    }

    String displayName() {
        if (!name.isEmpty()) return name;
        if (!vinTail2.isEmpty()) return "LynkCo??" + vinTail2;
        return address;
    }

    String displayDetail() {
        StringBuilder sb = new StringBuilder();
        sb.append(displayName());
        if (!address.isEmpty()) sb.append(" · ").append(address);
        if (!sourceTag.isEmpty()) sb.append(" · ").append(sourceTag);
        return sb.toString();
    }

    static boolean isGeelyVehicleName(String value) {
        if (value == null) return false;
        String n = value.trim();
        return n.regionMatches(true, 0, "GeelyVehicle", 0, "GeelyVehicle".length())
                || n.regionMatches(true, 0, "LynkCo", 0, "LynkCo".length())
                || n.regionMatches(true, 0, "Lynk", 0, "Lynk".length());
    }

    static String vinTailFromName(String value) {
        if (!isGeelyVehicleName(value)) return "";
        String trimmed = value.trim();
        int prefixLen = 0;
        if (trimmed.regionMatches(true, 0, "GeelyVehicle", 0, "GeelyVehicle".length())) {
            prefixLen = "GeelyVehicle".length();
        } else if (trimmed.regionMatches(true, 0, "LynkCo", 0, "LynkCo".length())) {
            prefixLen = "LynkCo".length();
        } else if (trimmed.regionMatches(true, 0, "Lynk", 0, "Lynk".length())) {
            prefixLen = "Lynk".length();
        }
        String suffix = (prefixLen > 0 && prefixLen <= trimmed.length()) ? trimmed.substring(prefixLen) : trimmed;
        int end = suffix.length();
        while (end > 0 && Character.isDigit(suffix.charAt(end - 1))) {
            end--;
        }
        String digits = suffix.substring(end);
        if (digits.isEmpty()) return "";
        return digits.length() <= 3 ? digits : digits.substring(digits.length() - 3);
    }

    private static boolean hasServiceUuid(ScanRecord record, String uuid) {
        if (record == null) return false;
        try {
            List<ParcelUuid> uuids = record.getServiceUuids();
            if (uuids == null) return false;
            for (ParcelUuid item : uuids) {
                if (item != null && item.toString().equalsIgnoreCase(uuid)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isPrintableAscii(byte value) {
        int v = value & 0xFF;
        return v >= 0x20 && v <= 0x7E;
    }

    static String bytesToHex(byte[] data) {
        if (data == null || data.length == 0) return "";
        return bytesToHex(data, 0, data.length);
    }

    static String bytesToHex(byte[] data, int offset, int length) {
        if (data == null || data.length == 0 || length <= 0) return "";
        int start = Math.max(0, offset);
        int end = Math.min(data.length, start + length);
        StringBuilder sb = new StringBuilder((end - start) * 2);
        for (int i = start; i < end; i++) {
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() < 2) return new byte[0];
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return new byte[0];
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
