package org.optaplanner.jpyinterpreter.implementors;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.optaplanner.jpyinterpreter.FunctionMetadata;
import org.optaplanner.jpyinterpreter.LocalVariableHelper;
import org.optaplanner.jpyinterpreter.StackMetadata;
import org.optaplanner.jpyinterpreter.ValueSourceInfo;

/**
 * Implementations of stack manipulation opcodes (rotations, pop, duplication, etc.)
 */
public class StackManipulationImplementor {

    /**
     * Swaps TOS and TOS1:
     *
     * (i.e. ..., TOS1, TOS -> ..., TOS, TOS1)
     */
    public static void swap(MethodVisitor methodVisitor) {
        methodVisitor.visitInsn(Opcodes.SWAP);
    }

    /**
     * Move TOS down two places, and pushes TOS1 and TOS2 up one:
     *
     * (i.e. ..., TOS2, TOS1, TOS -> ..., TOS, TOS2, TOS1)
     */
    public static void rotateThree(MethodVisitor methodVisitor) {
        methodVisitor.visitInsn(Opcodes.DUP_X2);
        methodVisitor.visitInsn(Opcodes.POP);
    }

    /**
     * Move TOS down three places, and pushes TOS1, TOS2 and TOS3 up one:
     *
     * (i.e. ..., TOS3, TOS2, TOS1, TOS -> ..., TOS, TOS3, TOS2, TOS1)
     */
    public static void rotateFour(FunctionMetadata functionMetadata, StackMetadata stackMetadata) {
        MethodVisitor methodVisitor = functionMetadata.methodVisitor;
        LocalVariableHelper localVariableHelper = stackMetadata.localVariableHelper;

        int secondFromStack = localVariableHelper.newLocal();
        int thirdFromStack = localVariableHelper.newLocal();

        methodVisitor.visitInsn(Opcodes.DUP_X2);
        methodVisitor.visitInsn(Opcodes.POP);

        localVariableHelper.writeTemp(methodVisitor,
                Type.getType(stackMetadata.getTypeAtStackIndex(1).getJavaTypeDescriptor()),
                secondFromStack);
        localVariableHelper.writeTemp(methodVisitor,
                Type.getType(stackMetadata.getTypeAtStackIndex(2).getJavaTypeDescriptor()),
                thirdFromStack);

        methodVisitor.visitInsn(Opcodes.SWAP);

        localVariableHelper.readTemp(methodVisitor,
                Type.getType(stackMetadata.getTypeAtStackIndex(2).getJavaTypeDescriptor()),
                thirdFromStack);
        localVariableHelper.readTemp(methodVisitor,
                Type.getType(stackMetadata.getTypeAtStackIndex(1).getJavaTypeDescriptor()),
                secondFromStack);

        localVariableHelper.freeLocal();
        localVariableHelper.freeLocal();
    }

    /**
     * Pops TOS.
     *
     * (i.e. ..., TOS -> ...)
     */
    public static void popTOS(MethodVisitor methodVisitor) {
        methodVisitor.visitInsn(Opcodes.POP);
    }

    /**
     * Duplicates TOS.
     *
     * (i.e. ..., TOS -> ..., TOS, TOS)
     */
    public static void duplicateTOS(MethodVisitor methodVisitor) {
        methodVisitor.visitInsn(Opcodes.DUP);
    }

    /**
     * Duplicates TOS and TOS1.
     *
     * (i.e. ..., TOS1, TOS -> ..., TOS1, TOS, TOS1, TOS)
     */
    public static void duplicateTOSAndTOS1(MethodVisitor methodVisitor) {
        methodVisitor.visitInsn(Opcodes.DUP2);
    }

    /**
     * Copies TOS[posFromTOS] to TOS, leaving other stack elements in their original place
     *
     * (i.e. ..., TOS[posFromTOS], ..., TOS2, TOS1, TOS -> ..., TOS[posFromTOS], ..., TOS2, TOS1, TOS, TOS[posFromTOS])
     */
    public static void duplicateToTOS(FunctionMetadata functionMetadata, StackMetadata stackMetadata, int posFromTOS) {
        MethodVisitor methodVisitor = functionMetadata.methodVisitor;
        LocalVariableHelper localVariableHelper = stackMetadata.localVariableHelper;
        List<Integer> localList = new ArrayList<>(posFromTOS);

        // Store TOS...TOS[posFromTOS - 1] into local variables
        for (int i = 0; i < posFromTOS; i++) {
            int local = localVariableHelper.newLocal();
            localList.add(local);
            localVariableHelper.writeTemp(methodVisitor,
                    Type.getType(stackMetadata.getTypeAtStackIndex(i).getJavaTypeDescriptor()),
                    local);
        }

        // Duplicate TOS[posFromTOS]
        methodVisitor.visitInsn(Opcodes.DUP);

        // Restore TOS...TOS[posFromTOS - 1] from local variables, swaping the duplicated value to keep it on TOS
        for (int i = posFromTOS - 1; i >= 0; i--) {
            int local = localList.get(i);
            localVariableHelper.readTemp(methodVisitor,
                    Type.getType(stackMetadata.getTypeAtStackIndex(i).getJavaTypeDescriptor()),
                    local);
            methodVisitor.visitInsn(Opcodes.SWAP);
            localVariableHelper.freeLocal();
        }
    }

    /**
     * Copies TOS to TOS[posFromTOS], moving other stack elements up by one
     *
     * (i.e. ..., TOS[posFromTOS], ..., TOS2, TOS1, TOS -> ..., TOS, TOS[posFromTOS] ..., TOS2, TOS1)
     */
    public static StackMetadata shiftTOSDownBy(FunctionMetadata functionMetadata, StackMetadata stackMetadata, int posFromTOS) {
        MethodVisitor methodVisitor = functionMetadata.methodVisitor;
        LocalVariableHelper localVariableHelper = stackMetadata.localVariableHelper;
        List<Integer> localList = new ArrayList<>(posFromTOS + 1);

        if (posFromTOS == 0) {
            // A rotation of 0 is a no-op
            return stackMetadata;
        }

        // Store TOS...TOS[posFromTOS - 1] into local variables
        for (int i = 0; i < posFromTOS + 1; i++) {
            int local = localVariableHelper.newLocal();
            localList.add(local);
            localVariableHelper.writeTemp(methodVisitor,
                    Type.getType(stackMetadata.getTypeAtStackIndex(i).getJavaTypeDescriptor()), local);
        }

        // Copy TOS to this position
        localVariableHelper.readTemp(methodVisitor, Type.getType(stackMetadata.getTypeAtStackIndex(0).getJavaTypeDescriptor()),
                localList.get(0));

        // Restore TOS[1]...TOS[posFromTOS] from local variables
        for (int i = posFromTOS; i > 0; i--) {
            int local = localList.get(i);
            localVariableHelper.readTemp(methodVisitor,
                    Type.getType(stackMetadata.getTypeAtStackIndex(i).getJavaTypeDescriptor()), local);
            localVariableHelper.freeLocal();
        }

        ValueSourceInfo top = stackMetadata.getTOSValueSource();
        StackMetadata out = stackMetadata;
        out = out.pop(posFromTOS + 1);
        out = out.push(top);
        for (int i = posFromTOS; i > 0; i--) {
            out = out.push(stackMetadata.getValueSourceForStackIndex(i));
        }
        return out;
    }

    public static int[] storeStack(MethodVisitor methodVisitor, StackMetadata stackMetadata) {
        int[] stackLocalVariables = new int[stackMetadata.getStackSize()];

        for (int i = stackLocalVariables.length - 1; i >= 0; i--) {
            stackLocalVariables[i] = stackMetadata.localVariableHelper.newLocal();
            stackMetadata.localVariableHelper.writeTemp(methodVisitor,
                    Type.getType(stackMetadata.getTypeAtStackIndex(i).getJavaTypeDescriptor()),
                    stackLocalVariables[i]);
        }

        for (int i = 0; i < stackLocalVariables.length; i++) {
            stackMetadata.localVariableHelper.readTemp(methodVisitor,
                    Type.getType(stackMetadata.getTypeAtStackIndex(i).getJavaTypeDescriptor()),
                    stackLocalVariables[i]);
        }

        return stackLocalVariables;
    }

    public static void restoreStack(MethodVisitor methodVisitor, StackMetadata stackMetadata, int[] stackLocalVariables) {
        for (int i = 0; i < stackLocalVariables.length; i++) {
            stackMetadata.localVariableHelper.readTemp(methodVisitor,
                    Type.getType(stackMetadata.getTypeAtStackIndex(i).getJavaTypeDescriptor()),
                    stackLocalVariables[i]);
        }
    }
}
