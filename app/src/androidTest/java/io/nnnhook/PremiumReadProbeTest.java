package io.nnnhook;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.UsingType;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import android.content.Context;

import java.io.File;
import java.util.Collections;

/**
 * 会员判定的真实落点探测。
 *
 * 背景：字段置位在真机上确实执行了（日志有 premium=true），但 UI 仍不显示会员。
 * 原因推断：UI 读的是 UserConfig.currentUser（早于 hook 造成的持久对象），
 * 或者走的是 isPremium() 这类封装方法。
 *
 * 这一轮直接问 dex："谁读了 TLRPC$User.premium 这个字段"。
 * 读它的地方就是会员判定的全部位置——比字符串锚点精确得多。
 */
@RunWith(AndroidJUnit4.class)
public class PremiumReadProbeTest {

    private static final String TAG = "NnnHook.PremiumRead";

    @Test
    public void probePremiumReaders() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File apk = new File(ctx.getExternalFilesDir(null), "target.apk");
        assertTrue("缺少 target.apk", apk.exists());

        byte[][] dex = DexSource.fromApk(apk);
        System.loadLibrary("dexkit");
        DexKitBridge bridge = DexKitBridge.create(dex);
        assertNotNull(bridge);

        MethodDataList readers = bridge.findMethod(FindMethod.create()
                .searchPackages(Collections.singletonList("org.telegram"))
                .matcher(MethodMatcher.create()
                        .addUsingField(FieldMatcher.create().name("premium"), UsingType.Read)));
        int n = readers == null ? 0 : readers.size();
        L.i(TAG, "读 premium 字段的方法总数=" + n);

        int shown = 0;
        int noArgBool = 0;
        if (readers != null) {
            for (MethodData d : readers) {
                boolean isNoArgBool = "boolean".equals(d.getReturnTypeName())
                        && d.getParamCount() == 0;
                if (isNoArgBool) noArgBool++;
                if (shown++ >= 40) continue;
                L.i(TAG, (isNoArgBool ? "* " : "  ")
                        + d.getReturnTypeName() + " "
                        + d.getClassName() + "#" + d.getName()
                        + "/" + d.getParamCount() + " " + d.getParamTypeNames());
            }
        }
        L.i(TAG, "无参 boolean 形态（会员判定的标准形态）= " + noArgBool);
        L.i(TAG, "PREMIUM READ PROBE DONE");
    }
}
