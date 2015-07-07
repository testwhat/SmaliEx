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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

import com.android.ddmlib.AdbHelper.AdbCommandRejectedException;
import com.android.ddmlib.AdbHelper.AdbResponse;
import com.android.ddmlib.AdbHelper.TimeoutException;
import com.android.ddmlib.SyncService.SyncException.SyncError;

/**
 * Sync service class to push/pull to/from devices/emulators, through the debug bridge.
 * <p/>
 * To get a {@link SyncService} object, use {@link Device#getSyncService()}.
 */
public class SyncService {

    private static final byte[] ID_OKAY = { 'O', 'K', 'A', 'Y' };
    private static final byte[] ID_FAIL = { 'F', 'A', 'I', 'L' };
    private static final byte[] ID_STAT = { 'S', 'T', 'A', 'T' };
    private static final byte[] ID_RECV = { 'R', 'E', 'C', 'V' };
    private static final byte[] ID_DATA = { 'D', 'A', 'T', 'A' };
    private static final byte[] ID_DONE = { 'D', 'O', 'N', 'E' };
    private static final byte[] ID_SEND = { 'S', 'E', 'N', 'D' };

    private static final NullSyncProgressMonitor sNullSyncProgressMonitor =
            new NullSyncProgressMonitor();

    private static final int SYNC_DATA_MAX = 64 * 1024;
    private static final int REMOTE_PATH_MAX_LENGTH = 1024;

    /**
     * Classes which implement this interface provide methods that deal
     * with displaying transfer progress.
     */
    public interface ISyncProgressMonitor {
        /**
         * Sent when the transfer starts
         * @param totalWork the total amount of work.
         */
        public void start(int totalWork);
        /**
         * Sent when the transfer is finished or interrupted.
         */
        public void stop();
        /**
         * Sent to query for possible cancellation.
         * @return true if the transfer should be stopped.
         */
        public boolean isCanceled();
        /**
         * Sent when a sub task is started.
         * @param name the name of the sub task.
         */
        public void startSubTask(String name);
        /**
         * Sent when some progress have been made.
         * @param work the amount of work done.
         */
        public void advance(int work);
    }

    /**
     * A Sync progress monitor that does nothing
     */
    private static class NullSyncProgressMonitor implements ISyncProgressMonitor {
        @Override
        public void advance(int work) {
        }
        @Override
        public boolean isCanceled() {
            return false;
        }

        @Override
        public void start(int totalWork) {
        }
        @Override
        public void startSubTask(String name) {
        }
        @Override
        public void stop() {
        }
    }

    private final InetSocketAddress mAddress;
    private final Device mDevice;
    private SocketChannel mChannel;

    /**
     * Buffer used to send data. Allocated when needed and reused afterward.
     */
    private byte[] mBuffer;

    /**
     * Creates a Sync service object.
     * @param address The address to connect to
     * @param device the {@link Device} that the service connects to.
     */
    SyncService(InetSocketAddress address, Device device) {
        mAddress = address;
        mDevice = device;
    }

    /**
     * Opens the sync connection. This must be called before any calls to push[File] / pull[File].
     * @return true if the connection opened, false if adb refuse the connection. This can happen
     * if the {@link Device} is invalid.
     * @throws TimeoutException in case of timeout on the connection.
     * @throws AdbCommandRejectedException if adb rejects the command
     * @throws IOException If the connection to adb failed.
     */
    boolean openSync() throws TimeoutException, AdbCommandRejectedException, IOException {
        try {
            mChannel = SocketChannel.open(mAddress);
            mChannel.configureBlocking(false);

            // target a specific device
            AdbHelper.setDevice(mChannel, mDevice);

            byte[] request = AdbHelper.formAdbRequest("sync:"); //$NON-NLS-1$
            AdbHelper.write(mChannel, request, -1, DdmPreferences.getTimeOut());

            AdbResponse resp = AdbHelper.readAdbResponse(mChannel, false /* readDiagString */);

            if (!resp.okay) {
                Log.w("ddms", "Got unhappy response from ADB sync req: " + resp.message);
                mChannel.close();
                mChannel = null;
                return false;
            }
        } catch (TimeoutException | IOException e) {
            if (mChannel != null) {
                try {
                    mChannel.close();
                } catch (IOException e2) {
                    // we want to throw the original exception, so we ignore this one.
                }
                mChannel = null;
            }

            throw e;
        }

        return true;
    }

    /**
     * Closes the connection.
     */
    public void close() {
        if (mChannel != null) {
            try {
                mChannel.close();
            } catch (IOException e) {
                // nothing to be done really...
            }
            mChannel = null;
        }
    }

    /**
     * Returns a sync progress monitor that does nothing. This allows background tasks that don't
     * want/need to display ui, to pass a valid {@link ISyncProgressMonitor}.
     * <p/>This object can be reused multiple times and can be used by concurrent threads.
     */
    public static ISyncProgressMonitor getNullProgressMonitor() {
        return sNullSyncProgressMonitor;
    }

    /**
     * Pulls a single file.
     * <p/>Because this method just deals with a String for the remote file instead of a
     * {@link FileEntry}, the size of the file being pulled is unknown and the
     * {@link ISyncProgressMonitor} will not properly show the progress
     * @param remoteFilepath the full path to the remote file
     * @param localFilename The local destination.
     * @param monitor The progress monitor. Cannot be null.
     *
     * @throws IOException in case of an IO exception.
     * @throws TimeoutException in case of a timeout reading responses from the device.
     * @throws SyncException in case of a sync exception.
     *
     * @see #getNullProgressMonitor()
     */
    public void pullFile(String remoteFilepath, String localFilename,
            ISyncProgressMonitor monitor) throws TimeoutException, IOException, SyncException {
        Integer mode = readMode(remoteFilepath);
        if (mode == null) {
            // attempts to download anyway
        } else if (mode == 0) {
            throw new SyncException(SyncError.NO_REMOTE_OBJECT);
        }

        monitor.start(0);
        doPullFile(remoteFilepath, localFilename, monitor);
        monitor.stop();
    }

    /**
     * Push a single file.
     * @param local the local filepath.
     * @param remote The remote filepath.
     * @param monitor The progress monitor. Cannot be null.
     *
     * @throws SyncException if file could not be pushed
     * @throws IOException in case of I/O error on the connection.
     * @throws TimeoutException in case of a timeout reading responses from the device.
     */
    public void pushFile(String local, String remote, ISyncProgressMonitor monitor)
            throws SyncException, IOException, TimeoutException {
        File f = new File(local);
        if (!f.exists()) {
            throw new SyncException(SyncError.NO_LOCAL_FILE);
        }

        if (f.isDirectory()) {
            throw new SyncException(SyncError.LOCAL_IS_DIRECTORY);
        }

        monitor.start((int)f.length());
        doPushFile(local, remote, monitor);
        monitor.stop();
    }

    /**
     * Pulls a remote file
     * @param remotePath the remote file (length max is 1024)
     * @param localPath the local destination
     * @param monitor the monitor. The monitor must be started already.
     * @throws SyncException if file could not be pushed
     * @throws IOException in case of I/O error on the connection.
     * @throws TimeoutException in case of a timeout reading responses from the device.
     */
    private void doPullFile(String remotePath, String localPath,
            ISyncProgressMonitor monitor) throws IOException, SyncException, TimeoutException {
        byte[] msg;
        byte[] pullResult = new byte[8];

        final int timeOut = DdmPreferences.getTimeOut();

        try {
            byte[] remotePathContent = remotePath.getBytes(AdbHelper.DEFAULT_ENCODING);

            if (remotePathContent.length > REMOTE_PATH_MAX_LENGTH) {
                throw new SyncException(SyncError.REMOTE_PATH_LENGTH);
            }

            // create the full request message
            msg = createFileReq(ID_RECV, remotePathContent);

            // and send it.
            AdbHelper.write(mChannel, msg, -1, timeOut);

            // read the result, in a byte array containing 2 ints
            // (id, size)
            AdbHelper.read(mChannel, pullResult, -1, timeOut);

            // check we have the proper data back
            if (!checkResult(pullResult, ID_DATA) &&
                    !checkResult(pullResult, ID_DONE)) {
                throw new SyncException(SyncError.TRANSFER_PROTOCOL_ERROR,
                        readErrorMessage(pullResult, timeOut));
            }
        } catch (UnsupportedEncodingException e) {
            throw new SyncException(SyncError.REMOTE_PATH_ENCODING, e);
        }

        // access the destination file
        File f = new File(localPath);

        // create the stream to write in the file. We use a new try/catch block to differentiate
        // between file and network io exceptions.
        try (FileOutputStream fos = new FileOutputStream(f)) {

            // the buffer to read the data
            byte[] data = new byte[SYNC_DATA_MAX];

            // loop to get data until we're done.
            while (true) {
                // check if we're cancelled
                if (monitor.isCanceled()) {
                    throw new SyncException(SyncError.CANCELED);
                }

                // if we're done, we stop the loop
                if (checkResult(pullResult, ID_DONE)) {
                    break;
                }
                if (!checkResult(pullResult, ID_DATA)) {
                    // hmm there's an error
                    throw new SyncException(SyncError.TRANSFER_PROTOCOL_ERROR,
                            readErrorMessage(pullResult, timeOut));
                }
                int length = ArrayHelper.swap32bitFromArray(pullResult, 4);
                if (length > SYNC_DATA_MAX) {
                    // buffer overrun!
                    // error and exit
                    throw new SyncException(SyncError.BUFFER_OVERRUN);
                }

                // now read the length we received
                AdbHelper.read(mChannel, data, length, timeOut);

                // get the header for the next packet.
                AdbHelper.read(mChannel, pullResult, -1, timeOut);

                // write the content in the file
                fos.write(data, 0, length);

                monitor.advance(length);
            }

            fos.flush();
        } catch (IOException e) {
            Log.e("ddms", String.format("Failed to open local file %s for writing, Reason: %s",
                    f.getAbsolutePath(), e.toString()));
            throw new SyncException(SyncError.FILE_WRITE_ERROR);
        }
    }

    /**
     * Push a single file
     * @param localPath the local file to push
     * @param remotePath the remote file (length max is 1024)
     * @param monitor the monitor. The monitor must be started already.
     *
     * @throws SyncException if file could not be pushed
     * @throws IOException in case of I/O error on the connection.
     * @throws TimeoutException in case of a timeout reading responses from the device.
     */
    private void doPushFile(String localPath, String remotePath,
            ISyncProgressMonitor monitor) throws SyncException, IOException, TimeoutException {
        FileInputStream fis = null;
        byte[] msg;

        final int timeOut = DdmPreferences.getTimeOut();

        try {
            byte[] remotePathContent = remotePath.getBytes(AdbHelper.DEFAULT_ENCODING);

            if (remotePathContent.length > REMOTE_PATH_MAX_LENGTH) {
                throw new SyncException(SyncError.REMOTE_PATH_LENGTH);
            }

            File f = new File(localPath);

            // create the stream to read the file
            fis = new FileInputStream(f);

            // create the header for the action
            msg = createSendFileReq(ID_SEND, remotePathContent, 0644);

            // and send it. We use a custom try/catch block to make the difference between
            // file and network IO exceptions.
            AdbHelper.write(mChannel, msg, -1, timeOut);

            System.arraycopy(ID_DATA, 0, getBuffer(), 0, ID_DATA.length);

            // look while there is something to read
            while (true) {
                // check if we're canceled
                if (monitor.isCanceled()) {
                    throw new SyncException(SyncError.CANCELED);
                }

                // read up to SYNC_DATA_MAX
                int readCount = fis.read(getBuffer(), 8, SYNC_DATA_MAX);

                if (readCount == -1) {
                    // we reached the end of the file
                    break;
                }

                // now send the data to the device
                // first write the amount read
                ArrayHelper.swap32bitsToArray(readCount, getBuffer(), 4);

                // now write it
                AdbHelper.write(mChannel, getBuffer(), readCount+8, timeOut);

                // and advance the monitor
                monitor.advance(readCount);
            }
        } catch (UnsupportedEncodingException e) {
            throw new SyncException(SyncError.REMOTE_PATH_ENCODING, e);
        } finally {
            // close the local file
            if (fis != null) {
                fis.close();
            }
        }

        // create the DONE message
        long time = System.currentTimeMillis() / 1000;
        msg = createReq(ID_DONE, (int)time);

        // and send it.
        AdbHelper.write(mChannel, msg, -1, timeOut);

        // read the result, in a byte array containing 2 ints
        // (id, size)
        byte[] result = new byte[8];
        AdbHelper.read(mChannel, result, -1 /* full length */, timeOut);

        if (!checkResult(result, ID_OKAY)) {
            throw new SyncException(SyncError.TRANSFER_PROTOCOL_ERROR,
                    readErrorMessage(result, timeOut));
        }
    }

    /**
     * Reads an error message from the opened {@link #mChannel}.
     * @param result the current adb result. Must contain both FAIL and the length of the message.
     * @param timeOut
     * @return
     * @throws TimeoutException in case of a timeout reading responses from the device.
     * @throws IOException
     */
    private String readErrorMessage(byte[] result, final int timeOut) throws TimeoutException,
            IOException {
        if (checkResult(result, ID_FAIL)) {
            int len = ArrayHelper.swap32bitFromArray(result, 4);

            if (len > 0) {
                AdbHelper.read(mChannel, getBuffer(), len, timeOut);

                String message = new String(getBuffer(), 0, len);
                Log.e("ddms", "transfer error: " + message);

                return message;
            }
        }

        return null;
    }

    /**
     * Returns the mode of the remote file.
     * @param path the remote file
     * @return an Integer containing the mode if all went well or null
     *      otherwise
     * @throws IOException
     * @throws TimeoutException in case of a timeout reading responses from the device.
     */
    private Integer readMode(String path) throws TimeoutException, IOException {
        // create the stat request message.
        byte[] msg = createFileReq(ID_STAT, path);

        AdbHelper.write(mChannel, msg, -1 /* full length */, DdmPreferences.getTimeOut());

        // read the result, in a byte array containing 4 ints
        // (id, mode, size, time)
        byte[] statResult = new byte[16];
        AdbHelper.read(mChannel, statResult, -1 /* full length */, DdmPreferences.getTimeOut());

        // check we have the proper data back
        if (!checkResult(statResult, ID_STAT)) {
            return null;
        }

        // we return the mode (2nd int in the array)
        return ArrayHelper.swap32bitFromArray(statResult, 4);
    }

    /**
     * Create a command with a code and an int values
     * @param command
     * @param value
     * @return
     */
    private static byte[] createReq(byte[] command, int value) {
        byte[] array = new byte[8];

        System.arraycopy(command, 0, array, 0, 4);
        ArrayHelper.swap32bitsToArray(value, array, 4);

        return array;
    }

    /**
     * Creates the data array for a stat request.
     * @param command the 4 byte command (ID_STAT, ID_RECV, ...)
     * @param path The path of the remote file on which to execute the command
     * @return the byte[] to send to the device through adb
     */
    private static byte[] createFileReq(byte[] command, String path) {
        byte[] pathContent;
        try {
            pathContent = path.getBytes(AdbHelper.DEFAULT_ENCODING);
        } catch (UnsupportedEncodingException e) {
            return null;
        }

        return createFileReq(command, pathContent);
    }

    /**
     * Creates the data array for a file request. This creates an array with a 4 byte command + the
     * remote file name.
     * @param command the 4 byte command (ID_STAT, ID_RECV, ...).
     * @param path The path, as a byte array, of the remote file on which to
     *      execute the command.
     * @return the byte[] to send to the device through adb
     */
    private static byte[] createFileReq(byte[] command, byte[] path) {
        byte[] array = new byte[8 + path.length];

        System.arraycopy(command, 0, array, 0, 4);
        ArrayHelper.swap32bitsToArray(path.length, array, 4);
        System.arraycopy(path, 0, array, 8, path.length);

        return array;
    }

    private static byte[] createSendFileReq(byte[] command, byte[] path, int mode) {
        // make the mode into a string
        String modeStr = "," + (mode & 0777); // $NON-NLS-1S
        byte[] modeContent;
        try {
            modeContent = modeStr.getBytes(AdbHelper.DEFAULT_ENCODING);
        } catch (UnsupportedEncodingException e) {
            return null;
        }

        byte[] array = new byte[8 + path.length + modeContent.length];

        System.arraycopy(command, 0, array, 0, 4);
        ArrayHelper.swap32bitsToArray(path.length + modeContent.length, array, 4);
        System.arraycopy(path, 0, array, 8, path.length);
        System.arraycopy(modeContent, 0, array, 8 + path.length, modeContent.length);

        return array;


    }

    /**
     * Checks the result array starts with the provided code
     * @param result The result array to check
     * @param code The 4 byte code.
     * @return true if the code matches.
     */
    private static boolean checkResult(byte[] result, byte[] code) {
        return !(result[0] != code[0] ||
                result[1] != code[1] ||
                result[2] != code[2] ||
                result[3] != code[3]);

    }

    /**
     * Retrieve the buffer, allocating if necessary
     * @return
     */
    private byte[] getBuffer() {
        if (mBuffer == null) {
            // create the buffer used to read.
            // we read max SYNC_DATA_MAX, but we need 2 4 bytes at the beginning.
            mBuffer = new byte[SYNC_DATA_MAX + 8];
        }
        return mBuffer;
    }


    /**
     * Exception thrown when a transfer using {@link SyncService} doesn't complete.
     * <p/>This is different from an {@link IOException} because it's not the underlying connection
     * that triggered the error, but the adb transfer protocol that didn't work somehow, or that the
     * targets (local and/or remote) were wrong.
     */
    public static class SyncException extends CanceledException {
        private static final long serialVersionUID = 1L;

        public enum SyncError {
            /** canceled transfer */
            CANCELED("Operation was canceled by the user."),
            /** Transfer error */
            TRANSFER_PROTOCOL_ERROR("Adb Transfer Protocol Error."),
            /** unknown remote object during a pull */
            NO_REMOTE_OBJECT("Remote object doesn't exist!"),
            /** Result code when attempting to pull multiple files into a file */
            TARGET_IS_FILE("Target object is a file."),
            /** Result code when attempting to pull multiple into a directory that does not exist. */
            NO_DIR_TARGET("Target directory doesn't exist."),
            /** wrong encoding on the remote path. */
            REMOTE_PATH_ENCODING("Remote Path encoding is not supported."),
            /** remote path that is too long. */
            REMOTE_PATH_LENGTH("Remote path is too long."),
            /** error while reading local file. */
            FILE_READ_ERROR("Reading local file failed!"),
            /** error while writing local file. */
            FILE_WRITE_ERROR("Writing local file failed!"),
            /** attempting to push a directory. */
            LOCAL_IS_DIRECTORY("Local path is a directory."),
            /** attempting to push a non-existent file. */
            NO_LOCAL_FILE("Local path doesn't exist."),
            /** when the target path of a multi file push is a file. */
            REMOTE_IS_FILE("Remote path is a file."),
            /** receiving too much data from the remove device at once */
            BUFFER_OVERRUN("Receiving too much data.");

            private final String mMessage;

            private SyncError(String message) {
                mMessage = message;
            }

            public String getMessage() {
                return mMessage;
            }
        }

        private final SyncError mError;

        public SyncException(SyncError error) {
            super(error.getMessage());
            mError = error;
        }

        public SyncException(SyncError error, String message) {
            super(message);
            mError = error;
        }

        public SyncException(SyncError error, Throwable cause) {
            super(error.getMessage(), cause);
            mError = error;
        }

        public SyncError getErrorCode() {
            return mError;
        }

        /**
         * Returns true if the sync was canceled by user input.
         */
        @Override
        public boolean wasCanceled() {
            return mError == SyncError.CANCELED;
        }
    }
}

/**
 * Abstract exception for exception that can be thrown when a user input cancels the action.
 * <p/>
 * {@link #wasCanceled()} returns whether the action was canceled because of user input.
 *
 */
abstract class CanceledException extends Exception {
    private static final long serialVersionUID = 1L;

    CanceledException(String message) {
        super(message);
    }

    CanceledException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Returns true if the action was canceled by user input.
     * @return canceled
     */
    public abstract boolean wasCanceled();
}
