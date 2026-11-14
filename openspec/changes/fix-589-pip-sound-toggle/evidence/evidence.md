# #589 真机验证时间线（华为 Mate X2, HarmonyOS 4.2, API 31）

## 环境

- 构建分支 `fix/589-pip-sound-toggle` @ 143e6576（22 文件 +171/−25，含全部修复），debug 包安装于真机。
- 任务经 USB 连接通道 `POST /api/v1/tasks` 提交：**打开华为音乐播放每日推荐歌曲，保持播放不要停**（run `28846183-…`）。
- 声音开关经 debug-only 临时广播触发 `TaskRun.togglePipSound()`（与 pip 按钮同一入口；验证后已移除临时代码，不进 PR）。

**为何用广播而不是点按钮**：任务在虚拟屏上操作 App 期间，系统输入焦点被虚拟屏 display 持有（`mFocusedWindow=com.huawei.music`，display 94），`adb shell input tap` 全部路由进虚拟屏，主屏 Mika 窗口的 overlay 收不到注入；`input` 无 `--display` 参数（API 31），`sendevent` 被 ROM 拒绝（Permission denied）。此为该设备的环境限制，与修复本身无关；按钮点击链路（`AgentPip` → `ChatViewModel.togglePipSound` → `togglePipSoundIn` → `TaskRun.togglePipSound`）由 `ChatViewModelTest` 覆盖。

## appops 时间线（PLAY_AUDIO of com.huawei.music）

| 时刻 | 动作 | appops | 结论 |
| --- | --- | --- | --- |
| T0 | 任务打开华为音乐（自动静音生效） | `ignore` | 前置成立 |
| T1 | 触发声音开关（第 1 次，应恢复） | `Default mode: allow` | 恢复 ✓ |
| T2 | 触发声音开关（第 2 次，应再静上） | `ignore` | **反向静音 ✓** |
| T3 | 触发声音开关（第 3 次，应再恢复） | `Default mode: allow` | 可反复 ✓ |
| T4 | 取消任务，收尾 `restoreAll` | `Default mode: allow` | 收尾还原 ✓ |

logcat 全程无 `could not restore/mute again` 失败日志。

另一轮独立任务（`c2d97c75-…` 打开华为音乐停留首页）同样验证了 T0 自动静音（`ignore`）与 T4 收尾还原（`allow`）。

## 单测

- `AppMuterTest` 新增 4 例：恢复后再静回到自动管理、收尾仍还原；主屏可见也静（显式意图优先）；再静失败保留恢复态可重试；`userUnmuted` 流随操作更新。
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` 全绿；`:core:test` 全绿（未触碰 core）。

## 截图（本地留存，不随 PR 发布）

- `mika-pip2.png`：任务运行中展开的小窗，左上角「恢复声音」按钮（改动前行为：按一次恢复、再按无效）。
- `mika-tk2.png`：等待接管时自动展开的预览（该布局无控制按钮，验证期间用于定位）。
- `evidence-running.png`：验证期间主屏状态。
