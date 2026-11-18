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
import org.luckypray.dexkit.query.enums.UsingType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import android.content.Context;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

/**
 * "会员专属内容"的真实判定点。
 *
 * 要回答：客户端是靠哪个字段/方法决定"这条内容需要会员"的。
 * 候选：
 *   - TLRPC$TL_channel.restricted（boolean）+ restriction_reason
 *   - TLRPC$TL_chat.restricted
 *   - TLRPC$TL_message 上的 paid/stars 相关
 *
 * 如果判定最终回到 premium 字段，那会员 unlock 已经覆盖此项。
 */
@RunWith(AndroidJUnit4.class)
public class RestrictedProbeTest {

    private static final String TAG = "NnnHook.Restricted";

    @Test
    public void probeRestricted() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File apk = new File(ctx.getExternalFilesDir(null), "target.apk");
        assertTrue("缺少 target.apk", apk.exists());

        byte[][] dex = DexSource.fromApk(apk);
        System.loadLibrary("dexkit");
        DexKitBridge bridge = DexKitBridge.create(dex);
        assertNotNull(bridge);

        // 1) 哪些类带 restricted 字段
        ClassDataList chans = bridge.findClass(FindClass.create()
                .searchPackages(Collections.singletonList("org.telegram.tgnet"))
                .matcher(ClassMatcher.create()
                        .className("TLRPC$TL_channel", org.luckypray.dexkit.query.enums.StringMatchType.Equals, false)));
        if (chans != null) {
            for (ClassData cd : chans) {
                StringBuilder sb = new StringBuilder();
                for (FieldData f : cd.getFields()) {
                    String n = f.getName().toLowerCase();
                    if (n.contains("restrict") || n.contains("paid") || n.contains("premium")) {
                        sb.append(f.getTypeName()).append(' ').append(f.getName()).append("; ");
                    }
                }
                L.i(TAG, "TL_channel: " + sb);
            }
        }

        // 2) 读 restricted 字段的 boolean 无参方法（客户端"是否受限"判定）
        MethodDataList readers = bridge.findMethod(FindMethod.create()
                .searchPackages(Arrays.asList("org.telegram"))
                .matcher(MethodMatcher.create()
                        .returnType("boolean")
                        .paramCount(0)
                        .addUsingField(FieldMatcher.create().name("restricted"), UsingType.Read)));
        int n = readers == null ? 0 : readers.size();
        L.i(TAG, "(boolean,0) 读 restricted = " + n);
        if (readers != null) {
            int i = 0;
            for (MethodData d : readers) {
                if (i++ >= 10) break;
                L.i(TAG, "   " + d.getClassName() + "#" + d.getName());
            }
        }

        // 3) 所有读 restricted 的方法（不限签名），看总量
        MethodDataList all = bridge.findMethod(FindMethod.create()
                .searchPackages(Arrays.asList("org.telegram"))
                .matcher(MethodMatcher.create()
                        .addUsingField(FieldMatcher.create().name("restricted"), UsingType.Read)));
        L.i(TAG, "全部读 restricted = " + (all == null ? 0 : all.size()));

        // 4) restriction_reason 的引用
        MethodDataList reasons = bridge.findMethod(FindMethod.create()
                .searchPackages(Arrays.asList("org.telegram"))
                .matcher(MethodMatcher.create()
                        .addUsingField(FieldMatcher.create().name("restriction_reason"),
                                UsingType.Read)));
        L.i(TAG, "读 restriction_reason = " + (reasons == null ? 0 : reasons.size()));
        if (reasons != null) {
            int i = 0;
            for (MethodData d : reasons) {
                if (i++ >= 12) break;
                L.i(TAG, "   " + d.getReturnTypeName() + " "
                        + d.getClassName() + "#" + d.getName() + "/" + d.getParamCount()
                        + " params=" + d.getParamTypeNames());
            }
        }

        // 5) 同时读 premium 与 restriction_reason 的方法 —— 会员解锁是否本来就覆盖受限判定
        MethodDataList both = bridge.findMethod(FindMethod.create()
                .searchPackages(Arrays.asList("org.telegram"))
                .matcher(MethodMatcher.create()
                        .addUsingField(FieldMatcher.create().name("premium"), UsingType.Read)
                        .addUsingField(FieldMatcher.create().name("restriction_reason"),
                                UsingType.Read)));
        L.i(TAG, "同时读 premium+restriction_reason = " + (both == null ? 0 : both.size()));
        if (both != null) {
            int i = 0;
            for (MethodData d : both) {
                if (i++ >= 10) break;
                L.i(TAG, "   " + d.getReturnTypeName() + " "
                        + d.getClassName() + "#" + d.getName() + "/" + d.getParamCount()
                        + " params=" + d.getParamTypeNames());
            }
        }

        // 6) 同时读 restricted 与 restriction_reason 的方法
        MethodDataList both2 = bridge.findMethod(FindMethod.create()
                .searchPackages(Arrays.asList("org.telegram"))
                .matcher(MethodMatcher.create()
                        .addUsingField(FieldMatcher.create().name("restricted"), UsingType.Read)
                        .addUsingField(FieldMatcher.create().name("restriction_reason"),
                                UsingType.Read)));
        L.i(TAG, "同时读 restricted+restriction_reason = "
                + (both2 == null ? 0 : both2.size()));
        if (both2 != null) {
            int i = 0;
            for (MethodData d : both2) {
                if (i++ >= 10) break;
                L.i(TAG, "   " + d.getReturnTypeName() + " "
                        + d.getClassName() + "#" + d.getName() + "/" + d.getParamCount()
                        + " params=" + d.getParamTypeNames());
            }
        }

        L.i(TAG, "RESTRICTED PROBE DONE");
    }
}
