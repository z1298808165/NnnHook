package io.nnnhook;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * 第二轮定位探测：字符串锚点全 miss 之后，改从「稳定类 + 字段」这一层找落点。
 *
 * 要回答四个问题：
 *   1. TLRPC$User / TLRPC$UserFull 里有没有 boolean 的 premium 字段（字段名是否保留）
 *   2. 同时被 "premium" 字符串引用且返回 boolean 的方法有哪些（最像会员判定的一批）
 *   3. "TLdeserialize" 这类 TL 生成方法名是否保留（比 fromConstructor 更可信的落点）
 *   4. TLRPC$User 自己有哪些方法可以挂
 */
@RunWith(AndroidJUnit4.class)
public class DexKitFieldProbeTest {

    private static final String TAG = "NnnHook.FieldProbe";

    private static final List<String> PKGS = Arrays.asList(
            "org.telegram", "xyz.nextalone", "tw.nekomimi",
            "uz.unnarsx", "app.mdgram", "momo.gram");

    private static final String USER = "org.telegram.tgnet.TLRPC$User";
    private static final String USER_FULL = "org.telegram.tgnet.TLRPC$UserFull";

    @Test
    public void probeFieldsAndDeserialize() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir = new File(ctx.getExternalFilesDir(null), "dex");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".dex"));
        assertNotNull("dex 目录不存在: " + dir, files);
        assertTrue("dex 目录为空", files.length > 0);

        byte[][] bufs = new byte[files.length][];
        for (int i = 0; i < files.length; i++) bufs[i] = readAll(files[i]);

        System.loadLibrary("dexkit");
        DexKitBridge bridge = DexKitBridge.create(bufs);
        assertNotNull(bridge);

        // ── 1. 两个 TL 类的字段 ───────────────────────────────────
        for (String cls : new String[]{USER, USER_FULL}) {
            ClassDataList list = bridge.findClass(FindClass.create()
                    .searchPackages(PKGS)
                    .matcher(ClassMatcher.create().className(cls)));
            L.i(TAG, "CLASS " + cls + " found=" + (list == null ? 0 : list.size()));
            if (list == null) continue;
            for (ClassData cd : list) {
                StringBuilder sb = new StringBuilder();
                int n = 0;
                for (FieldData f : cd.getFields()) {
                    if (!"boolean".equals(f.getTypeName())) continue;
                    if (n++ > 0) sb.append(' ');
                    sb.append(f.getName());
                }
                L.i(TAG, "  boolean fields(" + n + "): " + sb);
            }
        }

        // ── 2. premium 字符串 + 返回 boolean ──────────────────────
        MethodDataList premiumBool = bridge.findMethod(FindMethod.create()
                .searchPackages(PKGS)
                .matcher(MethodMatcher.create()
                        .usingStrings("premium")
                        .returnType("boolean")));
        int pn = premiumBool == null ? 0 : premiumBool.size();
        L.i(TAG, "PREMIUM+boolean hits=" + pn);
        int shown = 0;
        if (premiumBool != null) {
            for (MethodData d : premiumBool) {
                if (shown++ >= 12) break;
                L.i(TAG, "   " + d.getClassName() + "#" + d.getName()
                        + "/" + d.getParamCount()
                        + " params=" + d.getParamTypeNames());
            }
        }

        // ── 3. TL 生成方法名是否保留 ──────────────────────────────
        for (String m : new String[]{"TLdeserialize", "TLserialize", "deserialize"}) {
            MethodDataList l = bridge.findMethod(FindMethod.create()
                    .searchPackages(PKGS)
                    .matcher(MethodMatcher.create().name(m)));
            L.i(TAG, "NAME " + m + " hits=" + (l == null ? 0 : l.size()));
        }

        // ── 4. TLRPC$User 自己的方法（前 20 个）───────────────────
        MethodDataList um = bridge.findMethod(FindMethod.create()
                .searchPackages(PKGS)
                .matcher(MethodMatcher.create()
                        .declaredClass(ClassMatcher.create().className(USER))));
        int un = um == null ? 0 : um.size();
        L.i(TAG, "USER methods=" + un);
        shown = 0;
        if (um != null) {
            for (MethodData d : um) {
                if (shown++ >= 20) break;
                L.i(TAG, "   " + d.getReturnTypeName() + " " + d.getName()
                        + "/" + d.getParamCount() + " " + d.getParamTypeNames());
            }
        }

        L.i(TAG, "FIELD PROBE DONE");
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
            return buf;
        }
    }
}
