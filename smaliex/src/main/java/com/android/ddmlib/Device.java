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

import java.io.File;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.android.ddmlib.AdbHelper.AdbCommandRejectedException;
import com.android.ddmlib.AdbHelper.ShellCommandUnresponsiveException;
import com.android.ddmlib.AdbHelper.TimeoutException;
import com.android.ddmlib.SyncService.SyncException;


/**
 * A Device. It can be a physical device or an emulator.
 */
public final class Device {
    /** Emulator Serial Number regexp. */
    static final String RE_EMULATOR_SN = "emulator-(\\d+)";

    /** Serial number of the device */
    private final String mSerialNumber;

    /** Name of the AVD */
    private String mAvdName = null;

    /** State of the device. */
    private DeviceState mState = null;

    /** Device properties. */
    private final PropertyFetcher mPropFetcher = new PropertyFetcher(this);
    private final Map<String, String> mMountPoints = new HashMap<>();

    private static final String LOG_TAG = "Device";
    private static final char SEPARATOR = '-';

    /**
     * Socket for the connection monitoring client connection/disconnection.
     */
    private SocketChannel mSocketChannel;

    /** Cached list of hardware characteristics */
    private Set<String> mHardwareCharacteristics;

    private String mName;

    public boolean logError = true;

    /** Returns the serial number of the device. */
    @Nonnull
    public String getSerialNumber() {
        return mSerialNumber;
    }

    /**
     * Returns the name of the AVD the emulator is running.
     * <p/>This is only valid if {@link #isEmulator()} returns true.
     * <p/>If the emulator is not running any AVD (for instance it's running from an Android source
     * tree build), this method will return "<code>&lt;build&gt;</code>".
     *
     * @return the name of the AVD or <code>null</code> if there isn't any.
     */
    @Nullable
    public String getAvdName() {
        return mAvdName;
    }

    /**
     * Sets the name of the AVD
     */
    void setAvdName(String avdName) {
        if (!isEmulator()) {
            throw new IllegalArgumentException(
                    "Cannot set the AVD name of the device is not an emulator");
        }

        mAvdName = avdName;
    }

    public String getName() {
        if (mName != null) {
            return mName;
        }

        if (isOnline()) {
            // cache name only if device is online
            mName = constructName();
            return mName;
        } else {
            return constructName();
        }
    }

    private String constructName() {
        if (isEmulator()) {
            String avdName = getAvdName();
            if (avdName != null) {
                return String.format("%s [%s]", avdName, getSerialNumber());
            } else {
                return getSerialNumber();
            }
        } else {
            String manufacturer = null;
            String model = null;

            try {
                manufacturer = cleanupStringForDisplay(
                    getSystemProperty(PROP_DEVICE_MANUFACTURER).get());
                model = cleanupStringForDisplay(
                        getSystemProperty(PROP_DEVICE_MODEL).get());
            } catch (Exception e) {
                // If there are exceptions thrown while attempting to get these properties,
                // we can just use the serial number, so ignore these exceptions.
            }

            StringBuilder sb = new StringBuilder(20);

            if (manufacturer != null) {
                sb.append(manufacturer);
                sb.append(SEPARATOR);
            }

            if (model != null) {
                sb.append(model);
                sb.append(SEPARATOR);
            }

            sb.append(getSerialNumber());
            return sb.toString();
        }
    }

    private static String cleanupStringForDisplay(String s) {
        if (s == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append('_');
            }
        }

        return sb.toString();
    }

    /**
     * Returns the state of the device.
     */
    public DeviceState getState() {
        return mState;
    }

    /**
     * Changes the state of the device.
     */
    void setState(DeviceState state) {
        mState = state;
    }

    /**
     * Convenience method that attempts to retrieve a property via
     * {@link #getSystemProperty(String)} with minimal wait time, and swallows exceptions.
     *
     * @param name the name of the value to return.
     * @return the value or <code>null</code> if the property value was not immediately available
     */
    @Nullable
    public String getProperty(String name) {
        Future<String> future = mPropFetcher.getProperty(name);
        try {
            return future.get(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            if (logError) e.printStackTrace();
        } catch (ExecutionException e) {
            if (logError) Log.d(LOG_TAG, "Get prop " + name + " failed " + e);
        } catch (java.util.concurrent.TimeoutException e) {
            if (logError) Log.d(LOG_TAG, "Get prop " + name + " timeout " + e);
        }
        return null;
    }

    /**
     * Do a potential asynchronous query for a system property.
     *
     * @param name the name of the value to return.
     * @return a {@link Future} which can be used to retrieve value of property. Future#get() can
     *         return null if property can not be retrieved.
     */
    public @Nonnull Future<String> getSystemProperty(@Nonnull String name) {
        return mPropFetcher.getProperty(name);
    }

    // The full list of features can be obtained from /etc/permissions/features*
    // However, since we only support the "watch" feature, we can determine that by simply
    // reading the build characteristics property.
    /** Returns whether this device supports the given hardware feature. */
    public boolean supportsFeature(@Nonnull HardwareFeature feature) {
        if (mHardwareCharacteristics == null) {
            try {
                String characteristics = getSystemProperty(PROP_BUILD_CHARACTERISTICS).get();
                mHardwareCharacteristics = new HashSet<>();
                Collections.addAll(mHardwareCharacteristics, characteristics.split(","));
            } catch (Exception e) {
                mHardwareCharacteristics = Collections.emptySet();
            }
        }

        return mHardwareCharacteristics.contains(feature.getCharacteristic());
    }

    /**
     * Returns a mount point.
     *
     * @param name the name of the mount point to return
     *
     * @see #MNT_EXTERNAL_STORAGE
     * @see #MNT_ROOT
     * @see #MNT_DATA
     */
    public String getMountPoint(String name) {
        return mMountPoints.get(name);
    }


    @Override
    public String toString() {
        return mSerialNumber;
    }

    /**
     * Returns if the device is ready.
     *
     * @return <code>true</code> if {@link #getState()} returns {@link DeviceState#ONLINE}.
     */
    public boolean isOnline() {
        return mState == DeviceState.ONLINE;
    }

    /**
     * Returns <code>true</code> if the device is an emulator.
     */
    public boolean isEmulator() {
        return mSerialNumber.matches(RE_EMULATOR_SN);
    }

    /**
     * Returns if the device is offline.
     *
     * @return <code>true</code> if {@link #getState()} returns {@link DeviceState#OFFLINE}.
     */
    public boolean isOffline() {
        return mState == DeviceState.OFFLINE;
    }

    /**
     * Returns if the device is in bootloader mode.
     *
     * @return <code>true</code> if {@link #getState()} returns {@link DeviceState#BOOTLOADER}.
     */
    public boolean isBootLoader() {
        return mState == DeviceState.BOOTLOADER;
    }

    /**
     * Returns a {@link SyncService} object to push / pull files to and from the device.
     *
     * @return <code>null</code> if the SyncService couldn't be created. This can happen if adb
     *            refuse to open the connection because the {@link Device} is invalid
     *            (or got disconnected).
     * @throws TimeoutException in case of timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws IOException if the connection with adb failed.
     */
    public SyncService getSyncService()
            throws TimeoutException, AdbCommandRejectedException, IOException {
        SyncService syncService = new SyncService(AndroidDebugBridge.getSocketAddress(), this);
        if (syncService.openSync()) {
            return syncService;
        }

        return null;
    }

    /**
     * Executes a shell command on the device, and sends the result to a <var>receiver</var>.
     * <p/><var>maxTimeToOutputResponse</var> is used as a maximum waiting time when expecting the
     * command output from the device.<br>
     * At any time, if the shell command does not output anything for a period longer than
     * <var>maxTimeToOutputResponse</var>, then the method will throw
     * {@link ShellCommandUnresponsiveException}.
     * <p/>For commands like log output, a <var>maxTimeToOutputResponse</var> value of 0, meaning
     * that the method will never throw and will block until the receiver's
     * {@link IShellOutputReceiver#isCancelled()} returns <code>true</code>, should be
     * used.
     *
     * @param command the shell command to execute
     * @param receiver the {@link IShellOutputReceiver} that will receives the output of the shell
     *            command
     * @param maxTimeToOutputResponse the maximum amount of time during which the command is allowed
     *            to not output any response. A value of 0 means the method will wait forever
     *            (until the <var>receiver</var> cancels the execution) for command output and
     *            never throw.
     * @param maxTimeUnits Units for non-zero {@code maxTimeToOutputResponse} values.
     * @throws TimeoutException in case of timeout on the connection when sending the command.
     * @throws AdbCommandRejectedException if adb rejects the command.
     * @throws ShellCommandUnresponsiveException in case the shell command doesn't send any output
     *            for a period longer than <var>maxTimeToOutputResponse</var>.
     * @throws IOException in case of I/O error on the connection.
     *
     * @see DdmPreferences#getTimeOut()
     */
    public void executeShellCommand(String command, IShellOutputReceiver receiver,
            long maxTimeToOutputResponse, TimeUnit maxTimeUnits)
            throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException,
            IOException {
        AdbHelper.executeRemoteCommand(AndroidDebugBridge.getSocketAddress(), command, this,
                receiver, maxTimeToOutputResponse, maxTimeUnits);
    }

    /**
     * Creates a port forwarding between a local and a remote port.
     *
     * @param localPort the local port to forward
     * @param remotePort the remote port.
     * @throws TimeoutException in case of timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws IOException in case of I/O error on the connection.
     */
    public void createForward(int localPort, int remotePort)
            throws TimeoutException, AdbCommandRejectedException, IOException {
        AdbHelper.createForward(AndroidDebugBridge.getSocketAddress(), this,
                String.format("tcp:%d", localPort),     //$NON-NLS-1$
                String.format("tcp:%d", remotePort));   //$NON-NLS-1$
    }

    /**
     * Creates a port forwarding between a local TCP port and a remote Unix Domain Socket.
     *
     * @param localPort the local port to forward
     * @param remoteSocketName name of the unix domain socket created on the device
     * @param namespace namespace in which the unix domain socket was created
     * @throws TimeoutException in case of timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws IOException in case of I/O error on the connection.
     */
    public void createForward(int localPort, String remoteSocketName,
            DeviceUnixSocketNamespace namespace) throws TimeoutException,
            AdbCommandRejectedException, IOException {
        AdbHelper.createForward(AndroidDebugBridge.getSocketAddress(), this,
                String.format("tcp:%d", localPort),     //$NON-NLS-1$
                String.format("%s:%s", namespace.getType(), remoteSocketName));   //$NON-NLS-1$
    }

    /**
     * Removes a port forwarding between a local and a remote port.
     *
     * @param localPort the local port to forward
     * @param remotePort the remote port.
     * @throws TimeoutException in case of timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws IOException in case of I/O error on the connection.
     */
    public void removeForward(int localPort, int remotePort)
            throws TimeoutException, AdbCommandRejectedException, IOException {
        AdbHelper.removeForward(AndroidDebugBridge.getSocketAddress(), this,
                String.format("tcp:%d", localPort),     //$NON-NLS-1$
                String.format("tcp:%d", remotePort));   //$NON-NLS-1$
    }

    /**
     * Removes an existing port forwarding between a local and a remote port.
     *
     * @param localPort the local port to forward
     * @param remoteSocketName the remote unix domain socket name.
     * @param namespace namespace in which the unix domain socket was created
     * @throws TimeoutException in case of timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws IOException in case of I/O error on the connection.
     */
    public void removeForward(int localPort, String remoteSocketName,
            DeviceUnixSocketNamespace namespace) throws TimeoutException,
            AdbCommandRejectedException, IOException {
        AdbHelper.removeForward(AndroidDebugBridge.getSocketAddress(), this,
                String.format("tcp:%d", localPort),     //$NON-NLS-1$
                String.format("%s:%s", namespace.getType(), remoteSocketName));   //$NON-NLS-1$
    }

    Device(String serialNumber, DeviceState deviceState) {
        mSerialNumber = serialNumber;
        mState = deviceState;
    }

    /**
     * Sets the client monitoring socket.
     * @param socketChannel the sockets
     */
    void setClientMonitoringSocket(SocketChannel socketChannel) {
        mSocketChannel = socketChannel;
    }

    /**
     * Returns the client monitoring socket.
     */
    SocketChannel getClientMonitoringSocket() {
        return mSocketChannel;
    }

    void setMountingPoint(String name, String value) {
        mMountPoints.put(name, value);
    }

    /**
     * Push a single file.
     * @param local the local filepath.
     * @param remote The remote filepath.
     *
     * @throws IOException in case of I/O error on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws TimeoutException in case of a timeout reading responses from the device.
     * @throws SyncException if file could not be pushed
     */
    public void pushFile(String local, String remote)
            throws IOException, AdbCommandRejectedException, TimeoutException, SyncException {
        SyncService sync = null;
        try {
            String targetFileName = getFileName(local);

            Log.d(targetFileName, String.format("Uploading %1$s onto device '%2$s'",
                    targetFileName, getSerialNumber()));

            sync = getSyncService();
            if (sync != null) {
                String message = String.format("Uploading file onto device '%1$s'",
                        getSerialNumber());
                Log.d(LOG_TAG, message);
                sync.pushFile(local, remote, SyncService.getNullProgressMonitor());
            } else {
                throw new IOException("Unable to open sync connection!");
            }
        } catch (TimeoutException e) {
            Log.e(LOG_TAG, "Error during Sync: timeout.");
            throw e;

        } catch (SyncException | IOException e) {
            Log.e(LOG_TAG, String.format("Error during Sync: %1$s", e.getMessage()));
            throw e;

        } finally {
            if (sync != null) {
                sync.close();
            }
        }
    }

    /**
     * Pulls a single file.
     *
     * @param remote the full path to the remote file
     * @param local The local destination.
     *
     * @throws IOException in case of an IO exception.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws TimeoutException in case of a timeout reading responses from the device.
     * @throws SyncException in case of a sync exception.
     */
    public void pullFile(String remote, String local)
            throws IOException, AdbCommandRejectedException, TimeoutException, SyncException {
        SyncService sync = null;
        try {
            String targetFileName = getFileName(remote);

            Log.d(targetFileName, String.format("Downloading %1$s from device '%2$s'",
                    targetFileName, getSerialNumber()));

            sync = getSyncService();
            if (sync != null) {
                String message = String.format("Downloading file from device '%1$s'",
                        getSerialNumber());
                Log.d(LOG_TAG, message);
                sync.pullFile(remote, local, SyncService.getNullProgressMonitor());
            } else {
                throw new IOException("Unable to open sync connection!");
            }
        } catch (TimeoutException e) {
            Log.e(LOG_TAG, "Error during Sync: timeout.");
            throw e;

        } catch (SyncException | IOException e) {
            Log.e(LOG_TAG, String.format("Error during Sync: %1$s", e.getMessage()));
            throw e;

        } finally {
            if (sync != null) {
                sync.close();
            }
        }
    }

    /**
     * Helper method to retrieve the file name given a local file path
     * @param filePath full directory path to file
     * @return {@link String} file name
     */
    private static String getFileName(String filePath) {
        return new File(filePath).getName();
    }

    /**
     * Reboot the device.
     *
     * @param into the bootloader name to reboot into, or null to just reboot the device.
     * @throws TimeoutException in case of timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws IOException
     */
    public void reboot(String into)
            throws TimeoutException, AdbCommandRejectedException, IOException {
        AdbHelper.reboot(into, AndroidDebugBridge.getSocketAddress(), this);
    }

    /**
     * Returns the ABIs supported by this device. The ABIs are sorted in preferred order, with the
     * first ABI being the most preferred.
     * @return the list of ABIs.
     */
    @Nonnull
    public List<String> getAbis() {
        List<String> abis = new ArrayList<>(2);
        String abi = getProperty(PROP_DEVICE_CPU_ABI);
        if (abi != null) {
            abis.add(abi);
        }

        abi = getProperty(PROP_DEVICE_CPU_ABI2);
        if (abi != null) {
            abis.add(abi);
        }

        return abis;
    }

    /**
     * Returns the density bucket of the device screen.
     *
     * @return the density, or 0 if it's unknown.
     */
    public int getDensity() {
        String densityValue = getProperty(PROP_DEVICE_DENSITY);
        if (densityValue != null) {
            return Integer.parseInt(densityValue);
        }

        return 0;
    }

    public static final String PROP_BUILD_VERSION = "ro.build.version.release";
    public static final String PROP_BUILD_API_LEVEL = "ro.build.version.sdk";
    public static final String PROP_BUILD_CODENAME = "ro.build.version.codename";
    public static final String PROP_DEVICE_MODEL = "ro.product.model";
    public static final String PROP_DEVICE_MANUFACTURER = "ro.product.manufacturer";
    public static final String PROP_DEVICE_CPU_ABI = "ro.product.cpu.abi";
    public static final String PROP_DEVICE_CPU_ABI2 = "ro.product.cpu.abi2";
    public static final String PROP_BUILD_CHARACTERISTICS = "ro.build.characteristics";
    public static final String PROP_DEVICE_DENSITY = "ro.sf.lcd_density";

    public static final String PROP_DEBUGGABLE = "ro.debuggable";

    /** Device level hardware features. */
    public enum HardwareFeature {
        WATCH("watch");                   // supports feature watch

        private final String mCharacteristic;

        private HardwareFeature(String characteristic) {
            mCharacteristic = characteristic;
        }

        public String getCharacteristic() {
            return mCharacteristic;
        }
    }

    public static final String MNT_EXTERNAL_STORAGE = "EXTERNAL_STORAGE";
    public static final String MNT_ROOT = "ANDROID_ROOT";
    public static final String MNT_DATA = "ANDROID_DATA";

    /**
     * The state of a device.
     */
    public static enum DeviceState {
        BOOTLOADER("bootloader"),
        OFFLINE("offline"),
        ONLINE("device"),
        RECOVERY("recovery"),
        UNAUTHORIZED("unauthorized");

        private final String mState;

        DeviceState(String state) {
            mState = state;
        }

        /**
         * Returns a {@link DeviceState} from the string returned by <code>adb devices</code>.
         *
         * @param state the device state.
         * @return a {@link DeviceState} object or <code>null</code> if the state is unknown.
         */
        @Nullable
        public static DeviceState getState(String state) {
            for (DeviceState deviceState : values()) {
                if (deviceState.mState.equals(state)) {
                    return deviceState;
                }
            }
            return null;
        }
    }

    /**
     * Namespace of a Unix Domain Socket created on the device.
     */
    public static enum DeviceUnixSocketNamespace {
        ABSTRACT("localabstract"),
        FILESYSTEM("localfilesystem"),
        RESERVED("localreserved");

        private final String mType;

        private DeviceUnixSocketNamespace(String type) {
            mType = type;
        }

        String getType() {
            return mType;
        }
    }
}
