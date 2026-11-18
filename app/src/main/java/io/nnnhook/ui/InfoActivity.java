package io.nnnhook.ui;

import io.nnnhook.BuildConfig;

import io.nnnhook.core.AdaptProgress;
import io.nnnhook.core.DexSource;
import io.nnnhook.core.Engine;
import io.nnnhook.core.HookPoint;
import io.nnnhook.core.Resolved;
import io.nnnhook.util.L;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 模块信息 + 适配自检页。
 *
 * 自检怎么做的：选一个目标 APK，用 DexKit 建一次桥，跑与线上完全相同的识别流程
 * （Engine.resolve），把每个 HookPoint 的状态实时画出来。整个过程在本模块进程内完成，
 * 不碰目标 app，也不需要 root —— 只要你能选到那个 APK 文件。
 *
 * 线上（目标进程里）的适配结果看 logcat：
 *   adb logcat | grep NnnHook
 * 状态行形如 "✓ [premium.field] 会员字段置位 (12ms) — 2 个反序列化落点"。
 */
public final class InfoActivity extends Activity {

    private static final int REQ_APK = 1001;

    private final AdaptProgress progress = new AdaptProgress();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private ProgressBar bar;
    private LinearLayout rows;
    private volatile boolean probing;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            bar.setMax(Math.max(1, progress.total()));
            bar.setProgress(progress.doneCount());
            renderRows();
            if (probing) ui.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("NnnHook " + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.VERSION_CODE + ")");
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("\n适配自检：选一个目标 APK，用与线上同一套识别逻辑跑一遍，"
                + "逐点显示每个 hook API 的命中状态、落点与耗时。\n\n"
                + "线上结果看日志：adb logcat | grep NnnHook\n");
        desc.setTextSize(13f);
        root.addView(desc);

        Button pick = new Button(this);
        pick.setText("选择目标 APK 自检");
        pick.setOnClickListener(v -> pickApk());
        root.addView(pick);

        statusView = new TextView(this);
        statusView.setText("未开始");
        statusView.setTextSize(13f);
        statusView.setPadding(0, dp(12), 0, dp(4));
        root.addView(statusView);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(progress.total());
        bar.setProgress(0);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(8), 0, 0);
        root.addView(rows);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        renderRows();
    }

    @Override
    protected void onDestroy() {
        probing = false;
        ui.removeCallbacks(tick);
        super.onDestroy();
    }

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_APK);
        } catch (Throwable t) {
            statusView.setText("没有可用的文件选择器：" + t);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_APK || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        statusView.setText("正在复制 APK …");
        new Thread(() -> {
            File apk = null;
            String err = null;
            try {
                apk = copyToCache(uri);
            } catch (Throwable t) {
                err = String.valueOf(t);
            }
            final File f = apk;
            final String e = err;
            ui.post(() -> {
                if (f == null) {
                    statusView.setText("读取 APK 失败：" + e);
                    return;
                }
                probe(f);
            });
        }).start();
    }

    private void probe(File apk) {
        probing = true;
        progress.reset(apk.getName());
        statusView.setText("正在索引 " + apk.getName() + " …");
        ui.post(tick);

        new Thread(() -> {
            String note;
            try {
                // Android 14+ 不允许从可写目录 DexClassLoader，所以拆 dex 字节直喂 DexKit
                byte[][] dex = DexSource.fromApk(apk);
                Resolved r = Engine.forDexArray(apk.getName(), dex).resolve(progress);
                note = r.isEmpty() ? "识别完成，无可信落点（模块在真机上会保持 inert）"
                        : "识别完成：" + r.summary();
            } catch (Throwable t) {
                L.e("NnnHook.Info", "自检失败", t);
                note = "自检异常：" + t;
            }
            final String n = note;
            ui.post(() -> {
                probing = false;
                ui.removeCallbacks(tick);
                tick.run();
                statusView.setText(n);
            });
        }).start();
    }

    private File copyToCache(Uri uri) throws Exception {
        File out = new File(getCacheDir(), "probe-target.apk");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new IllegalStateException("openInputStream 返回 null");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
        return out;
    }

    /** 每个 HookPoint 一行：状态字形 + 标题 + 耗时 + 落点。 */
    private void renderRows() {
        if (rows == null) return;
        rows.removeAllViews();
        for (AdaptProgress.Item it : progress.snapshot()) {
            TextView tv = new TextView(this);
            StringBuilder sb = new StringBuilder();
            sb.append(it.status.glyph).append(' ').append(it.point.title);
            if (it.elapsedMs > 0) sb.append("  ").append(it.elapsedMs).append("ms");
            sb.append('\n');
            sb.append("    ");
            sb.append(it.detail.isEmpty() ? it.point.desc : it.detail);
            tv.setText(sb.toString());
            tv.setTextSize(13f);
            tv.setPadding(0, dp(6), 0, dp(6));
            rows.addView(tv);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
