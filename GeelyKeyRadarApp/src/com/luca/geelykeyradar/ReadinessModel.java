package com.luca.geelykeyradar;

import java.util.Locale;

/**
 * 新车主就绪度自检评估器 (ReadinessModel).
 * 纯逻辑层：聚合各项系统探针状态，判定 3 项必需与 1 项建议的就绪度及胶囊文案。
 * 遵循纯 Java 设计，可在 JVM 单元测试中 100% 覆盖。
 */
public final class ReadinessModel {

    public static final int REQUIRED_TOTAL = 3;

    public static final class Snapshot {
        public final boolean officialAppInstalled;
        public final boolean btScanGranted;
        public final boolean notifAccessGranted;
        public final boolean notifConnected;
        public final boolean corePermissionsGranted;
        public final boolean vehicleLearned;
        public final String vehicleName;
        public final String vehicleAddress;
        public final boolean batteryWhitelisted;

        public final int requiredCompleted;
        public final int requiredTotal;
        public final boolean isFullyReady;

        public final String badgeText;
        public final int badgeColor;
        public final int badgeBgColor;

        public Snapshot(
                boolean officialAppInstalled,
                boolean btScanGranted,
                boolean notifAccessGranted,
                boolean notifConnected,
                boolean corePermissionsGranted,
                boolean vehicleLearned,
                String vehicleName,
                String vehicleAddress,
                boolean batteryWhitelisted,
                int requiredCompleted,
                int requiredTotal,
                boolean isFullyReady,
                String badgeText,
                int badgeColor,
                int badgeBgColor
        ) {
            this.officialAppInstalled = officialAppInstalled;
            this.btScanGranted = btScanGranted;
            this.notifAccessGranted = notifAccessGranted;
            this.notifConnected = notifConnected;
            this.corePermissionsGranted = corePermissionsGranted;
            this.vehicleLearned = vehicleLearned;
            this.vehicleName = vehicleName != null ? vehicleName : "";
            this.vehicleAddress = vehicleAddress != null ? vehicleAddress : "";
            this.batteryWhitelisted = batteryWhitelisted;
            this.requiredCompleted = requiredCompleted;
            this.requiredTotal = requiredTotal;
            this.isFullyReady = isFullyReady;
            this.badgeText = badgeText;
            this.badgeColor = badgeColor;
            this.badgeBgColor = badgeBgColor;
        }
    }

    public Snapshot evaluate(
            boolean officialAppInstalled,
            boolean btScanGranted,
            boolean notifAccessGranted,
            boolean notifConnected,
            boolean identityLearned,
            String vehicleName,
            String vehicleAddress,
            boolean batteryWhitelisted,
            boolean bluetoothOn,
            boolean serviceEnabled,
            boolean chargingProtected,
            boolean geelyConnected
    ) {
        boolean corePermissions = btScanGranted && notifAccessGranted;
        boolean vehicleReady = identityLearned;

        int completed = 0;
        if (officialAppInstalled) completed++;
        if (corePermissions) completed++;
        if (vehicleReady) completed++;

        boolean fullyReady = (completed >= REQUIRED_TOTAL);

        String badgeText;
        int badgeColor;
        int badgeBg = PaseoTheme.COLOR_SURFACE_2;

        if (!fullyReady) {
            badgeText = String.format(Locale.ROOT, "● 待就绪 (%d/%d)", completed, REQUIRED_TOTAL);
            badgeColor = PaseoTheme.COLOR_IDENTITY_ORANGE;
        } else if (!serviceEnabled) {
            badgeText = "● 已暂停";
            badgeColor = PaseoTheme.COLOR_TEXT_TERTIARY;
        } else if (!bluetoothOn) {
            badgeText = "● 蓝牙未开";
            badgeColor = PaseoTheme.COLOR_ERROR;
        } else if (chargingProtected) {
            badgeText = "● 充电保护";
            badgeColor = PaseoTheme.COLOR_WARNING;
        } else if (geelyConnected) {
            badgeText = "● 守护中";
            badgeColor = PaseoTheme.COLOR_PRIMARY_TEXT;
        } else {
            badgeText = "● 搜索中";
            badgeColor = PaseoTheme.COLOR_INFO;
        }

        return new Snapshot(
                officialAppInstalled,
                btScanGranted,
                notifAccessGranted,
                notifConnected,
                corePermissions,
                vehicleReady,
                vehicleName,
                vehicleAddress,
                batteryWhitelisted,
                completed,
                REQUIRED_TOTAL,
                fullyReady,
                badgeText,
                badgeColor,
                badgeBg
        );
    }
}
