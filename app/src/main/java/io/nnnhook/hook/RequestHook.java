package io.nnnhook.hook;

import io.nnnhook.core.Anchor;
import io.nnnhook.util.L;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

/**
 * 发送层拦截：已读回执 / 在线状态 / 截图提示。
 *
 * 三个功能共用一个落点 `ConnectionsManager#sendRequest(...)`：
 * hook 住所有重载，第一个参数是目标请求类就直接返回 0（不发送）。
 * 这样不用碰上层业务（readMessages / updateOnlineStatus 这些类名全被混淆）。
 *
 * 为什么安全：sendRequest 的第一个参数在 8 个重载里都是 TLObject，
 * 用类名精确匹配，只丢这几种请求，其余原样 proceed。
 */
public final class RequestHook {

    private static final String TAG = "NnnHook.Request";

    private RequestHook() {}

    public static int install(XposedModule module, List<Method> senders,
                              Set<Method> seen, AtomicInteger blocked) {
        int hooked = 0;
        for (Method m : senders) {
            if (m == null) continue;
            if (!seen.add(m)) continue;
            if (m.getParameterTypes().length == 0) continue;

            final boolean returnsInt = m.getReturnType() == int.class;
            final String where = m.getDeclaringClass().getName() + "#" + m.getName()
                    + "/" + m.getParameterCount();
            try {
                module.hook(m).intercept(chain -> {
                    Object req = null;
                    try {
                        req = chain.getArg(0);
                    } catch (Throwable ignore) {
                        // 参数类型不匹配就当作不拦
                    }
                    if (req != null && isBlocked(req.getClass().getName())) {
                        int n = blocked.incrementAndGet();
                        if (n <= 6) {
                            L.i(TAG, "dropped " + req.getClass().getSimpleName() + " via " + where);
                        }
                        return returnsInt ? 0 : null;
                    }
                    return chain.proceed();
                });
                hooked++;
                L.i(TAG, "hooked " + where);
            } catch (Throwable t) {
                L.e(TAG, "hook 失败: " + where, t);
            }
        }
        return hooked;
    }

    private static boolean isBlocked(String cls) {
        if (cls == null) return false;
        for (String b : Anchor.BLOCKED_REQUESTS) {
            if (b.equals(cls)) return true;
        }
        return false;
    }
}
