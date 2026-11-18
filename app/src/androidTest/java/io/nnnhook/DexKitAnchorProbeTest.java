package io.nnnhook;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 锚点实测。对**真实目标 APK 的 dex** 跑 usingStrings，输出每个候选锚点的命中数与落点，
 * 用来判断 Anchor 里哪些字符串是活的、哪些是死的、活的那些又会捞到什么东西。
 *
 * 前置：三个 classes*.dex 已推到
 *   /sdcard/Android/data/io.nnnhook/files/dex/
 * 由宿主侧的 adb push 完成（dex 取自 xyz.nextalone.nnngram 的 base.apk）。
 *
 * 这个测试只产出数据，不做业务断言——它是拿来定锚点的量具，不是功能测试。
 */
@RunWith(AndroidJUnit4.class)
public class DexKitAnchorProbeTest {

    private static final String TAG = "NnnHook.AnchorProbe";

    private static final List<String> PKGS = Arrays.asList(
            "org.telegram", "xyz.nextalone", "tw.nekomimi",
            "uz.unnarsx", "app.mdgram", "momo.gram");

    /** 当前 Anchor 里在用的锚点 + 几个待评估的替代品。 */
    private static final String[] ANCHORS = {
            "is_premium",
            "isPremium",
            "premium_subscription",
            "premiumPromo",
            "premium_",
            "pinned_messages",
            "pinned",
            "pin_limit",
            "folder_limit",
            "dialog_filters",
            "messages.readHistory",
            "readMentions",
            "updateRead",
            "fromConstructor",
    };

    @Test
    public void probeRealAnchors() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir = new File(ctx.getExternalFilesDir(null), "dex");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".dex"));
        assertNotNull("dex 目录不存在: " + dir, files);
        assertTrue("dex 目录为空: " + dir + "（需要先 adb push）", files.length > 0);

        byte[][] bufs = new byte[files.length][];
        long total = 0;
        for (int i = 0; i < files.length; i++) {
            bufs[i] = readAll(files[i]);
            total += bufs[i].length;
        }
        L.i(TAG, "loaded dex=" + files.length + " bytes=" + total);

        System.loadLibrary("dexkit");
        DexKitBridge bridge = DexKitBridge.create(bufs);
        assertNotNull(bridge);
        L.i(TAG, "bridge dex=" + bridge.getDexNum());

        for (String anchor : ANCHORS) {
            MethodDataList list;
            try {
                list = bridge.findMethod(FindMethod.create()
                        .searchPackages(PKGS)
                        .matcher(MethodMatcher.create().usingStrings(anchor)));
            } catch (Throwable t) {
                L.e(TAG, "anchor=" + anchor + " 查询异常", t);
                continue;
            }
            int n = list == null ? 0 : list.size();
            L.i(TAG, "ANCHOR " + anchor + " hits=" + n);

            // 打印前 4 个落点，带返回类型与参数个数，方便判断能不能直接 hook
            int shown = 0;
            List<String> shapeHits = new ArrayList<>();
            if (list != null) {
                for (MethodData d : list) {
                    if (shown < 4) {
                        L.i(TAG, "   " + d.getReturnTypeName() + " "
                                + d.getClassName() + "#" + d.getName()
                                + "/" + d.getParamCount());
                        shown++;
                    }
                    if ("boolean".equals(d.getReturnTypeName()) && d.getParamCount() == 0) {
                        shapeHits.add(d.getClassName() + "#" + d.getName());
                    }
                }
            }
            L.i(TAG, "   shape(boolean,0args)=" + shapeHits.size()
                    + (shapeHits.isEmpty() ? "" : " " + shapeHits.subList(0, Math.min(3, shapeHits.size()))));
        }

        L.i(TAG, "ANCHOR PROBE DONE");
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
