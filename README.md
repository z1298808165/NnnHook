<h1 align="center">NnnHook</h1>

<p align="center">
  <img src="https://img.shields.io/github/v/release/z1298808165/NnnHook?label=release&color=blue" alt="Release" />
  <img src="https://img.shields.io/github/actions/workflow/status/z1298808165/NnnHook/ci.yml?label=CI" alt="CI" />
  <img src="https://img.shields.io/badge/Android-10%2B-3ddc84" alt="Android" />
  <img src="https://img.shields.io/badge/LSPosed-API%20102-6f42c1" alt="LSPosed" />
  <img src="https://img.shields.io/badge/license-MIT-lightgrey" alt="License" />
</p>

<p align="center">
  <b>解锁本地会员、置顶与文件夹上限、隐私开关</b><br />
  <sub>基于 DexKit 的锚点自推导 —— 理论上全版本通杀，换版本不需要改代码</sub>
</p>

* * *

NnnHook 是一个面向 **Nnngram**（`xyz.nextalone.nnngram`，Telegram 分支）的 LSPosed 模块（API 102）。

它做四件事：**解开本地会员权益**、**抬高置顶与文件夹上限**、**关掉已读 / 在线 / 截图 / 输入中
这些状态上报**、**隐藏频道里的赞助消息**。全部在客户端完成，不发额外请求、不改协议。

**适配不靠写死名字，靠推导。** 落点由 **DexKit** 在运行时从「TL 类名 + 字段名 + TL 生成方法名」
重新推导，结果缓存进目标私有目录 —— 只要目标还遵循 Telegram 协议，锚点就成立，
理论上**全版本通杀**，不需要为新版本单独改代码。

> **本仓库是个人维护的适配层。** 目标是闭源混淆包，没有可用的上游源码可比对，
> 落点全部靠静态结构 + 真机日志反推得出。因此每次目标发版后，
> 有一部分落点需要按 `Anchor.java` 里登记的实测方法重新对齐 —— 这不是 bug，是这个方案的固有成本。

* * *

## 功能

当前基线：目标 **v13.2.1-fb1b130 / code 1794670784**，三 dex 共 30.8 MB。
真机实测 **`ok=14/14`、19 个 hook、`fail=0`、无崩溃**。

| 能力 | 状态 | 落点 / 依据 |
|---|---|---|
| DexKit 建桥 | ✓ | `dex=3`，arm64 与 x86_64 均实测通过 |
| TL 反序列化落点 | ✓ | `TLRPC$User` / `TLRPC$UserFull` 的 `TLdeserialize` + `fromConstructor`，共 4 个 |
| **会员解锁** | ✓ | 双路径：4 个反序列化出口把 `TLRPC$User.premium` 置 true；2 个「读 premium 的无参 boolean」钉成 true |
| 受限内容解锁 | ✓ | `TLRPC$Chat` 的 3 个反序列化出口，清空 `restricted` / `restriction_reason` |
| 隐藏赞助消息 | ✓ | `TLRPC$messages_SponsoredMessages` 反序列化后清空结果列表 |
| 已读回执 | ✓ | `ConnectionsManager#sendRequest` 的 7 个重载中拦截 `messages_readHistory` |
| 在线状态 | ✓ | 同上，拦截 `account.updateStatus` |
| 截图通知 | ✓ | 同上，拦截 `messages.sendScreenshotNotification` |
| 输入中状态 | ✓ | 同上，拦截 `messages_setTyping` 系列 |
| 防撤回 | ✓ | `tl.TL_update$TL_updateDeleteMessages#readParams` |
| **置顶上限** | ✓ | `loadConfig` 里抹掉 `dialog_filters_limit_default` 的分支，特征值对齐 30 / 100 |
| **文件夹上限** | ✓ | 与置顶同一落点 |
| 版本自适应 | ✓ | 落点缓存按 `versionCode + 包名 + ANCHOR_REV` 三元组失效 |
| 适配报告 | ✓ | 打开目标时弹窗，逐项列出 `✓ / ×` 与耗时 |

三类「由会员状态连带」的项（语音转文字、动态封面、无限收藏）不做独立 hook —— 它们读的就是
`User.premium`，会员一解开就跟着开。

* * *

## 安装

1. 设备需 **Android 10+（API 29）**、已 root、已装 **LSPosed**（API 102 及以上）
2. 从 [Releases](../../releases) 下载 APK 安装
3. LSPosed 管理器里启用 **NnnHook**，作用域勾选 **Nnngram**
4. 强制停止 Nnngram 再打开 —— 首次启动会弹适配报告，逐项显示落点状态

> 首次适配要在目标 dex 上跑 DexKit 查询，约 **1~3 秒**；结果按版本缓存，
> 之后冷启动只读缓存。换版本会自动重算。

* * *

## 构建

环境要求：**JDK 21**（Gradle 8.9 起不来 JDK 25）、**compileSdk 36**、**build-tools 36.0.0**

```bash
# 调试包，产物在 app/build/outputs/apk/debug/
./gradlew :app:assembleDebug

# 正式包，没配签名密钥时用 debug 签名兜底（产物照样能装）
./gradlew :app:assembleRelease
```

配置正式签名（本地或 CI 都走同一套环境变量）：

```bash
export NNNHOOK_KEYSTORE=/path/to/release.jks
export NNNHOOK_KEYSTORE_PASSWORD=***
export NNNHOOK_KEY_ALIAS=***
export NNNHOOK_KEY_PASSWORD=***
./gradlew :app:assembleRelease
```

CI 里把 keystore 转 base64 存进 `KEYSTORE_BASE64`，配合 `KEYSTORE_PASSWORD` / `KEY_ALIAS` /
`KEY_PASSWORD` 三个 secret 即可。

### 仪器测试（不需要 LSPosed）

```bash
# 验证 DexKit 建桥、usingStrings、MethodData→Method 是否可用
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.nnnhook.DexKitProbeTest
```

`app/src/androidTest/` 下有 10 个探针用例，分别验证建桥、锚点命中量、字段 dump、
上限/隐私落点、广告模型等。它们跑在目标进程外，只读目标 APK 的 dex。

* * *

## 架构

按职责分四层，依赖方向单一 —— `ui` / `hook` 依赖 `core`，`core` 依赖 `util`，反向不成立。

```
io.nnnhook
├── Entry                      模块入口，只做生命周期与包名判定
├── core                       与「怎么找落点」有关，不含任何具体功能的 hook 逻辑
│   ├── Anchor                 全部锚点常量（TL 类名、字段名、方法名、特征值）
│   ├── Engine                 用 DexKit 把锚点解析成 Method / Field 列表
│   ├── Resolved               解析结果容器，一次查询的全部产物
│   ├── HookPoint              14 个 API 点的枚举，供报告与统计
│   ├── AdaptProgress          适配进度与耗时统计
│   ├── AdaptCache             落点缓存（按版本三元组失效）
│   └── DexSource              从目标 APK 取 dex 字节
├── hook                       每个功能一个类，只关心「拿到落点之后做什么」
│   ├── Hooks                  安装入口，按 HookPoint 顺序调度
│   ├── PremiumHook            会员判定 / 反序列化出口
│   ├── RestrictionHook        受限内容
│   ├── SponsoredHook          赞助消息
│   ├── RequestHook            已读 / 在线 / 截图 / 输入中
│   ├── AntiDeleteHook         防撤回
│   └── LimitHook              置顶 / 文件夹上限
├── ui
│   ├── InfoActivity           模块主界面（状态、手动重适配）
│   └── AdaptDialog            启动时的适配报告弹窗
└── util                       纯工具，无业务语义
    ├── L                      统一日志
    ├── FieldKit               反射读写字段（含混淆名兜底）
    └── TypeNames              类型名判断（数组 / 基本类型）
```

**改代码前先确认改哪一层**：

| 想做的事 | 改哪 |
|---|---|
| 目标发版后落点失效 | `core/Anchor.java`（只改常量） |
| 新增一个 hook 功能 | `hook/` 加类 + `HookPoint` 加枚举 + `Hooks` 里注册 |
| 解析逻辑本身有问题 | `core/Engine.java` |
| 界面 / 报告展示 | `ui/` |
| 反射、日志等通用能力 | `util/` |

* * *

## 换版本后怎么适配

**只改 `Anchor.java`。** 流程：

```bash
# 1. 先在 PC 上离线跑一遍，不用连真机
python tools/offline_adapt.py <新APK>

# 2. 看哪项掉成 ×，按 Anchor 注释里登记的实测方法补锚点

# 3. 重跑离线工具，确认回到全 ✓

# 4. 再装机，看 logcat 的 ok=14/14
adb logcat -s NnnHook:V | grep -E "install done|适配报告"
```

离线工具覆盖 8 项（建桥、4 个 TL 落点、发送层、防撤回、上限落点），
剩下 6 项依赖运行时字段值，仍需真机确认一次。

### 为什么落点是这么找的

| 目标里的东西 | 稳不稳 | 用什么当锚点 |
|---|---|---|
| TL 类名 `org.telegram.tgnet.TLRPC$User` | **稳** | 直接写死 |
| TL 字段名 `premium` / `unread_count` | **稳** | 直接写死 |
| TL 生成方法 `TLdeserialize` / `readParams` | **稳** | 直接写死 |
| 业务类名 `DialogsActivity` | 不稳 | 从「持有某字段」反查 |
| 业务方法名 `isPremium()` | 不稳 | 从「读某字段 + 签名」推导 |
| 业务字段名 `scrollSlidingTextTabStrip` | 半稳 | 字段名常保留，方法名常被换 |

R8 能换掉类名、方法名、dex 切分，但动不了 TL 层 —— 那是协议生成的，改名就和服务器对不上。
所以锚点全部建立在 TL 层，业务层只做「结构推导」。

* * *

## 离线适配验证

DexKit 是 Android native 库，PC 上跑不了。但它的查询本质都是 dex 结构查询，
用 androguard 能等价复现 —— 于是有了 `tools/offline_adapt.py`：

```bash
pip install androguard
python tools/offline_adapt.py tools/samples/nnngram-v13.2.1.apk
```

输出与真机 logcat 同一套格式：

```
── dex 3 个 / 方法 139479 个 ──

✓ [bridge] dex=3
✓ [premium.deser] 4 个: TLRPC$User#TLdeserialize + #fromConstructor + UserFull#...
✓ [premium.field] TLRPC$User 有 boolean premium（字段共 59 个）
✓ [premium.flag] 2 个: messenger.bj#在自由的旅行中… + ui.Components.引导团建之力…
✓ [premium.content] TLRPC$Chat 3 个落点
✓ [sponsored] 1 个落点
✓ [read_receipt/online/screenshot] sendRequest × 7 个重载
✓ [anti_delete] 1 个 readParams
── 离线可验证项 8/8 ──
```

### 跨构建验证

三个完全不同的构建，同一套锚点全中：

| | Nnngram v13.2.1 | Nagram v13.1.1 | Telegram 官方 |
|---|---|---|---|
| dex / 方法数 | 3 / 139,479 | 4 / 141,776 | 4 / 116,003 |
| 混淆强度 | 全混淆（方法名变中文长句） | **未混淆** | 部分 |
| premium.flag | `bj#在自由的旅行中…` | `UserConfig#isPremium` | `d20#q` |
| loadConfig | `s6#引导尼古丁之力` | `MessagesController#applyAppConfig` | `sp#q` |
| 结果 | 8/8 | 8/8 | 8/8 |

v13.1.1 没做混淆这一点特别有用：它直接印证了 v13.2.1 里的推断 ——
`bj#在自由的旅行中…` 就是 `UserConfig#isPremium`，`s6#引导尼古丁之力` 就是
`MessagesController#applyAppConfig`。遇到全混淆的版本，先找同 app 的早期未混淆版「对名字」，
比对着中文长句猜快得多。

* * *

## 项目结构

```
.
├── app/src/main/java/io/nnnhook/    源码（四层，见「架构」）
├── app/src/androidTest/             10 个探针用例，不需要 LSPosed
├── app/src/main/resources/META-INF/xposed/
│   ├── module.prop                  模块元信息
│   ├── scope.list                   作用域（xyz.nextalone.nnngram 等）
│   └── java_init.list               入口类声明
├── tools/offline_adapt.py           PC 端离线适配验证器
├── tools/samples/                   验证样本（APK 不入库，见 .gitignore）
└── .github/workflows/
    ├── ci.yml                       每次 push 构建 → ci-latest 预发布
    └── release.yml                  打 v* 标签 → 正式 Release
```

### 分支与发布

| 触发 | 结果 |
|---|---|
| push 到任意分支 | CI 构建 debug 包，覆盖到 `ci-latest` 预发布 |
| commit message 含 `[skip ci]` / `[ci skip]` | 不构建 |
| 打 `v*` 标签（如 `v1.0.0`） | 构建 release 包，创建正式 Release |

* * *

## 已知边界

- **理论上全版本通杀，实测只覆盖 Nnngram v13.2.1**。锚点全部建在 TL 层，只要目标还用
  Telegram 协议就能推导出来；但真实设备上只完整验证过这一个版本，其他发行版
  （Nekogram 等）尚未实测。
- **不做协议层改动**。会员解锁是改「客户端读到的值」，服务端该拦的还是会拦
  （比如付费表情的发送校验）。
- **上限是客户端上限**。抬高的是客户端校验与 UI 提示，服务端仍有真实限制。
- **依赖 DexKit 2.0.5**。目标 dex 结构若发生大改（比如拆包、加壳），建桥可能失败，
  此时适配报告会停在 `bridge` 一项。
- **正式包默认 debug 签名**（没配密钥时），这种包不能覆盖安装到已有正式签名的设备上。

* * *

## 贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。改动前先读「架构」那节的改哪一层对照表。

## 许可

[MIT](LICENSE)

## 致谢

- [DexKit](https://github.com/LuckyPray/DexKit) —— 运行时 dex 查询，整个适配层的基础
- [libxposed](https://github.com/libxposed/api) —— 模块 API
- [Nagram](https://github.com/NextAlone/Nagram) —— 目标应用的发行方
