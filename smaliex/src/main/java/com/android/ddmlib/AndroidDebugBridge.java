/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ddmlib;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import com.android.ddmlib.Log.LogLevel;

/**
 * A connection to the host-side android debug bridge (adb)
 * <p/>This is the central point to communicate with any devices, emulators, or the applications
 * running on them.
 * <p/><b>{@link #init(boolean)} must be called before anything is done.</b>
 */
public final class AndroidDebugBridge {

    /*
     * Minimum and maximum version of adb supported. This correspond to
     * ADB_SERVER_VERSION found in //device/tools/adb/adb.h
     */

    private static final int ADB_VERSION_MICRO_MIN = 20;
    private static final int ADB_VERSION_MICRO_MAX = -1;

    private static final Pattern sAdbVersion = Pattern.compile(
            "^.*(\\d+)\\.(\\d+)\\.(\\d+)$"); //$NON-NLS-1$

    private static final String ADB = "adb"; //$NON-NLS-1$
    private static final String DDMS = "ddms"; //$NON-NLS-1$
    private static final String SERVER_PORT_ENV_VAR = "ANDROID_ADB_SERVER_PORT"; //$NON-NLS-1$

    // Where to find the ADB bridge.
    static final String DEFAULT_ADB_HOST = "127.0.0.1"; //$NON-NLS-1$
    static final int DEFAULT_ADB_PORT = 5037;

    /** Port where adb server will be started **/
    private static int sAdbServerPort = 0;

    private static InetAddress sHostAddr;
    private static InetSocketAddress sSocketAddr;

    private static AndroidDebugBridge sThis;
    private static boolean sInitialized = false;

    /** Full path to adb. */
    private String mAdbOsLocation = null;

    private boolean mVersionCheck;

    private boolean mStarted = false;

    private DeviceMonitor mDeviceMonitor;

    private static final ArrayList<IDebugBridgeChangeListener> sBridgeListeners =
        new ArrayList<>();
    private static final ArrayList<IDeviceChangeListener> sDeviceListeners =
        new ArrayList<>();

    // lock object for synchronization
    private static final Object sLock = sBridgeListeners;

    /**
     * Classes which implement this interface provide a method that deals
     * with {@link AndroidDebugBridge} changes.
     */
    public interface IDebugBridgeChangeListener {
        /**
         * Sent when a new {@link AndroidDebugBridge} is connected.
         * <p/>
         * This is sent from a non UI thread.
         * @param bridge the new {@link AndroidDebugBridge} object.
         */
        public void bridgeChanged(AndroidDebugBridge bridge);
    }

    /**
     * Classes which implement this interface provide methods that deal
     * with {@link IDevice} addition, deletion, and changes.
     */
    public interface IDeviceChangeListener {
        /**
         * Sent when the a device is connected to the {@link AndroidDebugBridge}.
         * <p/>
         * This is sent from a non UI thread.
         * @param device the new device.
         */
        public void deviceConnected(Device device);

        /**
         * Sent when the a device is connected to the {@link AndroidDebugBridge}.
         * <p/>
         * This is sent from a non UI thread.
         * @param device the new device.
         */
        public void deviceDisconnected(Device device);

        /**
         * Sent when a device data changed, or when clients are started/terminated on the device.
         * <p/>
         * This is sent from a non UI thread.
         * @param device the device that was updated.
         * @param changeMask the mask describing what changed. It can contain any of the following
         * values: {@link IDevice#CHANGE_BUILD_INFO}, {@link IDevice#CHANGE_STATE},
         * {@link IDevice#CHANGE_CLIENT_LIST}
         */
        public void deviceChanged(Device device, int changeMask);
    }

    /**
     * Initialized the library only if needed.
     */
    public static synchronized void initIfNeeded() {
        if (sInitialized) {
            return;
        }
        init();
    }

    /**
     * Initializes the <code>ddm</code> library.
     * <p/>This must be called once <b>before</b> any call to
     * {@link #createBridge(String, boolean)}.
     * <p>The library can be initialized in 2 ways:
     * <ul>
     * <li>Mode 1: <var>clientSupport</var> == <code>true</code>.<br>The library monitors the
     * devices and the applications running on them. It will connect to each application, as a
     * debugger of sort, to be able to interact with them through JDWP packets.</li>
     * <li>Mode 2: <var>clientSupport</var> == <code>false</code>.<br>The library only monitors
     * devices. The applications are left untouched, letting other tools built on
     * <code>ddmlib</code> to connect a debugger to them.</li>
     * </ul>
     * <p/><b>Only one tool can run in mode 1 at the same time.</b>
     * <p/>Note that mode 1 does not prevent debugging of applications running on devices. Mode 1
     * lets debuggers connect to <code>ddmlib</code> which acts as a proxy between the debuggers and
     * the applications to debug.
     * <p/>The preferences of <code>ddmlib</code> should also be initialized with whatever default
     * values were changed from the default values.
     * <p/>When the application quits, {@link #terminate()} should be called.
     * @see AndroidDebugBridge#createBridge(String, boolean)
     * @see DdmPreferences
     */
    public static synchronized void init() {
        if (sInitialized) {
            throw new IllegalStateException("AndroidDebugBridge.init() has already been called.");
        }
        sInitialized = true;

        // Determine port and instantiate socket address.
        initAdbSocketAddr();
    }

    /**
     * Terminates the ddm library. This must be called upon application termination.
     */
    public static synchronized void terminate() {
        // kill the monitoring services
        if (sThis != null && sThis.mDeviceMonitor != null) {
            sThis.mDeviceMonitor.stop();
            sThis.mDeviceMonitor = null;
        }
        sBridgeListeners.clear();
        sDeviceListeners.clear();
        sInitialized = false;
    }

    /**
     * Returns the socket address of the ADB server on the host.
     */
    public static InetSocketAddress getSocketAddress() {
        return sSocketAddr;
    }

    /**
     * Creates a {@link AndroidDebugBridge} that is not linked to any particular executable.
     * <p/>This bridge will expect adb to be running. It will not be able to start/stop/restart
     * adb.
     * <p/>If a bridge has already been started, it is directly returned with no changes (similar
     * to calling {@link #getBridge()}).
     * @return a connected bridge.
     */
    public static AndroidDebugBridge createBridge() {
        synchronized (sLock) {
            if (sThis != null) {
                return sThis;
            }

            try {
                sThis = new AndroidDebugBridge();
                sThis.start();
            } catch (InvalidParameterException e) {
                sThis = null;
            }

            // because the listeners could remove themselves from the list while processing
            // their event callback, we make a copy of the list and iterate on it instead of
            // the main list.
            // This mostly happens when the application quits.
            IDebugBridgeChangeListener[] listenersCopy = sBridgeListeners.toArray(
                    new IDebugBridgeChangeListener[sBridgeListeners.size()]);

            // notify the listeners of the change
            for (IDebugBridgeChangeListener listener : listenersCopy) {
                // we attempt to catch any exception so that a bad listener doesn't kill our
                // thread
                try {
                    listener.bridgeChanged(sThis);
                } catch (Exception e) {
                    Log.e(DDMS, e);
                }
            }

            return sThis;
        }
    }


    /**
     * Creates a new debug bridge from the location of the command line tool.
     * <p/>
     * Any existing server will be disconnected, unless the location is the same and
     * <code>forceNewBridge</code> is set to false.
     * @param osLocation the location of the command line tool 'adb'
     * @param forceNewBridge force creation of a new bridge even if one with the same location
     * already exists.
     * @return a connected bridge.
     */
    public static AndroidDebugBridge createBridge(String osLocation, boolean forceNewBridge) {
        synchronized (sLock) {
            if (sThis != null) {
                if (sThis.mAdbOsLocation != null && sThis.mAdbOsLocation.equals(osLocation) &&
                        !forceNewBridge) {
                    return sThis;
                } else {
                    // stop the current server
                    sThis.stop();
                }
            }

            try {
                sThis = new AndroidDebugBridge(osLocation);
                sThis.start();
            } catch (InvalidParameterException e) {
                sThis = null;
            }

            // because the listeners could remove themselves from the list while processing
            // their event callback, we make a copy of the list and iterate on it instead of
            // the main list.
            // This mostly happens when the application quits.
            IDebugBridgeChangeListener[] listenersCopy = sBridgeListeners.toArray(
                    new IDebugBridgeChangeListener[sBridgeListeners.size()]);

            // notify the listeners of the change
            for (IDebugBridgeChangeListener listener : listenersCopy) {
                // we attempt to catch any exception so that a bad listener doesn't kill our
                // thread
                try {
                    listener.bridgeChanged(sThis);
                } catch (Exception e) {
                    Log.e(DDMS, e);
                }
            }

            return sThis;
        }
    }

    /**
     * Returns the current debug bridge. Can be <code>null</code> if none were created.
     */
    public static AndroidDebugBridge getBridge() {
        return sThis;
    }

    /**
     * Disconnects the current debug bridge, and destroy the object.
     * <p/>This also stops the current adb host server.
     * <p/>
     * A new object will have to be created with {@link #createBridge(String, boolean)}.
     */
    public static void disconnectBridge() {
        synchronized (sLock) {
            if (sThis != null) {
                sThis.stop();
                sThis = null;

                // because the listeners could remove themselves from the list while processing
                // their event callback, we make a copy of the list and iterate on it instead of
                // the main list.
                // This mostly happens when the application quits.
                IDebugBridgeChangeListener[] listenersCopy = sBridgeListeners.toArray(
                        new IDebugBridgeChangeListener[sBridgeListeners.size()]);

                // notify the listeners.
                for (IDebugBridgeChangeListener listener : listenersCopy) {
                    // we attempt to catch any exception so that a bad listener doesn't kill our
                    // thread
                    try {
                        listener.bridgeChanged(sThis);
                    } catch (Exception e) {
                        Log.e(DDMS, e);
                    }
                }
            }
        }
    }

    /**
     * Adds the listener to the collection of listeners who will be notified when a new
     * {@link AndroidDebugBridge} is connected, by sending it one of the messages defined
     * in the {@link IDebugBridgeChangeListener} interface.
     * @param listener The listener which should be notified.
     */
    public static void addDebugBridgeChangeListener(IDebugBridgeChangeListener listener) {
        synchronized (sLock) {
            if (!sBridgeListeners.contains(listener)) {
                sBridgeListeners.add(listener);
                if (sThis != null) {
                    // we attempt to catch any exception so that a bad listener doesn't kill our
                    // thread
                    try {
                        listener.bridgeChanged(sThis);
                    } catch (Exception e) {
                        Log.e(DDMS, e);
                    }
                }
            }
        }
    }

    /**
     * Removes the listener from the collection of listeners who will be notified when a new
     * {@link AndroidDebugBridge} is started.
     * @param listener The listener which should no longer be notified.
     */
    public static void removeDebugBridgeChangeListener(IDebugBridgeChangeListener listener) {
        synchronized (sLock) {
            sBridgeListeners.remove(listener);
        }
    }

    /**
     * Adds the listener to the collection of listeners who will be notified when a {@link IDevice}
     * is connected, disconnected, or when its properties,
     * by sending it one of the messages defined in the {@link IDeviceChangeListener} interface.
     * @param listener The listener which should be notified.
     */
    public static void addDeviceChangeListener(IDeviceChangeListener listener) {
        synchronized (sLock) {
            if (!sDeviceListeners.contains(listener)) {
                sDeviceListeners.add(listener);
            }
        }
    }

    /**
     * Removes the listener from the collection of listeners who will be notified when a
     * {@link IDevice} is connected, disconnected.
     * @param listener The listener which should no longer be notified.
     */
    public static void removeDeviceChangeListener(IDeviceChangeListener listener) {
        synchronized (sLock) {
            sDeviceListeners.remove(listener);
        }
    }

    /**
     * Creates a new bridge.
     * @param osLocation the location of the command line tool
     * @throws InvalidParameterException
     */
    private AndroidDebugBridge(String osLocation) throws InvalidParameterException {
        if (osLocation == null || osLocation.isEmpty()) {
            throw new InvalidParameterException();
        }
        mAdbOsLocation = osLocation;

        checkAdbVersion();
    }

    /**
     * Creates a new bridge not linked to any particular adb executable.
     */
    private AndroidDebugBridge() {
    }

    /**
     * Queries adb for its version number and checks it against {@link #ADB_VERSION_MICRO_MIN} and
     * {@link #ADB_VERSION_MICRO_MAX}
     */
    private void checkAdbVersion() {
        // default is bad check
        mVersionCheck = false;

        if (mAdbOsLocation == null) {
            return;
        }

        String[] command = new String[2];
        command[0] = mAdbOsLocation;
        command[1] = "version"; //$NON-NLS-1$
        Log.d(DDMS, String.format("Checking '%1$s version'", mAdbOsLocation));
        Process process;
        try {
            process = Runtime.getRuntime().exec(command);
        } catch (IOException e) {
            boolean exists = new File(mAdbOsLocation).exists();
            String msg;
            if (exists) {
                msg = String.format(
                        "Unexpected exception '%1$s' while attempting to get adb version from '%2$s'",
                        e.getMessage(), mAdbOsLocation);
            } else {
                msg = "Unable to locate adb.\n" +
                      "Please use SDK Manager and check if Android SDK platform-tools are installed.";
            }
            Log.logAndDisplay(LogLevel.ERROR, ADB, msg);
            return;
        }

        ArrayList<String> errorOutput = new ArrayList<>();
        ArrayList<String> stdOutput = new ArrayList<>();
        int status;
        try {
            status = grabProcessOutput(process, errorOutput, stdOutput,
                    true /* waitForReaders */);
        } catch (InterruptedException e) {
            return;
        }

        if (status != 0) {
            StringBuilder builder = new StringBuilder("'adb version' failed!"); //$NON-NLS-1$
            for (String error : errorOutput) {
                builder.append('\n');
                builder.append(error);
            }
            Log.logAndDisplay(LogLevel.ERROR, ADB, builder.toString());
        }

        // check both stdout and stderr
        boolean versionFound = false;
        for (String line : stdOutput) {
            versionFound = scanVersionLine(line);
            if (versionFound) {
                break;
            }
        }
        if (!versionFound) {
            for (String line : errorOutput) {
                versionFound = scanVersionLine(line);
                if (versionFound) {
                    break;
                }
            }
        }

        if (!versionFound) {
            // if we get here, we failed to parse the output.
            StringBuilder builder = new StringBuilder(
                    "Failed to parse the output of 'adb version':\n"); //$NON-NLS-1$
            builder.append("Standard Output was:\n"); //$NON-NLS-1$
            for (String line : stdOutput) {
                builder.append(line);
                builder.append('\n');
            }
            builder.append("\nError Output was:\n"); //$NON-NLS-1$
            for (String line : errorOutput) {
                builder.append(line);
                builder.append('\n');
            }
            Log.logAndDisplay(LogLevel.ERROR, ADB, builder.toString());
        }
    }

    /**
     * Scans a line resulting from 'adb version' for a potential version number.
     * <p/>
     * If a version number is found, it checks the version number against what is expected
     * by this version of ddms.
     * <p/>
     * Returns true when a version number has been found so that we can stop scanning,
     * whether the version number is in the acceptable range or not.
     *
     * @param line The line to scan.
     * @return True if a version number was found (whether it is acceptable or not).
     */
    @SuppressWarnings("all") // With Eclipse 3.6, replace by @SuppressWarnings("unused")
    private boolean scanVersionLine(String line) {
        if (line != null) {
            Matcher matcher = sAdbVersion.matcher(line);
            if (matcher.matches()) {
                int majorVersion = Integer.parseInt(matcher.group(1));
                int minorVersion = Integer.parseInt(matcher.group(2));
                int microVersion = Integer.parseInt(matcher.group(3));

                // check only the micro version for now.
                if (microVersion < ADB_VERSION_MICRO_MIN) {
                    String message = String.format(
                            "Required minimum version of adb: %1$d.%2$d.%3$d." //$NON-NLS-1$
                            + "Current version is %1$d.%2$d.%4$d", //$NON-NLS-1$
                            majorVersion, minorVersion, ADB_VERSION_MICRO_MIN,
                            microVersion);
                    Log.logAndDisplay(LogLevel.ERROR, ADB, message);
                } else if (ADB_VERSION_MICRO_MAX != -1 &&
                        microVersion > ADB_VERSION_MICRO_MAX) {
                    String message = String.format(
                            "Required maximum version of adb: %1$d.%2$d.%3$d." //$NON-NLS-1$
                            + "Current version is %1$d.%2$d.%4$d", //$NON-NLS-1$
                            majorVersion, minorVersion, ADB_VERSION_MICRO_MAX,
                            microVersion);
                    Log.logAndDisplay(LogLevel.ERROR, ADB, message);
                } else {
                    mVersionCheck = true;
                }

                return true;
            }
        }
        return false;
    }

    /**
     * Starts the debug bridge.
     *
     * @return true if success.
     */
    boolean start() {
        if (mAdbOsLocation != null && sAdbServerPort != 0 && (!mVersionCheck || !startAdb())) {
            return false;
        }

        mStarted = true;

        // now that the bridge is connected, we start the underlying services.
        mDeviceMonitor = new DeviceMonitor(this);
        mDeviceMonitor.start();

        return true;
    }

   /**
     * Kills the debug bridge, and the adb host server.
     * @return true if success
     */
    boolean stop() {
        // if we haven't started we return false;
        if (!mStarted) {
            return false;
        }

        // kill the monitoring services
        if (mDeviceMonitor != null) {
            mDeviceMonitor.stop();
            mDeviceMonitor = null;
        }

        if (!stopAdb()) {
            return false;
        }

        mStarted = false;
        return true;
    }

    /**
     * Restarts adb, but not the services around it.
     * @return true if success.
     */
    public boolean restart() {
        if (mAdbOsLocation == null) {
            Log.e(ADB,
                    "Cannot restart adb when AndroidDebugBridge is created without the location of adb."); //$NON-NLS-1$
            return false;
        }

        if (sAdbServerPort == 0) {
            Log.e(ADB, "ADB server port for restarting AndroidDebugBridge is not set."); //$NON-NLS-1$
            return false;
        }

        if (!mVersionCheck) {
            Log.logAndDisplay(LogLevel.ERROR, ADB,
                    "Attempting to restart adb, but version check failed!"); //$NON-NLS-1$
            return false;
        }
        synchronized (this) {
            stopAdb();

            boolean restart = startAdb();

            if (restart && mDeviceMonitor == null) {
                mDeviceMonitor = new DeviceMonitor(this);
                mDeviceMonitor.start();
            }

            return restart;
        }
    }

    /**
     * Notify the listener of a new {@link IDevice}.
     * <p/>
     * The notification of the listeners is done in a synchronized block. It is important to
     * expect the listeners to potentially access various methods of {@link IDevice} as well as
     * {@link #getDevices()} which use internal locks.
     * <p/>
     * For this reason, any call to this method from a method of {@link DeviceMonitor},
     * {@link IDevice} which is also inside a synchronized block, should first synchronize on
     * the {@link AndroidDebugBridge} lock. Access to this lock is done through {@link #getLock()}.
     * @param device the new <code>IDevice</code>.
     * @see #getLock()
     */
    static void deviceConnected(Device device) {
        // because the listeners could remove themselves from the list while processing
        // their event callback, we make a copy of the list and iterate on it instead of
        // the main list.
        // This mostly happens when the application quits.
        IDeviceChangeListener[] listenersCopy;
        synchronized (sLock) {
            listenersCopy = sDeviceListeners.toArray(
                    new IDeviceChangeListener[sDeviceListeners.size()]);
        }

        // Notify the listeners
        for (IDeviceChangeListener listener : listenersCopy) {
            // we attempt to catch any exception so that a bad listener doesn't kill our
            // thread
            try {
                listener.deviceConnected(device);
            } catch (Exception e) {
                Log.e(DDMS, e);
            }
        }
    }

    /**
     * Notify the listener of a disconnected {@link IDevice}.
     * <p/>
     * The notification of the listeners is done in a synchronized block. It is important to
     * expect the listeners to potentially access various methods of {@link IDevice} as well as
     * {@link #getDevices()} which use internal locks.
     * <p/>
     * For this reason, any call to this method from a method of {@link DeviceMonitor},
     * {@link IDevice} which is also inside a synchronized block, should first synchronize on
     * the {@link AndroidDebugBridge} lock. Access to this lock is done through {@link #getLock()}.
     * @param device the disconnected <code>IDevice</code>.
     * @see #getLock()
     */
    static void deviceDisconnected(Device device) {
        // because the listeners could remove themselves from the list while processing
        // their event callback, we make a copy of the list and iterate on it instead of
        // the main list.
        // This mostly happens when the application quits.
        IDeviceChangeListener[] listenersCopy;
        synchronized (sLock) {
            listenersCopy = sDeviceListeners.toArray(
                    new IDeviceChangeListener[sDeviceListeners.size()]);
        }

        // Notify the listeners
        for (IDeviceChangeListener listener : listenersCopy) {
            // we attempt to catch any exception so that a bad listener doesn't kill our
            // thread
            try {
                listener.deviceDisconnected(device);
            } catch (Exception e) {
                Log.e(DDMS, e);
            }
        }
    }

    /**
     * Notify the listener of a modified {@link IDevice}.
     * <p/>
     * The notification of the listeners is done in a synchronized block. It is important to
     * expect the listeners to potentially access various methods of {@link IDevice} as well as
     * {@link #getDevices()} which use internal locks.
     * <p/>
     * For this reason, any call to this method from a method of {@link DeviceMonitor},
     * {@link IDevice} which is also inside a synchronized block, should first synchronize on
     * the {@link AndroidDebugBridge} lock. Access to this lock is done through {@link #getLock()}.
     * @param device the modified <code>IDevice</code>.
     * @see #getLock()
     */
    static void deviceChanged(Device device, int changeMask) {
        // because the listeners could remove themselves from the list while processing
        // their event callback, we make a copy of the list and iterate on it instead of
        // the main list.
        // This mostly happens when the application quits.
        IDeviceChangeListener[] listenersCopy;
        synchronized (sLock) {
            listenersCopy = sDeviceListeners.toArray(
                    new IDeviceChangeListener[sDeviceListeners.size()]);
        }

        // Notify the listeners
        for (IDeviceChangeListener listener : listenersCopy) {
            // we attempt to catch any exception so that a bad listener doesn't kill our
            // thread
            try {
                listener.deviceChanged(device, changeMask);
            } catch (Exception e) {
                Log.e(DDMS, e);
            }
        }
    }

    /**
     * Returns the {@link DeviceMonitor} object.
     */
    DeviceMonitor getDeviceMonitor() {
        return mDeviceMonitor;
    }

    /**
     * Starts the adb host side server.
     * @return true if success
     */
    synchronized boolean startAdb() {
        if (mAdbOsLocation == null) {
            Log.e(ADB,
                "Cannot start adb when AndroidDebugBridge is created without the location of adb."); //$NON-NLS-1$
            return false;
        }

        if (sAdbServerPort == 0) {
            Log.w(ADB, "ADB server port for starting AndroidDebugBridge is not set."); //$NON-NLS-1$
            return false;
        }

        Process proc;
        int status = -1;

        String[] command = getAdbLaunchCommand("start-server");
        String commandString = join(command, ",");
        try {
            Log.d(DDMS, String.format("Launching '%1$s' to ensure ADB is running.", commandString));
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            if (DdmPreferences.getUseAdbHost()) {
                String adbHostValue = DdmPreferences.getAdbHostValue();
                if (adbHostValue != null && !adbHostValue.isEmpty()) {
                    Map<String, String> env = processBuilder.environment();
                    env.put("ADBHOST", adbHostValue);
                }
            }
            proc = processBuilder.start();

            ArrayList<String> errorOutput = new ArrayList<>();
            ArrayList<String> stdOutput = new ArrayList<>();
            status = grabProcessOutput(proc, errorOutput, stdOutput, false /* waitForReaders */);
        } catch (IOException | InterruptedException ioe) {
            Log.e(DDMS, "Unable to run 'adb': " + ioe.getMessage()); //$NON-NLS-1$
            // we'll return false;
        }

        if (status != 0) {
            Log.e(DDMS,
                String.format("'%1$s' failed -- run manually if necessary", commandString)); //$NON-NLS-1$
            return false;
        } else {
            Log.d(DDMS, String.format("'%1$s' succeeded", commandString)); //$NON-NLS-1$
            return true;
        }
    }

    private String[] getAdbLaunchCommand(String option) {
        List<String> command = new ArrayList<>(4);
        command.add(mAdbOsLocation);
        if (sAdbServerPort != DEFAULT_ADB_PORT) {
            command.add("-P"); //$NON-NLS-1$
            command.add(Integer.toString(sAdbServerPort));
        }
        command.add(option);
        return command.toArray(new String[command.size()]);
    }

    /**
     * Stops the adb host side server.
     *
     * @return true if success
     */
    private synchronized boolean stopAdb() {
        if (mAdbOsLocation == null) {
            Log.e(ADB,
                "Cannot stop adb when AndroidDebugBridge is created without the location of adb.");
            return false;
        }

        if (sAdbServerPort == 0) {
            Log.e(ADB, "ADB server port for restarting AndroidDebugBridge is not set");
            return false;
        }

        Process proc;
        int status = -1;

		String[] command = getAdbLaunchCommand("kill-server"); //$NON-NLS-1$
		try {
			proc = Runtime.getRuntime().exec(command);
			status = proc.waitFor();
		} catch (IOException | InterruptedException ioe) {
			// we'll return false;
		}
        // we'll return false;


        String commandString = join(command, ",");
        if (status != 0) {
            Log.w(DDMS, String.format("'%1$s' failed -- run manually if necessary", commandString));
            return false;
        } else {
            Log.d(DDMS, String.format("'%1$s' succeeded", commandString));
            return true;
        }
    }

    /**
     * Get the stderr/stdout outputs of a process and return when the process is done.
     * Both <b>must</b> be read or the process will block on windows.
     * @param process The process to get the output from
     * @param errorOutput The array to store the stderr output. cannot be null.
     * @param stdOutput The array to store the stdout output. cannot be null.
     * @param waitForReaders if true, this will wait for the reader threads.
     * @return the process return code.
     * @throws InterruptedException
     */
    private static int grabProcessOutput(final Process process, final ArrayList<String> errorOutput,
            final ArrayList<String> stdOutput, boolean waitForReaders)
            throws InterruptedException {
        assert errorOutput != null;
        assert stdOutput != null;
        // read the lines as they come. if null is returned, it's
        // because the process finished
        Thread t1 = new Thread("") { //$NON-NLS-1$
            @Override
            public void run() {
                // create a buffer to read the stderr output
                InputStreamReader is = new InputStreamReader(process.getErrorStream());
                BufferedReader errReader = new BufferedReader(is);

                try {
                    while (true) {
                        String line = errReader.readLine();
                        if (line != null) {
                            Log.e(ADB, line);
                            errorOutput.add(line);
                        } else {
                            break;
                        }
                    }
                } catch (IOException e) {
                    // do nothing.
                }
            }
        };

        Thread t2 = new Thread("") { //$NON-NLS-1$
            @Override
            public void run() {
                InputStreamReader is = new InputStreamReader(process.getInputStream());
                BufferedReader outReader = new BufferedReader(is);

                try {
                    while (true) {
                        String line = outReader.readLine();
                        if (line != null) {
                            Log.d(ADB, line);
                            stdOutput.add(line);
                        } else {
                            break;
                        }
                    }
                } catch (IOException e) {
                    // do nothing.
                }
            }
        };

        t1.start();
        t2.start();

        // it looks like on windows process#waitFor() can return
        // before the thread have filled the arrays, so we wait for both threads and the
        // process itself.
        if (waitForReaders) {
            try {
                t1.join();
            } catch (InterruptedException e) {
            }
            try {
                t2.join();
            } catch (InterruptedException e) {
            }
        }

        // get the return code from the process
        return process.waitFor();
    }

    /**
     * Returns the singleton lock used by this class to protect any access to the listener.
     * <p/>
     * This includes adding/removing listeners, but also notifying listeners of new bridges,
     * devices, and clients.
     */
    static Object getLock() {
        return sLock;
    }

    /**
     * Instantiates sSocketAddr with the address of the host's adb process.
     */
    private static void initAdbSocketAddr() {
        try {
            sAdbServerPort = getAdbServerPort();
            sHostAddr = InetAddress.getByName(DEFAULT_ADB_HOST);
            sSocketAddr = new InetSocketAddress(sHostAddr, sAdbServerPort);
        } catch (UnknownHostException e) {
            // localhost should always be known.
        }
    }

    /**
     * Returns the port where adb server should be launched. This looks at:
     * <ol>
     *     <li>The system property ANDROID_ADB_SERVER_PORT</li>
     *     <li>The environment variable ANDROID_ADB_SERVER_PORT</li>
     *     <li>Defaults to {@link #DEFAULT_ADB_PORT} if neither the system property nor the env var
     *     are set.</li>
     * </ol>
     *
     * @return The port number where the host's adb should be expected or started.
     */
    private static int getAdbServerPort() {
        // check system property
        Integer prop = Integer.getInteger(SERVER_PORT_ENV_VAR);
        if (prop != null) {
            try {
                return validateAdbServerPort(prop.toString());
            } catch (IllegalArgumentException e) {
                String msg = String.format(
                        "Invalid value (%1$s) for ANDROID_ADB_SERVER_PORT system property.",
                        prop);
                Log.w(DDMS, msg);
            }
        }

        // when system property is not set or is invalid, parse environment property
        try {
            String env = System.getenv(SERVER_PORT_ENV_VAR);
            if (env != null) {
                return validateAdbServerPort(env);
            }
        } catch (SecurityException ex) {
            // A security manager has been installed that doesn't allow access to env vars.
            // So an environment variable might have been set, but we can't tell.
            // Let's log a warning and continue with ADB's default port.
            // The issue is that adb would be started (by the forked process having access
            // to the env vars) on the desired port, but within this process, we can't figure out
            // what that port is. However, a security manager not granting access to env vars
            // but allowing to fork is a rare and interesting configuration, so the right
            // thing seems to be to continue using the default port, as forking is likely to
            // fail later on in the scenario of the security manager.
            Log.w(DDMS,
                    "No access to env variables allowed by current security manager. "
                            + "If you've set ANDROID_ADB_SERVER_PORT: it's being ignored.");
        } catch (IllegalArgumentException e) {
            String msg = String.format(
                    "Invalid value (%1$s) for ANDROID_ADB_SERVER_PORT environment variable (%2$s).",
                    prop, e.getMessage());
            Log.w(DDMS, msg);
        }

        // use default port if neither are set
        return DEFAULT_ADB_PORT;
    }

    /**
     * Returns the integer port value if it is a valid value for adb server port
     * @param adbServerPort adb server port to validate
     * @return {@code adbServerPort} as a parsed integer
     * @throws IllegalArgumentException when {@code adbServerPort} is not bigger than 0 or it is
     * not a number at all
     */
    private static int validateAdbServerPort(@Nonnull String adbServerPort)
            throws IllegalArgumentException {
        try {
            // C tools (adb, emulator) accept hex and octal port numbers, so need to accept them too
            int port = Integer.decode(adbServerPort);
            if (port <= 0 || port >= 65535) {
                throw new IllegalArgumentException("Should be > 0 and < 65535");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a valid port number");
        }
    }

	static String join(final String[] strs, final String delimiter) {
		if (strs == null) {
			return "";
		}
		if (strs.length < 2) {
			return strs[0];
		}

		StringBuilder buffer = new StringBuilder(strs[0]);
		for (int i = 1; i < strs.length; i++) {
			buffer.append(delimiter).append(strs[i]);
		}

		return buffer.toString();
	}
}
