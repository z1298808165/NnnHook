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
 * 广告 / 赞助频道隐藏。
 *
 * 落点用 TL 模型而不是 UI：TLRPC$messages_SponsoredMessages#TLdeserialize 完成后
 * 把它的 messages 列表清空，客户端拿到的就是空列表，自然不会渲染赞助位。
 *
 * 两个实测事实支撑这条路径：
 *   1. 类名保留，且 fields/TLdeserialize 都在父类 messages_SponsoredMessages 上
 *      （子类 TL_messages_sponsoredMessages 只有 <init>/readParams/serializeToStream）
 *   2. TL 模型字段名保留原名（messages/chats/users 都是原样）
 */
public final class SponsoredHook {

    private static final String TAG = "NnnHook.Sponsored";

    private SponsoredHook() {}

    public static int install(XposedModule module, List<Method> methods,
                              Set<Method> seen, AtomicInteger cleared) {
        int hooked = 0;
        for (Method m : methods) {
            if (m == null) continue;
            if (!seen.add(m)) {
                L.w(TAG, "重复目标，跳过: " + m);
                continue;
            }
            final String where = m.getDeclaringClass().getName() + "#" + m.getName();
            try {
                module.hook(m).intercept(chain -> {
                    Object ret = chain.proceed();
                    if (clearList(ret, "messages")) {
                        int n = cleared.incrementAndGet();
                        if (n <= 3) {
                            L.i(TAG, "sponsored cleared on "
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

    /** 把对象的某个 List 字段清空。字段可能是 final，直接对容器 clear。 */
    private static boolean clearList(Object target, String fieldName) {
        if (target == null) return false;
        Field f = FieldKit.find(target.getClass(), fieldName);
        if (f == null) return false;
        try {
            Object v = f.get(target);
            if (!(v instanceof Collection)) return false;
            Collection<?> c = (Collection<?>) v;
            if (c.isEmpty()) return false;
            c.clear();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
