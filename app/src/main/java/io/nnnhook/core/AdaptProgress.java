package io.nnnhook.core;

import io.nnnhook.util.L;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 适配进度。按 HookPoint 逐点记录状态、落点与耗时。
 *
 * 用途有三：
 *   1. 目标进程里驱动 Toast 汇总（"NNG 3/5 · 会员✓ 上限– 隐私–"）
 *   2. logcat 里输出完整报告（一行一个点，带落点）
 *   3. 模块 App 的自检页直接读它渲染进度条与清单
 *
 * 设计约束：
 *   - 每个点只走一次状态迁移，重复写会被忽略（防止同一点被两个阶段各写一遍）
 *   - 任何字符串都带兜底，不让 null 进日志拼接
 *   - 时间用 SystemClock.elapsedRealtime，不受系统时间调整影响
 */
public final class AdaptProgress {

    public enum Status {
        /** 还没轮到这个点 */
        PENDING("·"),
        /** 正在检测 */
        RUNNING("…"),
        /** 命中并已装上 */
        OK("✓"),
        /** 没找到合适落点，保持 inert */
        MISS("×"),
        /** 按设计显式关闭（该版本无可信锚点） */
        DISABLED("–"),
        /** 检测到落点但安装失败 */
        FAIL("!");

        public final String glyph;

        Status(String glyph) {
            this.glyph = glyph;
        }
    }

    public static final class Item {
        public final HookPoint point;
        public Status status = Status.PENDING;
        public String detail = "";
        public long startedAt;
        public long elapsedMs;

        Item(HookPoint point) {
            this.point = point;
        }

        public boolean done() {
            return status != Status.PENDING && status != Status.RUNNING;
        }
    }

    private static final String TAG = "NnnHook.Progress";

    private final Map<HookPoint, Item> items = new EnumMap<>(HookPoint.class);
    private String targetPkg = "";
    private int dexCount = -1;

    public AdaptProgress() {
        reset("");
    }

    public synchronized AdaptProgress reset(String pkg) {
        items.clear();
        for (HookPoint p : HookPoint.values()) {
            items.put(p, new Item(p));
        }
        targetPkg = pkg == null ? "" : pkg;
        dexCount = -1;
        return this;
    }

    public synchronized void target(String pkg, int dex) {
        targetPkg = pkg == null ? "" : pkg;
        dexCount = dex;
    }

    public synchronized void begin(HookPoint p) {
        Item it = items.get(p);
        if (it == null) return;
        if (it.status == Status.RUNNING) return;
        it.status = Status.RUNNING;
        it.startedAt = SystemClock.elapsedRealtime();
    }

    public void ok(HookPoint p, String detail) {
        finish(p, Status.OK, detail);
    }

    public void miss(HookPoint p, String detail) {
        finish(p, Status.MISS, detail);
    }

    public void disabled(HookPoint p, String detail) {
        finish(p, Status.DISABLED, detail);
    }

    public void fail(HookPoint p, String detail) {
        finish(p, Status.FAIL, detail);
    }

    /**
     * 落状态。后写赢：同一个点可能先由识别层给"可安装"，再由安装层改成真实结果。
     */
    private synchronized void finish(HookPoint p, Status st, String detail) {
        Item it = items.get(p);
        if (it == null) return;
        it.status = st;
        it.detail = detail == null ? "" : detail;
        it.elapsedMs = it.startedAt == 0 ? 0 : SystemClock.elapsedRealtime() - it.startedAt;
    }

    public synchronized List<Item> snapshot() {
        return new ArrayList<>(items.values());
    }

    public synchronized Item get(HookPoint p) {
        return items.get(p);
    }

    /** 已出结果（OK/MISS/DISABLED/FAIL）的点数。 */
    public synchronized int doneCount() {
        int n = 0;
        for (Item it : items.values()) {
            if (it.done()) n++;
        }
        return n;
    }

    public synchronized int total() {
        return items.size();
    }

    public synchronized int okCount() {
        int n = 0;
        for (Item it : items.values()) {
            if (it.status == Status.OK) n++;
        }
        return n;
    }

    public synchronized String targetPkg() {
        return targetPkg;
    }

    public synchronized int dexCount() {
        return dexCount;
    }

    /** 一行汇总，给 Toast 用。 */
    public synchronized String summaryLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("NnnHook ").append(okCount()).append('/').append(total()).append(' ');
        for (Item it : items.values()) {
            sb.append(it.point.title).append(it.status.glyph).append(' ');
        }
        return sb.toString().trim();
    }

    /** 多行完整报告，给 logcat 与自检页用。 */
    public synchronized String fullReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("target=").append(targetPkg)
                .append(" dex=").append(dexCount)
                .append(" ok=").append(okCount()).append('/').append(total())
                .append('\n');
        for (Item it : items.values()) {
            sb.append(it.status.glyph).append(' ')
                    .append('[').append(it.point.code).append("] ")
                    .append(it.point.title);
            if (it.elapsedMs > 0) sb.append(" (").append(it.elapsedMs).append("ms)");
            if (!it.detail.isEmpty()) sb.append(" — ").append(it.detail);
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** 每行都单独走一次日志，避免单条太长被截断。 */
    public synchronized void log() {
        L.i(TAG, "适配报告 target=" + targetPkg + " dex=" + dexCount
                + " ok=" + okCount() + "/" + total());
        for (Item it : items.values()) {
            String line = it.status.glyph + " [" + it.point.code + "] " + it.point.title
                    + (it.elapsedMs > 0 ? " (" + it.elapsedMs + "ms)" : "")
                    + (it.detail.isEmpty() ? "" : " — " + it.detail);
            L.i(TAG, line);
        }
    }
}
