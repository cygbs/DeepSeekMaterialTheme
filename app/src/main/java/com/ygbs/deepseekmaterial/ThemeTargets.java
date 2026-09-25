package com.ygbs.deepseekmaterial;

/**
 * 目标 App 的混淆符号表 —— 由 tools/gen_anchor_index.py 从反编译产物里挖出来。
 *
 * <p><b>本文件是「一次性」的：换一个 DeepSeek 版本就必须重新生成。</b>
 * 生成流程：
 * <pre>
 *   jadx -d DeepSeek-x.y.z-jadx DeepSeek-x.y.z.apk
 *   python3 tools/gen_anchor_index.py --sources ../DeepSeek-x.y.z-jadx/sources --out analysis
 *   # 然后按 analysis/theme-targets.json 更新下面的常量
 * </pre>
 *
 * <p>关键点：这些类名（如 {@code p3a}）是 <b>默认包</b> 下的名字，R8 把包名抹平了，
 * 所以 dex 描述符形如 {@code Lp3a;}，反射时直接用 "p3a" 即可。
 */
public final class ThemeTargets {

    /** 本符号表对应的 App 版本。 */
    public static final String APP_VERSION = "2.5.3";

    // ---------- 主题入口（Theme.kt 所在的那个 R8 合并类）----------
    /** 含 DeepSeekTheme 组合函数（Theme.kt:28）的类。 */
    public static final String THEME_CLASS = "p3a";
    /** 浅色 token 的 kotlin.Lazy 静态字段（Theme.kt 里 `by lazy`）。 */
    public static final String LIGHT_LAZY_FIELD = "a";
    /** 深色 token 的 kotlin.Lazy 静态字段。 */
    public static final String DARK_LAZY_FIELD = "b";
    /** 分发 token 的 CompositionLocal 静态字段。 */
    public static final String TOKEN_LOCAL_FIELD = "c";

    // ---------- token 容器 ----------
    /** 自研 token 容器类（字段：o53/b9b/v8/n53/pv/le2/o53/dvb/q53/epb 各一组）。 */
    public static final String TOKEN_CLASS = "r53";

    // ---------- 两套 token 的构造器（浅色 / 深色）----------
    /** 构造浅色 token 的静态方法所在类。 */
    public static final String BUILDER_CLASS = "pa6";
    /** 浅色：pa6.C() */
    public static final String LIGHT_BUILDER_METHOD = "C";
    /** 深色：pa6.s() */
    public static final String DARK_BUILDER_METHOD = "s";

    // ---------- M3 兜底 ColorScheme ----------
    /** 持有两个 M3 ColorScheme 静态字段的类（o3a.a 浅色 / o3a.b 深色）。 */
    public static final String COLOR_SCHEME_HOLDER = "o3a";
    public static final String LIGHT_CS_FIELD = "a";
    public static final String DARK_CS_FIELD = "b";

    // ---------- Compose 侧被改名的基础设施（仅供 Recon 打印参考）----------
    /** androidx.compose.runtime.Composer 的混淆名。 */
    public static final String COMPOSER_CLASS = "jh4";
    /** 生成 source info 字符串的工具类（androidx.compose.runtime.internal）。 */
    public static final String SOURCE_INFO_CLASS = "sn2";

    private ThemeTargets() {
    }
}
