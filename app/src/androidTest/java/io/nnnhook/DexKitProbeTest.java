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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DexKit 可用性实证。跑在真实设备/模拟器上，把三件事从"推测"变成"实测"：
 *
 *   1. libdexkit.so 在当前 ABI 上能加载（x86_64 也要过）
 *   2. DexKitBridge.create(ClassLoader, useMemoryDexFile) 能建桥并读到 dex
 *   3. findMethod(FindMethod) + usingStrings 能真的返回方法，且 MethodData
 *      能还原成可反射调用的 Method —— 这是 Engine 识别层的地基
 *
 * 目标包用本模块自己（io.nnnhook），因为 dex 结构稳定、可控，
 * 结果可断言；Nnngram 那份要装目标 APK 才能测。
 */
@RunWith(AndroidJUnit4.class)
public class DexKitProbeTest {

    private static final String TAG = "NnnHook.Probe";

    @Test
    public void bridgeLoadsAndQueries() {
        // 1) native 库
        System.loadLibrary("dexkit");
        L.i(TAG, "libdexkit loaded on " + System.getProperty("os.arch"));

        ClassLoader cl = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getClassLoader();
        assertNotNull(cl);

        // 2) 建桥
        DexKitBridge bridge = DexKitBridge.create(cl, false);
        assertNotNull("DexKitBridge.create 返回 null", bridge);
        int dexNum = bridge.getDexNum();
        L.i(TAG, "bridge ready, dex=" + dexNum);
        assertTrue("dex 数量应 >= 1，实际 " + dexNum, dexNum >= 1);

        // 3) 字符串锚点查询：L.java 里 L.TAG = "NnnHook"，引用它的方法必须被捞出来
        MethodDataList list = bridge.findMethod(FindMethod.create()
                .searchPackages(Collections.singletonList("io.nnnhook"))
                .matcher(MethodMatcher.create().usingStrings("NnnHook")));
        assertNotNull("findMethod 返回 null", list);

        List<String> keys = new ArrayList<>();
        for (MethodData d : list) {
            keys.add(d.getClassName() + "#" + d.getName() + "/" + d.getParamCount());
        }
        L.i(TAG, "usingStrings(\"NnnHook\") hits=" + list.size() + " " + keys);

        assertTrue("usingStrings 应至少命中 1 个方法，实际 " + list.size(),
                !list.isEmpty());

        boolean sawL = false;
        for (MethodData d : list) {
            if (d.getClassName() != null && d.getClassName().equals("io.nnnhook.L")) {
                sawL = true;
                break;
            }
        }
        assertTrue("应命中 L 类里的日志方法，实际候选: " + keys, sawL);

        // 4) MethodData → Method，验证 toMethod 链路
        MethodData first = list.get(0);
        Method m = null;
        try {
            m = first.getMethodInstance(cl);
        } catch (Throwable t) {
            L.e(TAG, "getMethodInstance 失败: " + first.getClassName() + "#" + first.getName(), t);
        }
        assertNotNull("MethodData 无法还原成 Method", m);
        L.i(TAG, "method instance ok: "
                + m.getDeclaringClass().getName() + "#" + m.getName());

        L.i(TAG, "PROBE PASSED");
    }
}
