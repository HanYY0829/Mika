## Context

小窗声音的现有链路：任务自动静音（`AppMuter`，appops 按「包+用户」静音，原模式记进持久化台账）→ 用户点小窗「恢复声音」（`ChatViewModel.unmutePip` → `ChatActions.unmuteIn` → `TaskRun.unmute()` → `restorePipSound()` 路由）→ 接管中走 `Takeover.unmute()` → `TakeoverPort.unmute` → `AppMuter.unmute()`；否则走虚拟屏目标 App → `AppMuter.unmute()`。后者把键放进 `unmuted` 集合（本次任务不再自动静它），整条链路没有反向操作。`AudioMute.mute()` 本身是安全的再静入口：键不在台账时先记录当前（已还原的）原模式，收尾 `restoreAll` 照旧能还原。

## Goals / Non-Goals

**Goals:**
- 小窗声音按钮可开可关：恢复过声音后再按一次能把目标 App 再静上，图标/文案随真实状态切换。
- 虚拟屏路径与接管路径行为一致。
- 反向静音沿用既有台账机制，任务收尾仍把声音还原（进程中途被杀的恢复逻辑同样覆盖）。

**Non-Goals:**
- 不改自动静音判定（`wanted`、主屏可见性、「不碰的包」）与设置开关。
- 不做按媒体流/音量的精细控制，仍按包静音（与现状同机制）。
- 不改核心 `TaskState`/主循环语义。

## Decisions

1. **状态源用 `AppMuter` 的 `unmuted` 集合，以 `StateFlow<Set<String>>` 暴露**——它是「用户明确恢复过声音」的真实台账，按钮状态据此翻转，延续「不伪造与真实音频无关的开关状态」的原则。备选「渲染时查 appops 实际模式」被否：异步 shell 调用、无法响应式刷新，且目标 App 切换频繁。

2. **`AppMuter.muteAgain()` 直接调 `audio.mute(key)`，不走 `wanted()`/主屏可见性判定**——与 `unmute()` 直接 `audio.restore(key)` 对称：都是用户显式意图，优先于启发式。成功后键从 `unmuted` 移回 `muted`（回到自动管理），失败留在 `unmuted`（按钮保持「静音」可重试，收尾 `restoreAll` 兜底）——与 `unmute()` 的失败语义对称。

3. **`TaskRun.unmute()` 改为切换开关 `togglePipSound()`**：当前目标键 ∈ `unmuted` → 走「再静上」，否则维持现有「恢复声音」。接管路径在 `TakeoverPort` 上加 `muteAgain(packageName)`（TaskRun 的匿名实现里直接调 `p.audio.muteAgain`），`Takeover` 加对称的 `muteBack()`；不借用 `setSound(pkg, false)`——它的现实现映射到 `opened()`，在接管场景会被「主屏可见不静」判定拒绝并误报「可能出声」。

4. **状态接线沿用 `frames`/`pipInput` 的旁路 StateFlow 模式**：`TaskRun` 内 `combine(目标 App 流, userUnmuted 流)` 得到 `pipSoundOn: StateFlow<Boolean>`，经 TaskService 对外（跟随显示中的任务），ChatScreen 以参数收、`AgentPip` 以 `soundOn: Boolean` 收。不进 `PipUi`/`TaskState`——那会把 appops 静音概念漏进核心发布状态。

5. **无记录时按钮维持「恢复声音」**：自动静音关闭、目标从未被静过时，键 ∉ `unmuted`，按钮显示与现状一致；按一下进入 `unmuted`（现状行为），之后即可反向。不额外区分「从未静过」的形态，避免引入第二种开关态。

## Risks / Trade-offs

- [再静仍是按包静音，同包在主屏可见的那份一起没声] → 与既有自动静音同机制；用户显式按下才发生，且收尾/恢复兜底还原，风险窗口只在任务期间。
- [`unmuted` 只在 `AppMuter` 内存里，进程重启后丢失，按钮回到「恢复声音」] → 重启后任务也在恢复流程里重建静音状态，与现状一致；不做持久化（现状 `unmuted` 也不持久化）。
- [目标 App 快速切换时状态短暂滞后] → `combine` 两个响应式流，切换即重算；不追加防抖。
- [接管期间再静会影响用户手上的主屏 App 声音] → 这正是用户按下按钮的要求；文案用「静音」明示。

## Migration Plan

纯增量行为变更，无数据迁移。回滚即还原代码；台账里的再静记录由收尾 `restoreAll` / 启动恢复照常清理，不留脏状态。
