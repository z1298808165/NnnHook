package io.nnnhook.core;

import io.nnnhook.BuildConfig;

import io.nnnhook.util.L;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 适配缓存：判断"这一次要不要重新识别"。
 *
 * 什么时候必须重来（指纹任一项变了）：
 *   1. 目标 APK 换版 —— versionCode 或 lastUpdateTime 变（R8 重映射，落点全变）
 *   2. 模块换版 —— 逻辑改了，落点集合可能变
 *   3. 锚点版本号变 —— Anchor.ANCHOR_REV，改锚点就 +1，强制重探
 *   4. 目标包名变 —— 不同分支的 dex 不同
 *
 * 指纹一致时直接读缓存里的落点标识（类名|方法名|参数），反射还原成 Method 装 hook，
 * 跳过 DexKit 建桥（那是整个流程里最慢的一步）。
 *
 * 缓存写在**目标 app 自己的** filesDir 下：目标进程写自己的目录合法，
 * 不需要 root，也不会污染模块 app 的存储。
 */
public final class AdaptCache {

    private static final String TAG = "NnnHook.Cache";
    private static final String DIR = "nnnhook";
    private static final String FILE = "adapt.txt";
    private static final String HEADER = "v1";

    private AdaptCache() {}

    /** 目标 app 的版本指纹。拿不到 PackageInfo 时返回 null（表示不要用缓存）。 */
    public static String fingerprint(Context ctx, String pkg) {
        if (ctx == null) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            long updated = pi.lastUpdateTime;
            return HEADER
                    + "|" + pkg
                    + "|" + pi.versionCode
                    + "|" + (pi.versionName == null ? "-" : pi.versionName)
                    + "|" + updated
                    + "|" + BuildConfig.VERSION_CODE
                    + "|" + Anchor.ANCHOR_REV;
        } catch (Throwable t) {
            L.w(TAG, "取不到 " + pkg + " 的版本信息: " + t);
            return null;
        }
    }

    public static File file(Context ctx) {
        File dir = new File(ctx.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) return null;
        return new File(dir, FILE);
    }

    /**
     * 按包名直接定位缓存文件。
     *
     * 为什么不用 Context：onPackageLoaded 阶段 Application 还没创建，拿不到 app Context；
     * 但我们在目标进程里，进程 uid 就是目标 app 的 uid，直接按路径读写自己的
     * /data/data/&lt;pkg&gt;/files 是合法的。这样最早阶段就能判断缓存。
     */
    public static File fileFor(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        File dir = new File("/data/data/" + pkg + "/files/" + DIR);
        if (!dir.exists() && !dir.mkdirs()) return null;
        return new File(dir, FILE);
    }

    /** 指纹匹配则返回落点标识列表，不匹配/不存在返回 null。 */
    public static List<String> load(Context ctx, String fp) {
        return load(fp, ctx == null ? null : file(ctx));
    }

    public static List<String> load(String fp, File f) {
        if (fp == null || f == null || !f.isFile()) return null;
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String first = r.readLine();
            if (!fp.equals(first)) {
                L.i(TAG, "指纹不匹配，需要重新识别");
                return null;
            }
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) out.add(line);
            }
        } catch (Throwable t) {
            L.w(TAG, "读缓存失败: " + t);
            return null;
        }
        L.i(TAG, "缓存命中，落点 " + out.size() + " 个");
        return out;
    }

    public static void save(Context ctx, String fp, List<String> spots) {
        save(fp, ctx == null ? null : file(ctx), spots);
    }

    public static void save(String fp, File f, List<String> spots) {
        if (fp == null || f == null) return;
        try (FileOutputStream fos = new FileOutputStream(f)) {
            StringBuilder sb = new StringBuilder(fp).append('\n');
            for (String s : spots) sb.append(s).append('\n');
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            L.i(TAG, "已缓存 " + spots.size() + " 个落点");
        } catch (Throwable t) {
            L.w(TAG, "写缓存失败: " + t);
        }
    }

    /** 清缓存（改锚点调试时用）。 */
    public static void clear(Context ctx) {
        File f = file(ctx);
        if (f != null && f.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public static void clear(String pkg) {
        File f = fileFor(pkg);
        if (f != null && f.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            L.i(TAG, "缓存已清除: " + pkg);
        }
    }
}
