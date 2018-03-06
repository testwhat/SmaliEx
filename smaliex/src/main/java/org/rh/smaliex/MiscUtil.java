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
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.ref.SoftReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

public class MiscUtil {

    public static File changeExt(File f, String targetExt) {
        String outPath = f.getAbsolutePath();
        if (!getFilenameExt(outPath).equals(targetExt)) {
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

    public static String getFilenameNoPath(String path) {
        String[] tokens = path.split("[\\\\|/]");
        return tokens[tokens.length - 1];
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

    @Nullable
    public static <K, T> T getCache(@Nonnull Map<K, SoftReference<T>> pool, K key) {
        final SoftReference<T> ref = pool.get(key);
        if (ref != null) {
            return ref.get();
        }
        return null;
    }

    public static <K, T> void putCache(@Nonnull Map<K, SoftReference<T>> pool, K key, T val) {
        pool.put(key, new SoftReference<>(val));
    }

    @Nonnull
    public static File[] getAsFiles(@Nonnull File path, @Nonnull String extensionsStr) {
        return path.isDirectory()
                ? MiscUtil.getFiles(path.getAbsolutePath(), extensionsStr)
                : new File[] { path };
    }

    @Nonnull
    public static File ensureOutputDir(@Nonnull File src,
                                       @Nullable File out, @Nonnull String postfix) {
        if (out == null) {
            out = new File(src.getParentFile(), src.getName() + postfix);
        }
        mkdirs(out);
        return out;
    }

    @Nonnull
    public static File ensureOutputDir(@Nonnull String src,
                                       @Nullable String out, @Nonnull String postfix) {
        return ensureOutputDir(new File(src), out == null ? null : new File(out), postfix);
    }

    static boolean checkFourBytes(File file, int offset, long fourBytes) {
        if (file.length() < offset) return false;
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

    public static boolean isOdex(File file) {
        return checkFourBytes(file, 0, 0x6465790A);
    }

    public static boolean isVdex(File file) {
        return checkFourBytes(file, 0, 0x76646578);
    }

    public static boolean isOat(File file) {
        return checkFourBytes(file, 0x1000, 0x6F61740A);
    }

    public static boolean isElf(File file) {
        return checkFourBytes(file, 0, 0x7F454C46);
    }

    public static void dump(final Object obj) {
        try {
            dump0(obj);
        } catch (IllegalArgumentException | IllegalAccessException ex) {
            LLog.ex(ex);
        }
    }

    public static void dump0(final Object obj) throws IllegalArgumentException, IllegalAccessException {
        final Field[] fields = obj.getClass().getDeclaredFields();

        for (Field field : fields) {
            if (field.getModifiers() == Modifier.STATIC
                    || !field.getName().endsWith("_")) {
                continue;
            }
            field.setAccessible(true);
            final Class<?> type = field.getType();
            System.out.print(field.getName() + " = ");
            final Object val = field.get(obj);
            if (val == null) {
                System.out.println("null");
                continue;
            }
            final DumpFormat fmt = field.getAnnotation(DumpFormat.class);
            if (fmt != null) {
                if (fmt.isString()) {
                    final String rawStr;
                    if (fmt.type() == DumpFormat.TYPE_BYTE) {
                        rawStr = new String((byte[]) val);
                    } else {
                        rawStr = new String((char[]) val);
                    }
                    System.out.println(rawStr.trim());
                } else if (fmt.enumClass() != DumpFormat.class) {
                    final Field[] enumDefines = fmt.enumClass().getDeclaredFields();
                    boolean matched = false;
                    for (Field f : enumDefines) {
                        if (val.equals(f.get(null))) {
                            System.out.println(f.getName());
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        System.out.println(val);
                    }
                } else {
                    if (type.isArray()) {
                        final byte[] bytes = (byte[]) val;
                        final String sf = fmt.hex() ? "%02X " : "%d ";
                        for (byte b : bytes) {
                            System.out.printf(sf, b);
                        }
                        System.out.println();
                    } else {
                        System.out.printf(fmt.hex() ? "0x%X\n" : "%d\n", val);
                    }
                }
            } else {
                if (type.isArray()) {
                    final Class<?> compType = type.getComponentType();
                    System.out.println(compType + "[" + Array.getLength(val) + "]");
                } else {
                    System.out.println(val);
                }
            }
        }
    }

    @Retention(value = RetentionPolicy.RUNTIME)
    @Target(value = {ElementType.FIELD})
    public @interface DumpFormat {
        int TYPE_BYTE = 0;
        int TYPE_CHAR = 1;
        int type() default -1;
        boolean isString() default false;
        boolean hex() default false;
        Class<?> enumClass() default DumpFormat.class;
    }
}
