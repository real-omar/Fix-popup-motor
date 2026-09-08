package com.omar.camerahelper;

import android.app.ActivityThread;
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
 *   com.omar.camerahelper.MainHook
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

        XposedHelpers.findAndHookMethod(
                "com.android.server.SystemServer",
                lpparam.classLoader,
                "run",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        onSystemReady();
                    }
                });
    }

    private void onSystemReady() {
        try {
            Object activityThread = XposedHelpers.callStaticMethod(
                    ActivityThread.class, "systemMain");
            Context systemContext = (Context) XposedHelpers.callMethod(
                    activityThread, "getSystemContext");

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
