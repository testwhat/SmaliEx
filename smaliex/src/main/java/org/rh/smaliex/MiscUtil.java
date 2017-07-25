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

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class MiscUtil {

    public static File changeExt(File f, String targetExt) {
        String outPath = f.getAbsolutePath();
        if (!outPath.endsWith(targetExt)) {
            int dotPos = outPath.lastIndexOf(".");
            if (dotPos > 0) {
                outPath = outPath.substring(0, dotPos + 1) + targetExt;
            } else {
                outPath = outPath + "." + targetExt;
            }
            return new File(outPath);
        }
        return f;
    }

    public static int toInt(String str) {
        int len = str.length(), p = 0;
        char[] sb = new char[len];
        for (int i = 0; i < len; i++) {
            char c = str.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-') {
                sb[p++] = c;
            }
        }

        return p == 0 ? 0 : Integer.parseInt(new String(sb, 0, p));
    }

    public static File appendTail(File f, String str) {
        String name = getFilenameNoExt(f.getName())
                + str + "." + getFilenameExt(f.getName());
        return new File(getFileDirPath(f.getAbsolutePath()), name);
    }

    @Nonnull
    public static File[] getFiles(String path, String extensionsStr) {
        File dir = new File(path);
        final String[] extensions = extensionsStr.split(";");
        for (int i = 0; i < extensions.length; i++) {
            extensions[i] = extensions[i].toLowerCase();
        }
        File[] files =  dir.listFiles((dir1, name) -> {
            for (String ext : extensions) {
                if (name.toLowerCase().endsWith(ext)) {
                    return true;
                }
            }
            return false;
        });
        return files == null ? new File[0] : files;
    }

    public static String getFilenameNoExt(String filename) {
        int dotPos = filename.lastIndexOf('.');
        if (dotPos == -1) {
            return filename;
        }
        return filename.substring(0, dotPos);
    }

    public static String getFilenameExt(String filename) {
        int dotPos = filename.lastIndexOf('.');
        if (dotPos == -1) {
            return "";
        }
        return filename.substring(dotPos + 1);
    }

    public static String getFileDirPath(String path) {
        return path.substring(0, path.lastIndexOf(java.io.File.separatorChar) + 1);
    }

    public static String workingDir() {
        return System.getProperty("user.dir");
    }

    public static String path(String... path) {
        StringBuilder sb = new StringBuilder(128);
        final int last = path.length - 1;
        for (int i = 0; i < last; i++) {
            if (path[i].length() < 1) {
                continue;
            }
            sb.append(path[i]);
            if (!path[i].endsWith(File.separator)) {
                sb.append(File.separator);
            }
        }
        sb.append(path[last]);
        return sb.toString();
    }

    public static void mkdirs(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            LLog.e("Failed to create directory " + dir);
        }
    }

    public static void delete(File file) {
        if (!file.delete()) {
            LLog.e("Failed to delete " + file);
        }

    }
    public static byte[] readBytes(java.io.InputStream input, int size) throws IOException {
        byte[] data = new byte[size];
        int remain = data.length;
        int read = 0;
        int readSize;
        while (remain > 0 && (readSize = input.read(data, read, remain)) != -1) {
            remain -= readSize;
            read += readSize;
        }
        return data;
    }

    public static byte[] readBytes(java.io.InputStream input) throws IOException {
        return readBytes(input, input.available());
    }

    static boolean checkFourBytes(File file, int offset, long fourBytes) {
        long n = 0;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.skipBytes(offset);
            n = raf.readInt();
        } catch (IOException ex) {
            LLog.ex(ex);
        }
        return n == fourBytes;
    }

    public static boolean isZip(File file) {
        return checkFourBytes(file, 0, 0x504B0304);
    }

    public static boolean isDex(File file) {
        return checkFourBytes(file, 0, 0x6465780A);
    }

    public static boolean isElf(File file) {
        return checkFourBytes(file, 0, 0x7F454C46);
    }
}
