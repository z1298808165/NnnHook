package io.nnnhook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import android.content.Context;

import java.io.File;

/**
 * 线上识别逻辑的等价验证：用目标 APK 建 DexClassLoader，
 * 跑 Engine.resolve(AdaptProgress)，看每个 HookPoint 到底落什么状态。
 *
 * 与 InfoActivity 的自检页走同一条代码路径，所以这个测试过了，
 * 自检页在真机上就一定能出结果。
 *
 * 前置：base.apk 已推到 files/target.apk
 */
@RunWith(AndroidJUnit4.class)
public class EngineResolveTest {

    private static final String TAG = "NnnHook.ResolveTest";

    @Test
    public void resolveAgainstRealApk() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File apk = new File(ctx.getExternalFilesDir(null), "target.apk");
        assertTrue("缺少目标 APK: " + apk + "（先 adb push）", apk.exists());

        AdaptProgress pg = new AdaptProgress();
        // 走与 InfoActivity 自检页完全相同的路径：拆 dex 字节 → forDexArray
        byte[][] dex = DexSource.fromApk(apk);
        Resolved r = Engine.forDexArray(apk.getName(), dex).resolve(pg);

        // 逐行输出，logcat 里能直接看到每个点的状态
        pg.log();
        for (AdaptProgress.Item it : pg.snapshot()) {
            L.i(TAG, it.point.code + " => " + it.status + " | " + it.detail);
        }

        assertTrue("建桥必须成功", pg.get(HookPoint.BRIDGE).status == AdaptProgress.Status.OK);
        assertTrue("应读完 3 个 dex", pg.dexCount() >= 3);

        // 这条是重构的核心断言：TL 反序列化落点必须命中
        assertFalse("TL 反序列化落点为空（premium 路径会全断）",
                r.userDeserializers.isEmpty());
        for (org.luckypray.dexkit.result.MethodData d : r.userDeserializers) {
            L.i(TAG, "deser: " + d.getClassName() + "#" + d.getName()
                    + " " + d.getParamTypeNames() + " -> " + d.getReturnTypeName());
        }

        // 广告落点必须命中（TL_messages_sponsoredMessages.TLdeserialize）
        assertFalse("广告落点为空", r.sponsoredDeserializers.isEmpty());

        // 明确禁用的点，状态必须是 DISABLED 而不是 MISS
        assertTrue(pg.get(HookPoint.PIN_LIMIT).status == AdaptProgress.Status.DISABLED);
        assertTrue(pg.get(HookPoint.READ_RECEIPT).status == AdaptProgress.Status.DISABLED);

        // 所有点都必须出结果，不能停在 PENDING（否则进度条卡住）
        assertTrue("有 HookPoint 停在 PENDING，进度会卡住",
                pg.doneCount() == pg.total());
    }
}
