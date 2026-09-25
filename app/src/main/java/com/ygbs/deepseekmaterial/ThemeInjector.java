package com.ygbs.deepseekmaterial;

import android.app.Activity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 主题注入 + 开关。
 *
 * <p>为什么不去 hook 组合函数：Compose 的 Composer start/end 协议一旦被破坏就很难查，
 * 而且 {@code <get-colorSchemeV2>} 这类小函数已经被 R8 内联掉了，根本没有方法可 hook。
 * 这里走的是「改数据」而非「改逻辑」：
 *
 * <ol>
 *   <li>hook {@code pa6.C()} / {@code pa6.s()}（浅色/深色 token 构造器）——
 *       每次构造完立刻把返回的 token 树里的颜色按 Material You 改写。
 *       这样连 {@code ForceDark} 那种「当场新建一套 token」的路径也一起覆盖了。</li>
 *   <li>hook {@code o3a} 的类初始化器 + 直接改写 {@code o3a.a/b} ——
 *       那是 M3 的兜底 ColorScheme，Material 组件（Switch/Slider/ContextMenu…）的颜色来源。</li>
 * </ol>
 *
 * <p>开关切回原版时，靠 {@link ColorState} 记录的原始值精确还原。
 */
public final class ThemeInjector {

    private static final boolean TRACE = false;

    private static ClassLoader sCl;
    private static XposedInterface sApi;

    private static volatile Object sLightToken;
    private static volatile Object sDarkToken;
    private static volatile Activity sCurrentActivity;
    private static volatile Object sLastScope;
    /** 设置行组合时抓到的 Composer（jh4）。 */
    private static volatile Object sLastComposer;
    /** 组合进行中抓到的根 RecomposeScope（刷新用）。 */
    private static volatile Object sRootScope;
    private static volatile boolean sForce;

    /** 在组合进行中调用：拿到「最外层（根）重组作用域」。 */
    public static Object captureRootScope(Object composer) {
        Object e = fieldValue(composer, "E");
        if (e instanceof ArrayList) {
            ArrayList<?> list = (ArrayList<?>) e;
            if (!list.isEmpty()) {
                Object root = list.get(0);
                XLog.i("抓到根作用域 " + root + "（作用域栈深度 " + list.size() + "）");
                return root;
            }
        }
        return null;
    }

    public static void setRootScope(Object scope) {
        sRootScope = scope;
    }

    public static void setLastComposer(Object composer) {
        sLastComposer = composer;
    }

    public static Object lastComposer() {
        return sLastComposer;
    }

    /**
     * 真正的即时刷新。
     *
     * <p>难点：颜色是烘在 token 对象的普通字段里的，Compose 的「跳过（skipping）」机制
     * 不知道它们变了，所以光无效化一个作用域、子组合还是会被跳过、不会重新读颜色。
     *
     * <p>所以做三件事：
     * <ol>
     *   <li>把根作用域标记为失效（{@code q98.r(scope, null)} = invalidate(scope, instance)）</li>
     *   <li>唤醒重组器（{@code q98.i()}）</li>
     *   <li>在重组期间把 {@code Composer.shouldExecute(int, boolean)} 强制返回 true
     *       —— 等价于「关掉跳过」，整棵组合都会重新执行、重新读颜色</li>
     * </ol>
     */
    public static boolean refreshNow(String why) {
        XLog.i("refresh: " + why);
        Object scope = sRootScope != null ? sRootScope : sLastScope;
        if (scope == null) {
            XLog.w("还没抓到根作用域（先去设置页转一圈）");
            return false;
        }
        Object owner = fieldValue(scope, "a");
        if (owner == null) {
            XLog.w("scope.owner 为 null");
            return false;
        }
        boolean ok = false;
        try {
            Method r = owner.getClass().getMethod("r", scope.getClass(), Object.class);
            r.setAccessible(true);
            Object res = r.invoke(owner, scope, null);
            XLog.i("invalidate(rootScope) -> " + res);
            ok = true;
        } catch (Throwable t) {
            XLog.w("invalidate(scope) 失败: " + t);
        }
        try {
            Method i = owner.getClass().getMethod("i");
            i.setAccessible(true);
            i.invoke(owner);
            XLog.i("已唤醒重组器 " + owner.getClass().getName() + ".i()");
            ok = true;
        } catch (Throwable t) {
            XLog.w("唤醒重组器失败: " + t);
        }
        if (ok) {
            forceRecompose(700);
        }
        return ok;
    }

    /** 在一段时间内让 Composer.shouldExecute 恒为 true（关闭 skipping）。 */
    public static void forceRecompose(long ms) {
        sForce = true;
        XLog.i("强制重组窗口开启 " + ms + "ms");
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                sForce = false;
                XLog.i("强制重组窗口关闭");
            }, ms);
        } catch (Throwable t) {
            sForce = false;
        }
    }

    public static boolean shouldForce() {
        return sForce;
    }

    /**
     * 切换后自动返回上一屏（对话界面），让用户立刻看到效果。
     *
     * <p>只做一次返回事件（AndroidX 的 OnBackPressedDispatcher），<b>不重启 App、不重建 Activity</b>。
     */
    public static void goBack(long delayMs) {
        final Activity a = sCurrentActivity;
        if (a == null) {
            XLog.w("拿不到当前 Activity，无法自动返回");
            return;
        }
        XLog.i("将在 " + delayMs + "ms 后返回上一屏: " + a.getClass().getName());
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Object dispatcher = a.getClass().getMethod("getOnBackPressedDispatcher").invoke(a);
                    dispatcher.getClass().getMethod("onBackPressed").invoke(dispatcher);
                    XLog.i("已触发返回（OnBackPressedDispatcher）");
                } catch (Throwable t) {
                    XLog.w("OnBackPressedDispatcher 不可用，退回 Activity.onBackPressed: " + t);
                    try {
                        a.onBackPressed();
                        XLog.i("已触发返回（Activity.onBackPressed）");
                    } catch (Throwable t2) {
                        XLog.w("自动返回失败: " + t2);
                    }
                }
            }, delayMs);
        } catch (Throwable t) {
            XLog.w("postDelayed 失败: " + t);
        }
    }

    /** hook Composer.shouldExecute：强制重组窗口内直接返回 true。 */
    public static void installForceHook(XposedInterface api, ClassLoader cl) {
        try {
            Method se = ComposeGlue.method(cl, "jh4", "X", "int", "boolean");
            if (se == null) {
                XLog.w("没找到 Composer.shouldExecute(jh4.X)");
                return;
            }
            api.hook(se)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("dsmat:shouldexecute")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> sForce ? Boolean.TRUE : chain.proceed());
            XLog.i("已 hook Composer.shouldExecute(jh4.X)");
        } catch (Throwable t) {
            XLog.w("hook shouldExecute 失败: " + t);
        }
    }

    private ThemeInjector() {
    }

    public static void install(XposedInterface api, ClassLoader cl, android.content.Context ctx) {
        sApi = api;
        sCl = cl;
        XLog.i("---- 主题注入初始化 ----");
        Monet.load(ctx);
        if (!Monet.ready()) {
            XLog.e("系统动态色板读不到，放弃注入");
            return;
        }
        // 记住当前 Activity，供「刷新界面」用
        try {
            Method onResume = Class.forName("android.app.Activity", false, null).getDeclaredMethod("onResume");
            api.hook(onResume)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setId("dsmat:activity-onresume")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object self = chain.getThisObject();
                        if (self instanceof Activity) {
                            sCurrentActivity = (Activity) self;
                        }
                        return chain.proceed();
                    });
            XLog.i("已 hook Activity.onResume（记录当前 Activity）");
        } catch (Throwable t) {
            XLog.w("hook Activity.onResume 失败: " + t);
        }

        boolean lightOk = hookBuilder(api, cl, ThemeTargets.LIGHT_BUILDER_METHOD);
        boolean darkOk = hookBuilder(api, cl, ThemeTargets.DARK_BUILDER_METHOD);
        boolean o3aOk = hookO3aClinit(api, cl);

        // 2) 已经把 token 建出来的话，直接补一刀
        sLightToken = remapLazy(cl, ThemeTargets.LIGHT_LAZY_FIELD);
        sDarkToken = remapLazy(cl, ThemeTargets.DARK_LAZY_FIELD);
        remapO3a(cl);

        // 3) 只保留这几个根对象的原始值记录，其它（如 ForceDark 临时新建的）丢掉
        List<Object> roots = new ArrayList<>();
        roots.add(sLightToken);
        roots.add(sDarkToken);
        roots.addAll(o3aObjects(cl));
        ColorState.keepOnly(roots);
        XLog.i("ColorState 记录根对象 " + roots.size() + " 个，条目 " + ColorState.size());

        XLog.i("hook 结果: LIGHT=" + lightOk + " DARK=" + darkOk + " o3a.<clinit>=" + o3aOk);
        XLog.i("当前模式: material=" + Prefs.material());
        // 4) 按持久化的开关值立刻同步一次
        ColorState.applyMode(Prefs.material());
        XLog.i("---- 主题注入完成 ----");
    }

    // ------------------------------------------------------------------ 开关

    public static boolean isMaterial() {
        return Prefs.material();
    }

    /** 切换 / 应用模式。true = Material 设计，false = 原版。 */
    public static void setMaterial(boolean material) {
        XLog.i("setMaterial(" + material + ")");
        ColorState.applyMode(material);
        refresh("material=" + material);
    }

    /** 让界面立刻用上新的颜色。 */
    public static void refresh(String why) {
        if (refreshNow(why)) {
            return;
        }
        XLog.w("即时刷新失败（why=" + why + "）");
    }

    private static Object fieldValue(Object target, String name) {
        try {
            Field f = target.getClass().getField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setLastScope(Object scope) {
        sLastScope = scope;
    }

    public static Object lastScope() {
        return sLastScope;
    }

    public static ClassLoader appClassLoader() {
        return sCl;
    }

    // ------------------------------------------------------------------ 注入

    /** hook pa6.C() / pa6.s()，把返回的 token 树就地改写（仅在 Material 模式下）。 */
    private static boolean hookBuilder(XposedInterface api, ClassLoader cl, String method) {
        final String tag = ThemeTargets.BUILDER_CLASS + "." + method + "()";
        try {
            Class<?> c = Class.forName(ThemeTargets.BUILDER_CLASS, false, cl);
            Method m = c.getDeclaredMethod(method);
            m.setAccessible(true);
            api.hook(m)
                    .setId("dsmat:" + ThemeTargets.BUILDER_CLASS + "." + method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object token = chain.proceed();
                        if (Prefs.material()) {
                            ColorGraph.apply(token, tag, TRACE, true);
                        }
                        return token;
                    });
            XLog.i("已 hook " + tag);
            return true;
        } catch (Throwable t) {
            XLog.e("hook " + tag + " 失败", t);
            return false;
        }
    }

    /** 兜底 ColorScheme 是在 o3a 的 <clinit> 里构造的：等它跑完再改写。 */
    private static boolean hookO3aClinit(XposedInterface api, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(ThemeTargets.COLOR_SCHEME_HOLDER, false, cl);
            api.hookClassInitializer(c)
                    .setId("dsmat:" + ThemeTargets.COLOR_SCHEME_HOLDER + ".<clinit>")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        if (Prefs.material()) {
                            remapO3a(cl);
                        }
                        return r;
                    });
            XLog.i("已 hook " + ThemeTargets.COLOR_SCHEME_HOLDER + ".<clinit>");
            return true;
        } catch (Throwable t) {
            XLog.e("hook o3a.<clinit> 失败", t);
            return false;
        }
    }

    private static Object remapLazy(ClassLoader cl, String fieldName) {
        try {
            Class<?> theme = Class.forName(ThemeTargets.THEME_CLASS, true, cl);
            Field f = theme.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object lazy = f.get(null);
            if (lazy == null) {
                XLog.w("Lazy 字段 " + fieldName + " 为 null，跳过");
                return null;
            }
            Method getValue = lazy.getClass().getMethod("getValue");
            getValue.setAccessible(true);
            Object token = getValue.invoke(lazy);
            if (Prefs.material()) {
                ColorGraph.apply(token, "TOKEN(" + fieldName + ")", TRACE, true);
            }
            return token;
        } catch (Throwable t) {
            XLog.w("改写 Lazy " + fieldName + " 失败: " + t);
            return null;
        }
    }

    private static void remapO3a(ClassLoader cl) {
        for (String fieldName : new String[]{ThemeTargets.LIGHT_CS_FIELD, ThemeTargets.DARK_CS_FIELD}) {
            try {
                Class<?> c = Class.forName(ThemeTargets.COLOR_SCHEME_HOLDER, true, cl);
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object cs = f.get(null);
                ColorGraph.apply(cs, "M3SCHEME(" + fieldName + ")", TRACE, true);
            } catch (Throwable t) {
                XLog.w("改写 M3 ColorScheme " + fieldName + " 失败: " + t);
            }
        }
    }

    private static List<Object> o3aObjects(ClassLoader cl) {
        List<Object> out = new ArrayList<>();
        for (String fieldName : new String[]{ThemeTargets.LIGHT_CS_FIELD, ThemeTargets.DARK_CS_FIELD}) {
            try {
                Class<?> c = Class.forName(ThemeTargets.COLOR_SCHEME_HOLDER, true, cl);
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object cs = f.get(null);
                if (cs != null) {
                    out.add(cs);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 调试用：列出关键类的静态字段。 */
    public static String dumpStatics(ClassLoader cl, String className) {
        StringBuilder sb = new StringBuilder(className).append(": ");
        try {
            Class<?> c = Class.forName(className, false, cl);
            sb.append(Arrays.toString(c.getDeclaredFields()));
        } catch (Throwable t) {
            sb.append("读取失败 ").append(t);
        }
        return sb.toString();
    }
}
