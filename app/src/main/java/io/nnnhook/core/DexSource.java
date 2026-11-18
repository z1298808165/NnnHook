package io.nnnhook.core;

import io.nnnhook.util.L;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从 APK 里拆 dex。
 *
 * 为什么不用 DexClassLoader：Android 14+ 会拒绝从可写目录加载 dex
 * （SecurityException: Writable dex file is not allowed），而自检的 APK
 * 一定放在 cache/sdcard 这类可写位置。DexKit 支持直接吃 dex 字节，
 * 所以干脆把 classes*.dex 拆出来喂给它，绕开整个 DexClassLoader 路径。
 */
public final class DexSource {

    private static final String TAG = "NnnHook.DexSource";

    private DexSource() {}

    public static byte[][] fromApk(File apk) throws IOException {
        List<byte[]> out = new ArrayList<>();
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!name.matches("classes\\d*\\.dex")) continue;
                try (InputStream in = zf.getInputStream(e)) {
                    out.add(readAll(in, (int) Math.max(e.getSize(), 0)));
                }
            }
        }
        if (out.isEmpty()) throw new IOException("APK 里没有 classes*.dex: " + apk.getName());
        L.i(TAG, "dex 提取完成: " + out.size() + " 个，来自 " + apk.getName());
        return out.toArray(new byte[0][]);
    }

    private static byte[] readAll(InputStream in, int hint) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(hint > 0 ? hint : 8192);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
