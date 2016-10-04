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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ddmlib.AdbHelper.AdbResponse;
import com.android.ddmlib.AdbHelper.TimeoutException;
import com.android.ddmlib.Device.DeviceState;

/**
 * A Device monitor. This connects to the Android Debug Bridge and get device and
 * debuggable process information from it.
 */
final class DeviceMonitor {
    private static final String ADB_TRACK_DEVICES_COMMAND = "host:track-devices";

    private final AndroidDebugBridge mServer;
    private DeviceListMonitorTask mDeviceListMonitorTask;

    private final ArrayList<Device> mDevices = new ArrayList<>();

    /**
     * Creates a new {@link DeviceMonitor} object and links it to the running
     * {@link AndroidDebugBridge} object.
     * @param server the running {@link AndroidDebugBridge}.
     */
    DeviceMonitor(@NonNull AndroidDebugBridge server) {
        mServer = server;
    }

    /**
     * Starts the monitoring.
     */
    void start() {
        mDeviceListMonitorTask = new DeviceListMonitorTask(mServer, new DeviceListUpdateListener());
        new Thread(mDeviceListMonitorTask, "Device List Monitor").start(); //$NON-NLS-1$
    }

    /**
     * Stops the monitoring.
     */
    void stop() {
        if (mDeviceListMonitorTask != null) {
            mDeviceListMonitorTask.stop();
        }
    }

    /**
     * Returns whether the monitor is currently connected to the debug bridge server.
     */
    boolean isMonitoring() {
        return mDeviceListMonitorTask != null && mDeviceListMonitorTask.isMonitoring();
    }

    int getConnectionAttemptCount() {
        return mDeviceListMonitorTask == null ? 0
                : mDeviceListMonitorTask.getConnectionAttemptCount();
    }

    int getRestartAttemptCount() {
        return mDeviceListMonitorTask == null ? 0 : mDeviceListMonitorTask.getRestartAttemptCount();
    }

    boolean hasInitialDeviceList() {
        return mDeviceListMonitorTask != null && mDeviceListMonitorTask.hasInitialDeviceList();
    }

    /**
     * Returns the devices.
     */
    @NonNull
    Device[] getDevices() {
        // Since this is a copy of write array list, we don't want to do a compound operation
        // (toArray with an appropriate size) without locking, so we just let the container provide
        // an appropriately sized array noinspection ToArrayCallWithZeroLengthArrayArgument
        return mDevices.toArray(new Device[mDevices.size()]);
    }

    @NonNull
    AndroidDebugBridge getServer() {
        return mServer;
    }

    /**
     * Attempts to connect to the debug bridge server.
     * @return a connect socket if success, null otherwise
     */
    @Nullable
    private static SocketChannel openAdbConnection() {
        try {
            SocketChannel adbChannel = SocketChannel.open(AndroidDebugBridge.getSocketAddress());
            adbChannel.socket().setTcpNoDelay(true);
            return adbChannel;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Updates the device list with the new items received from the monitoring service.
     */
    private void updateDevices(@NonNull List<Device> newList) {
        DeviceListComparisonResult result = DeviceListComparisonResult.compare(mDevices, newList);
        for (Device device : result.removed) {
            removeDevice(device);
            AndroidDebugBridge.deviceDisconnected(device);
        }

        List<Device> newlyOnline = new ArrayList<>(mDevices.size());

        for (Map.Entry<Device, DeviceState> entry : result.updated.entrySet()) {
            Device device = entry.getKey();
            device.setState(entry.getValue());

            if (device.isOnline()) {
                newlyOnline.add(device);
            }
        }

        for (Device device : result.added) {
            mDevices.add(device);
            AndroidDebugBridge.deviceConnected(device);
            if (device.isOnline()) {
                newlyOnline.add(device);
            }
        }

        for (Device device : newlyOnline) {
            // Initiate a property fetch so that future requests can be served out of this cache.
            // This is necessary for backwards compatibility
            device.getSystemProperty(Device.PROP_BUILD_API_LEVEL);
        }
    }

    private void removeDevice(@NonNull Device device) {
        device.setState(DeviceState.DISCONNECTED);
        mDevices.remove(device);
    }

    /**
     * Reads the length of the next message from a socket.
     * @param socket The {@link SocketChannel} to read from.
     * @return the length, or 0 (zero) if no data is available from the socket.
     * @throws IOException if the connection failed.
     */
    private static int readLength(@NonNull SocketChannel socket, @NonNull byte[] buffer)
            throws IOException {
        String msg = read(socket, buffer);

        if (msg != null) {
            try {
                return Integer.parseInt(msg, 16);
            } catch (NumberFormatException nfe) {
                // we'll throw an exception below.
            }
        }

        // we receive something we can't read. It's better to reset the connection at this point.
        throw new IOException("Unable to read length");
    }

    /**
     * Fills a buffer by reading data from a socket.
     * @return the content of the buffer as a string, or null if it failed to convert the buffer.
     * @throws IOException if there was not enough data to fill the buffer
     */
    @Nullable
    private static String read(@NonNull SocketChannel socket, @NonNull byte[] buffer)
            throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(buffer, 0, buffer.length);

        while (buf.position() != buf.limit()) {
            int count;

            count = socket.read(buf);
            if (count < 0) {
                throw new IOException("EOF");
            }
        }

        try {
            return new String(buffer, 0, buf.position(), AdbHelper.DEFAULT_ENCODING);
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    private class DeviceListUpdateListener implements DeviceListMonitorTask.UpdateListener {
        @Override
        public void connectionError(@NonNull Exception e) {
            for (Device device : mDevices) {
                removeDevice(device);
                AndroidDebugBridge.deviceDisconnected(device);
            }
        }

        @Override
        public void deviceListUpdate(@NonNull Map<String, DeviceState> devices) {
            List<Device> l = new ArrayList<>(devices.size());
            for (Map.Entry<String, DeviceState> entry : devices.entrySet()) {
                l.add(new Device(entry.getKey(), entry.getValue()));
            }
            // now merge the new devices with the old ones.
            updateDevices(l);
        }
    }

    static class DeviceListComparisonResult {
        @NonNull
        public final Map<Device,DeviceState> updated;
        @NonNull
        public final List<Device> added;
        @NonNull
        public final List<Device> removed;

        private DeviceListComparisonResult(@NonNull Map<Device,DeviceState> updated,
                @NonNull List<Device> added,
                @NonNull List<Device> removed) {
            this.updated = updated;
            this.added = added;
            this.removed = removed;
        }

        @NonNull
        public static DeviceListComparisonResult compare(@NonNull List<? extends Device> previous,
                @NonNull List<? extends Device> current) {
            current = new ArrayList<>(current);

            final Map<Device,DeviceState> updated = new HashMap<>(current.size());
            final List<Device> added = new ArrayList<>(1);
            final List<Device> removed = new ArrayList<>(1);

            for (Device device : previous) {
                Device currentDevice = find(current, device);
                if (currentDevice != null) {
                    if (currentDevice.getState() != device.getState()) {
                        updated.put(device, currentDevice.getState());
                    }
                    current.remove(currentDevice);
                } else {
                    removed.add(device);
                }
            }

            added.addAll(current);

            return new DeviceListComparisonResult(updated, added, removed);
        }

        @Nullable
        private static Device find(@NonNull List<? extends Device> devices,
                @NonNull Device device) {
            for (Device d : devices) {
                if (d.getSerialNumber().equals(device.getSerialNumber())) {
                    return d;
                }
            }

            return null;
        }
    }

    static class DeviceListMonitorTask implements Runnable {
        private final byte[] mLengthBuffer = new byte[4];

        private final AndroidDebugBridge mBridge;
        private final UpdateListener mListener;

        private SocketChannel mAdbConnection = null;
        private boolean mMonitoring = false;
        private int mConnectionAttempt = 0;
        private int mRestartAttemptCount = 0;
        private boolean mInitialDeviceListDone = false;

        private volatile boolean mQuit;

        private interface UpdateListener {
            void connectionError(@NonNull Exception e);
            void deviceListUpdate(@NonNull Map<String,DeviceState> devices);
        }

        public DeviceListMonitorTask(@NonNull AndroidDebugBridge bridge,
                @NonNull UpdateListener listener) {
            mBridge = bridge;
            mListener = listener;
        }

        @Override
        public void run() {
            do {
                if (mAdbConnection == null) {
                    Log.d("DeviceMonitor", "Opening adb connection");
                    mAdbConnection = openAdbConnection();
                    if (mAdbConnection == null) {
                        mConnectionAttempt++;
                        Log.e("DeviceMonitor", "Connection attempts: " + mConnectionAttempt);
                        if (mConnectionAttempt > 10) {
                            if (!mBridge.startAdb()) {
                                mRestartAttemptCount++;
                                Log.e("DeviceMonitor",
                                        "adb restart attempts: " + mRestartAttemptCount);
                            } else {
                                Log.i("DeviceMonitor", "adb restarted");
                                mRestartAttemptCount = 0;
                            }
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                        }
                    } else {
                        Log.d("DeviceMonitor", "Connected to adb for device monitoring");
                        mConnectionAttempt = 0;
                    }
                }

                try {
                    if (mAdbConnection != null && !mMonitoring) {
                        mMonitoring = sendDeviceListMonitoringRequest();
                    }

                    if (mMonitoring) {
                        int length = readLength(mAdbConnection, mLengthBuffer);

                        if (length >= 0) {
                            // read the incoming message
                            processIncomingDeviceData(length);

                            // flag the fact that we have build the list at least once.
                            mInitialDeviceListDone = true;
                        }
                    }
                } catch (AsynchronousCloseException ace) {
                    // this happens because of a call to Quit. We do nothing, and the loop will break.
                } catch (TimeoutException | IOException ioe) {
                    handleExceptionInMonitorLoop(ioe);
                }
            } while (!mQuit);
        }

        private boolean sendDeviceListMonitoringRequest() throws TimeoutException, IOException {
            byte[] request = AdbHelper.formAdbRequest(ADB_TRACK_DEVICES_COMMAND);

            try {
                AdbHelper.write(mAdbConnection, request);
                AdbResponse resp = AdbHelper.readAdbResponse(mAdbConnection, false);
                if (!resp.okay) {
                    // request was refused by adb!
                    Log.e("DeviceMonitor", "adb refused request: " + resp.message);
                }

                return resp.okay;
            } catch (IOException e) {
                Log.e("DeviceMonitor", "Sending Tracking request failed!");
                mAdbConnection.close();
                throw e;
            }
        }

        private void handleExceptionInMonitorLoop(@NonNull Exception e) {
            if (!mQuit) {
                if (e instanceof TimeoutException) {
                    Log.e("DeviceMonitor", "Adb connection Error: timeout");
                } else {
                    Log.e("DeviceMonitor", "Adb connection Error:" + e.getMessage());
                }
                mMonitoring = false;
                if (mAdbConnection != null) {
                    try {
                        mAdbConnection.close();
                    } catch (IOException ioe) {
                        // we can safely ignore that one.
                    }
                    mAdbConnection = null;

                    mListener.connectionError(e);
                }
            }
        }

        /** Processes an incoming device message from the socket */
        private void processIncomingDeviceData(int length) throws IOException {
            Map<String, DeviceState> result;
            if (length <= 0) {
                result = Collections.emptyMap();
            } else {
                String response = read(mAdbConnection, new byte[length]);
                result = parseDeviceListResponse(response);
            }

            mListener.deviceListUpdate(result);
        }

        static Map<String, DeviceState> parseDeviceListResponse(@Nullable String result) {
            Map<String, DeviceState> deviceStateMap = new HashMap<>();
            String[] devices = result == null ? new String[0] : result.split("\n"); //$NON-NLS-1$

            for (String d : devices) {
                String[] param = d.split("\t"); //$NON-NLS-1$
                if (param.length == 2) {
                    // new adb uses only serial numbers to identify devices
                    deviceStateMap.put(param[0], DeviceState.getState(param[1]));
                }
            }
            return deviceStateMap;
        }

        boolean isMonitoring() {
            return mMonitoring;
        }

        boolean hasInitialDeviceList() {
            return mInitialDeviceListDone;
        }

        int getConnectionAttemptCount() {
            return mConnectionAttempt;
        }

        int getRestartAttemptCount() {
            return mRestartAttemptCount;
        }

        public void stop() {
            mQuit = true;

            // wakeup the main loop thread by closing the main connection to adb.
            if (mAdbConnection != null) {
                try {
                    mAdbConnection.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
