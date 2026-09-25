package com.ygbs.deepseekmaterial;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记录「我们改过的颜色字段」以及它的原始值，这样开关切回原版时能精确还原。
 *
 * <p>用 IdentityHashMap + 「首次写入为准」的策略，保证：
 * <ul>
 *   <li>同一个对象被重复改写（我们的机制天然会重复走一遍）不会把映射后的值误当成原始值</li>
 *   <li>不同的 token 对象（比如 ForceDark 当场新建的那套）各自独立记录</li>
 * </ul>
 */
public final class ColorState {

    /** target(身份比较) -> (field -> 原始 long) */
    private static final Map<Object, Map<Field, Long>> ORIGINALS = new IdentityHashMap<>();

    private ColorState() {
    }

    public static void record(Object target, Field field, long original) {
        Map<Field, Long> m = ORIGINALS.get(target);
        if (m == null) {
            m = new HashMap<>();
            ORIGINALS.put(target, m);
        }
        if (!m.containsKey(field)) {
            m.put(field, original);
        }
    }

    /** 把记录过的字段按当前模式重写一遍。 */
    public static int applyMode(boolean material) {
        int n = 0;
        for (Map.Entry<Object, Map<Field, Long>> e : new ArrayList<>(ORIGINALS.entrySet())) {
            for (Map.Entry<Field, Long> f : e.getValue().entrySet()) {
                long orig = f.getValue();
                int argb = (int) (orig >>> 32);
                int target = material ? Monet.map(argb) : argb;
                try {
                    f.getKey().setLong(e.getKey(), ((long) target) << 32);
                    n++;
                } catch (Throwable t) {
                    XLog.w("回写失败 " + f.getKey().getName() + ": " + t);
                }
            }
        }
        XLog.i("ColorState.applyMode(material=" + material + ") 重写 " + n + " 个颜色字段");
        return n;
    }

    /** 只保留这些根对象（及其可达对象）的记录，其余丢弃，避免无限增长。 */
    public static void keepOnly(List<Object> roots) {
        java.util.Set<Object> keep = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object r : roots) {
            collect(r, keep, 0);
        }
        ORIGINALS.keySet().removeIf(o -> !keep.contains(o));
    }

    private static void collect(Object o, java.util.Set<Object> keep, int depth) {
        if (o == null || depth > 12 || !keep.add(o)) {
            return;
        }
        Class<?> c = o.getClass();
        String n = c.getName();
        if (c.isArray() || o instanceof java.util.Collection || o instanceof Map
                || n.startsWith("java.") || n.startsWith("kotlin.") || n.startsWith("android.")) {
            return;
        }
        for (Field f : c.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (f.getType().isPrimitive()) {
                continue;
            }
            try {
                f.setAccessible(true);
                collect(f.get(o), keep, depth + 1);
            } catch (Throwable ignored) {
            }
        }
    }

    public static int size() {
        return ORIGINALS.size();
    }
}
