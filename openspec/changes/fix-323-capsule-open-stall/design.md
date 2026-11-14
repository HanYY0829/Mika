## Context

`OverlayMotion`（`CapsuleCardContent.kt:272-314`）是胶囊两个浮层窗口共用的出入场动效：只动 `graphicsLayer` 的缩放/透明度，不改布局、不逐帧改窗口。收起路径经三轮加固：`runOverlayClose` 用 `withTimeoutOrNull((CARD_OUT_MS * scale) + CLOSE_GRACE_MS)` 兜底帧停（MotionDurationScale 最多按 10 倍算），超时后 `snapTo(0f)` + `closed()`；外加看门狗（97053397）与关闭后把浮层根视图显式置不可见（ce037717）。展开路径（`open = true` 分支）没有任何超时：

```kotlin
if (shown.value == 0f) repeat(SETTLE_FRAMES) { withFrameNanos { } }   // 帧停 → 永久挂起
opened()                                                                // cardOut=true、syncWindowVisibility
shown.animateTo(1f, tween(CARD_IN_MS, easing = MikaMotion.Decelerate)) // 帧停 → 卡在中间值
```

卡片窗口的 `onOpened` 回调把 `cardOut` 置 true（边窗 `CapsuleOverlay` 据此不再画那颗胶囊）并同步窗口可见性。帧停在 SETTLE 阶段 → `opened()` 不执行 → 边上的胶囊继续画（#323 里「藏在后面的悬浮球」）、窗口状态卡在 opening；帧停在 animateTo 阶段 → 卡片停在 0.6–1.0 之间的缩放、`transformOrigin` 指向停靠边（症状：画面不全、贴右缩小）。边窗自己（`CapsuleOverlay`，恒 `open = true`）的同一处 SETTLE 等待同理，帧停则胶囊永远不出现。

报告机为荣耀 30 Pro+ / HarmonyOS 4.2；本地真机华为 Mate X2 / HarmonyOS 4.2 同 OS 族，可做行为取证（能否复现帧停不强求，状态机层面代码可证）。

## Goals / Non-Goals

**Goals:**
- 展开路径与收起路径同级防卡死：无论帧时钟是否停摆，`OverlayMotion` 的 open 分支在有限时间内到达终态（`shown = 1f`、`opened()` 已调用）。
- 边窗恒开分支同样有守卫。
- `OverlayMotionTest` 镜像四类场景（正常、关动画、缺帧超时、慢动作倍率）。
- 真机取证收起残帧回归 + 展开态完整几何（全尺寸、居中、无裁切）。

**Non-Goals:**
- 不改停靠/居中几何算法（`CardDocking`、`CapsuleDocking`）。
- 不改收起路径既有防护，不动窗口管理（`syncWindowVisibility` 的语义）。
- 不处理「关闭动画期间用户再点胶囊」之类的交互竞态（现状行为保持）。
- 不给 #323 报告人机型的其它症状（无线调试重连折腾）立案。

## Decisions

1. **镜像 `runOverlayClose` 做 `runOverlayOpen`，而不是给整个 `LaunchedEffect` 套超时。**
   收起侧的 `runOverlayClose(reduceMotion, animate)` 已有现成测试模式（`runTest` 直测挂起函数、`awaitCancellation` 模拟帧停），展开侧照抄这个形状：`runOverlayOpen(reduceMotion, animate)` 内部 `withTimeoutOrNull((CARD_IN_MS * scale) + OPEN_GRACE_MS)` 包住「SETTLE 等待 + `opened()` + `animateTo`」整段。超时时返回 false，由调用处 `snapTo(1f)` 兜底并确保 `opened()` 只补调一次。备选（整个 LaunchedEffect 套 withTimeoutOrNull）会把 reduceMotion 分支也卷进去，且超时粒度混在协程管理层，不如纯函数可测。

2. **`opened()` 的调用时机保持「动画起步前」，超时兜底时改为「snap 之后补调」。**
   正常路径顺序不变（SETTLE → `opened()` → animateTo）：`cardOut` 置位与窗口可见性同步发生在动画起步前，与现状一致。超时路径先 `snapTo(1f)` 再补调 `opened()`：帧停时本来就没有下一帧可画，顺序只影响状态——先把 `shown` 归位，再让状态机落位，恢复供帧后窗口直接是终态。用一个 `var openedCalled` 保证不重复调（`onOpened` 里的 `syncWindowVisibility` 非幂等开销也应只发生一次）。

3. **超时时长 `CARD_IN_MS * scale + OPEN_GRACE_MS`，`OPEN_GRACE_MS` 沿用 500ms。**
   展开动画 240ms，与收起 180ms 同量级；grace 同值，系统慢动作倍率同样 `coerceAtMost(10f)`。两条路径常数各自独立命名，不共用，避免以后单边调整牵连。

4. **边窗分支复用同一个 `runOverlayOpen`。**
   `CapsuleOverlay` 的 `OverlayMotion(open = true, …, onClosed = {})` 与卡片共用 composable，守卫放在 `OverlayMotion` 内部则两处自动同时受益，不需要在调用点分别改。超时兜底对该分支表现为：胶囊直接以全尺寸出现（snap 过的头），可接受——它本来就该一直显示。

5. **真机取证用现有像素测试 + 新增几何断言，不新增依赖。**
   `CapsuleWindowDeviceTest` 已有经 Mika 自身无障碍服务找浮层节点、`UiAutomation.takeScreenshot` 比像素的完整模式。展开态几何断言按同一模式：点开卡片后，从无障碍树取 `capsule-card` 节点 bounds，断言（a）节点在屏内、左右留边对称（水平居中，容差按 density 换算）；（b）可见高度达到该缩放档位的预期范围。这条断言防的是 #323 第 1 点一类的「半开/裁切」回归，不依赖复现帧停。

## Risks / Trade-offs

- [真机上展开动画偶发超过 240ms+500ms（重负载）被误判超时] → 超时兜底只 `snapTo(1f)`，终态与动画自然结束完全一致，用户看到的差别只是动画跳变到全尺寸；状态机落位本就是目的。
- [`opened()` 在超时路径晚于正常路径调用，边窗多画了一小会儿] → 与现状（永远不落位）相比严格更好；500ms 量级的胶囊多显示无功能影响。
- [无法在本地复现报告机的帧停] → 守卫的价值由单测的 `awaitCancellation` 场景 + 状态机代码路径证明；真机取证覆盖「修复不破坏正常展开/收起」与「展开终态几何正确」。PR 里如实写明未能复现原始帧停。
- [几何断言在不同缩放档位/分屏下脆弱] → 断言只取默认档位、竖屏主屏场景，与现有设备测试的环境约定一致（`GestureTapTestActivity` + `CapsuleMode.Always`）。

## Migration Plan

单 PR 合入 main，无数据迁移；回滚 = revert 单提交。

## Open Questions

（无——守卫参数、调用点、测试形状都已对照收起侧确定。）
