## ADDED Requirements

### Requirement: 出站 HTTPS 信任用户安装的 CA

App 的全部出站 HTTPS 连接（模型 API、网页搜索、下载、内置浏览器、MCP 等）除系统证书外，还 MUST 信任用户在系统设置里安装的 CA 证书，让企业内网自签端点（内部 CA 签发的 OpenAI 兼容服务）无需 root 即可直连。明文 http 许可维持现状不变。

#### Scenario: 连接内部 CA 签发的端点
- **WHEN** 用户在系统设置里装了公司内部 CA，并配置自签 HTTPS 的 OpenAI 兼容端点为服务商
- **THEN** 模型连接、对话收发正常工作，不出证书校验失败

#### Scenario: 未装用户 CA 的设备
- **WHEN** 设备上没有任何用户安装的 CA
- **THEN** 全部出站 HTTPS 行为与改动前完全一致（只信系统证书的公共端点照常可连，自签端点照常失败）

#### Scenario: 公共端点不受影响
- **WHEN** 连接 Let's Encrypt 等公共 CA 签发的正常端点
- **THEN** 证书校验照常通过，无额外提示或降级
