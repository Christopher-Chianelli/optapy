package org.optaplanner.jpyinterpreter.implementors;

import java.util.List;
import java.util.Map;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.optaplanner.jpyinterpreter.FunctionMetadata;
import org.optaplanner.jpyinterpreter.LocalVariableHelper;
import org.optaplanner.jpyinterpreter.MethodDescriptor;
import org.optaplanner.jpyinterpreter.PythonDefaultArgumentImplementor;
import org.optaplanner.jpyinterpreter.PythonFunctionSignature;
import org.optaplanner.jpyinterpreter.PythonLikeObject;
import org.optaplanner.jpyinterpreter.StackMetadata;
import org.optaplanner.jpyinterpreter.types.BoundPythonLikeFunction;
import org.optaplanner.jpyinterpreter.types.BuiltinTypes;
import org.optaplanner.jpyinterpreter.types.PythonLikeType;
import org.optaplanner.jpyinterpreter.types.PythonString;
import org.optaplanner.jpyinterpreter.types.collections.PythonLikeDict;
import org.optaplanner.jpyinterpreter.types.collections.PythonLikeTuple;
import org.optaplanner.jpyinterpreter.types.wrappers.JavaObjectWrapper;
import org.optaplanner.jpyinterpreter.util.TypeHelper;
import org.optaplanner.jpyinterpreter.util.arguments.ArgumentSpec;

/**
 * Implements function calls when the function being called is known.
 */
public class KnownCallImplementor {

    static void unwrapBoundMethod(PythonFunctionSignature pythonFunctionSignature, FunctionMetadata functionMetadata,
            StackMetadata stackMetadata, int posFromTOS) {
        MethodVisitor methodVisitor = functionMetadata.methodVisitor;

        if (pythonFunctionSignature.getMethodDescriptor().getMethodType() == MethodDescriptor.MethodType.VIRTUAL ||
                pythonFunctionSignature.getMethodDescriptor().getMethodType() == MethodDescriptor.MethodType.INTERFACE) {
            StackManipulationImplementor.duplicateToTOS(functionMetadata, stackMetadata, posFromTOS);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(BoundPythonLikeFunction.class),
                    "getInstance", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class)),
                    false);
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST,
                    pythonFunctionSignature.getMethodDescriptor().getDeclaringClassInternalName());
            StackManipulationImplementor.shiftTOSDownBy(functionMetadata, stackMetadata, posFromTOS);
        }
    }

    public static void callMethod(PythonFunctionSignature pythonFunctionSignature, MethodVisitor methodVisitor,
            LocalVariableHelper localVariableHelper, int argumentCount) {
        if (pythonFunctionSignature.isClassMethod()) {
            // Class methods will also have their type/instance on the stack, but it not in argumentCount
            argumentCount++;
        }

        int specPositionalArgumentCount = pythonFunctionSignature.getArgumentSpec().getAllowPositionalArgumentCount();
        int missingValues = Math.max(0, specPositionalArgumentCount - argumentCount);

        int[] argumentLocals = new int[specPositionalArgumentCount];
        int capturedExtraPositionalArgumentsLocal = localVariableHelper.newLocal();

        // Create temporary variables for each argument
        for (int i = 0; i < argumentLocals.length; i++) {
            argumentLocals[i] = localVariableHelper.newLocal();
        }

        if (pythonFunctionSignature.getArgumentSpec().hasExtraPositionalArgumentsCapture()) {
            CollectionImplementor.buildCollection(PythonLikeTuple.class, methodVisitor,
                    Math.max(0, argumentCount - specPositionalArgumentCount));
            localVariableHelper.writeTemp(methodVisitor, Type.getType(PythonLikeTuple.class),
                    capturedExtraPositionalArgumentsLocal);
        } else if (argumentCount > specPositionalArgumentCount) {
            throw new IllegalStateException(
                    "Too many positional arguments given for argument spec " + pythonFunctionSignature.getArgumentSpec());
        }

        // Call stack is in reverse, so TOS = argument (specPositionalArgumentCount - missingValues - 1)
        // First store the variables into temporary local variables since we need to typecast them all
        for (int i = specPositionalArgumentCount - missingValues - 1; i >= 0; i--) {
            localVariableHelper.writeTemp(methodVisitor, Type.getType(PythonLikeObject.class),
                    argumentLocals[i]);
        }

        if (pythonFunctionSignature.isVirtualMethod()) {
            // If it is a virtual method, there will be self here, which we need to cast to the declaring class
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST,
                    pythonFunctionSignature.getMethodDescriptor().getDeclaringClassInternalName());
        }

        if (pythonFunctionSignature.isClassMethod()) {
            // If it is a class method, argument 0 need to be converted to a type if it not a type
            localVariableHelper.readTemp(methodVisitor, Type.getType(PythonLikeObject.class),
                    argumentLocals[0]);
            methodVisitor.visitInsn(Opcodes.DUP);
            Label ifIsBoundFunction = new Label();
            Label doneGettingType = new Label();
            methodVisitor.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(BoundPythonLikeFunction.class));
            methodVisitor.visitJumpInsn(Opcodes.IFNE, ifIsBoundFunction);
            methodVisitor.visitInsn(Opcodes.DUP);
            methodVisitor.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(PythonLikeType.class));
            methodVisitor.visitJumpInsn(Opcodes.IFNE, doneGettingType);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                    "__getType", Type.getMethodDescriptor(Type.getType(PythonLikeType.class)),
                    true);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, doneGettingType);
            methodVisitor.visitLabel(ifIsBoundFunction);
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(BoundPythonLikeFunction.class));
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(BoundPythonLikeFunction.class),
                    "getInstance", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class)),
                    false);
            methodVisitor.visitLabel(doneGettingType);
            localVariableHelper.writeTemp(methodVisitor, Type.getType(PythonLikeObject.class), argumentLocals[0]);
        }

        // Now load and typecheck the local variables
        for (int i = 0; i < Math.min(specPositionalArgumentCount, argumentCount); i++) {
            localVariableHelper.readTemp(methodVisitor, Type.getType(PythonLikeObject.class), argumentLocals[i]);
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST,
                    Type.getInternalName(pythonFunctionSignature.getArgumentSpec().getArgumentType(i)));
        }

        // Load any arguments missing values
        for (int i = specPositionalArgumentCount - missingValues; i < specPositionalArgumentCount; i++) {
            if (pythonFunctionSignature.getArgumentSpec().isArgumentNullable(i)) {
                methodVisitor.visitInsn(Opcodes.ACONST_NULL);
            } else {
                methodVisitor.visitFieldInsn(Opcodes.GETSTATIC,
                        Type.getInternalName(pythonFunctionSignature.getDefaultArgumentHolderClass()),
                        PythonDefaultArgumentImplementor.getConstantName(i),
                        Type.getDescriptor(pythonFunctionSignature.getArgumentSpec().getArgumentType(i)));
            }
        }

        // Load *vargs and **kwargs if the function has them
        if (pythonFunctionSignature.getArgumentSpec().hasExtraPositionalArgumentsCapture()) {
            localVariableHelper.readTemp(methodVisitor, Type.getType(PythonLikeTuple.class),
                    capturedExtraPositionalArgumentsLocal);
        }

        if (pythonFunctionSignature.getArgumentSpec().hasExtraKeywordArgumentsCapture()) {
            // No kwargs for call method, so just load an empty map
            CollectionImplementor.buildMap(PythonLikeDict.class, methodVisitor, 0);
        }

        // Call the method
        pythonFunctionSignature.getMethodDescriptor().callMethod(methodVisitor);

        // Free temporary locals for arguments
        for (int i = 0; i < argumentLocals.length; i++) {
            localVariableHelper.freeLocal();
        }
        // Free temporary local for vargs
        localVariableHelper.freeLocal();
    }

    public static void callPython311andAbove(PythonFunctionSignature pythonFunctionSignature, FunctionMetadata functionMetadata,
            StackMetadata stackMetadata, int argumentCount,
            List<String> keywordArgumentNameList) {
        MethodVisitor methodVisitor = functionMetadata.methodVisitor;
        LocalVariableHelper localVariableHelper = stackMetadata.localVariableHelper;

        int specTotalArgumentCount = pythonFunctionSignature.getArgumentSpec().getTotalArgumentCount();
        int positionalArgumentCount = argumentCount - keywordArgumentNameList.size();
        int[] argumentLocals = new int[specTotalArgumentCount];

        // Create temporary variables for each argument
        for (int i = 0; i < argumentLocals.length; i++) {
            argumentLocals[i] = localVariableHelper.newLocal();
        }
        int extraKeywordArgumentsLocal = (pythonFunctionSignature.getArgumentSpec().getExtraKeywordsArgumentIndex().isPresent())
                ? argumentLocals[pythonFunctionSignature.getArgumentSpec().getExtraKeywordsArgumentIndex().get()]
                : -1;
        int extraPositionalArgumentsLocal =
                (pythonFunctionSignature.getArgumentSpec().getExtraPositionalsArgumentIndex().isPresent())
                        ? argumentLocals[pythonFunctionSignature.getArgumentSpec().getExtraPositionalsArgumentIndex().get()]
                        : -1;

        // Read keyword arguments
        if (extraKeywordArgumentsLocal != -1) {
            CollectionImplementor.buildMap(PythonLikeDict.class, methodVisitor, 0);
            localVariableHelper.writeTemp(methodVisitor, Type.getType(PythonLikeDict.class),
                    extraKeywordArgumentsLocal);
        }

        // Read positional arguments
        int positionalArgumentStart = (pythonFunctionSignature.isClassMethod()) ? 1 : 0;

        for (int keywordArgumentNameIndex =
                keywordArgumentNameList.size() - 1; keywordArgumentNameIndex >= 0; keywordArgumentNameIndex--) {
            // Need to iterate keyword name tuple in reverse (since last element of the tuple correspond to TOS)
            String keywordArgument = keywordArgumentNameList.get(keywordArgumentNameIndex);
            int argumentIndex = pythonFunctionSignature.getArgumentSpec().getArgumentIndex(keywordArgument);
            if (argumentIndex == -1) {
                // Unknown keyword argument; put it into the extraKeywordArguments dict
                localVariableHelper.readTemp(methodVisitor, Type.getType(PythonLikeDict.class),
                        extraKeywordArgumentsLocal);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(PythonLikeDict.class));
                methodVisitor.visitInsn(Opcodes.SWAP);
                methodVisitor.visitLdcInsn(keywordArgument);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(PythonString.class),
                        "valueOf", Type.getMethodDescriptor(Type.getType(PythonString.class),
                                Type.getType(String.class)),
                        false);
                methodVisitor.visitInsn(Opcodes.SWAP);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(PythonLikeDict.class),
                        "put", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class),
                                Type.getType(PythonLikeObject.class),
                                Type.getType(PythonLikeObject.class)),
                        false);
            } else {
                localVariableHelper.writeTemp(methodVisitor, Type.getType(PythonLikeObject.class),
                        argumentLocals[argumentIndex]);
            }
        }

        if (extraPositionalArgumentsLocal != -1) {
            CollectionImplementor.buildCollection(PythonLikeTuple.class,
                    methodVisitor,
                    Math.max(0,
                            positionalArgumentCount
                                    - pythonFunctionSignature.getArgumentSpec().getAllowPositionalArgumentCount()
                                    + positionalArgumentStart));
            localVariableHelper.writeTemp(methodVisitor, Type.getType(PythonLikeTuple.class),
                    extraPositionalArgumentsLocal);
        }

        for (int i = Math.min(positionalArgumentCount + positionalArgumentStart,
                pythonFunctionSignature.getArgumentSpec().getAllowPositionalArgumentCount())
                - 1; i >= positionalArgumentStart; i--) {
            localVariableHelper.writeTemp(methodVisitor, Type.getType(PythonLikeObject.class),
                    argumentLocals[i]);
        }

        // Load missing arguments with default values
        int defaultOffset = pythonFunctionSignature.getArgumentSpec().getTotalArgumentCount()
                - pythonFunctionSignature.getDefaultArgumentList().size();
        for (int argumentIndex : pythonFunctionSignature.getArgumentSpec().getUnspecifiedArgumentSet(
                positionalArgumentCount + positionalArgumentStart,
                keywordArgumentNameList)) {
            if (pythonFunctionSignature.getArgumentSpec().isArgumentNullable(argumentIndex)) {
                methodVisitor.visitInsn(Opcodes.ACONST_NULL);
            } else {
                methodVisitor.visitFieldInsn(Opcodes.GETSTATIC,
                        Type.getInternalName(pythonFunctionSignature.getDefaultArgumentHolderClass()),
                        PythonDefaultArgumentImplementor.getConstantName(argumentIndex - defaultOffset),
                        Type.getDescriptor(pythonFunctionSignature.getArgumentSpec().getArgumentType(argumentIndex)));
            }
            localVariableHelper.writeTemp(methodVisitor, Type.getType(PythonLikeObject.class),
                    argumentLocals[argumentIndex]);
        }

        if (pythonFunctionSignature.isVirtualMethod()) {
            // If it is a virtual method, there will be self here, which we need to cast to the declaring class
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST,
                    pythonFunctionSignature.getMethodDescriptor().getDeclaringClassInternalName());
        }

        if (pythonFunctionSignature.isClassMethod()) {
            // If it is a class method, argument 0 need to be converted to a type if it not a type
            methodVisitor.visitInsn(Opcodes.DUP);
            Label ifIsBoundFunction = new Label();
            Label doneGettingType = new Label();
            methodVisitor.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(BoundPythonLikeFunction.class));
            methodVisitor.visitJumpInsn(Opcodes.IFNE, ifIsBoundFunction);
            methodVisitor.visitInsn(Opcodes.DUP);
            methodVisitor.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(PythonLikeType.class));
            methodVisitor.visitJumpInsn(Opcodes.IFNE, doneGettingType);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                    "__getType", Type.getMethodDescriptor(Type.getType(PythonLikeType.class)),
                    true);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, doneGettingType);
            methodVisitor.visitLabel(ifIsBoundFunction);
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(BoundPythonLikeFunction.class));
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(BoundPythonLikeFunction.class),
                    "getInstance", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class)),
                    false);
            methodVisitor.visitLabel(doneGettingType);
            localVariableHelper.writeTemp(methodVisitor, Type.getType(PythonLikeObject.class), argumentLocals[0]);
        }

        // Load arguments in proper order and typecast them
        for (int i = 0; i < specTotalArgumentCount; i++) {
            localVariableHelper.readTemp(methodVisitor, Type.getType(PythonLikeObject.class), argumentLocals[i]);
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST,
                    Type.getInternalName(pythonFunctionSignature.getArgumentSpec().getArgumentType(i)));
        }

        pythonFunctionSignature.getMethodDescriptor().callMethod(methodVisitor);

        // If it not a CLASS method, pop off the function object
        // CLASS method consume the function object; Static and Virtual do not
        if (!pythonFunctionSignature.isClassMethod()) {
            methodVisitor.visitInsn(Opcodes.SWAP);
            methodVisitor.visitInsn(Opcodes.POP);
        }

        // Pop off NULL if it on the stack
        if (stackMetadata.getTypeAtStackIndex(argumentCount + 1) == BuiltinTypes.NULL_TYPE) {
            methodVisitor.visitInsn(Opcodes.SWAP);
            methodVisitor.visitInsn(Opcodes.POP);
        }

        // Free temporary locals for arguments
        for (int i = 0; i < argumentLocals.length; i++) {
            localVariableHelper.freeLocal();
        }
    }

    public static void callWithoutKeywords(PythonFunctionSignature pythonFunctionSignature, FunctionMetadata functionMetadata,
            StackMetadata stackMetadata, int argumentCount) {
        MethodVisitor methodVisitor = functionMetadata.methodVisitor;

        CollectionImplementor.buildCollection(PythonLikeTuple.class, methodVisitor, 0);
        callWithKeywordsAndUnwrapSelf(pythonFunctionSignature, functionMetadata, stackMetadata, argumentCount);
    }

    public static void callWithKeywordsAndUnwrapSelf(PythonFunctionSignature pythonFunctionSignature,
            FunctionMetadata functionMetadata, StackMetadata stackMetadata,
            int argumentCount) {
        callWithKeywords(pythonFunctionSignature, functionMetadata, stackMetadata, argumentCount);
    }

    private static void callWithKeywords(PythonFunctionSignature pythonFunctionSignature, FunctionMetadata functionMetadata,
            StackMetadata stackMetadata,
            int argumentCount) {
        MethodVisitor methodVisitor = functionMetadata.methodVisitor;
        Type[] descriptorParameterTypes = pythonFunctionSignature.getMethodDescriptor().getParameterTypes();

        if (argumentCount < descriptorParameterTypes.length
                && pythonFunctionSignature.getDefaultArgumentHolderClass() == null) {
            throw new IllegalStateException(
                    "Cannot call " + pythonFunctionSignature + " because there are not enough arguments");
        }

        if (argumentCount > descriptorParameterTypes.length
                && pythonFunctionSignature.getExtraPositionalArgumentsVariableIndex().isEmpty()
                && pythonFunctionSignature.getExtraKeywordArgumentsVariableIndex().isEmpty()) {
            throw new IllegalStateException("Cannot call " + pythonFunctionSignature + " because there are too many arguments");
        }

        unwrapBoundMethod(pythonFunctionSignature, functionMetadata, stackMetadata, argumentCount + 1);

        if (pythonFunctionSignature.isClassMethod()) {
            argumentCount++;
        }

        // TOS is a tuple of keys
        methodVisitor.visitTypeInsn(Opcodes.NEW, Type.getInternalName(pythonFunctionSignature.getDefaultArgumentHolderClass()));
        methodVisitor.visitInsn(Opcodes.DUP_X1);
        methodVisitor.visitInsn(Opcodes.SWAP);

        // Stack is defaults (uninitialized), keys

        // Get position of last positional arg (= argumentCount - len(keys) - 1 )
        methodVisitor.visitInsn(Opcodes.DUP); // dup keys
        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(PythonLikeTuple.class), "size",
                Type.getMethodDescriptor(Type.INT_TYPE), false);
        methodVisitor.visitLdcInsn(argumentCount);
        methodVisitor.visitInsn(Opcodes.SWAP);
        methodVisitor.visitInsn(Opcodes.ISUB);

        methodVisitor.visitInsn(Opcodes.ICONST_1);
        methodVisitor.visitInsn(Opcodes.ISUB);

        // Stack is defaults (uninitialized), keys, positional arguments
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                Type.getInternalName(pythonFunctionSignature.getDefaultArgumentHolderClass()),
                "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(PythonLikeTuple.class), Type.INT_TYPE),
                false);

        for (int i = 0; i < argumentCount; i++) {
            methodVisitor.visitInsn(Opcodes.DUP_X1);
            methodVisitor.visitInsn(Opcodes.SWAP);
            if (pythonFunctionSignature.isClassMethod() && i == argumentCount - 1) {
                methodVisitor.visitInsn(Opcodes.DUP);
                Label ifIsBoundFunction = new Label();
                Label doneGettingType = new Label();
                methodVisitor.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(BoundPythonLikeFunction.class));
                methodVisitor.visitJumpInsn(Opcodes.IFNE, ifIsBoundFunction);
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(PythonLikeType.class));
                methodVisitor.visitJumpInsn(Opcodes.IFNE, doneGettingType);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                        "__getType", Type.getMethodDescriptor(Type.getType(PythonLikeType.class)),
                        true);
                methodVisitor.visitJumpInsn(Opcodes.GOTO, doneGettingType);
                methodVisitor.visitLabel(ifIsBoundFunction);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(BoundPythonLikeFunction.class));
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(BoundPythonLikeFunction.class),
                        "getInstance", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class)),
                        false);
                methodVisitor.visitLabel(doneGettingType);
            }
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName(pythonFunctionSignature.getDefaultArgumentHolderClass()),
                    "addArgument", Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(PythonLikeObject.class)),
                    false);
        }

        for (int i = 0; i < descriptorParameterTypes.length; i++) {
            methodVisitor.visitInsn(Opcodes.DUP);
            methodVisitor.visitFieldInsn(Opcodes.GETFIELD,
                    Type.getInternalName(pythonFunctionSignature.getDefaultArgumentHolderClass()),
                    PythonDefaultArgumentImplementor.getArgumentName(i),
                    descriptorParameterTypes[i].getDescriptor());
            methodVisitor.visitInsn(Opcodes.SWAP);
        }
        methodVisitor.visitInsn(Opcodes.POP);

        pythonFunctionSignature.getMethodDescriptor().callMethod(methodVisitor);
    }

    public static void callUnpackListAndMap(PythonLikeType type, Class<?> defaultArgumentHolderClass, MethodDescriptor methodDescriptor,
            MethodVisitor methodVisitor) {
        boolean isJavaClass = type.getJavaClassOrDefault(PythonLikeObject.class).equals(JavaObjectWrapper.class);
        Type[] descriptorParameterTypes = methodDescriptor.getParameterTypes();

        // TOS2 is the function to call, TOS1 is positional arguments, TOS is keyword arguments
        if (methodDescriptor.getMethodType() == MethodDescriptor.MethodType.CLASS) {
            // stack is bound-method, pos, keywords
            StackManipulationImplementor.rotateThree(methodVisitor);
            // stack is keywords, bound-method, pos
            StackManipulationImplementor.swap(methodVisitor);

            // stack is keywords, pos, bound-method
            methodVisitor.visitInsn(Opcodes.DUP_X2);

            // stack is bound-method, keywords, pos, bound-method
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(BoundPythonLikeFunction.class),
                    "getInstance", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class)),
                    false);

            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(PythonLikeType.class));
            // stack is bound-method, keywords, pos, type

            methodVisitor.visitInsn(Opcodes.DUP2);

            // stack is bound-method, keywords, pos, type, pos, type
            methodVisitor.visitInsn(Opcodes.ICONST_0);
            methodVisitor.visitInsn(Opcodes.SWAP);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(List.class), "add",
                    Type.getMethodDescriptor(Type.VOID_TYPE, Type.INT_TYPE, Type.getType(Object.class)),
                    true);
            // stack is bound-method, keywords, pos, type
            methodVisitor.visitInsn(Opcodes.POP);
            methodVisitor.visitInsn(Opcodes.SWAP);

            // stack is bound-method, pos, keywords
        }

        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(defaultArgumentHolderClass),
                PythonDefaultArgumentImplementor.ARGUMENT_SPEC_STATIC_FIELD_NAME,
                Type.getDescriptor(ArgumentSpec.class));

        methodVisitor.visitInsn(Opcodes.DUP_X2);
        methodVisitor.visitInsn(Opcodes.POP);

        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(ArgumentSpec.class),
                "extractArgumentList", Type.getMethodDescriptor(Type.getType(List.class),
                        Type.getType(List.class), Type.getType(Map.class)),
                false);

        // Stack is function to call, argument list
        // Unwrap the bound method
        if (methodDescriptor.getMethodType() == MethodDescriptor.MethodType.VIRTUAL ||
                methodDescriptor.getMethodType() == MethodDescriptor.MethodType.INTERFACE) {
            methodVisitor.visitInsn(Opcodes.SWAP);
            methodVisitor.visitInsn(Opcodes.DUP_X1);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(BoundPythonLikeFunction.class),
                    "getInstance", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class)),
                    false);

            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, methodDescriptor.getDeclaringClassInternalName());
            methodVisitor.visitInsn(Opcodes.SWAP);
        }

        // Stack is method, boundedInstance?, default

        // Read the parameters
        for (int i = 0; i < descriptorParameterTypes.length; i++) {
            methodVisitor.visitInsn(Opcodes.DUP);
            methodVisitor.visitLdcInsn(i);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(List.class),
                    "get", Type.getMethodDescriptor(Type.getType(Object.class), Type.INT_TYPE),
                    true);
            Type javaParameterType = descriptorParameterTypes[i];
            if (isJavaClass) {
                if (!TypeHelper.getBoxedType(javaParameterType).equals(javaParameterType)) {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(PythonLikeObject.class));
                    Type boxedType = TypeHelper.getBoxedType(javaParameterType);
                    methodVisitor.visitLdcInsn(boxedType);
                    methodVisitor.visitInsn(Opcodes.SWAP);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JavaPythonTypeConversionImplementor.class),
                            "convertPythonObjectToJavaType", Type.getMethodDescriptor(Type.getType(Object.class), Type.getType(Class.class), Type.getType(PythonLikeObject.class)),
                            false);
                    TypeHelper.unboxWithCast(methodVisitor, javaParameterType);
                    if (javaParameterType.equals(Type.LONG_TYPE) || javaParameterType.equals(Type.DOUBLE_TYPE)) {
                        methodVisitor.visitInsn(Opcodes.DUP2_X1);
                        methodVisitor.visitInsn(Opcodes.POP2);
                    } else {
                        methodVisitor.visitInsn(Opcodes.SWAP);
                    }
                } else {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, javaParameterType.getInternalName());
                    methodVisitor.visitInsn(Opcodes.SWAP);
                }
            } else {
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, javaParameterType.getInternalName());
                methodVisitor.visitInsn(Opcodes.SWAP);
            }
        }
        methodVisitor.visitInsn(Opcodes.POP);

        // Stack is method, boundedInstance?, arg0, arg1, ...

        methodDescriptor.callMethod(methodVisitor);

        if (methodDescriptor.getReturnType().equals(Type.VOID_TYPE)) {
            methodVisitor.visitInsn(Opcodes.ACONST_NULL);
        }

        // Stack is method, result
        if (isJavaClass) {
            TypeHelper.box(methodVisitor, methodDescriptor.getReturnType());
            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(JavaPythonTypeConversionImplementor.class),
                    "wrapJavaObject", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class), Type.getType(Object.class)),
                    false);
        }
    }
}
