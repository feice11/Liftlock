# 澎湃OS 2（Android 15）锁屏面板适配 0.3.5

目标机：小米 12S Pro，移植自小米 15 Pro 的官改包（白羊），
`unicorn` / `OS2.0.103.0.VOBCNXM` / Android 15（`sdk=35`）/ LSPosed 2.2.1（API 102）。

## 现象

四个进程里只有系统界面整体停用，另外三个正常：

```
[系统界面] state=UNSUPPORTED hooks=0
  error=ClassNotFoundException: com.android.keyguard.panel.KeyguardPanelViewController
  compat: 面板拖动|off|ClassNotFoundException: com.android.keyguard.panel.KeyguardPanelViewController
[系统框架] state=LOADED hooks=16   框架场景|on  解锁不闪锁屏壁纸|on
[壁纸]     state=LOADED hooks=4    壁纸出帧|on|通用引擎模式（engine fields not found）
[设置]     state=LOADED hooks=1    设置入口|on
```

框架侧不只是装上了：回执的行为日志里能看到真实工作。

```
wallpaper:reconcile sleep layer=202 shown | lock layer=202 shown | wake layer=202 shown
goingAway lease=-1 hidden=false | ready flags=0x108 lease=-1 lock=true participant=false hidden=false
```

## 根因

`SystemUiHooks.install()` 的第一句是 `type(PANEL), type(KEYGUARD)`。异常报的是第二个，
所以 `com.android.systemui.shade.NotificationPanelViewController` 存在，而
`com.android.keyguard.panel.KeyguardPanelViewController` 不存在——`com.android.keyguard.panel.*`
这个包在 AI 15 的澎湃上还没有。

该角色在 OS2 上由 **`com.android.systemui.shade.MiuiNotificationPanelViewController`** 承担：
它是 `NotificationPanelViewController` 的 MIUI 子类，同时声明了
`updateKeyguardElementsExpansionInternal(float)` 与全部 keyguard 状态字段。

由此产生三个差异：

| 差异 | OS4 形态 | OS2 形态 |
|---|---|---|
| keyguard 宿主 | 独立对象 `KeyguardPanelViewController` | 面板自身（`MiuiNotificationPanelViewController`） |
| 状态字段名 | `interactive` `keyguardShowing` `statusBarState` … | `mInteractive` `mKeyguardShowing` `mBarState` … |
| 触控入口 | `TouchHandler#handleTouch` | `TouchHandler#handleMiuiTouch` |
| 逐帧高度写入 | `setExpandedHeightInternalAosp(F)`（有方法体） | `setExpandedHeightInternal(F)` **在面板上是抽象的**，实现在 MIUI 子类上 |
| 回调内部类名 | `$keyguardUpdateMonitorCallback$1` | `$mKeyguardUpdateMonitorCallback$1`（MIUI 字段带 `m` 前缀，匿名类名跟着字段走） |

另外两处已确证的事实：

- `initDownStates` 与 `endMotionEvent` 的**私有方法名不在 dex 里**，但它们的 nest 访问器
  `-$$Nest$minitDownStates(NotificationPanelViewController, MotionEvent)` 与
  `-$$Nest$mendMotionEvent(..., float, float, boolean)` 在，现有的 `Members.accessor` 正好命中。
- `NotificationPanelViewController.mTouchHandler` 的字段类型是
  `MiuiNotificationPanelViewController$getTouchHandler$1`；它的父类正是
  `NotificationPanelViewController$TouchHandler`，所以按后者调用合法。
- `NotificationPanelViewController` 与 `MiuiNotificationPanelViewController` 上都没有
  `notificationShadeWindowView` 字段，窗口根改为从面板视图取。

## 实现

改动落在四个文件：`SystemUiHooks`、`SystemServerSceneHooks`、`NativeReflection`，以及
`motion-core` 的 `Members`（含单测）。全部按"这个成员存不存在"做候选，没有任何机型或版本判断。

| 改动 | 内容 |
|---|---|
| keyguard 宿主 | `KEYGUARD` 由单个类名改为候选链：`MiuiNotificationPanelViewController` → `KeyguardPanelViewController`，用 `Members.load` 取第一个能加载的 |
| keyguard 字段 | 7 个字段各给两种拼写，安装期解析一次后按名读取；`keyguardPanelViewInjector` 同理 |
| 窗口根 | `shadeWindowView()`：宿主有字段就用字段，没有就把面板视图的根视图当作该窗口（同一扇窗） |
| 触控入口 | `handleTouch` 取不到时取 `handleMiuiTouch`，并把实际用到的那一种记进兼容报告 |
| 逐帧高度 | `recover()` 原先按 MIUI 名字重新解析 `setExpandedHeightInternalAosp`，改为使用安装期解析出的方法。这一处对**任何只有 AOSP 名字的构建**都成立：安装会成功，但一次回弹就运行期 `fail()` 停用适配 |
| 抽象声明 | `Members.concrete(...)` + `withBody(...)`：面板上的声明没有方法体时改从实际被实例化的 MIUI 子类取实现。`setExpandedHeightInternal` 是本次扫描里**唯一**会被挂钩的抽象方法；`isFalseTouch` 与 `fling` 落在同一形态上（它们只被调用，一并统一） |
| 回调内部类 | `Members.inner` 增加 `m` 前缀候选，使 `$mKeyguardUpdateMonitorCallback$1` 能被按结构找到 |

## 边界

- 系统框架侧只改运行期取不到成员的两处（`WindowStateAnimator` 的 surface、`SurfaceAnimator` 的 leash），
  不动场景时序与交接逻辑；壁纸与设置两个进程未改。
- 下列可选项在 OS2 上会如实报 `off`，不动核心手势：
  - 壁纸缩放接管：`doDeductedImageScaleAnim` `calculateWallpaperMatrixArray` `initDeductedImageScale` 均不存在。
  - 退场保持：OS2 的 keyguard 回调类只有 `onKeyguardBouncerStateChanged` 与
    `onKeyguardVisibilityChanged`，没有 `onKeyguardShowingChanged`。
  - 唤醒预备：没有 `onWakeupAnimationEnd`。
  - 区域模糊：`com.miui.systemui.shade.blur.ShadeWindowBlurControllerImpl` 不存在，
    按既有分支保持不做解锁模糊层着色。
- `panel` 与 `keyguard` 在 OS2 上会指向同一个对象。已逐个复核全部身份比较，都是单向判定，
  没有依赖两者互斥的分支。

## 静态验证

对着 `MiuiSystemUI.apk`（deodexed，三个 dex）的全量成员表，按 `install()` 的求值顺序逐个核对
核心依赖，27/27 命中；`framework.jar` 中 `applyTransactionOnDraw`、`getViewRootImpl`、
`BackgroundBlurDrawable`、`setCropRect`、`setBlendModes`、`setCornerRadius` 均存在。

本地：`gradle :motion-core:test :app:assembleRelease :app:lintRelease` 通过，lint 0 错误。

## 真机验证

### 第一轮（未改动，仅确证现象）

系统界面 `UNSUPPORTED` / `hooks=0`，唯一错误是
`ClassNotFoundException: com.android.keyguard.panel.KeyguardPanelViewController`；
另外三个进程正常，框架回执里的行为日志证明其真实工作。

### 第二轮（补齐候选之后）

系统界面 `hooks=12`，核心成员全部解析成功，`壁纸跟随` `底部圆角` `区域模糊` `模糊缩放修正`
`通知层修复` `认证页位置` `底部解锁区域` 均已 `on`。仍停用的原因是

```
IllegalArgumentException: Cannot hook abstract methods:
  public abstract void com.android.systemui.shade.NotificationPanelViewController.setExpandedHeightInternal(float)
```

即本文档「逐帧高度写入」一行所述的差异。据此增加 `Members.concrete` 与 `withBody`。
同一轮里 `认证页让位` 报 `bouncer callback missing`，据此给 `Members.inner` 增加 `m` 前缀候选。

### 第三轮（抽象方法修复之后）

系统界面与系统框架两个进程都变成 `state=loaded`、`error` 为空，上滑走完完整周期：

```
release:coordinated p=0.5293 vy=-19290.857 expand=false falsing=false types=4/4
height-fling target=0.0 start=1693.6289 engaged=true permit=2
animator:start native-ms=122 | spring:start delta=-1693.6658 ms=521
spring:end finger=false cancelled=false | native-auth:request-under-opaque-cover | finish token=2
```

`motion=119`、`touch=77`、`height=168`、`fling=3`、`spring=3`、`coordinatedRelease=3`；
弹簧中途重新接手（`spring:cancel` → `g4 down retouch=true`）也正常。

同一轮修掉框架侧的一处运行期停用：

```
IllegalStateException: com.android.server.wm.WindowStateAnimator#mSurfaceControl missing
```

Android 15 底座上该字段不存在，只有 `mSurfaceController`（`WindowSurfaceController` 类型）
与 `getSurfaceControl()`；`mSurfaceControl` 由 client-surface 重构引入。同理 `SurfaceAnimator`
在该底座上是字段 `mLeash` 而非方法 `getLeash()`。两处都改为先按较新形态取、取不到再按较旧形态取。

### 第四轮（把返回目标换成桌面后再测）

累计 `motion=521`、`touch=215`、`height=808`、`release=11`、`fling=7`、`spring=5`、
`coordinatedRelease=7`，四个进程保持 `state=loaded` 且 `error` 为空。
壁纸引擎绑定也由 `no-window` 变为可解析：`lockBinding=server:58/client:59/engine:59/sameWindow:true`。

但场景仍未 ready，两个现象分开：

- **锁屏时**：返回目标正确（`target=HOME`，桌面为第三方 Niagara Launcher），
  但 `phase=PREPARING`、`binding=329`、`activity=STOPPED`、`ancestors=false`、`drawn=false`、
  `blocker=invalid-window-surface`、`liveScene=返回窗口未显示`。
- **解锁后**：出现约 60 次/秒的
  `stop lease=<新租约> 锁屏/AOD/遮挡状态不允许场景` 与
  `stop lease=-1 手势结束，场景已交回原生` 交替；系统界面侧对应一串
  `scene:server-restored`。此时确实已经不在锁屏，框架拒绝是对的，
  但反复的 begin/stop 会持续占 CPU。

这两处各自失败在 `allowed()` 的哪一项，由第五轮的逐项诊断确证，见下一节。

### 一处观察，本次没有改动

给框架侧 `allowed()` 加了一行逐项诊断后（该诊断在本 PR 内），锁屏态那次失败的明细是：

```
lease=false  showing=true  aod=false  occluded=false  goingAway=false  target=true  safe=true
```

即：返回目标选到了、也不是 AOD（原本怀疑 `isAodShowing`，**实测排除**），
失败的是 `lease=false` —— 租约过期后 `watch` 走"超时或原生状态改变"分支停掉场景。

而 `SceneLease.renew(token, now)` 的第一句是 `if (!active(now) || token != id) return false;`，
配合框架侧的 `if (!lease.renew(id, now())) return;`，含义是：
**续约一旦迟到超过 `TIMEOUT_MS`（1800ms），持有者自己也无法救回，只能靠新的 `begin` 重开。**

本 PR 没有改动租约与目标选择的语义。它们位于交接时序之中，属于作者已反复调整的部分，
因此只在此记录，并作为问题报给作者。

顺带：本机空闲锁屏时 `system_server` 占单核 25%、`com.android.systemui` 占 1%（`/proc` 采样 8 秒），
模块并没有持续占用 CPU；一次上滑手势期间 SystemUI 的帧统计为
`Total 198 / Janky 3 (1.52%) / p50 10ms / p95 14ms / p99 22ms / Missed Vsync 1`，
也未见明显掉帧。（该 ROM 的 `dumpsys cpuinfo` 返回的是过期缓存，两次采样完全相同且自相矛盾，不可用。）

### 返回窗口无法呈现时的行为（本平台最关键的表现）

框架在这台机器上**是活着的、且持续回执**，因此 `NativeSceneChannel.frameworkSilent()`
（只在完全没有任何回执时为真，1.5 秒）不会成立。`finish()` 于是走"等场景"那条路：

```
g16:t=136482 native-fling-end:await-presented-frame
g16:t=136482 native-auth:request-under-opaque-cover
g16:t=138424 native-fling-end:complete cancelled=true
```

`sceneDeadline` 为 5 秒，到期后执行 `recover(true)`，也就是**把这次上滑判为失败手势并回弹**。
用户侧的表现因此是"上滑后回到锁屏、要多滑几次才偶有一次进入"，
而不是"卡住不动"；跟手本身一直是正常的。
（早先记录的一次 6534ms 是同一路径的另一次观测，并非成功进入的那一次。）

本 PR **没有改动这一判定**。把它改成"像 `frameworkSilent()` 那样直接交给原生解锁"，
表面上只差一个分支，但它位于交接时序之中：改错的表现是黑屏或花屏而不是回弹，
因此留给作者决定。相关的一版尝试（把等待按"无进展"提前结束）在本机验证时
恰恰暴露了这一点——等待提前结束只是让回弹更早发生。

### 仍未生效

- **场景联动**（上滑透出桌面/应用与壁纸跟随）：见上一节，未 ready；
  锁屏态失败在 `lease` 一项，租约与心跳的关系也记在该节。
- **壁纸引擎主动出帧**：`壁纸出帧|on|通用引擎模式（engine fields not found）`、`wallpaperFrames=0`。
  绑定关系已经可解析，但无法主动请求一帧。

### 尚未解决

`壁纸缩放接管`、`退场保持`、`唤醒预备`、`解锁模糊层`、`晚加载桌面` 在 OS2 上均缺少对应成员，
按既有约定如实报 `off`，不影响核心跟手。
