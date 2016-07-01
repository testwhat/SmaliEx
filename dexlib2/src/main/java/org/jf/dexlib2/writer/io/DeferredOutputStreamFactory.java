package org.jf.dexlib2.writer.io;

import java.io.IOException;

@FunctionalInterface
public interface DeferredOutputStreamFactory {
    DeferredOutputStream makeDeferredOutputStream() throws IOException;
}
