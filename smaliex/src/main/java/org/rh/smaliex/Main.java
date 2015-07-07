/*
 * [The "BSD licence"]
 * Copyright (c) 2014 Riddle Hsu
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.rh.smaliex;

import java.io.File;
import java.io.IOException;

public class Main {

    static void printUsage() {
        System.out.println("Easy oat2dex 0.83");
        System.out.println("Usage:");
        System.out.println("Get dex from boot.oat: boot <boot.oat>");
        System.out.println("Get dex from  app oat: <oat-file> <boot-class-folder>");
        System.out.println("Get raw odex from oat: odex <oat-file>");
        System.out.println("Get raw odex smali   : smali <oat/odex file>");
        System.out.println("Deodex framework     : devfw");
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            return;
        }
        String cmd = args[0];
        if ("devfw".equals(cmd)) {
            String[] shiftArgs = new String[args.length - 1];
            System.arraycopy(args, 1, shiftArgs, 0, shiftArgs.length);
            DeodexFrameworkFromDevice.main(shiftArgs);
            return;
        }
        if (args.length == 2) {
            if ("boot".equals(cmd)) {
                checkExist(args[1]);
                OatUtil.bootOat2Dex(args[1]);
                return;
            }
            if ("odex".equals(cmd)) {
                OatUtil.extractOdexFromOat(checkExist(args[1]), null);
                return;
            }
            if ("smali".equals(cmd)) {
                OatUtil.smaliRaw(checkExist(args[1]));
                return;
            }
            checkExist(args[0]);
            checkExist(args[1]);
            try {
                OatUtil.oat2dex(args[0], args[1]);
            } catch (Exception ex) {
                LLog.ex(ex);
            }
        } else {
            printUsage();
        }
    }

    static File checkExist(String path) {
        File input = new File(path);
        if (!input.exists()) {
            System.out.println("Input file not found: " + input);
            System.exit(0);
        }
        return input;
    }
}
