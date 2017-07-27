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

import com.android.ddmlib.Device;

import org.rh.smaliex.reader.Elf;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class DeodexFrameworkFromDevice {

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
        }
        deOptimizeAuto(sysFolder, outFolder);
    }

    public final static String FOLDER_BOOT_JAR_ORIGINAL = "boot-jar-original";
    public final static String FOLDER_BOOT_JAR_RESULT = "boot-jar-with-dex";
    public final static String FOLDER_BOOT_ODEX = "boot-raw";
    public final static String FOLDER_FRAMEWORK_ODEX = "framework-odex";
    public final static String FOLDER_FRAMEWORK_JAR = "framework-jar-original";
    public final static String FOLDER_FRAMEWORK_JAR_DEX = "framework-jar-with-dex";

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
        final static String[] ABIS = {"arm64", "arm", "x64", "x86"};
        String mAbiFolder = "arm/";

        abstract void pullFileToFolder(String remote, String localFolder);
        abstract String[] getFileList(String path);
        abstract boolean isFileExist(String path);
        abstract String[] getBootClassPath();
        abstract String getName();

        String getBootOatLocation() {
            return SYS_FRAMEWORK + mAbiFolder;
        }

        String getNonBootOatLocation() {
            return SYS_FRAMEWORK + "oat/" + mAbiFolder;
        }

        // Folder structure
        // legacy:
        //   /system/framework/<abi>: boot, non-boot
        // current:
        //   /system/framework/<abi>: boot
        //   /system/framework/oat/<abi>: non-boot
    }

    static FwProvider createFwProvider(Device device) {
        return new DeviceFwProvider(device);
    }

    static FwProvider createFwProvider(File folder) {
        return new FileFwProvider(folder);
    }

    static class DeviceFwProvider extends FwProvider {
        final Device mDevice;
        final String[] mBootJars;

        DeviceFwProvider(Device device) {
            mDevice = device;
            mBootJars = AdbUtil.getBootClassPath(mDevice);
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
        void pullFileToFolder(String remote, String localFolder) {
            AdbUtil.pullFileToFolder(mDevice, remote, localFolder);
        }

        @Override
        String[] getFileList(String path) {
            return AdbUtil.getFileList(mDevice, path);
        }

        @Override
        boolean isFileExist(String path) {
            return AdbUtil.isFileExist(mDevice, path);
        }

        @Override
        String[] getBootClassPath() {
            return mBootJars;
        }

        @Override
        String getName() {
            return mDevice.getName();
        }
    }

    static class FileFwProvider extends FwProvider {
        final File mFolder;
        final boolean mContainsSysFw;
        final ArrayList<String> mBootJars;

        FileFwProvider(File folder) {
            mFolder = folder;
            mContainsSysFw = new File(folder, SYS_FRAMEWORK).isDirectory();
            for (String abi : ABIS) {
                if (new File(mFolder, autoPath(SYS_FRAMEWORK + abi)).isDirectory()) {
                    mAbiFolder = abi + "/";
                    break;
                }
            }
            File bootLocation = new File(mFolder, autoPath(getBootOatLocation()));
            if (!bootLocation.isDirectory()) {
                throw new RuntimeException(bootLocation + " is not a folder");
            }
            mBootJars = OatUtil.getBootJarNames(
                    bootLocation.getAbsolutePath(), mContainsSysFw);
        }

        String autoPath(String path) {
            return mContainsSysFw ? path : path.replace(SYS_FRAMEWORK, "/");
        }

        @Override
        void pullFileToFolder(String remote, String localFolder) {
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
        String[] getFileList(String path) {
            return new File(mFolder, autoPath(path)).list();
        }

        @Override
        boolean isFileExist(String path) {
            return new File(mFolder, autoPath(path)).exists();
        }

        @Override
        String[] getBootClassPath() {
            if (!mBootJars.isEmpty()) {
                return mBootJars.stream().map(f -> SYS_FRAMEWORK + f).toArray(String[]::new);
            }
            String[] bootJars = {
                    "core-oj.jar",
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
        String getName() {
            return mFolder.getName();
        }
    }

    static void deOptimizeFramework(FwProvider device, String workingDir) throws IOException {
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
                                            String workingDir,
                                            String bootDir) throws IOException {
        final String pullOdexDir = MiscUtil.path(workingDir, FOLDER_FRAMEWORK_ODEX);
        final String pullJarDir = MiscUtil.path(workingDir, FOLDER_FRAMEWORK_JAR);

        MiscUtil.mkdirs(new File(pullOdexDir));
        MiscUtil.mkdirs(new File(pullJarDir));
        final File resultJarDir = new File(MiscUtil.path(workingDir, FOLDER_FRAMEWORK_JAR_DEX));
        MiscUtil.mkdirs(resultJarDir);

        java.util.HashMap<String, String[]> fileLists = new java.util.HashMap<>();
        String oatLocation = device.getNonBootOatLocation();
        if (!device.isFileExist(oatLocation)) {
            oatLocation = SYS_FRAMEWORK + device.mAbiFolder;
        }
        String[] paths = {device.getBootOatLocation(), device.getNonBootOatLocation()};
        for (String path : paths) {
            fileLists.put(path, device.getFileList(path));
        }

        final List<String> excludePrefixes = Arrays.stream(device.getBootClassPath()).map(p ->
                "boot-" + MiscUtil.getFilenameNoExt(MiscUtil.getFilenameNoPath(p))
        ).collect(Collectors.toList());

        for (java.util.Map.Entry<String, String[]> entry : fileLists.entrySet()) {
            final String path = entry.getKey();
            final String[] files = entry.getValue();
            if (files == null) {
                LLog.e("Cannot list " + path + " from " + device.getName());
                continue;
            }

            String[] filteredFiles = Arrays.stream(files).filter(f ->
                    !f.endsWith(".art") && !f.startsWith("boot.")
                            && excludePrefixes.stream().noneMatch(f::startsWith)
            ).toArray(String[]::new);

            for (String f : filteredFiles) {
                String oat = oatLocation + f;
                LLog.i("Pulling " + oat + " -> " + pullOdexDir);
                device.pullFileToFolder(oat, pullOdexDir);

                String jar = SYS_FRAMEWORK + MiscUtil.getFilenameNoExt(f) + ".jar";
                LLog.i("Pulling " + jar + " -> " + pullJarDir);
                device.pullFileToFolder(jar, pullJarDir);
            }

            for (String f : filteredFiles) {
                File odex = new File(MiscUtil.path(pullOdexDir, f));
                if (MiscUtil.isOat(odex)) {
                    try (Elf e = new Elf(odex)) {
                        OatUtil.convertToDexJar(
                                OatUtil.getOat(e), resultJarDir,
                                bootDir, pullJarDir, false);
                    }
                } else if (MiscUtil.isOdex(odex)) {
                    LLog.i("Not support repacking legacy " + odex + " yet");
                }
            }
        }
    }

    static boolean generateBootJar(FwProvider device, String workingDir,
            String outBootJarFolder) throws IOException {
        String bootOatDir = MiscUtil.path(workingDir, FOLDER_BOOT_ODEX);
        MiscUtil.mkdirs(new File(bootOatDir));

        String bootOatLocation = device.getBootOatLocation();
        LLog.i("Preparing boot jars from " + device.getName());
        for (String file : device.getFileList(bootOatLocation)) {
            if (file.endsWith(".art")) continue;
            File targetFile = new File(bootOatDir, file);
            if (targetFile.exists()) {
                LLog.i("Found " + targetFile + ", skip pull " + file);
                continue;
            }

            String fileLoc = bootOatLocation + file;
            LLog.i("Pulling " + fileLoc + " -> " + bootOatDir);
            device.pullFileToFolder(fileLoc, bootOatDir);
            if (!targetFile.exists()) {
                LLog.i("Pulled " + targetFile + " not found");
                return false;
            }
        }

        String originalJarFolder = MiscUtil.path(workingDir, FOLDER_BOOT_JAR_ORIGINAL);
        MiscUtil.mkdirs(new File(originalJarFolder));
        for (String jar : device.getBootClassPath()) {
            File targetFile = new File(originalJarFolder, MiscUtil.getFilenameNoPath(jar));
            if (targetFile.exists()) {
                LLog.i("Found " + targetFile + ", skip pull " + jar);
                continue;
            }
            LLog.i("Pulling " + jar + " -> " + originalJarFolder);
            device.pullFileToFolder(jar, originalJarFolder);
        }
        OatUtil.bootOat2Jar(bootOatDir, originalJarFolder, outBootJarFolder);
        return true;
    }
}
