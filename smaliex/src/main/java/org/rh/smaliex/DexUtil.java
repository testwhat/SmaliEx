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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nonnull;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodParameter;

public class DexUtil {
    public static final int API_LEVEL = 19;
    public static Opcodes DEFAULT_OPCODES;

    public static boolean isZip(File f) {
        long n = 0;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            n = raf.readInt();
        } catch (IOException ex) {
            LLog.ex(ex);
        }
        return n == 0x504B0304;
    }

    static Opcodes getDefaultOpCodes(Opcodes opc) {
        if (opc == null) {
            if (DEFAULT_OPCODES == null) {
                DEFAULT_OPCODES = new Opcodes(API_LEVEL);
            }
            opc = DEFAULT_OPCODES;
        }
        return opc;
    }

    public static DexBackedDexFile loadSingleDex(File file, Opcodes opc) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(bytes);
        }
        return new DexBackedDexFile(getDefaultOpCodes(opc), bytes);
    }

    public static List<DexBackedDexFile> loadMultiDex(File file) {
        return loadMultiDex(file, null);
    }

    public static List<DexBackedDexFile> loadMultiDex(File file, Opcodes opc) {
        List<DexBackedDexFile> dexFiles = new ArrayList<>();
        opc = getDefaultOpCodes(opc);
        try {
            if (isZip(file)) {
                List<byte[]> dexBytes = readMultipleDexFromJar(file);
                for (byte[] data : dexBytes) {
                    dexFiles.add(new DexBackedDexFile(opc, data));
                }
            } else {
                dexFiles.add(loadSingleDex(file, opc));
            }
        } catch (IOException ex) {
            LLog.ex(ex);
        }
        return dexFiles;
    }

    public static List<byte[]> readMultipleDexFromJar(File file) throws IOException {
        List<byte[]> dexBytes = new ArrayList<>(2);
        try (ZipFile zipFile = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> zs = zipFile.entries();
            byte[] buf = new byte[8192];
            ByteArrayOutputStream out = new ByteArrayOutputStream(1024 * 1024);
            while (zs.hasMoreElements()) {
                ZipEntry entry = zs.nextElement();
                String name = entry.getName();
                if (name.startsWith("classes") && name.endsWith(".dex")) {
                    if (entry.getSize() < 40) {
                        LLog.i("The dex file in " + file + " is too small to be a valid dex file");
                        continue;
                    }
                    InputStream is = zipFile.getInputStream(entry);
                    for (int c = is.read(buf); c > 0; c = is.read(buf)) {
                        out.write(buf, 0, c);
                    }
                    dexBytes.add(out.toByteArray());
                    out.reset();
                }
            }
            if (dexBytes.isEmpty()) {
                throw new IOException( "Cannot find classes.dex in zip file");
            }
            return dexBytes;
        }
    }

    @Nonnull
    public static String getMethodString(Method m, StringBuilder sb) {
        sb.setLength(0);
        for (MethodParameter param : m.getParameters()) {
            String pname = param.getName();
            if (pname == null) {
                pname = "obj";
            }
            sb.append(DexUtil.typeString(param.getType())).append(" ").append(pname).append(", ");
        }
        if (sb.length() > 1) {
            sb.setLength(sb.length() - 2);
        }
        String paramStr = sb.toString();
        sb.setLength(0);
        String accStr = AccessFlags.formatAccessFlagsForMethod(m.getAccessFlags());
        return sb.append(accStr).append((accStr.length() > 0 ? " " : ""))
                .append(DexUtil.typeString(m.getReturnType())).append(" ").append(m.getName())
                .append("(").append(paramStr).append(")").toString();
    }

    public static boolean isSyntheticMethod(Method m) {
        return (m.getAccessFlags() & AccessFlags.SYNTHETIC.getValue()) != 0;
    }

    public static String typeString(String type) {
        switch (type.charAt(0)) {
            case 'V':
                return "void";
            case 'Z':
                return "boolean";
            case 'L':
                return type.substring(1, type.length() - 1).replace("/", ".");
            case 'C':
                return "char";
            case 'B':
                return "byte";
            case 'S':
                return "short";
            case 'I':
                return "int";
            case 'F':
                return "float";
            case 'J':
                return "long";
            case 'D':
                return "double";
            case '[':
                return typeString(type.substring(1)) + "[]";
        }
        return "void";
    }

    public static void dumpMethodList(DexFile df) {
        StringBuilder sb = new StringBuilder();
        for (ClassDef c : df.getClasses()) {
            String accStr = AccessFlags.formatAccessFlagsForMethod(c.getAccessFlags());
            LLog.i(accStr + (accStr.length() > 0 ? " " : "")
                    + DexUtil.typeString(c.getType()));
            for (Method m : c.getMethods()) {
                if (DexUtil.isSyntheticMethod(m)) {
                    continue;
                }
                LLog.i(DexUtil.getMethodString(m, sb));
            }
        }
    }

    public static void createDexJar(String[] files, String output) {
        Manifest manifest = new Manifest();
        Attributes attribute = manifest.getMainAttributes();
        attribute.putValue("Manifest-Version", "1.0");

        final byte[] buf = new byte[8192];
        int readSize;
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(output), manifest)) {
            String idx = "";
            int i = 1;
            for (String file : files) {
                try (FileInputStream in = new FileInputStream(file)) {
                    String filename = file.replace('\\', '/');
                    filename = filename.substring(filename.lastIndexOf('/') + 1);
                    if (filename.endsWith(".dex")) {
                        jos.putNextEntry(new ZipEntry("classes" + idx + ".dex"));
                        idx = String.valueOf(++i);
                    }
                    jos.putNextEntry(new ZipEntry(filename));
                    while ((readSize = in.read(buf, 0, buf.length)) != -1) {
                        jos.write(buf, 0, readSize);
                    }
                }
                jos.closeEntry();
            }
        } catch (IOException e) {
            LLog.ex(e);
        }
    }
}
