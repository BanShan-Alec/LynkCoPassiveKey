# Paseo Dark 设计规范 (Design System Specification)

> 本规范遵循 **《Refactoring UI》** 的核心工程原则与约束层体系（Constraint Layer），将项目接入的 **Paseo Dark** 设计 Token 体系转化为可落地、可检查、可演进的工业级设计规范。
>
> 适用于 Android 原生应用（`GeelyKeyRadarApp`）、Web 管理后台及未来全平台客户端。

---

## 目录

- [一、产品个性定位与核心设计哲学](#一产品个性定位与核心设计哲学)
- [二、Refactoring UI 十二大系统阶梯 (The 12 Scales)](#二refactoring-ui-十二大系统阶梯-the-12-scales)
  - [1. 字阶系统 (Typography Scale)](#1-字阶系统-typography-scale)
  - [2. 字重系统 (Font Weight)](#2-字重系统-font-weight)
  - [3. 行高系统 (Line Height)](#3-行高系统-line-height)
  - [4. 色彩与表面高度阶梯 (Color & Dark Surfaces)](#4-色彩与表面高度阶梯-color--dark-surfaces)
  - [5 & 6. 间距系统与内外边距 (Spacing Scale)](#5--6-间距系统与内外边距-spacing-scale)
  - [7 & 8. 尺寸系统与触摸目标 (Width, Height & Target Sizes)](#7--8-尺寸系统与触摸目标-width-height--target-sizes)
  - [9. 深度与暗色模式阶梯 (Depth & Elevation)](#9-深度与暗色模式阶梯-depth--elevation)
  - [10. 圆角系统 (Border Radius)](#10-圆角系统-border-radius)
  - [11. 描边与边框系统 (Border Width)](#11-描边与边框系统-border-width)
  - [12. 不透明度系统 (Opacity)](#12-不透明度系统-opacity)
- [三、视觉层级与操作金字塔 (Visual Hierarchy & Action Pyramid)](#三视觉层级与操作金字塔-visual-hierarchy--action-pyramid)
- [四、工程映射对照表 (Android / CSS / Web)](#四工程映射对照表-android--css--web)
- [五、设计防腐与反模式自查清单 (Governance & Anti-patterns)](#五设计防腐与反模式自查清单-governance--anti-patterns)

---

## 一、产品个性定位与核心设计哲学

根据《Refactoring UI》§1.4“从个性出发”，界面设计绝非凭空装饰，而是先明确三条轴线定位：

| 轴线维度 | 本项目定位 | 映射的设计决策 |
| :--- | :--- | :--- |
| **严肃 ↔ 活泼** (§1.4.3) | **沉稳科技 · 现代克制 (Serious-Modern)** | 圆角收敛在 `12px` 与 `8px`，杜绝浮夸的全胶囊造型；强调精密仪器与车载 HUD 的秩序感。 |
| **经典 ↔ 现代** (§1.4.1) | **现代无衬线 (Clean Sans-Serif)** | 采用系统原生现代无衬线栈，中性冷色调暗灰底色（带有微弱的天然墨玉/暗板岩底蕴）。 |
| **紧凑 ↔ 宽松** (§3.1.2) | **半紧凑仪表式 (Semi-Dense HUD)** | 高信息密度卡片分组，在保障 46dp 手指安全触控靶区的前提下，压缩无意义留白，一眼尽览蓝牙状态。 |

### 核心准则：暗色模式的高度表达 (§6.4.1, 15-beyond-the-book)
在传统浅色模式中，高度通过向下投影表达；而在暗黑模式（Dark Mode）中，黑色阴影在深色底色上对比度极其微弱。
**暗色模式的核心高度法则：更浅代表更近，更深代表更远（Lighter reads as closer, darker reads as further away）。** 容器层级完全由表面亮度梯度驱动，阴影仅作边缘强化辅助。

---

## 二、Refactoring UI 十二大系统阶梯 (The 12 Scales)

《Refactoring UI》§1.5.3 明确指出：任何专业 UI 必须由 12 个系统完全覆盖，禁止手写随意、孤立的硬编码值（No arbitrary one-offs）。

### 1. 字阶系统 (Typography Scale)
严禁自由手写任意字号。遵循 Paseo 阶梯与规范应用场景：

| 阶梯 Token | 像素值 (CSS/sp) | 行高 | 适用场景与语义规则 (§2.6) |
| :--- | :--- | :--- | :--- |
| `--paseo-text-caption` | **12px** | 20px | 辅助元数据、输入框 Tip、底部版权、微弱提示文案 |
| `--paseo-text-body` | **14px** | 22px | **正文基准 (Body Text)**、开关说明、状态详情、按钮文本 |
| `--paseo-text-subtitle` | **16px** | 24px | **卡片小标题 (Card Title)**、弹窗确认项、分段操作标题 |
| `--paseo-text-heading-sm` | **20px** | 28px | 模态弹窗主标题、重点区域导航 |
| `--paseo-text-heading` | **24px** | 32px | 核心统计大字、重要状态概览 |
| `--paseo-text-heading-lg` | **30px** | 38px | 页面一级主标题（如“领克无感钥匙”顶栏） |
| `--paseo-text-display` | **38px** | 46px | 巨型数据仪表（如信号 dBm 巨额数值监控） |

### 2. 字重系统 (Font Weight)
遵循《Refactoring UI》§2.2.b **严格两级字重原则**：
- **常规体 (Regular / 400)**：用于所有正文、辅助说明、状态值、副标题。
- **加粗体 (Bold / 600~700)**：用于卡片小标题、主标题、按钮动作。
- ❌ **绝对红线**：UI 界面中文本严禁使用低于 400 的纤细体（Thin/Light），避免在车载与移动暗色强光下笔画消失。

### 3. 行高系统 (Line Height)
遵循《Refactoring UI》§4.5 相对收紧原则：
- 正文与说明（12~14px）：行高设置在 `1.4 ~ 1.6`（20~22px），确保阅读连续性；
- 标题大字（24px+）：行高收紧至 `1.2 ~ 1.25`，防止大标题由于过大行距产生视觉割裂；
- 等宽日志流（Monospace Log）：行高固定为 `1.15`，以最高信息密度呈现近场通信日志。

### 4. 色彩与表面高度阶梯 (Color & Dark Surfaces)

#### 4.1 深色表面阶梯 (Dark Surfaces & Elevation)
遵循暗色表面递进阶梯，严禁使用 `#000000` 纯黑作为页面底色（纯黑会导致发光感过强并加剧视觉疲劳）：

```
[surface-0: #181B1A] ── 全局背景底色 (Page Layout)
       │
[surface-1: #1E2120] ── 业务卡片容器 (Card Container)
       │
[surface-2: #272A29] ── 浮动控制底色 (Elevated / Popovers / Switch Track Inactive)
       │
[surface-3: #434645] ── 弹窗模态与填充 (Modal Surface / Secondary Fill)
       │
[surface-4: #595B5B] ── 控件未激活滑块 (Switch Thumb Inactive / Muted Controls)
```

#### 4.2 品牌主色阶 (Paseo Primary Ramp)
基于高品质沉稳森林绿，而非廉价的高亮荧光色：

| Token | 色值 (HEX) | 作用场景与 WCAG 对比度 |
| :--- | :--- | :--- |
| `--paseo-primary` | `#20744A` | 主交互按钮底色、Switch 激活滑轨 |
| `--paseo-primary-hover` | `#347552` | 触控悬停态反馈 |
| `--paseo-primary-active` | `#1C5237` | 触控按下/按压深度态 |
| `--paseo-primary-bg` | `#183124` | 浅绿色强调容器衬底 |
| `--paseo-primary-text` | `#7CCBA0` | **核心文本高亮**、卡片小标题、Switch 激活滑块（在 `#1E2120` 对比度达 **7.3:1**，远超 WCAG AA） |
| `--paseo-primary-border` | `#183124` | 绿色容器边界描边 |

#### 4.3 文本三层级系统 (The Three Text Tiers §2.2.a)
遵循《Refactoring UI》§2.2.a 规定，界面中最多允许 3 个文本灰度层级，严禁每个组件随意取色：

| 文本层级 | Token | 色值 | 适用内容 | 在 #1E2120 下对比度 |
| :--- | :--- | :--- | :--- | :---: |
| **Tier 1 (主文本)** | `--paseo-text` | `#FAFAFA` | 页面标题、卡片正文、数据实际数值、开关主标题 | **12.8:1** (AAA) |
| **Tier 2 (次级文本)** | `--paseo-text-secondary` | `#A1A5A4` | 开关说明、卡片副标题、运行日志、说明指南 | **5.4:1** (AA) |
| **Tier 3 (弱化文本)** | `--paseo-text-tertiary` | `#8A8E8D` | 底部微注、暗淡辅助说明、输入框 Placeholder | **4.2:1** (大字合规) |

> ⚠️ **反模式警示 (§2.3)**：绝对禁止通过降低白色文字不透明度（如 `rgba(255,255,255,0.6)`）来实现次级文本。半透明文本会导致底色透出且产生灰蒙蒙的脏色，必须使用手选的不透明实体色（`#A1A5A4`）。

#### 4.4 状态语义色阶 (Status Semantics)

| 语义类型 | 强调色 / 文本 | 容器衬底 | 状态点 (Dot) | 应用场景 |
| :--- | :--- | :--- | :--- | :--- |
| **成功 (Success)** | `#6CB17B` / `#C4D0C6` | `#19201B` | `#35C264` | 蓝牙已连接、鉴权成功、进入解锁成功 |
| **警告 (Warning)** | `#C09664` / `#E0DBD2` | `#221D18` | `#DB932E` | 假死状态未绑定、充电防锁保护中、超时重试中 |
| **危险 (Error)** | `#D8847B` / `#F8ECE9` | `#261B1B` | `#F7796D` | 权限缺失、蓝牙未开启、服务崩溃 |
| **信息 (Info)** | `#5CAAF6` / `#D3E9F8` | `#18202A` | `#5CAAF6` | 正在扫描中、后台同步中 |
| **身份 (Identity)** | `#CC8F64` (Orange) | `#272A29` | — | vivo / OriginOS 专属保活指引等厂商专有模块 |

### 5 & 6. 间距系统与内外边距 (Spacing Scale)
遵循系统化步进阶梯，禁止出现 `7px`、`13px`、`23px` 等随意偶数值：

```
[ 4px ]  ── 紧凑文本微间距（如标题与副标题之间）
[ 8px ]  ── 紧凑组件间距（如输入框与 Tip 之间）
[ 10px ] ── 卡片内垂直行间距（如卡片标题与内容正文之间、按钮与正文之间）
[ 12px ] ── 弹窗内部边距
[ 14px ] ── 卡片与卡片之间的外间距 (Card Margin Bottom)
[ 16px ] ── 卡片内部全方向内边距 (Card Padding)
[ 20px ] ── 页面水平侧边距 (Page Horizontal Padding)
[ 32px ] ── 页面底部留白 (Page Bottom Padding)
[ 48px ] ── 页面顶部安全距离 (Page Top Padding)
```

> 🎯 **空间铁律：外层间距必须严格大于内层间距 (§3.6 Outer > Inner)**
> - `卡片外间距 (14dp)` > `卡片内组件间距 (10dp)`；
> - `卡片内边距 (16dp)` > `组内元素间距 (10dp)` > `紧凑文本间距 (4dp)`；
> - 绝对禁止出现“两张卡片贴得极近，但卡片内部元素却散落极开”的视觉坍塌。

### 7 & 8. 尺寸系统与触摸目标 (Width, Height & Target Sizes)
- **手指标准触控靶区**：所有独立按钮高度统一设为 **`46dp`**（超越 WCAG 2.2 AA 规定的 `24×24px` 与 iOS/Android 推荐的 `44~48dp` 安全区间）；
- **全宽卡片**：横向铺满并遵循 `20dp` 边衬，避免窄屏水平拥挤与溢出。

### 9. 深度与暗色模式阶梯 (Depth & Elevation)
- **表面轻量微投影**（仅用于边缘清晰化）：
  - `--paseo-shadow-raised`: `0 1px 2px rgba(0, 0, 0, 0.08), 0 2px 6px rgba(0, 0, 0, 0.10)`
  - `--paseo-shadow-overlay`: `0 4px 10px rgba(0, 0, 0, 0.18), 0 1px 3px rgba(0, 0, 0, 0.10)`
- 在深色 UI 中，高度层次感通过 **容器底色提升（#181B1A → #1E2120 → #272A29）+ 1px 细微边界描边（#252B2A）** 共同确立。

### 10. 圆角系统 (Border Radius §1.4.3)
项目圆角风格遵循克制、沉稳的科技感，禁止混用随意数值：
- `xs` (`4px`)：小型标签、状态微徽章；
- `sm` (`8px`)：弹窗输入框、二级芯片按钮；
- `md / lg` (`12px`)：**所有卡片容器标准圆角**、主交互按钮标准圆角。

### 11. 描边与边框系统 (Border Width §1.5.3, §2.7.2)
- **容器细边框**：全量卡片均统一采用 `1px` 细描边（颜色为 `--paseo-border: #252B2A`），防止深色卡片边缘在不同显示屏上泛白或与底色融为一体；
- **微分隔线**：采用轻度半透明分割 `--paseo-split: rgba(219, 255, 254, 0.09)`；
- **反模式自查 (§2.7.2)**：如果发现边框过暗不可见，**优先增加描边线宽或使用有色彩倾向的微边框，切忌一味将其加粗为亮白色**。

### 12. 不透明度系统 (Opacity)
- 背景遮罩层：`--paseo-bg-mask: rgba(0, 0, 0, 0.65)`；
- 选区高亮：`--paseo-selection: rgba(255, 255, 255, 0.2)`；
- 严禁对正文/标题文字直接应用 `opacity`。

---

## 三、视觉层级与操作金字塔 (Visual Hierarchy & Action Pyramid)

遵循《Refactoring UI》§2.8“语义服从层级”，页面操作必须严格划分等级，杜绝全屏平铺高饱和度实心按钮：

```
       ▲
      / \        Primary Action (主操作：1 个/屏)
     /   \       ── 实心高对比度背景 (#20744A + #FAFAFA 字)
    /─────\
   /       \     Secondary Action (次级操作：少数)
  /         \    ── 描边或中性暗色底 (#1E2120/#272A29)
 /───────────\
/             \  Tertiary Action (三级操作：可选)
                 ── 文字链接式或低调文本按钮
```

### 1. 操作分级实现模式

#### 1.1 主操作 (Primary Action)
- **样式**：背景采用 `--paseo-primary` (`#20744A`)，文字采用 `--paseo-text` (`#FAFAFA`)；
- **原则**：一个视窗/卡片区域原则上仅允许 1 个最具生产力的主操作；
- **示例**：【填入车架号后3位锁定本车】、【自定义靠近/离开阈值】。

#### 1.2 告警与自愈操作 (Warning & Recovery Action)
- **样式**：背景采用高对比度暖金 `--paseo-warning` (`#C09664`)，字色采用专属深墨色 `--paseo-identity-ink` (`#1F1E1D`)，对比度高达 **7.1:1**；
- **规则**：仅在系统出现假死、异常或降级时**条件性动态浮现**，平时处于隐藏状态，避免视觉持续噪音；
- **示例**：【⚡ 一键修复通知监听 (服务自愈)】。

#### 1.3 次级操作与危险操作 (Secondary & Destructive Action §2.8)
- **样式**：采用次级填充色或低饱和度中性表面；
- **破坏性操作处理**：如【清空日志】、【重新学习本车】，**不应默认做成全红大按钮**，应采用常规中性底色，通过弹窗二次确认承载破坏性防护，防止喧宾夺主。

---

## 四、工程映射对照表 (Android / CSS / Web)

| 设计规范概念 | Paseo CSS Token | Android 原生实现映射 | 色值 / 尺寸 |
| :--- | :--- | :--- | :--- |
| **页面布局底色** | `--paseo-bg-layout` | `scrollView.setBackgroundColor(COLOR_BG)` | `#181B1A` |
| **卡片容器底色** | `--paseo-bg-container` | `cardBg.setColor(COLOR_CARD)` | `#1E2120` |
| **卡片边界描边** | `--paseo-border` | `cardBg.setStroke(dp(1), COLOR_BORDER)` | `#252B2A` |
| **卡片圆角** | `--paseo-radius-lg` | `cardBg.setCornerRadius(dp(12))` | `12dp` |
| **卡片小标题** | `--paseo-primary-text` | `text(..., 16, COLOR_ACCENT, Typeface.BOLD)` | `#7CCBA0` |
| **正文主字色** | `--paseo-text` | `text(..., 14, COLOR_TEXT, Typeface.NORMAL)` | `#FAFAFA` |
| **次级辅助文案** | `--paseo-text-secondary` | `text(..., 12, COLOR_MUTED, Typeface.NORMAL)` | `#A1A5A4` |
| **主按钮底色** | `--paseo-primary` | `button.setBackgroundTintList(COLOR_PRIMARY)` | `#20744A` |
| **主按钮字色** | `--paseo-text` | `button.setTextColor(COLOR_TEXT)` | `#FAFAFA` |
| **自愈警告按钮** | `--paseo-warning` | `button.setBackgroundTintList(COLOR_WARNING)` | `#C09664` |
| **自愈按钮文字** | `--paseo-identity-ink` | `button.setTextColor(COLOR_IDENTITY_INK)` | `#1F1E1D` |
| **开关激活滑块** | `--paseo-primary-text` | `toggle.setThumbTintList(..., COLOR_PRIMARY_TEXT)` | `#7CCBA0` |
| **开关激活滑轨** | `--paseo-primary` | `toggle.setTrackTintList(..., COLOR_PRIMARY)` | `#20744A` |
| **开关未激活滑块**| `--paseo-surface-4` | `toggle.setThumbTintList(..., COLOR_SURFACE_4)` | `#595B5B` |
| **开关未激活滑轨**| `--paseo-surface-2` | `toggle.setTrackTintList(..., COLOR_SURFACE_2)` | `#272A29` |
| **输入框文本与Hint**| `--paseo-text` / `secondary` | `input.setTextColor` / `setHintTextColor` | `#FAFAFA` / `#A1A5A4` |

---

## 五、设计防腐与反模式自查清单 (Governance & Anti-patterns)

在后续新增 UI 模块或维护代码时，必须对照以下《Refactoring UI》反模式清单进行自查：

- [ ] **反模式 1：硬编码孤立颜色**
  - ❌ 禁止在 Java/XML/CSS 中手写未在 Token 列表中声明的 Hex 颜色；
  - ✅ 新增颜色必须先补充进语义 Token 层，并经过 WCAG 对比度验证。
- [ ] **反模式 2：彩色背景上使用灰色文字 (§2.3)**
  - ❌ 禁止在绿色容器或暗绿背景上放置中性冷灰文字；
  - ✅ 必须选用带同色相倾向的浅色系（如 `--paseo-primary-text: #7CCBA0`）。
- [ ] **反模式 3：外间距小于内间距 (§3.6)**
  - ❌ 禁止外层卡片 Margin 小于内部 Item Margin；
  - ✅ 始终保持 `Outer Spacing (14dp+)` > `Inner Spacing (10dp)`.
- [ ] **反模式 4：单维度依赖字号表达层级 (§2.2)**
  - ❌ 禁止为了突出信息一味把字号调到极大，导致界面排版失衡；
  - ✅ 优先使用字重加粗（400 → Bold）与色彩提亮（#A1A5A4 → #FAFAFA）来建立层级。
- [ ] **反模式 5：全屏平铺主操作 (§2.8)**
  - ❌ 禁止在一个页面内放置 4 个以上同等权重的实心彩色按钮；
  - ✅ 严格实施金字塔收敛：1 个主操作，其余收敛为次级或链接按钮。
- [ ] **反模式 6：微调色阶泛滥 (§5.3.5)**
  - ❌ 禁止因为某个局部感觉“略暗一点点”就随意新增一个 515 或 520 色阶；
  - ✅ 微调应优先复用现有 12 系统阶梯，保持全系统一致性。
