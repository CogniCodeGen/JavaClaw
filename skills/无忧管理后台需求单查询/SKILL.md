---
name: 无忧管理后台需求单查询
description: 登录无忧管理后台（fat环境），导航到需求单管理页面，查询并逐页提取所有需求单数据。适用于需要从无忧管理后台导出需求单列表的场景。
enabled: 'true'
version: 1.0.0
source: agent
user-modified: 'false'
category: 浏览器
tags:
- 无忧管理后台
- 需求单
- 数据查询
- 浏览器自动化
---

# 无忧管理后台需求单查询

## 适用场景
需要登录无忧管理后台（fat 测试环境），查询并提取需求单管理页面中的全部需求单数据时使用。

## 操作步骤

### 第一步：登录系统
1. 用 `web_navigate` 打开 `https://wy-admin-fat.meilisci.com/`。系统会先尝试恢复配置数据库中已加密保存的站点会话。
2. 用 `web_snapshot` 检查页面。若仍在登录页，确保选中“账户密码登录”标签。
3. 页面下方“登录类型”默认可能是“需求方登录”，这会导致“当前客户未成为需求方”。点击下拉框，再用 `web_snapshot(interactive_only=false)` 定位 portal 中的选项并选择 **“采购方登录”**。
4. 调用 `site_login_interactive`，由用户在本地浏览器窗口中输入账号、密码、验证码或完成双因素认证。不得要求用户把密码发到对话中，也不得从项目文件读取密码。
5. 用户确认登录完成后，按提示选择是否把 Cookie 与站点存储加密保存到项目配置数据库。只有工具明确返回保存成功，才能告知用户“下次会自动登录”。

### 第二步：导航到需求单管理页面
1. 点击左侧导航菜单"需求管理"，展开子菜单
2. 点击子菜单"需求单管理"
3. 验证 URL 是否为 `https://wy-admin-fat.meilisci.com/indirect_purchasing/demand/list`

### 第三步：加载全部数据
1. 点击"重置"按钮，清空所有查询条件
2. 使用 `web_wait_for_load` 等待 `networkidle` 状态
3. 查看页面底部分页信息，确认总条数、每页条数、总页数

### 第四步：逐页提取数据
1. 先用 `web_snapshot(interactive_only=false)` 确认数据表格，再用 `web_get_count` 统计该表格 `tbody tr` 的行数。不要依赖脚本执行能力。
2. 对每一行使用 `web_get_text` 读取行文本；需要列级结构时，再按该行下的 `td` 数量逐格读取。表格定位经页面结构确认后可使用 Playwright 的 `table >> nth=2` 定位第三个表格，不得在未验证页面结构时盲用固定序号。
3. 翻页必须使用 `web_click` 点击快照中的真实页码或“下一页”元素，不能使用 JavaScript `click()`。
4. 每次翻页后使用 `web_wait_for_load` 等待 `networkidle`，并核对首行或当前页码已经变化，再提取数据，避免重复上一页。
5. 重复直到“下一页”不可用；最后校验累计行数与页面显示的总条数一致。若不一致，明确报告缺失页码，不得声称已完整导出。

## 注意事项

1. **登录类型**：必须选择"采购方登录"，默认的"需求方登录"会报错"当前客户未成为需求方"
2. **Ant Design 下拉框**：选项在 portal/overlay 中渲染，interactive_only 快照不可见，需用完整快照
3. **翻页方式**：必须用 `web_click` 工具点击页码，不能用 JS `click()`，否则数据不刷新
4. **表格定位**：数据表格是页面第 3 个 `table` 元素（`index=2`），不是第 1 个
5. **页面跳转验证**：登录后 URL 从 `/login?callBackUrl=...` 跳转为 `https://wy-admin-fat.meilisci.com/`，可能需要短暂等待
6. **凭据边界**：密码、Cookie、令牌和验证码只允许经本地安全输入与加密配置数据库处理；不得写入 SKILL.md、references、日志、聊天记录或工具结果

## 验证方法

- **登录成功**：URL 从 `/login?callBackUrl=...` 跳转到 `https://wy-admin-fat.meilisci.com/`，页面显示左侧导航菜单
- **页面正确**：URL 为 `/indirect_purchasing/demand/list`
- **数据完整**：提取的行数 = 每页条数（最后一页除外），总页数 × 每页条数 ≈ 总条数
