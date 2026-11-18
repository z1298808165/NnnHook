package io.nnnhook.hook;

import io.nnnhook.core.AdaptProgress;
import io.nnnhook.core.Anchor;
import io.nnnhook.core.Engine;
import io.nnnhook.core.HookPoint;
import io.nnnhook.core.Resolved;
import io.nnnhook.util.L;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

/**
 * hook 安装总控。
 *
 * 纪律：
 *   1. 每条 hook 独立 try，单点失败不扩散，更不把异常抛回宿主
 *   2. 全组共用一份 Method 去重表，同一方法只挂一次
 *   3. 每装完一组就把结果写进 AdaptProgress（后写覆盖 Engine 的初判）
 *   4. 开关关闭的点不安装，也不在这里改状态（Engine 已标 DISABLED）
 */
public final class Hooks {

    private static final String TAG = "NnnHook.Hooks";

    private Hooks() {}

    public static void install(XposedModule module, Resolved r, AdaptProgress pg) {
        int total = 0;
        int fail = 0;
        Set<Method> seen = new HashSet<>();

        // ── 会员：反序列化出口改字段 + 判定方法钉 true ──────────
        pg.begin(HookPoint.PREMIUM_FIELD);
        if (!r.premiumFieldPresent && !r.fromCache) {
            pg.fail(HookPoint.PREMIUM_FIELD, "TLRPC$User 上没有 premium 字段");
        } else {
            AtomicInteger hits = new AtomicInteger();
            try {
                List<Method> exits = r.premiumMethods();
                List<Method> flags = r.premiumFlagMethods();
                int n = PremiumHook.install(module, exits, flags, seen, hits);
                total += n;
                if (n > 0) {
                    pg.ok(HookPoint.PREMIUM_FIELD,
                            exits.size() + " 个出口 + " + flags.size() + " 个判定"
                                    + (r.fromCache ? "（缓存）" : ""));
                } else {
                    pg.miss(HookPoint.PREMIUM_FIELD, "没有可挂的落点");
                }
            } catch (Throwable t) {
                fail++;
                pg.fail(HookPoint.PREMIUM_FIELD, String.valueOf(t));
                L.e(TAG, "PremiumHook 安装失败", t);
            }
        }

        // ── 广告 / 赞助 ────────────────────────────────────────
        if (Anchor.ENABLE_SPONSORED) {
            pg.begin(HookPoint.SPONSORED);
            try {
                AtomicInteger cleared = new AtomicInteger();
                int n = SponsoredHook.install(module, r.sponsoredMethods(), seen, cleared);
                total += n;
                if (n > 0) {
                    pg.ok(HookPoint.SPONSORED, n + " 个落点" + (r.fromCache ? "（缓存）" : ""));
                } else {
                    pg.miss(HookPoint.SPONSORED, "没有可挂的落点");
                }
            } catch (Throwable t) {
                fail++;
                pg.fail(HookPoint.SPONSORED, String.valueOf(t));
                L.e(TAG, "SponsoredHook 安装失败", t);
            }
        }

        // ── 发送层：已读 / 在线 / 截图 共用 sendRequest ─────────
        List<Method> senders = r.sendRequestList();
        if (!senders.isEmpty()) {
            pg.begin(HookPoint.READ_RECEIPT);
            try {
                AtomicInteger blocked = new AtomicInteger();
                int n = RequestHook.install(module, senders, seen, blocked);
                total += n;
                if (n > 0) {
                    String detail = n + " 个 sendRequest 重载 · 拦 "
                            + Anchor.BLOCKED_REQUESTS.length + " 类请求"
                            + (r.fromCache ? "（缓存）" : "");
                    pg.ok(HookPoint.READ_RECEIPT, detail);
                    pg.ok(HookPoint.ONLINE_STATUS, detail);
                    pg.ok(HookPoint.SCREENSHOT, detail);
                } else {
                    pg.fail(HookPoint.READ_RECEIPT, "sendRequest 安装失败");
                    pg.fail(HookPoint.ONLINE_STATUS, "sendRequest 安装失败");
                    pg.fail(HookPoint.SCREENSHOT, "sendRequest 安装失败");
                }
            } catch (Throwable t) {
                fail++;
                pg.fail(HookPoint.READ_RECEIPT, String.valueOf(t));
                L.e(TAG, "RequestHook 安装失败", t);
            }
        }

        // ── 防撤回 ─────────────────────────────────────────────
        List<Method> antiDel = r.antiDeleteList();
        if (!antiDel.isEmpty()) {
            pg.begin(HookPoint.ANTI_DELETE);
            try {
                AtomicInteger blocked = new AtomicInteger();
                int n = AntiDeleteHook.install(module, antiDel, seen, blocked);
                total += n;
                if (n > 0) {
                    pg.ok(HookPoint.ANTI_DELETE,
                            n + " 个 readParams" + (r.fromCache ? "（缓存）" : ""));
                } else {
                    pg.miss(HookPoint.ANTI_DELETE, "安装失败");
                }
            } catch (Throwable t) {
                fail++;
                pg.fail(HookPoint.ANTI_DELETE, String.valueOf(t));
                L.e(TAG, "AntiDeleteHook 安装失败", t);
            }
        }

        // ── 频道/群组受限标记 ──────────────────────────────────
        if (Anchor.ENABLE_PREMIUM_CONTENT) {
            List<Method> rs = r.restrictedList();
            if (!rs.isEmpty()) {
                pg.begin(HookPoint.PREMIUM_CONTENT);
                try {
                    AtomicInteger cleaned = new AtomicInteger();
                    int n = RestrictionHook.install(module, rs, seen, cleaned);
                    total += n;
                    if (n > 0) {
                        pg.ok(HookPoint.PREMIUM_CONTENT,
                                n + " 个 TL 模型 · 清受限标记" + (r.fromCache ? "（缓存）" : ""));
                    } else {
                        pg.miss(HookPoint.PREMIUM_CONTENT, "安装失败");
                    }
                } catch (Throwable t) {
                    fail++;
                    pg.fail(HookPoint.PREMIUM_CONTENT, String.valueOf(t));
                    L.e(TAG, "RestrictionHook 安装失败", t);
                }
            }
        }

        // ── 上限：文件夹 + 置顶（同一个 loadConfig 落点）──────────
        if (Anchor.ENABLE_FOLDER_LIMIT || Anchor.ENABLE_PIN_LIMIT) {
            List<Method> loaders = r.limitLoaders();
            if (!loaders.isEmpty()) {
                pg.begin(HookPoint.FOLDER_LIMIT);
                pg.begin(HookPoint.PIN_LIMIT);
                try {
                    AtomicInteger lifted = new AtomicInteger();
                    int n = LimitHook.install(module, loaders, seen,
                            Anchor.LIMIT_FEATURE_VALUES, lifted);
                    total += n;
                    if (n > 0) {
                        String detail = "loadConfig 后抬升 · 特征值 "
                                + java.util.Arrays.toString(Anchor.LIMIT_FEATURE_VALUES)
                                + " → " + Anchor.LIMIT_LARGE;
                        if (Anchor.ENABLE_FOLDER_LIMIT) pg.ok(HookPoint.FOLDER_LIMIT, detail);
                        if (Anchor.ENABLE_PIN_LIMIT) pg.ok(HookPoint.PIN_LIMIT, detail);
                    } else {
                        if (Anchor.ENABLE_FOLDER_LIMIT) pg.miss(HookPoint.FOLDER_LIMIT, "安装失败");
                        if (Anchor.ENABLE_PIN_LIMIT) pg.miss(HookPoint.PIN_LIMIT, "安装失败");
                    }
                } catch (Throwable t) {
                    fail++;
                    pg.fail(HookPoint.FOLDER_LIMIT, String.valueOf(t));
                    pg.fail(HookPoint.PIN_LIMIT, String.valueOf(t));
                    L.e(TAG, "LimitHook 安装失败", t);
                }
            }
        }

        L.i(TAG, "install done: total=" + total + " fail=" + fail);
    }

}
