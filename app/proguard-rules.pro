# libxposed 的 api aar 自带注解，不需要额外 keep
-keep class io.github.libxposed.api.** { *; }

# DexKit 的 native 桥与结果类必须保留（JNI 按名查找）
-keep class org.luckypray.dexkit.** { *; }
-keepclassmembers class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

# kotlin 元数据在 DexKit 的查询 DSL 里被用到
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.jvm.functions.** { *; }
-dontwarn kotlin.**

# 本模块的入口与锚点不要被裁
-keep class io.nnnhook.Entry { *; }
-keep class io.nnnhook.Anchor { *; }
