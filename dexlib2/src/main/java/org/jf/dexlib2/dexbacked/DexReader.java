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

package org.jf.dexlib2.dexbacked;

import javax.annotation.Nonnull;

public class DexReader extends BaseDexReader<DexBackedDexFile> {
    public DexReader(@Nonnull DexBackedDexFile dexFile, int offset) {
        super(dexFile, offset);
    }

    @Override
    public int readSmallUleb128() {
        if (!dexBuf.isCompact) {
            return super.readSmallUleb128();
        }
        // See art/libartbase/base/leb128.h
        final byte[] buf = dexBuf.buf;
        int end = dexBuf.baseOffset + getOffset();
        int result = buf[end++] & 0xff;
        if (result > 0x7f) {
            int cur = buf[end++] & 0xff;
            result = (result & 0x7f) | ((cur & 0x7f) << 7);
            if (cur > 0x7f) {
                cur = buf[end++] & 0xff;
                result |= (cur & 0x7f) << 14;
                if (cur > 0x7f) {
                    cur = buf[end++] & 0xff;
                    result |= (cur & 0x7f) << 21;
                    if (cur > 0x7f) {
                        cur = buf[end++];
                        // Note: We don't check to see if cur is out of range here,
                        // meaning we tolerate garbage in the four high-order bits.
                        result |= cur << 28;
                    }
                }
            }
        }
        setOffset(end - dexBuf.baseOffset);
        return result;
    }
}
