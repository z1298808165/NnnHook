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

import android.content.Context;

import java.io.File;
import java.util.List;

/**
 * 赞助消息模型的落点探测。
 *
 * EngineResolveTest 里 sponsored 一直 MISS，说明 TLRPC$TL_messages_sponsoredMessages
 * 没有我们猜的 TLdeserialize。这个探测把该类和它的父类的字段/方法全列出来，
 * 看真实的落点长什么样。
 */
@RunWith(AndroidJUnit4.class)
public class SponsoredProbeTest {

    private static final String TAG = "NnnHook.SponsoredProbe";
    private static final String CLS = "org.telegram.tgnet.TLRPC$TL_messages_sponsoredMessages";
    private static final String PARENT = "org.telegram.tgnet.TLRPC$messages_SponsoredMessages";

    @Test
    public void probeSponsoredModel() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File apk = new File(ctx.getExternalFilesDir(null), "target.apk");
        assertTrue("缺少 target.apk", apk.exists());

        byte[][] dex = DexSource.fromApk(apk);
        System.loadLibrary("dexkit");
        DexKitBridge bridge = DexKitBridge.create(dex);
        assertNotNull(bridge);

        for (String cls : new String[]{CLS, PARENT}) {
            ClassDataList list = bridge.findClass(FindClass.create()
                    .searchPackages(java.util.Collections.singletonList("org.telegram.tgnet"))
                    .matcher(ClassMatcher.create().className(cls)));
            L.i(TAG, "CLASS " + cls + " found=" + (list == null ? 0 : list.size()));
            if (list == null) continue;
            for (ClassData cd : list) {
                StringBuilder sb = new StringBuilder();
                for (FieldData f : cd.getFields()) sb.append(f.getTypeName()).append(' ').append(f.getName()).append("; ");
                L.i(TAG, "  fields: " + sb);
                int n = 0;
                for (MethodData m : cd.getMethods()) {
                    if (n++ >= 15) break;
                    L.i(TAG, "  m: " + m.getReturnTypeName() + " " + m.getName()
                            + "/" + m.getParamCount() + " " + m.getParamTypeNames());
                }
                L.i(TAG, "  method count=" + cd.getMethods().size());
            }
        }

        // 反过来：找出"返回该类型"的所有方法，看客户端在哪里造它
        String[] rets = {CLS, PARENT};
        for (String r : rets) {
            org.luckypray.dexkit.result.MethodDataList l = bridge.findMethod(FindMethod.create()
                    .searchPackages(java.util.Collections.singletonList("org.telegram.tgnet"))
                    .matcher(MethodMatcher.create().returnType(r)));
            L.i(TAG, "RETURN " + r + " hits=" + (l == null ? 0 : l.size()));
            int n = 0;
            if (l != null) {
                for (MethodData d : l) {
                    if (n++ >= 10) break;
                    L.i(TAG, "   " + d.getClassName() + "#" + d.getName()
                            + "/" + d.getParamCount());
                }
            }
        }

        // 字段名探测：messages 列表在哪
        L.i(TAG, "DONE");
    }
}
