package com.omar.camerahelper;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Keeps a single persistent `su` shell open and pipes commands through it,
 * rather than spawning a new `su` process per read/write (spawning per-call
 * is what causes the visible lag/jank you'd get on a per-open/close camera
 * event if the motor write is on the UI-adjacent path).
 *
 * Each command's output is delimited with an echoed marker so we know where
 * one command's output ends and the next begins on the same stream.
 */
final class RootShell {

    private static final String TAG = "CameraHelperRootShell";
    private static final String END_MARKER = "__END_CMD__";

    private static volatile RootShell sInstance;

    private Process mProcess;
    private DataOutputStream mStdin;
    private BufferedReader mStdout;
    private final Object mLock = new Object();

    private RootShell() {
        open();
    }

    static RootShell get() {
        RootShell instance = sInstance;
        if (instance == null) {
            synchronized (RootShell.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new RootShell();
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    private void open() {
        try {
            mProcess = new ProcessBuilder("su").redirectErrorStream(true).start();
            mStdin = new DataOutputStream(mProcess.getOutputStream());
            mStdout = new BufferedReader(new InputStreamReader(mProcess.getInputStream()));
        } catch (IOException e) {
            Log.e(TAG, "Failed to open root shell", e);
            mProcess = null;
        }
    }

    private boolean isAlive() {
        return mProcess != null && isProcessAlive(mProcess);
    }

    private static boolean isProcessAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    /**
     * Runs a shell command as root and returns its stdout (trimmed),
     * or null on failure. Reopens the shell transparently if it died.
     */
    String exec(String command) {
        synchronized (mLock) {
            if (!isAlive()) {
                open();
                if (mProcess == null) {
                    return null;
                }
            }
            try {
                mStdin.writeBytes(command + "\n");
                mStdin.writeBytes("echo " + END_MARKER + "\n");
                mStdin.flush();

                StringBuilder out = new StringBuilder();
                String line;
                while ((line = mStdout.readLine()) != null) {
                    if (line.equals(END_MARKER)) {
                        break;
                    }
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    out.append(line);
                }
                return out.toString();
            } catch (IOException e) {
                Log.e(TAG, "Root shell command failed: " + command, e);
                // Shell pipe likely broken — force a reopen on next call.
                close();
                return null;
            }
        }
    }

    /** Reads a sysfs node as root. Returns null on failure. */
    String readFile(String path) {
        return exec("cat '" + path + "'");
    }

    /** Writes a sysfs node as root. Returns true on success. */
    boolean writeFile(String path, String value) {
        // printf avoids echo's platform-dependent trailing-newline quirks.
        String escaped = value.replace("'", "'\\''");
        String result = exec("printf '%s' '" + escaped + "' > '" + path + "' && echo OK || echo FAIL");
        return "OK".equals(result);
    }

    private void close() {
        try {
            if (mStdin != null) {
                mStdin.writeBytes("exit\n");
                mStdin.flush();
            }
        } catch (IOException ignored) {
        }
        if (mProcess != null) {
            mProcess.destroy();
        }
        mProcess = null;
    }
}
