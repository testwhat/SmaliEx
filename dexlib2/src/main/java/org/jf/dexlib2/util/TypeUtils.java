/*
 * Copyright 2012, Google Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.jf.dexlib2.util;

import javax.annotation.Nonnull;

import org.jf.dexlib2.iface.reference.TypeReference;

public final class TypeUtils {
    public final static char PRIM_VOID = 'V';
    public final static char PRIM_BOOLEAN = 'Z';
    public final static char PRIM_BYTE = 'B';
    public final static char PRIM_CHAR = 'C';
    public final static char PRIM_SHORT = 'S';
    public final static char PRIM_INT = 'I';
    public final static char PRIM_FLOAT = 'F';
    public final static char PRIM_LONG = 'J';
    public final static char PRIM_DOUBLE = 'D';
    public final static char TYPE_ARRAY = '[';
    public final static char TYPE_OBJECT = 'L';

    public static boolean isWideType(@Nonnull String type) {
        char c = type.charAt(0);
        return c == PRIM_LONG || c == PRIM_DOUBLE;
    }

    public static boolean isWideType(@Nonnull TypeReference type) {
        return isWideType(type.getType());
    }

    public static boolean isPrimitiveType(String type) {
        return type.length() == 1;
    }

    public static String toFullString(String type) {
        switch (type.charAt(0)) {
            case PRIM_VOID:
                return "void";
            case PRIM_BOOLEAN:
                return "boolean";
            case TYPE_OBJECT:
                return type.substring(1, type.length() - 1).replace("/", ".");
            case PRIM_CHAR:
                return "char";
            case PRIM_BYTE:
                return "byte";
            case PRIM_SHORT:
                return "short";
            case PRIM_INT:
                return "int";
            case PRIM_FLOAT:
                return "float";
            case PRIM_LONG:
                return "long";
            case PRIM_DOUBLE:
                return "double";
            case TYPE_ARRAY:
                return toFullString(type.substring(1)) + "[]";
        }
        return "void";
    }

    private TypeUtils() {
    }
}
