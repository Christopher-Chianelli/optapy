package org.optaplanner.jpyinterpreter.util;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class TypeHelper {

    public static Class<?> getBoxedType(Class<?> javaClass) {
        JvmStackType jvmStackType = JvmStackType.getStackTypeForClass(javaClass);
        if (jvmStackType != JvmStackType.OBJECT) {
            return jvmStackType.getBoxedType();
        }
        return javaClass;
    }

    public static void unboxWithoutCast(MethodVisitor methodVisitor, Class<?> javaClass) {
        JvmStackType jvmStackType = JvmStackType.getStackTypeForClass(javaClass);
        jvmStackType.unbox(methodVisitor, javaClass, false);
    }

    public static void unboxWithCast(MethodVisitor methodVisitor, Class<?> javaClass) {
        JvmStackType jvmStackType = JvmStackType.getStackTypeForClass(javaClass);
        jvmStackType.unbox(methodVisitor, javaClass, true);
    }

    public static void box(MethodVisitor methodVisitor, Class<?> javaClass) {
        JvmStackType jvmStackType = JvmStackType.getStackTypeForClass(javaClass);
        jvmStackType.box(methodVisitor);
    }

    public static Type getBoxedType(Type javaType) {
        JvmStackType jvmStackType = JvmStackType.getStackTypeForType(javaType);
        if (jvmStackType != JvmStackType.OBJECT) {
            return Type.getType(jvmStackType.getBoxedType());
        }
        return javaType;
    }

    public static void unboxWithoutCast(MethodVisitor methodVisitor, Type javaType) {
        JvmStackType jvmStackType = JvmStackType.getStackTypeForType(javaType);
        jvmStackType.unbox(methodVisitor, javaType, false);
    }

    public static void unboxWithCast(MethodVisitor methodVisitor, Type javaType) {
        JvmStackType jvmStackType = JvmStackType.getStackTypeForType(javaType);
        jvmStackType.unbox(methodVisitor, javaType, true);
    }

    public static void box(MethodVisitor methodVisitor, Type javaType) {
        JvmStackType jvmStackType = JvmStackType.getStackTypeForType(javaType);
        jvmStackType.box(methodVisitor);
    }

    public static String getBoxedType(String javaClassInternalName) {
        JvmStackType jvmStackType = JvmStackType.getStackTypeForInternalName(javaClassInternalName);
        if (jvmStackType != JvmStackType.OBJECT) {
            return Type.getInternalName(jvmStackType.getBoxedType());
        }
        return javaClassInternalName;
    }

    public static void unboxWithoutCast(MethodVisitor methodVisitor, String javaClassInternalName) {
        JvmStackType jvmStackType = JvmStackType.getStackTypeForInternalName(javaClassInternalName);
        jvmStackType.unbox(methodVisitor, javaClassInternalName, false);
    }

    public static void unboxWithCast(MethodVisitor methodVisitor, String javaClassInternalName) {
        JvmStackType jvmStackType = JvmStackType.getStackTypeForInternalName(javaClassInternalName);
        jvmStackType.unbox(methodVisitor, javaClassInternalName, true);
    }

    public static void box(MethodVisitor methodVisitor, String javaClassInternalName) {
        JvmStackType jvmStackType = JvmStackType.getStackTypeForInternalName(javaClassInternalName);
        jvmStackType.box(methodVisitor);
    }

    private enum JvmStackType {
        BOOLEAN(boolean.class, Boolean.class, "booleanValue"),
        BYTE(byte.class, Byte.class, "byteValue"),
        CHARACTER(char.class, Character.class, "charValue"),
        SHORT(short.class, Short.class, "shortValue"),
        INT(int.class, Integer.class, "intValue"),
        LONG(long.class, Long.class, "longValue"),
        FLOAT(float.class, Float.class, "floatValue"),
        DOUBLE(double.class, Double.class, "doubleValue"),
        OBJECT(Object.class, null, null);

        private Class<?> stackType;
        private Class<?> boxedType;
        private String boxedGetter;

        JvmStackType(Class<?> stackType, Class<?> boxedType, String boxedGetter) {
            this.stackType = stackType;
            this.boxedType = boxedType;
            this.boxedGetter = boxedGetter;
        }

        public static JvmStackType getStackTypeForClass(Class<?> javaClass) {
            for (JvmStackType jvmStackType: JvmStackType.values()) {
                if (jvmStackType.stackType.equals(javaClass)) {
                    return jvmStackType;
                }
            }
            return OBJECT;
        }

        public static JvmStackType getStackTypeForType(Type javaType) {
            for (JvmStackType jvmStackType: JvmStackType.values()) {
                if (Type.getType(jvmStackType.stackType).equals(javaType)) {
                    return jvmStackType;
                }
            }
            return OBJECT;
        }

        public static JvmStackType getStackTypeForInternalName(String javaInternalName) {
            for (JvmStackType jvmStackType: JvmStackType.values()) {
                if (Type.getInternalName(jvmStackType.stackType).equals(javaInternalName)) {
                    return jvmStackType;
                }
            }
            return OBJECT;
        }

        public void unbox(MethodVisitor methodVisitor, Class<?> targetType, boolean cast) {
            if (cast) {
                if (boxedType == null) {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(targetType));
                } else {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(boxedType));
                }
            }
            if (boxedType != null) {
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(boxedType),
                        boxedGetter, Type.getMethodDescriptor(Type.getType(stackType)), false);
            }
        }

        public void unbox(MethodVisitor methodVisitor, Type targetType, boolean cast) {
            if (cast) {
                if (boxedType == null) {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, targetType.getInternalName());
                } else {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(boxedType));
                }
            }
            if (boxedType != null) {
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(boxedType),
                        boxedGetter, Type.getMethodDescriptor(Type.getType(stackType)), false);
            }
        }

        public void unbox(MethodVisitor methodVisitor, String targetTypeInternalName, boolean cast) {
            if (cast) {
                if (boxedType == null) {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, targetTypeInternalName);
                } else {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(boxedType));
                }
            }
            if (boxedType != null) {
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(boxedType),
                        boxedGetter, Type.getMethodDescriptor(Type.getType(stackType)), false);
            }
        }

        public void box(MethodVisitor methodVisitor) {
            if (boxedType != null) {
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(boxedType),
                        "valueOf", Type.getMethodDescriptor(Type.getType(boxedType), Type.getType(stackType)), false);
            }
        }

        public Class<?> getBoxedType() {
            return boxedType;
        }

    }
}
