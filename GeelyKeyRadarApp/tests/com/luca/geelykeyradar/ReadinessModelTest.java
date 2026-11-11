package com.luca.geelykeyradar;

/**
 * 验证新车主使用前就绪自检评估器 (ReadinessModel) 的判定规则。
 * 核心验证：
 * 1. 3 项必需（官方 App、核心系统权限、车辆自学习）与 1 项建议（后台白名单）的解耦；
 * 2. 关键业务决策：未开启后台保活白名单绝对不阻塞全面就绪 (isFullyReady == true)；
 * 3. 状态胶囊文案与 Paseo 色彩映射。
 */
public final class ReadinessModelTest {

    public static void main(String[] args) {
        testColdBootAllMissing();
        testPartialReady();
        testCriticalDecisionOptionalBatteryDoesNotBlockReady();
        testAllFourComplete();
        testServiceDisabledOrBluetoothOffWhenReady();
        System.out.println("ReadinessModelTest passed");
    }

    private static void testColdBootAllMissing() {
        ReadinessModel model = new ReadinessModel();
        ReadinessModel.Snapshot snapshot = model.evaluate(
                false, // officialAppInstalled
                false, // btScanGranted
                false, // notifAccessGranted
                false, // notifConnected
                false, // identityLearned
                "",    // vehicleName
                "",    // vehicleAddress
                false, // batteryWhitelisted
                true,  // bluetoothOn
                true,  // serviceEnabled
                false, // chargingProtected
                false  // geelyConnected
        );

        assert snapshot.requiredCompleted == 0 : "冷启动无配置应为 0 项完成";
        assert snapshot.requiredTotal == 3 : "必需项总数固定为 3";
        assert !snapshot.isFullyReady : "缺少必需项不可判定为全面就绪";
        assert !snapshot.officialAppInstalled;
        assert !snapshot.corePermissionsGranted;
        assert !snapshot.vehicleLearned;
        assert !snapshot.batteryWhitelisted;
        assert "● 待就绪 (0/3)".equals(snapshot.badgeText) : "胶囊应显示待就绪 (0/3)";
        assert snapshot.badgeColor == PaseoTheme.COLOR_IDENTITY_ORANGE : "未就绪应使用 identity-orange";
    }

    private static void testPartialReady() {
        ReadinessModel model = new ReadinessModel();
        // 官方 App 已装，权限已给，但尚未在车旁连过车
        ReadinessModel.Snapshot snapshot = model.evaluate(
                true,  // officialAppInstalled
                true,  // btScanGranted
                true,  // notifAccessGranted
                true,  // notifConnected
                false, // identityLearned (未绑定车)
                "",
                "",
                false,
                true,
                true,
                false,
                false
        );

        assert snapshot.requiredCompleted == 2 : "应完成 2 项必需 (官方 App + 核心权限)";
        assert !snapshot.isFullyReady : "未绑定车辆不可全面就绪";
        assert snapshot.officialAppInstalled;
        assert snapshot.corePermissionsGranted;
        assert !snapshot.vehicleLearned;
        assert "● 待就绪 (2/3)".equals(snapshot.badgeText);
        assert snapshot.badgeColor == PaseoTheme.COLOR_IDENTITY_ORANGE;
    }

    private static void testCriticalDecisionOptionalBatteryDoesNotBlockReady() {
        ReadinessModel model = new ReadinessModel();
        // 关键决策：3 项必需全部满足，但车主【未开启】后台电池白名单
        ReadinessModel.Snapshot snapshotConnected = model.evaluate(
                true,        // officialAppInstalled
                true,        // btScanGranted
                true,        // notifAccessGranted
                true,        // notifConnected
                true,        // identityLearned
                "领克 08 EM-P",
                "C8:38:2A:11:22:33",
                false,       // batteryWhitelisted == false (未开启后台白名单)
                true,        // bluetoothOn
                true,        // serviceEnabled
                false,       // chargingProtected
                true         // geelyConnected
        );

        assert snapshotConnected.requiredCompleted == 3 : "3 项必需已全部达标";
        assert !snapshotConnected.batteryWhitelisted : "确认电池白名单为未加入";
        assert snapshotConnected.isFullyReady : "【关键决策】未加后台白名单绝不阻塞全面就绪！";
        assert "● 守护中".equals(snapshotConnected.badgeText) : "蓝牙已连通应展示正常守护中胶囊";
        assert snapshotConnected.badgeColor == PaseoTheme.COLOR_SUCCESS : "正常守护展示 Paseo 成功绿";

        // 若当前未连上车辆，应展示正常的搜索中胶囊
        ReadinessModel.Snapshot snapshotScanning = model.evaluate(
                true, true, true, true, true, "领克 08 EM-P", "C8:38:2A:11:22:33",
                false, true, true, false, false
        );
        assert snapshotScanning.isFullyReady;
        assert "● 搜索中".equals(snapshotScanning.badgeText);
        assert snapshotScanning.badgeColor == PaseoTheme.COLOR_INFO;
    }

    private static void testAllFourComplete() {
        ReadinessModel model = new ReadinessModel();
        ReadinessModel.Snapshot snapshot = model.evaluate(
                true, true, true, true, true, "领克 08 EM-P", "C8:38:2A:11:22:33",
                true, // batteryWhitelisted == true
                true, true, false, true
        );

        assert snapshot.requiredCompleted == 3;
        assert snapshot.batteryWhitelisted;
        assert snapshot.isFullyReady;
        assert "● 守护中".equals(snapshot.badgeText);
    }

    private static void testServiceDisabledOrBluetoothOffWhenReady() {
        ReadinessModel model = new ReadinessModel();
        // 当就绪后，若蓝牙关闭或服务被总开关暂停，胶囊应体现硬件或总开关状态
        ReadinessModel.Snapshot sPaused = model.evaluate(
                true, true, true, true, true, "领克 08", "AA", true,
                true,
                false, // serviceEnabled == false
                false, false
        );
        assert sPaused.isFullyReady;
        assert "● 已暂停".equals(sPaused.badgeText);
        assert sPaused.badgeColor == PaseoTheme.COLOR_TEXT_TERTIARY;

        ReadinessModel.Snapshot sBtOff = model.evaluate(
                true, true, true, true, true, "领克 08", "AA", true,
                false, // bluetoothOn == false
                true, false, false
        );
        assert sBtOff.isFullyReady;
        assert "● 蓝牙未开".equals(sBtOff.badgeText);
        assert sBtOff.badgeColor == PaseoTheme.COLOR_ERROR;

        ReadinessModel.Snapshot sCharging = model.evaluate(
                true, true, true, true, true, "领克 08", "AA", true,
                true, true,
                true, // chargingProtected == true
                true
        );
        assert sCharging.isFullyReady;
        assert "● 充电保护".equals(sCharging.badgeText);
        assert sCharging.badgeColor == PaseoTheme.COLOR_WARNING;
    }
}
