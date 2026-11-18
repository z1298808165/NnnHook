package io.nnnhook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * 缓存与落点反射的往返验证。
 *
 * 三件事必须成立，否则"已适配就不重复适配"这条逻辑不成立：
 *   1. 指纹能生成（拿得到本包 PackageInfo）
 *   2. 落点标识能写进去、原样读出来，指纹不符就读不到
 *   3. 落点标识能反射回 Method（下次启动靠它装 hook）
 */
@RunWith(AndroidJUnit4.class)
public class AdaptCacheTest {

    private static final String TAG = "NnnHook.CacheTest";

    @Test
    public void cacheRoundTrip() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

        String fp = AdaptCache.fingerprint(ctx, ctx.getPackageName());
        assertNotNull("指纹生成失败", fp);
        L.i(TAG, "fingerprint=" + fp);

        List<String> in = Arrays.asList(
                "premium|io.nnnhook.L|i|java.lang.String,java.lang.String",
                "sponsored|io.nnnhook.L|w|java.lang.String,java.lang.String");

        AdaptCache.save(ctx, fp, in);

        List<String> out = AdaptCache.load(ctx, fp);
        assertNotNull("同指纹读不到缓存", out);
        assertEquals("缓存内容与写入不一致", in, out);

        List<String> miss = AdaptCache.load(ctx, fp + "|changed");
        assertNull("指纹变了还能读到缓存，会用到过期落点", miss);

        // 落点标识 → Method
        Method m = TypeNames.toMethod(in.get(0).substring("premium|".length()),
                ctx.getClassLoader());
        assertNotNull("落点标识无法反射成 Method", m);
        assertEquals("i", m.getName());
        L.i(TAG, "resolved: " + m.getDeclaringClass().getName() + "#" + m.getName());

        AdaptCache.clear(ctx);
        assertNull("清缓存后仍能读到", AdaptCache.load(ctx, fp));
        L.i(TAG, "CACHE TEST PASSED");
    }
}
