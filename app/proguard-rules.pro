# Shizuku instantiates the user service by class name in a separate (shell) process.
-keep class com.gamepadman.uhid.UhidService { *; }
-keep class com.gamepadman.uhid.DebugMain { *; }
-keep class com.gamepadman.IVirtualPad { *; }
-keep class com.gamepadman.IVirtualPad$* { *; }

# Built-in helper is started by app_process with this class name.
-keep class com.gamepadman.helper.HelperMain { *; }

# libadb loads Conscrypt / SPAKE2 reflectively.
-keep class org.conscrypt.** { *; }
-keep class io.github.muntashirakon.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn io.github.muntashirakon.**
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
