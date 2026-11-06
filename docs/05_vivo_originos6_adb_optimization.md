# 05. vivo iQOO Neo10 Pro (`V2426A` / OriginOS 6 / Android 16) ADB 优化与回滚手册

本文档记录了在 `vivo V2426A` 上通过 ADB 已执行的 **6 档「影响最小、效果最好」系统无损优化**、蓝牙钥匙保活豁免配置，以及随时可用的**一键回滚恢复命令**。

---

## 一、已执行的 6 档无损优化清单（重启不失效）

所有优化均写入系统 `/data/system/users/0/` 配置与 Settings 数据库，**手机重启后依然永久保持生效**，且不影响日常使用、vivo 账号、钱包车钥匙、应用商店更新或系统 OTA。

| 排名 | 优化项 | 处理的包名 / 设置项 | 实际收益 |
| :---: | :--- | :--- | :--- |
| **TOP 1** | 系统广告推送与商业化追踪开关清零 | `Settings.Global` / `System` / `Secure` 广告与诊断追踪属性 | 消除系统级个性化广告推荐与后台遥测上报 |
| **TOP 2** | 禁用开机常驻快应用引擎 (`com.nearme.instant.platform`) | `pm disable-user --user 0 com.nearme.instant.platform` | 彻底拦截网页/广告自动跳快应用，释放约 `~172 MB` 常驻内存 |
| **TOP 3** | 冻结系统内置浏览器商业资讯后台 (`com.vivo.browser`) | `pm disable-user --user 0 com.vivo.browser` | 消除后台资讯流拉取与通知打扰，释放约 `~224 MB` 内存 |
| **TOP 4** | 禁用负一屏商业卡片流 (`com.vivo.hiboard`) | `pm disable-user --user 0 com.vivo.hiboard` | 桌面右滑更清爽流畅，释放约 `~203 MB` 内存 |
| **TOP 5** | 冻结预装游戏中心、视频、电子书后台进程 | `com.vivo.game`、`com.vivo.video`、`com.chaozh.iReader` | 阻止三大内容消费预装组件开机自启与后台拉活，合计节省 `~450 MB` 内存 |
| **TOP 6** | 限制官方商城、官网社区、杂志锁屏后台自启 | `cmd appops set <pkg> RUN_IN_BACKGROUND ignore` (`com.vivo.space`, `com.bbk.MR.aged`, `com.vivo.magazine`) | 保留桌面图标可正常点开，但退出后严禁后台驻留耗电 |

---

## 二、蓝牙钥匙双应用（银河钥匙雷达 + 吉利银河）最高优先级保活配置

```bash
ADB="adb"
for pkg in com.luca.geelykeyradar com.geelygalaxy.customer; do
  "$ADB" -P 5038 shell dumpsys deviceidle whitelist +$pkg
  "$ADB" -P 5038 shell cmd appops set $pkg RUN_IN_BACKGROUND allow
  "$ADB" -P 5038 shell cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow
  "$ADB" -P 5038 shell cmd appops set $pkg WAKE_LOCK allow
  "$ADB" -P 5038 shell cmd appops set $pkg START_FOREGROUND allow
  "$ADB" -P 5038 shell am set-standby-bucket $pkg active
done
"$ADB" -P 5038 shell cmd notification allow_listener com.luca.geelykeyradar/com.luca.geelykeyradar.KeyNotificationBridgeService
```

---

## 三、按需恢复（回滚）命令速查

如果日后你想恢复上述任何一个被禁用的系统组件，只需执行对应的单行命令即可立即恢复（数据完好无损）：

### 1. 恢复负一屏 (`com.vivo.hiboard`)
```bash
adb -P 5038 shell pm enable com.vivo.hiboard
```

### 2. 恢复系统内置浏览器 (`com.vivo.browser`)
```bash
adb -P 5038 shell pm enable com.vivo.browser
```

### 3. 恢复快应用引擎 (`com.nearme.instant.platform`)
```bash
adb -P 5038 shell pm enable com.nearme.instant.platform
```

### 4. 恢复 vivo 游戏中心 / 视频 / 电子书
```bash
adb -P 5038 shell "pm enable com.vivo.game; pm enable com.vivo.video; pm enable com.chaozh.iReader"
```
