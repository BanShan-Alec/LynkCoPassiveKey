# 领克/吉利无感钥匙 (LynkCo Passive Key)

基于手机端高精度 BLE 信号滤波与官方应用通知栏桥接，替代车机原厂单点判定以解决车旁误锁与罚站的数字钥匙辅助系统。

## Language

**被动无感进入 (Passive Entry / PE)**:
车身控制器（BCM）依靠车载蓝牙单点信号自主判定距离并执行解闭锁的原厂内置功能。
_Avoid_: 手机无感钥匙, 自动钥匙

**主动远程遥控 (Remote Keyless Entry / RKE)**:
手机通过领克官方 App 向车辆下发明确加密鉴权协议的解闭锁指令（如通知栏按钮动作）。
_Avoid_: 遥控钥匙, 物理按键

**蓝牙单连接槽位 (Single BLE Connection Slot)**:
车机车载蓝牙外设仅允许单一活跃 GATT 连接的硬件限制，被官方 App 占满后车机会立即停发广播。
_Avoid_: 蓝牙占用, 蓝牙互斥

**近场驻留保护 (Dwell & Proximity Guard)**:
手机端防止人体吸收与多径效应引起瞬间信号骤降导致误锁的连续时间窗与滤波防抖机制。
_Avoid_: 防抖延时, 延迟锁车

**通知栏桥接 (Notification Bridge)**:
从领克官方 App 常驻前台通知中提取并触发解闭锁 PendingIntent 的无侵入控制通道。
_Avoid_: 辅助功能点击, 屏幕模拟点击

**充电抑制保护 (Charging Inhibit Guard)**:
检测到手机处于车载有线或无线充电状态时强制禁止自动离车上锁的行车安全策略。
_Avoid_: 充电禁控, 行车检测

**非对称禁控 (Asymmetric Inhibit)**:
充电状态下仅抑制自动上锁动作，而在近场识别有效时放行靠近解锁的防罚站设计。
_Avoid_: 单向锁车, 解锁放行
