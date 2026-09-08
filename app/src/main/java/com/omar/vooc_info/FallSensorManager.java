package com.omar.camerahelper;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Port of FallSensor.smali.
 *
 * NOTE ON THE DIALOG: the original app showed an AlertDialog asking the user
 * whether to keep the camera down or go home, using string/layout resources
 * from the app's own R class (0x7f01...). An Xposed module hooking system_server
 * has no app resources to reference that way, so this version just auto-retracts
 * and posts a Toast instead of the confirmation dialog. If you want the dialog
 * back, you'll need to inflate it from your module's own resources via
 * XposedBridge's resource hook (handleInitPackageResources) or ship literal
 * strings instead of resource IDs.
 */
public class FallSensorManager implements SensorEventListener {

    private static final String TAG = "FallSensorManager";
    private static final String SENSOR_TYPE_CAMERA_PROTECT = "android.sensor.camera_protect";

    private final Context mContext;
    private final SensorManager mSensorManager;
    private final ExecutorService mExecutorService = Executors.newSingleThreadExecutor();
    private Sensor mSensor;

    public FallSensorManager(Context context) {
        mContext = context;
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);

        for (Sensor s : mSensorManager.getSensorList(Sensor.TYPE_ALL)) {
            Log.d(TAG, "Sensor type: " + s.getStringType());
            if (SENSOR_TYPE_CAMERA_PROTECT.equals(s.getStringType())) {
                Log.d(TAG, "Found fall sensor");
                mSensor = s;
                break;
            }
        }
    }

    public boolean hasSensor() {
        return mSensor != null;
    }

    public void enable() {
        if (mSensor == null) {
            return;
        }
        Log.d(TAG, "Enabling");
        mExecutorService.submit(() ->
                mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_UI));
    }

    public void disable() {
        if (mSensor == null) {
            return;
        }
        Log.d(TAG, "Disabling");
        mExecutorService.submit(() -> mSensorManager.unregisterListener(this, mSensor));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.values[0] <= 0f) {
            return;
        }

        Log.d(TAG, "Fall detected, ensuring front camera is closed");

        if (CameraMotorController.POSITION_DOWN.equals(CameraMotorController.getMotorPosition())) {
            // Already retracted, nothing to do.
            return;
        }

        CameraMotorController.setMotorDirection(CameraMotorController.DIRECTION_DOWN);
        CameraMotorController.setMotorEnabled();

        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(mContext, "Fall detected — camera retracted", Toast.LENGTH_LONG).show());
    }
}
