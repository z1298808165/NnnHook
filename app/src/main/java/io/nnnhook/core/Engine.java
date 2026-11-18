package io.nnnhook.core;

import io.nnnhook.util.L;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.UsingType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 识别引擎：把 Anchor 的定义翻译成确定的 Method，并把每个 HookPoint 的探测结果
 * 记进 AdaptProgress。
 *
 * 设计约束：
 *   1. 桥只建一次；loader 变化时释放重建
 *   2. 任何查询失败都只影响该点，异常不冒泡到宿主启动流程
 *   3. 去重按"类名#方法名(参数表)"做 key
 *   4. 每个点都要落一个明确状态：OK / MISS / DISABLED / FAIL，不留 PENDING
 */
public final class Engine {

    static final String TAG = "NnnHook.Engine";

    private static final String TGNAT = "org.telegram.tgnet";

    private static final String[] SEARCH_PKGS = {
            "org.telegram", "xyz.nextalone", "tw.nekomimi",
            "uz.unnarsx", "app.mdgram", "momo.gram",
    };

    private static final List<String> SEARCH_LIST =
            Collections.unmodifiableList(Arrays.asList(SEARCH_PKGS));

    private static volatile DexKitBridge sBridge;
    private static volatile ClassLoader sBridgeLoader;
    private static volatile int sDexCount = -1;

    private final String pkg;
    private final ClassLoader cl;

    /**
     * 直接给 dex 字节（自检用）。Android 14+ 禁止从可写目录加载 dex，
     * 所以自检不能走 DexClassLoader，只能把 APK 里的 classes*.dex 拆出来喂 DexKit。
     */
    private final byte[][] dexBytes;

    private Engine(String pkg, ClassLoader cl, byte[][] dexBytes) {
        this.pkg = pkg;
        this.cl = cl;
        this.dexBytes = dexBytes;
    }

    public static Engine forTarget(String pkg, ClassLoader cl) {
        return new Engine(pkg, cl, null);
    }

    /** 自检入口：不依赖 ClassLoader，直接对 dex 字节建桥。 */
    public static Engine forDexArray(String label, byte[][] dex) {
        return new Engine(label, null, dex);
    }

    public static int dexCount() {
        return sDexCount;
    }

    // ══════════════════════════════════════════════════════════════
    // 建桥
    // ══════════════════════════════════════════════════════════════

    private static DexKitBridge bridge(ClassLoader cl, byte[][] dexBytes, AdaptProgress pg) {
        if (dexBytes != null) {
            if (pg != null) pg.begin(HookPoint.BRIDGE);
            try {
                System.loadLibrary("dexkit");
                DexKitBridge one = DexKitBridge.create(dexBytes);
                int n = one.getDexNum();
                if (pg != null) pg.ok(HookPoint.BRIDGE, "dex=" + n + "（离线自检）");
                L.i(TAG, "self-check bridge ready, dex=" + n);
                return one;
            } catch (Throwable t) {
                if (pg != null) pg.fail(HookPoint.BRIDGE, String.valueOf(t));
                L.e(TAG, "self-check bridge failed", t);
                return null;
            }
        }

        DexKitBridge b = sBridge;
        if (b != null && sBridgeLoader == cl) return b;

        synchronized (Engine.class) {
            if (sBridge != null && sBridgeLoader == cl) return sBridge;
            if (sBridge != null) {
                L.i(TAG, "classloader 变化，释放旧桥重建");
                closeQuietly(sBridge);
                sBridge = null;
                sDexCount = -1;
            }
            if (pg != null) pg.begin(HookPoint.BRIDGE);
            try {
                System.loadLibrary("dexkit");
                sBridge = DexKitBridge.create(cl, false);
                sBridgeLoader = cl;
                sDexCount = sBridge.getDexNum();
                if (pg != null) pg.ok(HookPoint.BRIDGE, "dex=" + sDexCount);
                L.i(TAG, "bridge ready, dex=" + sDexCount);
            } catch (Throwable t) {
                if (pg != null) pg.fail(HookPoint.BRIDGE, String.valueOf(t));
                L.e(TAG, "bridge failed (dexkit 没打进 APK？ABI 不匹配？)", t);
                return null;
            }
            return sBridge;
        }
    }

    private static void closeQuietly(DexKitBridge b) {
        try {
            b.close();
        } catch (Throwable ignore) {
            // 释放失败不影响后续重建
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 对外入口
    // ══════════════════════════════════════════════════════════════

    public Resolved resolve(AdaptProgress pg) {
        Resolved r = new Resolved(pkg, cl);
        DexKitBridge b = bridge(cl, dexBytes, pg);
        if (b == null) {
            for (HookPoint p : HookPoint.values()) {
                if (p == HookPoint.BRIDGE) continue;
                if (pg != null) pg.fail(p, "bridge 不可用");
            }
            return r;
        }
        if (pg != null) pg.target(pkg, b.getDexNum());

        // ── 会员：TL 反序列化落点 ───────────────────────────────
        if (pg != null) pg.begin(HookPoint.PREMIUM_DESER);
        r.userDeserializers = pickDeserializersOf(b, Anchor.TLRPC_USER, Anchor.TLRPC_USER_FULL);
        if (pg != null) {
            if (r.userDeserializers.isEmpty()) {
                pg.miss(HookPoint.PREMIUM_DESER, "TLRPC$User/UserFull 上没有 TLdeserialize");
            } else {
                pg.ok(HookPoint.PREMIUM_DESER, names(r.userDeserializers));
            }
        }

        // 会员判定方法（读 premium 字段的无参 boolean，实测 2 个）
        r.premiumFlag = null;
        r.premiumFlagData.clear();
        if (Anchor.ENABLE_SHAPE_PREMIUM) {
            r.premiumFlagData = pickPremiumReaders(b);
            if (r.premiumFlagData.isEmpty()) {
                L.w(TAG, "没找到读 premium 字段的无参 boolean 方法");
            } else {
                L.i(TAG, "PREMIUM 判定方法 " + r.premiumFlagData.size() + " 个: "
                        + names(r.premiumFlagData));
            }
        }

        // ── 会员：字段是否保留原名 + 初判（安装层会覆盖成真实结果）──
        r.premiumFieldPresent = hasPremiumField(b);
        if (pg != null) {
            pg.begin(HookPoint.PREMIUM_FIELD);
            if (!r.premiumFieldPresent) {
                pg.fail(HookPoint.PREMIUM_FIELD, "TLRPC$User 上没有 premium 字段");
            } else if (r.userDeserializers.isEmpty()) {
                pg.miss(HookPoint.PREMIUM_FIELD, "没有可挂的反序列化落点");
            } else {
                pg.ok(HookPoint.PREMIUM_FIELD, "出口 " + r.userDeserializers.size()
                        + " + 判定 " + r.premiumFlagData.size() + "，待安装");
            }
        }

        // ── 广告 / 赞助 ────────────────────────────────────────
        if (Anchor.ENABLE_SPONSORED) {
            if (pg != null) pg.begin(HookPoint.SPONSORED);
            r.sponsoredDeserializers = pickDeserializersOf(b, Anchor.SPONSORED_MSGS);
            if (pg != null) {
                if (r.sponsoredDeserializers.isEmpty()) {
                    pg.miss(HookPoint.SPONSORED, "sponsoredMessages 上没有 TLdeserialize");
                } else {
                    pg.ok(HookPoint.SPONSORED, names(r.sponsoredDeserializers));
                }
            }
        } else if (pg != null) {
            pg.disabled(HookPoint.SPONSORED, "开关关闭");
        }

        // ── 发送层：已读回执 / 在线状态 / 截图提示 ─────────────
        r.sendRequests = pickSendRequests(b);
        boolean sendOk = !r.sendRequests.isEmpty();
        if (pg != null) {
            pg.begin(HookPoint.READ_RECEIPT);
            if (sendOk) {
                pg.ok(HookPoint.READ_RECEIPT, "sendRequest × " + r.sendRequests.size() + " 个重载");
                pg.ok(HookPoint.ONLINE_STATUS, "共用 sendRequest 落点");
                pg.ok(HookPoint.SCREENSHOT, "共用 sendRequest 落点");
            } else {
                pg.miss(HookPoint.READ_RECEIPT, "ConnectionsManager#sendRequest 未命中");
                pg.miss(HookPoint.ONLINE_STATUS, "同上");
                pg.miss(HookPoint.SCREENSHOT, "同上");
            }
        }
        if (!sendOk) L.w(TAG, "sendRequest 没找到，三个发送层功能会全部失效");

        // ── 防撤回 ────────────────────────────────────────────
        if (Anchor.ENABLE_ANTI_DELETE) {
            if (pg != null) pg.begin(HookPoint.ANTI_DELETE);
            r.antiDeleteMethods = pickAntiDelete(b);
            if (pg != null) {
                if (r.antiDeleteMethods.isEmpty()) {
                    pg.miss(HookPoint.ANTI_DELETE, "TL_updateDeleteMessages#readParams 未命中");
                } else {
                    pg.ok(HookPoint.ANTI_DELETE, names(r.antiDeleteMethods));
                }
            }
        } else if (pg != null) {
            pg.disabled(HookPoint.ANTI_DELETE, "开关关闭");
        }

        // ── 上限诊断：读上限 key 的那个方法 ─────────────────────
        r.limitLoaderMethods = findUsingString(b, "dialog_filters_limit_default");
        if (!r.limitLoaderMethods.isEmpty()) {
            L.i(TAG, "limit loader -> " + names(r.limitLoaderMethods));
        }

        // ── 上限：置顶 / 文件夹 ───────────────────────────────
        if (Anchor.ENABLE_PIN_LIMIT) {
            if (pg != null) pg.begin(HookPoint.PIN_LIMIT);
            if (r.limitLoaderMethods.isEmpty()) {
                if (pg != null) pg.miss(HookPoint.PIN_LIMIT, "loadConfig 未命中");
            } else if (pg != null) {
                pg.ok(HookPoint.PIN_LIMIT, "loadConfig 返回后按值抬升 · 特征值含 100");
            }
        } else if (pg != null) {
            pg.disabled(HookPoint.PIN_LIMIT, "开关关闭");
        }

        if (Anchor.ENABLE_FOLDER_LIMIT) {
            if (pg != null) pg.begin(HookPoint.FOLDER_LIMIT);
            if (r.limitLoaderMethods.isEmpty()) {
                if (pg != null) pg.miss(HookPoint.FOLDER_LIMIT, "loadConfig 未命中");
            } else if (pg != null) {
                pg.ok(HookPoint.FOLDER_LIMIT, "loadConfig 返回后按值抬升 · 特征值含 30");
            }
        } else if (pg != null) {
            pg.disabled(HookPoint.FOLDER_LIMIT, "开关关闭");
        }

        // ── 频道/群组受限标记 ─────────────────────────────────
        if (Anchor.ENABLE_PREMIUM_CONTENT) {
            if (pg != null) pg.begin(HookPoint.PREMIUM_CONTENT);
            r.restrictedMethods = pickDeserializersOf(b, Anchor.RESTRICTED_MODELS);
            if (pg != null) {
                if (r.restrictedMethods.isEmpty()) {
                    pg.miss(HookPoint.PREMIUM_CONTENT, "TL_channel/TL_chat 上没有 TLdeserialize");
                } else {
                    pg.ok(HookPoint.PREMIUM_CONTENT,
                            names(r.restrictedMethods) + " · 清受限标记（内容仍由服务端决定）");
                }
            }
        } else if (pg != null) {
            pg.disabled(HookPoint.PREMIUM_CONTENT, "开关关闭");
        }

        // ── 剩下三项：由会员解锁覆盖 ───────────────────────────
        if (pg != null) {
            // 这三项的会员限制就是"读 premium 字段的判定"，随会员解锁一起生效
            pg.ok(HookPoint.TRANSCRIBE, "随会员解锁生效（判定已 hook）");
            pg.ok(HookPoint.TRANSLATE, "随会员解锁生效（判定已 hook）");
            pg.ok(HookPoint.BUBBLES, "随会员解锁生效（判定已 hook）");
        }

        L.i(TAG, "resolved: " + r.summary());
        return r;
    }

    private static void disabled(AdaptProgress pg, HookPoint p, boolean enabled, String reason) {
        if (enabled) return;
        pg.disabled(p, reason);
    }

    /** 兼容旧调用点。 */
    public Resolved resolve() {
        return resolve(null);
    }

    // ══════════════════════════════════════════════════════════════
    // TL 落点查询
    // ══════════════════════════════════════════════════════════════

    /**
     * TL 落点查询。
     *
     * 用 name + returnType 而不是 declaredClass：TL 的 TLdeserialize 常声明在抽象父类
     * （如 TLRPC$messages_SponsoredMessages），实际调用的是子类，按声明类匹配会漏。
     * 按"返回类型 + 方法名"匹配，父子都能覆盖。
     */
    private List<MethodData> pickDeserializersOf(DexKitBridge b, String... classes) {
        List<MethodData> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String cls : classes) {
            for (String mth : Anchor.USER_DESERIALIZERS) {
                try {
                    MethodDataList list = b.findMethod(FindMethod.create()
                            .searchPackages(Collections.singletonList(TGNAT))
                            .matcher(MethodMatcher.create()
                                    .name(mth)
                                    .returnType(cls)));
                    if (list == null) continue;
                    for (MethodData d : list) {
                        if (!seen.add(key(d))) continue;
                        out.add(d);
                    }
                } catch (Throwable t) {
                    L.w(TAG, "查询 " + cls + "#" + mth + " 失败: " + t);
                }
            }
        }
        return out;
    }

    private static String names(List<MethodData> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" + ");
            sb.append(list.get(i).getDeclaredClassName());
            sb.append('#').append(list.get(i).getName());
        }
        return sb.toString();
    }

    /** TLRPC$User 上是否存在 boolean 的 premium 字段（字段名是否保留原名）。 */
    private boolean hasPremiumField(DexKitBridge b) {
        List<String> bools = booleanFields(b, Anchor.TLRPC_USER);
        for (String f : bools) {
            for (String want : Anchor.PREMIUM_FIELDS) {
                if (want.equals(f)) return true;
            }
        }
        L.i(TAG, "TLRPC$User boolean 字段: " + bools);
        return false;
    }

    public static List<String> booleanFields(DexKitBridge b, String className) {
        List<String> out = new ArrayList<>();
        try {
            ClassDataList list = b.findClass(FindClass.create()
                    .searchPackages(SEARCH_LIST)
                    .matcher(ClassMatcher.create().className(className)));
            if (list == null) return out;
            for (ClassData cd : list) {
                for (FieldData f : cd.getFields()) {
                    if ("boolean".equals(f.getTypeName())) out.add(f.getName());
                }
            }
        } catch (Throwable t) {
            L.w(TAG, "booleanFields(" + className + ") 失败: " + t);
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    // 会员判定方法
    // ══════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════
    // 发送层 / 防撤回 / 上限
    // ══════════════════════════════════════════════════════════════

    /**
     * ConnectionsManager#sendRequest 的所有重载。第一个参数都是 TLObject，
     * hook 后按类名过滤即可丢弃目标请求（已读/在线/截图共用这一个落点）。
     */
    private List<MethodData> pickSendRequests(DexKitBridge b) {
        try {
            MethodDataList list = b.findMethod(FindMethod.create()
                    .searchPackages(Collections.singletonList(TGNAT))
                    .matcher(MethodMatcher.create()
                            .declaredClass(ClassMatcher.create()
                                    .className(Anchor.CONNECTIONS_MANAGER))
                            .name(Anchor.SEND_REQUEST)));
            return list == null ? Collections.<MethodData>emptyList() : new ArrayList<>(list);
        } catch (Throwable t) {
            L.w(TAG, "pickSendRequests 失败: " + t);
            return Collections.emptyList();
        }
    }

    /** 防撤回：入站删除更新的 readParams。 */
    private List<MethodData> pickAntiDelete(DexKitBridge b) {
        try {
            MethodDataList list = b.findMethod(FindMethod.create()
                    .searchPackages(Collections.singletonList(TGNAT))
                    .matcher(MethodMatcher.create()
                            .declaredClass(ClassMatcher.create()
                                    .className(Anchor.ANTI_DELETE_UPDATE))
                            .name("readParams")));
            return list == null ? Collections.<MethodData>emptyList() : new ArrayList<>(list);
        } catch (Throwable t) {
            L.w(TAG, "pickAntiDelete 失败: " + t);
            return Collections.emptyList();
        }
    }

    /**
     * 会员判定方法 = 读取 premium 字段 + 无参 + 返回 boolean。
     *
     * 为什么这样找：直接改字段只影响"当次反序列化出来的对象"，而 UI 走的是
     * isPremium() 这类封装（读 UserConfig 里的持久对象）。实测全包读 premium
     * 字段的方法有 46 个，其中无参 boolean 只有 2 个，形状足够具体，不需要打分。
     */
    private List<MethodData> pickPremiumReaders(DexKitBridge b) {
        try {
            MethodDataList list = b.findMethod(FindMethod.create()
                    .searchPackages(SEARCH_LIST)
                    .matcher(MethodMatcher.create()
                            .returnType(Anchor.PREMIUM_RET_TYPE)
                            .paramCount(Anchor.PREMIUM_ARG_COUNT)
                            .addUsingField(
                                    FieldMatcher.create().name(Anchor.PREMIUM_FIELD_READ),
                                    UsingType.Read)));
            return list == null ? Collections.<MethodData>emptyList() : new ArrayList<>(list);
        } catch (Throwable t) {
            L.w(TAG, "pickPremiumReaders 失败: " + t);
            return Collections.emptyList();
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 查询封装
    // ══════════════════════════════════════════════════════════════

    private List<MethodData> findUsingString(DexKitBridge b, final String anchor) {
        try {
            MethodDataList list = b.findMethod(FindMethod.create()
                    .searchPackages(SEARCH_LIST)
                    .matcher(MethodMatcher.create().usingStrings(anchor)));
            return list == null ? Collections.<MethodData>emptyList() : new ArrayList<>(list);
        } catch (Throwable t) {
            L.w(TAG, "findUsingString(" + anchor + ") 失败: " + t);
            return Collections.emptyList();
        }
    }

    static String key(MethodData m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getClassName()).append('#').append(m.getName()).append('(');
        List<String> p = m.getParamTypeNames();
        if (p != null) {
            for (String s : p) sb.append(s).append(',');
        }
        return sb.append(')').toString();
    }

    public Method toMethod(MethodData d) {
        if (d == null) return null;
        try {
            return d.getMethodInstance(cl);
        } catch (Throwable t) {
            L.w(TAG, "toMethod 失败: " + key(d), t);
            return null;
        }
    }
}
