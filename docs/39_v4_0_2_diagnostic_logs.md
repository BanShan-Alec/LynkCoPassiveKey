# 39. v4.0.2：日志增强与诊断包使用

版本：`4.0.2-min / versionCode 102`。本次补齐观测与手动导出，默认参数继续使用 `-75 / -82 dBm / 4.5s`，解闭锁判定、失败预算和保护策略沿用 v4.0.1。

## 1. 出现问题后怎么操作

1. 记下大概时间、解锁还是上锁、是否息屏/放口袋/插线，以及有没有听到车辆钥匙连接声。
2. 打开本应用，滑到最底部，点 **“导出排查日志（ZIP）”**。
3. 在系统文件选择器中保存。把保存的ZIP和发生时间提供给排查人员。

导出无需电脑、adb、root或存储权限，不会自动上传文件。系统文件选择器打开前会采集状态；返回时如果服务已重新绑定，会更新快照。`snapshot_captured_mono_ms`标明采集时间，和ZIP实际生成时间可能不同。

排查前不要清空日志或卸载应用。日志会轮转，建议问题发生后尽快导出；不能保证保存所有历史事件。界面只显示最近十条，并不代表后台只有十条。

## 2. 固定容量与写入策略

| 类型 | 文件 | 容量 |
| --- | --- | --- |
| 关键事件 | `passive_key.log`及`.1`—`.3` | 4份，每份256KiB，共1MiB |
| 诊断细节 | `passive_key_diagnostic.log`及`.1`—`.5` | 6份，每份512KiB，共3MiB |
| 界面 | 最近关键事件，最新在前 | 最多10条 |
| 内存写入队列 | 所有模块共用单一写入线程 | 最多256个任务，满时丢弃并记录计数 |

总日志上限 **4MiB**。文件达到上限时只淘汰最老分段，不整批删除历史。单行正文限制8192字符；过长信息截断并标记。日志与导出操作在后台执行，车控线程不等待磁盘。

- 常态RSSI约每5秒保存一次；上锁驻留或请求等待期间约每500ms记录判定现场。
- 判定原因变化及区间变化有节流；长期相同原因仍周期保留。
- 每30秒保存心跳和窗口计数，没有信号时也保存，便于识别无回调。
- `dropped`和`io_errors`记录未接纳或写入失败数量，非零表示现场可能不完整。
- 一键清空通过同一队列删除两组分段，并保留清空事件。身份脱敏盐和服务运行标记不随日志清空。
- 导出期间生成一个缓存ZIP，写到用户选择的文件后清理暂存。用户保存的文件由用户管理，不计入后台4MiB日志容量。

## 3. ZIP内容及脱敏

| 文件 | 用途 |
| --- | --- |
| `summary.txt` | App/银河版本、手机品牌型号和Android版本、当前配置、运行状态、日志健康计数 |
| `events.log` | 服务启动/结束、连接边沿、动作请求、发送尝试/结果、取消/超时、信号过期、异常 |
| `diagnostic.log` | 收包与过滤计数、RSSI、判定原因、等待阶段、GATT读取状态、通知解析和省电心跳 |
| `README.txt` | 包内字段释义及证据边界 |

分段按旧到新合并。ZIP在统一写入队列中生成，只包含已接纳并在导出任务之前完成的日志前缀。不会读取或打包SharedPreferences、身份盐、扫描原始字节、官方通知正文、银河账号或钥匙凭证。

车辆地址和名称从日志源头写为本机加盐指纹`addr_id/name_id/identity_id`。导出时再次处理旧版本中的MAC、VIN特征和身份事件详情，包括自定义车名。相同地址在本机可关联；指纹只用于诊断，不能作为钥匙或车辆授权依据。设备品牌型号、版本、时间和数值状态会保留。

## 4. 关联时间与请求

每条新日志有完整日期、时区及以下前缀：

```text
2026-11-07T12:00:00.000+0800 sid=12ab34cd pid=12345 seq=80 mono_ms=100000 src=Control event=REQUEST_CREATED trace_run=ab12cd34 req_id=2 action=LOCK ...
```

- `sid/pid`区分进程；同一进程内前台服务重建会生成新的`trace_run`。
- **用`sid + trace_run + req_id`关联一次请求**，不能只看`req_id`，因为服务重建后编号可能重用。
- `seq`是日志编号，不是车控次数。`wake_id`关联一次向官方组件注入广播的多个入口。
- `mono_ms`是本次开机单调时间，适合比较耗时。设备重启后重新计时；墙钟可受校时影响。
- 时间/年龄`-1`表示未观察到，不代表刚发生。历史段中没有这些字段的行来自旧版。

## 5. 关键事件和判定字段

| 范围 | 事件/字段 | 可以回答的问题 |
| --- | --- | --- |
| 扫描 | `SCAN_START/SCAN_CALLBACK_ERROR/TARGET_FIRST_SEEN`，`scan_callbacks/scan_not_geely/scan_stale/scan_duplicate/scan_other_vehicle/scan_non_primary/scan_accepted` | 没收到回调，还是回调被过滤？ |
| 原始信号 | `RSSI_SAMPLE`，`raw/smooth/approach/pocket/age/source` | 是原始弱信号、滤波、口袋补偿还是旧样本？ |
| GATT | `CONNECT_START/STATE_CALLBACK/CONNECT_TIMEOUT/CLOSE`，`gatt_attempt/gatt_addr_id/read_pending/read_request_age_ms/read_success_age_ms/read_status` | 通道没有连上，还是读取请求被拒绝/回调停更？ |
| 官方通知 | `LISTENER_CONNECTED/LISTENER_DISCONNECTED/REMOVED/PARSED/STATE_CLEARED` | 监听掉线、通知移除、解析失败或按钮不可用？ |
| 唤醒官方 | `WAKE_REQUEST/WAKE_BEGIN/WAKE_SERVICE_RETURN/WAKE_SERVICE_ERROR/WAKE_BROADCAST_SENT` | 哪个入口被系统接纳或拒绝？ |
| 车控 | `REQUEST_CREATED/REQUEST_WAIT/SEND_ATTEMPT/SEND_RESULT/REQUEST_CANCELLED` | 是否生成请求、等待什么、何时取消、通过哪条路径发送？ |
| 运行 | `PROCESS_START/SERVICE_BEGIN/SERVICE_START_COMMAND/SERVICE_END/PREVIOUS_PROCESS_EXIT/TICK_GAP/HEARTBEAT` | 应用重启、内存压力、调度长间隔或权限/省电变化？ |

扫描/GATT/通知计数形如`scan_callbacks=20(+3)`，分别表示进程累计20次、本窗口新增3次。导出快照不会推进计数窗口。

官方通知时间分开记录：

- `posted_callback_age_ms`：最近一次真实`onNotificationPosted`回调的年龄；启动时仅扫描已有通知则可以是`-1`。
- `notification_seen_age_ms`：最近通过回调或活动通知扫描看见它的年龄。
- `notification_parse_age_ms`：最近完成解析的年龄。
- `notification_post_ms`：系统通知自身的墙钟时间，不能直接当作蓝牙连接/断开时刻。

状态机原因来自实际执行的返回分支，诊断快照不会再次运行状态机：

| `sample_reason/official_reason` | 含义 |
| --- | --- |
| `UNLOCK_THRESHOLD_REACHED/OFFICIAL_READY_UNLOCK/LOCK_DWELL_READY` | 本次已产生对应动作请求 |
| `APPROACH_LATCHED` | 本次接近已占用解锁机会，防止重复 |
| `UNLOCK_DISABLED/LOCK_DISABLED` | 对应自动开关关闭 |
| `DRIVING_OR_WIRED_BLOCK` | 行驶或插线禁控，结合`driving/wiredBlock`区分 |
| `NO_NEAR_EVIDENCE` | 从未接近的远处会话不能补锁 |
| `SIGNAL_LOSS_INHIBIT/NEAR_GUARD` | 断连保护或近期近场保护仍有效 |
| `LOCK_NEEDS_SAMPLES/LOCK_DWELL_IN_PROGRESS` | 新样本数或连续确认时间尚不足 |
| `CONTROL_PENDING/CONTROL_RETRY_COOLDOWN/UNLOCK_COOLDOWN` | 已有请求或冷却中 |
| `UNLOCK_REQUEST_LIMIT/LOCK_REQUEST_LIMIT/RETURN_REQUIRED_AFTER_FAILURE` | 本会话失败预算耗尽，等待真实离开再返回 |
| `RETURN_CONFIRMATION/FRESH_FAR_SIGNAL/BUFFER_ZONE` | 回程未确认、新鲜远场或处于安全缓冲带 |
| `APPROACH_NOT_FINISHED`（v4.0.3起） | 虽然已提前解锁，但还没有真正进入车旁，也没有同源持续变弱的离开证据，上锁被拦截 |
| `INVALID_SAMPLE_OR_ORDER` | 无效或乱序样本 |

同时保留`dwell_ms/dwell_samples/near_guard_left_ms/inhibit_left_ms/retry_left_ms/unlock_requests/lock_requests/return_required/return_ms/return_samples`及原因年龄，避免把旧原因误认作当前判断。

v4.0.3新增`departure_armed/early_unlock_age_ms/early_peak_dbm/departure_drop_dbm/rssi_source`，区分真正到过车旁、尚在提前接近，以及广播/GATT采样来源切换。相关故障与修复见文档40。

## 6. 证据边界与复盘顺序

先按大概时间找到`TARGET_FIRST_SEEN`，再看`WAKE_* → Official CONNECTED → REQUEST_* → SEND_*`；离开问题结合`RSSI_SAMPLE`和`sample_reason`查看驻留是否成立。

- 官方服务调用返回`accepted=true`只表示系统接纳启动请求，广播也没有鉴权回执。
- `SENT`表示本应用已通过通知按钮交给银河；本版本没有车锁执行回执。`UNKNOWN`不能当作发送成功或确定失败。
- `TICK_GAP`可能来自休眠或调度延迟，不能单独认定被系统冻结。
- `previous_clean_stop=false`只表示没有记录到正常停止；可结合Android11以上的最近三次本应用退出原因分析。退出历史未返回不证明从未退出。
- 日志轮转、队列拒绝、进程突停都可能丢失记录，没有日志不等于没有事件。

## 7. 验证

`SKIP_INSTALL=1 bash GeelyKeyRadarApp/build.sh`运行六组测试并生成签名APK。`DiagnosticsTest`覆盖：只读快照不改变动作、原因与分支一致、计数空间有界、ZIP白名单、旧日志和自定义车名脱敏、4MiB容量、清空顺序、服务未正常结束标记。车控原有五组测试继续运行。

Android系统文件选择器与厂商后台行为需要真机验证；自动测试不模拟真实车辆鉴权或锁车回执。

### 本次验证结果（2026-11-07）

- 六组测试全部通过，APK完成编译、对齐和签名验证。
- 已通过ADB覆盖安装到连接的vivo手机，系统包信息确认`versionCode=102 / 4.0.2-min`。
- 安装前后对照偏好，车辆身份、两个自动开关和`-75/-82/4500ms`参数保持一致。
- 手机处于USB供电且插线禁控开启；启动后确认两组日志文件及服务运行标记已生成。
- ZIP一致性、白名单、容量和脱敏经离线测试验证。随后ADB连接中断，尚未完成系统文件选择器保存ZIP的真机操作验证。
