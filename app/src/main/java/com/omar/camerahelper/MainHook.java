package com.omar.camerahelper;

import android.content.Context;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed entry point. Targets the "android" package (system_server) and
 * hooks SystemServer#run so that, once the system is fully up, we grab the
 * system Context the same way real system services do, then start the
 * camera-motor watcher and fall-sensor watcher directly in-process — no
 * separate app/Service needed, which is what let the original app be a
 * standalone priv-app with its own manifest-registered Services.
 *
 * xposed_init must contain:
 *   org.lineageos.camerahelper.MainHook
 *
 * AndroidManifest.xml needs:
 *   <meta-data android:name="xposedmodule" android:value="true" />
 *   <meta-data android:name="xposedminversion" android:value="93" />
 *   <meta-data android:name="xposedscope" android:resource="@array/xposed_scope" />
 * with xposed_scope containing "android".
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "CameraHelperXposed";

    private CameraMotorManager mCameraMotorManager;
    private FallSensorManager mFallSensorManager;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) {
            return;
        }

        // Fires as soon as the module is actually loaded into system_server,
        // regardless of whether the later hook below ever fires. If this
        // line never shows up in `adb logcat -s CameraHelperXposed`, the
        // module isn't enabled/scoped onto the system framework in your
        // LSPosed manager — check Modules -> this module -> scope includes
        // "Android System (android)", and that it's toggled on.
        Log.i(TAG, "Module loaded into system_server");

        // NOTE: SystemServer#run() never returns — it ends in Looper.loop()
        // which blocks until the process dies, so hooking it with
        // afterHookedMethod would never fire. startOtherServices() is one
        // of the last things run() calls during boot and it does return,
        // so we hook that instead. Its signature varies by AOSP version
        // (no-arg on older, takes a TimingsTraceAndSlog on newer), so find
        // it by name rather than hardcoding a signature.
        Class<?> systemServerClass = XposedHelpers.findClass(
                "com.android.server.SystemServer", lpparam.classLoader);

        boolean hooked = false;
        for (java.lang.reflect.Method m : systemServerClass.getDeclaredMethods()) {
            if (m.getName().equals("startOtherServices")) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Log.i(TAG, "startOtherServices returned, initializing");
                        onSystemReady();
                    }
                });
                hooked = true;
            }
        }

        if (!hooked) {
            Log.e(TAG, "Could not find startOtherServices to hook — "
                    + "check logcat for the actual method name on this Android version");
        }
    }

    private void onSystemReady() {
        try {
            Context systemContext = HiddenApi.getSystemContext();
            if (systemContext == null) {
                Log.e(TAG, "Could not obtain system context, aborting init");
                return;
            }

            mCameraMotorManager = new CameraMotorManager();
            mCameraMotorManager.start(systemContext);

            mFallSensorManager = new FallSensorManager(systemContext);
            if (mFallSensorManager.hasSensor()) {
                mFallSensorManager.enable();
            } else {
                Log.d(TAG, "No android.sensor.camera_protect on this device, "
                        + "fall-detection retract disabled");
            }

            Log.i(TAG, "Camera motor + fall sensor hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(t);
            Log.e(TAG, "Failed to initialize camera motor hooks", t);
        }
    }
}
