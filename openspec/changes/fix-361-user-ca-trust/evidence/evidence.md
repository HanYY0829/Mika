# fix-361-user-ca-trust 真机验证记录

日期：2026-11-11 · 设备：华为 Mate X2（TET-AN50，API 31，未锁屏）· 分支：`fix/361-user-ca-trust`（基于 origin/main c8178d21）

## 方法

模拟 issue #361 的企业内网场景，全程在真机上：

1. 电脑上用 openssl 造「公司内部 CA」（`CN=Mika Test Internal CA`，指纹见 ca-fingerprint.txt）+ 由它签发的服务器证书（SAN: IP 127.0.0.1），mock-endpoint.py 起一个 OpenAI 兼容 HTTPS 端点（GET /v1/models、POST /v1/chat/completions）监听 127.0.0.1:8443。
2. `adb reverse tcp:8443 tcp:8443`，手机上 Mika 以 `https://127.0.0.1:8443/v1` 添加自定义服务商，链路不依赖 WiFi。
3. 对照变量只有一个：用户 CA 装没装（系统设置 › 安全 › 更多安全设置 › 加密和凭据 › 从存储设备安装）。

## 时间线

| 时间 | 步骤 | 结果 |
|---|---|---|
| 15:14 | 构建含修复的 debug 包装机，添加服务商指向自签端点（**未装 CA**） | **「连接失败：连不上这个地址,请检查网络和接口地址」**，服务端无 HTTP 请求（TLS 握手被拒）。截图 `02-before-ca-install.png` |
| 15:18 | 在系统设置安装测试 CA，凭据列表出现 `cn=mika test internal ca`（用户凭据） | — |
| 15:20 | 同一 App、同一地址重新「测试并保存」 | **「已连接 · 1 个模型」**，服务商保存成功；服务端收到 `GET /v1/models` 与 `POST /v1/chat/completions`（TLS 信任链通）。提示「internal-test-model 没测试通过：它不会调用工具」系当时 mock 还不会回 tool_calls，属模型能力校验，与证书无关；升级 mock 后终态仍为已连接。截图 `03-fixed-apk-ca-installed-connected.png` |
| 15:26 | （补充对照）构建删掉 `<certificates src="user"/>` 的同源 APK 装机，重放添加流程 | Compose 表单的自动化 tap 多次未命中「测试并保存」，未取得稳定 UI 结论；该对照未纳入证据链。装回修复包终态复验「已连接」。注：「显式声明 system 锚点即拒用户 CA」是 Android 平台文档化行为，issue 作者亦已实测，可信不依赖本对照 |

## 结论

- 修复包上，装 CA 前后唯一变量是用户信任区多了测试 CA，行为从「连接失败」翻转为「已连接」，服务端日志同步印证——`base-config` 增加 `<certificates src="user"/>` 后企业内部 CA 签发的端点免 root 可连。
- 公共端点回归：装 CA 前后用户的「智谱 Coding Plan」服务商始终「已连接 · 12 个模型」，未受影响。

## 现场清理（已完成）

测试服务商已删（服务商列表仅剩用户原有项）；测试 CA 已从用户凭据移除；`/sdcard/Download/mika-test-ca.crt` 已删；`adb reverse` 已移除；mock 端点已停。CA 私钥仅存于本机 /tmp，未入库。
