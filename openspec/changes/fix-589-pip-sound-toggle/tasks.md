## 1. 静音台账（AppMuter）

- [x] 1.1 `AppMuter` 增加 `muteAgain(packageName, user): Boolean`（直接 `audio.mute`，键从 `unmuted` 移回 `muted`、清出 `early`；失败留 `unmuted` 可重试），并把 `unmuted` 集合以 `StateFlow<Set<String>>` 暴露（`unmute`/`muteAgain` 时更新）
- [x] 1.2 `AppMuterTest` 补用例：恢复后再静回到自动管理、再静后 `restoreAll` 台账仍还原、再静失败保留重试、`userUnmuted` 流随操作更新

## 2. 任务与接管路由（TaskRun / Takeover）

- [x] 2.1 `TakeoverPort` 增 `muteAgain(packageName)`（TaskRun 匿名实现直接调 `p.audio.muteAgain`），`Takeover` 增对称 `muteBack(): Boolean`
- [x] 2.2 `TaskRun.unmute()` 改为 `togglePipSound()`：目标键 ∈ `unmuted` → 走「再静上」（接管经 `Takeover.muteBack()`，否则 `p.audio.muteAgain`），否则维持现有恢复路径
- [x] 2.3 `TaskRun` 暴露 `pipSoundOn: StateFlow<Boolean>`（`combine` 目标 App 流与 `userUnmuted` 流；接管时以被接管包为准），TaskService 按显示中的任务对外提供

## 3. 小窗 UI 与文案

- [x] 3.1 `AgentPip` 声音按钮状态化：`soundOn: Boolean` 参数，已恢复时图标/文案为「静音」（`pip_mute`），点击调 `onToggleSound`；ChatViewModel/ChatScreen/MainActivity 按 `frames`/`pipInput` 同款接线
- [x] 3.2 新增 `pip_mute` 文案并同步 8 个语言目录（values、zh、b+zh+Hant、ko、fa、in、ar、pt）

## 4. 验证

- [x] 4.1 `./gradlew :app:testDebugUnitTest`（AppMuter 新用例与既有全绿）+ `:app:assembleDebug` 构建
- [x] 4.2 真机验证：任务自动静音 → 小窗恢复声音 → 再按静回去（appops/dumpsys 取证）→ 收尾还原；截图与时间线存 `openspec/changes/fix-589-pip-sound-toggle/evidence/`
