package io.nnnhook.core;

/**
 * 模块能做的全部 hook 能力，一条一个 API 点。
 *
 * 这份清单是"适配进度"的骨架，也是功能开关矩阵：适配时按顺序过一遍，
 * 每个点记 OK / MISS / DISABLED / FAIL；界面按这份清单一列一行。
 *
 * DISABLED 表示"当前目标版本找不到可信落点，主动不装"，不是"做不了"。
 * 理由写在 Anchor 对应位置，落点探到后把开关打开即可，不用改这里。
 *
 * 加新能力时在末尾追加，不要改已有 code（code 出现在日志与报告里）。
 */
public enum HookPoint {

    /** 建桥：DexKit 读目标 dex 并索引。所有后续点的前置。 */
    BRIDGE("bridge", "DexKit 建桥", "读取目标 dex 并建立索引"),

    /** TL 反序列化落点：TLRPC$User / UserFull 的 TLdeserialize、fromConstructor。 */
    PREMIUM_DESER("premium.deser", "TL 反序列化落点",
            "TLRPC$User.TLdeserialize / fromConstructor"),

    /** 会员解锁：把 TLRPC$User.premium 写成 true。 */
    PREMIUM_FIELD("premium.field", "会员解锁", "TLRPC$User.premium → true"),

    /** 频道/群组"会员专属"内容：放宽客户端对受限内容的拦截。 */
    PREMIUM_CONTENT("premium.content", "会员专属内容", "频道/群组受限内容放行"),

    /** 语音转文字：解除客户端对转写入口的会员限制。 */
    TRANSCRIBE("transcribe", "语音转文字", "TL_messages_transcribeAudio 入口"),

    /** 翻译：解除客户端对翻译入口的会员限制。 */
    TRANSLATE("translate", "翻译", "TL_messages_translateText 入口"),

    /** 聊天气泡：解除气泡样式选择器的会员限制。 */
    BUBBLES("bubbles", "聊天气泡", "气泡样式解锁"),

    /** 广告与赞助频道：清空 sponsored messages 响应。 */
    SPONSORED("sponsored", "广告/赞助隐藏", "TL_messages_sponsoredMessages.messages 清空"),

    /** 已读回执：不发出站已读上报。 */
    READ_RECEIPT("read_receipt", "已读回执", "TL_messages_readHistory 出站拦截"),

    /** 在线状态：不发出站在线上报。 */
    ONLINE_STATUS("online_status", "在线状态", "account.updateStatus 出站拦截"),

    /** 截图提示：不发截图通知。 */
    SCREENSHOT("screenshot", "截图提示", "TL_messages_sendScreenshotNotification 出站拦截"),

    /** 反删除/防撤回：保留被删消息。 */
    ANTI_DELETE("anti_delete", "防撤回", "入站 deleteMessages 更新拦截"),

    /** 无限置顶：放宽置顶数上限。 */
    PIN_LIMIT("pin_limit", "无限置顶", "客户端置顶上限"),

    /** 文件夹 / 分组上限：放宽数量上限。 */
    FOLDER_LIMIT("folder_limit", "文件夹/分组上限", "客户端 folder 上限");

    public final String code;
    public final String title;
    public final String desc;

    HookPoint(String code, String title, String desc) {
        this.code = code;
        this.title = title;
        this.desc = desc;
    }
}
