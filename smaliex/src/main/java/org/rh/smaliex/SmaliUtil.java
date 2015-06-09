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

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

import javax.annotation.Nullable;

import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.TokenSource;
import org.antlr.runtime.tree.CommonTree;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.jf.baksmali.baksmaliOptions;
import org.jf.baksmali.Adaptors.ClassDefinition;
import org.jf.dexlib2.analysis.ClassPath;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.writer.builder.DexBuilder;
import org.jf.smali.LexerErrorInterface;
import org.jf.smali.smaliFlexLexer;
import org.jf.smali.smaliParser;
import org.jf.smali.smaliTreeWalker;
import org.jf.util.IndentingWriter;

public class SmaliUtil {

    public static String getSmaliContent(ClassDef classDef, ClassPath classPath) {
        baksmaliOptions options = new baksmaliOptions();
        if (classPath != null) {
            options.apiLevel = classPath.apiLevel;
            options.allowOdex = true;
            options.deodex = true;
            options.classPath = classPath;
        }
        options.checkPackagePrivateAccess = false;
        options.noAccessorComments = true;
        return getSmaliContent(classDef, options);
    }

    public static String getSmaliContent(ClassDef classDef, baksmaliOptions options) {
        ClassDefinition cd = new ClassDefinition(options, classDef);
        StringWriter sw = new StringWriter(4096);
        try {
            IndentingWriter writer = new IndentingWriter(sw);
            cd.writeTo(writer);
        } catch (IOException ex) {
            LLog.ex(ex);
        }
        return sw.toString();
    }

    @Nullable
    public static ClassDef assembleSmali(String smaliContent) {
        try {
            final DexBuilder dexBuilder = DexBuilder.makeDexBuilder();
            LexerErrorInterface lexer = new smaliFlexLexer(new StringReader(smaliContent));
            CommonTokenStream tokens = new CommonTokenStream((TokenSource) lexer);
            smaliParser parser = new smaliParser(tokens);
            smaliParser.smali_file_return result = parser.smali_file();

            if (parser.getNumberOfSyntaxErrors() > 0 || lexer.getNumberOfSyntaxErrors() > 0) {
                return null;
            }

            CommonTree t = result.getTree();
            CommonTreeNodeStream treeStream = new CommonTreeNodeStream(t);
            treeStream.setTokenStream(tokens);

            smaliTreeWalker dexGen = new smaliTreeWalker(treeStream);
            dexGen.setDexBuilder(dexBuilder);
            return dexGen.smali_file();
        } catch (RecognitionException ex) {
            LLog.ex(ex);
        }
        return null;
    }
}
