## 1. 展开路径守卫

- [x] 1.1 `CapsuleCardContent.kt`：新增 `runOverlayOpen(reduceMotion, animate)`（`withTimeoutOrNull((CARD_IN_MS * scale) + OPEN_GRACE_MS)`，scale 取 MotionDurationScale、`coerceAtMost(10f)`），`OverlayMotion` 的 open 分支整段（SETTLE 等待、`opened()`、`animateTo`）纳入守卫；超时 `snapTo(1f)` 并补调 `opened()`，`openedCalled` 保证恰好一次；关动画分支行为不变
- [x] 1.2 确认边窗 `CapsuleOverlay`（恒 `open = true`）经同一 composable 自动受益，无调用点改动

## 2. 单元测试

- [x] 2.1 `OverlayMotionTest` 补 `runOverlayOpen` 四场景：正常完成不被兜底、reduceMotion 直接返回、缺帧（`awaitCancellation`）超时落位、慢动作倍率换算（极大倍率截到 10 倍）

## 3. 真机取证（华为 Mate X2 / HarmonyOS 4.2）

- [ ] 3.1 现有 `CapsuleWindowDeviceTest.closedCardLeavesNoPixelsBehind` 在改动后通过（收起残帧回归）——**受阻**：本机无障碍绑定在 instrumentation 强停后卡死（Binding 挂起、不再重试，重启可解但 PIN 锁不可 adb 解），两个设备用例均到不了断言；详见 evidence.md
- [ ] 3.2 `CapsuleWindowDeviceTest` 新增展开态几何断言：点开卡片后 `capsule-card` 节点 bounds 在屏内、左右留边对称（居中容差）、高度不低于该档位预期下限；真机跑过并留截图——用例已写好并编译通过；本机受阻同上，待无障碍环境正常的设备执行
- [x] 3.3 证据落 `evidence/`：时间线 + 截图（是否随 PR 发布由用户定）——时间线与单测结果已落，真机截图因用例未能执行而无
- [x] 3.4 （实施中发现）修复 `DeviceSupport.rebindAccessibility()` 只认完整组件名、与 App 自己写短形式（`PermissionCommands.accessibilityService`）不匹配导致重绑被跳过的测试基建 bug

## 4. 构建与收尾

- [x] 4.1 `./gradlew :app:testDebugUnitTest`（全量）与 `:app:assembleDebug` 通过
- [x] 4.2 提交（中文提交信息）、推 fork、建 PR 关联 #323，正文首屏给实测证据；如实写明原始帧停未能复现、守卫由单测与代码路径证明（PR #621）
