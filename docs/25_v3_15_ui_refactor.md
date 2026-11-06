# 25. v3.15：App 页面重构（驾驶舱 Dashboard）

## 一、重构原因

旧页面问题：

- 单页超长 `ScrollView`，控制、设置、行程、日志全堆在一起；
- 顶部 4 行状态 + 全宽开关 + 全宽按钮，层级弱；
- 大量 emoji 当图标，视觉噪声大；
- 关键状态（锁态/信号/主操作）淹没在设置项里。

## 二、设计方向

**Dark Cockpit / Minimal Control Panel**

- 深色底 + 单一青色强调；
- 一个 Hero 主状态区，主操作始终可见；
- 内容分 4 个页签，降低单页密度；
- 卡片 18dp 圆角、16dp 内边距、8/12/16 间距；
- 触摸目标 ≥44dp（Tab 44dp、按钮 46dp、开关 48dp）；
- 状态文字用颜色而不是堆图标。

设计参考来自 `ui-ux-pro-max` 的 automotive/dashboard 查询，但排除了它返回的 Web Hero/Motion 模板，按 Android 原生控制台场景落地。

## 三、页面结构

```text
App
├── 品牌 Header（银河车钥 / GALAXY KEY RADAR）
├── Hero（锁态 + 信号 + 距离 + 解锁/上锁）
├── Tabs（控制 / 行程 / 设置 / 日志）
└── 单页滚动内容
    ├── 控制：实时信号 KPI / 波形 / 车辆状态 / 手动 RKE
    ├── 行程：油电续航 / 行程效率 / 行程历史
    ├── 设置：自动解闭锁 / 安全 / 阈值 / 高级校准
    └── 日志：实时状态机审计
```

### Hero

- 大号锁态：`已上锁 / 已解锁 / 状态未知`
- 开锁/闭锁矢量图标；
- 右侧实时有效信号与折算距离；
- 底部两个 46dp 主按钮；
- 充电门控/行车门控时按钮置灰。

### Tabs

- 4 个等宽 Tab，44dp 高；
- 选中：`#11263A` 底 + 青色描边 + 青色文字；
- 切换 180ms 淡入；
- 点击有 `KEYBOARD_TAP` 触感反馈；
- 每个 Tab 独立 ScrollView，互不干扰。

## 四、视觉 Token

| 角色 | 颜色 |
| :--- | :--- |
| App 背景 | `#070B14` |
| Header 卡片 | `#0E1626` |
| 普通卡片 | `#111827` / `#0E1726` |
| KPI 卡 | `#0B1320` |
| 边框 | `#223047` / `#1E2A3E` |
| 主强调 | `#22D3EE` |
| 成功 | `#34D399` |
| 警告 | `#FBBF24` |
| 危险 | `#FB7185` |
| 主文字 | `#F8FAFC` |
| 次文字 | `#94A3B8` |
| 弱文字 | `#64748B` |

- 卡片圆角：18dp；Hero：22dp；按钮/输入框：12–13dp；
- 页面边距：12dp；卡片内边距：16dp；KPI：12dp；
- 字体：系统 sans-serif；标题 22–25sp，正文 12–13sp，标签 10.5–11.5sp。

## 五、Emoji 清理

新增 `cleanUiLabel()`：

- 移除 `Character.OTHER_SYMBOL`、ZWJ、VS16；
- 仅用于静态标签、按钮、开关、标题，以及动态状态行；
- 保留 `≥ ≤ · →` 等功能符号；
- 日志正文保持原样，方便排查。

## 六、实现位置

- `MainActivity.buildRootLayout()`：Shell + Header + Hero + Tabs + PageHost
- `buildHeroCard()` / `updateHeroUi()`
- `buildTabBar()` / `selectPage()` / `styleTab()`
- `createPageContent()` / `wrapPage()`
- `createCard()` / `createKpiBox()` / `createSwitch()` / `createActionButton()` 统一 token
- `cleanUiLabel()`

没有删除任何业务功能；只是重排与视觉重构。

## 七、实机验证

- 版本：3.15.1
- 前台服务正常，无 crash
- 通过 uiautomator 验证 4 个 Tab：
  - 控制：`实时有效信号`
  - 行程：`混动行程记录仪`
  - 设置：`手机侧蓝牙自动重连`
  - 日志：`实时状态机`

实机截图：

- [控制页](../mockups/app_ui_v3151_control.png)
- [行程页](../mockups/app_ui_v3151_trip.png)
- [设置页](../mockups/app_ui_v3151_settings.png)
- [日志页](../mockups/app_ui_v3151_logs.png)

> 截图时手机插着 USB，充电门控生效，所以 Hero 按钮是置灰状态；拔线后恢复。

## 八、后续可继续优化

- Header 的 4 行诊断状态仍偏多，可进一步收进「控制」页或折叠面板；
- 设置页开关较多，可按「自动控制 / 安全 / 高级」分组折叠；
- 行程页可加总里程趋势图；
- 深色是主场景，暂不做浅色主题。
