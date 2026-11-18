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
 * 防撤回。
 *
 * 落点：入站更新 `TL_update$TL_updateDeleteMessages` 的 `readParams`。
 * 该对象反序列化完会把要删的 message id 放进 `messages` 列表，
 * 我们在这里把它清空 —— 后续处理找不到 id，消息就留在界面上。
 *
 * 为什么改 readParams 而不是拦网络：删除是服务端主动推的 update，
 * 客户端必须收到（否则状态会不同步），只在本地忽略"删除动作"最安全。
 */
public final class AntiDeleteHook {

    private static final String TAG = "NnnHook.AntiDelete";

    private AntiDeleteHook() {}

    public static int install(XposedModule module, List<Method> readParams,
                              Set<Method> seen, AtomicInteger blocked) {
        int hooked = 0;
        for (Method m : readParams) {
            if (m == null) continue;
            if (!seen.add(m)) continue;

            final String where = m.getDeclaringClass().getName() + "#" + m.getName();
            try {
                module.hook(m).intercept(chain -> {
                    Object ret = chain.proceed();
                    Object self = chain.getThisObject();
                    if (clearList(self, "messages")) {
                        int n = blocked.incrementAndGet();
                        if (n <= 3) {
                            L.i(TAG, "delete update neutralized (#" + n + ")");
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
