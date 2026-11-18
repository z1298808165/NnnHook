# 更新日志

版本号规则：`主版本.次版本.修订号`

- **主版本**：适配方案本身变了（比如锚点策略重写）
- **次版本**：新增功能点
- **修订号**：修 bug、补锚点、只调参

## [未发布]

## [1.0.0] - 2026-11-18

首个可用版本。

### 功能

- **会员解锁**：双路径（反序列化出口置位 + 判定方法钉 true）
- **受限内容解锁**：`TLRPC$Chat` 三个反序列化出口清空 `restricted`
- **隐藏赞助消息**：清空 `messages_SponsoredMessages` 结果列表
- **已读回执 / 在线状态 / 截图通知 / 输入中**：统一在 `ConnectionsManager#sendRequest` 拦截
- **防撤回**：拦截 `TL_updateDeleteMessages#readParams`
- **置顶 / 文件夹上限**：抹掉 `loadConfig` 里的上限分支

### 工程

- 运行时可适配：落点由 DexKit 从 TL 层锚点推导，按版本三元组缓存
- 适配报告：启动弹窗逐项显示 `✓ / ×` 与耗时
- 离线验证器 `tools/offline_adapt.py`：PC 上直接验证 8 项落点，不用连真机
- CI：push 自动构建并发布到 `ci-latest` 预发布，支持 `[skip ci]`
- Release：打 `v*` 标签发布正式版

### 已知问题

- 只在 Nnngram v13.2.1 上完成全量实测
- 正式包在未配置签名时使用 debug 签名

### 移除

- 「双击分组标签跳到未读」功能尝试后移除：滚动 API 已打通
  （`layoutManager` 上被 R8 改名的 `scrollToPositionWithOffset`），
  但拿不到真实 adapter position，无法精确定位。详见 `Anchor.java` 注释。
