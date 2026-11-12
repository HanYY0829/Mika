## 1. 配置与守护测试

- [ ] 1.1 从最新 main 拉分支 `fix/361-user-ca-trust`（用 worktree，不动用户工作区的未提交改动）
- [ ] 1.2 `network_security_config.xml`：trust-anchors 加 `<certificates src="user"/>`，更新注释说明理由（与明文 http 先例同源）
- [ ] 1.3 `app/src/test` 加守护测试：解析该 XML，断言 trust-anchors 同时含 `system` 与 `user`、`cleartextTrafficPermitted` 仍为 true

## 2. 验证

- [ ] 2.1 `./gradlew :app:testDebugUnitTest` 全绿
- [ ] 2.2 真机验证：本机起自签 HTTPS 端点 → 未装 CA 时 App 内连它失败 → 装用户 CA（需解锁手机）后成功；截图/UI dump 取证存 `openspec/changes/fix-361-user-ca-trust/evidence/`
- [ ] 2.3 公共端点回归：正常服务商连接不受影响（连一次模型或内置浏览器开 https 页）

## 3. 收尾

- [ ] 3.1 中文提交、写 PR 描述（关联 #361，首屏放实测证据，写明安全权衡与 Non-Goals），等用户确认后推 fork 建 PR
