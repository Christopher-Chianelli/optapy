package org.optaplanner.jpyinterpreter;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.optaplanner.jpyinterpreter.dag.FlowGraph;
import org.optaplanner.jpyinterpreter.implementors.CollectionImplementor;
import org.optaplanner.jpyinterpreter.implementors.FunctionImplementor;
import org.optaplanner.jpyinterpreter.implementors.JavaPythonTypeConversionImplementor;
import org.optaplanner.jpyinterpreter.implementors.VariableImplementor;
import org.optaplanner.jpyinterpreter.opcodes.Opcode;
import org.optaplanner.jpyinterpreter.opcodes.OpcodeWithoutSource;
import org.optaplanner.jpyinterpreter.opcodes.SelfOpcodeWithoutSource;
import org.optaplanner.jpyinterpreter.types.BuiltinTypes;
import org.optaplanner.jpyinterpreter.types.PythonLikeFunction;
import org.optaplanner.jpyinterpreter.types.PythonLikeType;
import org.optaplanner.jpyinterpreter.types.PythonString;
import org.optaplanner.jpyinterpreter.types.collections.PythonLikeDict;
import org.optaplanner.jpyinterpreter.types.collections.PythonLikeTuple;
import org.optaplanner.jpyinterpreter.types.wrappers.OpaquePythonReference;
import org.optaplanner.jpyinterpreter.types.wrappers.PythonObjectWrapper;
import org.optaplanner.jpyinterpreter.util.JavaPythonClassWriter;
import org.optaplanner.jpyinterpreter.util.MethodVisitorAdapters;
import org.optaplanner.jpyinterpreter.util.arguments.ArgumentSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PythonBytecodeToJavaBytecodeTranslator {

    public static final String USER_PACKAGE_BASE = "org.jpyinterpreter.user.";

    public static final String GENERATED_PACKAGE_BASE = "org.jpyinterpreter.synthetic.";

    public static final String CONSTANTS_STATIC_FIELD_NAME = "co_consts";

    public static final String NAMES_STATIC_FIELD_NAME = "co_names";

    public static final String VARIABLE_NAMES_STATIC_FIELD_NAME = "co_varnames";

    public static final String GLOBALS_MAP_STATIC_FIELD_NAME = "__globals__";

    public static final String CLASS_CELL_STATIC_FIELD_NAME = "__class_cell__";

    public static final String ARGUMENT_SPEC_GETTER_STATIC_FIELD_NAME = "__spec_getter__";

    public static final String PYTHON_WRAPPER_CODE_STATIC_FIELD_NAME = "__code__";

    public static final String DEFAULT_POSITIONAL_ARGS_INSTANCE_FIELD_NAME = "__defaults__";

    public static final String DEFAULT_KEYWORD_ARGS_INSTANCE_FIELD_NAME = "__kwdefaults__";

    public static final String ANNOTATION_DIRECTORY_INSTANCE_FIELD_NAME = "__annotations__";
    public static final String CELLS_INSTANCE_FIELD_NAME = "__closure__";

    public static final String QUALIFIED_NAME_INSTANCE_FIELD_NAME = "__qualname__";

    public static final String ARGUMENT_SPEC_INSTANCE_FIELD_NAME = "__spec__";

    public static final String INTERPRETER_INSTANCE_FIELD_NAME = "__interpreter__";

    public static final String PYTHON_WRAPPER_FUNCTION_INSTANCE_FIELD_NAME = "__function__";
    public static final Map<String, Integer> classNameToSharedInstanceCount = new HashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(PythonBytecodeToJavaBytecodeTranslator.class);
    public static Path classOutputRootPath = InterpreterStartupOptions.classOutputRootPath;

    static {
        BuiltinTypes.load();
    }

    public static void writeClassOutput(Map<String, byte[]> classNameToBytecode, String className, byte[] classByteCode) {
        classNameToBytecode.put(className, classByteCode);

        if (classOutputRootPath == null) {
            return;
        }

        String[] parts = (className.replace('.', '/') + ".class").split("/");
        Path classFileLocation = classOutputRootPath.resolve(Path.of(".", parts));

        try {
            Files.createDirectories(classFileLocation.getParent());
            Files.write(classFileLocation, classByteCode);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Method getFunctionalInterfaceMethod(Class<?> interfaceClass) {
        List<Method> candidateList = new ArrayList<>();
        for (Method method : interfaceClass.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers())) {
                candidateList.add(method);
            }
        }

        if (candidateList.isEmpty()) {
            throw new IllegalArgumentException("Class (" + interfaceClass.getName() + ") is not a functional interface: " +
                    "it has no abstract methods.");
        }

        if (candidateList.size() > 1) {
            throw new IllegalArgumentException("Class (" + interfaceClass.getName() + ") is not a functional interface: " +
                    "it has multiple abstract methods (" + candidateList + ").");
        }

        return candidateList.get(0);
    }

    public static <T> T createInstance(Class<T> functionClass, PythonInterpreter pythonInterpreter) {
        return FunctionImplementor.createInstance(new PythonLikeTuple(), new PythonLikeDict(),
                new PythonLikeTuple(), new PythonLikeTuple(),
                PythonString.valueOf(functionClass.getName()),
                functionClass, pythonInterpreter);
    }

    public static <T> T translatePythonBytecode(PythonCompiledFunction pythonCompiledFunction,
            Class<T> javaFunctionalInterfaceType) {
        Class<T> compiledClass = translatePythonBytecodeToClass(pythonCompiledFunction, javaFunctionalInterfaceType);
        PythonLikeTuple annotationTuple = pythonCompiledFunction.typeAnnotations.entrySet()
                .stream()
                .map(entry -> PythonLikeTuple.fromList(List.of(PythonString.valueOf(entry.getKey()),
                        entry.getValue() != null ? entry.getValue() : BuiltinTypes.BASE_TYPE)))
                .collect(Collectors.toCollection(PythonLikeTuple::new));
        return FunctionImplementor.createInstance(pythonCompiledFunction.defaultPositionalArguments,
                pythonCompiledFunction.defaultKeywordArguments,
                annotationTuple, pythonCompiledFunction.closure,
                PythonString.valueOf(compiledClass.getName()),
                compiledClass, PythonInterpreter.DEFAULT);
    }

    public static <T> T translatePythonBytecode(PythonCompiledFunction pythonCompiledFunction,
            Class<T> javaFunctionalInterfaceType, List<Class<?>> genericTypeArgumentList) {
        Class<T> compiledClass =
                translatePythonBytecodeToClass(pythonCompiledFunction, javaFunctionalInterfaceType, genericTypeArgumentList);
        PythonLikeTuple annotationTuple = pythonCompiledFunction.typeAnnotations.entrySet()
                .stream()
                .map(entry -> PythonLikeTuple.fromList(List.of(PythonString.valueOf(entry.getKey()), entry.getValue())))
                .collect(Collectors.toCollection(PythonLikeTuple::new));
        return FunctionImplementor.createInstance(pythonCompiledFunction.defaultPositionalArguments,
                pythonCompiledFunction.defaultKeywordArguments,
                annotationTuple, pythonCompiledFunction.closure,
                PythonString.valueOf(compiledClass.getName()),
                compiledClass, PythonInterpreter.DEFAULT);
    }

    public static <T> T forceTranslatePythonBytecodeToGenerator(PythonCompiledFunction pythonCompiledFunction,
            Class<T> javaFunctionalInterfaceType) {
        Method methodWithoutGenerics = getFunctionalInterfaceMethod(javaFunctionalInterfaceType);
        MethodDescriptor methodDescriptor = new MethodDescriptor(javaFunctionalInterfaceType,
                methodWithoutGenerics,
                List.of());
        Class<T> compiledClass = forceTranslatePythonBytecodeToGeneratorClass(pythonCompiledFunction, methodDescriptor,
                methodWithoutGenerics, false);
        return FunctionImplementor.createInstance(new PythonLikeTuple(), new PythonLikeDict(),
                new PythonLikeTuple(), pythonCompiledFunction.closure,
                PythonString.valueOf(compiledClass.getName()),
                compiledClass, PythonInterpreter.DEFAULT);
    }

    public static <T> Class<T> translatePythonBytecodeToClass(PythonCompiledFunction pythonCompiledFunction,
            Class<T> javaFunctionalInterfaceType) {
        MethodDescriptor methodDescriptor = new MethodDescriptor(getFunctionalInterfaceMethod(javaFunctionalInterfaceType));
        return translatePythonBytecodeToClass(pythonCompiledFunction, methodDescriptor);
    }

    public static <T> Class<T> translatePythonBytecodeToClass(PythonCompiledFunction pythonCompiledFunction,
            Class<T> javaFunctionalInterfaceType,
            List<Class<?>> genericTypeArgumentList) {
        Method methodWithoutGenerics = getFunctionalInterfaceMethod(javaFunctionalInterfaceType);
        MethodDescriptor methodDescriptor = new MethodDescriptor(javaFunctionalInterfaceType,
                methodWithoutGenerics,
                genericTypeArgumentList);
        return translatePythonBytecodeToClass(pythonCompiledFunction, methodDescriptor, methodWithoutGenerics, false);
    }

    public static <T> Class<T> translatePythonBytecodeToClass(PythonCompiledFunction pythonCompiledFunction,
            MethodDescriptor methodDescriptor) {
        return translatePythonBytecodeToClass(pythonCompiledFunction, methodDescriptor, false);
    }

    public static <T> T translatePythonBytecodeToInstance(PythonCompiledFunction pythonCompiledFunction,
            MethodDescriptor methodDescriptor) {
        return translatePythonBytecodeToInstance(pythonCompiledFunction, methodDescriptor, false);
    }

    public static <T> T translatePythonBytecodeToInstance(PythonCompiledFunction pythonCompiledFunction,
            MethodDescriptor methodDescriptor,
            boolean isVirtual) {
        Class<T> compiledClass = translatePythonBytecodeToClass(pythonCompiledFunction, methodDescriptor, isVirtual);
        PythonLikeTuple annotationTuple = pythonCompiledFunction.typeAnnotations.entrySet()
                .stream()
                .map(entry -> PythonLikeTuple.fromList(List.of(PythonString.valueOf(entry.getKey()), entry.getValue())))
                .collect(Collectors.toCollection(PythonLikeTuple::new));
        return FunctionImplementor.createInstance(pythonCompiledFunction.defaultPositionalArguments,
                pythonCompiledFunction.defaultKeywordArguments,
                annotationTuple, pythonCompiledFunction.closure,
                PythonString.valueOf(compiledClass.getName()),
                compiledClass, PythonInterpreter.DEFAULT);
    }

    @SuppressWarnings("unchecked")
    public static <T> Class<T> translatePythonBytecodeToClass(PythonCompiledFunction pythonCompiledFunction,
            MethodDescriptor methodDescriptor, boolean isVirtual) {
        String maybeClassName = USER_PACKAGE_BASE + pythonCompiledFunction.getGeneratedClassBaseName();
        int numberOfInstances = classNameToSharedInstanceCount.merge(maybeClassName, 1, Integer::sum);
        if (numberOfInstances > 1) {
            maybeClassName = maybeClassName + "$$" + numberOfInstances;
        }
        String className = maybeClassName;
        String internalClassName = className.replace('.', '/');
        ClassWriter classWriter = new JavaPythonClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        classWriter.visit(Opcodes.V11, Modifier.PUBLIC, internalClassName, null, Type.getInternalName(Object.class),
                new String[] { methodDescriptor.declaringClassInternalName });

        final boolean isPythonLikeFunction =
                methodDescriptor.declaringClassInternalName.equals(Type.getInternalName(PythonLikeFunction.class));

        createFields(classWriter);
        createConstructor(classWriter, internalClassName);

        MethodVisitor methodVisitor = classWriter.visitMethod(Modifier.PUBLIC,
                methodDescriptor.methodName,
                methodDescriptor.methodDescriptor,
                null,
                null);

        translatePythonBytecodeToMethod(methodDescriptor, internalClassName, methodVisitor, pythonCompiledFunction,
                isPythonLikeFunction, Integer.MAX_VALUE, isVirtual); // TODO: Use actual python version

        classWriter.visitEnd();

        writeClassOutput(BuiltinTypes.classNameToBytecode, className, classWriter.toByteArray());

        try {
            Class<T> compiledClass = (Class<T>) BuiltinTypes.asmClassLoader.loadClass(className);
            setStaticFields(compiledClass, pythonCompiledFunction);
            return compiledClass;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Impossible State: Unable to load generated class (" +
                    className + ") despite it being just generated.", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> Class<T> translatePythonBytecodeToClass(PythonCompiledFunction pythonCompiledFunction,
            MethodDescriptor methodDescriptor, Method methodWithoutGenerics,
            boolean isVirtual) {
        String maybeClassName = USER_PACKAGE_BASE + pythonCompiledFunction.getGeneratedClassBaseName();
        int numberOfInstances = classNameToSharedInstanceCount.merge(maybeClassName, 1, Integer::sum);
        if (numberOfInstances > 1) {
            maybeClassName = maybeClassName + "$$" + numberOfInstances;
        }
        String className = maybeClassName;
        String internalClassName = className.replace('.', '/');
        ClassWriter classWriter = new JavaPythonClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        classWriter.visit(Opcodes.V11, Modifier.PUBLIC, internalClassName, null, Type.getInternalName(Object.class),
                new String[] { methodDescriptor.declaringClassInternalName });

        final boolean isPythonLikeFunction =
                methodDescriptor.declaringClassInternalName.equals(Type.getInternalName(PythonLikeFunction.class));

        createFields(classWriter);
        createConstructor(classWriter, internalClassName);

        MethodVisitor methodVisitor = classWriter.visitMethod(Modifier.PUBLIC,
                methodDescriptor.methodName,
                methodDescriptor.methodDescriptor,
                null,
                null);

        translatePythonBytecodeToMethod(methodDescriptor, internalClassName, methodVisitor, pythonCompiledFunction,
                isPythonLikeFunction, Integer.MAX_VALUE, isVirtual); // TODO: Use actual python version

        String withoutGenericsSignature = Type.getMethodDescriptor(methodWithoutGenerics);
        if (!withoutGenericsSignature.equals(methodDescriptor.methodDescriptor)) {
            methodVisitor =
                    classWriter.visitMethod(Modifier.PUBLIC, methodDescriptor.methodName, withoutGenericsSignature, null, null);

            methodVisitor.visitCode();
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            for (int i = 0; i < methodWithoutGenerics.getParameterCount(); i++) {
                Type parameterType = Type.getType(methodWithoutGenerics.getParameterTypes()[i]);
                methodVisitor.visitVarInsn(parameterType.getOpcode(Opcodes.ILOAD), i + 1);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, methodDescriptor.getParameterTypes()[i].getInternalName());
            }
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internalClassName, methodDescriptor.methodName,
                    methodDescriptor.methodDescriptor, false);
            methodVisitor.visitInsn(methodDescriptor.getReturnType().getOpcode(Opcodes.IRETURN));

            methodVisitor.visitMaxs(-1, -1);
            methodVisitor.visitEnd();
        }
        classWriter.visitEnd();

        writeClassOutput(BuiltinTypes.classNameToBytecode, className, classWriter.toByteArray());

        try {
            Class<T> compiledClass = (Class<T>) BuiltinTypes.asmClassLoader.loadClass(className);
            setStaticFields(compiledClass, pythonCompiledFunction);
            return compiledClass;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Impossible State: Unable to load generated class (" +
                    className + ") despite it being just generated.", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> Class<T> translatePythonBytecodeToPythonWrapperClass(PythonCompiledFunction pythonCompiledFunction,
            OpaquePythonReference codeReference) {
        String maybeClassName = USER_PACKAGE_BASE + pythonCompiledFunction.getGeneratedClassBaseName();
        int numberOfInstances = classNameToSharedInstanceCount.merge(maybeClassName, 1, Integer::sum);
        if (numberOfInstances > 1) {
            maybeClassName = maybeClassName + "$$" + numberOfInstances;
        }
        String className = maybeClassName;
        String internalClassName = className.replace('.', '/');
        ClassWriter classWriter = new JavaPythonClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        classWriter.visit(Opcodes.V11, Modifier.PUBLIC, internalClassName, null, Type.getInternalName(Object.class),
                new String[] { Type.getInternalName(PythonLikeFunction.class) });

        createFields(classWriter);
        classWriter.visitField(Modifier.PUBLIC | Modifier.STATIC, PYTHON_WRAPPER_CODE_STATIC_FIELD_NAME,
                Type.getDescriptor(OpaquePythonReference.class),
                null, null);
        classWriter.visitField(Modifier.PUBLIC | Modifier.FINAL, PYTHON_WRAPPER_FUNCTION_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonObjectWrapper.class),
                null, null);
        createPythonWrapperConstructor(classWriter, internalClassName);

        MethodVisitor methodVisitor = classWriter.visitMethod(Modifier.PUBLIC,
                "$call",
                Type.getMethodDescriptor(Type.getType(PythonLikeObject.class),
                        Type.getType(List.class),
                        Type.getType(Map.class),
                        Type.getType(PythonLikeObject.class)),
                null,
                null);

        methodVisitor.visitCode();
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, PYTHON_WRAPPER_FUNCTION_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonObjectWrapper.class));
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 2);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 3);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(PythonObjectWrapper.class), "$call",
                Type.getMethodDescriptor(Type.getType(PythonLikeObject.class),
                        Type.getType(List.class),
                        Type.getType(Map.class),
                        Type.getType(PythonLikeObject.class)),
                false);
        methodVisitor.visitInsn(Opcodes.ARETURN);
        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();

        classWriter.visitEnd();

        writeClassOutput(BuiltinTypes.classNameToBytecode, className, classWriter.toByteArray());

        try {
            Class<T> compiledClass = (Class<T>) BuiltinTypes.asmClassLoader.loadClass(className);
            setStaticFields(compiledClass, pythonCompiledFunction);
            compiledClass.getField(PYTHON_WRAPPER_CODE_STATIC_FIELD_NAME).set(null, codeReference);
            return compiledClass;
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Impossible State: Unable to load generated class (" +
                    className + ") despite it being just generated.", e);
        }
    }

    /**
     * Used for testing; force translate the python to a generator, even if it is not a generator
     */
    @SuppressWarnings("unchecked")
    public static <T> Class<T> forceTranslatePythonBytecodeToGeneratorClass(PythonCompiledFunction pythonCompiledFunction,
            MethodDescriptor methodDescriptor, Method methodWithoutGenerics,
            boolean isVirtual) {
        String maybeClassName = USER_PACKAGE_BASE + pythonCompiledFunction.getGeneratedClassBaseName();
        int numberOfInstances = classNameToSharedInstanceCount.merge(maybeClassName, 1, Integer::sum);
        if (numberOfInstances > 1) {
            maybeClassName = maybeClassName + "$$" + numberOfInstances;
        }
        String className = maybeClassName;
        String internalClassName = className.replace('.', '/');
        ClassWriter classWriter = new JavaPythonClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        classWriter.visit(Opcodes.V11, Modifier.PUBLIC, internalClassName, null, Type.getInternalName(Object.class),
                new String[] { methodDescriptor.declaringClassInternalName });

        final boolean isPythonLikeFunction =
                methodDescriptor.declaringClassInternalName.equals(Type.getInternalName(PythonLikeFunction.class));

        createFields(classWriter);
        createConstructor(classWriter, internalClassName);

        MethodVisitor methodVisitor = classWriter.visitMethod(Modifier.PUBLIC,
                methodDescriptor.methodName,
                methodDescriptor.methodDescriptor,
                null,
                null);

        LocalVariableHelper localVariableHelper =
                new LocalVariableHelper(methodDescriptor.getParameterTypes(), pythonCompiledFunction);

        if (!isPythonLikeFunction) {
            // Need to convert Java parameters
            for (int i = 0; i < localVariableHelper.parameters.length; i++) {
                JavaPythonTypeConversionImplementor.copyParameter(methodVisitor, localVariableHelper, i);
            }
        } else {
            // Need to move Python parameters from the argument list + keyword list to their variable slots
            movePythonParametersToSlots(methodVisitor, internalClassName, pythonCompiledFunction, localVariableHelper);
        }

        for (int i = 0; i < localVariableHelper.getNumberOfBoundCells(); i++) {
            VariableImplementor.createCell(methodVisitor, localVariableHelper, i);
        }

        for (int i = 0; i < localVariableHelper.getNumberOfFreeCells(); i++) {
            VariableImplementor.setupFreeVariableCell(methodVisitor, internalClassName, localVariableHelper, i);
        }

        translateGeneratorBytecode(methodVisitor, methodDescriptor, internalClassName, localVariableHelper,
                pythonCompiledFunction); // TODO: Use actual python version

        String withoutGenericsSignature = Type.getMethodDescriptor(methodWithoutGenerics);
        if (!withoutGenericsSignature.equals(methodDescriptor.methodDescriptor)) {
            methodVisitor =
                    classWriter.visitMethod(Modifier.PUBLIC, methodDescriptor.methodName, withoutGenericsSignature, null, null);

            methodVisitor.visitCode();
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            for (int i = 0; i < methodWithoutGenerics.getParameterCount(); i++) {
                Type parameterType = Type.getType(methodWithoutGenerics.getParameterTypes()[i]);
                methodVisitor.visitVarInsn(parameterType.getOpcode(Opcodes.ILOAD), i + 1);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, methodDescriptor.getParameterTypes()[i].getInternalName());
            }
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internalClassName, methodDescriptor.methodName,
                    methodDescriptor.methodDescriptor, false);
            methodVisitor.visitInsn(methodDescriptor.getReturnType().getOpcode(Opcodes.IRETURN));

            methodVisitor.visitMaxs(-1, -1);
            methodVisitor.visitEnd();
        }
        classWriter.visitEnd();

        writeClassOutput(BuiltinTypes.classNameToBytecode, className, classWriter.toByteArray());

        try {
            Class<T> compiledClass = (Class<T>) BuiltinTypes.asmClassLoader.loadClass(className);
            setStaticFields(compiledClass, pythonCompiledFunction);
            return compiledClass;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Impossible State: Unable to load generated class (" +
                    className + ") despite it being just generated.", e);
        }
    }

    private static void createConstructor(ClassWriter classWriter, String className) {
        // Empty constructor, for java code
        MethodVisitor methodVisitor = classWriter.visitMethod(Modifier.PUBLIC, "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE),
                null, null);
        methodVisitor.visitCode();

        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(Object.class), "<init>",
                "()V", false);

        // Positional only and Positional/Keyword default arguments
        methodVisitor.visitInsn(Opcodes.DUP);
        CollectionImplementor.buildCollection(PythonLikeTuple.class, methodVisitor, 0);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, DEFAULT_POSITIONAL_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        // Keyword only default arguments
        methodVisitor.visitInsn(Opcodes.DUP);
        CollectionImplementor.buildMap(PythonLikeDict.class, methodVisitor, 0);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, DEFAULT_KEYWORD_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));

        // Annotation Directory as key/value tuple
        methodVisitor.visitInsn(Opcodes.DUP);
        CollectionImplementor.buildMap(PythonLikeDict.class, methodVisitor, 0);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, ANNOTATION_DIRECTORY_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));

        // Free variable cells
        methodVisitor.visitInsn(Opcodes.DUP);
        CollectionImplementor.buildCollection(PythonLikeTuple.class, methodVisitor, 0);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, CELLS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        // Function name
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitLdcInsn(className.replace('/', '.'));
        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(PythonString.class),
                "valueOf", Type.getMethodDescriptor(Type.getType(PythonString.class), Type.getType(String.class)),
                false);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, QUALIFIED_NAME_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonString.class));

        // Spec
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, className, ARGUMENT_SPEC_GETTER_STATIC_FIELD_NAME,
                Type.getDescriptor(BiFunction.class));

        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, className, DEFAULT_POSITIONAL_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, className, DEFAULT_KEYWORD_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));

        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(BiFunction.class), "apply",
                Type.getMethodDescriptor(Type.getType(Object.class), Type.getType(Object.class), Type.getType(Object.class)),
                true);
        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(ArgumentSpec.class));

        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, ARGUMENT_SPEC_INSTANCE_FIELD_NAME,
                Type.getDescriptor(ArgumentSpec.class));

        // Interpreter
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(PythonInterpreter.class), "DEFAULT",
                Type.getDescriptor(PythonInterpreter.class));
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, INTERPRETER_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonInterpreter.class));
        methodVisitor.visitInsn(Opcodes.RETURN);

        methodVisitor.visitMaxs(-1, -1);
        methodVisitor.visitEnd();

        // Full constructor, for MAKE_FUNCTION
        methodVisitor = classWriter.visitMethod(Modifier.PUBLIC, "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE,
                        Type.getType(PythonLikeTuple.class),
                        Type.getType(PythonLikeDict.class),
                        Type.getType(PythonLikeDict.class),
                        Type.getType(PythonLikeTuple.class),
                        Type.getType(PythonString.class),
                        Type.getType(PythonInterpreter.class)),
                null, null);
        methodVisitor.visitCode();
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(Object.class), "<init>",
                "()V", false);

        // Positional only and Positional/Keyword default arguments
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, DEFAULT_POSITIONAL_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        // Keyword only default arguments
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 2);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, DEFAULT_KEYWORD_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));

        // Annotation Directory as key/value tuple
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 3);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, ANNOTATION_DIRECTORY_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));

        // Free variable cells
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 4);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, CELLS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        // Function name
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 5);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, QUALIFIED_NAME_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonString.class));

        // Spec
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, className, ARGUMENT_SPEC_GETTER_STATIC_FIELD_NAME,
                Type.getDescriptor(BiFunction.class));

        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, className, DEFAULT_POSITIONAL_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, className, DEFAULT_KEYWORD_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));

        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(BiFunction.class), "apply",
                Type.getMethodDescriptor(Type.getType(Object.class), Type.getType(Object.class), Type.getType(Object.class)),
                true);
        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(ArgumentSpec.class));

        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, ARGUMENT_SPEC_INSTANCE_FIELD_NAME,
                Type.getDescriptor(ArgumentSpec.class));

        // Interpreter
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 6);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, INTERPRETER_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonInterpreter.class));

        methodVisitor.visitInsn(Opcodes.RETURN);
        methodVisitor.visitMaxs(-1, -1);
        methodVisitor.visitEnd();
    }

    private static void createPythonWrapperConstructor(ClassWriter classWriter, String className) {
        // Empty constructor, for java code
        MethodVisitor methodVisitor = classWriter.visitMethod(Modifier.PUBLIC, "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE),
                null, null);
        methodVisitor.visitCode();

        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(Object.class), "<init>",
                "()V", false);

        // Positional only and Positional/Keyword default arguments
        methodVisitor.visitInsn(Opcodes.DUP);
        CollectionImplementor.buildCollection(PythonLikeTuple.class, methodVisitor, 0);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, DEFAULT_POSITIONAL_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        // Keyword only default arguments
        methodVisitor.visitInsn(Opcodes.DUP);
        CollectionImplementor.buildMap(PythonLikeDict.class, methodVisitor, 0);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, DEFAULT_KEYWORD_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));

        // Annotation Directory as key/value tuple
        methodVisitor.visitInsn(Opcodes.DUP);
        CollectionImplementor.buildMap(PythonLikeDict.class, methodVisitor, 0);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, ANNOTATION_DIRECTORY_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));

        // Free variable cells
        methodVisitor.visitInsn(Opcodes.DUP);
        CollectionImplementor.buildCollection(PythonLikeTuple.class, methodVisitor, 0);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, CELLS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        // Function name
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitLdcInsn(className.replace('/', '.'));
        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(PythonString.class),
                "valueOf", Type.getMethodDescriptor(Type.getType(PythonString.class), Type.getType(String.class)),
                false);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, QUALIFIED_NAME_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonString.class));

        // Spec
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, className, ARGUMENT_SPEC_GETTER_STATIC_FIELD_NAME,
                Type.getDescriptor(BiFunction.class));

        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, className, DEFAULT_POSITIONAL_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, className, DEFAULT_KEYWORD_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));

        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(BiFunction.class), "apply",
                Type.getMethodDescriptor(Type.getType(Object.class), Type.getType(Object.class), Type.getType(Object.class)),
                true);
        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(ArgumentSpec.class));

        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, ARGUMENT_SPEC_INSTANCE_FIELD_NAME,
                Type.getDescriptor(ArgumentSpec.class));

        // Interpreter
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(PythonInterpreter.class), "DEFAULT",
                Type.getDescriptor(PythonInterpreter.class));
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, INTERPRETER_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonInterpreter.class));

        // Function object
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, className, PYTHON_WRAPPER_CODE_STATIC_FIELD_NAME,
                Type.getDescriptor(OpaquePythonReference.class));

        methodVisitor.visitInsn(Opcodes.SWAP);
        methodVisitor.visitInsn(Opcodes.DUP_X1);
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, className, GLOBALS_MAP_STATIC_FIELD_NAME,
                Type.getDescriptor(Map.class));

        methodVisitor.visitInsn(Opcodes.SWAP);
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, className, CELLS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        methodVisitor.visitInsn(Opcodes.SWAP);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, className, QUALIFIED_NAME_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonString.class));

        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(CPythonBackedPythonInterpreter.class),
                "createPythonFunctionWrapper",
                Type.getMethodDescriptor(Type.getType(PythonObjectWrapper.class),
                        Type.getType(OpaquePythonReference.class),
                        Type.getType(Map.class),
                        Type.getType(PythonLikeTuple.class),
                        Type.getType(PythonString.class)),
                false);

        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, PYTHON_WRAPPER_FUNCTION_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonObjectWrapper.class));

        methodVisitor.visitInsn(Opcodes.RETURN);

        methodVisitor.visitMaxs(-1, -1);
        methodVisitor.visitEnd();

        // Full constructor, for MAKE_FUNCTION
        methodVisitor = classWriter.visitMethod(Modifier.PUBLIC, "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE,
                        Type.getType(PythonLikeTuple.class),
                        Type.getType(PythonLikeDict.class),
                        Type.getType(PythonLikeDict.class),
                        Type.getType(PythonLikeTuple.class),
                        Type.getType(PythonString.class),
                        Type.getType(PythonInterpreter.class)),
                null, null);
        methodVisitor.visitCode();
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(Object.class), "<init>",
                "()V", false);

        // Positional only and Positional/Keyword default arguments
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, DEFAULT_POSITIONAL_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        // Keyword only default arguments
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 2);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, DEFAULT_KEYWORD_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));

        // Annotation Directory as key/value tuple
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 3);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, ANNOTATION_DIRECTORY_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));

        // Free variable cells
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 4);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, CELLS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        // Function name
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 5);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, QUALIFIED_NAME_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonString.class));

        // Spec
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, className, ARGUMENT_SPEC_GETTER_STATIC_FIELD_NAME,
                Type.getDescriptor(BiFunction.class));

        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, className, DEFAULT_POSITIONAL_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, className, DEFAULT_KEYWORD_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));

        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(BiFunction.class), "apply",
                Type.getMethodDescriptor(Type.getType(Object.class), Type.getType(Object.class), Type.getType(Object.class)),
                true);
        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(ArgumentSpec.class));

        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, ARGUMENT_SPEC_INSTANCE_FIELD_NAME,
                Type.getDescriptor(ArgumentSpec.class));

        // Interpreter
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 6);
        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, INTERPRETER_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonInterpreter.class));

        // Function object
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, className, PYTHON_WRAPPER_CODE_STATIC_FIELD_NAME,
                Type.getDescriptor(OpaquePythonReference.class));

        methodVisitor.visitInsn(Opcodes.SWAP);
        methodVisitor.visitInsn(Opcodes.DUP_X1);
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, className, GLOBALS_MAP_STATIC_FIELD_NAME,
                Type.getDescriptor(Map.class));

        methodVisitor.visitInsn(Opcodes.SWAP);
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, className, CELLS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));

        methodVisitor.visitInsn(Opcodes.SWAP);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, className, QUALIFIED_NAME_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonString.class));

        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(CPythonBackedPythonInterpreter.class),
                "createPythonFunctionWrapper",
                Type.getMethodDescriptor(Type.getType(PythonObjectWrapper.class),
                        Type.getType(OpaquePythonReference.class),
                        Type.getType(Map.class),
                        Type.getType(PythonLikeTuple.class),
                        Type.getType(PythonString.class)),
                false);

        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, PYTHON_WRAPPER_FUNCTION_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonObjectWrapper.class));

        methodVisitor.visitInsn(Opcodes.RETURN);
        methodVisitor.visitMaxs(-1, -1);
        methodVisitor.visitEnd();
    }

    public static void createFields(ClassWriter classWriter) {
        // Static fields
        classWriter.visitField(Modifier.PUBLIC | Modifier.STATIC,
                CONSTANTS_STATIC_FIELD_NAME, Type.getDescriptor(List.class), null, null);
        classWriter.visitField(Modifier.PUBLIC | Modifier.STATIC,
                NAMES_STATIC_FIELD_NAME, Type.getDescriptor(List.class), null, null);
        classWriter.visitField(Modifier.PUBLIC | Modifier.STATIC,
                VARIABLE_NAMES_STATIC_FIELD_NAME, Type.getDescriptor(List.class), null, null);
        classWriter.visitField(Modifier.PUBLIC | Modifier.STATIC,
                GLOBALS_MAP_STATIC_FIELD_NAME, Type.getDescriptor(Map.class), null, null);
        classWriter.visitField(Modifier.PUBLIC | Modifier.STATIC,
                CLASS_CELL_STATIC_FIELD_NAME, Type.getDescriptor(PythonLikeType.class), null, null);
        classWriter.visitField(Modifier.PUBLIC | Modifier.STATIC,
                ARGUMENT_SPEC_GETTER_STATIC_FIELD_NAME, Type.getDescriptor(BiFunction.class), null, null);

        // Instance fields
        classWriter.visitField(Modifier.PRIVATE | Modifier.FINAL,
                INTERPRETER_INSTANCE_FIELD_NAME, Type.getDescriptor(PythonInterpreter.class), null, null);
        classWriter.visitField(Modifier.PRIVATE | Modifier.FINAL,
                DEFAULT_POSITIONAL_ARGS_INSTANCE_FIELD_NAME, Type.getDescriptor(PythonLikeTuple.class), null, null);
        classWriter.visitField(Modifier.PRIVATE | Modifier.FINAL,
                DEFAULT_KEYWORD_ARGS_INSTANCE_FIELD_NAME, Type.getDescriptor(PythonLikeDict.class), null, null);
        classWriter.visitField(Modifier.PRIVATE | Modifier.FINAL,
                ANNOTATION_DIRECTORY_INSTANCE_FIELD_NAME, Type.getDescriptor(PythonLikeDict.class), null, null);
        classWriter.visitField(Modifier.PRIVATE | Modifier.FINAL,
                QUALIFIED_NAME_INSTANCE_FIELD_NAME, Type.getDescriptor(PythonString.class), null, null);
        classWriter.visitField(Modifier.PRIVATE | Modifier.FINAL,
                CELLS_INSTANCE_FIELD_NAME, Type.getDescriptor(PythonLikeTuple.class), null, null);
        classWriter.visitField(Modifier.PUBLIC | Modifier.FINAL,
                ARGUMENT_SPEC_INSTANCE_FIELD_NAME, Type.getDescriptor(ArgumentSpec.class), null, null);
    }

    static void setStaticFields(Class<?> compiledClass, PythonCompiledFunction pythonCompiledFunction) {
        try {
            compiledClass.getField(CONSTANTS_STATIC_FIELD_NAME).set(null, pythonCompiledFunction.co_constants);
            compiledClass.getField(GLOBALS_MAP_STATIC_FIELD_NAME).set(null, pythonCompiledFunction.globalsMap);
            compiledClass.getField(ARGUMENT_SPEC_GETTER_STATIC_FIELD_NAME).set(null,
                    pythonCompiledFunction.getArgumentSpecMapper());

            // Need to convert co_names to python strings (used in __getattribute__)
            List<PythonString> pythonNameList = new ArrayList<>(pythonCompiledFunction.co_names.size());
            for (String name : pythonCompiledFunction.co_names) {
                pythonNameList.add(PythonString.valueOf(name));
            }
            compiledClass.getField(NAMES_STATIC_FIELD_NAME).set(null, pythonNameList);

            List<PythonString> pythonVariableNameList = new ArrayList<>(pythonCompiledFunction.co_varnames.size());
            for (String name : pythonCompiledFunction.co_varnames) {
                pythonVariableNameList.add(PythonString.valueOf(name));
            }
            compiledClass.getField(VARIABLE_NAMES_STATIC_FIELD_NAME).set(null, pythonVariableNameList);
            // Class cell is set by PythonClassTranslator
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new IllegalStateException("Impossible state: generated class (" + compiledClass +
                    ") does not have static field \"" + CONSTANTS_STATIC_FIELD_NAME + "\"", e);
        }
    }

    public static List<Opcode> getOpcodeList(PythonCompiledFunction pythonCompiledFunction) {
        List<Opcode> opcodeList = new ArrayList<>(pythonCompiledFunction.instructionList.size());
        for (PythonBytecodeInstruction instruction : pythonCompiledFunction.instructionList) {
            opcodeList.add(Opcode.lookupOpcodeForInstruction(instruction, pythonCompiledFunction.pythonVersion));
        }
        return opcodeList;
    }

    public static StackMetadata getInitialStackMetadata(LocalVariableHelper localVariableHelper, MethodDescriptor method,
            boolean isVirtual) {
        StackMetadata initialStackMetadata = new StackMetadata();
        initialStackMetadata.stackValueSources = new ArrayList<>();
        initialStackMetadata.localVariableHelper = localVariableHelper;
        initialStackMetadata.localVariableValueSources = new ArrayList<>(localVariableHelper.getNumberOfLocalVariables());
        initialStackMetadata.cellVariableValueSources = new ArrayList<>(localVariableHelper.getNumberOfCells());

        if (Type.getInternalName(PythonLikeFunction.class).equals(method.getDeclaringClassInternalName())) {
            return getPythonLikeFunctionInitialStackMetadata(localVariableHelper, initialStackMetadata);
        }

        for (Type type : method.getParameterTypes()) {
            try {
                Class<?> typeClass = Class.forName(type.getClassName(), false, BuiltinTypes.asmClassLoader);
                initialStackMetadata.localVariableValueSources.add(ValueSourceInfo.of(new OpcodeWithoutSource(),
                        JavaPythonTypeConversionImplementor.getPythonLikeType(typeClass)));
            } catch (ClassNotFoundException e) {
                initialStackMetadata.localVariableValueSources
                        .add(ValueSourceInfo.of(new OpcodeWithoutSource(), BuiltinTypes.BASE_TYPE));
            }
        }

        for (int i = method.getParameterTypes().length; i < localVariableHelper.getNumberOfLocalVariables(); i++) {
            initialStackMetadata.localVariableValueSources.add(null);
        }

        if (isVirtual && method.getParameterTypes().length > 0) {
            try {
                Class<?> typeClass =
                        Class.forName(method.getParameterTypes()[0].getClassName(), false, BuiltinTypes.asmClassLoader);
                initialStackMetadata.localVariableValueSources.set(0, ValueSourceInfo.of(new SelfOpcodeWithoutSource(),
                        JavaPythonTypeConversionImplementor.getPythonLikeType(typeClass)));
            } catch (ClassNotFoundException e) {
                initialStackMetadata.localVariableValueSources.set(0,
                        ValueSourceInfo.of(new SelfOpcodeWithoutSource(), BuiltinTypes.BASE_TYPE));
            }
        }

        for (int i = 0; i < localVariableHelper.getNumberOfBoundCells(); i++) {
            // Bound variables are assumed initialized
            initialStackMetadata.cellVariableValueSources.add(ValueSourceInfo.of(new OpcodeWithoutSource(),
                    BuiltinTypes.BASE_TYPE));
        }

        for (int i = 0; i < localVariableHelper.getNumberOfFreeCells(); i++) {
            // Free variables are assumed initialized
            initialStackMetadata.cellVariableValueSources.add(ValueSourceInfo.of(new OpcodeWithoutSource(),
                    BuiltinTypes.BASE_TYPE));
        }

        return initialStackMetadata;
    }

    private static StackMetadata getPythonLikeFunctionInitialStackMetadata(LocalVariableHelper localVariableHelper,
            StackMetadata initialStackMetadata) {
        for (int i = 0; i < localVariableHelper.getNumberOfLocalVariables(); i++) {
            initialStackMetadata.localVariableValueSources
                    .add(ValueSourceInfo.of(new OpcodeWithoutSource(), BuiltinTypes.BASE_TYPE));
        }

        for (int i = 0; i < localVariableHelper.getNumberOfBoundCells(); i++) {
            // Bound variables are assumed initialized
            initialStackMetadata.cellVariableValueSources.add(ValueSourceInfo.of(new OpcodeWithoutSource(),
                    BuiltinTypes.BASE_TYPE));
        }

        for (int i = 0; i < localVariableHelper.getNumberOfFreeCells(); i++) {
            // Free variables are assumed initialized
            initialStackMetadata.cellVariableValueSources.add(ValueSourceInfo.of(new OpcodeWithoutSource(),
                    BuiltinTypes.BASE_TYPE));
        }

        return initialStackMetadata;
    }

    public static PythonFunctionType getFunctionType(PythonCompiledFunction pythonCompiledFunction) {
        for (PythonBytecodeInstruction instruction : pythonCompiledFunction.instructionList) {
            switch (instruction.opcode) {
                case GEN_START:
                case YIELD_VALUE:
                case YIELD_FROM:
                    return PythonFunctionType.GENERATOR;

                default:
                    break; // Do nothing
            }
        }
        return PythonFunctionType.FUNCTION;
    }

    private static void translatePythonBytecodeToMethod(MethodDescriptor method, String className, MethodVisitor methodVisitor,
            PythonCompiledFunction pythonCompiledFunction, boolean isPythonLikeFunction, int pythonVersion, boolean isVirtual) {
        // Apply Method Adapters, which reorder try blocks and check the bytecode to ensure it valid
        methodVisitor = MethodVisitorAdapters.adapt(methodVisitor, method);

        for (int i = 0; i < method.getParameterTypes().length; i++) {
            if (!isPythonLikeFunction) {
                methodVisitor.visitParameter(pythonCompiledFunction.co_varnames.get(i), 0);
            } else {
                methodVisitor.visitParameter(null, 0);
            }
        }
        methodVisitor.visitCode();

        Label start = new Label();
        Label end = new Label();

        methodVisitor.visitLabel(start);

        Map<Integer, Label> bytecodeCounterToLabelMap = new HashMap<>();
        LocalVariableHelper localVariableHelper = new LocalVariableHelper(method.getParameterTypes(), pythonCompiledFunction);

        if (!isPythonLikeFunction) {
            // Need to convert Java parameters
            for (int i = 0; i < localVariableHelper.parameters.length; i++) {
                JavaPythonTypeConversionImplementor.copyParameter(methodVisitor, localVariableHelper, i);
            }
        } else {
            // Need to move Python parameters from the argument list + keyword list to their variable slots
            movePythonParametersToSlots(methodVisitor, className, pythonCompiledFunction, localVariableHelper);
        }

        for (int i = 0; i < localVariableHelper.getNumberOfBoundCells(); i++) {
            VariableImplementor.createCell(methodVisitor, localVariableHelper, i);
        }

        for (int i = 0; i < localVariableHelper.getNumberOfFreeCells(); i++) {
            VariableImplementor.setupFreeVariableCell(methodVisitor, className, localVariableHelper, i);
        }

        Map<Integer, List<Runnable>> bytecodeIndexToArgumentorsMap = new HashMap<>();

        FunctionMetadata functionMetadata = new FunctionMetadata();
        functionMetadata.functionType = getFunctionType(pythonCompiledFunction);
        functionMetadata.method = method;
        functionMetadata.bytecodeCounterToCodeArgumenterList = bytecodeIndexToArgumentorsMap;
        functionMetadata.bytecodeCounterToLabelMap = bytecodeCounterToLabelMap;
        functionMetadata.methodVisitor = methodVisitor;
        functionMetadata.pythonCompiledFunction = pythonCompiledFunction;
        functionMetadata.className = className;

        if (functionMetadata.functionType == PythonFunctionType.GENERATOR) {
            translateGeneratorBytecode(methodVisitor, method, className, localVariableHelper, pythonCompiledFunction);
            return;
        }

        StackMetadata initialStackMetadata = getInitialStackMetadata(localVariableHelper, method, isVirtual);

        List<Opcode> opcodeList = getOpcodeList(pythonCompiledFunction);

        FlowGraph flowGraph = FlowGraph.createFlowGraph(functionMetadata, initialStackMetadata, opcodeList);
        List<StackMetadata> stackMetadataForOpcodeIndex = flowGraph.getStackMetadataForOperations();

        for (int i = 0; i < opcodeList.size(); i++) {
            StackMetadata stackMetadata = stackMetadataForOpcodeIndex.get(i);
            PythonBytecodeInstruction instruction = pythonCompiledFunction.instructionList.get(i);

            if (instruction.isJumpTarget) {
                Label label = bytecodeCounterToLabelMap.computeIfAbsent(instruction.offset, offset -> new Label());
                methodVisitor.visitLabel(label);
            }

            bytecodeIndexToArgumentorsMap.getOrDefault(instruction.offset, List.of()).forEach(Runnable::run);

            if (stackMetadata.isDeadCode()) {
                continue;
            }
            opcodeList.get(i).implement(functionMetadata, stackMetadata);
        }

        methodVisitor.visitLabel(end);

        for (int i = method.getParameterTypes().length; i < localVariableHelper.getNumberOfLocalVariables(); i++) {
            methodVisitor.visitLocalVariable(pythonCompiledFunction.co_varnames.get(i),
                    Type.getDescriptor(PythonLikeObject.class),
                    null,
                    start,
                    end,
                    localVariableHelper.getPythonLocalVariableSlot(i));
        }

        try {
            methodVisitor.visitMaxs(0, 0);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid Java bytecode generated (this is a bug):\n" +
                    pythonCompiledFunction.instructionList.stream()
                            .map(PythonBytecodeInstruction::toString)
                            .collect(Collectors.joining("\n")),
                    e);
        }

        methodVisitor.visitEnd();
    }

    private static void translateGeneratorBytecode(MethodVisitor methodVisitor, MethodDescriptor method,
            String internalClassName, LocalVariableHelper localVariableHelper, PythonCompiledFunction pythonCompiledFunction) {
        Class<?> generatorClass = PythonGeneratorTranslator.translateGeneratorFunction(pythonCompiledFunction);

        methodVisitor.visitTypeInsn(Opcodes.NEW, Type.getInternalName(generatorClass));
        methodVisitor.visitInsn(Opcodes.DUP);

        Type[] javaParameterTypes = Stream.concat(Stream.of(Type.getType(PythonLikeTuple.class),
                Type.getType(PythonLikeDict.class),
                Type.getType(PythonLikeDict.class),
                Type.getType(PythonLikeTuple.class),
                Type.getType(PythonString.class),
                Type.getType(PythonInterpreter.class)),
                pythonCompiledFunction.getParameterTypes().stream()
                        .map(type -> Type.getType(type.getJavaTypeDescriptor())))
                .toArray(Type[]::new);

        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);

        // Positional only and Positional/Keyword default arguments
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, DEFAULT_POSITIONAL_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));
        methodVisitor.visitInsn(Opcodes.SWAP);

        // Keyword only default arguments
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, DEFAULT_KEYWORD_ARGS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));
        methodVisitor.visitInsn(Opcodes.SWAP);

        // Annotation Directory as key/value tuple
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, ANNOTATION_DIRECTORY_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeDict.class));
        methodVisitor.visitInsn(Opcodes.SWAP);

        // Free variable cells
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, CELLS_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonLikeTuple.class));
        methodVisitor.visitInsn(Opcodes.SWAP);

        // Function name
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, QUALIFIED_NAME_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonString.class));
        methodVisitor.visitInsn(Opcodes.SWAP);

        // Interpreter
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, INTERPRETER_INSTANCE_FIELD_NAME,
                Type.getDescriptor(PythonInterpreter.class));

        for (int i = 0; i < pythonCompiledFunction.totalArgCount(); i++) {
            localVariableHelper.readLocal(methodVisitor, i);
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, javaParameterTypes[i + 6].getInternalName());
        }

        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(generatorClass),
                "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, javaParameterTypes),
                false);
        methodVisitor.visitInsn(Opcodes.ARETURN);

        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();
    }

    private static void movePythonParametersToSlots(MethodVisitor methodVisitor,
            String internalClassName,
            PythonCompiledFunction pythonCompiledFunction,
            LocalVariableHelper localVariableHelper) {
        // Call {@link ArgumentSpec#extractArgumentList} to extract argument into a list
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalClassName, ARGUMENT_SPEC_INSTANCE_FIELD_NAME,
                Type.getDescriptor(ArgumentSpec.class));

        methodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 2);

        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(ArgumentSpec.class),
                "extractArgumentList",
                Type.getMethodDescriptor(Type.getType(List.class), Type.getType(List.class), Type.getType(Map.class)),
                false);

        for (int i = 0; i < pythonCompiledFunction.totalArgCount(); i++) {
            methodVisitor.visitInsn(Opcodes.DUP);
            methodVisitor.visitLdcInsn(i);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(List.class), "get",
                    Type.getMethodDescriptor(Type.getType(Object.class), Type.INT_TYPE), true);
            methodVisitor.visitVarInsn(Opcodes.ASTORE, localVariableHelper.getPythonLocalVariableSlot(i));
        }
        methodVisitor.visitInsn(Opcodes.POP);
    }

    /**
     * Used for debugging; prints the offset of the instruction when it is executed
     */
    @SuppressWarnings("unused")
    private static void trace(MethodVisitor methodVisitor, PythonBytecodeInstruction instruction) {
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(System.class),
                "out", Type.getDescriptor(PrintStream.class));
        methodVisitor.visitLdcInsn(instruction.offset);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(PrintStream.class),
                "println", Type.getMethodDescriptor(Type.VOID_TYPE, Type.INT_TYPE),
                false);
    }

    /**
     * Used for debugging; prints TOS
     */
    @SuppressWarnings("unused")
    public static void print(MethodVisitor methodVisitor) {
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(System.class),
                "out", Type.getDescriptor(PrintStream.class));
        methodVisitor.visitInsn(Opcodes.SWAP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(PrintStream.class),
                "println", Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(Object.class)),
                false);
    }
}
