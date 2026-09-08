package com.omar.camerahelper;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Thin reflection wrappers around hidden/system-only framework classes
 * (ActivityThread, SystemProperties) that exist at runtime on the device
 * but aren't in the public android.jar used to compile against, so they
 * can't be imported/called directly without the build failing with
 * "cannot find symbol".
 */
final class HiddenApi {

    private static final String TAG = "CameraHelperHiddenApi";

    private HiddenApi() {
    }

    /** Equivalent of ActivityThread.systemMain().getSystemContext(). */
    static Context getSystemContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method systemMain = activityThreadClass.getMethod("systemMain");
            Object activityThread = systemMain.invoke(null);
            Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
            return (Context) getSystemContext.invoke(activityThread);
        } catch (Exception e) {
            Log.e(TAG, "Failed to obtain system context via ActivityThread", e);
            return null;
        }
    }

    /** Equivalent of SystemProperties.get(key, def). */
    static String getSystemProperty(String key, String def) {
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            Method get = spClass.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, def);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read SystemProperty " + key, e);
            return def;
        }
    }

    /** Equivalent of SystemProperties.set(key, value). */
    static void setSystemProperty(String key, String value) {
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            Method set = spClass.getMethod("set", String.class, String.class);
            set.invoke(null, key, value);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set SystemProperty " + key, e);
        }
    }
}
