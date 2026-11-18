package io.nnnhook.ui;

import io.nnnhook.core.AdaptProgress;
import io.nnnhook.util.L;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 适配结果弹窗。
 *
 * 为什么挂 Activity.onCreate：适配发生在 onPackageLoaded/Ready，那时还没有 Activity，
 * 拿 Application context 弹 Dialog 会 BadTokenException。所以先挂住 framework 的
 * Activity.onCreate（framework 类不受 R8 混淆影响），等宿主第一个界面起来再弹。
 *
 * 每个进程只弹一次；弹过一次就把 shown 置起来，后续 Activity 创建只走一次判断。
 */
public final class AdaptDialog {

    private static final String TAG = "NnnHook.Dialog";

    private static volatile boolean shown;
    private static AdaptProgress pendingProgress;
    private static boolean pendingFromCache;
    private static boolean attached;

    private AdaptDialog() {}

    public static void attach(XposedModule module, AdaptProgress pg, boolean fromCache) {
        pendingProgress = pg;
        pendingFromCache = fromCache;
        if (attached) return;

        try {
            Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            module.hook(onCreate).intercept(chain -> {
                Object ret = chain.proceed();
                Object self = chain.getThisObject();
                if (!shown && self instanceof Activity) {
                    shown = true;
                    final Activity a = (Activity) self;
                    a.runOnUiThread(() -> show(a));
                }
                return ret;
            });
            attached = true;
            L.i(TAG, "已挂 Activity.onCreate，等第一个界面出来弹适配报告");
        } catch (Throwable t) {
            L.e(TAG, "挂 Activity.onCreate 失败", t);
        }
    }

    private static void show(Activity a) {
        AdaptProgress pg = pendingProgress;
        if (pg == null || a == null || a.isFinishing()) return;
        try {
            int pad = (int) (16 * a.getResources().getDisplayMetrics().density);

            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(pad, pad / 2, pad, 0);

            ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(Math.max(1, pg.total()));
            bar.setProgress(pg.doneCount());
            box.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView tv = new TextView(a);
            StringBuilder sb = new StringBuilder();
            sb.append(pg.targetPkg());
            if (pg.dexCount() > 0) sb.append("  dex=").append(pg.dexCount());
            sb.append("  ").append(pg.okCount()).append('/').append(pg.total()).append('\n');
            for (AdaptProgress.Item it : pg.snapshot()) {
                sb.append(it.status.glyph).append(' ').append(it.point.title);
                if (it.elapsedMs > 0) sb.append(' ').append(it.elapsedMs).append("ms");
                if (!it.detail.isEmpty()) sb.append("\n    ").append(it.detail);
                sb.append('\n');
            }
            tv.setText(sb.toString().trim());
            tv.setTextSize(12f);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setPadding(0, pad / 2, 0, 0);
            box.addView(tv);

            new AlertDialog.Builder(a)
                    .setTitle(pendingFromCache
                            ? "NnnHook · 使用缓存适配"
                            : "NnnHook · 适配完成")
                    .setView(box)
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable t) {
            L.e(TAG, "弹窗失败", t);
        }
    }

    /** 拿最近 resume 的 Activity（备用入口：hook 里取不到 this 时用）。 */
    @android.annotation.SuppressLint("PrivateApi")
    static Activity currentActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object records = at.getDeclaredField("mActivities").get(thread);
            if (!(records instanceof java.util.Map)) return null;
            for (Object rec : ((java.util.Map<?, ?>) records).values()) {
                if (rec == null) continue;
                java.lang.reflect.Field fa = rec.getClass().getDeclaredField("activity");
                fa.setAccessible(true);
                Object act = fa.get(rec);
                if (act instanceof Activity) {
                    Activity a = (Activity) act;
                    if (!a.isFinishing()) return a;
                }
            }
        } catch (Throwable t) {
            L.w(TAG, "currentActivity 失败: " + t);
        }
        return null;
    }
}
