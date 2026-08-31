# ADR-0006：数据库外主密钥与 SecretStore

- 状态：Accepted
- 日期：2026-08-27

## 决策

Provider、MCP 与 Plugin 凭据统一通过 `SecretStore`。H2 只保存 AES-256-GCM 密文、nonce、
key revision 和 metadata；主密钥文件位于配置根，绝不与数据库同存。POSIX 权限必须是 0600，
Windows ACL 必须只包含当前 SID；不能证明 owner-only 时拒绝写入。环境变量只作进程内临时覆盖，
不写库、不进入事件、日志或诊断包。

## 结果

备份 H2 不等于获得明文密钥；密钥轮换可逐条重加密。客户端只能看到 configured、revision 与
updatedAt，协议没有读取明文方法。
