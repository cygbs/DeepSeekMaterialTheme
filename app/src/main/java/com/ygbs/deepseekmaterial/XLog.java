package com.ygbs.deepseekmaterial;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * 统一日志出口：同时写 logcat 和 LSPosed 管理器日志。
 * LSPosed 那侧可用「过滤器 == DSMaterial」快速筛选。
 */
public final class XLog {

    public static final String TAG = "DSMaterial";

    private static volatile XposedInterface sApi;

    private XLog() {
    }

    public static void init(XposedInterface api) {
        sApi = api;
    }

    public static void i(String msg) {
        write(Log.INFO, msg, null);
    }

    public static void w(String msg, Throwable t) {
        write(Log.WARN, msg, t);
    }

    public static void w(String msg) {
        write(Log.WARN, msg, null);
    }

    public static void e(String msg, Throwable t) {
        write(Log.ERROR, msg, t);
    }

    public static void e(String msg) {
        write(Log.ERROR, msg, null);
    }

    private static void write(int priority, String msg, Throwable t) {
        try {
            Log.println(priority, TAG, t == null ? msg : msg + "\n" + Log.getStackTraceString(t));
        } catch (Throwable ignored) {
            // Log 不可用时也不能让模块崩
        }
        XposedInterface api = sApi;
        if (api != null) {
            try {
                api.log(priority, TAG, msg, t);
            } catch (Throwable ignored) {
            }
        }
    }
}
