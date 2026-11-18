package io.nnnhook.core;

import io.nnnhook.util.L;

import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 识别结果容器。所有字段都可能为 null / 空，调用方必须容忍。
 * 一个"空结果"是合法状态：说明这个版本没有可信落点，模块保持静默。
 */
public final class Resolved {

    public static final Resolved EMPTY = new Resolved("<none>", null);

    public final String pkg;
    public final ClassLoader classLoader;

    /** TL 反序列化入口：TLRPC$User / TLRPC$UserFull 的 TLdeserialize、fromConstructor。 */
    public List<MethodData> userDeserializers = new ArrayList<>();

    /** 赞助消息模型的反序列化入口。 */
    public List<MethodData> sponsoredDeserializers = new ArrayList<>();

    /** 会员判定方法：读 premium 字段的无参 boolean，hook 成 true。 */
    public List<MethodData> premiumFlagData = new ArrayList<>();

    /** 已解析好的可 hook 方法（线上路径由 Engine 填，缓存路径直接填）。 */
    public List<Method> premiumHooks = new ArrayList<>();

    /** 已解析好的判定方法（缓存路径直接填）。 */
    public List<Method> premiumFlagHooks = new ArrayList<>();

    /** 发送层 sendRequest 的所有重载（已读/在线/截图的共同落点）。 */
    public List<MethodData> sendRequests = new ArrayList<>();

    /** 防撤回：入站删除更新的 readParams。 */
    public List<MethodData> antiDeleteMethods = new ArrayList<>();

    /** 置顶上限：读 pinned_dialogs_count_max 的方法。 */
    public List<MethodData> pinLimitMethods = new ArrayList<>();

    /** 文件夹上限：读 dialog_filters_* 的方法。 */
    public List<MethodData> folderLimitMethods = new ArrayList<>();

    /** 受限标记清理：TLRPC$chat 的 TLdeserialize。 */
    public List<MethodData> restrictedMethods = new ArrayList<>();
    public List<Method> restrictedHooks = new ArrayList<>();

    /** 读上限 key 的 loadConfig 方法（诊断用）。 */
    public List<MethodData> limitLoaderMethods = new ArrayList<>();
    public List<Method> limitLoaderHooks = new ArrayList<>();

    /** 缓存命中的直接落点（不做 MethodData → Method 转换）。 */
    public List<Method> sendRequestHooks = new ArrayList<>();
    public List<Method> antiDeleteHooks = new ArrayList<>();
    public List<Method> pinLimitHooks = new ArrayList<>();
    public List<Method> folderLimitHooks = new ArrayList<>();

    private List<Method> resolve(List<MethodData> data, List<Method> cached) {
        if (!cached.isEmpty()) return cached;
        List<Method> out = new ArrayList<>();
        for (MethodData d : data) {
            Method m = method(d);
            if (m != null) out.add(m);
        }
        return out;
    }

    public List<Method> sendRequestList() {
        return resolve(sendRequests, sendRequestHooks);
    }

    public List<Method> antiDeleteList() {
        return resolve(antiDeleteMethods, antiDeleteHooks);
    }

    public List<Method> pinLimitList() {
        return resolve(pinLimitMethods, pinLimitHooks);
    }

    public List<Method> folderLimitList() {
        return resolve(folderLimitMethods, folderLimitHooks);
    }

    public List<Method> restrictedList() {
        return resolve(restrictedMethods, restrictedHooks);
    }

    /** loadConfig 类方法：读上限 key 并写字段的那个（诊断用）。 */
    public List<Method> limitLoaders() {
        return resolve(limitLoaderMethods, limitLoaderHooks);
    }

    /** 已解析好的可 hook 方法（赞助）。 */
    public List<Method> sponsoredHooks = new ArrayList<>();

    /** 本次结果是否来自缓存（跳过 DexKit 探测）。 */
    public boolean fromCache;

    /** 会员判定方法。只有打开 Anchor.ENABLE_SHAPE_PREMIUM 时才可能非空。 */
    public MethodData premiumFlag;

    /** TLRPC$User 上是否真的存在 boolean premium 字段（字段名是否保留原名）。 */
    public boolean premiumFieldPresent;

    /** 上限类方法。Anchor.ENABLE_LIMIT 关闭时恒为空。 */
    public List<MethodData> limits = Collections.emptyList();

    /** 隐私上报类方法。Anchor.ENABLE_PRIVACY 关闭时恒为空。 */
    public List<MethodData> reads = Collections.emptyList();

    public Resolved(String pkg, ClassLoader cl) {
        this.pkg = pkg;
        this.classLoader = cl;
    }

    public boolean isEmpty() {
        return userDeserializers.isEmpty()
                && sponsoredDeserializers.isEmpty()
                && premiumHooks.isEmpty()
                && sponsoredHooks.isEmpty()
                && premiumFlag == null
                && premiumFlagData.isEmpty()
                && sendRequests.isEmpty()
                && sendRequestHooks.isEmpty()
                && antiDeleteMethods.isEmpty()
                && antiDeleteHooks.isEmpty()
                && pinLimitMethods.isEmpty()
                && pinLimitHooks.isEmpty()
                && folderLimitMethods.isEmpty()
                && folderLimitHooks.isEmpty()
                && limits.isEmpty()
                && reads.isEmpty();
    }

    /** 把 MethodData 转成可 hook 的 Method，失败返回 null。 */
    public Method method(MethodData d) {
        if (d == null || classLoader == null) return null;
        try {
            return d.getMethodInstance(classLoader);
        } catch (Throwable t) {
            L.w("NnnHook.Resolved", "method() 失败: " + d.getClassName() + "#" + d.getName(), t);
            return null;
        }
    }

    /** 会员落点：缓存命中时直接用 premiumHooks，否则从 MethodData 现场解析。 */
    public List<Method> premiumMethods() {
        if (!premiumHooks.isEmpty()) return premiumHooks;
        List<Method> out = new ArrayList<>();
        for (MethodData d : userDeserializers) {
            Method m = method(d);
            if (m != null) out.add(m);
        }
        return out;
    }

    /** 赞助落点，解析规则同上。 */
    public List<Method> sponsoredMethods() {
        if (!sponsoredHooks.isEmpty()) return sponsoredHooks;
        List<Method> out = new ArrayList<>();
        for (MethodData d : sponsoredDeserializers) {
            Method m = method(d);
            if (m != null) out.add(m);
        }
        return out;
    }

    /** 会员判定方法（读 premium 字段的无参 boolean）。 */
    public List<Method> premiumFlagMethods() {
        if (!premiumFlagHooks.isEmpty()) return premiumFlagHooks;
        List<Method> out = new ArrayList<>();
        for (MethodData d : premiumFlagData) {
            Method m = method(d);
            if (m != null) out.add(m);
        }
        return out;
    }

    public String summary() {
        return "deser=" + userDeserializers.size()
                + " sponsored=" + sponsoredDeserializers.size()
                + " premium=" + (premiumFlag != null)
                + " limit=" + limits.size()
                + " privacy=" + reads.size();
    }
}
