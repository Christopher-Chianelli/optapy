package org.optaplanner.jpyinterpreter;

import static org.optaplanner.jpyinterpreter.PythonBytecodeToJavaBytecodeTranslator.ANNOTATION_DIRECTORY_INSTANCE_FIELD_NAME;
import static org.optaplanner.jpyinterpreter.PythonBytecodeToJavaBytecodeTranslator.CELLS_INSTANCE_FIELD_NAME;
import static org.optaplanner.jpyinterpreter.PythonBytecodeToJavaBytecodeTranslator.DEFAULT_KEYWORD_ARGS_INSTANCE_FIELD_NAME;
import static org.optaplanner.jpyinterpreter.PythonBytecodeToJavaBytecodeTranslator.DEFAULT_POSITIONAL_ARGS_INSTANCE_FIELD_NAME;
import static org.optaplanner.jpyinterpreter.PythonBytecodeToJavaBytecodeTranslator.INTERPRETER_INSTANCE_FIELD_NAME;
import static org.optaplanner.jpyinterpreter.PythonBytecodeToJavaBytecodeTranslator.QUALIFIED_NAME_INSTANCE_FIELD_NAME;
import static org.optaplanner.jpyinterpreter.PythonBytecodeToJavaBytecodeTranslator.USER_PACKAGE_BASE;
import static org.optaplanner.jpyinterpreter.PythonBytecodeToJavaBytecodeTranslator.classNameToSharedInstanceCount;
import static org.optaplanner.jpyinterpreter.PythonBytecodeToJavaBytecodeTranslator.getInitialStackMetadata;
import static org.optaplanner.jpyinterpreter.PythonBytecodeToJavaBytecodeTranslator.getOpcodeList;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.optaplanner.jpyinterpreter.dag.FlowGraph;
import org.optaplanner.jpyinterpreter.implementors.DunderOperatorImplementor;
import org.optaplanner.jpyinterpreter.implementors.FunctionImplementor;
import org.optaplanner.jpyinterpreter.implementors.GeneratorImplementor;
import org.optaplanner.jpyinterpreter.implementors.PythonConstantsImplementor;
import org.optaplanner.jpyinterpreter.implementors.VariableImplementor;
import org.optaplanner.jpyinterpreter.opcodes.AbstractOpcode;
import org.optaplanner.jpyinterpreter.opcodes.Opcode;
import org.optaplanner.jpyinterpreter.opcodes.generator.GeneratorStartOpcode;
import org.optaplanner.jpyinterpreter.opcodes.generator.YieldFromOpcode;
import org.optaplanner.jpyinterpreter.opcodes.generator.YieldValueOpcode;
import org.optaplanner.jpyinterpreter.types.BuiltinTypes;
import org.optaplanner.jpyinterpreter.types.PythonCell;
import org.optaplanner.jpyinterpreter.types.PythonGenerator;
import org.optaplanner.jpyinterpreter.types.PythonLikeType;
import org.optaplanner.jpyinterpreter.types.PythonString;
import org.optaplanner.jpyinterpreter.types.collections.PythonLikeDict;
import org.optaplanner.jpyinterpreter.types.collections.PythonLikeTuple;
import org.optaplanner.jpyinterpreter.types.errors.StopIteration;
import org.optaplanner.jpyinterpreter.util.JavaPythonClassWriter;
import org.optaplanner.jpyinterpreter.util.MethodVisitorAdapters;

public class PythonGeneratorTranslator {
    // Needed since value from return is used for StopIteration, meaning to check if a generator has more values
    // we need to progress it to the next yield/return to determine if it has a next value
    private static final String SHOULD_PROGRESS_GENERATOR = "$shouldProgressGenerator";

    // Remembers where the generator was last yielded at
    // -1 if the generator hits a return. 0 if generator.__next__() has not been called yet
    public static final String GENERATOR_STATE = "$generatorState";

    // Stack of the generator after it yield a value
    public static final String GENERATOR_STACK = "$generatorStack";

    // The last value yielded by the generator
    public static final String YIELDED_VALUE = "$yieldedValue";

    // The iterator to yield values from, as well as to delegate "send" and "next" calls to
    public static final String YIELD_FROM_ITERATOR = "$yieldFromIterator";

    // The last exception catch by the generator
    public static final String CURRENT_EXCEPTION = "$currentException";

    // Called to advance the generator
    private static final String PROGRESS_GENERATOR = "progressGenerator";

    public static Class<?> translateGeneratorFunction(PythonCompiledFunction pythonCompiledFunction) {
        String maybeClassName = USER_PACKAGE_BASE + pythonCompiledFunction.getGeneratedClassBaseName() + "$Generator";
        int numberOfInstances = classNameToSharedInstanceCount.merge(maybeClassName, 1, Integer::sum);
        if (numberOfInstances > 1) {
            maybeClassName = maybeClassName + "$$" + numberOfInstances;
        }
        String className = maybeClassName;
        String internalClassName = className.replace('.', '/');

        ClassWriter classWriter = new JavaPythonClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

        classWriter.visit(Opcodes.V11, Modifier.PUBLIC, internalClassName, null,
                Type.getInternalName(PythonGenerator.class), null);

        // Create fields for generator state
        classWriter.visitField(Modifier.PRIVATE, SHOULD_PROGRESS_GENERATOR,
                Type.BOOLEAN_TYPE.getDescriptor(),
                null, null);
        classWriter.visitField(Modifier.PRIVATE, GENERATOR_STATE,
                Type.INT_TYPE.getDescriptor(),
                null, null);
        classWriter.visitField(Modifier.PRIVATE, GENERATOR_STACK,
                Type.getDescriptor(List.class),
                null, null);
        classWriter.visitField(Modifier.PRIVATE, YIELDED_VALUE,
                Type.getDescriptor(PythonLikeObject.class),
                null, null);
        classWriter.visitField(Modifier.PRIVATE, YIELD_FROM_ITERATOR,
                Type.getDescriptor(PythonLikeObject.class),
                null, null);
        classWriter.visitField(Modifier.PRIVATE, CURRENT_EXCEPTION,
                Type.getDescriptor(Throwable.class),
                null, null);

        // Create fields for translated functions
        PythonBytecodeToJavaBytecodeTranslator.createFields(classWriter);

        // Create fields for parameters, cells and local variables
        {
            // Cannot use parameter types as the type descriptor, since the variables assigned to the
            // Python parameter can change types in the middle of code
            for (int variable = 0; variable < pythonCompiledFunction.co_varnames.size(); variable++) {
                classWriter.visitField(Modifier.PRIVATE, pythonCompiledFunction.co_varnames.get(variable),
                        Type.getDescriptor(PythonLikeObject.class),
                        null, null);
            }
            for (int i = 0; i < pythonCompiledFunction.co_cellvars.size(); i++) {
                classWriter.visitField(Modifier.PRIVATE, pythonCompiledFunction.co_cellvars.get(i),
                        Type.getDescriptor(PythonCell.class),
                        null, null);
            }
            for (int i = 0; i < pythonCompiledFunction.co_freevars.size(); i++) {
                classWriter.visitField(Modifier.PRIVATE, pythonCompiledFunction.co_freevars.get(i),
                        Type.getDescriptor(PythonCell.class),
                        null, null);
            }
        }

        Type[] javaParameterTypes = Stream.concat(Stream.of(Type.getType(PythonLikeTuple.class),
                Type.getType(PythonLikeDict.class),
                Type.getType(PythonLikeDict.class),
                Type.getType(PythonLikeTuple.class),
                Type.getType(PythonString.class),
                Type.getType(PythonInterpreter.class)),
                pythonCompiledFunction.getParameterTypes().stream()
                        .map(type -> Type.getType(type.getJavaTypeDescriptor())))
                .toArray(Type[]::new);
        // Create constructor that sets parameters and initial generator state
        MethodVisitor methodVisitor =
                classWriter.visitMethod(Modifier.PUBLIC, "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, javaParameterTypes),
                        null, null);
        methodVisitor.visitCode();

        // Call super
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(PythonGenerator.class), "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE),
                false);

        // Positional only and Positional/Keyword default arguments
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, DEFAULT_POSITIONAL_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        // Keyword only default arguments
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 2);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, DEFAULT_KEYWORD_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));

        // Annotation Directory as key/value tuple
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 3);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, ANNOTATION_DIRECTORY_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));

        // Free variable cells
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 4);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, CELLS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        // Function name
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 5);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, QUALIFIED_NAME_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonString.class));

        // Interpreter
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 6);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, INTERPRETER_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonInterpreter.class));

        // Set initial generator state
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitLdcInsn(true);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, SHOULD_PROGRESS_GENERATOR,
                Type.BOOLEAN_TYPE.getDescriptor());

        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitLdcInsn(0);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, GENERATOR_STATE,
                Type.INT_TYPE.getDescriptor());

        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitInsn(Opcodes.ACONST_NULL);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, GENERATOR_STACK,
                Type.getDescriptor(List.class));

        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitInsn(Opcodes.ACONST_NULL);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, YIELDED_VALUE,
                Type.getDescriptor(PythonLikeObject.class));

        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitInsn(Opcodes.ACONST_NULL);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, YIELD_FROM_ITERATOR,
                Type.getDescriptor(PythonLikeObject.class));

        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitInsn(Opcodes.ACONST_NULL);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, CURRENT_EXCEPTION,
                Type.getDescriptor(Throwable.class));

        // Set parameters
        {
            for (int parameter = 0; parameter < pythonCompiledFunction.getParameterTypes().size(); parameter++) {
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, parameter + 7);
                methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName,
                        pythonCompiledFunction.co_varnames.get(parameter),
                        Type.getDescriptor(PythonLikeObject.class));
            }
        }

        GeneratorLocalVariableHelper localVariableHelper =
                new GeneratorLocalVariableHelper(classWriter, internalClassName, new Type[] {}, pythonCompiledFunction);

        // Load cells
        for (int i = 0; i < localVariableHelper.getNumberOfBoundCells(); i++) {
            VariableImplementor.createCell(methodVisitor, localVariableHelper, i);
        }

        for (int i = 0; i < localVariableHelper.getNumberOfFreeCells(); i++) {
            VariableImplementor.setupFreeVariableCell(methodVisitor, internalClassName, localVariableHelper, i);
        }

        methodVisitor.visitInsn(Opcodes.RETURN);

        methodVisitor.visitMaxs(-1, -1);
        methodVisitor.visitEnd();

        generateHasNext(classWriter, internalClassName, pythonCompiledFunction);
        generateNext(classWriter, internalClassName, pythonCompiledFunction);

        Map<Integer, GeneratorMethodPart> generatorStateToMethodPart =
                createGeneratorStateToMethod(classWriter, internalClassName, pythonCompiledFunction);
        generateProgressGenerator(classWriter, internalClassName, generatorStateToMethodPart);
        generateAdvanceGeneratorMethods(classWriter, internalClassName, generatorStateToMethodPart);

        classWriter.visitEnd();

        PythonBytecodeToJavaBytecodeTranslator.writeClassOutput(BuiltinTypes.classNameToBytecode, className,
                classWriter.toByteArray());
        try {
            Class<?> out = BuiltinTypes.asmClassLoader.loadClass(className);
            PythonBytecodeToJavaBytecodeTranslator.setStaticFields(out, pythonCompiledFunction);
            return out;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot load class " + className + " despite it being just generated.", e);
        }
    }

    private static void generateHasNext(ClassWriter classWriter, String internalClassName,
            PythonCompiledFunction pythonCompiledFunction) {
        MethodVisitor methodVisitor = MethodVisitorAdapters
                .adapt(classWriter.visitMethod(Modifier.PUBLIC, "hasNext", Type.getMethodDescriptor(Type.BOOLEAN_TYPE),
                        null, null), "hasNext", Type.getMethodDescriptor(Type.BOOLEAN_TYPE));
        methodVisitor.visitCode();

        Label checkIfGeneratorEnded = new Label();

        // Check if we need to progress generator
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, SHOULD_PROGRESS_GENERATOR,
                Type.BOOLEAN_TYPE.getDescriptor());
        methodVisitor.visitJumpInsn(Opcodes.IFEQ, checkIfGeneratorEnded);

        // We need to progress generator
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internalClassName, PROGRESS_GENERATOR,
                Type.getMethodDescriptor(Type.VOID_TYPE), false);
        // generator been progressed, so future hasNext calls (until next is called) should not progress generator
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitInsn(Opcodes.ICONST_0);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, SHOULD_PROGRESS_GENERATOR,
                Type.BOOLEAN_TYPE.getDescriptor());

        methodVisitor.visitLabel(checkIfGeneratorEnded);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, GENERATOR_STATE,
                Type.INT_TYPE.getDescriptor());
        methodVisitor.visitLdcInsn(-1);

        Label generatorEnded = new Label();

        methodVisitor.visitJumpInsn(Opcodes.IF_ICMPEQ, generatorEnded);
        // generator state is not -1, so there are more values
        methodVisitor.visitInsn(Opcodes.ICONST_1);
        methodVisitor.visitInsn(Opcodes.IRETURN);

        methodVisitor.visitLabel(generatorEnded);
        // generator state is -1, so there are no more values
        methodVisitor.visitInsn(Opcodes.ICONST_0);
        methodVisitor.visitInsn(Opcodes.IRETURN);

        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();
    }

    private static void generateNext(ClassWriter classWriter, String internalClassName,
            PythonCompiledFunction pythonCompiledFunction) {
        MethodVisitor methodVisitor = MethodVisitorAdapters
                .adapt(classWriter.visitMethod(Modifier.PUBLIC, "next", Type.getMethodDescriptor(Type.getType(Object.class)),
                        null, null), "next", Type.getMethodDescriptor(Type.getType(Object.class)));
        methodVisitor.visitCode();

        Label returnYieldedValue = new Label();

        // Check if we need to progress generator
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, SHOULD_PROGRESS_GENERATOR,
                Type.BOOLEAN_TYPE.getDescriptor());
        methodVisitor.visitJumpInsn(Opcodes.IFEQ, returnYieldedValue);

        // We need to progress generator
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internalClassName, PROGRESS_GENERATOR,
                Type.getMethodDescriptor(Type.VOID_TYPE), false);

        methodVisitor.visitLabel(returnYieldedValue);

        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, GENERATOR_STATE,
                Type.INT_TYPE.getDescriptor());
        methodVisitor.visitLdcInsn(-1);

        Label generatorEnded = new Label();

        methodVisitor.visitJumpInsn(Opcodes.IF_ICMPEQ, generatorEnded);
        // generator state is not -1, so there are more values
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitInsn(Opcodes.DUP);

        // next call should progress generator
        methodVisitor.visitInsn(Opcodes.ICONST_1);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, SHOULD_PROGRESS_GENERATOR,
                Type.BOOLEAN_TYPE.getDescriptor());

        // get the yielded value and return it
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, YIELDED_VALUE,
                Type.getDescriptor(PythonLikeObject.class));
        methodVisitor.visitInsn(Opcodes.ARETURN);

        methodVisitor.visitLabel(generatorEnded);

        // generator state is -1, so there are no more values
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitInsn(Opcodes.DUP);

        // next call should not progress generator, since it ended
        methodVisitor.visitInsn(Opcodes.ICONST_0);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, SHOULD_PROGRESS_GENERATOR,
                Type.BOOLEAN_TYPE.getDescriptor());

        // We need to throw StopIteration with the return value, which is stored in YIELDED_VALUE
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, YIELDED_VALUE,
                Type.getDescriptor(PythonLikeObject.class));
        methodVisitor.visitTypeInsn(Opcodes.NEW, Type.getInternalName(StopIteration.class));
        methodVisitor.visitInsn(Opcodes.DUP_X1);
        methodVisitor.visitInsn(Opcodes.SWAP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(StopIteration.class),
                "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(PythonLikeObject.class)),
                false);
        methodVisitor.visitInsn(Opcodes.ATHROW);

        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();
    }

    private static void generateProgressGenerator(ClassWriter classWriter, String internalClassName,
            Map<Integer, GeneratorMethodPart> generatorStateToMethodPartMap) {
        MethodVisitor methodVisitor = MethodVisitorAdapters
                .adapt(classWriter.visitMethod(Modifier.PRIVATE, PROGRESS_GENERATOR, Type.getMethodDescriptor(Type.VOID_TYPE),
                        null, null), PROGRESS_GENERATOR, Type.getMethodDescriptor(Type.VOID_TYPE));

        methodVisitor.visitCode();

        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, GENERATOR_STATE, Type.INT_TYPE.getDescriptor());

        BytecodeSwitchImplementor.createIntSwitch(methodVisitor, generatorStateToMethodPartMap.keySet(), generatorState -> {
            GeneratorMethodPart generatorMethodPart = generatorStateToMethodPartMap.get(generatorState);

            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            generatorMethodPart.functionMetadata.method.callMethod(methodVisitor);
        }, () -> {
        }, false);

        methodVisitor.visitInsn(Opcodes.RETURN);

        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();
    }

    private static void generateAdvanceGeneratorMethods(ClassWriter classWriter, String internalClassName,
            Map<Integer, GeneratorMethodPart> generatorStateToMethodPartMap) {
        generatorStateToMethodPartMap.values().forEach(
                generatorMethodPart -> generateAdvanceGeneratorMethod(classWriter, internalClassName, generatorMethodPart));
    }

    private static void generateAdvanceGeneratorMethod(ClassWriter classWriter, String internalClassName,
            GeneratorMethodPart generatorMethodPart) {
        switch (generatorMethodPart.instruction.opcode) {
            case YIELD_VALUE:
                generateAdvanceGeneratorMethodForYieldValue(classWriter, internalClassName, generatorMethodPart);
                return;

            case YIELD_FROM:
                generateAdvanceGeneratorMethodForYieldFrom(classWriter, internalClassName, generatorMethodPart);
                return;

            default:
                throw new IllegalArgumentException("Invalid opcode for instruction: " + generatorMethodPart.instruction.opcode);
        }
    }

    private static void generateAdvanceGeneratorMethodForYieldValue(ClassWriter classWriter, String internalClassName,
            GeneratorMethodPart generatorMethodPart) {
        MethodVisitor methodVisitor = generatorMethodPart.functionMetadata.methodVisitor;
        List<Opcode> opcodeList = getOpcodeList(generatorMethodPart.functionMetadata.pythonCompiledFunction);

        // First, restore stack
        methodVisitor.visitCode();

        GeneratorImplementor.restoreGeneratorState(generatorMethodPart.functionMetadata,
                generatorMethodPart.initialStackMetadata);

        if (generatorMethodPart.afterYield != 0
                || (opcodeList.size() > 0 && opcodeList.get(0) instanceof GeneratorStartOpcode)) {
            // Push the sent value to the stack
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            methodVisitor.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(PythonGenerator.class), "sentValue",
                    Type.getDescriptor(PythonLikeObject.class));

            // Set the sent value to None
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            PythonConstantsImplementor.loadNone(methodVisitor);
            methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, Type.getInternalName(PythonGenerator.class), "sentValue",
                    Type.getDescriptor(PythonLikeObject.class));
        }

        // Now generate bytecode for method
        StackMetadata initialStackMetadata =
                getInitialStackMetadata(generatorMethodPart.initialStackMetadata.localVariableHelper,
                        generatorMethodPart.originalMethodDescriptor, false);
        FlowGraph flowGraph = FlowGraph.createFlowGraph(generatorMethodPart.functionMetadata, initialStackMetadata, opcodeList);
        List<StackMetadata> stackMetadataForOpcodeIndex = flowGraph.getStackMetadataForOperations();

        if (generatorMethodPart.afterYield != 0) {
            Label afterYieldLabel = new Label();
            generatorMethodPart.functionMetadata.bytecodeCounterToLabelMap.put(generatorMethodPart.afterYield, afterYieldLabel);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, afterYieldLabel);
        }

        for (int i = 0; i < opcodeList.size(); i++) {
            StackMetadata stackMetadata = stackMetadataForOpcodeIndex.get(i);
            PythonBytecodeInstruction instruction =
                    generatorMethodPart.functionMetadata.pythonCompiledFunction.instructionList.get(i);

            if (instruction.isJumpTarget || instruction.offset == generatorMethodPart.afterYield) {
                Label label = generatorMethodPart.functionMetadata.bytecodeCounterToLabelMap.computeIfAbsent(instruction.offset,
                        offset -> new Label());
                methodVisitor.visitLabel(label);
            }

            if (instruction.offset == generatorMethodPart.afterYield) {
                // Put thrownValue on TOS
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(PythonGenerator.class), "thrownValue",
                        Type.getDescriptor(Throwable.class));

                // Set thrownValue to null
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, Type.getInternalName(PythonGenerator.class), "thrownValue",
                        Type.getDescriptor(Throwable.class));

                // Duplicate top
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitInsn(Opcodes.ACONST_NULL);

                Label doNotThrowException = new Label();
                methodVisitor.visitJumpInsn(Opcodes.IF_ACMPEQ, doNotThrowException); // If thrownValue is null, continue

                // else, raise thrownValue
                methodVisitor.visitInsn(Opcodes.ATHROW);

                methodVisitor.visitLabel(doNotThrowException); // continue as normal
                methodVisitor.visitInsn(Opcodes.POP); // Pop top, since it was not an exception
            }

            generatorMethodPart.functionMetadata.bytecodeCounterToCodeArgumenterList.getOrDefault(instruction.offset, List.of())
                    .forEach(Runnable::run);

            if (stackMetadata.isDeadCode()) {
                continue;
            }

            opcodeList.get(i).implement(generatorMethodPart.functionMetadata, stackMetadata);
        }

        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();
    }

    private static void generateAdvanceGeneratorMethodForYieldFrom(ClassWriter classWriter, String internalClassName,
            GeneratorMethodPart generatorMethodPart) {
        MethodVisitor methodVisitor = generatorMethodPart.functionMetadata.methodVisitor;

        methodVisitor.visitCode();

        // Generate bytecode for method
        StackMetadata initialStackMetadata =
                getInitialStackMetadata(generatorMethodPart.initialStackMetadata.localVariableHelper,
                        generatorMethodPart.originalMethodDescriptor, false);
        List<Opcode> opcodeList = getOpcodeList(generatorMethodPart.functionMetadata.pythonCompiledFunction);
        FlowGraph flowGraph = FlowGraph.createFlowGraph(generatorMethodPart.functionMetadata, initialStackMetadata, opcodeList);
        List<StackMetadata> stackMetadataForOpcodeIndex = flowGraph.getStackMetadataForOperations();

        if (generatorMethodPart.afterYield != 0) {
            Label afterYieldLabel = new Label();
            generatorMethodPart.functionMetadata.bytecodeCounterToLabelMap.put(generatorMethodPart.afterYield, afterYieldLabel);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, afterYieldLabel);
        }

        for (int i = 0; i < opcodeList.size(); i++) {
            StackMetadata stackMetadata = stackMetadataForOpcodeIndex.get(i);
            PythonBytecodeInstruction instruction =
                    generatorMethodPart.functionMetadata.pythonCompiledFunction.instructionList.get(i);

            if (instruction.isJumpTarget || instruction.offset == generatorMethodPart.afterYield) {
                Label label = generatorMethodPart.functionMetadata.bytecodeCounterToLabelMap.computeIfAbsent(instruction.offset,
                        offset -> new Label());
                methodVisitor.visitLabel(label);
            }

            if (instruction.offset == generatorMethodPart.afterYield) {
                // 0 = next, 1 = send, 2 = throw

                Label wasNotSentValue = new Label();
                Label wasNotThrownValue = new Label();
                Label iterateSubiterator = new Label();

                // Push subiterator
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, YIELD_FROM_ITERATOR,
                        Type.getDescriptor(PythonLikeObject.class));

                // Check if sent a value
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(PythonGenerator.class), "sentValue",
                        Type.getDescriptor(PythonLikeObject.class));
                PythonConstantsImplementor.loadNone(methodVisitor);

                methodVisitor.visitJumpInsn(Opcodes.IF_ACMPEQ, wasNotSentValue);

                methodVisitor.visitLdcInsn(1);
                methodVisitor.visitJumpInsn(Opcodes.GOTO, iterateSubiterator);

                methodVisitor.visitLabel(wasNotSentValue);

                // Check if thrown a value
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(PythonGenerator.class), "thrownValue",
                        Type.getDescriptor(Throwable.class));
                methodVisitor.visitInsn(Opcodes.ACONST_NULL);

                methodVisitor.visitJumpInsn(Opcodes.IF_ACMPEQ, wasNotThrownValue);

                methodVisitor.visitLdcInsn(2);
                methodVisitor.visitJumpInsn(Opcodes.GOTO, iterateSubiterator);

                methodVisitor.visitLabel(wasNotThrownValue);

                methodVisitor.visitLdcInsn(0);

                methodVisitor.visitLabel(iterateSubiterator);

                Label tryStartLabel = new Label();
                Label tryEndLabel = new Label();
                Label catchStartLabel = new Label();
                Label catchEndLabel = new Label();

                methodVisitor.visitTryCatchBlock(tryStartLabel, tryEndLabel, catchStartLabel,
                        Type.getInternalName(StopIteration.class));

                methodVisitor.visitLabel(tryStartLabel);
                BytecodeSwitchImplementor.createIntSwitch(methodVisitor, List.of(0, 1, 2),
                        key -> {
                            Label generatorOperationDone = new Label();
                            switch (key) {
                                case 0: { // next
                                    DunderOperatorImplementor.unaryOperator(methodVisitor, PythonUnaryOperator.NEXT);
                                    break;
                                }
                                case 1: { // send
                                    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                                    methodVisitor.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(PythonGenerator.class),
                                            "sentValue",
                                            Type.getDescriptor(PythonLikeObject.class));
                                    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                                    PythonConstantsImplementor.loadNone(methodVisitor);
                                    methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, Type.getInternalName(PythonGenerator.class),
                                            "sentValue",
                                            Type.getDescriptor(PythonLikeObject.class));
                                    FunctionImplementor.callBinaryMethod(methodVisitor,
                                            PythonBinaryOperators.SEND.dunderMethod);
                                    break;
                                }
                                case 2: { // throw
                                    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                                    methodVisitor.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(PythonGenerator.class),
                                            "thrownValue",
                                            Type.getDescriptor(Throwable.class));
                                    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                                    methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                                    methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, Type.getInternalName(PythonGenerator.class),
                                            "thrownValue",
                                            Type.getDescriptor(Throwable.class));

                                    methodVisitor.visitInsn(Opcodes.SWAP);
                                    // Stack is now Throwable, Generator

                                    // Check if the subgenerator has a "throw" method
                                    methodVisitor.visitInsn(Opcodes.DUP);
                                    methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                                            Type.getInternalName(PythonLikeObject.class),
                                            "__getType", Type.getMethodDescriptor(Type.getType(PythonLikeType.class)),
                                            true);
                                    methodVisitor.visitLdcInsn("throw");
                                    methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                                            Type.getInternalName(PythonLikeObject.class),
                                            "__getAttributeOrNull",
                                            Type.getMethodDescriptor(Type.getType(PythonLikeObject.class),
                                                    Type.getType(String.class)),
                                            true);

                                    // Stack is now Throwable, Generator, maybeMethod
                                    Label ifThrowMethodPresent = new Label();
                                    methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                                    methodVisitor.visitJumpInsn(Opcodes.IF_ACMPNE, ifThrowMethodPresent);

                                    // does not have a throw method
                                    // Set yieldFromIterator to null since it is finished
                                    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                                    methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                                    methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName,
                                            PythonGeneratorTranslator.YIELD_FROM_ITERATOR,
                                            Type.getDescriptor(PythonLikeObject.class));

                                    methodVisitor.visitInsn(Opcodes.POP);
                                    methodVisitor.visitInsn(Opcodes.ATHROW);

                                    methodVisitor.visitLabel(ifThrowMethodPresent);

                                    // Swap so it Generator, Throwable instead of Throwable, Generator
                                    methodVisitor.visitInsn(Opcodes.SWAP);
                                    FunctionImplementor.callBinaryMethod(methodVisitor,
                                            PythonBinaryOperators.THROW.dunderMethod);
                                    break;
                                }
                            }
                            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(PythonLikeObject.class));
                            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                            methodVisitor.visitInsn(Opcodes.SWAP);
                            methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName,
                                    PythonGeneratorTranslator.YIELDED_VALUE,
                                    Type.getDescriptor(PythonLikeObject.class));
                            methodVisitor.visitInsn(Opcodes.RETURN); // subiterator yielded something; return control to caller
                        }, () -> {
                            methodVisitor.visitTypeInsn(Opcodes.NEW, Type.getInternalName(IllegalStateException.class));
                            methodVisitor.visitInsn(Opcodes.DUP);
                            methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                                    Type.getInternalName(IllegalStateException.class),
                                    "<init>", Type.getMethodDescriptor(Type.VOID_TYPE), false);
                            methodVisitor.visitInsn(Opcodes.ATHROW);
                        }, true);

                methodVisitor.visitLabel(tryEndLabel);

                methodVisitor.visitLabel(catchStartLabel);
                methodVisitor.visitInsn(Opcodes.POP); // pop the StopIteration exception
                methodVisitor.visitLabel(catchEndLabel);

                // Set yieldFromIterator to null since it is finished
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, PythonGeneratorTranslator.YIELD_FROM_ITERATOR,
                        Type.getDescriptor(PythonLikeObject.class));

                // Restore the stack
                GeneratorImplementor.restoreGeneratorState(generatorMethodPart.functionMetadata,
                        generatorMethodPart.initialStackMetadata);

                // Push the last yielded value to TOS
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, PythonGeneratorTranslator.YIELDED_VALUE,
                        Type.getDescriptor(PythonLikeObject.class));

                // Set yielded value to null
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, internalClassName, PythonGeneratorTranslator.YIELDED_VALUE,
                        Type.getDescriptor(PythonLikeObject.class));

                // Resume execution
            }

            generatorMethodPart.functionMetadata.bytecodeCounterToCodeArgumenterList.getOrDefault(instruction.offset, List.of())
                    .forEach(Runnable::run);

            if (stackMetadata.isDeadCode()) {
                continue;
            }

            opcodeList.get(i).implement(generatorMethodPart.functionMetadata, stackMetadata);
        }

        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();
    }

    private static Map<Integer, GeneratorMethodPart> createGeneratorStateToMethod(ClassWriter classWriter,
            String internalClassName,
            PythonCompiledFunction pythonCompiledFunction) {
        Map<Integer, GeneratorMethodPart> generatorStateToMethod = new HashMap<>();
        MethodVisitor methodVisitor =
                classWriter.visitMethod(Modifier.PRIVATE, "advance", Type.getMethodDescriptor(Type.VOID_TYPE),
                        null, null);
        methodVisitor = MethodVisitorAdapters.adapt(methodVisitor, "advance", Type.getMethodDescriptor(Type.VOID_TYPE));
        Map<Integer, List<Runnable>> bytecodeIndexToArgumentorsMap = new HashMap<>();
        Map<Integer, Label> bytecodeCounterToLabelMap = new HashMap<>();

        FunctionMetadata functionMetadata = new FunctionMetadata();
        functionMetadata.functionType = PythonFunctionType.GENERATOR;
        functionMetadata.method = new MethodDescriptor(internalClassName, MethodDescriptor.MethodType.VIRTUAL,
                "advance", Type.getMethodDescriptor(Type.VOID_TYPE));
        functionMetadata.bytecodeCounterToCodeArgumenterList = bytecodeIndexToArgumentorsMap;
        functionMetadata.bytecodeCounterToLabelMap = bytecodeCounterToLabelMap;
        functionMetadata.methodVisitor = methodVisitor;
        functionMetadata.pythonCompiledFunction = pythonCompiledFunction;
        functionMetadata.className = internalClassName;

        GeneratorLocalVariableHelper localVariableHelper =
                new GeneratorLocalVariableHelper(classWriter, internalClassName, new Type[] {}, pythonCompiledFunction);

        MethodDescriptor stackMetadataMethod = new MethodDescriptor(internalClassName, MethodDescriptor.MethodType.STATIC,
                pythonCompiledFunction.qualifiedName, pythonCompiledFunction.getAsmMethodDescriptorString());
        StackMetadata initialStackMetadata = getInitialStackMetadata(localVariableHelper, stackMetadataMethod, false);

        List<Opcode> opcodeList = getOpcodeList(pythonCompiledFunction);

        FlowGraph flowGraph = FlowGraph.createFlowGraph(functionMetadata, initialStackMetadata, opcodeList);
        flowGraph.visitOperations(YieldValueOpcode.class, (yieldValueOpcode, priorStackMetadata) -> {
            generatorStateToMethod.put(yieldValueOpcode.getBytecodeIndex() + 1,
                    getGeneratorMethodPartForYield(internalClassName, classWriter, pythonCompiledFunction,
                            stackMetadataMethod,
                            priorStackMetadata.pop(),
                            yieldValueOpcode));
        });

        flowGraph.visitOperations(YieldFromOpcode.class, (yieldFromOpcode, priorStackMetadata) -> {
            generatorStateToMethod.put(yieldFromOpcode.getBytecodeIndex() + 1,
                    getGeneratorMethodPartForYield(internalClassName, classWriter, pythonCompiledFunction,
                            stackMetadataMethod,
                            priorStackMetadata.pop(2),
                            yieldFromOpcode));
        });

        GeneratorMethodPart start = new GeneratorMethodPart();
        start.initialStackMetadata = initialStackMetadata;
        start.functionMetadata = functionMetadata;
        start.afterYield = 0;
        start.originalMethodDescriptor = stackMetadataMethod;
        start.instruction = new PythonBytecodeInstruction();
        start.instruction.opcode = OpcodeIdentifier.YIELD_VALUE;
        start.instruction.offset = 0;
        generatorStateToMethod.put(0, start);

        return generatorStateToMethod;
    }

    private static GeneratorMethodPart getGeneratorMethodPartForYield(String internalClassName,
            ClassWriter classWriter,
            PythonCompiledFunction pythonCompiledFunction,
            MethodDescriptor originalMethodDescriptor,
            StackMetadata stackMetadata,
            AbstractOpcode opcode) {
        final String methodName = "advance" + (opcode.getBytecodeIndex() + 1);

        GeneratorMethodPart out = new GeneratorMethodPart();
        out.initialStackMetadata = stackMetadata;
        out.afterYield = opcode.getBytecodeIndex() + 1;
        out.instruction = opcode.getInstruction();
        out.originalMethodDescriptor = originalMethodDescriptor;

        FunctionMetadata functionMetadata = new FunctionMetadata();
        out.functionMetadata = functionMetadata;
        functionMetadata.functionType = PythonFunctionType.GENERATOR;
        functionMetadata.method = new MethodDescriptor(internalClassName, MethodDescriptor.MethodType.VIRTUAL,
                methodName, Type.getMethodDescriptor(Type.VOID_TYPE));
        functionMetadata.bytecodeCounterToLabelMap = new HashMap<>();
        functionMetadata.bytecodeCounterToCodeArgumenterList = new HashMap<>();
        functionMetadata.className = internalClassName;
        functionMetadata.methodVisitor =
                classWriter.visitMethod(Modifier.PRIVATE, methodName, Type.getMethodDescriptor(Type.VOID_TYPE),
                        null, null);
        functionMetadata.methodVisitor = MethodVisitorAdapters.adapt(functionMetadata.methodVisitor, methodName,
                Type.getMethodDescriptor(Type.VOID_TYPE));
        functionMetadata.pythonCompiledFunction = pythonCompiledFunction.copy();
        return out;
    }

    private static class GeneratorMethodPart {
        FunctionMetadata functionMetadata;
        StackMetadata initialStackMetadata;
        MethodDescriptor originalMethodDescriptor;
        int afterYield;
        PythonBytecodeInstruction instruction;
    }
}
