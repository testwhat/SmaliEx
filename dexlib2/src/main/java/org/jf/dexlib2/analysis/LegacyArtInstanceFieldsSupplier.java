package org.jf.dexlib2.analysis;

import com.google.common.base.Supplier;
import com.google.common.collect.Lists;

import org.jf.dexlib2.VersionMap;
import org.jf.dexlib2.dexbacked.DexBackedField;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.util.SparseArray;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.TreeSet;

// For Lollipop ~ Marshmallow
class LegacyArtInstanceFieldsSupplier implements Supplier<SparseArray<FieldReference>> {
    static final int API_M = 23;
    final Comparator<Field> fieldComparator; // art/runtime/class_linker.cc LinkFieldsComparator
    final ClassProto classProto;

    LegacyArtInstanceFieldsSupplier(ClassProto classProto) {
        this.classProto = classProto;
        fieldComparator = createFieldComparator(classProto.classPath.oatVersion);
    }

    SparseArray<FieldReference> getForApiLevelBelow23(ArrayList<Field> fields,
                                                      int fieldOffset,
                                                      SparseArray<FieldReference> instanceFields) {
        // https://android.googlesource.com/platform/art/+/android-5.1.1_r37/runtime/class_linker.cc#5425
        final int numFields = fields.size();
        int currentField = 0;
        for (; currentField < numFields; currentField++) {
            final Field field = fields.get(0);
            final int type = getTypeAsPrimitiveType(field);
            final boolean isPrimitive = type != kPrimNot;
            if (isPrimitive) {
                break;
            }
            fields.remove(0);
            instanceFields.append(fieldOffset, field);
            fieldOffset += sizeof_uint32_t;
        }

        if (currentField != numFields && !isAligned(8, fieldOffset)) {
            for (int i = 0; i < fields.size(); i++) {
                final Field field = fields.get(i);
                final int type = getTypeAsPrimitiveType(field);
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
            final Field field = fields.remove(0);
            final int type = getTypeAsPrimitiveType(field);
            instanceFields.append(fieldOffset, field);
            fieldOffset += (type == kPrimLong || type == kPrimDouble) ?
                    sizeof_uint64_t : sizeof_uint32_t;
        }
        classProto.objectSize = fieldOffset; // not for IsVariableSize (String and Array)

        return instanceFields;
    }

    static final int sizeof_mirror_HeapReference_mirror_Object = 4;

    @Override
    public SparseArray<FieldReference> get() {
        // See art/runtime/class_linker.cc ClassLinker::LinkFields
        final ArrayList<Field> fields = Lists.newArrayList(
                classProto.getClassDef().getInstanceFields());
        fields.sort(fieldComparator);

        final int fieldCount = fields.size();
        // art/runtime/class_linker.cc LinkFields
        // art/compiler/dex/dex_to_dex_compiler.cc
        int fieldOffset = 0; // MemberOffset field_offset(0);
        int superFieldCount = 0;
        final String superclassType = classProto.getSuperclass();
        SparseArray<FieldReference> superFields = null;
        if (superclassType != null) {
            final ClassProto superclass = (ClassProto) classProto.classPath.getClass(superclassType);
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
        if (VersionMap.mapArtVersionToApi(classProto.classPath.oatVersion) < API_M) {
            return getForApiLevelBelow23(fields, fieldOffset, instanceFields);
        }

        // References should be at the front.
        final GapContainer gaps = new GapContainer(
                createFieldGapCreator(classProto.classPath.oatVersion));
        int currentField = 0;
        for (; currentField < fieldCount; currentField++) {
            final Field f = fields.get(0);
            final int type = getTypeAsPrimitiveType(f);
            final boolean isPrimitive = type != kPrimNot;
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
        final FieldOffsetData data = new FieldOffsetData();
        data.currentField = currentField;
        data.fieldOffset = fieldOffset;
        data.fields = fields;
        data.gaps = gaps;
        data.instanceFields = instanceFields;
        shuffleForward(8, data);
        shuffleForward(4, data);
        shuffleForward(2, data);
        shuffleForward(1, data);

        assert fields.isEmpty();
        // See runtime/mirror/class.h
        classProto.objectSize = data.fieldOffset; // not for IsVariableSize (String and Array)
        return instanceFields;
    }

    static void shuffleForward(int n, FieldOffsetData data) {
        while (!data.fields.isEmpty()) {
            final Field f = data.fields.get(0);
            final int type = getTypeAsPrimitiveType(f);
            if (getComponentSize(type) < n) {
                break;
            }
            if (!isAligned(n, data.fieldOffset)) {
                final int oldFieldOffset = data.fieldOffset;
                data.fieldOffset = roundUp(data.fieldOffset, n);
                FieldGap.add(oldFieldOffset, data.fieldOffset, data.gaps);
            }
            data.fields.remove(0);
            if (!data.gaps.isEmpty() && data.gaps.first().size >= n) {
                final FieldGap gap = data.gaps.pollFirst();
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
            final int c = rhs.size - size;
            if (c != 0) {
                return c;
            } else {
                return startOffset - rhs.startOffset;
            }
        }

        static void add(int gapStart, int gapEnd, GapContainer gaps) {
            int currentOffset = gapStart;
            while (currentOffset != gapEnd) {
                final int remaining = gapEnd - currentOffset;
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
            final int c = size - rhs.size;
            if (c != 0) {
                return c;
            } else {
                return rhs.startOffset - startOffset;
            }
        }
    }

    static Comparator<Field> createFieldComparator(int oatVersion) {
        return oatVersion <= 39 ? new FieldComparatorL50() : new FieldComparatorL51();
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

    final static class FieldComparatorL51 implements Comparator<Field>  {
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
    static final int kObjectReferenceSize = 4;

    static final int kPrimNot = 0;
    static final int kPrimBoolean = 1;
    static final int kPrimByte = 2;
    static final int kPrimChar = 3;
    static final int kPrimShort = 4;
    static final int kPrimInt = 5;
    static final int kPrimLong = 6;
    static final int kPrimFloat = 7;
    static final int kPrimDouble = 8;
    static final int kPrimVoid = 9;

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
