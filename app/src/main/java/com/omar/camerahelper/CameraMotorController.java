package com.omar.camerahelper;

import android.util.Log;

/**
 * All sysfs/persist reads and writes now go through RootShell (a persistent
 * `su` session) instead of FileUtils, so this works regardless of whether
 * system_server's own uid/sepolicy domain has write access to
 * /sys/class/motor/* and /sys/class/leds/* — root does.
 *
 * SystemProperties reads/writes are left as regular Android API calls since
 * those are already accessible without root.
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
    return;
}

    public static String getMotorPosition() {
        String position = RootShell.get().readFile(CAMERA_MOTOR_POSITION_PATH);
        if (position == null) {
            Log.e(TAG, "Failed to read " + CAMERA_MOTOR_POSITION_PATH);
        }
        return position;
    }

    public static void setMotorDirection(String direction) {
        if (!RootShell.get().writeFile(CAMERA_MOTOR_DIRECTION_PATH, direction)) {
            Log.e(TAG, "Failed to write to " + CAMERA_MOTOR_DIRECTION_PATH);
        }
    }

    public static void setMotorEnabled() {
        String direction = RootShell.get().readFile(CAMERA_MOTOR_DIRECTION_PATH);
        boolean movingUp = direction != null && direction.trim().equals("1");

        if (!RootShell.get().writeFile(CAMERA_MOTOR_ENABLE_PATH, "1")) {
            Log.e(TAG, "Failed to write to " + CAMERA_MOTOR_ENABLE_PATH);
        }

        triggerLed(movingUp);
    }

    /**
     * up == true  -> camera rising  (dim variant, brightness field "22")
     * up == false -> camera retracting (brighter variant, brightness field "44")
     */
    public static void triggerLed(boolean up) {
        String enabled = HiddenApi.getSystemProperty(LED_ENABLE_PROP, "0");
        if (!"1".equals(enabled)) {
            return;
        }

        String color = HiddenApi.getSystemProperty(LED_COLOR_PROP, "0");
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
                String[] parts = color.split(",");
                if (parts.length < 6) {
                    return;
                }
                parts[3] = up ? "22" : "44";
                value = String.join(",", parts);
                break;
        }

        writeLed(value);
    }

    private static void writeLed(String value) {
        if (!RootShell.get().writeFile(LED_COLOR_PATH, value)) {
            Log.e(TAG, "Failed to write to " + LED_COLOR_PATH);
        }
    }
}
