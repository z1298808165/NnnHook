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
import org.luckypray.dexkit.query.enums.StringMatchType;
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
import java.util.List;

/**
 * 剩余 10 项功能的落点探测。
 *
 * 三个思路，按可靠性排序：
 *   1. **发送层统一拦截**：`ConnectionsManager.sendRequest(TLObject, RequestDelegate)` ——
 *      拦住它，凡是目标 TL 请求就丢弃。已读回执、在线状态、截图提示都走这条路。
 *   2. **TL 模型字段**：`TLRPC$TL_config` 里带上限（pinned_dialogs_count_max、
 *      dialog_filters_limit），字段名保留，改字段就能放宽客户端校验。
 *   3. **类名/方法名枚举**：防撤回、翻译、转写各自的类在 dex 里长什么样。
 */
@RunWith(AndroidJUnit4.class)
public class FeatureProbeTest {

    private static final String TAG = "NnnHook.FeatureProbe";

    private static final List<String> TGNET = Collections.singletonList("org.telegram.tgnet");
    private static final List<String> ALL = Collections.singletonList("org.telegram");

    @Test
    public void probeLimitFields() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File apk = new File(ctx.getExternalFilesDir(null), "target.apk");
        byte[][] dex = DexSource.fromApk(apk);
        System.loadLibrary("dexkit");
        DexKitBridge bridge = DexKitBridge.create(dex);
        assertNotNull(bridge);

        String[] names = {
                "pinned_dialogs_count_max", "pinned_dialogs_count_max_default",
                "pinned_dialogs_count_max_premium",
                "dialog_filters_limit", "dialog_filters_limit_default",
                "dialog_filters_chats_limit_default", "pinned_limit",
        };

        for (String n : names) {
            // a) 作为字段被读
            MethodDataList byField = bridge.findMethod(FindMethod.create()
                    .searchPackages(ALL)
                    .matcher(MethodMatcher.create()
                            .addUsingField(FieldMatcher.create()
                                    .name(n, StringMatchType.Contains, false), UsingType.Any)));
            // b) 作为字符串被引用（反射/JSON key）
            MethodDataList byStr = bridge.findMethod(FindMethod.create()
                    .searchPackages(ALL)
                    .matcher(MethodMatcher.create().usingStrings(n)));
            L.i(TAG, n + "  fieldRef=" + (byField == null ? 0 : byField.size())
                    + "  stringRef=" + (byStr == null ? 0 : byStr.size()));
            if (byField != null) {
                int i = 0;
                for (MethodData d : byField) {
                    if (i++ >= 4) break;
                    L.i(TAG, "   field: " + d.getReturnTypeName() + " "
                            + d.getClassName() + "#" + d.getName() + "/" + d.getParamCount());
                }
            }
            if (byStr != null) {
                int i = 0;
                for (MethodData d : byStr) {
                    if (i++ >= 4) break;
                    L.i(TAG, "   str: " + d.getReturnTypeName() + " "
                            + d.getClassName() + "#" + d.getName() + "/" + d.getParamCount());
                }
            }
        }
        // 类定义里到底有没有这些字段
        ClassDataList cfg = bridge.findClass(FindClass.create()
                .searchPackages(Arrays.asList("org.telegram.tgnet", "org.telegram.messenger"))
                .matcher(ClassMatcher.create()
                        .className("config", StringMatchType.Contains, false)));
        L.i(TAG, "config-like classes=" + (cfg == null ? 0 : cfg.size()));
        if (cfg != null) {
            int i = 0;
            for (ClassData cd : cfg) {
                if (i++ >= 6) break;
                StringBuilder sb = new StringBuilder();
                for (FieldData f : cd.getFields()) {
                    String fn = f.getName().toLowerCase();
                    if (fn.contains("pin") || fn.contains("filter")) {
                        sb.append(f.getName()).append(' ');
                    }
                }
                if (sb.length() > 0) L.i(TAG, "  " + cd.getName() + ": " + sb);
            }
        }
        L.i(TAG, "LIMIT FIELD PROBE DONE");
    }

    @Test
    public void probeAll() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File apk = new File(ctx.getExternalFilesDir(null), "target.apk");
        assertTrue("缺少 target.apk", apk.exists());

        byte[][] dex = DexSource.fromApk(apk);
        System.loadLibrary("dexkit");
        DexKitBridge bridge = DexKitBridge.create(dex);
        assertNotNull(bridge);

        // ── 1. 发送层：ConnectionsManager#sendRequest ─────────────
        ClassDataList cm = bridge.findClass(FindClass.create()
                .searchPackages(TGNET)
                .matcher(ClassMatcher.create().className("org.telegram.tgnet.ConnectionsManager")));
        L.i(TAG, "ConnectionsManager found=" + (cm == null ? 0 : cm.size()));
        if (cm != null) {
            for (ClassData cd : cm) {
                StringBuilder names = new StringBuilder();
                for (MethodData m : cd.getMethods()) {
                    String n = m.getName();
                    if (n.toLowerCase().contains("send") || n.toLowerCase().contains("request")) {
                        names.append(n).append('/').append(m.getParamCount())
                                .append(" ").append(m.getParamTypeNames()).append("; ");
                    }
                }
                L.i(TAG, "  CM send-like: " + names);
            }
        }
        // 全局按参数找：第一个参数是 TLObject 的 sendRequest 类方法
        MethodDataList senders = bridge.findMethod(FindMethod.create()
                .searchPackages(TGNET)
                .matcher(MethodMatcher.create()
                        .paramCount(2)
                        .paramTypes("org.telegram.tgnet.TLObject",
                                "org.telegram.tgnet.RequestDelegate")));
        L.i(TAG, "sendRequest(TLObject,RequestDelegate) hits=" + (senders == null ? 0 : senders.size()));
        if (senders != null) {
            int i = 0;
            for (MethodData d : senders) {
                if (i++ >= 6) break;
                L.i(TAG, "   " + d.getReturnTypeName() + " " + d.getClassName() + "#" + d.getName());
            }
        }

        // ── 2. TLRPC$TL_config 的上限字段 ─────────────────────────
        ClassDataList cfg = bridge.findClass(FindClass.create()
                .searchPackages(TGNET)
                .matcher(ClassMatcher.create().className("org.telegram.tgnet.TLRPC$TL_config")));
        L.i(TAG, "TL_config found=" + (cfg == null ? 0 : cfg.size()));
        if (cfg != null) {
            for (ClassData cd : cfg) {
                StringBuilder sb = new StringBuilder();
                for (FieldData f : cd.getFields()) {
                    String n = f.getName().toLowerCase();
                    if (n.contains("pin") || n.contains("dialog") || n.contains("filter")
                            || n.contains("folder") || n.contains("group")) {
                        sb.append(f.getTypeName()).append(' ').append(f.getName()).append("; ");
                    }
                }
                L.i(TAG, "  config limits: " + sb);
            }
        }
        // 父类 config 上也有一份
        ClassDataList cfgParent = bridge.findClass(FindClass.create()
                .searchPackages(TGNET)
                .matcher(ClassMatcher.create().className("org.telegram.tgnet.TLRPC$config")));
        if (cfgParent != null) {
            for (ClassData cd : cfgParent) {
                StringBuilder sb = new StringBuilder();
                for (FieldData f : cd.getFields()) {
                    String n = f.getName().toLowerCase();
                    if (n.contains("pin") || n.contains("dialog") || n.contains("filter")
                            || n.contains("folder")) {
                        sb.append(f.getTypeName()).append(' ').append(f.getName()).append("; ");
                    }
                }
                L.i(TAG, "  TL_config parent fields: " + sb);
            }
        }

        // ── 3. 防撤回：类名里带 Delete 的 ────────────────────────
        ClassDataList del = bridge.findClass(FindClass.create()
                .searchPackages(TGNET)
                .matcher(ClassMatcher.create()
                        .className("DeleteMessages", StringMatchType.Contains, false)));
        L.i(TAG, "class *DeleteMessages* = " + (del == null ? 0 : del.size()));
        if (del != null) {
            int i = 0;
            for (ClassData d : del) {
                if (i++ >= 8) break;
                L.i(TAG, "   " + d.getName());
            }
        }

        // ── 4. 在线状态 / 截图 / 已读 的 TL 类 ───────────────────
        String[] wants = {
                "UpdateStatus", "updateStatus", "Screenshot", "readHistory",
                "Translate", "Transcribe", "transcribe",
        };
        for (String w : wants) {
            ClassDataList l = bridge.findClass(FindClass.create()
                    .searchPackages(ALL)
                    .matcher(ClassMatcher.create()
                            .className(w, StringMatchType.Contains, false)));
            L.i(TAG, "class *" + w + "* = " + (l == null ? 0 : l.size()));
            if (l != null) {
                int i = 0;
                for (ClassData d : l) {
                    if (i++ >= 5) break;
                    L.i(TAG, "   " + d.getName());
                }
            }
        }

        L.i(TAG, "FEATURE PROBE DONE");
    }
}
