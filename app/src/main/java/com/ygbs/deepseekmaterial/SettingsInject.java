package com.ygbs.deepseekmaterial;

import android.widget.Toast;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 往 DeepSeek 的设置页里插入一行「Material 设计」（整行可点，不带开关）。
 *
 * <p><b>槽位映射（探针实测 + 截图核对）</b>：SettingItem 的四个 me4 槽位渲染顺序是 5 → 9 → 6 → 7：
 * <pre>
 *   [5 = leading 图标] [9 = 标题] ......... [6 = 尾部灰字] [7 = 尾部箭头]
 * </pre>
 * 我们只提供 9（标题）和 7（复用 App 自己那行的箭头 lambda {@code aj7.g}），其余走默认值。
 * 点击整行即切换，不做开关控件。
 */
public final class SettingsInject {

    private static final int ARG_COMPOSER = 10;
    /** defaults：让 0,2,3,4,5,6,8 用默认值，只提供 1(onClick)/7(箭头)/9(标题) */
    private static final int DEFAULTS = 381;

    private static ClassLoader sCl;
    private static Method sSettingItem;
    private static Method sText;

    private static volatile boolean sInsertedThisPass;
    private static final ThreadLocal<Boolean> INSIDE = new ThreadLocal<>();
    private static Object sUnit;
    private static Object sChevron;
    private static android.content.Context sContext;

    private SettingsInject() {
    }

    public static void install(XposedInterface api, ClassLoader cl) {
        sCl = cl;

        sSettingItem = ComposeGlue.method(cl, ComposeGlue.SETTING_ITEM, "b",
                ComposeGlue.MODIFIER, ComposeGlue.F0, "boolean", "boolean", "long",
                ComposeGlue.F2, ComposeGlue.F2, ComposeGlue.F2, "float", ComposeGlue.F2,
                ComposeGlue.COMPOSER, "int", "int");
        sText = ComposeGlue.method(cl, ComposeGlue.TEXT, "b",
                "java.lang.String", ComposeGlue.MODIFIER, "long", "fd0", "long", "w74", "long", "ky9",
                "long", "int", "boolean", "int", "int", "b3a", ComposeGlue.COMPOSER,
                "int", "int", "int");
        sChevron = staticField(cl, "aj7", "g");
        XLog.i("SettingsInject: SettingItem=" + (sSettingItem != null)
                + " Text=" + (sText != null) + " 箭头lambda=" + sChevron);

        if (sSettingItem == null || sText == null) {
            XLog.e("关键方法没找到，放弃设置项注入");
            return;
        }

        try {
            api.hook(sSettingItem)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("dsmat:settingitem")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(SettingsInject::onSettingItem);
            XLog.i("已 hook SettingItem(d39.b)");
        } catch (Throwable t) {
            XLog.e("hook SettingItem 失败", t);
            return;
        }

        try {
            Method page = byNameAndArity(cl, ComposeGlue.SETTINGS_PAGE, "t", 22);
            if (page != null) {
                api.hook(page)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setId("dsmat:settingspage")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            sInsertedThisPass = false;
                            return chain.proceed();
                        });
                XLog.i("已 hook SettingsPageContent(i9b.t)");
            } else {
                XLog.w("没找到 i9b.t，插入标记无法重置");
            }
        } catch (Throwable t) {
            XLog.w("hook 设置页失败: " + t);
        }
    }

    private static Object staticField(ClassLoader cl, String cls, String field) {
        try {
            return Class.forName(cls, false, cl).getField(field).get(null);
        } catch (Throwable t) {
            XLog.w("取静态字段 " + cls + "." + field + " 失败: " + t);
            return null;
        }
    }

    private static Method byNameAndArity(ClassLoader cl, String clsName, String name, int arity) {
        Class<?> c = ComposeGlue.cls(cl, clsName);
        if (c == null) {
            return null;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arity) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 插入

    private static Object onSettingItem(XposedInterface.Chain chain) throws Throwable {
        if (!Boolean.TRUE.equals(INSIDE.get()) && !sInsertedThisPass && inSettingsPage()) {
            sInsertedThisPass = true;
            try {
                Object composer = chain.getArg(ARG_COMPOSER);
                ThemeInjector.setLastComposer(composer);
                // 组合进行中：这时候作用域栈是有内容的，把根作用域抓住，供即时刷新用
                Object root = ThemeInjector.captureRootScope(composer);
                if (root != null) {
                    ThemeInjector.setRootScope(root);
                }
                insertRow(composer);
            } catch (Throwable t) {
                XLog.e("插入设置行失败", t);
            }
        }
        return chain.proceed();
    }

    private static boolean inSettingsPage() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            if (ComposeGlue.SETTINGS_PAGE.equals(e.getClassName()) && "t".equals(e.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    private static void insertRow(Object composer) {
        INSIDE.set(Boolean.TRUE);
        try {
            Object onClick = ComposeGlue.callback(sCl, SettingsInject::toggle);
            Object title = ComposeGlue.composable(sCl, (c, changed) -> {
                text(c, "Material 设计");
                return unit();
            });

            Object[] args = {
                    null,           // 0 Modifier（默认）
                    onClick,        // 1 整行点击
                    Boolean.FALSE,  // 2
                    Boolean.FALSE,  // 3
                    0L,             // 4
                    null,           // 5 leading（默认）
                    null,           // 6 尾部灰字（默认）
                    sChevron,       // 7 尾部箭头（复用 App 自己的）
                    0f,             // 8（默认）
                    title,          // 9 标题
                    composer,       // 10 Composer
                    0,              // 11 changed
                    DEFAULTS        // 12 defaults
            };
            sSettingItem.invoke(null, args);
            XLog.i("设置行已插入 | material=" + Prefs.material());
        } catch (Throwable t) {
            XLog.e("调用 SettingItem 失败", t);
        } finally {
            INSIDE.remove();
        }
    }

    private static void text(Object composer, String s) {
        try {
            sText.invoke(null, s, null, 0L, null, 0L, null, 0L, null, 0L,
                    0, Boolean.FALSE, 0, 0, null, composer, 0, 0, 262142);
        } catch (Throwable t) {
            XLog.e("渲染文字 \"" + s + "\" 失败", t);
        }
    }

    private static Object unit() {
        Object u = sUnit;
        if (u != null) {
            return u;
        }
        for (String clsName : new String[]{"via", "kotlin.Unit"}) {
            try {
                Class<?> c = Class.forName(clsName, false, sCl);
                for (String f : new String[]{"a", "INSTANCE"}) {
                    try {
                        u = c.getField(f).get(null);
                        if (u != null) {
                            sUnit = u;
                            return u;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 开关

    private static void toggle() {
        boolean on = !Prefs.material();
        Prefs.setMaterial(on);
        XLog.i("点击「Material 设计」-> " + on);
        toast("Material 设计：" + (on ? "已开启" : "已关闭"));
        ThemeInjector.setMaterial(on);
        // 立即返回上一屏（对话界面），让用户当场看到效果；不重启 App
        ThemeInjector.goBack(220);
    }

    private static void toast(String msg) {
        try {
            if (sContext != null) {
                Toast.makeText(sContext, msg, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            XLog.w("Toast 失败: " + t);
        }
    }

    public static void setContext(android.content.Context ctx) {
        sContext = ctx.getApplicationContext();
    }
}
