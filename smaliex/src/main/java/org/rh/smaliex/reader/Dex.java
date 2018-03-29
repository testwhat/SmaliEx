package org.rh.smaliex.reader;

import static org.rh.smaliex.MiscUtil.DumpFormat;

import org.rh.smaliex.LLog;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

// See /art/runtime/dex_file.h
public class Dex {

    public static class Header {
        static final String MAGIC_DEX = "dex";
        static final String MAGIC_COMPACT_DEX = "cdex";

        @DumpFormat(type = DumpFormat.TYPE_CHAR, isString = true)
        final char[] magic_ = new char[4];
        @DumpFormat(type = DumpFormat.TYPE_CHAR, isString = true)
        final char[] version_ = new char[4];
        @DumpFormat(hex = true)
        final int checksum_;
        @DumpFormat(hex = true)
        final byte[] signature_ = new byte[20];
        public final int file_size_;
        public final int header_size_;
        final int endian_tag_;
        final int link_size_;
        final int link_off_;
        final int map_off_;
        final int string_ids_size_;
        final int string_ids_off_;
        final int type_ids_size_;
        final int type_ids_off_;
        final int proto_ids_size_;
        final int proto_ids_off_;
        final int field_ids_size_;
        final int field_ids_off_;
        final int method_ids_size_;
        final int method_ids_off_;
        final int class_defs_size_;
        final int class_defs_off_;
        final int data_size_;
        public final int data_off_;

        final String magic;
        final String version;
        final boolean isCompactDex;

        public Header(DataReader r) {
            r.readBytes(magic_);
            magic = new String(magic_).trim();
            isCompactDex = magic.equals(MAGIC_COMPACT_DEX);
            if (!magic.equals(MAGIC_DEX) && !isCompactDex) {
                LLog.e(String.format("Invalid dex magic '%s'", magic));
            }
            r.readBytes(version_);
            version = new String(version_).trim();
            if (!isCompactDex && version.compareTo("035") < 0) {
                LLog.e(String.format("Invalid dex version '%s'", version));
            }
            checksum_= r.readInt();
            r.readBytes(signature_);
            file_size_ = r.readInt();
            header_size_ = r.readInt();
            endian_tag_ = r.readInt();
            link_size_ = r.readInt();
            link_off_ = r.readInt();
            map_off_ = r.readInt();
            string_ids_size_ = r.readInt();
            string_ids_off_ = r.readInt();
            type_ids_size_ = r.readInt();
            type_ids_off_ = r.readInt();
            proto_ids_size_ = r.readInt();
            proto_ids_off_ = r.readInt();
            field_ids_size_ = r.readInt();
            field_ids_off_ = r.readInt();
            method_ids_size_ = r.readInt();
            method_ids_off_ = r.readInt();
            class_defs_size_ = r.readInt();
            class_defs_off_ = r.readInt();
            data_size_ = r.readInt();
            data_off_ = r.readInt();
        }
    }

    private final DataReader mReader;
    public final int dexPosition;
    public final int dataEnd;
    public final Header header;

    public Dex(DataReader r) {
        dexPosition = r.position();
        mReader = r;
        header = new Header(r);
        dataEnd = header.isCompactDex ? header.data_off_ + header.data_size_ : header.file_size_;
    }

    @Nonnull
    public byte[] getBytes() {
        final byte[] dexBytes = new byte[dataEnd];
        mReader.position(dexPosition);
        mReader.readBytes(dexBytes);
        return dexBytes;
    }

    public void saveTo(@Nonnull File outputFile) throws IOException {
        try (FileOutputStream output = new FileOutputStream(outputFile)) {
            mReader.getChannel().transferTo(dexPosition, dataEnd, output.getChannel());
        }
    }
}
