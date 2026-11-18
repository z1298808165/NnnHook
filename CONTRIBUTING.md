# 贡献指南

## 先读这个

NnnHook 是一个**适配层**，不是功能仓库。它的价值在于「目标换版本后还能自己找到落点」，
所以任何改动都要先回答一个问题：**这个改动会让锚点更稳，还是更脆？**

判断标准只有一条：**能不能在不看目标源码的前提下，用结构特征推导出来。**

## 改动前先定位层级

| 你要做的事 | 改哪里 |
|---|---|
| 目标发版后某个落点失效 | `core/Anchor.java`，只改常量，别动逻辑 |
| 新增一个功能 | `hook/` 加类 → `HookPoint` 加枚举 → `Hooks` 注册 → `Engine` 加解析 |
| 解析逻辑有问题 | `core/Engine.java` |
| 界面 / 报告 | `ui/` |
| 反射、日志等通用能力 | `util/` |

**不要跨层调用。** `core` 不许 import `hook`，`util` 不许 import 任何业务类。

## 锚点怎么写

优先级从高到低：

1. **TL 类名 / 字段名 / 生成方法名** —— 协议生成的，R8 动不了，直接写死
2. **字段结构特征** —— 比如「同时有 `dialogsAdapter` 和 `listView` 字段的 View」，
   这种比类名稳
3. **方法签名** —— 比如「`(int, int) -> void`」就是被改名的 `scrollToPositionWithOffset`
4. **字符串常量** —— 比如 `dialog_filters_limit_default`
5. **混淆名** —— 绝对不要写进代码，只能出现在注释里作为「实测记录」

## 提交前自检

```bash
# 1. 编译 + lint，必须 0 error
./gradlew :app:assembleDebug :app:lintDebug

# 2. 如果改了 Anchor，离线验证要全绿
python tools/offline_adapt.py tools/samples/nnngram-v13.2.1.apk

# 3. 装机看真机报告，必须 ok=14/14、fail=0
adb logcat -s NnnHook:V | grep -E "install done|适配报告"
```

三条都过再提 PR。只过了前两条的，在 PR 描述里注明「未做真机验证」。

## 提交信息

```
<type>: <一句话说明>

<可选：为什么这么改，踩了什么坑>
```

`type` 用 `feat` / `fix` / `refactor` / `docs` / `chore`。
改了 `Anchor.java` 的，在正文里写清楚「旧锚点为什么失效、新锚点怎么确认的」。

## 不要做的事

- 不要为了让某项 `✓` 而写死混淆名 —— 下个版本必炸，还会掩盖真实问题
- 不要在 `core` 里写具体功能的逻辑
- 不要提交 `tools/samples/*.apk`（几十 MB，`.gitignore` 已挡）
- 不要提交任何 keystore / 密码
- 不要在 logcat 里打印用户数据（会话 id、消息内容等）

## 关于「做不到」

有些功能在当前目标版本上做不成，比如需要 hook 但拿不到稳定落点的 UI 行为。
这种情况的处理方式：

1. 在 `Anchor.java` 里加注释，写清楚**试过什么、为什么不行**
2. 在 `HookPoint` 里标 `DISABLED`，让适配报告显示出来
3. 在 README 的「已知边界」里加一条

**不要留半成品代码。** 做不成的功能要么干净移除，要么明确标 DISABLED。
