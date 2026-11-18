package io.nnnhook.hook;

import io.nnnhook.core.Anchor;
import io.nnnhook.util.L;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

/**
 * 文件夹数量上限。
 *
 * 走过的弯路与最终结论（全部真机实测）：
 *   1. `dialog_filters_limit_default` 这类名字在 dex 里 fieldDef=0、fieldRef=0，
 *      只作为字符串 key 出现 → 它们不是字段，是配置键
 *   2. 上限持久化在目标 app 自己的 `shared_prefs/mainconfig.xml`，
 *      键名明文：`dialogFiltersLimitPremium`（实测 30）
 *   3. 直接改 XML 无效：app 每次启动都从服务端 config 重写 prefs（改完重启又变 30）
 *   4. 在 loadConfig **之前**改字段也无效：方法内部会重新从 config 赋值（1000 被打回 30）
 *
 * 所以唯一有效时机是 **loadConfig 返回之后**再抬：
 *   UI 读的是内存里的字段，不读 prefs，返回后改就能让界面按新值走。
 *
 * 字段名被替换成中文长句（如 `但是巫师三狂猎每天看四小时书`），不可硬编码，
 * 因此按**值匹配**：只有取值恰好等于上限特征值（这一版是 30）的 int 字段才被抬。
 * 该值在原包内只对应 2 个字段，误伤面极小，日志会打印具体改了哪个。
 */
public final class LimitHook {

    private static final String TAG = "NnnHook.Limit";

    private LimitHook() {}

    public static int install(XposedModule module, List<Method> loaders,
                              Set<Method> seen, int[] featureValues,
                              AtomicInteger lifted) {
        int hooked = 0;
        for (Method m : loaders) {
            if (m == null) continue;
            if (!seen.add(m)) continue;

            final String where = m.getDeclaringClass().getName() + "#" + m.getName();
            final AtomicInteger entry = new AtomicInteger();
            try {
                module.hook(m).intercept(chain -> {
                    int calls = entry.incrementAndGet();
                    if (calls <= 3) L.i(TAG, "loadConfig called #" + calls);

                    Object ret = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self != null) {
                        int n = raise(self, featureValues, Anchor.LIMIT_LARGE);
                        if (n > 0) {
                            int total = lifted.addAndGet(n);
                            if (total <= 6) {
                                L.i(TAG, "raised " + n + " field(s) to "
                                        + Anchor.LIMIT_LARGE + " after " + where);
                            }
                        } else if (calls <= 3) {
                            L.i(TAG, "loadConfig 返回后没有匹配到特征值，"
                                    + "上限可能不是 30（看 mainconfig.xml 实际值）");
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

    /** 把值恰好等于特征值的 int 字段抬到 to。 */
    private static int raise(Object target, int[] from, int to) {
        int changed = 0;
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }
            for (Field f : fields) {
                if (f.getType() != int.class) continue;
                try {
                    f.setAccessible(true);
                    int v = f.getInt(target);
                    if (v >= to) continue;
                    boolean hit = false;
                    for (int want : from) {
                        if (v == want) {
                            hit = true;
                            break;
                        }
                    }
                    if (!hit) continue;
                    f.setInt(target, to);
                    changed++;
                    L.i(TAG, "[" + f.getName() + "] " + v + " -> " + to);
                } catch (Throwable ignore) {
                    // 单个字段失败不影响其它
                }
            }
            c = c.getSuperclass();
        }
        return changed;
    }
}
