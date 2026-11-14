## Why

#323（荣耀 30 Pro+ / HarmonyOS 4.2）：点悬浮球展开的卡片停在半开状态——画面缩小、偏向停靠边（「打开的软件画面没有全屏」）；点右上角 ×，整张往停靠边缩走却留一半在屏上；边上那颗胶囊还画在残卡后面，点它又回到半开。三点症状循环，v0.1.37 上报告人复测仍在。收起路径已有三层防卡死（`runOverlayClose` 帧停超时、97053397 收尾看门狗、ce037717 关闭后强制不可见 + 像素对比真机测试），但**展开路径完全没有同类守卫**：`OverlayMotion` 里 `repeat(SETTLE_FRAMES) { withFrameNanos {} }` 和 `animateTo(1f)` 都挂在帧时钟上，浮层一收不到帧就永久挂起——`opened()` 不执行，`cardOut` 不置位（边上的胶囊继续画），窗口可见性状态机卡死在 opening，卡片停在中间缩放值。

## What Changes

- 给 `OverlayMotion` 的展开路径加与收起对称的帧停守卫 `runOverlayOpen`：按 `MotionDurationScale` 换算超时，帧停时兜底 `snapTo(1f)` 并补调 `opened()`，保证状态机（`cardOut`、`syncWindowVisibility`）总能落位。
- 边窗（`CapsuleOverlay`，恒 `open = true`）的同一处 `SETTLE_FRAMES` 等待同样纳入守卫：胶囊不会因帧停而永远不出现。
- 单测镜像 `OverlayMotionTest`：正常动画、关动画、缺帧超时、系统慢动作倍率四类场景。
- 真机（华为 Mate X2 / HarmonyOS 4.2，与报告机同 OS 族）取证：跑现有 `CapsuleWindowDeviceTest.closedCardLeavesNoPixelsBehind`（收起残帧回归），并新增展开态几何断言——卡片可见尺寸达到缩放档位的完整大小、水平居中、无贴边裁切（#323 第 1 点的断面）。

## Capabilities

### New Capabilities
- `capsule-overlay-motion`: 悬浮胶囊浮层（卡片展开/收起、边窗出现）动效的帧停防护——展开与收起两条路径都必须在有限时间内落位到终态，帧时钟停摆不能把状态机卡在中间。

### Modified Capabilities

（无——`openspec/specs/` 尚无主规格，本变更首次为该能力立规格。）

## Impact

- `app/src/main/java/com/wangzan101/mika/capsule/CapsuleCardContent.kt`（`OverlayMotion`、新增 `runOverlayOpen`）
- `app/src/test/java/com/wangzan101/mika/capsule/OverlayMotionTest.kt`（新增用例）
- `app/src/androidTest/java/com/wangzan101/mika/capsule/CapsuleWindowDeviceTest.kt`（新增展开态几何断言；真机取证用）
- 不改停靠几何、不动 `CardDocking`/`CapsuleDocking` 纯函数，不涉及其它浮层行为。
