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

import org.rh.smaliex.reader.Elf;

import com.android.ddmlib.Device;

public class DeodexFrameworkFromDevice {
    static boolean TEST_MODE = false;

    public static void main(String[] args) {
        String outFolder = MiscUtil.workingDir();
        String sysFolder = null;
        if (args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                if ("-o".equals(args[i]) && i + 1 < args.length) {
                    outFolder = args[i + 1];
                    break;
                }
            }
            if (args.length > 2) {
                String inputPath = args[args.length - 1];
                if (new File(inputPath).isDirectory()) {
                    sysFolder = inputPath;
                }
            }
            if ("t".equals(args[0])) {
                TEST_MODE = true;
                LLog.i("Force test mode, using dalvik-cache");
            }
        }
        deOptimizeAuto(sysFolder, outFolder);
    }

    public final static String FOLDER_BOOT_JAR_ORIGINAL = "boot-jar-original";
    public final static String FOLDER_BOOT_JAR_RESULT = "boot-jar-result";
    public final static String FOLDER_FRAMEWORK_ODEX = "framework-odex";
    public final static String FOLDER_FRAMEWORK_JAR = "framework-jar-original";
    public final static String FOLDER_FRAMEWORK_JAR_DEX = "framework-jar-with-dex";

    public final static String BOOT_OAT = "boot.oat";
    public final static String SYS_FRAMEWORK = "/system/framework/";

    public static void deOptimizeAuto(String sysFolder, String outFolder) {
        if (outFolder == null) {
            outFolder = MiscUtil.workingDir();
        } else {
            MiscUtil.mkdirs(new File(outFolder));
        }
        if (sysFolder != null) {
            deOptimizeFromFrameworkFolder(sysFolder, outFolder);
        } else {
            deOptimizeFromDevice(outFolder);
        }
    }

    public static void deOptimizeFromDevice(final String outFolder) {
        AdbUtil.runOneTimeAction(new AdbUtil.OneTimeAction() {

            @Override
            public void run(Device device) throws Exception {
                deOptimizeFramework(createFwProvider(device), outFolder);
            }

            @Override
            public String onDisconnectedMsg() {
                return "The de-opt flow may not complete";
            }
        });
    }

    public static void deOptimizeFromFrameworkFolder(String sysFwFolder, String outFolder) {
        LLog.i("From " + sysFwFolder);
        try {
            deOptimizeFramework(createFwProvider(new File(sysFwFolder)), outFolder);
        } catch (IOException e) {
            LLog.ex(e);
        }
    }

    abstract static class FwProvider {
        static String[] ABIS = {"arm64", "arm", "x64", "x86"};
        String mAbiFolder = "arm/";

        abstract void pullFileToFolder(String remote, String localFolder);
        abstract String[] getFileList(String path);
        abstract boolean isFileExist(String path);
        abstract String[] getBootClassPath();
        abstract String getName();

        public String getBootOatLocation() {
            return SYS_FRAMEWORK + mAbiFolder;
        }

        public String getOatLocation() {
            return SYS_FRAMEWORK + "oat/"  + mAbiFolder;
        }
    }

    static FwProvider createFwProvider(Device device) {
        return new DeviceFwProvider(device);
    }

    static FwProvider createFwProvider(File folder) {
        return new FileFwProvider(folder);
    }

    static class DeviceFwProvider extends FwProvider {
        final Device mDevice;

        DeviceFwProvider(Device device) {
            mDevice = device;
            String deviceAbi = device.getProperty(Device.PROP_DEVICE_CPU_ABI);
            if (deviceAbi != null) {
                for (String abi : ABIS) {
                    if (deviceAbi.contains("abi")) {
                        mAbiFolder = abi + "/";
                        break;
                    }
                }
            }
        }

        @Override
        public void pullFileToFolder(String remote, String localFolder) {
            AdbUtil.pullFileToFolder(mDevice, remote, localFolder);
        }

        @Override
        public String[] getFileList(String path) {
            return AdbUtil.getFileList(mDevice, path);
        }

        @Override
        public boolean isFileExist(String path) {
            return AdbUtil.isFileExist(mDevice, path);
        }

        @Override
        public String[] getBootClassPath() {
            return AdbUtil.getBootClassPath(mDevice);
        }

        @Override
        public String getName() {
            return mDevice.getName();
        }
    }

    static class FileFwProvider extends FwProvider {
        final File mFolder;
        final boolean mContainsSysFw;
        final String[] booJars;

        FileFwProvider(File folder) {
            mFolder = folder;
            mContainsSysFw = new File(folder, SYS_FRAMEWORK).isDirectory();
            for (String abi : ABIS) {
                if (new File(mFolder, autoPath(SYS_FRAMEWORK + abi)).isDirectory()) {
                    mAbiFolder = abi + "/";
                    break;
                }
            }
            File bootLocation = new File(mFolder, autoPath(SYS_FRAMEWORK + "oat/" + mAbiFolder));
            if (!bootLocation.isDirectory()) {
                bootLocation = new File(mFolder, autoPath(SYS_FRAMEWORK + mAbiFolder));
            }
            java.util.ArrayList<String> jars = OatUtil.getBootJarNames(
                    MiscUtil.path(bootLocation.getAbsolutePath(), BOOT_OAT), mContainsSysFw);
            booJars = jars.toArray(new String[jars.size()]);
        }

        String autoPath(String path) {
            return mContainsSysFw ? path : path.replace(SYS_FRAMEWORK, "/");
        }

        @Override
        public void pullFileToFolder(String remote, String localFolder) {
            File src = new File(mFolder, autoPath(remote));
            if (!src.exists()) {
                LLog.i(src + " not found, skip");
                return;
            }
            try {
                java.nio.file.Files.copy(src.toPath(),
                        new File(localFolder, src.getName()).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LLog.ex(e);
            }
        }

        @Override
        public String[] getFileList(String path) {
            return new File(mFolder, autoPath(path)).list();
        }

        @Override
        public boolean isFileExist(String path) {
            return new File(mFolder, autoPath(path)).exists();
        }

        @Override
        public String[] getBootClassPath() {
            if (booJars.length > 0) {
                return booJars;
            }
            String[] bootJars = {
                    "core-libart.jar",
                    "conscrypt.jar",
                    "okhttp.jar",
                    "core-junit.jar",
                    "bouncycastle.jar",
                    "ext.jar",
                    "framework.jar",
                    "telephony-common.jar",
                    "voip-common.jar",
                    "ims-common.jar",
                    "mms-common.jar",
                    "android.policy.jar",
                    "apache-xml.jar"
            };
            for (int i = 0; i < bootJars.length; i++) {
                bootJars[i] = SYS_FRAMEWORK + bootJars[i];
            }
            return bootJars;
        }

        @Override
        public String getName() {
            return mFolder.getName();
        }
    }

    public static void deOptimizeFramework(FwProvider device, String workingDir) throws IOException {
        String outBootJarFolder = MiscUtil.path(workingDir, FOLDER_BOOT_JAR_RESULT);
        if (!generateBootJar(device, workingDir, outBootJarFolder)) {
            return;
        }
        generateNonBootFrameworkJar(device, workingDir, outBootJarFolder);
        String outFwJarFolder = MiscUtil.path(workingDir, FOLDER_FRAMEWORK_JAR_DEX);
        LLog.i("Done\n The rest steps:\n"
                + " 1. Push all files under " + outBootJarFolder + " to /system/framework/\n"
                + " 2. Push all files under " + outFwJarFolder + " to /system/framework/\n"
                + " 3. Delete all files under /system/framework/arm and arm64\n"
                + " 4. Reboot then the device will run with non-pre-optimized framework");
    }

    static void generateNonBootFrameworkJar(FwProvider device,
            String workingDir, String bootDir) throws IOException {
        final String PULL_ODEX_DIR = MiscUtil.path(workingDir, FOLDER_FRAMEWORK_ODEX);
        final String PULL_JAR_DIR = MiscUtil.path(workingDir, FOLDER_FRAMEWORK_JAR);

        MiscUtil.mkdirs(new File(PULL_ODEX_DIR));
        MiscUtil.mkdirs(new File(PULL_JAR_DIR));
        final File RESULT_JAR_DIR = new File(MiscUtil.path(workingDir, FOLDER_FRAMEWORK_JAR_DEX));
        MiscUtil.mkdirs(RESULT_JAR_DIR);

        java.util.HashMap<String, String[]> fileLists = new java.util.HashMap<>();
        String oatLocation = device.getOatLocation();
        if (!device.isFileExist(oatLocation)) {
            oatLocation = SYS_FRAMEWORK + device.mAbiFolder;
        }
        String[] paths = {device.getBootOatLocation(), oatLocation};
        for (String path : paths) {
            fileLists.put(path, device.getFileList(path));
        }

        for (java.util.Map.Entry<String, String[]> entry : fileLists.entrySet()) {
            final String path = entry.getKey();
            final String[] files = entry.getValue();
            if (files == null) {
                LLog.e("Cannot list " + path + " from " + device.getName());
                continue;
            }
            for (String f : files) {
                if (f.startsWith("boot.")) {
                    continue;
                }

                String oat = oatLocation + f;
                LLog.i("Pulling " + oat);
                device.pullFileToFolder(oat, PULL_ODEX_DIR);

                String jar = SYS_FRAMEWORK + MiscUtil.getFilenamePrefix(f) + ".jar";
                LLog.i("Pulling " + jar);
                device.pullFileToFolder(jar, PULL_JAR_DIR);

                try (Elf e = new Elf(MiscUtil.path(PULL_ODEX_DIR, f))) {
                    OatUtil.convertToDexJar(
                            OatUtil.getOat(e), RESULT_JAR_DIR,
                            bootDir, PULL_JAR_DIR, false);
                }
            }
        }
    }

    static boolean generateBootJar(FwProvider device, String workingDir,
            String outBootJarFolder) throws IOException {
        String oatName = BOOT_OAT;
        String bootOatHere = MiscUtil.path(workingDir, oatName);
        if (new File(bootOatHere).exists()) {
            LLog.i("Found " + bootOatHere + ", skip pull " + BOOT_OAT);
        } else {
            String bootOat = device.getBootOatLocation() + oatName;
            if (TEST_MODE) {
                oatName = "system@framework@boot.oat";
                bootOat = "/data/dalvik-cache/" + device.mAbiFolder + "/" + oatName;
            }
            if (!device.isFileExist(bootOat)) {
                LLog.i("The rom does not have pre-compiled " + bootOat);
                return false;
            }
            LLog.i("Preparing de-optimizing for device " + device.getName());

            LLog.i("Pulling " + bootOat);
            device.pullFileToFolder(bootOat, workingDir);
            String po = MiscUtil.path(workingDir, oatName);
            if (!new File(po).exists()) {
                LLog.i("Pulled " + po + " not found");
                return false;
            }
        }

        String originalJarFolder = MiscUtil.path(workingDir, FOLDER_BOOT_JAR_ORIGINAL);
        MiscUtil.mkdirs(new File(originalJarFolder));
        for (String jar : device.getBootClassPath()) {
            LLog.i("Pulling " + jar);
            device.pullFileToFolder(jar, originalJarFolder);
        }
        OatUtil.bootOat2Jar(MiscUtil.path(workingDir, oatName),
                originalJarFolder, outBootJarFolder);
        return true;
    }
}
