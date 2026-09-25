package com.ygbs.deepseekmaterial;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 递归遍历一个对象图，把里面所有「Compose Color」形态的 long 字段原地替换掉。
 *
 * <p>为什么能这么干：Compose 的 {@code Color} 是 Kotlin 内联值类，
 * 在对象里就是一个 long，编码为 {@code argb << 32}。
 * 所以只要看到「低 32 位为 0、高 32 位是 ARGB」的 long 字段，就是一个颜色。
 *
 * <p>token 对象是 final 类 + final 字段，但字段是<b>实例</b> final，
 * 在 ART 上 setAccessible(true) 之后可以改写（Xposed 一直这么干）。
 */
public final class ColorGraph {

    public static final class Result {
        public int rewritten;
        public int failed;
        public int visited;
        public final StringBuilder trace = new StringBuilder();

        @Override
        public String toString() {
            return "改写 " + rewritten + " 个颜色字段，失败 " + failed + "，遍历 " + visited + " 个对象";
        }
    }

    private ColorGraph() {
    }

    public static Result apply(Object root, String label, boolean traceAll, boolean record) {
        Result r = new Result();
        if (root == null) {
            XLog.w(label + ": 根对象为 null");
            return r;
        }
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        walk(root, label, 0, seen, r, traceAll, record);
        XLog.i(label + ": " + r);
        if (traceAll && r.trace.length() > 0) {
            XLog.i(label + " 明细:\n" + r.trace);
        }
        return r;
    }

    private static void walk(Object o, String path, int depth, Set<Object> seen, Result r, boolean traceAll, boolean record) {
        if (o == null || depth > 12) {
            return;
        }
        Class<?> c = o.getClass();
        String cn = c.getName();
        if (c.isArray() || o instanceof Collection || o instanceof Map
                || cn.startsWith("java.") || cn.startsWith("kotlin.") || cn.startsWith("android.")) {
            return;
        }
        if (!seen.add(o)) {
            return;
        }
        r.visited++;
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Class<?> t = f.getType();
            try {
                f.setAccessible(true);
                if (t == long.class) {
                    long raw = f.getLong(o);
                    if (raw != 0L && (raw & 0xFFFFFFFFL) == 0L) {
                        int argb = (int) (raw >>> 32);
                        int mapped = Monet.map(argb);
                        if (mapped != argb) {
                            if (record) {
                                ColorState.record(o, f, raw);
                            }
                            f.setLong(o, ((long) mapped) << 32);
                            r.rewritten++;
                            if (traceAll) {
                                r.trace.append("  ").append(path).append('.').append(f.getName())
                                        .append(String.format("  #%08X -> #%08X%n", argb, mapped));
                            }
                        }
                    }
                } else if (!t.isPrimitive()) {
                    walk(f.get(o), path + "." + f.getName(), depth + 1, seen, r, traceAll, record);
                }
            } catch (Throwable e) {
                r.failed++;
                if (r.failed <= 5) {
                    XLog.w("改写失败 " + path + "." + f.getName() + ": " + e);
                }
            }
        }
    }
}
