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

    public static int mapDexVersionToApi(int dexVersion) {
        switch (dexVersion) {
            case 35:
                return 23;
            case 37:
                return 25;
            case 38:
                return 27;
            case 39:
                return 28;
            case 40:
                return 34;
            case 41:
                return 35;
            default:
                return NO_VERSION;
        }
    }

    public static int mapApiToDexVersion(int api) {
        if (api <= 23) {  // Android M/6
            return 35;
        }
        switch (api) {
            case 24:  // Android N/7
            case 25:  // Android N/7.1
                return 37;
            case 26:  // Android O/8
            case 27:  // Android O/8.1
                return 38;
            case 28:  // Android P/9
                return 39;
            case 29:  // Android Q/10
            case 30:  // Android R/11
            case 31:  // Android S/12
            case 32:  // Android S/12.1
            case 33:  // Android T/13
            case 34:  // Android U/14
                return 40;
            case 35:  // Android V/15
                return 41;
        }
        return NO_VERSION;
    }

    public static int mapArtVersionToApi(int artVersion) {
        if (artVersion >= 170) {
            return 29;
        }
        if (artVersion >= 138) {
            return 28;
        }
        if (artVersion >= 131) {
            return 27;
        }
        if (artVersion >= 124) {
            return 26;
        }
        if (artVersion >= 79) {
            return 24;
        }
        if (artVersion >= 64) {
            return 23;
        }
        if (artVersion >= 45) {
            return 22;
        }
        if (artVersion >= 39) {
            return 21;
        }
        return 19;
    }

    public static int mapApiToArtVersion(int api) {
        switch (api) {
            case 19:
            case 20:
                return 7;
            case 21:
                return 39;
            case 22:
                return 45;
            case 23:
                return 64;
            case 24:
            case 25:
                return 79;
            case 26:
                return 124;
            case 27:
                return 131;
            case 28:
                // 144 is the current version in the master branch of AOSP as of 2018-05-22
                return 144;
        }

        // NOTE: Art version 143 and api level 27 do not correspond to any
        // particular android release and represent the current (as of
        // May 2018) state of aosp/master.
        if (api > 26) {
            return 143;
        }
        return NO_VERSION;
    }
}
