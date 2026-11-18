package io.nnnhook.util;

import io.nnnhook.core.Engine;

import android.util.Log;

/**
 * 日志。统一主 tag = NnnHook，子 tag 拼在消息前缀里，
 * 这样 `adb logcat | grep NnnHook` 能一次抓全，又能看出是哪个部件打的。
 */
public final class L {

    public static final String TAG = "NnnHook";

    /** 由 "NnnHook.Engine" 之类的全名压成 "Engine"，避免每行重复 "NnnHook." */
    private static String sub(String tag) {
        if (tag == null || tag.isEmpty()) return "";
        int i = tag.lastIndexOf('.');
        return i >= 0 && i + 1 < tag.length() ? tag.substring(i + 1) : tag;
    }

    public static void i(String tag, String msg) {
        Log.i(TAG, "[" + sub(tag) + "] " + msg);
    }

    public static void w(String tag, String msg) {
        Log.w(TAG, "[" + sub(tag) + "] " + msg);
    }

    public static void w(String tag, String msg, Throwable t) {
        Log.w(TAG, "[" + sub(tag) + "] " + msg, t);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(TAG, "[" + sub(tag) + "] " + msg, t);
    }

    private L() {}
}
