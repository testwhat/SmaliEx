/*
 * Copyright 2013, Google Inc.
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

package org.jf.dexlib2.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.analysis.util.TypeProtoUtils;
import org.jf.dexlib2.dexbacked.DexBackedField;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.util.MethodUtil;
import org.jf.dexlib2.util.TypeUtils;
import org.jf.util.ExceptionWithContext;
import org.jf.util.SparseArray;

import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * A class "prototype". This contains things like the interfaces, the superclass, the vtable and the instance fields
 * and their offsets.
 */
public class ClassProto implements TypeProto {
    @Nonnull protected final ClassPath classPath;
    @Nonnull protected final String type;
    @Nonnull private final FieldOffsetCalculator fieldOffsetCalculator;
    @Nonnull private final Supplier<SparseArray<FieldReference>> instanceFieldsSupplier;
    @Nonnull private final Supplier<List<Method>> vtableSupplier;

    protected boolean vtableFullyResolved = true;
    protected boolean interfacesFullyResolved = true;
    protected int objectSize;

    public ClassProto(@Nonnull ClassPath classPath, @Nonnull String type) {
        if (type.charAt(0) != TypeUtils.TYPE_OBJECT) {
            throw new ExceptionWithContext("Cannot construct ClassProto for non reference type: %s", type);
        }
        this.classPath = classPath;
        this.type = type;
        fieldOffsetCalculator = classPath.version.api < Opcode.API_L
                ? new DalvikIFieldSupplier(this) : new ArtIFieldSupplier(this);
        vtableSupplier = classPath.version.api < Opcode.API_N
                ? new VirtualTableSupplierLegacy(this) : new VirtualTableSupplierLatest(this);
        instanceFieldsSupplier = Suppliers.memoize(fieldOffsetCalculator);
    }

    @Override public String toString() { return type; }
    @Nonnull @Override public ClassPath getClassPath() { return classPath; }
    @Nonnull @Override public String getType() { return type; }

    @Nonnull
    public ClassDef getClassDef() {
        return classDefSupplier.get();
    }

    @Nonnull private final Supplier<ClassDef> classDefSupplier = Suppliers.memoize(new Supplier<ClassDef>() {
        @Override public ClassDef get() {
            return classPath.getClassDef(type);
        }
    });

    /**
     * Returns true if this class is an interface.
     *
     * If this class is not defined, then this will throw an UnresolvedClassException
     *
     * @return True if this class is an interface
     */
    @Override
    public boolean isInterface() {
        ClassDef classDef = getClassDef();
        return (classDef.getAccessFlags() & AccessFlags.INTERFACE.getValue()) != 0;
    }

    /**
     * Returns the set of interfaces that this class implements as a Map<String, ClassDef>.
     *
     * The ClassDef value will be present only for the interfaces that this class directly implements (including any
     * interfaces transitively implemented), but not for any interfaces that are only implemented by a superclass of
     * this class
     *
     * For any interfaces that are only implemented by a superclass (or the class itself, if the class is an interface),
     * the value will be null.
     *
     * If any interface couldn't be resolved, then the interfacesFullyResolved field will be set to false upon return.
     *
     * @return the set of interfaces that this class implements as a Map<String, ClassDef>.
     */
    @Nonnull
    protected LinkedHashMap<String, ClassDef> getInterfaces() {
        return interfacesSupplier.get();
    }

    @Nonnull
    private final Supplier<LinkedHashMap<String, ClassDef>> interfacesSupplier =
            Suppliers.memoize(new Supplier<LinkedHashMap<String, ClassDef>>() {
                @Override public LinkedHashMap<String, ClassDef> get() {
                    LinkedHashMap<String, ClassDef> interfaces = Maps.newLinkedHashMap();

                    try {
                        for (String interfaceType: getClassDef().getInterfaces()) {
                            if (!interfaces.containsKey(interfaceType)) {
                                ClassDef interfaceDef;
                                try {
                                    interfaceDef = classPath.getClassDef(interfaceType);
                                    interfaces.put(interfaceType, interfaceDef);
                                } catch (UnresolvedClassException ex) {
                                    interfaces.put(interfaceType, null);
                                    interfacesFullyResolved = false;
                                }

                                ClassProto interfaceProto = (ClassProto) classPath.getClass(interfaceType);
                                for (String superInterface : interfaceProto.getInterfaces().keySet()) {
                                    if (!interfaces.containsKey(superInterface)) {
                                        interfaces.put(superInterface,
                                                interfaceProto.getInterfaces().get(superInterface));
                                    }
                                }
                                if (!interfaceProto.interfacesFullyResolved) {
                                    interfacesFullyResolved = false;
                                }
                            }
                        }
                    } catch (UnresolvedClassException ex) {
                        interfacesFullyResolved = false;
                    }

                    // now add self and super class interfaces, required for common super class lookup
                    // we don't really need ClassDef's for that, so let's just use null

                    if (isInterface() && !interfaces.containsKey(getType())) {
                        interfaces.put(getType(), null);
                    }

                    try {
                        String superclass = getSuperclass();
                        if (superclass != null) {
                            ClassProto superclassProto = (ClassProto) classPath.getClass(superclass);
                            for (String superclassInterface: superclassProto.getInterfaces().keySet()) {
                                if (!interfaces.containsKey(superclassInterface)) {
                                    interfaces.put(superclassInterface, null);
                                }
                            }
                            if (!superclassProto.interfacesFullyResolved) {
                                interfacesFullyResolved = false;
                            }
                        }
                    } catch (UnresolvedClassException ex) {
                        interfacesFullyResolved = false;
                    }

                    return interfaces;
                }
            });

    /**
     * Gets the interfaces directly implemented by this class, or the interfaces they transitively implement.
     *
     * This does not include any interfaces that are only implemented by a superclass
     *
     * @return An iterables of ClassDefs representing the directly or transitively implemented interfaces
     * @throws UnresolvedClassException if interfaces could not be fully resolved
     */
    @Nonnull
    protected Iterable<ClassDef> getDirectInterfaces() {
        Iterable<ClassDef> directInterfaces =
                FluentIterable.from(getInterfaces().values()).filter(Predicates.notNull());

        if (!interfacesFullyResolved) {
            throw new UnresolvedClassException("Interfaces for class %s not fully resolved", getType());
        }

        return directInterfaces;
    }

    /**
     * Checks if this class implements the given interface.
     *
     * If the interfaces of this class cannot be fully resolved then this
     * method will either return true or throw an UnresolvedClassException
     *
     * @param iface The interface to check for
     * @return true if this class implements the given interface, otherwise false
     * @throws UnresolvedClassException if the interfaces for this class could not be fully resolved, and the interface
     * is not one of the interfaces that were successfully resolved
     */
    @Override
    public boolean implementsInterface(@Nonnull String iface) {
        if (getInterfaces().containsKey(iface)) {
            return true;
        }
        if (!interfacesFullyResolved) {
            throw new UnresolvedClassException("Interfaces for class %s not fully resolved", getType());
        }
        return false;
    }

    @Nullable @Override
    public String getSuperclass() {
        return getClassDef().getSuperclass();
    }

    /**
     * This is a helper method for getCommonSuperclass
     *
     * It checks if this class is an interface, and if so, if other implements it.
     *
     * If this class is undefined, we go ahead and check if it is listed in other's interfaces. If not, we throw an
     * UndefinedClassException
     *
     * If the interfaces of other cannot be fully resolved, we check the interfaces that can be resolved. If not found,
     * we throw an UndefinedClassException
     *
     * @param other The class to check the interfaces of
     * @return true if this class is an interface (or is undefined) other implements this class
     *
     */
    private boolean checkInterface(@Nonnull ClassProto other) {
        boolean isResolved = true;
        boolean isInterface = true;
        try {
            isInterface = isInterface();
        } catch (UnresolvedClassException ex) {
            isResolved = false;
            // if we don't know if this class is an interface or not,
            // we can still try to call other.implementsInterface(this)
        }
        if (isInterface) {
            try {
                if (other.implementsInterface(getType())) {
                    return true;
                }
            } catch (UnresolvedClassException ex) {
                // There are 2 possibilities here, depending on whether we were able to resolve this class.
                // 1. If this class is resolved, then we know it is an interface class. The other class either
                //    isn't defined, or its interfaces couldn't be fully resolved.
                //    In this case, we throw an UnresolvedClassException
                // 2. If this class is not resolved, we had tried to call implementsInterface anyway. We don't
                //    know for sure if this class is an interface or not. We return false, and let processing
                //    continue in getCommonSuperclass
                if (isResolved) {
                    throw ex;
                }
            }
        }
        return false;
    }

    @Override @Nonnull
    public TypeProto getCommonSuperclass(@Nonnull TypeProto other) {
        // use the other type's more specific implementation
        if (!(other instanceof ClassProto)) {
            return other.getCommonSuperclass(this);
        }

        if (this == other || getType().equals(other.getType())) {
            return this;
        }

        if (this.getType().equals("Ljava/lang/Object;")) {
            return this;
        }

        if (other.getType().equals("Ljava/lang/Object;")) {
            return other;
        }

        boolean gotException = false;
        try {
            if (checkInterface((ClassProto)other)) {
                return this;
            }
        } catch (UnresolvedClassException ex) {
            gotException = true;
        }

        try {
            if (((ClassProto)other).checkInterface(this)) {
                return other;
            }
        } catch (UnresolvedClassException ex) {
            gotException = true;
        }
        if (gotException) {
            return classPath.getUnknownClass();
        }

        List<TypeProto> thisChain = Lists.<TypeProto>newArrayList(this);
        Iterables.addAll(thisChain, TypeProtoUtils.getSuperclassChain(this));

        List<TypeProto> otherChain = Lists.newArrayList(other);
        Iterables.addAll(otherChain, TypeProtoUtils.getSuperclassChain(other));

        // reverse them, so that the first entry is either Ljava/lang/Object; or Ujava/lang/Object;
        thisChain = Lists.reverse(thisChain);
        otherChain = Lists.reverse(otherChain);

        for (int i=Math.min(thisChain.size(), otherChain.size())-1; i>=0; i--) {
            TypeProto typeProto = thisChain.get(i);
            if (typeProto.getType().equals(otherChain.get(i).getType())) {
                return typeProto;
            }
        }

        return classPath.getUnknownClass();
    }

    @Override
    @Nullable
    public FieldReference getFieldByOffset(int fieldOffset) {
        if (getInstanceFields().size() == 0) {
            return null;
        }
        return getInstanceFields().get(fieldOffset);
    }

    @Override
    @Nullable
    public MethodReference getMethodByVtableIndex(int vtableIndex) {
        List<Method> vtable = getVtable();
        if (vtableIndex < 0 || vtableIndex >= vtable.size()) {
            return null;
        }

        return vtable.get(vtableIndex);
    }

    @Nonnull SparseArray<FieldReference> getInstanceFields() {
        return instanceFieldsSupplier.get();
    }

    public static void dump(TypeProto type) {
        if (type instanceof ClassProto) {
            ((ClassProto) type).dump();
        }
    }

    public void dump() {
        fieldOffsetCalculator.debugOffset(null, getInstanceFields());
        debugVtable(null);
    }

    void debugVtable(String className) {
        final String type = getClassDef().getType();
        if (className != null && !type.equals(className)) {
            return;
        }
        System.out.println("## Vtable of " + type);
        List<Method> vtable = getVtable();
        for (int i = 0; i < vtable.size(); i++) {
            Method m = vtable.get(i);
            System.out.println("#" + i + " " + m.getDefiningClass()
                    + " : " + MethodUtil.toSourceStyleString(m));
        }
    }

    private final static byte REFERENCE = 0;
    private final static byte WIDE = 1;
    private final static byte OTHER = 2;

    private static byte getFieldWidthType(@Nonnull FieldReference field) {
        return getFieldWidthType(field.getType().charAt(0));
    }

    private static byte getFieldWidthType(char typeChar) {
        switch (typeChar) {
        case TypeUtils.TYPE_ARRAY:
        case TypeUtils.TYPE_OBJECT:
            return REFERENCE;
        case TypeUtils.PRIM_LONG:
        case TypeUtils.PRIM_DOUBLE:
            return WIDE;
        default:
            return OTHER;
        }
    }

    abstract static class FieldOffsetCalculator implements Supplier<SparseArray<FieldReference>> {
        final ClassProto classProto;
        int initialFieldOffset;

        FieldOffsetCalculator(ClassProto cProto) {
            classProto = cProto;
        }

        void debugOffset(String className, SparseArray<FieldReference> fields) {
            if (className != null && !classProto.type.equals(className)) {
                return;
            }
            System.out.println("## Field offset of " + classProto.type);
            ArrayList<ClassProto> classHierarchy = new ArrayList<>();
            ClassProto cp = classProto;
            for (String superclassType = cp.getSuperclass(); superclassType != null;
                    superclassType = cp.getSuperclass()) {
                cp = (ClassProto) classProto.classPath.getClass(superclassType);
                classHierarchy.add(0, cp);
            }

            for (int i = 0; i < fields.size(); i++) {
                int offset = fields.keyAt(i);
                FieldReference f = fields.valueAt(i);
                if (!classHierarchy.isEmpty()) {
                    cp = classHierarchy.get(0);
                    int countInstanceField = cp.getInstanceFields().size();
                    if (countInstanceField <= i) {
                        System.out.println(" --- " + cp.type + " " + countInstanceField);
                        classHierarchy.remove(0);
                    }
                }
                System.out.println(" #" + i + "# " + offset + " (0x"
                        + Integer.toHexString(offset) + ") "
                        + ":" + f.getType() + " " + f.getName());
            }
        }

        void debugFieldsOrder(String className, List<Field> fields) {
            if (className != null && className.equals(classProto.type)) {
                System.out.println(" ===== " + classProto.type + " " + fields.size() + " =====");
                for (int i = 0; i < fields.size(); i++) {
                    Field f = fields.get(i);
                    System.out.println(" #" + i + "# " + f.getType() + " " + f.getName());
                }
            }
        }
    }

    private static class ArtIFieldSupplier extends FieldOffsetCalculator {
        final Comparator<Field> fieldComparator; // art/runtime/class_linker.cc LinkFieldsComparator

        ArtIFieldSupplier(ClassProto clsProto) {
            super(clsProto);
            // art/runtime/class_linker.cc LinkFields
            // art/compiler/dex/dex_to_dex_compiler.cc
            initialFieldOffset = 0; // MemberOffset field_offset(0);
            fieldComparator = createFieldComparator(classProto.classPath.version.oat);
        }

        SparseArray<FieldReference> getForApiLevelBelow23(ArrayList<Field> fields,
                int fieldOffset, SparseArray<FieldReference> instanceFields) {
            // https://android.googlesource.com/platform/art/+/android-5.1.1_r37/runtime/class_linker.cc#5425
            final int numFields = fields.size();
            int currentField = 0;
            for (; currentField < numFields; currentField++) {
                Field field = fields.get(0);
                int type = getTypeAsPrimitiveType(field);
                boolean isPrimitive = type != kPrimNot;
                if (isPrimitive) {
                    break;
                }
                fields.remove(0);
                instanceFields.append(fieldOffset, field);
                fieldOffset += sizeof_uint32_t;
            }

            if (currentField != numFields && !isAligned(8, fieldOffset)) {
                for (int i = 0; i < fields.size(); i++) {
                    Field field = fields.get(i);
                    int type = getTypeAsPrimitiveType(field);
                    if (type == kPrimLong || type == kPrimDouble) {
                        continue;
                    }
                    instanceFields.append(fieldOffset, field);
                    fields.remove(i);
                    break;
                }
                fieldOffset += sizeof_uint32_t;
            }

            while (!fields.isEmpty()) {
                Field field = fields.remove(0);
                int type = getTypeAsPrimitiveType(field);
                instanceFields.append(fieldOffset, field);
                fieldOffset += (type == kPrimLong || type == kPrimDouble) ?
                        sizeof_uint64_t : sizeof_uint32_t;
            }
            classProto.objectSize = fieldOffset; // not for IsVariableSize (String and Array)

            return instanceFields;
        }

        final static int sizeof_mirror_HeapReference_mirror_Object = 4;

        @Override
        public SparseArray<FieldReference> get() {
            // See art/runtime/class_linker.cc ClassLinker::LinkFields
            final ArrayList<Field> fields = Lists.newArrayList(
                    classProto.getClassDef().getInstanceFields());
            Collections.sort(fields, fieldComparator);

            final int fieldCount = fields.size();
            int fieldOffset = initialFieldOffset;
            int superFieldCount = 0;
            String superclassType = classProto.getSuperclass();
            SparseArray<FieldReference> superFields = null;
            if (superclassType != null) {
                ClassProto superclass = (ClassProto) classProto.classPath.getClass(superclassType);
                superFields = superclass.getInstanceFields();
                fieldOffset = superclass.objectSize;
                superFieldCount = superFields.size();
                //if (superFieldCount == 0 && fieldOffset == 0) {
                //    // There is something missing.
                //    fieldOffset = 8;
                //}
            }
            final int totalFieldCount = superFieldCount + fieldCount;
            final SparseArray<FieldReference> instanceFields = new SparseArray<>(totalFieldCount);
            if (superFields != null && superFieldCount > 0) {
                for (int i = 0; i < superFieldCount; i++) {
                    instanceFields.append(superFields.keyAt(i), superFields.valueAt(i));
                }
            }
            //debugFieldsOrder("name", fields);
            if (classProto.classPath.version.api <= Opcode.API_L_MR1) {
                return getForApiLevelBelow23(fields, fieldOffset, instanceFields);
            }

            // References should be at the front.
            GapContainer gaps = new GapContainer(
                    createFieldGapCreator(classProto.classPath.version.oat));
            int currentField = 0;
            for (; currentField < fieldCount; currentField++) {
                Field f = fields.get(0);
                int type = getTypeAsPrimitiveType(f);
                boolean isPrimitive = type != kPrimNot;
                if (isPrimitive) {
                    break;
                }
                if (!isAligned(sizeof_mirror_HeapReference_mirror_Object, fieldOffset)) {
                    int oldFieldOffset = fieldOffset;
                    fieldOffset = roundUp(fieldOffset, 4);
                    FieldGap.add(oldFieldOffset, fieldOffset, gaps);
                }
                assert isAligned(sizeof_mirror_HeapReference_mirror_Object, fieldOffset);
                fields.remove(0);
                instanceFields.append(fieldOffset, f);
                fieldOffset += sizeof_mirror_HeapReference_mirror_Object;
            }
            FieldOffsetData data = new FieldOffsetData();
            data.currentField = currentField;
            data.fieldOffset = fieldOffset;
            data.fields = fields;
            data.gaps = gaps;
            data.instanceFields = instanceFields;
            shuffleForward(8, data);
            shuffleForward(4, data);
            shuffleForward(2, data);
            shuffleForward(1, data);
            //debugOffset("", instanceFields);
            //classProto.debugVtable("");
            assert fields.isEmpty();
            // See runtime/mirror/class.h
            classProto.objectSize = data.fieldOffset; // not for IsVariableSize (String and Array)
            return instanceFields;
        }

        static void shuffleForward(int n, FieldOffsetData data) {
            while (!data.fields.isEmpty()) {
                Field f = data.fields.get(0);
                int type = getTypeAsPrimitiveType(f);
                if (getComponentSize(type) < n) {
                    break;
                }
                if (!isAligned(n, data.fieldOffset)) {
                    int oldFieldOffset = data.fieldOffset;
                    data.fieldOffset = roundUp(data.fieldOffset, n);
                    FieldGap.add(oldFieldOffset, data.fieldOffset, data.gaps);
                }
                data.fields.remove(0);
                if (!data.gaps.isEmpty() && data.gaps.first().size >= n) {
                    FieldGap gap = data.gaps.pollFirst();
                    data.instanceFields.append(gap.startOffset, f);
                    assert isAligned(n, gap.startOffset);
                    if (gap.size > n) {
                        FieldGap.add(gap.startOffset + n, gap.startOffset + gap.size, data.gaps);
                    }
                } else {
                    assert isAligned(n, data.fieldOffset);
                    data.instanceFields.append(data.fieldOffset, f);
                    data.fieldOffset += n;
                }
                data.currentField++;
            }
        }

        static class GapContainer extends TreeSet<FieldGap> {
            final IFieldGapCreator creator;

            GapContainer(IFieldGapCreator c) {
                creator = c;
            }

            void add(int offset, int size) {
                add(creator.create(offset, size));
            }
        }

        static class FieldOffsetData {
            int currentField;
            int fieldOffset;
            ArrayList<Field> fields;
            GapContainer gaps;
            SparseArray<FieldReference> instanceFields;
        }

        final static int sizeof_uint64_t = 8;
        final static int sizeof_uint32_t = 4;
        final static int sizeof_uint16_t = 2;
        final static int sizeof_uint8_t = 1;

        interface IFieldGapCreator {
            FieldGap create(int offset, int size);
        }

        static IFieldGapCreator createFieldGapCreator(int oatVersion) {
            return oatVersion >= 67 ? new FieldGapCreator() : new FieldGapCreatorM();
        }

        static class FieldGapCreator implements IFieldGapCreator {

            @Override
            public FieldGap create(int offset, int size) {
                return new FieldGap(offset, size);
            }
        }

        static class FieldGapCreatorM implements IFieldGapCreator {

            @Override
            public FieldGap create(int offset, int size) {
                return new FieldGapM(offset, size);
            }
        }

        static class FieldGap implements Comparable<FieldGap> {
            final int startOffset;
            final int size;

            FieldGap(int o, int s) {
                startOffset = o;
                size = s;
            }

            @Override
            public int compareTo(@Nonnull FieldGap rhs) {
                // kOatVersion 067+
                // Fix FieldGap priority queue ordering bug
                // https://android.googlesource.com/platform/art/+/fab67883
                // Note that the priority queue returns the largest element, so operator()
                // should return true if lhs is less than rhs.
                // return lhs.size < rhs.size || (lhs.size == rhs.size && lhs.start_offset > rhs.start_offset);
                int c = rhs.size - size;
                if (c != 0) {
                    return c;
                } else {
                    return startOffset - rhs.startOffset;
                }
            }

            static void add(int gapStart, int gapEnd, GapContainer gaps) {
                int currentOffset = gapStart;
                while (currentOffset != gapEnd) {
                    int remaining = gapEnd - currentOffset;
                    if (remaining >= sizeof_uint32_t && isAligned(4, currentOffset)) {
                        gaps.add(currentOffset, sizeof_uint32_t);
                        currentOffset += sizeof_uint32_t;
                    } else if (remaining >= sizeof_uint16_t && isAligned(2, currentOffset)) {
                        gaps.add(currentOffset, sizeof_uint16_t);
                        currentOffset += sizeof_uint16_t;
                    } else {
                        gaps.add(currentOffset, sizeof_uint8_t);
                        currentOffset += sizeof_uint8_t;
                    }
                }
            }
        }

        static class FieldGapM extends FieldGap {
            // https://android.googlesource.com/platform/art/+/381e4ca3
            FieldGapM(int o, int s) {
                super(o, s);
            }

            @Override
            public int compareTo(@Nonnull FieldGap rhs) {
                // kOatVersion 55~66
                // Ensure order of field gaps
                // https://android.googlesource.com/platform/art/+/fab67883
                // Sort by gap size, largest first. Secondary sort by starting offset.
                // lhs.size > rhs.size || (lhs.size == rhs.size && lhs.start_offset < rhs.start_offset)
                int c = size - rhs.size;
                if (c != 0) {
                    return c;
                } else {
                    return rhs.startOffset - startOffset;
                }
            }
        }

        static Comparator<Field> createFieldComparator(int oatVersion) {
            return oatVersion <= 39 ? new FieldComparatorL50() : new FieldComparator();
        }

        final static class FieldComparatorL50 implements Comparator<Field> {
            // https://android.googlesource.com/platform/art/+/android-5.0.2_r3/runtime/class_linker.cc#4933
            //  bool operator()(mirror::ArtField* field1, mirror::ArtField* field2)
            //      NO_THREAD_SAFETY_ANALYSIS {
            //    // First come reference fields, then 64-bit, and finally 32-bit
            //    Primitive::Type type1 = field1->GetTypeAsPrimitiveType();
            //    Primitive::Type type2 = field2->GetTypeAsPrimitiveType();
            //    if (type1 != type2) {
            //      bool is_primitive1 = type1 != Primitive::kPrimNot;
            //      bool is_primitive2 = type2 != Primitive::kPrimNot;
            //      bool is64bit1 = is_primitive1 && (type1 == Primitive::kPrimLong ||
            //                                        type1 == Primitive::kPrimDouble);
            //      bool is64bit2 = is_primitive2 && (type2 == Primitive::kPrimLong ||
            //                                        type2 == Primitive::kPrimDouble);
            //      int order1 = !is_primitive1 ? 0 : (is64bit1 ? 1 : 2);
            //      int order2 = !is_primitive2 ? 0 : (is64bit2 ? 1 : 2);
            //      if (order1 != order2) {
            //        return order1 < order2;
            //      }
            //    }
            //    // same basic group? then sort by string.
            //    return strcmp(field1->GetName(), field2->GetName()) < 0;
            //  }
            @Override
            public int compare(Field f1, Field f2) {
                int type1 = getTypeAsPrimitiveType(f1);
                int type2 = getTypeAsPrimitiveType(f2);
                if (type1 != type2) {
                    boolean isPrimitive1 = type1 != kPrimNot;
                    boolean isPrimitive2 = type2 != kPrimNot;
                    boolean is64bit1 = isPrimitive1 &&
                            (type1 == kPrimLong || type1 == kPrimDouble);
                    boolean is64bit2 = isPrimitive2 &&
                            (type2 == kPrimLong || type2 == kPrimDouble);
                    int order1 = !isPrimitive1 ? 0 : (is64bit1 ? 1 : 2);
                    int order2 = !isPrimitive2 ? 0 : (is64bit2 ? 1 : 2);
                    return order1 - order2;
                }
                return f1.getName().compareTo(f2.getName());
            }
        }

        final static class FieldComparator implements Comparator<Field>  {
            // art/runtime/class_linker.cc LinkFieldsComparator
            // https://android-review.googlesource.com/#/c/114281/
            // https://android-review.googlesource.com/#/c/114814/
            // https://android.googlesource.com/platform/art/+/android-5.1.1_r37/runtime/class_linker.cc#5362
            //  bool operator()(mirror::ArtField* field1, mirror::ArtField* field2)
            //      NO_THREAD_SAFETY_ANALYSIS {
            //    // First come reference fields, then 64-bit, and finally 32-bit
            //    Primitive::Type type1 = field1->GetTypeAsPrimitiveType();
            //    Primitive::Type type2 = field2->GetTypeAsPrimitiveType();
            //    if (type1 != type2) {
            //      if (type1 == Primitive::kPrimNot) {
            //        // Reference always goes first.
            //        return true;
            //      }
            //      if (type2 == Primitive::kPrimNot) {
            //        // Reference always goes first.
            //        return false;
            //      }
            //      size_t size1 = Primitive::ComponentSize(type1);
            //      size_t size2 = Primitive::ComponentSize(type2);
            //      if (size1 != size2) {
            //        // Larger primitive types go first.
            //        return size1 > size2;
            //      }
            //      // Primitive types differ but sizes match. Arbitrarily order by primitive type.
            //      return type1 < type2;
            //    }
            //    // Same basic group? Then sort by dex field index. This is guaranteed to be sorted
            //    // by name and for equal names by type id index.
            //    // NOTE: This works also for proxies. Their static fields are assigned appropriate indexes.
            //    return field1->GetDexFieldIndex() < field2->GetDexFieldIndex();
            //  }
            //};
            @Override
            public int compare(Field f1, Field f2) {
                int type1 = getTypeAsPrimitiveType(f1);
                int type2 = getTypeAsPrimitiveType(f2);
                if (type1 != type2) {
                    if (type1 == kPrimNot) {
                        return -1;
                    }
                    if (type2 == kPrimNot) {
                        return 1;
                    }
                    int size1 = getComponentSize(type1);
                    int size2 = getComponentSize(type2);
                    if (size1 != size2) {
                        return size2 - size1;
                    }
                    return type1 - type2;
                }
                if (f1 instanceof DexBackedField && f2 instanceof DexBackedField) {
                    return ((DexBackedField) f1).fieldIndex - ((DexBackedField) f2).fieldIndex;
                }
                return 0;
            }
        }

        static int roundUp(int n, int mul) {
            return (n > 0) ? ((n + mul - 1) / mul) * mul
                    : (n / mul) * mul;
        }

        static boolean isAligned(int n, int x) {
            assert ((n & (n - 1)) == 0); // n_not_power_of_two
            return (x & (n - 1)) == 0;
        }

        // runtime/primitive.h
        final static int kObjectReferenceSize = 4;

        final static int kPrimNot = 0;
        final static int kPrimBoolean = 1;
        final static int kPrimByte = 2;
        final static int kPrimChar = 3;
        final static int kPrimShort = 4;
        final static int kPrimInt = 5;
        final static int kPrimLong = 6;
        final static int kPrimFloat = 7;
        final static int kPrimDouble = 8;
        final static int kPrimVoid = 9;

        static int getTypeAsPrimitiveType(Field field) {
            return getPrimitiveType(field.getType().charAt(0));
        }

        static int getPrimitiveType(char type) {
            switch (type) {
            case 'B':
                return kPrimByte;
            case 'C':
                return kPrimChar;
            case 'D':
                return kPrimDouble;
            case 'F':
                return kPrimFloat;
            case 'I':
                return kPrimInt;
            case 'J':
                return kPrimLong;
            case 'S':
                return kPrimShort;
            case 'Z':
                return kPrimBoolean;
            case 'V':
                return kPrimVoid;
            default:
                return kPrimNot;
            }
        }

        static int getComponentSize(int type) {
            switch (type) {
            case kPrimVoid:
                return 0;
            case kPrimBoolean:
            case kPrimByte:
                return 1;
            case kPrimChar:
            case kPrimShort:
                return 2;
            case kPrimInt:
            case kPrimFloat:
                return 4;
            case kPrimLong:
            case kPrimDouble:
                return 8;
            case kPrimNot:
                return kObjectReferenceSize;
            default:
                assert false;
                return 0;
            }
        }
    }

    private static class DalvikIFieldSupplier extends FieldOffsetCalculator {

        DalvikIFieldSupplier(ClassProto clsProto) {
            super(clsProto);
            initialFieldOffset = 8; // OFFSETOF_MEMBER(DataObject, instanceData)
        }

        @Override
        public SparseArray<FieldReference> get() {
            // This is a bit of an "involved" operation. We need to follow the same
            // algorithm that dalvik uses to arrange fields, so that we end up with
            // the same field offsets (which is needed for deodexing).
            // See dalvik/vm/oo/Class.c computeFieldOffsets()

            ArrayList<Field> fields = Lists.newArrayList(
                    classProto.getClassDef().getInstanceFields());
            Collections.sort(fields);
            final int fieldCount = fields.size();
            // The "type" for each field in fields. 0=reference,1=wide,2=other
            byte[] fieldTypes = new byte[fields.size()];
            for (int i = 0; i < fieldCount; i++) {
                fieldTypes[i] = getFieldWidthType(fields.get(i));
            }

            // The first operation is to move all of the reference fields to the front.
            // To do this, find the first non-reference field, then find the last
            // reference field, swap them and repeat.
            int back = fields.size() - 1;
            int front;
            for (front = 0; front < fieldCount; front++) {
                if (fieldTypes[front] != REFERENCE) {
                    while (back > front) {
                        if (fieldTypes[back] == REFERENCE) {
                            swap(fieldTypes, fields, front, back--);
                            break;
                        }
                        back--;
                    }
                }

                if (fieldTypes[front] != REFERENCE) {
                    break;
                }
            }

            int startFieldOffset = initialFieldOffset;
            String superclassType = classProto.getSuperclass();
            ClassProto superclass = null;
            if (superclassType != null) {
                superclass = (ClassProto) classProto.classPath.getClass(superclassType);
                startFieldOffset = superclass.getNextFieldOffset();
            }
            //System.out.println("type=" + type + " superclass="
            //        + superclass + " startFieldOffset=" + startFieldOffset);

            int fieldIndexMod;
            if ((startFieldOffset % 8) == 0) {
                fieldIndexMod = 0;
            } else {
                fieldIndexMod = 1;
            }

            // Next, we need to group all the wide fields after the reference fields.
            // But the wide fields have to be 8-byte aligned. If we're on an odd field index,
            // we need to insert a 32-bit field. If the next field is already a 32-bit field,
            // use that. Otherwise, find the first 32-bit field from the end and swap it in.
            // If there are no 32-bit fields, do nothing for now. We'll add padding when
            // calculating the field offsets.
            if (front < fieldCount && (front % 2) != fieldIndexMod) {
                if (fieldTypes[front] == WIDE) {
                    // We need to swap in a 32-bit field, so the wide fields will be correctly aligned
                    back = fieldCount - 1;
                    while (back > front) {
                        if (fieldTypes[back] == OTHER) {
                            swap(fieldTypes, fields, front++, back);
                            break;
                        }
                        back--;
                    }
                } else {
                    // There's already a 32-bit field here that we can use
                    front++;
                }
            }

            // Do the swap thing for wide fields
            back = fieldCount - 1;
            for (; front < fieldCount; front++) {
                if (fieldTypes[front] != WIDE) {
                    while (back > front) {
                        if (fieldTypes[back] == WIDE) {
                            swap(fieldTypes, fields, front, back--);
                            break;
                        }
                        back--;
                    }
                }

                if (fieldTypes[front] != WIDE) {
                    break;
                }
            }

            SparseArray<FieldReference> superFields;
            if (superclass != null) {
                superFields = superclass.getInstanceFields();
            } else {
                superFields = new SparseArray<>();
            }
            int superFieldCount = superFields.size();

            // Now the fields are in the correct order.
            // Add them to the SparseArray and lookup, and calculate the offsets
            int totalFieldCount = superFieldCount + fieldCount;
            SparseArray<FieldReference> instanceFields = new SparseArray<>(totalFieldCount);

            int fieldOffset;
            if (superclass != null && superFieldCount > 0) {
                for (int i = 0; i < superFieldCount; i++) {
                    instanceFields.append(superFields.keyAt(i), superFields.valueAt(i));
                }
                fieldOffset = superclass.getNextFieldOffset();
            } else {
                fieldOffset = initialFieldOffset;
            }

            boolean gotDouble = false;
            for (int i = 0; i < fieldCount; i++) {
                FieldReference field = fields.get(i);

                // Add padding to align the wide fields, if needed
                if (fieldTypes[i] == WIDE && !gotDouble) {
                    if (fieldOffset % 8 != 0) {
                        assert fieldOffset % 8 == 4;
                        fieldOffset += 4;
                    }
                    gotDouble = true;
                }

                instanceFields.append(fieldOffset, field);
                if (fieldTypes[i] == WIDE) {
                    fieldOffset += 8;
                } else {
                    fieldOffset += 4;
                }
            }

            return instanceFields;
        }

        static void swap(byte[] fieldTypes, List<Field> fields, int position1, int position2) {
            byte tempType = fieldTypes[position1];
            fieldTypes[position1] = fieldTypes[position2];
            fieldTypes[position2] = tempType;

            Field tempField = fields.set(position1, fields.get(position2));
            fields.set(position2, tempField);
        }
    }

    private int getNextFieldOffset() {
        SparseArray<FieldReference> instanceFields = getInstanceFields();
        if (instanceFields.size() == 0) {
            return 8;
        }

        int lastItemIndex = instanceFields.size() - 1;
        int fieldOffset = instanceFields.keyAt(lastItemIndex);
        FieldReference lastField = instanceFields.valueAt(lastItemIndex);

        if (getFieldWidthType(lastField) == WIDE) {
            return fieldOffset + 8;
        }
        return fieldOffset + 4;
    }

    // TODO: check the case when we have a package private method that overrides an interface method
    abstract static class VirtualTableSupplier implements Supplier<List<Method>> {
        final ClassProto classProto;

        VirtualTableSupplier(ClassProto cProto) {
            classProto = cProto;
        }

        abstract List<Method> getVirtualTable(List<Method> vtable);

        @Override
        public List<Method> get() {
            List<Method> vtable = Lists.newArrayList();

            //copy the virtual methods from the superclass
            String superclassType;
            try {
                superclassType = classProto.getSuperclass();
            } catch (UnresolvedClassException ex) {
                vtable.addAll(((ClassProto) classProto.classPath.getClass("Ljava/lang/Object;")).getVtable());
                classProto.vtableFullyResolved = false;
                return vtable;
            }

            if (superclassType != null) {
                ClassProto superclass = (ClassProto) classProto.classPath.getClass(superclassType);
                vtable.addAll(superclass.getVtable());

                // If the superclass's vtable wasn't fully resolved, then we can't know where the
                // new methods added by this class should start, so we just propagate what we can
                // from the parent and hope for the best.
                if (!superclass.vtableFullyResolved) {
                    classProto.vtableFullyResolved = false;
                    return vtable;
                }
            }

            // Iterate over the virtual methods in the current class, and only add them when
            // we don't already have the method (i.e. if it was implemented by the superclass).
            if (!classProto.isInterface()) {
                return getVirtualTable(vtable);
            }
            return vtable;
        }

        static boolean canAccess(@Nonnull TypeProto type, @Nonnull Method virtualMethod) {
            if (!methodIsPackagePrivate(virtualMethod.getAccessFlags())) {
                return true;
            }

            String otherPackage = getPackage(virtualMethod.getDefiningClass());
            String ourPackage = getPackage(type.getType());
            return otherPackage.equals(ourPackage);
        }

        @Nonnull
        static String getPackage(@Nonnull String classType) {
            int lastSlash = classType.lastIndexOf('/');
            if (lastSlash < 0) {
                return "";
            }
            return classType.substring(1, lastSlash);
        }

        static boolean methodSignaturesMatch(@Nonnull Method a, @Nonnull Method b) {
            return (a.getName().equals(b.getName()) &&
                    a.getReturnType().equals(b.getReturnType()) &&
                    a.getParameters().equals(b.getParameters()));
        }

        static boolean methodIsPackagePrivate(int accessFlags) {
            return (accessFlags & (AccessFlags.PRIVATE.getValue() |
                    AccessFlags.PROTECTED.getValue() |
                    AccessFlags.PUBLIC.getValue())) == 0;
        }
    }

    private static class VirtualTableSupplierLegacy extends VirtualTableSupplier {
        VirtualTableSupplierLegacy(ClassProto classProto) {
            super(classProto);
        }

        @Override
        public List<Method> getVirtualTable(List<Method> vtable) {
            addToVtable(classProto.getClassDef().getVirtualMethods(), vtable, true);

            // Assume that interface method is implemented in the current class, when adding
            // it to vtable otherwise it looks like that method is invoked on an interface,
            // which fails Dalvik's optimization checks.
            for (ClassDef interfaceDef: classProto.getDirectInterfaces()) {
                List<Method> interfaceMethods = Lists.newArrayList();
                for (Method interfaceMethod: interfaceDef.getVirtualMethods()) {
                    ImmutableMethod method = new ImmutableMethod(
                            classProto.type,
                            interfaceMethod.getName(),
                            interfaceMethod.getParameters(),
                            interfaceMethod.getReturnType(),
                            interfaceMethod.getAccessFlags(),
                            interfaceMethod.getAnnotations(),
                            interfaceMethod.getImplementation());
                    interfaceMethods.add(method);
                }
                addToVtable(interfaceMethods, vtable, false);
            }

            return vtable;
        }

        private void addToVtable(@Nonnull Iterable<? extends Method> localMethods,
                                 @Nonnull List<Method> vtable, boolean replaceExisting) {
            List<? extends Method> methods = Lists.newArrayList(localMethods);
            Collections.sort(methods);

            outer:
            for (Method virtualMethod : methods) {
                for (int i = 0; i < vtable.size(); i++) {
                    Method superMethod = vtable.get(i);
                    if (methodSignaturesMatch(superMethod, virtualMethod)) {
                        if (!classProto.classPath.checkPackagePrivateAccess
                                || canAccess(classProto, superMethod)) {
                            if (replaceExisting) {
                                vtable.set(i, virtualMethod);
                            }
                            continue outer;
                        }
                    }
                }
                // We didn't find an equivalent method, so add it as a new entry.
                vtable.add(virtualMethod);
            }
        }
    }

    private static class VirtualTableSupplierLatest extends VirtualTableSupplier {
        VirtualTableSupplierLatest(ClassProto classProto) {
            super(classProto);
        }

        @Override
        public List<Method> getVirtualTable(List<Method> vtable) {
            addToVtable(Lists.newArrayList(classProto.getClassDef().getVirtualMethods()),
                    vtable, true, false);

            for (ClassDef interfaceDef : classProto.getDirectInterfaces()) {
                List<Method> interfaceMethods = Lists.newArrayList();
                List<Method> defaultMethods = null;

                for (Method interfaceMethod : interfaceDef.getVirtualMethods()) {
                    ImmutableMethod method = new ImmutableMethod(
                            interfaceMethod.getDefiningClass(),
                            interfaceMethod.getName(),
                            interfaceMethod.getParameters(),
                            interfaceMethod.getReturnType(),
                            interfaceMethod.getAccessFlags(),
                            interfaceMethod.getAnnotations(),
                            interfaceMethod.getImplementation());
                    if (MethodUtil.isDefault(interfaceMethod)) {
                        if (defaultMethods == null) {
                            defaultMethods = Lists.newArrayList();
                        }
                        defaultMethods.add(method);
                    } else {
                        interfaceMethods.add(method);
                    }
                }
                if (defaultMethods != null) {
                    addToVtable(defaultMethods, vtable, true, true);
                }
                addToVtable(interfaceMethods, vtable, false, false);
            }

            return vtable;
        }

        private void addToVtable(@Nonnull List<Method> localMethods,
                                 @Nonnull List<Method> vtable, boolean replaceExisting,
                                 boolean arrangeDefaultMethod) {
            Collections.sort(localMethods);

            outer:
            for (Method virtualMethod : localMethods) {
                for (int i = 0; i < vtable.size(); i++) {
                    Method superMethod = vtable.get(i);
                    if (methodSignaturesMatch(superMethod, virtualMethod)) {
                        if (!classProto.classPath.checkPackagePrivateAccess
                                || canAccess(classProto, superMethod)) {
                            if (replaceExisting) {
                                if (arrangeDefaultMethod) {
                                    TypeProto interfaceImpl = classProto.classPath.getClass(
                                            virtualMethod.getDefiningClass());
                                    if (!interfaceImpl.implementsInterface(
                                            superMethod.getDefiningClass())) {
                                        // The current vtable has included the overridden default
                                        // method, so if we meet the method from parent interface,
                                        // just skip it.
                                        continue outer;
                                    }
                                }
                                vtable.set(i, virtualMethod);

                                // Workaround to simulate for N bug (maybe) which
                                // adds duplicate default method.
                                if ((arrangeDefaultMethod || MethodUtil.isAbstract(virtualMethod))
                                        && !virtualMethod.getDefiningClass().equals(
                                        superMethod.getDefiningClass())) {
                                    System.out.println(
                                            "#addred# " + MethodUtil.toSourceStyleString(virtualMethod));
                                    vtable.add(virtualMethod);
                                }
                            }
                            continue outer;
                        }
                    }
                }
                // We didn't find an equivalent method, so add it as a new entry.
                vtable.add(virtualMethod);
            }
        }
    }

    @Nonnull
    List<Method> getVtable() {
        return vtableSupplier.get();
    }
}
