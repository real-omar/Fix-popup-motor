package com.omar.camerahelper;

import android.os.FileUtils;
import android.os.SystemProperties;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Direct Java port of the smali CameraMotorController.
 * Pure sysfs/prop I/O — no Xposed-specific code. Must run with system/root
 * privileges (called from a system_server-side hook in MainHook), since
 * /sys/class/motor/* and /sys/class/leds/* are not writable by regular apps
 * on most OEM builds.
 */
public class CameraMotorController {

    private static final String TAG = "CameraMotorController";

    private static final String CAMERA_MOTOR_DIRECTION_PATH = "/sys/class/motor/direction";
    private static final String CAMERA_MOTOR_ENABLE_PATH = "/sys/class/motor/enable";
    public static final String CAMERA_MOTOR_HALL_CALIBRATION = "/sys/class/motor/hall_calibration";
    private static final String CAMERA_MOTOR_POSITION_PATH = "/sys/class/motor/position";
    public static final String CAMERA_PERSIST_HALL_CALIBRATION =
            "/mnt/vendor/persist/engineermode/hall_calibration";

    public static final String DIRECTION_DOWN = "0";
    public static final String DIRECTION_UP = "1";
    public static final String ENABLED = "1";
    public static final String HALL_CALIBRATION_DEFAULT = "-200,-184,-265,23,-1,-249";

    private static final String LED_COLOR_PATH = "/sys/class/leds/led1/device/color";
    private static final String LED_COLOR_PROP = "persist.sys.phh.oppo.led.color";
    private static final String LED_ENABLE_PROP = "persist.sys.phh.oppo.led.enable";

    public static final String POSITION_DOWN = "1";
    public static final String POSITION_UP = "0";

    private CameraMotorController() {
    }

    public static void calibrate() {
        String calibration;
        try {
            calibration = FileUtils.readTextFile(
                    new File(CAMERA_PERSIST_HALL_CALIBRATION), 0, null);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read " + CAMERA_PERSIST_HALL_CALIBRATION, e);
            calibration = HALL_CALIBRATION_DEFAULT;
        }
        try {
            FileUtils.stringToFile(CAMERA_MOTOR_HALL_CALIBRATION, calibration);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write to " + CAMERA_MOTOR_HALL_CALIBRATION, e);
        }
    }

    public static String getMotorPosition() {
        String position = null;
        try {
            position = FileUtils.readTextFile(
                    new File(CAMERA_MOTOR_POSITION_PATH), 1, null);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read " + CAMERA_MOTOR_POSITION_PATH, e);
        }
        return position;
    }

    public static void setMotorDirection(String direction) {
        try {
            FileUtils.stringToFile(CAMERA_MOTOR_DIRECTION_PATH, direction);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write to " + CAMERA_MOTOR_DIRECTION_PATH, e);
        }
    }

    public static void setMotorEnabled() {
        String direction;
        try {
            direction = FileUtils.readTextFile(
                    new File(CAMERA_MOTOR_DIRECTION_PATH), 1, null);
        } catch (IOException e) {
            direction = "0";
        }
        boolean movingUp = direction.trim().equals("1");

        try {
            FileUtils.stringToFile(CAMERA_MOTOR_ENABLE_PATH, "1");
        } catch (IOException e) {
            Log.e(TAG, "Failed to write to " + CAMERA_MOTOR_ENABLE_PATH, e);
        }

        triggerLed(movingUp);
    }

    /**
     * up == true  -> camera rising  (dim variant, brightness field "22")
     * up == false -> camera retracting (brighter variant, brightness field "44")
     * Exact port of the smali color switch table.
     */
    public static void triggerLed(boolean up) {
        String enabled = SystemProperties.get(LED_ENABLE_PROP, "0");
        if (!"1".equals(enabled)) {
            return;
        }

        String color = SystemProperties.get(LED_COLOR_PROP, "0");
        String value;

        switch (color) {
            case "pink":
            case "0":
                value = up ? "36,0,178,22,2,1" : "36,0,178,44,2,1";
                break;
            case "reddish_pink":
                value = up ? "10,0,178,22,2,1" : "10,0,178,44,2,1";
                break;
            case "red":
                value = up ? "0,0,178,22,2,1" : "0,0,178,44,2,1";
                break;
            case "orange":
                value = up ? "0,30,178,22,2,1" : "0,30,178,44,2,1";
                break;
            case "yellow":
                value = up ? "0,100,178,22,2,1" : "0,100,178,44,2,1";
                break;
            case "yellowish_green":
                value = up ? "0,178,40,22,2,1" : "0,178,40,44,2,1";
                break;
            case "light_green":
                value = up ? "12,178,0,22,2,1" : "12,178,0,44,2,1";
                break;
            case "green":
                value = up ? "0,178,0,22,2,1" : "0,178,0,44,2,1";
                break;
            case "cyan":
                value = up ? "70,253,0,22,2,1" : "70,253,0,44,2,1";
                break;
            case "blue":
                value = up ? "70,0,0,22,2,1" : "70,0,0,44,2,1";
                break;
            case "purple":
                value = up ? "70,0,20,22,2,1" : "70,0,20,44,2,1";
                break;
            case "light_purple":
                value = up ? "70,0,70,22,2,1" : "70,0,70,44,2,1";
                break;
            default:
                // Custom "r,g,b,brightness,?,?" string from settings — patch
                // in the brightness field (index 3) and pass through as-is.
                String[] parts = color.split(",");
                if (parts.length < 6) {
                    // Matches the smali fallthrough: nothing written when the
                    // custom string is malformed and no known name matched.
                    return;
                }
                parts[3] = up ? "22" : "44";
                value = String.join(",", parts);
                break;
        }

        writeLed(value);
    }

    private static void writeLed(String value) {
        try {
            FileUtils.stringToFile(LED_COLOR_PATH, value);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write to " + LED_COLOR_PATH, e);
        }
    }
}
