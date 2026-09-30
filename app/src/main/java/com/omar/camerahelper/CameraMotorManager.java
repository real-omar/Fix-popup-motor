package com.omar.camerahelper;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * Port of CameraMotorService + its inner AvailabilityCallback (CameraMotorService$1).
 * Registers a CameraManager.AvailabilityCallback on the system context and
 * drives the motor the same way the original Service did — with the same
 * 100ms debounce (CAMERA_EVENT_DELAY_TIME) so rapid open/close churn from
 * some camera apps doesn't jitter the motor.
 */
public class CameraMotorManager implements Handler.Callback {

    private static final String TAG = "CameraMotorManager";
    private static final String FRONT_CAMERA_ID = "1";
    private static final int CAMERA_EVENT_DELAY_TIME = 100;
    private static final int MSG_CAMERA_CLOSED = 1000;
    private static final int MSG_CAMERA_OPEN = 1001;

    private final Handler mHandler = new Handler(this);
    private long mOpenEvent;
    private long mClosedEvent;

    // NOTE: the original smali overrode onCameraOpened(String, String) — a
    // hidden @SystemApi overload not present in the public SDK, so it can't
    // be compiled against directly. onCameraAvailable/onCameraUnavailable
    // are the public equivalents: unavailable fires when something else
    // grabs the camera (~open), available fires when it's freed (~closed).
    private final CameraManager.AvailabilityCallback mAvailabilityCallback =
            new CameraManager.AvailabilityCallback() {
                @Override
                public void onCameraAvailable(String cameraId) {
                    if (!FRONT_CAMERA_ID.equals(cameraId)) {
                        return;
                    }
                    mClosedEvent = System.currentTimeMillis();
                    mHandler.removeMessages(MSG_CAMERA_CLOSED);
                    mHandler.sendEmptyMessageDelayed(MSG_CAMERA_CLOSED, CAMERA_EVENT_DELAY_TIME);
                }

                @Override
                public void onCameraUnavailable(String cameraId) {
                    if (!FRONT_CAMERA_ID.equals(cameraId)) {
                        return;
                    }
                    mOpenEvent = System.currentTimeMillis();
                    mHandler.removeMessages(MSG_CAMERA_OPEN);
                    mHandler.sendEmptyMessageDelayed(MSG_CAMERA_OPEN, CAMERA_EVENT_DELAY_TIME);
                }
            };

    public void start(Context systemContext) {
        CameraManager cm = systemContext.getSystemService(CameraManager.class);
        cm.registerAvailabilityCallback(mAvailabilityCallback, mHandler);
        Log.d(TAG, "Registered front-camera availability callback");
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_CAMERA_CLOSED:
                CameraMotorController.setMotorDirection(CameraMotorController.DIRECTION_DOWN);
                CameraMotorController.setMotorEnabled();
                break;
            case MSG_CAMERA_OPEN:
                CameraMotorController.setMotorDirection(CameraMotorController.DIRECTION_UP);
                CameraMotorController.setMotorEnabled();
                break;
            default:
                break;
        }
        return true;
    }
}
