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
 * STRATEGY (see calibrateAfterMove for the actual trigger point):
 *  - Runs exactly once per process, after the FIRST camera-motor move
 *    fully completes — not before any move, and not on every move.
 *  - It samples hall_data/hall_max_data AFTER that first move, not
 *    before. This was flipped from an earlier pre-move design once
 *    testing showed hall_max_data ("extent reached during the last
 *    travel") reads as uninitialized 0,0 until a real travel has
 *    actually happened — sampling it before the motor has ever moved
 *    computes a reference from that 0,0 garbage and writes it straight
 *    to hardware. Confirmed on-device: that produced a far-reference
 *    write consistent exactly with hallMaxData=[0,0], and the resulting
 *    symptom was the motor stopping almost immediately on raise and
 *    overshooting on lower. calibrate() now also explicitly refuses to
 *    act on a 0,0 hall_max_data reading as a second line of defense.
 *  - Earlier still, calibrating from a reading taken mid-move (motor
 *    actively travelling, not yet arrived) was also tried and rejected:
 *    a physically-wrong reference from that can make some vendor
 *    drivers stall the motor against its end-stop chasing a hall value
 *    it'll never see, over-current trip, and soft-reboot the device.
 *    Post-move sampling avoids both failure modes at once.
 *  - The reference it diffs against is the compiled-in
 *    HALL_CALIBRATION_DEFAULT, not a persisted file — no dependency on
 *    /mnt/vendor/persist/engineermode/hall_calibration existing or being
 *    in the format OPPO's was. This also means calibration is
 *    intentionally NOT persisted across reboots: it's cheap to
 *    recompute, and it avoids trusting stale data across a driver/kernel
 *    change.
 *  - If the post-move hall reading is already within OPPO's tolerance
 *    window of the default, nothing is written at all — matches stock's
 *    behavior of this being a rare drift-correction, not routine.
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
     * Left in as a manual kill switch: set true to log the computed
     * calibration without writing it to hardware.
     */
    private static final boolean DRY_RUN = false;

    private static volatile boolean sCalibrationDone = false;

    private CameraMotorCalibrator() {
    }

    /** Cheap check so callers can skip the getMotorPosition() root round-trip once done. */
    public static boolean isCalibrationDone() {
        return sCalibrationDone;
    }

    /**
     * Call this from CameraMotorManager, AFTER a move has fully
     * completed (motor settled at the endpoint), not before. It's a
     * no-op after the first successful call in this process's lifetime.
     *
     * `downed` = true if the just-finished move was a lower (motor now
     * resting DOWN), false if it was a raise (motor now resting UP).
     *
     * WHY POST-MOVE, NOT PRE-MOVE: hall_max_data (the "extent reached
     * during the last travel" reading the formula needs) is only
     * populated by the driver once a real, completed travel has
     * happened. Sampling it before the motor has ever moved reads as
     * 0,0 — uninitialized, not "at rest" — and computing a reference
     * from that writes nonsense to hall_calibration (confirmed: this is
     * exactly what produced the stuck-on-raise/overshoot-on-lower
     * symptom, with a readback showing far-reference values that only
     * make sense if hall_max_data had been 0,0 at write time).
     */
    public static void calibrateAfterMove(boolean downed) {
        if (sCalibrationDone) {
            return;
        }
        synchronized (CameraMotorCalibrator.class) {
            if (sCalibrationDone) {
                return;
            }
            if (calibrate(downed)) {
                sCalibrationDone = true;
            }
        }
    }

    /**
     * Core calibration check + write. Only ever called with the motor
     * confirmed to have just finished a real move — see the class doc
     * for why this can't run before any travel has happened
     * (hall_max_data reads as uninitialized 0,0 until then).
     *
     * @return true if calibration ran to a real conclusion (wrote new
     *         data, or determined none was needed) — false if a read
     *         failed and it should be retried on a later move instead
     *         of being marked permanently done.
     */
    private static boolean calibrate(boolean downed) {
        int[] hallData = readInts(HALL_DATA_PATH);
        if (hallData == null) {
            Log.e(TAG, "calibrate: couldn't read hall_data, will retry next move");
            return false;
        }
        Log.d(TAG, "calibrate: downed=" + downed + " hall_data=" + join(hallData));

        int[] reference = parseCsvInts(CameraMotorController.HALL_CALIBRATION_DEFAULT, DATA_LENGTH);
        if (reference == null) {
            Log.e(TAG, "calibrate: HALL_CALIBRATION_DEFAULT is malformed, giving up");
            return true; // won't fix itself on retry
        }

        if (!isNeedCalib(downed, hallData, reference)) {
            Log.d(TAG, "calibrate: at-rest hall reading within tolerance, nothing to do");
            return true;
        }

        int[] hallMaxData = readInts(HALL_MAX_DATA_PATH);
        if (hallMaxData == null) {
            Log.e(TAG, "calibrate: needed calibration but hall_max_data unreadable, will retry next move");
            return false;
        }
        Log.d(TAG, "calibrate: hall_max_data=" + join(hallMaxData));

        if (hallMaxData[0] == 0 && hallMaxData[1] == 0) {
            // This is the exact condition that produced the
            // stuck-on-raise/overshoot-on-lower bug: hall_max_data isn't
            // populated yet even though we thought a move had completed.
            // Refuse to calibrate off it and retry on the next move
            // instead of writing garbage again.
            Log.w(TAG, "calibrate: hall_max_data is 0,0 (uninitialized) despite calling "
                    + "this post-move — will retry on next move instead of writing garbage");
            return false;
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
            return true; // computed math won't change on retry, don't loop forever
        }

        String csv = join(updated);
        if (DRY_RUN) {
            Log.w(TAG, "calibrate: would write hall_calibration=" + csv + " but DRY_RUN is on. Not writing.");
            return true;
        }
        Log.d(TAG, "calibrate: writing new hall_calibration: " + csv);
        if (!RootShell.get().writeFile(CameraMotorController.CAMERA_MOTOR_HALL_CALIBRATION, csv)) {
            Log.e(TAG, "Failed to write " + CameraMotorController.CAMERA_MOTOR_HALL_CALIBRATION);
            return false;
        }

        // Confirm the driver actually accepted what we sent rather than
        // silently clamping/rejecting it.
        String readback = RootShell.get().readFile(CameraMotorController.CAMERA_MOTOR_HALL_CALIBRATION);
        if (!csv.equals(readback == null ? null : readback.trim())) {
            Log.w(TAG, "calibrate: readback mismatch — wrote \"" + csv
                    + "\" but driver now reports \"" + readback
                    + "\". Driver is not applying what we wrote as-is.");
        } else {
            Log.d(TAG, "calibrate: readback confirms write applied");
        }
        return true;
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
