## Why

小窗（AgentPip）左上角的声音按钮是「一次性恢复声音」：任务自动静音后，用户点一下能把当前 App 的声音放出来，但之后再点没有反向效果——声音一直响，只能关掉整个小窗等任务收尾才停（issue #589，#588 为重复帖）。根因是 `AppMuter.unmute()` 把键放进 `unmuted` 集合后，整条链路上没有任何「再静上」的入口。

## What Changes

- `AppMuter` 增加对称的用户操作 `muteAgain(packageName, user)`：用户明确按下时直接经 `AudioMute.mute` 静上（不走自动静音的 `wanted`/主屏可见性判定），键从 `unmuted` 移回 `muted`；同时把 `unmuted` 集合以 `StateFlow` 暴露，供按钮显示真实状态。
- `TaskRun` 把浮层的 `unmute()` 扩成按当前状态切换的开关：目标 App 已被用户恢复过声音时走「再静上」，否则维持现有「恢复声音」；接管路径经 `TakeoverPort` 走 `setSound(pkg, false)` 的关方向。新增小窗声音状态的对外 `StateFlow`（目标 App + unmuted 记录合成）。
- `AgentPip` 的声音按钮跟随真实状态切换图标与文案（`恢复声音` / `静音`），点击即切换；无状态信息时维持现状显示「恢复声音」。
- 新增 `pip_mute` 文案，随现有 `pip_unmute` 同步到 8 个语言目录。
- 单测：`AppMuterTest` 补 `muteAgain` 的语义（直接静上、回到自动管理、收尾 restoreAll 仍还原、失败时不丢记录）。

不改的：自动静音的判定规则（`wanted`、主屏可见、不碰的包）；设置里「打开的 App 静音」开关；收尾 `restoreAll` 的还原责任；接管/虚拟屏两条路径的路由逻辑。

## Capabilities

### New Capabilities
- `pip-sound-toggle`: 小窗声音按钮的双向开关行为——按钮动作与显示状态都基于任务静音台账的真实记录。

### Modified Capabilities

（无——`AppMuter` 既有自动静音需求不变，只是补一个用户显式反向操作。）

## Impact

- `app/src/main/java/com/wangzan101/mika/task/AppMuter.kt`：新增 `muteAgain` 与 `userUnmuted` StateFlow。
- `app/src/main/java/com/wangzan101/mika/task/TaskRun.kt`：`unmute()` 改为切换开关，新增小窗声音状态流。
- `app/src/main/java/com/wangzan101/mika/takeover/Takeover.kt` / `TakeoverPort`：接管路径补关方向。
- `app/src/main/java/com/wangzan101/mika/ui/`：`ChatViewModel`（动作与状态）、`ChatScreen`/`MainActivity`（接线）、`AgentPip.kt`（按钮状态化）。
- `app/src/main/res/values*/strings_chat.xml`：新增 `pip_mute`。
- `app/src/test/java/com/wangzan101/mika/task/AppMuterTest.kt`：新增用例。
- 风险：反向静音仍是按包静音（同一包在主屏的另一份实例会一起没声）——与既有自动静音同一机制，收尾统一还原；显式用户意图优先，与 `unmute` 对称。
