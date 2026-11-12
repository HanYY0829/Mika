## Why

企业内网自建的 OpenAI 兼容端点（GPUStack / vLLM 一类）用公司内部 CA 签发证书，Mika 连不上：`network_security_config` 显式只信系统证书后，Android 不再接受用户安装的 CA（#361）。现有配置已为同一场景开了全局明文 http（局域网模型服务），HTTPS 自签这条路的姿态应当一致——信任用户主动安装的 CA 风险更低（安装本身就需要用户在系统设置里操作确认）。

## What Changes

- `app/src/main/res/xml/network_security_config.xml`：`base-config` 的 trust-anchors 增加 `<certificates src="user"/>`，系统证书照旧信任。
- 更新该 XML 的注释，说明为何接受用户 CA（与明文 http 的先例同一理由：用户自己的设备、自己的服务）。
- 单测：守护测试断言配置同时声明 `system` 与 `user` 锚点（防止未来被误删回归 #361）。

## Capabilities

### New Capabilities
- `tls-trust`: 出站 HTTPS 连接信任哪些证书锚点（系统 + 用户安装），覆盖模型 API、搜索、下载、浏览器等全部出站通道的证书信任基线。

### Modified Capabilities

（无——`openspec/specs/` 目前为空，这是仓库第一份规格。）

## Impact

- 代码：仅 `app/src/main/res/xml/network_security_config.xml` 一处声明，无 Kotlin 行为改动；全部出站 HTTPS（模型 API、网页搜索、APK 更新、浏览器、MCP、OTLP 等 OkHttp 通道）的信任链同时生效。
- 安全权衡：接受用户安装的 CA 后，设备上被装了恶意 CA 的人可以中间人解密 Mika 的流量——与已存在的全局明文 http 许可相比不放宽攻击面（明文连加密都没有），且装 CA 需用户在系统设置里主动确认。
- 不做的事：不加设置开关（OkHttp 客户端散布 12 处，运行时切换范围失控；issue #361 方案 2/3 留给维护者需要时再做）；不改 core 模块。
