# 在 USB 连接的手机上读取完整运行日志

`./gradlew :app:assembleLogs` 生成本地检查包 `app-logs.apk`，显示名称为
**Mika Logs**，包名为 `com.wangzan101.mika.logs`。它使用本机调试签名，
与官方 Mika 并排安装，不覆盖原应用的对话、密钥和设置。

该构建开启 Android `debuggable`，所以已获 USB 调试授权的电脑可以通过
`adb run-as` 读取此应用的私有目录；它不改变其他应用的访问权限。
详细 Trace 默认开启，也可在 Mika 的设置中关闭。运行结束后，现有的调试包
逻辑还会把 `run.json`、`events.jsonl` 和截图镜像到外部文件目录 `runs/`。

安装并配置模型、手机助手后，执行一次任务，再拉取记录：

```sh
adb -s SERIAL install -r app/build/outputs/apk/logs/app-logs.apk
adb -s SERIAL shell run-as com.wangzan101.mika.logs ls files/runs
python3 tools/pull-logs.py --device SERIAL --output mika-logs.tar
```

非 PATH 中的 adb 可通过 `--adb /absolute/path/to/adb` 指定。
归档只包含 `files/runs`（概要、工具参数及结果、截图）和
`no_backup/harness-traces`（已记录的详细 Trace），不导出模型密钥或整个应用数据。
为得到一致的记录，请在任务完成后导出。Trace 沿用现有脱敏、长度限制及存储配额，
不会包含模型的隐藏思考；运行前未记录的请求和响应不能事后恢复。

官方版与本地包签名不同，无法直接覆盖安装。此包也不能读取官方版私有目录中的旧任务。
其他构建类型的调试标记、Trace 默认值和包名保持原有配置。
