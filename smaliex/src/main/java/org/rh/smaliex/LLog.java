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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class LLog {

    private static final SimpleDateFormat sdf = new SimpleDateFormat("MM-dd kk:mm:ss:SSS");
    private static final Date date = new Date();
    public static boolean VERBOSE = false;

    private synchronized static String time() {
        date.setTime(System.currentTimeMillis());
        return sdf.format(date);
    }

    interface P {
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
