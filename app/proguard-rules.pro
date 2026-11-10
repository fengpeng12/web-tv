# 自定义 R8 规则（release / R8 构建时由掌造引擎合并进 rules-release.pro）

# androidx.startup 的 R$string 被 R8 误判为死代码整类删除，
# 但 InitializationProvider.onCreate() 运行时仍按名字引用，导致 NoClassDefFoundError 崩溃。
-keep class androidx.startup.R$* { *; }

# 兜底：InitializationProvider 是清单合并自动注册的 ContentProvider，运行时反射按名加载
-keep class androidx.startup.InitializationProvider { *; }
