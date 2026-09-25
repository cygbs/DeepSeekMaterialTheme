package com.ygbs.deepseekmaterial;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * Compose 侧反射胶水。
 *
 * <p>关键事实（都是从反编译产物里核出来的，见 README/analysis）：
 * <pre>
 *   jh4  = androidx.compose.runtime.Composer        （组合器）
 *   me4  = Function2&lt;Composer, Integer, Object&gt;     （组合 lambda：r(composer, changed)）
 *   wd4  = Function0&lt;Object&gt;                        （w()）
 *   yd4  = Function1&lt;Object, Object&gt;                （onCheckedChange 之类的单参回调）
 *   ot6  = androidx.compose.ui.Modifier（lt6 = Modifier.Companion，toString()=="Modifier"）
 *   d39.b = SettingItem（设置行）
 *   b2a.b = Text（文字）
 *   pb7.q = Switch（开关控件）
 * </pre>
 *
 * <p>模块跑在自己的 classloader 里，<b>不能</b>直接 implements 目标 App 的接口，
 * 所以组合 lambda / 回调一律用 {@link Proxy} 在 App 的 classloader 里动态生成。
 */
public final class ComposeGlue {

    public static final String COMPOSER = "jh4";
    public static final String MODIFIER = "ot6";
    public static final String F2 = "me4";   // 组合 lambda
    public static final String F0 = "wd4";   // 无参回调
    public static final String F1 = "yd4";   // 单参回调
    public static final String SETTING_ITEM = "d39";
    public static final String TEXT = "b2a";
    public static final String SWITCH = "pb7";
    public static final String SETTINGS_PAGE = "i9b";

    private static final Map<String, Class<?>> CACHE = new HashMap<>();

    private ComposeGlue() {
    }

    public static Class<?> cls(ClassLoader cl, String name) {
        Class<?> c = CACHE.get(name);
        if (c != null) {
            return c;
        }
        try {
            c = Class.forName(name, false, cl);
        } catch (Throwable t) {
            c = null;
        }
        CACHE.put(name, c);
        return c;
    }

    public static Method method(ClassLoader cl, String clsName, String name, String... paramTypeNames) {
        Class<?> c = cls(cl, clsName);
        if (c == null) {
            return null;
        }
        Class<?>[] types = new Class<?>[paramTypeNames.length];
        for (int i = 0; i < types.length; i++) {
            String n = paramTypeNames[i];
            switch (n) {
                case "boolean":
                    types[i] = boolean.class;
                    break;
                case "int":
                    types[i] = int.class;
                    break;
                case "long":
                    types[i] = long.class;
                    break;
                case "float":
                    types[i] = float.class;
                    break;
                default:
                    types[i] = cls(cl, n);
                    if (types[i] == null) {
                        return null;
                    }
            }
        }
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && sameTypes(m.getParameterTypes(), types)) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static boolean sameTypes(Class<?>[] a, Class<?>[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 组合协议

    /** Composer.startRestartGroup(int) —— 反编译里是 jh4.i0(int)。 */
    public static boolean startGroup(ClassLoader cl, Object composer, int key) {
        return invokeNoReturn(method(cl, COMPOSER, "i0", "int"), composer, key);
    }

    /** Composer.endRestartGroup() —— 反编译里是 jh4.v()，返回 RecomposeScope(p98)。 */
    public static Object endGroup(ClassLoader cl, Object composer) {
        Method m = method(cl, COMPOSER, "v");
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(composer);
        } catch (Throwable t) {
            XLog.w("endRestartGroup 失败: " + t);
            return null;
        }
    }

    private static boolean invokeNoReturn(Method m, Object target, Object... args) {
        if (m == null) {
            return false;
        }
        try {
            m.invoke(target, args);
            return true;
        } catch (Throwable t) {
            XLog.w("调用 " + m.getName() + " 失败: " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 组合 lambda

    /** 造一个 me4（组合 lambda）；handler 收到 (Composer, changedInt)。 */
    public static Object composable(ClassLoader cl, LambdaHandler handler) {
        return proxy(cl, F2, (p, m, a) -> {
            if (m.getName().equals("r")) {
                return handler.run(a[0], ((Number) a[1]).intValue());
            }
            return fallback(p, m, a);
        });
    }

    /** 造一个 wd4（无参回调）。 */
    public static Object callback(ClassLoader cl, final Runnable r) {
        return proxy(cl, F0, (p, m, a) -> {
            if (m.getName().equals("w")) {
                r.run();
                return null;
            }
            return fallback(p, m, a);
        });
    }

    /** 造一个 yd4（单参回调）。 */
    public static Object callback1(ClassLoader cl, final java.util.function.Consumer<Object> c) {
        return proxy(cl, F1, (p, m, a) -> {
            if (a != null && a.length == 1) {
                c.accept(a[0]);
                return null;
            }
            return fallback(p, m, a);
        });
    }

    public interface LambdaHandler {
        Object run(Object composer, int changed);
    }

    private static Object proxy(ClassLoader cl, String ifaceName, InvocationHandler h) {
        Class<?> iface = cls(cl, ifaceName);
        if (iface == null) {
            XLog.e("找不到接口 " + ifaceName);
            return null;
        }
        return Proxy.newProxyInstance(cl, new Class<?>[]{iface}, h);
    }

    private static Object fallback(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "toString":
                return "dsmat-" + method.getDeclaringClass().getSimpleName();
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            default:
                return null;
        }
    }
}
