# 忽略 libxposed 编译期注解与框架接口警告
-dontwarn io.github.libxposed.**

# 保留本模块全部类、字段与方法（含 XposedModule 入口）
-keep class cn.ltingyuanfang.simunlockmoudle.** { *; }

# 保留 libxposed API 与 Service（含 XposedProvider、AIDL 与 Parcelable）
-keep class io.github.libxposed.** { *; }
