package io.nnnhook;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * 第三轮探测：给 limit / privacy 找活锚点。
 *
 * 已知（前两轮实测）：
 *   - is_premium / premium_subscription / premiumPromo / pinned_messages / pin_limit /
 *     folder_limit / messages.readHistory / readMentions / updateRead 全部 usingStrings 命中 0
 *   - premium / pinned / limit / folder 这些泛词是活的
 *   - TLRPC$User 字段名保留，premium:boolean 在
 *
 * 这一轮要定量：泛词 + 返回类型 + 参数个数交叉之后，还剩多少候选、长什么样。
 */
@RunWith(AndroidJUnit4.class)
public class DexKitLimitProbeTest {

    private static final String TAG = "NnnHook.LimitProbe";

    private static final List<String> PKGS = Arrays.asList(
            "org.telegram", "xyz.nextalone", "tw.nekomimi",
            "uz.unnarsx", "app.mdgram", "momo.gram");

    private static final String USER = "org.telegram.tgnet.TLRPC$User";

    @Test
    public void probeLimitAndPrivacy() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir = new File(ctx.getExternalFilesDir(null), "dex");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".dex"));
        assertNotNull("dex 目录不存在", files);
        assertTrue("dex 目录为空", files.length > 0);

        byte[][] bufs = new byte[files.length][];
        for (int i = 0; i < files.length; i++) bufs[i] = readAll(files[i]);

        System.loadLibrary("dexkit");
        DexKitBridge bridge = DexKitBridge.create(bufs);
        assertNotNull(bridge);

        // ── A. 泛词 + int + 无参：上限 getter 的形状 ──────────────
        String[] limitWords = {"pinned", "limit", "folder", "dialog_filters"};
        for (String w : limitWords) {
            MethodDataList l = bridge.findMethod(FindMethod.create()
                    .searchPackages(PKGS)
                    .matcher(MethodMatcher.create()
                            .usingStrings(w)
                            .returnType("int")
                            .paramCount(0)));
            int n = l == null ? 0 : l.size();
            L.i(TAG, "LIMIT " + w + " (int,0) hits=" + n);
            int shown = 0;
            if (l != null) {
                for (MethodData d : l) {
                    if (shown++ >= 6) break;
                    L.i(TAG, "   " + d.getClassName() + "#" + d.getName());
                }
            }
        }

        // ── B. 已读上报：换一批候选词 ────────────────────────────
        String[] readWords = {
                "markAsRead", "readMessages", "messages.readMessages",
                "sendRead", "readContents", "readHistory",
        };
        for (String w : readWords) {
            MethodDataList l = bridge.findMethod(FindMethod.create()
                    .searchPackages(PKGS)
                    .matcher(MethodMatcher.create().usingStrings(w)));
            int n = l == null ? 0 : l.size();
            L.i(TAG, "READSTR " + w + " hits=" + n);
            int shown = 0;
            if (l != null) {
                for (MethodData d : l) {
                    if (shown++ >= 4) break;
                    L.i(TAG, "   " + d.getReturnTypeName() + " "
                            + d.getClassName() + "#" + d.getName()
                            + "/" + d.getParamCount());
                }
            }
        }

        // ── C. (TLRPC$User) -> boolean 的全部命中：会员判定候选 ───
        MethodDataList byUser = bridge.findMethod(FindMethod.create()
                .searchPackages(PKGS)
                .matcher(MethodMatcher.create()
                        .returnType("boolean")
                        .paramCount(1)
                        .paramTypes(new String[]{USER})));
        int cn = byUser == null ? 0 : byUser.size();
        L.i(TAG, "SHAPE (User)->boolean hits=" + cn);
        int shown = 0;
        if (byUser != null) {
            for (MethodData d : byUser) {
                if (shown++ >= 20) break;
                L.i(TAG, "   " + d.getClassName() + "#" + d.getName()
                        + " params=" + d.getParamTypeNames());
            }
        }

        // ── D. TLRPC$User 上所有 boolean 无参方法（字段 getter 风格）──
        MethodDataList getters = bridge.findMethod(FindMethod.create()
                .searchPackages(PKGS)
                .matcher(MethodMatcher.create()
                        .declaredClass(ClassMatcher.create().className(USER))
                        .returnType("boolean")
                        .paramCount(0)));
        L.i(TAG, "USER boolean/0 hits=" + (getters == null ? 0 : getters.size()));
        shown = 0;
        if (getters != null) {
            for (MethodData d : getters) {
                if (shown++ >= 10) break;
                L.i(TAG, "   " + d.getName());
            }
        }

        L.i(TAG, "LIMIT PROBE DONE");
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
