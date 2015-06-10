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
import java.util.ArrayList;
import java.util.List;

import org.jf.baksmali.baksmaliOptions;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.rh.smaliex.reader.Elf;
import org.rh.smaliex.reader.Oat;

public class Main {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Easy oat2dex 0.81");
            System.out.println("Usage:");
            System.out.println("Get dex from boot.oat: boot <boot.oat>");
            System.out.println("Get dex from  app oat: <oat-file> <boot-class-folder>");
            System.out.println("Get raw odex from oat: odex <oat-file>");
            System.out.println("Get raw odex smali   : smali <oat/odex file>");
            return;
        }
        if ("boot".equals(args[0])) {
            OatUtil.bootOat2Dex(args[1]);
            return;
        }
        if ("odex".equals(args[0])) {
            OatUtil.extractOdexFromOat(new File(args[1]), null);
            return;
        }
        if ("smali".equals(args[0])) {
            try {
                smaliRaw(new File(args[1]));
            } catch (IOException ex) {
                LLog.ex(ex);
            }
            return;
        }
        try {
            OatUtil.oat2dex(args[0], args[1]);
        } catch (IOException ex) {
            LLog.ex(ex);
        }
    }

    public static void smaliRaw(File inputFile) throws IOException {
        if (!inputFile.isFile()) {
            LLog.i(inputFile + " is not a file.");
        }
        String folderName = MiscUtil.getFileNamePrefix(inputFile.getName());
        String outputBaseFolder = MiscUtil.path(inputFile.getParent(), folderName);
        baksmaliOptions options = new baksmaliOptions();
        Opcodes opc = new Opcodes(Opcode.LOLLIPOP);
        options.apiLevel = opc.apiLevel;
        options.allowOdex = true;
        options.jobs = 4;

        List<DexBackedDexFile> dexFiles = new ArrayList<>();
        List<String> outSubFolders = new ArrayList<>();
        if (Elf.isElf(inputFile)) {
            byte[] buf = new byte[8192];
            try (Elf e = new Elf(inputFile)) {
                Oat oat = OatUtil.getOat(e);
                for (int i = 0; i < oat.mDexFiles.length; i++) {
                    Oat.DexFile df = oat.mDexFiles[i];
                    dexFiles.add(OatUtil.readDex(df, df.mHeader.file_size_, opc, buf));
                    String opath = new String(oat.mOatDexFiles[i].dex_file_location_data_);
                    opath = MiscUtil.getFileNamePrefix(OatUtil.getOuputNameForSubDex(opath));
                    outSubFolders.add(MiscUtil.path(outputBaseFolder, opath));
                }
            }
        } else {
            dexFiles = DexUtil.loadMultiDex(inputFile, opc);
            String subFolder = "classes";
            for (int i = 0; i < dexFiles.size(); i++) {
                outSubFolders.add(MiscUtil.path(outputBaseFolder, subFolder));
                subFolder = "classes" + (i + 2);
            }
        }
        if (outSubFolders.size() == 1) {
            outSubFolders.set(0, outputBaseFolder);
        }

        for (int i = 0; i < dexFiles.size(); i++) {
            options.outputDirectory = outSubFolders.get(i);
            org.jf.baksmali.baksmali.disassembleDexFile(dexFiles.get(i), options);
            LLog.i("Output to " + options.outputDirectory);
        }
        LLog.i("All done");
    }
}
