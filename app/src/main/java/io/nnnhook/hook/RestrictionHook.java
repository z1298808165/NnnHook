package io.nnnhook.hook;

import io.nnnhook.util.FieldKit;
import io.nnnhook.util.L;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

/**
 * 频道 / 群组"会员专属"标记。
 *
 * 先说清楚边界（实测得到，不是推测）：
 *   - 受限内容的消息**本身由服务端决定是否下发**，客户端拿不到就是拿不到
 *   - 全包没有任何"同时读 premium 与 restriction_reason"的判定方法，
 *     13 个交叉命中全是 TL 的 serializeToStream（序列化本来就读写所有字段）
 *     → 说明客户端不是靠会员身份放行内容
 *
 * 所以这一项能做的是客户端这一半：把 `restricted` 标记与 `restriction_reason` 清掉，
 * 界面不再显示"此内容受限 / 需要会员"，客户端会正常向服务端请求内容。
 * 服务端放不放行由账号策略决定。
 *
 * 落点用 TL 反序列化出口（TLRPC$chat 父类的 TLdeserialize），
 * 覆盖 TLRPC$TL_channel / TLRPC$TL_chat 的所有具体子类。
 */
public final class RestrictionHook {

    private static final String TAG = "NnnHook.Restriction";

    private RestrictionHook() {}

    public static int install(XposedModule module, List<Method> deserializers,
                              Set<Method> seen, AtomicInteger cleaned) {
        int hooked = 0;
        for (Method m : deserializers) {
            if (m == null) continue;
            if (!seen.add(m)) continue;

            final String where = m.getDeclaringClass().getName() + "#" + m.getName();
            try {
                module.hook(m).intercept(chain -> {
                    Object ret = chain.proceed();
                    if (ret != null && clearRestriction(ret)) {
                        int n = cleaned.incrementAndGet();
                        if (n <= 3) {
                            L.i(TAG, "restriction cleared on "
                                    + ret.getClass().getSimpleName() + " (#" + n + ")");
                        }
                    }
                    return ret;
                });
                hooked++;
                L.i(TAG, "hooked " + where);
            } catch (Throwable t) {
                L.e(TAG, "hook 失败: " + where, t);
            }
        }
        return hooked;
    }

    private static boolean clearRestriction(Object target) {
        boolean changed = false;

        Field flag = FieldKit.find(target.getClass(), "restricted");
        if (flag != null && flag.getType() == boolean.class) {
            try {
                if (flag.getBoolean(target)) {
                    flag.setBoolean(target, false);
                    changed = true;
                }
            } catch (Throwable ignore) {
                // 忽略
            }
        }

        Field reason = FieldKit.find(target.getClass(), "restriction_reason");
        if (reason != null) {
            try {
                Object v = reason.get(target);
                if (v instanceof Collection) {
                    Collection<?> c = (Collection<?>) v;
                    if (!c.isEmpty()) {
                        c.clear();
                        changed = true;
                    }
                }
            } catch (Throwable ignore) {
                // 忽略
            }
        }
        return changed;
    }
}
