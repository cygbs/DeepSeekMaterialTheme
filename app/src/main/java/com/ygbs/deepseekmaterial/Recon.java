package com.ygbs.deepseekmaterial;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * 侦察（recon）：把目标 App 里主题相关的混淆结构在<b>运行时</b>倒出来。
 *
 * <p>为什么要在运行时做一遍：静态反编译看到的是 R8 合并后的类，字段表是「多类合并的并集」，
 * 单看源码分不清哪个字段才是真正在用的。运行时 dump 出来的是真值。
 *
 * <p>本类只读不写，不改任何东西，用来验证 ThemeTargets 是否和当前 App 版本对得上。
 */
public final class Recon {

    private static final int MAX_DEPTH = 3;

    private Recon() {
    }

    public static void run(ClassLoader cl) {
        XLog.i("==================== RECON BEGIN ====================");
        XLog.i("符号表目标版本=" + ThemeTargets.APP_VERSION);

        dumpClassShape(cl, ThemeTargets.THEME_CLASS);
        dumpClassShape(cl, ThemeTargets.TOKEN_CLASS);

        Object light = callStaticBuilder(cl, ThemeTargets.LIGHT_BUILDER_METHOD);
        Object dark = callStaticBuilder(cl, ThemeTargets.DARK_BUILDER_METHOD);
        dumpToken("LIGHT", light);
        dumpToken("DARK", dark);

        dumpLazyValues(cl);
        XLog.i("===================== RECON END =====================");
    }

    // ------------------------------------------------------------------
    // 反射工具
    // ------------------------------------------------------------------

    private static Class<?> load(ClassLoader cl, String name) throws ClassNotFoundException {
        return Class.forName(name, true, cl);
    }

    private static Object callStaticBuilder(ClassLoader cl, String method) {
        try {
            Class<?> c = load(cl, ThemeTargets.BUILDER_CLASS);
            Method m = c.getDeclaredMethod(method);
            m.setAccessible(true);
            Object v = m.invoke(null);
            XLog.i("调用 " + ThemeTargets.BUILDER_CLASS + "." + method + "() 成功 -> "
                    + (v == null ? "null" : v.getClass().getName() + "@"
                    + Integer.toHexString(System.identityHashCode(v))));
            return v;
        } catch (Throwable t) {
            XLog.w("调用 " + ThemeTargets.BUILDER_CLASS + "." + method + "() 失败: " + t);
            return null;
        }
    }

    private static void dumpClassShape(ClassLoader cl, String className) {
        XLog.i("---- 类结构: " + className + " ----");
        try {
            Class<?> c = load(cl, className);
            for (Field f : c.getDeclaredFields()) {
                XLog.i(String.format("  %s%s %s : %s",
                        Modifier.isStatic(f.getModifiers()) ? "static " : "",
                        Modifier.isFinal(f.getModifiers()) ? "final " : "",
                        f.getName(), f.getType().getName()));
            }
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() <= 1) {
                    XLog.i("  method " + m.getName() + "() -> " + m.getReturnType().getSimpleName());
                }
            }
        } catch (Throwable t) {
            XLog.w("读取类结构失败 " + className + ": " + t);
        }
    }

    /** 尝试读取两个 kotlin.Lazy 静态字段当前的值（App 跑起来后通常已经初始化）。 */
    private static void dumpLazyValues(ClassLoader cl) {
        for (String fieldName : new String[]{ThemeTargets.LIGHT_LAZY_FIELD, ThemeTargets.DARK_LAZY_FIELD}) {
            try {
                Class<?> c = load(cl, ThemeTargets.THEME_CLASS);
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object lazy = f.get(null);
                if (lazy == null) {
                    XLog.w("静态字段 " + fieldName + " 为 null（还没初始化？）");
                    continue;
                }
                Method getValue = lazy.getClass().getMethod("getValue");
                getValue.setAccessible(true);
                Object token = getValue.invoke(lazy);
                dumpToken("LAZY(" + fieldName + ")", token);
            } catch (Throwable t) {
                XLog.w("读取 Lazy 字段 " + fieldName + " 失败: " + t);
            }
        }
    }

    // ------------------------------------------------------------------
    // token 对象倒树
    // ------------------------------------------------------------------

    private static void dumpToken(String label, Object token) {
        if (token == null) {
            XLog.w(label + " token = null");
            return;
        }
        XLog.i("---- " + label + " token 树 ----");
        dump(label, token, 0, new HashSet<>());
    }

    private static void dump(String path, Object value, int depth, Set<Object> seen) {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (value == null) {
            XLog.i(pad(depth) + path + " = null");
            return;
        }
        Class<?> type = value.getClass();
        if (type.isPrimitive() || value instanceof Number || value instanceof CharSequence
                || value instanceof Boolean || value instanceof Character) {
            XLog.i(pad(depth) + path + " = " + render(value));
            return;
        }
        if (type.isArray() || value instanceof java.util.Collection || value instanceof java.util.Map) {
            XLog.i(pad(depth) + path + " = " + type.getSimpleName() + "(len=" + len(value) + ")");
            return;
        }
        if (!seen.add(value)) {
            XLog.i(pad(depth) + path + " = <循环引用 " + type.getSimpleName() + ">");
            return;
        }
        StringBuilder line = new StringBuilder(pad(depth)).append(path)
                .append(" : ").append(type.getName());
        String name = type.getName();
        if (name.length() <= 4 && !name.contains(".")) {
            line.append("   <-- 合并类，短名");
        }
        XLog.i(line.toString());

        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object v = f.get(value);
                dump(path + "." + f.getName(), v, depth + 1, seen);
            } catch (Throwable t) {
                XLog.w(pad(depth + 1) + path + "." + f.getName() + " = <读取失败 " + t + ">");
            }
        }
    }

    private static int len(Object v) {
        try {
            if (v instanceof java.util.Collection) {
                return ((java.util.Collection<?>) v).size();
            }
            if (v instanceof java.util.Map) {
                return ((java.util.Map<?, ?>) v).size();
            }
            return java.lang.reflect.Array.getLength(v);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** long 字段按 Compose Color（argb << 32）解码，方便和静态分析出的色值对照。 */
    private static String render(Object v) {
        if (v instanceof Long) {
            long raw = (Long) v;
            long argb = (raw >>> 32) & 0xFFFFFFFFL;
            if (raw != 0L && (raw & 0xFFFFFFFFL) == 0L && argb != 0L) {
                return "#" + String.format("%08X", argb) + " (Color raw=0x" + Long.toHexString(raw) + ")";
            }
            return raw + " (0x" + Long.toHexString(raw) + ")";
        }
        if (v instanceof Integer) {
            int i = (Integer) v;
            return i + " (0x" + Integer.toHexString(i) + ")";
        }
        return String.valueOf(v);
    }

    private static String pad(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }
}
