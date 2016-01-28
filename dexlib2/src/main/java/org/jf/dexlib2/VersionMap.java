/*
 * Copyright 2015, Google Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 * Neither the name of Google Inc. nor the names of its
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

package org.jf.dexlib2;

public class VersionMap {
    public static final int NO_VERSION = -1;
    public static final int KITKAT = 19;
    public static final int LOLLIPOP = 21; // Android 5.0
    public static final int LOLLIPOP_MR1 = 22; // Android 5.1
    public static final int M = 23; // Android 6.0

    public static final int DEFAULT = KITKAT;

    public static int mapArtVersionToApi(int artVersion) {
        if (artVersion >= 64) { // { '0', '6', '4', '\0' };
            return M;
        }
        if (artVersion >= 45) { // { '0', '4', '5', '\0' };
            return LOLLIPOP_MR1;
        }
        if (artVersion >= 39) { // { '0', '3', '9', '\0' };
            return LOLLIPOP;
        }
        return 20;
    }

    public static int mapApiToArtVersion(int api) {
        // TODO: implement this
        if (api < 20) {
            return NO_VERSION;
        } else {
            return 56;
        }
    }
}
