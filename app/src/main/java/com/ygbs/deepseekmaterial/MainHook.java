package com.ygbs.deepseekmaterial;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * LSPosed 模块入口（modern libxposed API 102，入口类名见 META-INF/xposed/java_init.list）。
 *
 * <p>生命周期：onModuleLoaded → onPackageReady →（挂 Application.onCreate）→ ThemeInjector.install()
 *
 * <p>注意：targetApiVersion = 102 的模块<b>不允许</b>再调用 legacy 的
 * {@code de.robv.android.xposed.*}，所以这里一律用标准反射。
 */
public final class MainHook extends XposedModule {

    public static final String TARGET_PACKAGE = "com.deepseek.chat";

    /** 需要重新倒运行时结构时改成 true（只读，不改任何东西）。 */
    private static final boolean RECON = false;

    private volatile ClassLoader mAppClassLoader;
    private volatile boolean mDone;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        XLog.init(this);
        XLog.i("模块已加载 | 进程=" + param.getProcessName()
                + " | systemServer=" + param.isSystemServer()
                + " | 框架=" + getFrameworkName() + " " + getFrameworkVersion()
                + " | API=" + getApiVersion()
                + " | 框架属性=0x" + Long.toHexString(getFrameworkProperties()));
    }

    /**
     * 用反射读字段。编译用的 android.jar 把 ApplicationInfo.versionName / longVersionCode
     * 拿掉了（编译期找不到符号），但运行时它们还在，所以走反射。
     */
    private static Object reflectField(Object target, String name) {
        try {
            Field f = target.getClass().getField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return "<" + name + " 不可读>";
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        mAppClassLoader = param.getClassLoader();
        XLog.i("目标已就绪 | 包=" + param.getPackageName()
                + " | 版本=" + reflectField(param.getApplicationInfo(), "versionName")
                + " | versionCode=" + reflectField(param.getApplicationInfo(), "longVersionCode")
                + " | 首个包=" + param.isFirstPackage());

        try {
            Method onCreate = Class.forName("android.app.Application", false, null)
                    .getDeclaredMethod("onCreate");
            hook(onCreate)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("dsmat:app-oncreate")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        if (!mDone) {
                            mDone = true;
                            Object app = chain.getThisObject();
                            try {
                                if (RECON) {
                                    Recon.run(mAppClassLoader);
                                }
                                if (app instanceof Context) {
                                    Prefs.init((Context) app);
                                    SettingsInject.setContext((Context) app);
                                    ThemeInjector.install(MainHook.this, mAppClassLoader, (Context) app);
                                    ThemeInjector.installForceHook(MainHook.this, mAppClassLoader);
                                    SettingsInject.install(MainHook.this, mAppClassLoader);
                                } else {
                                    XLog.e("拿不到 Application 上下文: " + app);
                                }
                            } catch (Throwable t) {
                                XLog.e("注入失败", t);
                            }
                        }
                        return chain.proceed();
                    });
            XLog.i("已挂 Application.onCreate 钩子");
        } catch (Throwable t) {
            XLog.e("挂钩子失败", t);
        }
    }
}
