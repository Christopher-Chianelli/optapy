package org.optaplanner.jpyinterpreter.implementors;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.optaplanner.jpyinterpreter.PythonClassTranslator;
import org.optaplanner.jpyinterpreter.PythonCompiledClass;
import org.optaplanner.jpyinterpreter.PythonCompiledFunction;
import org.optaplanner.jpyinterpreter.PythonLikeObject;
import org.optaplanner.jpyinterpreter.types.BuiltinTypes;
import org.optaplanner.jpyinterpreter.types.PythonLikeType;
import org.optaplanner.jpyinterpreter.types.errors.ValueError;
import org.optaplanner.jpyinterpreter.util.MethodVisitorAdapters;
import org.optaplanner.jpyinterpreter.util.TypeHelper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

public class InterfaceDelegateImplementor extends JavaInterfaceImplementor {
    final Class<?> interfaceClass;
    final String internalClassName;

    public InterfaceDelegateImplementor(Class<?> interfaceClass, String internalClassName) {
        this.interfaceClass = interfaceClass;
        this.internalClassName = internalClassName;
    }

    @Override
    public Class<?> getInterfaceClass() {
        return interfaceClass;
    }

    private void implementMethod(ClassWriter classWriter, PythonCompiledClass compiledClass, Method method) {
        PythonCompiledFunction pythonImplementation = compiledClass.instanceFunctionNameToPythonBytecode.get(method.getName());
        if (pythonImplementation == null) {
            if (!Modifier.isAbstract(method.getModifiers())) {
                // default method
                return;
            }
            throw new ValueError("Class (" + compiledClass.className + ") does not implement (" + interfaceClass.getName() + "): missing method (" + method + ").");
        }

        if (pythonImplementation.totalArgCount() - 1 != method.getParameterCount()) {
            throw new ValueError("Class (" + compiledClass.className + ") does not implement (" + interfaceClass.getName() + "): method (" + method +
                    ") has wrong parameter count (expected: " + method.getParameterCount() + ", actual: " + (pythonImplementation.totalArgCount() - 1) + ")");
        }

        MethodVisitor methodVisitor = MethodVisitorAdapters.adapt(classWriter.visitMethod(Modifier.PUBLIC, method.getName(),
                Type.getMethodDescriptor(method),
                null,
                null),  method.getName(), Type.getMethodDescriptor(method));

        methodVisitor.visitCode();

        // Load return class constant here to avoid a swap
        Class<?> javaReturnType = method.getReturnType();
        if (javaReturnType.equals(void.class)) {
            // do nothing
        } else {
            methodVisitor.visitLdcInsn(Type.getType(TypeHelper.getBoxedType(javaReturnType)));
        }

        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        List<PythonLikeType> parameterTypeList = pythonImplementation.getParameterTypes();
        for (int i = 1; i < pythonImplementation.totalArgCount(); i++) {
            PythonLikeType parameterType = parameterTypeList.get(i);
            methodVisitor.visitVarInsn(Opcodes.ALOAD, i);
            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JavaPythonTypeConversionImplementor.class),
                    "wrapJavaObject", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class), Type.getType(Object.class)),
                    false);
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, parameterType.getJavaTypeInternalName());
        }

        PythonClassTranslator.InterfaceDeclaration interfaceDeclaration = PythonClassTranslator.getInterfaceForInstancePythonFunction(internalClassName, pythonImplementation);

        Type methodType = Type.getMethodType(interfaceDeclaration.getMethodDescriptor());
        Type[] newParameterTypes = new Type[method.getParameterCount()];
        System.arraycopy(methodType.getArgumentTypes(), 1, newParameterTypes, 0, newParameterTypes.length);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internalClassName, PythonClassTranslator.getJavaMethodName(method.getName()),
                Type.getMethodDescriptor(methodType.getReturnType(), newParameterTypes), false);

        if (javaReturnType.equals(void.class)) {
            methodVisitor.visitInsn(Opcodes.RETURN);
            methodVisitor.visitMaxs(0, 0);
            methodVisitor.visitEnd();
            return;
        }

        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JavaPythonTypeConversionImplementor.class),
                "convertPythonObjectToJavaType", Type.getMethodDescriptor(Type.getType(Object.class), Type.getType(Class.class), Type.getType(PythonLikeObject.class)),
                false);

        TypeHelper.unboxWithCast(methodVisitor, javaReturnType);
        methodVisitor.visitInsn(Type.getType(javaReturnType).getOpcode(Opcodes.IRETURN));
        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();
    }

    @Override
    public void implement(ClassWriter classWriter, PythonCompiledClass compiledClass) {
        for (Method method : interfaceClass.getMethods()) {
            implementMethod(classWriter, compiledClass, method);
        }
    }
}
