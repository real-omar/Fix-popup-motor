package com.omar.camerahelper;

import android.util.Log;

/**
 * Port of OppoMotorCalibrateHelper's hall-sensor calibration logic
 * (the thing OPPO's system_server actually calls after every up/down
 * motor move) — adapted from HIDL/IMotorControl + OplusEngineerManager
 * writes to plain sysfs + RootShell, since a GSI has neither of those
 * vendor services.
 *
 * Background, so the numbers below don't look like they fell from the sky:
 * the popup mechanism has no position encoder. It only has two hall-effect
 * sensors, and the driver exposes their raw readings as two comma-separated
 * ints at a time:
 *   /sys/class/motor/hall_data      -> current (hall1, hall2) reading
 *   /sys/class/motor/hall_max_data  -> the extreme reading hit during the
 *                                      last full travel (i.e. "how far did
 *                                      it actually swing")
 * "Calibration" here just means: after the motor finishes a move, look at
 * how far the hall values actually swung, compare that against the last
 * known-good reference, and if it drifted outside an acceptable band,
 * recompute a new 6-value reference string and push it to
 * /sys/class/motor/hall_calibration (+ persist it so it survives reboot).
 *
 * The 6-value layout mirrors CameraMotorController.HALL_CALIBRATION_DEFAULT
 * ("-200,-184,-265,23,-1,-249"):
 *   [0] = near hall-1 reference
 *   [1] = near hall-2 reference
 *   [2] = near delta reference   (used by isNeedCalib for the "down" side)
 *   [3] = far hall-1 reference
 *   [4] = far hall-2 reference
 *   [5] = far delta reference    (used by isNeedCalib for the "up" side)
 *
 * NOTE ON FIDELITY: the OPPO helper additionally branched on a project name
 * (ro.separate.soft == "19331"/"19031") and on a "backflash" device variant,
 * each taking a slightly different offset path. Those branches are
 * OPPO-model-specific and meaningless on a GSI, so they're dropped — this
 * keeps the single general-purpose path (the offsets/threshold constants
 * below, e.g. the ±0x28/±0x64 nudges and the 20-50 / 10-60 valid-delta
 * windows, are taken directly from that path). If the retracted/extended
 * positions still feel a little off after calibrate() runs, the values
 * most worth tweaking are NEAR_OFFSET / FAR_OFFSET below.
 */
public final class CameraMotorCalibrator {

    private static final String TAG = "CameraMotorCalibrator";

    // Index layout within the 6-value calibration array.
    private static final int NEAR_HALL_ONE = 0;
    private static final int NEAR_HALL_TWO = 1;
    private static final int NEAR_DELTA = 2;
    private static final int FAR_HALL_ONE = 3;
    private static final int FAR_HALL_TWO = 4;
    private static final int FAR_DELTA = 5;
    private static final int DATA_LENGTH = 6;

    // Acceptable drift window before we bother recalibrating (from OPPO's
    // sHallNearMinDif/MaxDif, sHallFarMinDif/MaxDif).
    private static final int NEAR_MIN_DIF = 20;   // 0x14
    private static final int NEAR_MAX_DIF = 50;   // 0x32
    private static final int FAR_MIN_DIF = 10;    // 0xa
    private static final int FAR_MAX_DIF = 60;    // 0x3c

    // Nudge applied to the raw hall reading when building a new reference.
    private static final int NEAR_OFFSET = 40;    // 0x28
    private static final int FAR_OFFSET = 100;    // 0x64

    // Same descending delta-standard table OPPO used for both near and far
    // (NEAR_/FAR_CALIBRATION_DELTA_STANDARD_ARRAY were identical: 70,65,...,20).
    private static final int[] DELTA_STANDARD_ARRAY = {
            0x46, 0x41, 0x3c, 0x37, 0x32, 0x2d, 0x28, 0x23, 0x1e, 0x19, 0x14
    };
    private static final int NEAR_DELTA_INDEX = 7; // sNearDeltaParameterIndex
    private static final int FAR_DELTA_INDEX = 4;  // sFarDeltaParameterIndex

    private static final String HALL_DATA_PATH = "/sys/class/motor/hall_data";
    private static final String HALL_MAX_DATA_PATH = "/sys/class/motor/hall_max_data";

    private CameraMotorCalibrator() {
    }

    /**
     * Call this after a move completes and the motor has come to rest,
     * i.e. from the same place CameraMotorManager currently calls
     * CameraMotorController.calibrate() — downed=true after a DOWN move,
     * downed=false after an UP move.
     */
    public static void calibrate(boolean downed) {
        int[] hallData = readInts(HALL_DATA_PATH);
        if (hallData == null) {
            Log.e(TAG, "calibrate: couldn't read hall_data, skipping");
            return;
        }

        int[] current = loadCalibrationData();

        if (!isNeedCalib(downed, hallData, current)) {
            Log.d(TAG, "calibrate: hall reading within tolerance, no calibration needed");
            return;
        }

        int[] hallMaxData = readInts(HALL_MAX_DATA_PATH);
        if (hallMaxData == null) {
            Log.e(TAG, "calibrate: needed calibration but hall_max_data unreadable, skipping");
            return;
        }

        int[] updated = current.clone();
        if (downed) {
            // near-side reference: hall1 gets shifted "in" by NEAR_OFFSET,
            // hall2 mirrors the swing, delta reference tracks off the
            // descending standard table.
            updated[NEAR_HALL_ONE] = hallMaxData[0] - NEAR_OFFSET;
            updated[NEAR_HALL_TWO] = hallMaxData[1] + hallMaxData[0];
            updated[NEAR_DELTA] = hallMaxData[0] - DELTA_STANDARD_ARRAY[NEAR_DELTA_INDEX];
        } else {
            updated[FAR_HALL_ONE] = hallMaxData[0] + FAR_OFFSET;
            updated[FAR_HALL_TWO] = hallMaxData[1] - hallMaxData[0];
            updated[FAR_DELTA] = hallMaxData[0] + DELTA_STANDARD_ARRAY[FAR_DELTA_INDEX];
        }

        if (!isCalibrationDataSane(updated)) {
            Log.e(TAG, "calibrate: computed calibration looks bogus, keeping previous data: "
                    + join(current));
            return;
        }

        saveCalibrationData(updated);
    }

    /** Mirrors OppoMotorCalibrateHelper.isNeedCalib(boolean). */
    private static boolean isNeedCalib(boolean downed, int[] hallData, int[] calibData) {
        if (downed) {
            int diff = -hallData[0] + calibData[NEAR_HALL_ONE];
            boolean inRange = diff > NEAR_MIN_DIF && diff < NEAR_MAX_DIF;
            Log.d(TAG, "isNeedCalib(downed): hall[0]=" + hallData[0]
                    + " ref=" + calibData[NEAR_HALL_ONE] + " diff=" + diff + " inRange=" + inRange);
            return !inRange;
        } else {
            int diff = -hallData[1] + calibData[FAR_HALL_TWO];
            boolean inRange = diff > FAR_MIN_DIF && diff < FAR_MAX_DIF;
            Log.d(TAG, "isNeedCalib(up): hall[1]=" + hallData[1]
                    + " ref=" + calibData[FAR_HALL_TWO] + " diff=" + diff + " inRange=" + inRange);
            return !inRange;
        }
    }

    /** Loose bounds check so a read glitch can't wedge a garbage reference in. */
    private static boolean isCalibrationDataSane(int[] data) {
        if (data.length != DATA_LENGTH) {
            return false;
        }
        for (int v : data) {
            if (v < -2000 || v > 2000) {
                return false;
            }
        }
        return true;
    }

    /** Reads the persisted calibration, falling back to the compiled-in default. */
    private static int[] loadCalibrationData() {
        String persisted = RootShell.get().readFile(CameraMotorController.CAMERA_PERSIST_HALL_CALIBRATION);
        int[] parsed = parseCsvInts(persisted, DATA_LENGTH);
        if (parsed != null) {
            return parsed;
        }
        Log.d(TAG, "loadCalibrationData: no valid persisted data, using HALL_CALIBRATION_DEFAULT");
        int[] fallback = parseCsvInts(CameraMotorController.HALL_CALIBRATION_DEFAULT, DATA_LENGTH);
        return fallback != null ? fallback : new int[DATA_LENGTH];
    }

    private static void saveCalibrationData(int[] data) {
        String csv = join(data);
        Log.d(TAG, "saveCalibrationData: " + csv);

        boolean wroteSysfs = RootShell.get().writeFile(
                CameraMotorController.CAMERA_MOTOR_HALL_CALIBRATION, csv);
        if (!wroteSysfs) {
            Log.e(TAG, "Failed to write " + CameraMotorController.CAMERA_MOTOR_HALL_CALIBRATION);
        }

        boolean wrotePersist = RootShell.get().writeFile(
                CameraMotorController.CAMERA_PERSIST_HALL_CALIBRATION, csv);
        if (!wrotePersist) {
            Log.e(TAG, "Failed to persist calibration to "
                    + CameraMotorController.CAMERA_PERSIST_HALL_CALIBRATION);
        }
    }

    private static int[] readInts(String sysfsPath) {
        String raw = RootShell.get().readFile(sysfsPath);
        return parseCsvInts(raw, 2);
    }

    private static int[] parseCsvInts(String raw, int expectedLength) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String[] parts = raw.trim().split(",");
        if (parts.length != expectedLength) {
            return null;
        }
        int[] result = new int[expectedLength];
        try {
            for (int i = 0; i < expectedLength; i++) {
                result[i] = Integer.parseInt(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "parseCsvInts: malformed data \"" + raw + "\"", e);
            return null;
        }
        return result;
    }

    private static String join(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }
}
