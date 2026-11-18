package io.nnnhook;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.FieldDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import android.content.Context;

import java.io.File;
import java.util.Arrays;

/**
 * 上限落点深挖：之前只查了"谁读这个字段"（0 命中），没查"字段定义在哪个类"。
 *
 * 两个可能性要分清：
 *   A. 字段真实存在于某个类 → 直接 hook 读它的方法，或 hook 该类反序列化改字段
 *   B. 只是 JSON key → 客户端从 SharedPreferences 的 JSON 里取，只能改配置
 *
 * 顺带把 s6（引用全部上限 key 的类）的字段结构 dump 出来。
 */
@RunWith(AndroidJUnit4.class)
public class LimitFieldProbeTest {

    private static final String TAG = "NnnHook.LimitField";

    @Test
    public void probeFieldDefinitions() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File apk = new File(ctx.getExternalFilesDir(null), "target.apk");
        assertTrue("缺少 target.apk", apk.exists());

        byte[][] dex = DexSource.fromApk(apk);
        System.loadLibrary("dexkit");
        DexKitBridge bridge = DexKitBridge.create(dex);
        assertNotNull(bridge);

        // ── A. 字段定义在哪 ──────────────────────────────────────
        String[] keys = {"dialog_filters", "pinned_dialogs", "filters_limit"};
        for (String k : keys) {
            FieldDataList fields = bridge.findField(FindField.create()
                    .searchPackages(Arrays.asList("org.telegram"))
                    .matcher(FieldMatcher.create()
                            .name(k, StringMatchType.Contains, false)));
            int n = fields == null ? 0 : fields.size();
            L.i(TAG, "FIELDDEF *" + k + "* = " + n);
            if (fields != null) {
                int i = 0;
                for (FieldData f : fields) {
                    if (i++ >= 12) break;
                    L.i(TAG, "   " + f.getTypeName() + " "
                            + f.getDeclaredClassName() + "." + f.getName());
                }
            }
        }

        // ── B. s6 是什么、有哪些字段 ────────────────────────────
        ClassDataList s6 = bridge.findClass(FindClass.create()
                .searchPackages(Arrays.asList("org.telegram.messenger"))
                .matcher(ClassMatcher.create().className("org.telegram.messenger.s6")));
        L.i(TAG, "class s6 found=" + (s6 == null ? 0 : s6.size()));
        if (s6 != null) {
            for (ClassData cd : s6) {
                StringBuilder ints = new StringBuilder();
                int cnt = 0;
                for (FieldData f : cd.getFields()) {
                    if ("int".equals(f.getTypeName())) {
                        cnt++;
                        if (ints.length() < 300) ints.append(f.getName()).append(' ');
                    }
                }
                L.i(TAG, "  s6 int fields(" + cnt + "): " + ints);
                L.i(TAG, "  s6 method count=" + cd.getMethods().size());
                int i = 0;
                for (MethodData m : cd.getMethods()) {
                    if (i++ >= 12) break;
                    L.i(TAG, "   m: " + m.getReturnTypeName() + " " + m.getName()
                            + "/" + m.getParamCount() + " " + m.getParamTypeNames());
                }
            }
        }

        // ── C. 谁调用 s6#...（引用全部上限 key 的那个方法）───────
        MethodDataList readers = bridge.findMethod(FindMethod.create()
                .searchPackages(Arrays.asList("org.telegram"))
                .matcher(MethodMatcher.create().usingStrings("dialog_filters_limit_default")));
        if (readers != null) {
            for (MethodData d : readers) {
                L.i(TAG, "  keyReader: " + d.getReturnTypeName() + " "
                        + d.getClassName() + "#" + d.getName() + "/" + d.getParamCount());
            }
        }

        L.i(TAG, "LIMIT FIELD PROBE DONE");
    }
}
