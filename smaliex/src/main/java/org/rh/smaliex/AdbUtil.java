/*
 * Copyright (C) 2014 Riddle Hsu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.rh.smaliex;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import com.android.ddmlib.AdbHelper.AdbCommandRejectedException;
import com.android.ddmlib.AdbHelper.ShellCommandUnresponsiveException;
import com.android.ddmlib.AdbHelper.TimeoutException;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.Device;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.SyncService.SyncException;

public class AdbUtil {

    public static abstract class DeviceListener implements AndroidDebugBridge.IDeviceChangeListener {
        //public void deviceChanged(Device device, int changeMask) {
        //}
    }

    public static AndroidDebugBridge startAdb(final DeviceListener listener) {
        return startAdb("adb", listener);
    }

    public static AndroidDebugBridge startAdb(String adbExe, final DeviceListener listner) {
        try {
            Runtime.getRuntime().exec(adbExe + " start-server");
        } catch (IOException ex) {
            LLog.i(ex + ", env has no adb");
        }
        AndroidDebugBridge.initIfNeeded();
        final AndroidDebugBridge bridge = AndroidDebugBridge.createBridge();

        if (listner != null) {
            AndroidDebugBridge.addDeviceChangeListener(listner);
        }
        return bridge;
    }

    public static void stopAdb() {
        AndroidDebugBridge.terminate();
    }

    public static void pullFileToFolder(Device device, String remote, String localFolder) {
        try {
            String name = remote;
            int p = remote.lastIndexOf("/");
            if (p > -1) {
                name = remote.substring(p);
            }
            device.pullFile(remote, localFolder + File.separator + name);
        } catch (IOException | AdbCommandRejectedException | TimeoutException | SyncException  ex) {
            LLog.e("Unable to pull " + remote + ", "+ ex.getMessage());
        }
    }

    public static void shell(Device device, String cmd, IShellOutputReceiver outputReceiver) {
        try {
            device.executeShellCommand(cmd, outputReceiver, 60, TimeUnit.SECONDS);
        } catch (TimeoutException | AdbCommandRejectedException
                | ShellCommandUnresponsiveException | IOException ex) {
            if (device.logError) LLog.ex(ex);
        }
    }

    public static String shell(Device device, String cmd) {
        return shellSync(device, cmd, new String[] { "" });
    }

    public static String shellSync(Device device, String cmd, final String[] result) {
        final FutureTask<String> task = new FutureTask<>(new Callable<String>() {
            @Override
            public String call() throws Exception {
                return result[0];
            }
        });
        shell(device, cmd, new CollectingOutputReceiver() {
            @Override
            public void flush() {
                super.flush();
                result[0] = getOutput().trim();
                task.run();
            }
        });
        final int waitSec = 10;
        try {
            return task.get(waitSec, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException
                | java.util.concurrent.TimeoutException ex) {
            LLog.i(ex + " " + waitSec + "s timeout cmd:" + cmd);
        }

        return result[0];
    }

    public static boolean isFileExist(Device device, String filePath) {
        String result = shellSync(device, "ls " + filePath,
                new String[] { "" });
        return !result.contains("No such file or directory") || result.equals(filePath);
    }

    public static String[] getFileList(Device device, String dir) {
        String[] result = { "" };
        shellSync(device, "ls -1 " + dir, result);
        String resultStr = result[0];
        if (resultStr != null && resultStr.contains("Unknown option")) {
            shellSync(device, "ls " + dir, result);
            resultStr = result[0];
        }
        if (resultStr == null || resultStr.contains("No such file or directory")) {
            return new String[0];
        }
        return resultStr.split("[\r\n]+");
    }

    public static String[] getBootClassPath(Device device) {
        return getPathByEnv(device, "BOOTCLASSPATH");
    }

    public static String[] getPathByEnv(Device device, String envVarName) {
        return AdbUtil.shell(device, "echo $" + envVarName).split(":");
    }

    public static abstract class OneTimeAction {
        public abstract void run(Device device) throws Exception;
        public String onDisconnectedMsg() {
            return "";
        }
    }

    final static boolean[] state = new boolean[2];

    public static void runOneTimeAction(final OneTimeAction action) {
        final int RUNNING = 0;
        final int DONE = 1;

        startAdb(new DeviceListener() {
            @Override
            public void deviceConnected(Device device) {
                if (state[RUNNING]) {
                    LLog.i("Only execute on first device, ignoring " + device.getName());
                    return;
                }
                state[RUNNING] = true;
                try {
                    action.run(device);
                } catch (Exception e) {
                    LLog.ex(e);
                } finally {
                    synchronized (state) {
                        state.notifyAll();
                        state[1] = true;
                    }
                }
            }

            @Override
            public void deviceDisconnected(Device device) {
                LLog.i(device.getName() + " disconnected. " + action.onDisconnectedMsg());
            }
        });
        int checkDeviceCount = 6;
        while (checkDeviceCount-- > 0 && !state[RUNNING]) {
            synchronized (state) {
                try {
                    state.wait(1000);
                } catch (InterruptedException ex) {
                }
                if (!state[DONE] && !state[RUNNING]) {
                    LLog.i("Waiting device countdown " + checkDeviceCount);
                }
            }
        }
        if (checkDeviceCount > 0) {
            synchronized (state) {
                if (!state[DONE]) {
                    try {
                        state.wait();
                    } catch (InterruptedException ex) {
                    }
                }
            }
        } else {
            LLog.i("No connected device or adb not started, exit.");
        }
        stopAdb();
    }
}
