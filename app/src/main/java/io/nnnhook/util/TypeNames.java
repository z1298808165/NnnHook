package io.nnnhook.util;

import java.util.List;

/**
 * 类型名 ↔ Class 的互转。
 *
 * 缓存里存的是 dex 风格的类型名（`int`、`org.telegram.tgnet.InputSerializedData`），
 * 下次启动靠它反射还原方法签名，这样就不必再走一遍 DexKit。
 */
public final class TypeNames {

    private TypeNames() {}

    /** dex 类型名 → Class。失败返回 null。 */
    public static Class<?> resolve(String name, ClassLoader cl) {
        if (name == null || name.isEmpty()) return null;
        switch (name) {
            case "void": return void.class;
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "char": return char.class;
            case "short": return short.class;
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            default: break;
        }
        if (name.endsWith("[]")) {
            Class<?> base = resolve(name.substring(0, name.length() - 2), cl);
            return base == null ? null : java.lang.reflect.Array.newInstance(base, 0).getClass();
        }
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 方法签名的稳定 key：类名|方法名|参数1,参数2 */
    public static String spec(String className, String methodName, List<String> params) {
        StringBuilder sb = new StringBuilder();
        sb.append(className).append('|').append(methodName).append('|');
        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(params.get(i));
            }
        }
        return sb.toString();
    }

    /** 把 spec 还原成 Method。类或方法找不到返回 null。 */
    public static java.lang.reflect.Method toMethod(String spec, ClassLoader cl) {
        if (spec == null || cl == null) return null;
        String[] parts = spec.split("\\|", -1);
        if (parts.length < 3) return null;
        Class<?> c = resolve(parts[0], cl);
        if (c == null) return null;
        String[] rawParams = parts[2].isEmpty() ? new String[0] : parts[2].split(",");
        Class<?>[] types = new Class<?>[rawParams.length];
        for (int i = 0; i < rawParams.length; i++) {
            types[i] = resolve(rawParams[i].trim(), cl);
            if (types[i] == null) return null;
        }
        try {
            java.lang.reflect.Method m = c.getDeclaredMethod(parts[1], types);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }
}
