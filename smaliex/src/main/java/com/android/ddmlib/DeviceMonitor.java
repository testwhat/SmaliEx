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

import com.android.ddmlib.AdbHelper.AdbResponse;
import com.android.ddmlib.AdbHelper.TimeoutException;
import com.android.ddmlib.Device.DeviceState;

/**
 * A Device monitor. This connects to the Android Debug Bridge and get device and
 * debuggable process information from it.
 */
final class DeviceMonitor {
    private final byte[] mLengthBuffer = new byte[4];

    private boolean mQuit = false;

    private final AndroidDebugBridge mServer;

    private SocketChannel mMainAdbConnection = null;
    private boolean mMonitoring = false;
    private int mConnectionAttempt = 0;
    private int mRestartAttemptCount = 0;
    private boolean mInitialDeviceListDone = false;

    private final ArrayList<Device> mDevices = new ArrayList<>();

    /**
     * Creates a new {@link DeviceMonitor} object and links it to the running
     * {@link AndroidDebugBridge} object.
     * @param server the running {@link AndroidDebugBridge}.
     */
    DeviceMonitor(AndroidDebugBridge server) {
        mServer = server;
    }

    /**
     * Starts the monitoring.
     */
    void start() {
        new Thread("Device List Monitor") { //$NON-NLS-1$
            @Override
            public void run() {
                deviceMonitorLoop();
            }
        }.start();
    }

    /**
     * Stops the monitoring.
     */
    void stop() {
        mQuit = true;

        // wakeup the main loop thread by closing the main connection to adb.
        try {
            if (mMainAdbConnection != null) {
                mMainAdbConnection.close();
            }
        } catch (IOException e1) {
        }
    }

    /**
     * Returns if the monitor is currently connected to the debug bridge server.
     * @return
     */
    boolean isMonitoring() {
        return mMonitoring;
    }

    int getConnectionAttemptCount() {
        return mConnectionAttempt;
    }

    int getRestartAttemptCount() {
        return mRestartAttemptCount;
    }

    /**
     * Returns the devices.
     */
    Device[] getDevices() {
        synchronized (mDevices) {
            return mDevices.toArray(new Device[mDevices.size()]);
        }
    }

    boolean hasInitialDeviceList() {
        return mInitialDeviceListDone;
    }

    AndroidDebugBridge getServer() {
        return mServer;
    }

    /**
     * Monitors the devices. This connects to the Debug Bridge
     */
    private void deviceMonitorLoop() {
        do {
            try {
                if (mMainAdbConnection == null) {
                    Log.d("DeviceMonitor", "Opening adb connection");
                    mMainAdbConnection = openAdbConnection();
                    if (mMainAdbConnection == null) {
                        mConnectionAttempt++;
                        Log.e("DeviceMonitor", "Connection attempts: " + mConnectionAttempt);
                        if (mConnectionAttempt > 10) {
                            if (!mServer.startAdb()) {
                                mRestartAttemptCount++;
                                Log.e("DeviceMonitor",
                                        "adb restart attempts: " + mRestartAttemptCount);
                            } else {
                                Log.i("DeviceMonitor", "adb restarted");
                                mRestartAttemptCount = 0;
                            }
                        }
                        waitABit();
                    } else {
                        Log.d("DeviceMonitor", "Connected to adb for device monitoring");
                        mConnectionAttempt = 0;
                    }
                }

                if (mMainAdbConnection != null && !mMonitoring) {
                    mMonitoring = sendDeviceListMonitoringRequest();
                }

                if (mMonitoring) {
                    // read the length of the incoming message
                    int length = readLength(mMainAdbConnection, mLengthBuffer);

                    if (length >= 0) {
                        // read the incoming message
                        processIncomingDeviceData(length);

                        // flag the fact that we have build the list at least once.
                        mInitialDeviceListDone = length > 0;
                    }
                }
            } catch (AsynchronousCloseException ace) {
                // this happens because of a call to Quit. We do nothing, and the loop will break.
            } catch (TimeoutException | IOException ioe) {
                handleExpectionInMonitorLoop(ioe);
            }
        } while (!mQuit);
    }

    private void handleExpectionInMonitorLoop(Exception e) {
        if (!mQuit) {
            if (e instanceof TimeoutException) {
                Log.e("DeviceMonitor", "Adb connection Error: timeout");
            } else {
                Log.e("DeviceMonitor", "Adb connection Error:" + e.getMessage());
            }
            mMonitoring = false;
            if (mMainAdbConnection != null) {
                try {
                    mMainAdbConnection.close();
                } catch (IOException ioe) {
                    // we can safely ignore that one.
                }
                mMainAdbConnection = null;

                // remove all devices from list
                // because we are going to call mServer.deviceDisconnected which will acquire this
                // lock we lock it first, so that the AndroidDebugBridge lock is always locked
                // first.
                synchronized (AndroidDebugBridge.getLock()) {
                    synchronized (mDevices) {
                        for (int n = mDevices.size() - 1; n >= 0; n--) {
                            Device device = mDevices.get(0);
                            removeDevice(device);
                            AndroidDebugBridge.deviceDisconnected(device);
                        }
                    }
                }
            }
        }
    }

    /**
     * Sleeps for a little bit.
     */
    private void waitABit() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e1) {
        }
    }

    /**
     * Attempts to connect to the debug bridge server.
     * @return a connect socket if success, null otherwise
     */
    private SocketChannel openAdbConnection() {
        Log.d("DeviceMonitor", "Connecting to adb for Device List Monitoring...");

        SocketChannel adbChannel = null;
        try {
            adbChannel = SocketChannel.open(AndroidDebugBridge.getSocketAddress());
            adbChannel.socket().setTcpNoDelay(true);
        } catch (IOException e) {
        }

        return adbChannel;
    }

    /**
     *
     * @return
     * @throws IOException
     */
    private boolean sendDeviceListMonitoringRequest() throws TimeoutException, IOException {
        byte[] request = AdbHelper.formAdbRequest("host:track-devices"); //$NON-NLS-1$

        try {
            AdbHelper.write(mMainAdbConnection, request);

            AdbResponse resp = AdbHelper.readAdbResponse(mMainAdbConnection,
                    false /* readDiagString */);

            if (!resp.okay) {
                // request was refused by adb!
                Log.e("DeviceMonitor", "adb refused request: " + resp.message);
            }

            return resp.okay;
        } catch (IOException e) {
            Log.e("DeviceMonitor", "Sending Tracking request failed!");
            mMainAdbConnection.close();
            throw e;
        }
    }

    /** Processes an incoming device message from the socket */
    private void processIncomingDeviceData(int length) throws IOException {
        ArrayList<Device> list = new ArrayList<>();

        if (length > 0) {
            byte[] buffer = new byte[length];
            String result = read(mMainAdbConnection, buffer);

            String[] devices = result.split("\n"); //$NON-NLS-1$

            for (String d : devices) {
                String[] param = d.split("\t"); //$NON-NLS-1$
                if (param.length == 2) {
                    // new adb uses only serial numbers to identify devices
                    Device device = new Device(param[0] /*serialnumber*/,
                            DeviceState.getState(param[1]));

                    //add the device to the list
                    list.add(device);
                }
            }
        }

        // now merge the new devices with the old ones.
        updateDevices(list);
    }

    /**
     *  Updates the device list with the new items received from the monitoring service.
     */
    private void updateDevices(ArrayList<Device> newList) {
        // because we are going to call mServer.deviceDisconnected which will acquire this lock
        // we lock it first, so that the AndroidDebugBridge lock is always locked first.
        synchronized (AndroidDebugBridge.getLock()) {
            synchronized (mDevices) {
                // For each device in the current list, we look for a matching the new list.
                // * if we find it, we update the current object with whatever new information
                //   there is
                //   (mostly state change, if the device becomes ready, we query for build info).
                //   We also remove the device from the new list to mark it as "processed"
                // * if we do not find it, we remove it from the current list.
                // Once this is done, the new list contains device we aren't monitoring yet, so we
                // add them to the list, and start monitoring them.

                for (int d = 0 ; d < mDevices.size() ;) {
                    Device device = mDevices.get(d);

                    // look for a similar device in the new list.
                    int count = newList.size();
                    boolean foundMatch = false;
                    for (int dd = 0 ; dd < count ; dd++) {
                        Device newDevice = newList.get(dd);
                        // see if it matches in id and serial number.
                        if (newDevice.getSerialNumber().equals(device.getSerialNumber())) {
                            foundMatch = true;

                            // update the state if needed.
                            if (device.getState() != newDevice.getState()) {
                                device.setState(newDevice.getState());
                            }

                            // remove the new device from the list since it's been used
                            newList.remove(dd);
                            break;
                        }
                    }

                    if (!foundMatch) {
                        // the device is gone, we need to remove it, and keep current index
                        // to process the next one.
                        removeDevice(device);
                        AndroidDebugBridge.deviceDisconnected(device);
                    } else {
                        // process the next one
                        d++;
                    }
                }

                // at this point we should still have some new devices in newList, so we
                // process them.
                for (Device newDevice : newList) {
                    // add them to the list
                    mDevices.add(newDevice);
                    AndroidDebugBridge.deviceConnected(newDevice);
                }
            }
        }
        newList.clear();
    }

    private void removeDevice(Device device) {
        mDevices.remove(device);

        SocketChannel channel = device.getClientMonitoringSocket();
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                // doesn't really matter if the close fails.
            }
        }
    }

    /**
     * Reads the length of the next message from a socket.
     * @param socket The {@link SocketChannel} to read from.
     * @return the length, or 0 (zero) if no data is available from the socket.
     * @throws IOException if the connection failed.
     */
    private int readLength(SocketChannel socket, byte[] buffer) throws IOException {
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
     * Fills a buffer from a socket.
     * @param socket
     * @param buffer
     * @return the content of the buffer as a string, or null if it failed to convert the buffer.
     * @throws IOException
     */
    private String read(SocketChannel socket, byte[] buffer) throws IOException {
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
            // we'll return null below.
        }

        return null;
    }

}
