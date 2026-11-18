package io.nnnhook;

import io.nnnhook.core.AdaptCache;
import io.nnnhook.core.AdaptProgress;
import io.nnnhook.core.Anchor;
import io.nnnhook.core.Engine;
import io.nnnhook.core.HookPoint;
import io.nnnhook.core.Resolved;
import io.nnnhook.hook.Hooks;
import io.nnnhook.ui.AdaptDialog;
import io.nnnhook.util.L;
import io.nnnhook.util.TypeNames;

import android.content.Context;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * API 102 模块入口。
 *
 * 一次适配的完整流程：
 *
 *   1. 取指纹（目标 versionCode + lastUpdateTime + 模块 versionCode + Anchor.ANCHOR_REV）
 *   2. 指纹命中缓存 → 直接把落点标识反射成 Method，装 hook，跳过 DexKit（最慢的一步）
 *   3. 指纹不命中 → DexKit 重新识别，结束后把落点标识写回缓存
 *   4. 首个 Activity 起来时弹适配报告（AdaptDialog）
 *
 * 什么时候必须重新适配：
 *   - 目标 app 换版（versionCode / lastUpdateTime 变）
 *   - 模块换版（versionCode 变）
 *   - 锚点改了（ANCHOR_REV 必须手动 +1）
 *   - 缓存里的落点反射全部失败（类/方法被删）→ 自动回退到重新识别
 */
public final class Entry extends XposedModule {

    static final String TAG = "NnnHook.Entry";

    private final AdaptProgress progress = new AdaptProgress();

    /** 本进程是否已装过 hook。模块实例每进程一个，进程内装一次就够。 */
    private volatile boolean installed;

    /**
     * 待写入的缓存。onPackageLoaded 阶段 Application 还没创建，
     * appContext() 返回 null，指纹算不出来；把落点留到 onPackageReady 落盘。
     */
    private volatile String pendingPkg;
    private volatile List<String> pendingSpecs;

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        L.i(TAG, "onModuleLoaded api=" + getApiVersion()
                + " framework=" + getFrameworkName()
                + " verCode=" + getFrameworkVersionCode()
                + " process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer());
    }

    @Override
    public void onPackageLoaded(@NonNull XposedModuleInterface.PackageLoadedParam param) {
        if (!Anchor.supports(param.getPackageName())) return;
        adapt(param.getPackageName(), param.getDefaultClassLoader(),
                "loaded", param.isFirstPackage());
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (!Anchor.supports(param.getPackageName())) return;
        if (installed) {
            // 已经装完了，这个阶段只为把没写成的缓存补上
            flushCache();
            return;
        }
        adapt(param.getPackageName(), param.getClassLoader(), "ready", param.isFirstPackage());
    }

    @Override
    public boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        L.i(TAG, "onHotReloading, old handles will be dropped");
        installed = false;
        return true;
    }

    // ══════════════════════════════════════════════════════════════

    private void adapt(String pkg, ClassLoader cl, String phase, boolean firstPackage) {
        L.i(TAG, "target hit: " + pkg + " phase=" + phase + " first=" + firstPackage);
        progress.reset(pkg);

        // 用 system context 取 PackageManager（app context 这一阶段还没创建）；
        // 缓存文件按包名直接定位，目标进程读自己的 /data/data 是合法的。
        Context sys = systemContext();
        String fp = AdaptCache.fingerprint(sys, pkg);
        java.io.File cacheFile = AdaptCache.fileFor(pkg);
        List<String> cached = AdaptCache.load(fp, cacheFile);

        if (cached != null && !cached.isEmpty()) {
            Resolved r = fromCache(pkg, cl, cached);
            if (!r.isEmpty()) {
                markCacheHit(r);
                Hooks.install(this, r, progress);
                installed = true;
                progress.log();
                // 缓存命中说明这一版已经适配过：不再弹窗，只留日志。
                // 弹窗只在"需要重新适配"（首次/换版/改锚点/缓存失效）时出现。
                L.i(TAG, "缓存命中，跳过适配弹窗");
                return;
            }
            L.w(TAG, "缓存落点全部失效（类或方法被删），回退到重新识别");
            AdaptCache.clear(pkg);
        }

        try {
            Resolved r = Engine.forTarget(pkg, cl).resolve(progress);
            if (r.isEmpty()) {
                L.w(TAG, phase + " 阶段没有可信落点，模块保持 inert");
                progress.log();
                AdaptDialog.attach(this, progress, false);
                return;
            }
            Hooks.install(this, r, progress);
            installed = true;
            progress.log();

            List<String> specs = specsOf(r);
            if (fp != null && cacheFile != null) {
                AdaptCache.save(fp, cacheFile, specs);
            } else {
                pendingPkg = pkg;
                pendingSpecs = specs;
                L.i(TAG, "缓存路径不可用，留到 onPackageReady 再写");
            }
            AdaptDialog.attach(this, progress, false);
        } catch (Throwable t) {
            // 识别期异常不允许打断宿主进程启动
            L.e(TAG, "bootstrap failed at " + phase, t);
        }
    }

    /** onPackageReady 阶段补写缓存。 */
    private void flushCache() {
        List<String> specs = pendingSpecs;
        String pkg = pendingPkg;
        if (specs == null || pkg == null) return;

        Context sys = systemContext();
        String fp = AdaptCache.fingerprint(sys, pkg);
        java.io.File f = AdaptCache.fileFor(pkg);
        if (fp == null || f == null) {
            L.w(TAG, "ready 阶段缓存路径仍不可用，本进程不写缓存");
            return;
        }
        AdaptCache.save(fp, f, specs);
        pendingSpecs = null;
        pendingPkg = null;
    }

    /** 缓存 → Resolved：把落点标识反射回 Method，不建 DexKit 桥。 */
    private static Resolved fromCache(String pkg, ClassLoader cl, List<String> specs) {
        Resolved r = new Resolved(pkg, cl);
        for (String s : specs) {
            int i = s.indexOf('|');
            if (i <= 0) continue;
            String kind = s.substring(0, i);
            Method m = TypeNames.toMethod(s.substring(i + 1), cl);
            if (m == null) {
                L.w(TAG, "缓存落点已失效: " + s);
                continue;
            }
            if ("premium".equals(kind)) {
                r.premiumHooks.add(m);
            } else if ("premium.flag".equals(kind)) {
                r.premiumFlagHooks.add(m);
            } else if ("sponsored".equals(kind)) {
                r.sponsoredHooks.add(m);
            } else if ("send".equals(kind)) {
                r.sendRequestHooks.add(m);
            } else if ("delete".equals(kind)) {
                r.antiDeleteHooks.add(m);
            } else if ("pinlimit".equals(kind)) {
                r.pinLimitHooks.add(m);
            } else if ("folderlimit".equals(kind)) {
                r.folderLimitHooks.add(m);
            } else if ("loader".equals(kind)) {
                r.limitLoaderHooks.add(m);
            } else if ("restrict".equals(kind)) {
                r.restrictedHooks.add(m);
            }
        }
        r.fromCache = true;
        r.premiumFieldPresent = !r.premiumHooks.isEmpty();
        return r;
    }

    /** Resolved → 落点标识行（写缓存用）。 */
    private static List<String> specsOf(Resolved r) {
        List<String> out = new ArrayList<>();
        for (org.luckypray.dexkit.result.MethodData d : r.userDeserializers) {
            out.add("premium|" + TypeNames.spec(
                    d.getClassName(), d.getName(), d.getParamTypeNames()));
        }
        for (org.luckypray.dexkit.result.MethodData d : r.premiumFlagData) {
            out.add("premium.flag|" + TypeNames.spec(
                    d.getClassName(), d.getName(), d.getParamTypeNames()));
        }
        for (org.luckypray.dexkit.result.MethodData d : r.sponsoredDeserializers) {
            out.add("sponsored|" + TypeNames.spec(
                    d.getClassName(), d.getName(), d.getParamTypeNames()));
        }
        for (org.luckypray.dexkit.result.MethodData d : r.sendRequests) {
            out.add("send|" + TypeNames.spec(
                    d.getClassName(), d.getName(), d.getParamTypeNames()));
        }
        for (org.luckypray.dexkit.result.MethodData d : r.antiDeleteMethods) {
            out.add("delete|" + TypeNames.spec(
                    d.getClassName(), d.getName(), d.getParamTypeNames()));
        }
        for (org.luckypray.dexkit.result.MethodData d : r.restrictedMethods) {
            out.add("restrict|" + TypeNames.spec(
                    d.getClassName(), d.getName(), d.getParamTypeNames()));
        }
        for (org.luckypray.dexkit.result.MethodData d : r.limitLoaderMethods) {
            out.add("loader|" + TypeNames.spec(
                    d.getClassName(), d.getName(), d.getParamTypeNames()));
        }
        for (org.luckypray.dexkit.result.MethodData d : r.pinLimitMethods) {
            out.add("pinlimit|" + TypeNames.spec(
                    d.getClassName(), d.getName(), d.getParamTypeNames()));
        }
        for (org.luckypray.dexkit.result.MethodData d : r.folderLimitMethods) {
            out.add("folderlimit|" + TypeNames.spec(
                    d.getClassName(), d.getName(), d.getParamTypeNames()));
        }
        return out;
    }

    /** 缓存命中时，把不参与探测的点标成明确状态，避免进度条停在 PENDING。 */
    private void markCacheHit(Resolved r) {
        progress.target(r.pkg, -1);
        progress.begin(HookPoint.BRIDGE);
        progress.ok(HookPoint.BRIDGE, "缓存命中，跳过建桥");
        progress.begin(HookPoint.PREMIUM_DESER);
        progress.ok(HookPoint.PREMIUM_DESER,
                r.premiumHooks.size() + " 出口 + " + r.premiumFlagHooks.size()
                        + " 判定（缓存）");
        boolean send = !r.sendRequestHooks.isEmpty();
        boolean del = !r.antiDeleteHooks.isEmpty();
        boolean pin = !r.pinLimitHooks.isEmpty();
        boolean folder = !r.limitLoaderHooks.isEmpty() || !r.folderLimitHooks.isEmpty();

        for (HookPoint p : HookPoint.values()) {
            if (p == HookPoint.BRIDGE || p == HookPoint.PREMIUM_DESER) continue;
            // 下面这些由 Hooks.install 写真实结果
            if (p == HookPoint.PREMIUM_FIELD) continue;
            if (p == HookPoint.SPONSORED && Anchor.ENABLE_SPONSORED) continue;
            if (send && (p == HookPoint.READ_RECEIPT || p == HookPoint.ONLINE_STATUS
                    || p == HookPoint.SCREENSHOT)) continue;
            if (del && p == HookPoint.ANTI_DELETE) continue;
            if (pin && p == HookPoint.PIN_LIMIT) continue;
            if (folder && p == HookPoint.FOLDER_LIMIT) continue;
            if (!r.restrictedHooks.isEmpty() && p == HookPoint.PREMIUM_CONTENT) continue;
            progress.disabled(p, "缓存模式，未重新探测");
        }
    }

    @android.annotation.SuppressLint("PrivateApi")
    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            L.w(TAG, "拿不到 Application context: " + t);
            return null;
        }
    }

    /**
     * system context。onPackageLoaded 阶段 Application 还没创建，但
     * ActivityThread 已经有了 system context，拿它只为访问 PackageManager
     * 查目标版本（算缓存指纹），不用于文件路径。
     */
    @android.annotation.SuppressLint("PrivateApi")
    private static Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object ctx = at.getMethod("getSystemContext").invoke(thread);
            return ctx instanceof Context ? (Context) ctx : null;
        } catch (Throwable t) {
            L.w(TAG, "拿不到 system context: " + t);
            return null;
        }
    }
}
