package io.nnnhook.hook;

import io.nnnhook.core.Anchor;
import io.nnnhook.util.FieldKit;
import io.nnnhook.util.L;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

/**
 * 会员状态 hook。
 *
 * 为什么改字段而不是钉判定方法：
 *   实测这一版全包 (TLRPC$User)->boolean 有 15 个，方法名全被替换成中文长句，
 *   没有可区分特征；唯一形状匹配的 boolean 方法是 onFragmentCreate（Fragment 生命周期），
 *   钉它必崩。反过来 TLRPC$User 的 34 个 boolean 字段名全部保留原名，premium 在其中，
 *   TLdeserialize / fromConstructor 也没被动。
 *
 * 所以路径是：挂 TL 反序列化出口 → 返回的 User/UserFull 对象上把 premium 置 true。
 * 覆盖每次从网络/存储反序列化出来的用户对象，包括当前用户与对话方。
 *
 * 落点来源有两种：线上探测（MethodData → Method）与缓存命中（直接给 Method），
 * 这里统一收 List&lt;Method&gt;，两条路走同一段安装逻辑。
 */
public final class PremiumHook {

    private static final String TAG = "NnnHook.Premium";

    private PremiumHook() {}

    /**
     * @param exits 反序列化出口：拿返回值把 premium 字段置 true
     * @param flags 判定方法：直接返回 true（UI 的会员分支走这里）
     * @param hits  实际置位次数
     * @return 装上的 hook 条数
     */
    public static int install(XposedModule module, List<Method> exits, List<Method> flags,
                              Set<Method> seen, AtomicInteger hits) {
        int hooked = 0;

        for (Method m : exits) {
            if (m == null) continue;
            if (!seen.add(m)) {
                L.w(TAG, "重复目标，跳过: " + m);
                continue;
            }
            final String where = m.getDeclaringClass().getName() + "#" + m.getName();
            try {
                module.hook(m).intercept(chain -> {
                    Object ret = chain.proceed();
                    if (ret != null && FieldKit.setPremium(ret, Anchor.PREMIUM_FIELDS)) {
                        int n = hits.incrementAndGet();
                        if (n <= 3) {
                            L.i(TAG, "premium=true on " + ret.getClass().getSimpleName()
                                    + " via " + where + " (#" + n + ")");
                        }
                    }
                    return ret;
                });
                hooked++;
                L.i(TAG, "exit hooked " + where);
            } catch (Throwable t) {
                L.e(TAG, "exit hook 失败: " + where, t);
            }
        }

        for (Method m : flags) {
            if (m == null) continue;
            if (!seen.add(m)) continue;
            final String where = m.getDeclaringClass().getName() + "#" + m.getName();
            try {
                // 判定方法读的是 premium 字段，钉成 true 就等于"当前账号是会员"
                final AtomicInteger calls = new AtomicInteger();
                module.hook(m).intercept(chain -> {
                    int n = calls.incrementAndGet();
                    // 前几次调用打印调用者，用来确认这个方法到底服务于哪条 UI 分支
                    if (n <= 6) {
                        L.i(TAG, "flag call #" + n + " <- " + caller());
                    }
                    return Boolean.TRUE;
                });
                hooked++;
                L.i(TAG, "flag hooked " + where + " -> true");
            } catch (Throwable t) {
                L.e(TAG, "flag hook 失败: " + where, t);
            }
        }

        return hooked;
    }

    /** 取调用栈上 3 层最近的非自身帧，用于判断判定方法服务于哪条分支。 */
    private static String caller() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int picked = 0;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn.startsWith("io.nnnhook") || cn.startsWith("java.lang.Throwable")
                    || cn.startsWith("io.github.libxposed")) {
                continue;
            }
            if (picked++ > 0) sb.append(" <- ");
            int dot = cn.lastIndexOf('.');
            sb.append(dot > 0 ? cn.substring(dot + 1) : cn)
                    .append('#').append(e.getMethodName());
            if (picked >= 3) break;
        }
        return sb.toString();
    }
}
