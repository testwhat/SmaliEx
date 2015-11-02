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
        if (args.length > 0) {
            String a0 = args[0];
            if (new File(a0).isDirectory()) {
                deOptimizeFromFrameworkFolder(a0);
                return;
            }
            if ("t".equals(a0)) {
                TEST_MODE = true;
                LLog.i("Force test mode, using dalvik-cache");
            }
        }
        deOptimizeAuto();
    }

    public final static String FOLDER_BOOT_JAR_ORIGINAL = "boot-jar-original";
    public final static String FOLDER_BOOT_JAR_RESULT = "boot-jar-result";
    public final static String FOLDER_FRAMEWORK_ODEX = "framework-odex";
    public final static String FOLDER_FRAMEWORK_JAR = "framework-jar-original";
    public final static String FOLDER_FRAMEWORK_JAR_DEX = "framework-jar-with-dex";

    public final static String BOOT_OAT = "boot.oat";
    public final static String SYS_FRAMEWORK = "/system/framework/";
    public final static String FRAMEWORK_ARM = SYS_FRAMEWORK + "arm/";
    public final static String FRAMEWORK_ARM64 = SYS_FRAMEWORK + "arm64/";

    public static void deOptimizeAuto() {
        AdbUtil.runOneTimeAction(new AdbUtil.OneTimeAction() {

            @Override
            public void run(Device device) throws Exception {
                deOptimizeFramework(createFwProvider(device), MiscUtil.workingDir());
            }

            @Override
            public String onDisconnectedMsg() {
                return "The de-opt flow may not complete";
            }
        });
    }

    public static void deOptimizeFromFrameworkFolder(String sysFwFolder) {
        LLog.i("From " + sysFwFolder);
        try {
            deOptimizeFramework(createFwProvider(new File(sysFwFolder)), MiscUtil.workingDir());
        } catch (IOException e) {
            LLog.ex(e);
        }
    }

    interface FwProvider {
        void pullFileToFolder(String remote, String localFolder);
        String[] getFileList(String path);
        boolean isFileExist(String path);
        String[] getBootClassPath();
        String getName();
    }

    static FwProvider createFwProvider(Device device) {
        return new DeviceFwProvider(device);
    }

    static FwProvider createFwProvider(File folder) {
        return new FileFwProvider(folder);
    }

    static class DeviceFwProvider implements FwProvider {
        final Device mDevice;

        DeviceFwProvider(Device device) {
            mDevice = device;
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

    static class FileFwProvider implements FwProvider {
        final File mFolder;
        final boolean mContainsSysFw;
        FileFwProvider(File folder) {
            mFolder = folder;
            mContainsSysFw = new File(folder, SYS_FRAMEWORK).isDirectory();
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
        final String OAT_DIR = FRAMEWORK_ARM;
        final String PULL_ODEX_DIR = MiscUtil.path(workingDir, FOLDER_FRAMEWORK_ODEX);
        final String PULL_JAR_DIR = MiscUtil.path(workingDir, FOLDER_FRAMEWORK_JAR);

        new File(PULL_ODEX_DIR).mkdirs();
        new File(PULL_JAR_DIR).mkdirs();
        final File RESULT_JAR_DIR = new File(MiscUtil.path(workingDir, FOLDER_FRAMEWORK_JAR_DEX));
        RESULT_JAR_DIR.mkdirs();

        String[] files = device.getFileList(OAT_DIR);
        if (files == null) {
            LLog.e("Cannot list " + OAT_DIR + " from " + device.getName());
            return;
        }
        for (String f : files) {
            if (f.startsWith("boot.")) {
                continue;
            }

            String oat = OAT_DIR + f;
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

    static boolean generateBootJar(FwProvider device, String workingDir,
            String outBootJarFolder) throws IOException {
        String oatName = BOOT_OAT;
        String bootOatHere = MiscUtil.path(workingDir, oatName);
        if (new File(bootOatHere).exists()) {
            LLog.i("Found " + bootOatHere + ", skip pull " + BOOT_OAT);
        } else {
            String bootOat = FRAMEWORK_ARM + oatName;
            if (TEST_MODE) {
                oatName = "system@framework@boot.oat";
                bootOat = "/data/dalvik-cache/arm/" + oatName;
            }
            boolean isPreOptRom = device.isFileExist(bootOat);
            if (!isPreOptRom) {
                String bootOat64 = FRAMEWORK_ARM64 + oatName;
                isPreOptRom = device.isFileExist(bootOat64);
                if (isPreOptRom) {
                    LLog.i("Using 64-bit boot oat " + bootOat64);
                    bootOat = bootOat64;
                } else {
                    LLog.i("The rom does not have pre-compiled " + bootOat);
                    return false;
                }
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
        new File(originalJarFolder).mkdirs();
        for (String jar : device.getBootClassPath()) {
            LLog.i("Pulling " + jar);
            device.pullFileToFolder(jar, originalJarFolder);
        }
        OatUtil.bootOat2Jar(MiscUtil.path(workingDir, oatName),
                originalJarFolder, outBootJarFolder);
        return true;
    }
}
