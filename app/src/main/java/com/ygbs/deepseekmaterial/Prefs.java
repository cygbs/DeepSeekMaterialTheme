package com.ygbs.deepseekmaterial;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 模块自己的开关状态（存在目标 App 的私有目录里，键名前缀 dsmat_）。
 */
public final class Prefs {

    private static final String FILE = "dsmat";
    private static final String KEY_MATERIAL = "material_ui";

    private static volatile SharedPreferences sPrefs;
    private static volatile Boolean sCached;

    private Prefs() {
    }

    public static void init(Context ctx) {
        sPrefs = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        sCached = null;
    }

    /** Material 设计是否开启。默认 true。 */
    public static boolean material() {
        Boolean c = sCached;
        if (c != null) {
            return c;
        }
        SharedPreferences p = sPrefs;
        boolean v = p == null || p.getBoolean(KEY_MATERIAL, true);
        sCached = v;
        return v;
    }

    public static void setMaterial(boolean value) {
        sCached = value;
        SharedPreferences p = sPrefs;
        if (p != null) {
            p.edit().putBoolean(KEY_MATERIAL, value).apply();
        }
        XLog.i("Prefs: material_ui -> " + value);
    }
}
