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
import java.io.FilenameFilter;
import java.util.ArrayList;

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.analysis.ClassPath;
import org.jf.dexlib2.iface.DexFile;

public class MiscUtil {

    public static File changeExt(File f, String targetExt) {
        String outPath = f.getAbsolutePath();
        if (!outPath.endsWith(targetExt)) {
            int dpos = outPath.lastIndexOf(".");
            if (dpos > 0) {
                outPath = outPath.substring(0, dpos + 1) + targetExt;
            } else {
                outPath = outPath + "." + targetExt;
            }
            return new File(outPath);
        }
        return f;
    }

    public static File appendTail(File f, String str) {
        String name = getFilenamePrefix(f.getName())
                + str + "." + getFilenameSuffix(f.getName());
        return new File(getFileDirPath(f.getAbsolutePath()), name);
    }

    static File[] getFiles(String path, String _exts) {
        File dir = new File(path);
        final String[] exts = _exts.split(";");
        for (int i = 0; i < exts.length; i++) {
            exts[i] = exts[i].toLowerCase();
        }
        return dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                for (String ext : exts) {
                    if (name.toLowerCase().endsWith(ext)) {
                        return true;
                    }
                }
                return false;
            }
        });
    }

    public static String getFilenamePrefix(String filename) {
        int dotPos = filename.lastIndexOf('.');
        if (dotPos == -1) {
            return filename;
        }
        return filename.substring(0, dotPos);
    }

    public static String getFilenameSuffix(String filename) {
        int dotPos = filename.lastIndexOf('.');
        if (dotPos == -1) {
            return "";
        }
        return filename.substring(dotPos + 1);
    }

    public static String getFileDirPath(String path) {
        return path.substring(0, path.lastIndexOf(java.io.File.separatorChar) + 1);
    }

    public static ClassPath getClassPath(String path, Opcodes opcodes, String ext) {
        ArrayList<DexFile> dexFiles = new ArrayList<>();
        for (File f : MiscUtil.getFiles(path, ext)) {
            dexFiles.addAll(DexUtil.loadMultiDex(f, opcodes));
        }
        return new ClassPath(dexFiles, opcodes.apiLevel);
    }

    public static String workingDir() {
        return System.getProperty("user.dir");
    }

    public static String path(String... path) {
        StringBuilder sb = new StringBuilder(64);
        int last = path.length - 1;
        for (int i = 0; i < last; i++) {
            sb.append(path[i]).append(File.separator);
        }
        sb.append(path[last]);
        return sb.toString();
    }
}
