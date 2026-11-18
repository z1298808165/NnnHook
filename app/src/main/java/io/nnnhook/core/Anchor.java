package io.nnnhook.core;

import io.nnnhook.ui.AdaptDialog;

import java.util.List;
import java.util.Locale;

/**
 * 全部锚点定义 + 每个 API 点的开关。换 Nnngram 版本时，主要改这个文件。
 *
 * 设计铁律：
 *   1. 优先用"混淆动不了"的东西：TL 类的类名、字段名、TL 生成方法名
 *   2. 字符串锚点只作为补充：泛词能活，带下划线的具体词实测多半是死的
 *   3. 宁可 DISABLED，也不装一个猜出来的落点
 *
 * 实测基线（目标 APK v13.2.1-fb1b130，code 1794670784，三 dex 共 30.8 MB）：
 *
 *   usingStrings 实测命中（方法体内真引用该字符串的）：
 *     is_premium            0      ← 死
 *     premium_subscription  0      ← 死
 *     premiumPromo          0      ← 死（字符串在表里，但没有方法引用）
 *     pinned_messages       0      ← 死
 *     pin_limit             0      ← 死
 *     folder_limit          0      ← 死
 *     messages.readHistory  0      ← 死
 *     readMentions          0      ← 死
 *     updateRead            0      ← 死
 *     premium              10 个方法，其中 (boolean,0参) 只有 onFragmentCreate（生命周期方法，钉 true 会崩）
 *     pinned               45 个，全是 void
 *
 *   类/字段层实测：
 *     TLRPC$User 存在，34 个 boolean 字段名全部保留，含 premium
 *     TLRPC$User#TLdeserialize(InputSerializedData,int,boolean) 存在（全包 680 个 TLdeserialize）
 *     TLRPC$User#fromConstructor(int) 存在
 *     (TLRPC$User)->boolean 全包 15 个，名字全被替换成中文长句，无法区分哪个是会员判定
 *     UserConfig / MessagesController 类名被完全混淆（匹配 0）
 */
public final class Anchor {

    /**
     * 锚点版本。**改任何锚点都必须 +1**，否则目标端的适配缓存不会失效，
     * 用户会继续用旧落点。缓存指纹里带了这一项。
     */
    public static final int ANCHOR_REV = 10;

    /**
     * 作用域。只列要适配的分支，官方 org.telegram.messenger 不在此列。
     */
    private static final String[] SCOPE = {
            "xyz.nextalone.nnngram",
            "xyz.nextalone.nagram",
            "tw.nekomimi.nekogram",
    };

    public static boolean supports(String pkg) {
        if (pkg == null) return false;
        for (String s : SCOPE) {
            if (s.equals(pkg)) return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════
    // 会员：字段级落点（当前唯一可信路径）
    // ══════════════════════════════════════════════════════════════

    /** TL 用户模型。类名实测保留原名。 */
    public static final String TLRPC_USER = "org.telegram.tgnet.TLRPC$User";

    /** TL 用户详情模型。会员相关的 UI 分支大量走它。 */
    public static final String TLRPC_USER_FULL = "org.telegram.tgnet.TLRPC$UserFull";

    /**
     * 反序列化入口方法名。这两个名字是 TL 生成器产出的，实测保留。
     *
     * 注意：它们不是字符串常量，所以**不能**用 usingStrings 找，
     * 必须用 declaredClass + name 精确匹配（早期版本这里是错的）。
     */
    public static final String[] USER_DESERIALIZERS = {
            "TLdeserialize",
            "fromConstructor",
    };

    /**
     * 会员字段名。实测 TLRPC$User 的 34 个 boolean 字段名全部保留原名，
     * premium 就在其中，可以直接反射置位。
     */
    public static final String[] PREMIUM_FIELDS = {
            "premium",
    };

    /**
     * 判定方法：**读取 premium 字段的无参 boolean 方法**。
     *
     * 实测这一版全包共 46 个方法读 premium 字段，其中无参 boolean 只有 2 个
     * （`org.telegram.messenger.bj#.../0` 与 UI 层一个同名形态）——
     * 前者就是 isPremium() 这类封装，UI 的会员分支走它，而不是直接读字段。
     * 只挂字段不改判定，界面上不会出现会员标识（已在真机验证过）。
     *
     * 这条路径精确到"读这个字段的无参 boolean"，误伤面极小。
     */
    public static final boolean ENABLE_SHAPE_PREMIUM = true;

    /** 判定方法读的那个字段名。 */
    public static final String PREMIUM_FIELD_READ = "premium";

    /**
     * 结构特征常量（判定方法用）。
     */
    public static final int PREMIUM_ARG_COUNT = 0;
    public static final String PREMIUM_RET_TYPE = "boolean";

    // ══════════════════════════════════════════════════════════════
    // 上限 / 隐私：当前版本无可靠锚点，默认关闭
    // ══════════════════════════════════════════════════════════════

    /** 上限类。四组字符串 + (int,0参) 交叉实测全部 0 命中，见类注释。 */
    public static final boolean ENABLE_LIMIT = false;

    /** 已读上报。readHistory/readMessages/markAsRead 全 0 命中，见类注释。 */
    public static final boolean ENABLE_PRIVACY = false;

    public static final String[] LIMIT_STRINGS = {
            "pinned_messages",
            "pin_limit",
            "pinned_dialogs",
            "folder_limit",
            "dialog_filters",
    };

    public static final String LIMIT_RET_TYPE = "int";

    private static final String[] LIMIT_HINTS = {
            "limit", "pinned", "pin", "folder", "filter", "max",
    };

    public static final String[] READ_STRINGS = {
            "messages.readHistory",
            "messages.readMentions",
            "updateRead",
            "readHistory",
    };

    public static final String READ_RET_TYPE = "void";

    private static final String[] READ_OPS = {
            "readhistory", "readmentions", "updateread",
    };

    /**
     * 泛词锚点。这类词在 UI 文案、序列化、日志里到处出现，
     * 命中它只能说明"沾边"，不足以支撑一个 hook 落点。
     */
    private static final String[] WEAK_ANCHORS = {
            "premium", "readHistory", "updateRead",
    };

    public static boolean isWeakAnchor(String anchor) {
        if (anchor == null) return true;
        for (String w : WEAK_ANCHORS) {
            if (w.equals(anchor)) return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════
    // 广告 / 赞助
    // ══════════════════════════════════════════════════════════════

    /**
     * 赞助消息响应模型。
     *
     * 实测结构：TL_messages_sponsoredMessages 只是 constructor 子类（只有
     * &lt;init&gt; / readParams / serializeToStream），真正的字段（ArrayList messages、
     * chats、users、between_delay）和 TLdeserialize 都在**父类** messages_SponsoredMessages 上。
     * 所以锚点必须用父类名，用子类名会 0 命中。
     */
    public static final String SPONSORED_MSGS =
            "org.telegram.tgnet.TLRPC$messages_SponsoredMessages";

    public static final boolean ENABLE_SPONSORED = true;

    // ══════════════════════════════════════════════════════════════
    // 发送层拦截（已读回执 / 在线状态 / 截图提示）
    // ══════════════════════════════════════════════════════════════
    //
    // 三个功能共用一个落点：ConnectionsManager#sendRequest(TLObject, RequestDelegate)。
    // 实测该类名保留、sendRequest 有 8 个重载、第一个参数都是 TLObject，
    // 所以按"第一个参数的类名"过滤就能精确丢掉目标请求，不用碰上层业务逻辑。

    public static final String CONNECTIONS_MANAGER = "org.telegram.tgnet.ConnectionsManager";
    public static final String SEND_REQUEST = "sendRequest";

    /** 发出去会被丢弃的请求类。注意目标里有两套命名空间（TLRPC$ 与 tl.TL_xxx$）。 */
    public static final String[] BLOCKED_REQUESTS = {
            // 已读回执
            "org.telegram.tgnet.TLRPC$TL_messages_readHistory",
            "org.telegram.tgnet.TLRPC$TL_channels_readHistory",
            // 在线状态
            "org.telegram.tgnet.tl.TL_account$updateStatus",
            // 截图提示
            "org.telegram.tgnet.TLRPC$TL_messages_sendScreenshotNotification",
    };

    public static final String REQ_READ = "org.telegram.tgnet.TLRPC$TL_messages_readHistory";
    public static final String REQ_READ_CHANNEL = "org.telegram.tgnet.TLRPC$TL_channels_readHistory";
    public static final String REQ_ONLINE = "org.telegram.tgnet.tl.TL_account$updateStatus";
    public static final String REQ_SCREENSHOT =
            "org.telegram.tgnet.TLRPC$TL_messages_sendScreenshotNotification";

    public static final boolean ENABLE_READ_RECEIPT = true;
    public static final boolean ENABLE_ONLINE_STATUS = true;
    public static final boolean ENABLE_SCREENSHOT = true;

    // ══════════════════════════════════════════════════════════════
    // 防撤回
    // ══════════════════════════════════════════════════════════════

    /**
     * 入站删除更新。实测在 `org.telegram.tgnet.tl` 命名空间下（不是 TLRPC$），
     * 把它的 messages(id 列表) 清空，后续处理就找不到要删的 id，消息留在界面上。
     */
    public static final String ANTI_DELETE_UPDATE =
            "org.telegram.tgnet.tl.TL_update$TL_updateDeleteMessages";

    public static final boolean ENABLE_ANTI_DELETE = true;

    // ══════════════════════════════════════════════════════════════
    // 上限（置顶 / 文件夹）
    // ══════════════════════════════════════════════════════════════

    /**
     * 服务端下发的上限字段名。TL_config 系列字段名保留原名，
     * 读这些字段的方法就是客户端做校验的地方，把它们的结果抬高即可。
     */
    /** 抬高后的值。服务端仍有真实上限，这里只影响客户端校验与 UI 提示。 */
    public static final int LIMIT_LARGE = 1000;
    /**
     * 文件夹上限。
     *
     * 链路实测：配置键 dialog_filters_limit_* → 持久化到
     * `/data/data/<pkg>/shared_prefs/mainconfig.xml` 的 `dialogFiltersLimitPremium`（实测 30）
     * → MessagesController（混淆名 s6）的 loadConfig 把它赋给某个混淆 int 字段 → UI 读字段。
     *
     * 改 XML 无效（启动被服务端 config 覆盖），改字段也无效（loadConfig 内部重赋值），
     * 唯一有效时机是 loadConfig **返回之后**再抬。
     *
     * 字段名是中文长句无法硬编码，按值匹配：只有值恰好等于 30 的 int 字段才被抬。
     * 换版本后先看 mainconfig.xml 里 dialogFiltersLimitPremium 的值，加进特征值数组。
     */

    /**
     * 上限特征值。取的是 `mainconfig.xml` 里这些键的实测值：
     *   dialogFiltersLimitPremium        = 30   → 文件夹上限
     *   savedDialogsPinnedLimitPremium   = 100  → 置顶上限（premium 档）
     *   maxFolderPinnedDialogsCountPremium = 100 → 文件夹内置顶上限
     * 值 100 在 s6 里对应十多个 int 字段，但它们全是服务端下发的上限项
     * （chatlistInvitesLimitPremium / quickRepliesLimit / savedDialogsPinnedLimit* 等），
     * 一起抬到 1000 属于同类增强，不会误伤到非上限逻辑。
     */
    public static final int[] LIMIT_FEATURE_VALUES = {30, 100};

    /** 文件夹上限：特征值 30 的那批字段。 */
    public static final boolean ENABLE_FOLDER_LIMIT = true;

    /** 置顶上限：特征值 100 的那批字段。 */
    public static final boolean ENABLE_PIN_LIMIT = true;

    /** 诊断开关：定位混淆字段用，定位完关掉。 */
    public static final boolean DIAG_LIMIT_FIELDS = false;

    // ══════════════════════════════════════════════════════════════
    // 其余功能开关
    // ══════════════════════════════════════════════════════════════
    //
    // 全部默认关闭，每一项后面写的是这一版实测到的事实，不是"没时间做"。
    // 落点补上后把对应开关置 true，代码路径已经就位。

    /**
     * 频道/群组会员专属标记。
     * 客户端这一半可做：清掉 restricted 标记与 restriction_reason，界面不再提示受限；
     * 内容本身是否下发由服务端账号策略决定，客户端拿不到就是拿不到。
     */
    public static final boolean ENABLE_PREMIUM_CONTENT = true;

    /**
     * 受限标记所在的 TL 模型。
     * TLdeserialize 声明在**父类** TLRPC$Chat 上、返回类型也是 TLRPC$Chat，
     * 反序列化出来才是 TL_channel / TL_chat 实例（restricted 在子类字段里）。
     * 所以这里必须用父类名，用子类名会 0 命中（已实测）。
     */
    public static final String[] RESTRICTED_MODELS = {
            "org.telegram.tgnet.TLRPC$Chat",
    };

    /** 语音转文字：转写结果由服务端产出；入口本身已随会员解锁放开（membership 判定已 hook）。 */
    public static final boolean ENABLE_TRANSCRIBE = false;

    /** 翻译：Nnngram 走自建翻译服务，会员限制由 premium 判定控制，已随会员解锁生效。 */
    public static final boolean ENABLE_TRANSLATE = false;

    /** 聊天气泡：Nnngram 自带气泡选择，纯资源层；随会员解锁放开。 */
    public static final boolean ENABLE_BUBBLES = false;

    // ══════════════════════════════════════════════════════════════
    // 适配反馈
    // ══════════════════════════════════════════════════════════════

    /**
     * 适配完成后在目标进程里弹一条 Toast 汇总。
     * 默认关：已经有 Activity 上的适配弹窗（AdaptDialog），Toast 只在需要
     * "界面之外也能看到"时打开。
     */
    public static final boolean SHOW_ADAPT_TOAST = false;

    // ══════════════════════════════════════════════════════════════
    // 判定工具
    // ══════════════════════════════════════════════════════════════

    public static boolean isReadOp(String methodName) {
        if (methodName == null) return false;
        String n = methodName.toLowerCase(Locale.ROOT);
        for (String s : READ_OPS) {
            if (n.equals(s)) return true;
        }
        return false;
    }

    public static boolean looksLikeLimit(String methodName, String className) {
        String s = (methodName == null ? "" : methodName)
                + ' ' + (className == null ? "" : className);
        String lower = s.toLowerCase(Locale.ROOT);
        for (String h : LIMIT_HINTS) {
            if (lower.contains(h)) return true;
        }
        return false;
    }

    public static boolean hasTlParam(List<String> paramTypes) {
        if (paramTypes == null) return false;
        for (String p : paramTypes) {
            if (p != null && p.contains("TLRPC")) return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════
    // 打分权重
    // ══════════════════════════════════════════════════════════════

    public static final class Score {
        public static final int RET_MATCH = 50;
        public static final int ARG_MATCH = 30;
        public static final int CLASS_HINT = 20;
        public static final int TL_DOMAIN = 25;
        public static final int MULTI_ANCHOR = 15;
        public static final int WEAK_ANCHOR = 5;

        private Score() {}
    }

    public static final class MinScore {
        /** 形状分 80 + 一个具体锚点 15 = 95。只命中泛词（85）进不来。 */
        public static final int PREMIUM = 95;

        private MinScore() {}
    }

    private Anchor() {}
}
