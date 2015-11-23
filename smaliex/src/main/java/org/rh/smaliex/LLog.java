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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class LLog {

    private final static SimpleDateFormat sdf = new SimpleDateFormat("MM-dd kk:mm:ss:SSS");
    private final static Date date = new Date();
    public static boolean VERBOSE = false;

    private synchronized static String time() {
        date.setTime(System.currentTimeMillis());
        return sdf.format(date);
    }

    static interface P {
        void print(CharSequence str);
        void println(CharSequence str);
    }
    static final P sStdOut = new P() {
        @Override
        public void print(CharSequence str) {
            System.out.println(str);
        }

        @Override
        public void println(CharSequence str) {
            System.out.println(time() + " " + str);
        }
    };

    static P sOut = sStdOut;

    public static void e(String msg) {
        sOut.println(msg);
    }

    public static void ex(Throwable e) {
        String s = exception(e);
        sOut.println(s);
    }

    public static void v(String msg) {
        if (VERBOSE) {
            sOut.println(msg);
        }
    }

    public static void i(String msg) {
        sOut.println(msg);
    }

    public static String exception(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }
}
