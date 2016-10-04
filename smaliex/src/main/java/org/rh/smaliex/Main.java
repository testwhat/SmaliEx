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

public class Main {

    static void printUsage() {
        System.out.println("Easy oat2dex 0.88");
        System.out.println("Usage:");
        System.out.println(" java -jar oat2dex.jar [options] <action>");
        System.out.println("[options]");
        System.out.println(" Api level (for raw odex): -a <integer>");
        System.out.println(" Output folder: -o <folder path>");
        System.out.println(" Print detail : -v");
        System.out.println("<action>");
        System.out.println(" Get dex from boot.oat: boot <boot.oat/boot-folder>");
        System.out.println(" Get dex from oat/odex: <oat/odex-file/folder> <boot-class-folder>");
        System.out.println(" Get raw odex from oat: odex <oat-file/folder>");
        System.out.println(" Get raw odex smali   : smali <oat/odex-file>");
        System.out.println(" Deodex framework     : devfw [empty or path of /system/framework/]");
    }

    public static void main(String[] args) {
        try {
            mainImpl(args);
        } catch (IOException ex) {
            exit("Unhandled IOException: " + ex.getMessage());
        }
    }

    public static void mainImpl(String[] args) throws IOException {
        String outputFolder = null;
        int apiLevel = DexUtil.DEFAULT_API_LEVEL;
        if (args.length > 2) {
            String opt = args[0];
            while (opt.length() > 1 && opt.charAt(0) == '-') {
                int shift = 1;
                switch (opt.charAt(1)) {
                    case 'a':
                        try {
                            apiLevel = Integer.parseInt(args[1]);
                        } catch (NumberFormatException e) {
                            System.out.println("Invalid api level: " + args[1]);
                        }
                        shift = 2;
                        break;
                    case 'o':
                        outputFolder = args[1];
                        shift = 2;
                        break;
                    case 'v':
                        LLog.VERBOSE = true;
                        break;
                    default:
                        System.out.println("Unrecognized option: " + opt);
                }
                String[] newArgs = shiftArgs(args, shift);
                if (newArgs != args) {
                    args = newArgs;
                    if (newArgs.length < shift) {
                        break;
                    }
                } else {
                    break;
                }

                opt = args[0];
            }
        }
        if (args.length < 1) {
            printUsage();
            return;
        }

        String cmd = args[0];
        if ("devfw".equals(cmd)) {
            DeodexFrameworkFromDevice.deOptimizeAuto(
                    args.length > 1 ? args[1] : null, outputFolder);
            return;
        }
        if (args.length == 2) {
            if ("boot".equals(cmd)) {
                checkExist(args[1]);
                OatUtil.bootOat2Dex(args[1], outputFolder);
                return;
            }
            if ("odex".equals(cmd)) {
                OatUtil.extractOdexFromOat(checkExist(args[1]),
                        outputFolder == null ? null : new File(outputFolder));
                return;
            }
            if ("smali".equals(cmd)) {
                OatUtil.smaliRaw(checkExist(args[1]), apiLevel);
                return;
            }
            String inputPath = args[0];
            String bootPath = args[1];
            File input = checkExist(inputPath);
            checkExist(bootPath);
            int type = getInputType(input);
            if (type > 0) {
                if (type == TYPE_ODEX) {
                    DexUtil.odex2dex(inputPath, bootPath, outputFolder, apiLevel);
                } else if (type == TYPE_OAT) {
                    OatUtil.oat2dex(inputPath, bootPath, outputFolder);
                }
            } else {
                exit("Unknown input file type: " + input);
            }
        } else {
            printUsage();
        }
    }

    static String[] shiftArgs(String[] args, int n) {
        if (n >= args.length) {
            return args;
        }
        String[] shiftArgs = new String[args.length - n];
        System.arraycopy(args, n, shiftArgs, 0, shiftArgs.length);
        return shiftArgs;
    }

    static File checkExist(String path) {
        File input = new File(path);
        if (!input.exists()) {
            exit("Input file not found: " + input);
        }
        return input;
    }

    final static int TYPE_ODEX = 1;
    final static int TYPE_OAT = 2;

    static int getInputType(File input) {
        if (input.isDirectory()) {
            File[] files = MiscUtil.getFiles(input.getAbsolutePath(), "dex;oat");
            if (files.length > 0) {
                input = files[0];
            }
        }
        if (MiscUtil.isDex(input)) {
            return TYPE_ODEX;
        }
        if (MiscUtil.isElf(input)) {
            return TYPE_OAT;
        }
        return -1;
    }

    static void exit(String msg) {
        System.out.println(msg);
        System.exit(1);
    }
}
