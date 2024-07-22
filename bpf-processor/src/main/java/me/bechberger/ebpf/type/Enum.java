package me.bechberger.ebpf.type;

import me.bechberger.ebpf.annotations.bpf.MethodIsBPFRelatedFunction;
import me.bechberger.ebpf.annotations.bpf.BuiltinBPFFunction;
import me.bechberger.ebpf.annotations.EnumMember;
import me.bechberger.ebpf.annotations.bpf.NotUsableInJava;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;

/**
 * Base interface for all enums, {@link #value()} returns the value of the enum member
 * <p>
 * The value is either specified by the {@link EnumMember} annotation or is +1 of the previous member, starting at 0
 * <p>
 * Example: {@snippet :
 *     import me.bechberger.ebpf.annotations.EnumMember;@Type
 *     enum Kind implements Enum<Kind> {
 *         A, // value 0
 *         // value 23
 *         @EnumMember(value = 23, name = "KIND_A") B,
 *         C, // value 24
 *         D  // value 25
 *     }
 *}
 * @param <T> The enum type
 */
public interface Enum<T extends java.lang.Enum<T> & Enum<T>> {

    /**
     * Utility class to support enums that implement {@link Enum}
     */
    class EnumSupport {
        public static <T extends java.lang.Enum<T> & Enum<T>> T fromValue(Class<T> enumClass, long value) {
            // Optimize this with some code generation if it ever is a performance problem
            var members = enumClass.getEnumConstants();
            long currentValue = 0;
            for (T t : members) {
                var val = getMemberAnnotationValue((T) t);
                if (val != -1) {
                    if (val == value) {
                        return (T) t;
                    }
                    currentValue = val + 1;
                } else {
                    if (currentValue == value) {
                        return (T) t;
                    }
                    currentValue++;
                }
            }
            return null;
        }

        private static <T extends java.lang.Enum<T> & Enum<T>> long getMemberAnnotationValue(T enumMember) {
            Field field = enumMember.getClass().getDeclaredFields()[enumMember.ordinal()];
            var ann = field.getAnnotation(EnumMember.class);
            return ann == null ? -1 : ann.value();
        }

        /**
         * Get the value of an enum member
         */
        @SuppressWarnings("unchecked")
        public static <T extends java.lang.Enum<T> & Enum<T>> long value(T enumMember) {
            var val = getMemberAnnotationValue(enumMember);
            if (val != -1) {
                return val;
            }
            // check if any of the ordinals coming before this one have a value in their annotation
            var members = enumMember.getClass().getEnumConstants();
            for (int i = 0; i < enumMember.ordinal(); i++) {
                var member = (T) members[i];
                val = getMemberAnnotationValue(member);
                if (val != -1) {
                    return val + (enumMember.ordinal() - i);
                }
            }
            return enumMember.ordinal();
        }

        public static <T extends java.lang.Enum<T> & Enum<T>> String toString(T enumMember) {
            return enumMember.name() + "(" + value(enumMember) + ")";
        }
    }

    /**
     * Value of the enum member
     */
    @SuppressWarnings("unchecked")
    @BuiltinBPFFunction("(long)($this)")
    default long value() {
        return EnumSupport.value((T) this);
    }

    @BuiltinBPFFunction("($arg1)")
    @SuppressWarnings("unchecked")
    default @Nullable T fromValue(long value) {
        // get EnumMember annotation value
        return EnumSupport.fromValue((Class<T>) this.getClass(), value);
    }

    @BuiltinBPFFunction("($T1)($arg1)")
    @NotUsableInJava
    static <T extends java.lang.Enum<T> & Enum<T>> T ofValue(long value) {
        throw new MethodIsBPFRelatedFunction();
    }

    default String toStr() {
        return ((java.lang.Enum<?>) this).name() + "(" + value() + ")";
    }
}
