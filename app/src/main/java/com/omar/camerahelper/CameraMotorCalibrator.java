package com.omar.camerahelper;

import android.util.Log;

/**
 * Port of OppoMotorCalibrateHelper's hall-sensor calibration logic,
 * adapted from HIDL/IMotorControl + OplusEngineerManager writes to plain
 * sysfs + RootShell (a GSI has neither of those vendor services).
 *
 * Background: the popup mechanism has no position encoder, only two
 * hall-effect sensors. The driver exposes their raw readings as two
 * comma-separated ints:
 *   /sys/class/motor/hall_data      -> current (hall1, hall2) reading
 *   /sys/class/motor/hall_max_data  -> the extreme reading hit during the
 *                                      last full travel
 * "Calibration" means: compare the hall reading against a known-good
 * reference, and if it's drifted outside an acceptable band, recompute a
 * new 6-value reference string and push it to
 * /sys/class/motor/hall_calibration.
 *
 * STRATEGY (see calibrateOnFirstUse for the actual trigger point):
 *  - Runs exactly once per process, on the first camera-motor event after
 *    boot — not at system_server startup, and not on every move.
 *  - It samples hall_data BEFORE issuing the direction/enable writes for
 *    that first event, while the motor is still sitting wherever it
 *    settled last boot. That's the only truly-at-rest moment we can get
 *    without hooking a real "motor arrived" interrupt, so this is what
 *    lets us skip arrival-polling entirely while still avoiding the
 *    mid-swing sampling bug (see history in this file's earlier
 *    revisions: calibrating off a reading taken while the motor is
 *    actively moving can compute a physically-wrong reference, which on
 *    some vendor drivers means it stalls the motor against its end-stop
 *    trying to reach a hall value it'll never see, over-current trips,
 *    and the device soft-reboots).
 *  - The reference it diffs against is the compiled-in
 *    HALL_CALIBRATION_DEFAULT, not a persisted file — no dependency on
 *    /mnt/vendor/persist/engineermode/hall_calibration existing or being
 *    in the format OPPO's was. This also means calibration is
 *    intentionally NOT persisted across reboots: it's cheap to
 *    recompute, and it avoids trusting stale data across a driver/kernel
 *    change.
 *  - If the at-rest reading is already within OPPO's tolerance window of
 *    the default, nothing is written at all — matches stock's behavior
 *    of this being a rare drift-correction, not a routine action.
 *
 * The 6-value layout mirrors CameraMotorController.HALL_CALIBRATION_DEFAULT
 * ("-200,-184,-265,23,-1,-249"):
 *   [0] = near hall-1 reference   [3] = far hall-1 reference
 *   [1] = near hall-2 reference   [4] = far hall-2 reference
 *   [2] = near delta reference    [5] = far delta reference
 *
 * NOTE ON FIDELITY: OPPO's helper also branched on project name
 * (ro.separate.soft == "19331"/"19031") and a "backflash" device variant.
 * Those are OPPO-model-specific and meaningless on a GSI, so they're
 * dropped — this keeps the one general-purpose path (offsets/thresholds
 * below are taken directly from that path).
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

    // Acceptable drift window before recalibrating (OPPO's
    // sHallNearMinDif/MaxDif, sHallFarMinDif/MaxDif).
    private static final int NEAR_MIN_DIF = 20;   // 0x14
    private static final int NEAR_MAX_DIF = 50;   // 0x32
    private static final int FAR_MIN_DIF = 10;    // 0xa
    private static final int FAR_MAX_DIF = 60;    // 0x3c

    // Nudge applied to the raw hall reading when building a new reference.
    private static final int NEAR_OFFSET = 40;    // 0x28
    private static final int FAR_OFFSET = 100;    // 0x64

    // Same descending delta-standard table OPPO used for both near and far.
    private static final int[] DELTA_STANDARD_ARRAY = {
            0x46, 0x41, 0x3c, 0x37, 0x32, 0x2d, 0x28, 0x23, 0x1e, 0x19, 0x14
    };
    private static final int NEAR_DELTA_INDEX = 7; // sNearDeltaParameterIndex
    private static final int FAR_DELTA_INDEX = 4;  // sFarDeltaParameterIndex

    private static final String HALL_DATA_PATH = "/sys/class/motor/hall_data";
    private static final String HALL_MAX_DATA_PATH = "/sys/class/motor/hall_max_data";

    /**
     * Was a safety switch while the offset formula was suspected wrong.
     * Turns out the formula itself was fine — the earlier boot-time
     * version raised correctly. The actual bug was a race: this version
     * ran calibration right before the first move with no gap for the
     * driver to ingest the new hall_calibration write before
     * direction/enable landed on the same call stack. Fixed via
     * CALIBRATION_SETTLE_DELAY_MS below instead of disabling writes.
     */
    private static final boolean DRY_RUN = false;

    /**
     * Gap between writing hall_calibration and letting the caller proceed
     * with the actual motor move, so the driver has time to ingest the
     * new reference before direction/enable are written. Without this,
     * the first move after a recalibration races the write and the motor
     * behaves as if calibrated with stale/partial state (stops early
     * raising, overshoots lowering).
     */
    private static final long CALIBRATION_SETTLE_DELAY_MS = 150;

    private static volatile boolean sCalibrationDone = false;

    private CameraMotorCalibrator() {
    }

    /** Cheap check so callers can skip the getMotorPosition() root round-trip once done. */
    public static boolean isCalibrationDone() {
        return sCalibrationDone;
    }

    /**
     * Call this from CameraMotorManager.handleMessage(), BEFORE issuing
     * any direction/enable sysfs write, on every message. It's a no-op
     * after the first call in this process's lifetime.
     *
     * `downed` should reflect the motor's CURRENT resting state going
     * into this event — i.e. pass true if a MSG_CAMERA_CLOSED (down) is
     * about to run but the motor is presently sitting UP (about to move
     * down), false if presently sitting DOWN (about to move up). In
     * other words: whichever endpoint it's resting AT right now, not
     * where it's headed. CameraMotorManager works this out from
     * getMotorPosition() before calling in.
     */
    public static void calibrateOnFirstUse(boolean motorCurrentlyDown) {
        if (sCalibrationDone) {
            return;
        }
        synchronized (CameraMotorCalibrator.class) {
            if (sCalibrationDone) {
                return;
            }
            sCalibrationDone = true;
            calibrate(motorCurrentlyDown);
        }
    }

    /**
     * Core calibration check + write. Only ever called with the motor
     * confirmed at rest at the endpoint matching `downed` — see the class
     * doc for why a mid-move sample is dangerous, not just inaccurate.
     */
    private static void calibrate(boolean downed) {
        int[] hallData = readInts(HALL_DATA_PATH);
        if (hallData == null) {
            Log.e(TAG, "calibrate: couldn't read hall_data, skipping");
            return;
        }

        int[] reference = parseCsvInts(CameraMotorController.HALL_CALIBRATION_DEFAULT, DATA_LENGTH);
        if (reference == null) {
            Log.e(TAG, "calibrate: HALL_CALIBRATION_DEFAULT is malformed, skipping");
            return;
        }

        if (!isNeedCalib(downed, hallData, reference)) {
            Log.d(TAG, "calibrate: at-rest hall reading within tolerance, nothing to do");
            return;
        }

        int[] hallMaxData = readInts(HALL_MAX_DATA_PATH);
        if (hallMaxData == null) {
            Log.e(TAG, "calibrate: needed calibration but hall_max_data unreadable, skipping");
            return;
        }

        int[] updated = reference.clone();
        if (downed) {
            updated[NEAR_HALL_ONE] = hallMaxData[0] - NEAR_OFFSET;
            updated[NEAR_HALL_TWO] = hallMaxData[1] + hallMaxData[0];
            updated[NEAR_DELTA] = hallMaxData[0] - DELTA_STANDARD_ARRAY[NEAR_DELTA_INDEX];
        } else {
            updated[FAR_HALL_ONE] = hallMaxData[0] + FAR_OFFSET;
            updated[FAR_HALL_TWO] = hallMaxData[1] - hallMaxData[0];
            updated[FAR_DELTA] = hallMaxData[0] + DELTA_STANDARD_ARRAY[FAR_DELTA_INDEX];
        }

        if (!isCalibrationDataSane(updated)) {
            Log.e(TAG, "calibrate: computed calibration looks bogus, leaving hall_calibration untouched");
            return;
        }

        String csv = join(updated);
        if (DRY_RUN) {
            Log.w(TAG, "calibrate: would write hall_calibration=" + csv + " but DRY_RUN is on. Not writing.");
            return;
        }
        Log.d(TAG, "calibrate: writing new hall_calibration: " + csv);
        if (!RootShell.get().writeFile(CameraMotorController.CAMERA_MOTOR_HALL_CALIBRATION, csv)) {
            Log.e(TAG, "Failed to write " + CameraMotorController.CAMERA_MOTOR_HALL_CALIBRATION);
            return;
        }

        // Give the driver time to actually ingest the new reference
        // before the caller (CameraMotorManager) issues the move that
        // triggered this calibration in the first place.
        try {
            Thread.sleep(CALIBRATION_SETTLE_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Mirrors OppoMotorCalibrateHelper.isNeedCalib(boolean). */
    private static boolean isNeedCalib(boolean downed, int[] hallData, int[] reference) {
        if (downed) {
            int diff = -hallData[0] + reference[NEAR_HALL_ONE];
            boolean inRange = diff > NEAR_MIN_DIF && diff < NEAR_MAX_DIF;
            Log.d(TAG, "isNeedCalib(downed): hall[0]=" + hallData[0]
                    + " ref=" + reference[NEAR_HALL_ONE] + " diff=" + diff + " inRange=" + inRange);
            return !inRange;
        } else {
            int diff = -hallData[1] + reference[FAR_HALL_TWO];
            boolean inRange = diff > FAR_MIN_DIF && diff < FAR_MAX_DIF;
            Log.d(TAG, "isNeedCalib(up): hall[1]=" + hallData[1]
                    + " ref=" + reference[FAR_HALL_TWO] + " diff=" + diff + " inRange=" + inRange);
            return !inRange;
        }
    }

    /**
     * Loose magnitude bounds so an outright read glitch can't wedge a
     * garbage reference in. Can't detect "plausible but physically wrong"
     * values from a bad sample — that's why calibrate() must only ever
     * run against a confirmed at-rest reading.
     */
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
