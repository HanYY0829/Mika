## Context

`app/src/main/res/xml/network_security_config.xml` 当前 `base-config`：`cleartextTrafficPermitted="true"`（为局域网 http 模型服务开明文，见注释与 `LocalHosts` 提示），trust-anchors 只声明 `system`。Android 规则：显式声明锚点后用户 CA 一律不信，所以装了公司 CA 也连不上自签端点（#361）。出站 HTTPS 通道众多（模型 API、搜索、下载、浏览器、MCP、OTLP……），OkHttp 客户端在 12 处各自构建（虽有 `NetProxy.shared.client()` 包装点但非人人走它）。

## Goals / Non-Goals

**Goals:**
- 用户安装的 CA 被全部出站 HTTPS 通道信任，企业内网自签端点免 root 可用。
- 改动最小（声明式一行 + 注释），不动任何 Kotlin 网络代码。

**Non-Goals:**
- 不做设置开关 / 按端点 pinned trust（issue 方案 2/3）：客户端构建分散，运行时热切换范围失控；需要时维护者可在此基础上收紧。
- 不动明文 http 的现有许可，不碰 core 模块。

## Decisions

**在 `base-config` 加 `<certificates src="user"/>`，而不是运行时自定义 TrustManager。**
- 一处声明覆盖所有通道（包括未来新增的客户端），无遗漏面；TrustManager 方案要逐客户端改 12 处，漏一处就是「有的能连有的不能」的疑难杂症。
- 与现有先例一致：配置已为「用户自建服务」开全局明文 http；信任用户 CA 是同一姿态、更小的放宽（装 CA 需用户在系统设置主动操作确认，明文则零门槛）。
- debug/release 共用 main 这一份配置，调试探活同样受益。

**守护测试放 `app/src/test`（Robolectric/JUnit 读资源解析 XML），不做行为级真机自动化。**
- 证书信任行为由系统网络栈解析该 XML 决定，单测里造不出来；真机手测（装 CA → 连自签端点）承担行为验证，测试只防「未来误删这一行」的回退。

## Risks / Trade-offs

- [设备被装恶意用户 CA 后流量可被解密] → 攻击前提是攻击者已能在受害者设备系统设置里装证书；同一前提下明文许可早已允许无加密直连，攻击面不因此扩大。PR 描述里向维护者写明这一权衡。
- [维护者可能更想要设置开关（默认关）] → 本变更与其不冲突：开关方案落地时可把锚点改回 system、由开关挂 user；PR 里说明。
- [Robolectric 解析 resources 是否读到 xml/] → xml/ 资源在单测里可经 `XmlResourceParser` 或直接文件流读取；实现时先验证读取路径，不行就退化为从仓库相对路径读文件断言（仍守护同一事实）。

## Migration Plan

合入即生效，无数据迁移；回滚 = 删掉那一行。
