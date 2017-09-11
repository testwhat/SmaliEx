package org.jf.dexlib2;

import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.writer.io.DexDataStore;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.ClassPool;
import org.jf.dexlib2.writer.pool.DexPool;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class MultiDex implements DexFile {
    private static final int MAX_METHOD_ADDED_DURING_DEX_CREATION = 2;
    private static final int MAX_FIELD_ADDED_DURING_DEX_CREATION = 9;
    private static final int DEFAULT_MAX_DEX_ID = 0xffff + 1;
    private int mMaxNumberOfIdxPerDex = DEFAULT_MAX_DEX_ID;

    private final Opcodes opcodes;
    public List<DexFile> files;
    public final Set<ClassDef> classes = new HashSet<>();

    public MultiDex(Opcodes opcodes) {
        this.opcodes = opcodes;
    }

    public <F extends DexFile> MultiDex(@Nonnull List<F> dexFiles) {
        Opcodes firstOpcodes = null;
        if (!dexFiles.isEmpty()) {
            DexFile dexFile = dexFiles.get(0);
            if (dexFile instanceof DexBackedDexFile) {
                DexBackedDexFile dexBackedDexFile = (DexBackedDexFile) dexFile;
                firstOpcodes = dexBackedDexFile.getOpcodes();
            }
        }
        opcodes = firstOpcodes == null ? Opcodes.getDefault() : firstOpcodes;
        dexFiles.forEach(this::addFile);
    }

    public void addFile(@Nonnull DexFile dexFile) {
        classes.addAll(dexFile.getClasses());
        if (files == null) {
            files = new ArrayList<>();
        }
        files.add(dexFile);
    }

    public void forEachDexBackedDexFile(@Nonnull Consumer<DexBackedDexFile> action) {
        if (files != null) {
            files.forEach(dexFile -> {
                if (dexFile instanceof DexBackedDexFile) {
                    action.accept((DexBackedDexFile) dexFile);
                }
            });
        }
    }

    public DexBackedDexFile getFirstDexBackedDexFile() {
        if (files != null && !files.isEmpty()) {
            DexFile df = files.get(0);
            if (df instanceof DexBackedDexFile) {
                return (DexBackedDexFile) df;
            }
        }
        return null;
    }

    public boolean hasOdexOpcodes() {
        DexBackedDexFile df = getFirstDexBackedDexFile();
        return df != null && df.hasOdexOpcodes();
    }

    public void setMaxNumberOfIdxPerDex(int maxIdx) {
        mMaxNumberOfIdxPerDex = maxIdx;
    }

    @Override
    @Nonnull
    public Set<ClassDef> getClasses() {
        return classes;
    }

    @Nonnull
    @Override
    public Opcodes getOpcodes() {
        return opcodes;
    }

    public List<MemoryDataStore> asMemory() {
        final List<MemoryDataStore> stores = new ArrayList<>(2);
        try {
            writeTo(dexNum -> {
                MemoryDataStore m = new MemoryDataStore();
                stores.add(m);
                return m;
            });
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        return stores;
    }

    public void writeTo(@Nonnull String outFolder) {
        writeTo(new File(outFolder));
    }

    public void writeTo(@Nonnull final File outFolder) {
        try {
            if (!outFolder.mkdirs()) {
                System.err.println("No output folder " + outFolder);
                return;
            }
            writeTo(dexNum -> new FileDataStore(new File(outFolder, getDexFileName(dexNum))));
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public void writeTo(DexDataStoreFactory store) throws IOException {
        writeClassesTo(new ArrayList<>(classes), store);
    }

    public <C extends ClassDef> void writeClassesTo(
            List<C> classList, DexDataStoreFactory store) throws IOException {
        int dexNum = 0;
        DexPool dexPool = new DexPool(opcodes);
        ClassPool clsPool = dexPool.classSection;
        classList.sort(Comparator.comparing(ClassDef::getType));

        for (ClassDef classDef : classList) {
            int numMethodIds = dexPool.methodSection.getItemCount();
            int numFieldIds = dexPool.fieldSection.getItemCount();
            int constantPoolSize = classDef.getDirectMethodCount()
                    + classDef.getVirtualMethodCount()
                    + classDef.getStaticFieldCount()
                    + classDef.getInstanceFieldCount();

            int maxMethodIdsInDex = numMethodIds + constantPoolSize
                    + MAX_METHOD_ADDED_DURING_DEX_CREATION;
            int maxFieldIdsInDex = numFieldIds + constantPoolSize
                    + MAX_FIELD_ADDED_DURING_DEX_CREATION;

            if (maxMethodIdsInDex > mMaxNumberOfIdxPerDex
                    || maxFieldIdsInDex > mMaxNumberOfIdxPerDex) {
                dexPool.writeTo(store.getDataStore(dexNum));
                dexNum++;
                dexPool = new DexPool(opcodes);
                clsPool = dexPool.classSection;
            }
            clsPool.intern(classDef);
        }
        dexPool.writeTo(store.getDataStore(dexNum));
    }

    public static String getDexFileName(int i) {
        return getDexFileName("classes.dex", i);
    }

    public static String getDexFileName(String outputName, int i) {
        if (i == 0) {
            return outputName;
        }
        i++;
        int dotPos = outputName.lastIndexOf(".");
        if (dotPos > 0) {
            return outputName.substring(0, dotPos) + i
                    + outputName.substring(dotPos, outputName.length());
        }
        return outputName + i;
    }

    public interface DexDataStoreFactory {
        DexDataStore getDataStore(int dexNum) throws IOException;
    }
}
