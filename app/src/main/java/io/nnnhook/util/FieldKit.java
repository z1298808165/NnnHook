package io.nnnhook.util;

import io.nnnhook.core.Anchor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * 字段操作工具。
 *
 * 目标 APK 里字段名也可能被混淆（premium 作为方法名 0 次，字段同样不可信），
 * 所以这里按「类型 + 名字片段」双条件定位，而不是按精确名。
 */
public final class FieldKit {

    private static final String TAG = "NnnHook.FieldKit";

    /** 认为"像会员标记"的名字片段，全小写比较。去重后保留语义最窄的几个。 */
    private static final String[] HINTS = {
            "premium", "vip", "subscri", "ispro", "paid"
    };

    private FieldKit() {}

    /**
     * 把对象上所有疑似 premium 的 boolean 字段置 true。
     *
     * 只改 boolean，不碰其它类型，避免破坏对象状态。
     * final 字段也尝试改：TL 模型里这类字段大量是 final，跳过它等于这条路径无效；
     * 反射改非 static final 实例字段在 ART 上是可行的，失败就跳过该字段。
     *
     * @return 实际被置位的字段个数，调用方用它判断这条弱路径是否真起了作用
     */
    public static int forcePremiumFlags(Object target) {
        if (target == null) return 0;

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
                if (!looksLikePremiumFlag(f)) continue;
                // static final 是编译期常量，改它没有运行时意义，直接跳
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) && Modifier.isFinal(mod)) continue;
                try {
                    f.setAccessible(true);
                    f.setBoolean(target, true);
                    changed++;
                } catch (Throwable ignore) {
                    // 单个字段失败不影响其它字段
                }
            }
            c = c.getSuperclass();
        }
        if (changed > 0) {
            L.i(TAG, "premium flags set: " + changed + " on " + target.getClass().getName());
        }
        return changed;
    }

    /**
     * 按字段名精确置 true。用于 TL 模型上名字已确认保留的字段（如 premium）。
     * 这是当前唯一可信的会员路径：字段名在目标版本实测保留原名。
     *
     * @return 是否真的写进去了
     */
    public static boolean setBooleanField(Object target, String name) {
        if (target == null || name == null) return false;
        Field f = find(target.getClass(), name);
        if (f == null) return false;
        if (f.getType() != boolean.class) return false;
        try {
            f.setAccessible(true);
            f.setBoolean(target, true);
            return true;
        } catch (Throwable t) {
            L.w(TAG, "setBooleanField(" + name + ") 失败: " + t);
            return false;
        }
    }

    /** 按 Anchor 里登记的字段名逐个尝试，命中一个即可。 */
    public static boolean setPremium(Object target, String[] names) {
        if (target == null || names == null) return false;
        for (String n : names) {
            if (setBooleanField(target, n)) return true;
        }
        // 名字全不对时退回模糊匹配（覆盖字段名被混淆的版本）
        return forcePremiumFlags(target) > 0;
    }

    private static boolean looksLikePremiumFlag(Field f) {
        if (f.getType() != boolean.class) return false;
        String n = f.getName();
        if (n == null) return false;
        String lower = n.toLowerCase(Locale.ROOT);
        for (String h : HINTS) {
            if (lower.contains(h)) return true;
        }
        return false;
    }

    /** 在类层级上按名字找字段，找不到返回 null。 */
    public static Field find(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 按类型找第一个匹配的字段。 */
    public static Field findByType(Class<?> c, Class<?> type) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try {
                for (Field f : cur.getDeclaredFields()) {
                    if (f.getType() == type) {
                        f.setAccessible(true);
                        return f;
                    }
                }
            } catch (Throwable ignore) {
                // 继续往父类找
            }
            cur = cur.getSuperclass();
        }
        return null;
    }
}
