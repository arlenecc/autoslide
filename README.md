# AutoSlide（自动滑刷）

一个 Android 无障碍服务应用，在抖音 / 快手等短视频 App 中按**随机间隔**执行**拟人化上划手势**，自动切换下一个视频。

核心设计目标：**节奏随机、轨迹拟人**，避免"机械等距直线滑动"被识别；同时保持代码分层清晰，业务逻辑可在 JVM 上单测。

---

## 功能特性

### 1. 随机切换间隔
每次滑动之间的等待时长在 **5 ~ 30 秒（闭区间）** 内随机取值，不存在固定节拍。

### 2. 拟人化手势轨迹
一次上划不是直线匀速拖动，而是带有人手特征的一整套参数：

| 拟人要素 | 实现 |
| --- | --- |
| 起点位置 | 落在拇指自然活动区：横向 30%~70%、纵向 65%~85% 屏宽/屏高 |
| 终点位置 | 纵向 20%~40%，横向在起点基础上随机漂移 ±12% 屏宽，并夹紧在距屏幕边缘 3% 以内（避开系统边缘手势区） |
| 轨迹形状 | 二次贝塞尔弧线，控制点沿弦法线方向偏移 3%~8% 屏宽，**弯向随机**（左弯/右弯各 50%） |
| 按压延迟 | 手指按下后先停顿 **20 ~ 90 ms** 再起滑 |
| 滑动时长 | 快速轻扫 **180 ~ 420 ms**（85% 概率）／慢速拖动 **450 ~ 700 ms**（15% 概率） |
| 变速执行 | 一条弧线用 de Casteljau 算法精确切为三段，按 **起手慢 → 中段最快 → 收尾减速** 的时长比例链式派发，拼接处斜率连续 |

三段弧通过 `GestureDescription.StrokeDescription.continueStroke` 续接，构成一次完整的连续手势。

### 3. 目标应用
内置抖音（`com.ss.android.ugc.aweme`）与快手（`com.smile.gifmaker`）。主界面单选目标，点击"启动"时若目标已安装会自动拉起该 App，并显示"（未安装）"提示。

### 4. 悬浮球控制
无需退出短视频 App 即可控制：

- **点击**：暂停 / 继续切换（图标在 ▶ / ⏸ 间同步）
- **长按**：停止
- 浮球显示在屏幕右下角，仅在运行/暂停时出现，停止后自动隐藏

### 5. 状态与权限自检
主界面实时显示引擎状态、无障碍服务开启状态、悬浮窗权限状态，并提供一键跳转系统设置页的入口。

---

## 快速开始

### 环境要求
- JDK 17
- Android Gradle Plugin 8.4.2 / Kotlin 1.9.24
- `compileSdk 34`，`minSdk 26`（Android 8.0+），`targetSdk 34`

### 构建与安装

```bash
./gradlew assembleDebug     # 构建 Debug 包
./gradlew installDebug      # 安装到已连接设备
./gradlew test              # 运行 JVM 单元测试
```

### 使用步骤

1. 安装并打开「自动滑刷」。
2. 点击 **去开启无障碍服务** → 在系统无障碍设置中找到 **自动滑刷服务** 并开启（**必须**，否则无法派发手势）。
3. 点击 **授权悬浮窗权限**（非必须，但授权后才能在目标 App 内用悬浮球暂停/停止）。
4. 选择目标应用（抖音 / 快手），点击 **启动并开始自动滑刷**。
5. 进入短视频界面后自动开始滑动；右上角浮球可暂停/继续，长按停止。

> 若未开启无障碍服务就点击启动，会直接跳转系统设置页并提示。

---

## 架构设计

```
MainActivity (UI)                  FloatingControlOverlay (悬浮球)
        │  读状态/下发指令                    │ 点击暂停/长按停止
        ▼                                    ▼
   SwipeEngine  ←──────── 全局状态机单例（IDLE / RUNNING / PAUSED）
        │  监听状态变化
        ▼
AutoSwipeAccessibilityService      唯一拥有手势派发能力的组件
        │
        ├── SwipeLoop          调度循环：等待随机间隔 → 派发 → 完成回调 → 下一轮
        └── SwipePlanner       手势生成：纯数学计算，产出弧线三段参数
```

设计要点：

- **纯逻辑层与 Android 解耦**：`SwipePlanner` / `SwipeLoop` 不引用任何 Android API，通过 `RandomSource`（随机源抽象）和 `TaskRunner`（调度抽象）隔离 `kotlin.random.Random` 与 `Handler`，因此可在 JVM 上被完整单测覆盖。
- **无障碍服务只做薄封装**：`AutoSwipeAccessibilityService` 仅负责把 `SwipePlanner` 产出的三段弧用 `dispatchGesture` 链式派发，所有调度策略下沉到 `SwipeLoop`。
- **手势代际号（gestureSeq）**：每次新手势自增，旧链路的回调据此失效，避免暂停/停止后迟到的回调引起重复滑动或串扰。
- **生命周期兜底**：服务 `onUnbind` / `onDestroy` 时强制 `SwipeEngine.stop()`，防止"幽灵滑动"。
- **异常不崩溃**：悬浮窗添加/移除、权限被撤销等情况均被捕获，不影响主流程。

### 模块说明

| 文件 | 职责 |
| --- | --- |
| `MainActivity.kt` | 权限自检、目标应用选择、启停按钮、引擎状态展示 |
| `SwipeEngine.kt` | 全局状态机单例（线程安全），状态变更广播给所有监听器 |
| `AutoSwipeAccessibilityService.kt` | 手势派发（三段链式 `continueStroke`）、状态驱动循环与浮球 |
| `SwipeLoop.kt` | 调度循环：随机间隔等待、在飞手势去重、暂停/停止取消调度 |
| `SwipePlanner.kt` | 生成随机间隔与拟人化手势参数（弧线 + 三段切分） |
| `FloatingControlOverlay.kt` | 悬浮控制球的显示、图标同步与点击/长按交互 |
| `TargetApp.kt` | 目标应用枚举（包名 / 显示名 / 反查） |

### 状态机

```
IDLE ──start──▶ RUNNING ──pause──▶ PAUSED
  ▲                │                  │
  └─────stop───────┴────resume────────┘
```

无障碍服务未连接时 `start()` 返回 `false` 且状态不变；非法转换为 no-op；重复 `start()` 幂等。

---

## 测试

全部为 JVM 本地单元测试（无需模拟器），共 **31 个用例**，覆盖 TC-01 ~ TC-24：

| 测试类 | 覆盖点 |
| --- | --- |
| `SwipePlannerTest` | 间隔区间与随机性、确定性种子、手势纵向/横向范围、弧线弯向与幅度、三段时长分配与守恒、拼接点连续性、按压延迟、屏幕尺寸非法返回 null |
| `SwipeLoopTest` | 启动时只调度一次、start 幂等、手势完成后续期、stop/pause 取消调度、迟到回调不重新调度、在飞手势不重复调度 |
| `SwipeEngineTest` | 完整生命周期流转、无障碍未就绪时拒绝启动、非法转换 no-op、监听器收到 (old, new)、目标应用切换 |
| `TargetAppTest` | 包名正确性、按包名反查、未知包名返回 null |

```bash
./gradlew test
```

---

## 权限说明

| 权限 | 用途 | 是否必须 |
| --- | --- | --- |
| `BIND_ACCESSIBILITY_SERVICE`（系统授予） | 通过无障碍服务派发全局手势 | **必须** |
| `SYSTEM_ALERT_WINDOW` | 显示悬浮控制球 | 可选（推荐） |

应用**不读取窗口内容**（`canRetrieveWindowContent="false"`），仅监听窗口状态变化类型事件，不采集或上传任何用户数据，无网络权限。

---

## 项目结构

```
autoslide/
├── build.gradle                 # 根构建脚本（AGP / Kotlin 插件版本）
├── settings.gradle              # 仓库与模块配置
├── gradle.properties
└── app/
    ├── build.gradle             # 模块构建配置（namespace、SDK、依赖）
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/autoslide/app/
        │   │   ├── MainActivity.kt
        │   │   ├── AutoSwipeAccessibilityService.kt
        │   │   ├── SwipeEngine.kt
        │   │   ├── SwipeLoop.kt
        │   │   ├── SwipePlanner.kt
        │   │   ├── FloatingControlOverlay.kt
        │   │   └── TargetApp.kt
        │   └── res/
        │       ├── layout/activity_main.xml
        │       ├── xml/accessibility_config.xml
        │       └── values/strings.xml
        └── test/java/com/autoslide/app/   # 4 个测试类 / 31 个用例
```

依赖极简：运行时**零第三方库**，仅测试期依赖 `junit:junit:4.13.2`。

---

## 已知限制

- 手势为全局屏幕坐标派发，仅在**前台为短视频播放页**时才符合预期；停留在评论区、搜索页等其他页面时不会产生预期效果。
- "目标应用"仅用于启动时拉起与记录，运行期间不会校验当前前台包名。
- 无障碍服务被系统回收或用户手动关闭后，需重新开启并再次点击启动。
- 未做手势成功率回执校验：若某次手势被系统取消，循环会按正常节奏继续下一轮，而不会立即重试。
