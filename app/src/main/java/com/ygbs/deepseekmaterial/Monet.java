package com.ygbs.deepseekmaterial;

import android.content.Context;
import android.content.res.Resources;

import java.util.HashMap;
import java.util.Map;

/**
 * Material You（Monet）取色。
 *
 * <p>Android 12+ 的 framework 里有一套「动态色板」资源，运行时会跟着壁纸变化：
 * <pre>
 *   android.R.color.system_neutral1_{0,10,50,...,1000}   // 表面/文字用的中性色
 *   android.R.color.system_neutral2_{...}                // variant 中性色
 *   android.R.color.system_accent1_{...}                 // 主题色 1（主色）
 *   android.R.color.system_accent2_{...} / accent3_{...} // 主题色 2/3
 * </pre>
 * 注意 Android 的编号方向：<b>tone 0 = 白，tone 1000 = 黑</b>（和 Material 官方 tone 定义相反）。
 *
 * <p>本类做一件事：把 App 原有的 ARGB 颜色按「饱和度 + 亮度」映射到壁纸色板里最接近的一级，
 * 于是整套 UI 会跟着壁纸变成 Material You 的色调。
 */
public final class Monet {

    private static final int[] TONES = {0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
    private static final String[] FAMILIES = {"neutral1", "neutral2", "accent1", "accent2", "accent3"};

    private static final int NEUTRAL1 = 0;
    private static final int NEUTRAL2 = 1;
    private static final int ACCENT1 = 2;

    /** ramps[family][toneIndex] */
    private static int[][] ramps;
    private static final Map<Integer, Integer> CACHE = new HashMap<>();
    /** 已经映射出来的颜色；再次映射时原样返回，保证幂等（防止被改写两次后跑偏）。 */
    private static final java.util.Set<Integer> OUTPUTS = new java.util.HashSet<>();

    private Monet() {
    }

    public static void load(Context ctx) {
        if (ramps != null) {
            return;
        }
        Resources res = ctx.getResources();
        int[][] r = new int[FAMILIES.length][TONES.length];
        boolean ok = true;
        StringBuilder sb = new StringBuilder("MaterialYou 色板: ");
        for (int f = 0; f < FAMILIES.length; f++) {
            for (int t = 0; t < TONES.length; t++) {
                String name = "system_" + FAMILIES[f] + "_" + TONES[t];
                int id = res.getIdentifier(name, "color", "android");
                if (id == 0) {
                    ok = false;
                    r[f][t] = 0;
                    continue;
                }
                try {
                    r[f][t] = res.getColor(id, ctx.getTheme());
                } catch (Throwable e) {
                    r[f][t] = 0;
                }
            }
            sb.append(FAMILIES[f]).append("=[");
            for (int t = 0; t < TONES.length; t++) {
                sb.append(String.format("%08X", r[f][t])).append(t < TONES.length - 1 ? " " : "");
            }
            sb.append("] ");
        }
        ramps = r;
        XLog.i("Monet.load 成功=" + ok);
        XLog.i(sb.toString());
    }

    public static boolean ready() {
        return ramps != null;
    }

    /**
     * 把原色映射到壁纸色板。
     *
     * <p>规则：
     * <ul>
     *   <li>半透明色（alpha &lt; 0x80）不动 —— 那些是阴影/叠加层</li>
     *   <li>几乎纯灰（sat &lt; 0.015）走 neutral1（表面 &amp; 文字）</li>
     *   <li>微微带色（0.015 ~ 0.28）走 neutral2（variant 表面）</li>
     *   <li>明显有色（&gt; 0.28）走 accent1（主色），色相直接换成壁纸色</li>
     * </ul>
     * 具体取哪一级，按感知亮度就近选。
     */
    public static int map(int argb) {
        Integer hit = CACHE.get(argb);
        if (hit != null) {
            return hit;
        }
        if (OUTPUTS.contains(argb)) {
            return argb; // 已经是映射结果，保持幂等
        }
        int out = compute(argb);
        CACHE.put(argb, out);
        OUTPUTS.add(out);
        return out;
    }

    private static int compute(int argb) {
        if (ramps == null) {
            return argb;
        }
        int a = (argb >>> 24) & 0xFF;
        if (a < 0x80) {
            return argb; // 阴影 / 半透明叠加
        }
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int mx = Math.max(r, Math.max(g, b));
        int mn = Math.min(r, Math.min(g, b));
        float sat = mx == 0 ? 0f : (float) (mx - mn) / mx;

        int family;
        if (sat < 0.015f) {
            family = NEUTRAL1;
        } else if (sat < 0.28f) {
            family = NEUTRAL2;
        } else {
            family = ACCENT1;
        }

        float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        int[] ramp = ramps[family];
        int best = ramp[0];
        float bestD = Float.MAX_VALUE;
        for (int c : ramp) {
            if (c == 0) {
                continue;
            }
            int cr = (c >> 16) & 0xFF;
            int cg = (c >> 8) & 0xFF;
            int cb = c & 0xFF;
            float cl = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb;
            float d = Math.abs(cl - lum);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return (a << 24) | (best & 0xFFFFFF);
    }
}
