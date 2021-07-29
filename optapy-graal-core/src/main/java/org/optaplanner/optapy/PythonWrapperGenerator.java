package org.optaplanner.optapy;

import io.quarkus.gizmo.AnnotationCreator;
import io.quarkus.gizmo.BranchResult;
import io.quarkus.gizmo.BytecodeCreator;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyNativeObject;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.objectweb.asm.Type;
import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.lookup.PlanningId;
import org.optaplanner.core.api.domain.solution.PlanningEntityCollectionProperty;
import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.domain.solution.ProblemFactCollectionProperty;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;

import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

public class PythonWrapperGenerator {
    /**
     * The Gizmo generated bytecode. Used by
     * gizmoClassLoader when not run in Quarkus
     * in order to create an instance of the Member
     * Accessor
     */
    private static final Map<String, byte[]> classNameToBytecode = new HashMap<>();

    static final String pythonBindingFieldName = "__optaplannerPythonValue";
    static final String valueToInstanceMapFieldName = "__optaplannerPythonValueToInstanceMap";

    /**
     * A custom classloader that looks for the class in
     * classNameToBytecode
     */
    static ClassLoader gizmoClassLoader = new ClassLoader() {
        // getName() is an abstract method in Java 11 but not in Java 8
        public String getName() {
            return "OptaPlanner Gizmo Python Wrapper ClassLoader";
        }

        @Override
        public Class<?> findClass(String name) throws ClassNotFoundException {
            if (classNameToBytecode.containsKey(name)) {
                // Gizmo generated class
                byte[] byteCode = classNameToBytecode.get(name);
                return defineClass(name, byteCode, 0, byteCode.length);
            } else {
                // Not a Gizmo generated class; load from parent class loader
                return PythonWrapperGenerator.class.getClassLoader().loadClass(name);
            }
        }
    };

    public static Class<?> getArrayClass(Class<?> elementClass) {
        return Array.newInstance(elementClass, 0).getClass();
    }

    public static <T> T wrap(Class<T> javaClass, Value object) {
        return wrap(javaClass, object, new HashMap<>());
    }

    public static Value unwrap(Class<?> javaClass, Object object) {
        try {
            return (Value) javaClass.getField(pythonBindingFieldName).get(object);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    public static <T> T wrap(Class<T> javaClass, Value object, Map<Value, Object> valueObjectMap) {
        if (valueObjectMap.containsKey(object)) {
            return (T) valueObjectMap.get(object);
        }

        if (object.isNull()) {
            return null;
        }
        try {
            if (javaClass.isArray()) {
                int length = (int) object.getArraySize();
                Object out = Array.newInstance(javaClass.getComponentType(), length);
                for (int i = 0; i < length; i++) {
                    Array.set(out, i, wrap(javaClass.getComponentType(), object.getArrayElement(i), valueObjectMap));
                }
                return (T) out;
            } else {
                T out = (T) javaClass.getConstructor(Value.class, Map.class).newInstance(object, valueObjectMap);
                valueObjectMap.put(object, out);
                return out;
            }
        } catch (IllegalAccessException | NoSuchMethodException | InstantiationException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Supplier<Object> wrapObject(Class<?> javaClass, Value object) {
        Object out = wrap(javaClass, object);
        return () -> out;
    }

    public static Class<?> defineConstraintProviderClass(String className, Function<ConstraintProvider, Constraint[]> defineConstraintsImpl) {
        className = "org.optaplanner.optapy.generated." + className + ".GeneratedClass";
        if (classNameToBytecode.containsKey(className)) {
            try {
                return gizmoClassLoader.loadClass(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Impossible State: the class (" + className + ") should exists since it was created");
            }
        }
        AtomicReference<byte[]> classBytecodeHolder = new AtomicReference<>();
        ClassOutput classOutput = (path, byteCode) -> {
            classBytecodeHolder.set(byteCode);
        };
        FieldDescriptor valueField;
        try(ClassCreator classCreator = ClassCreator.builder()
                .className(className)
                .interfaces(ConstraintProvider.class)
                .classOutput(classOutput)
                .build()) {
            valueField = classCreator.getFieldCreator("__defineConstraintsImpl", Function.class)
                    .setModifiers(Modifier.STATIC | Modifier.PUBLIC)
                    .getFieldDescriptor();
            MethodCreator methodCreator = classCreator.getMethodCreator(MethodDescriptor.ofMethod(ConstraintProvider.class,
                    "defineConstraints", Constraint[].class, ConstraintFactory.class));
            ResultHandle pythonMethod = methodCreator.readStaticField(valueField);
            ResultHandle constraints = methodCreator.invokeInterfaceMethod(MethodDescriptor.ofMethod(Function.class, "apply", Object.class, Object.class),
                    pythonMethod, methodCreator.getMethodParam(0));
            methodCreator.returnValue(constraints);
        } catch (Exception e) {
            throw e;
        }
        classNameToBytecode.put(className, classBytecodeHolder.get());
        try {
            Class<?> out = gizmoClassLoader.loadClass(className);
            out.getField(valueField.getName()).set(null, defineConstraintsImpl);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Impossible State: the class (" + className + ") should exists since it was just created");
        }
    }

    public static Class<?> definePlanningEntityClass(String className, Object[][] optaplannerMethodAnnotations) {
        className = "org.optaplanner.optapy.generated." + className + ".GeneratedClass";
        if (classNameToBytecode.containsKey(className)) {
            try {
                return gizmoClassLoader.loadClass(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Impossible State: the class (" + className + ") should exists since it was created");
            }
        }
        AtomicReference<byte[]> classBytecodeHolder = new AtomicReference<>();
        ClassOutput classOutput = (path, byteCode) -> {
            classBytecodeHolder.set(byteCode);
        };
        try(ClassCreator classCreator = ClassCreator.builder()
                .className(className)
                .interfaces(ProxyObject.class, ProxyNativeObject.class)
                .classOutput(classOutput)
                .build()) {
            classCreator.addAnnotation(PlanningEntity.class);
            FieldDescriptor valueField = classCreator.getFieldCreator(pythonBindingFieldName, Value.class)
                    .setModifiers(Modifier.PUBLIC).getFieldDescriptor();
            generateWrapperMethods(classCreator, valueField, optaplannerMethodAnnotations);
        }
        classNameToBytecode.put(className, classBytecodeHolder.get());
        try {
            return gizmoClassLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Impossible State: the class (" + className + ") should exists since it was just created");
        }
    }

    public static Class<?> defineProblemFactClass(String className, Object[][] optaplannerMethodAnnotations) {
        className = "org.optaplanner.optapy.generated." + className + ".GeneratedClass";
        if (classNameToBytecode.containsKey(className)) {
            try {
                return gizmoClassLoader.loadClass(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Impossible State: the class (" + className + ") should exists since it was created");
            }
        }
        AtomicReference<byte[]> classBytecodeHolder = new AtomicReference<>();
        ClassOutput classOutput = (path, byteCode) -> {
            classBytecodeHolder.set(byteCode);
        };
        try(ClassCreator classCreator = ClassCreator.builder()
                .className(className)
                .interfaces(ProxyObject.class, ProxyNativeObject.class)
                .classOutput(classOutput)
                .build()) {
            FieldDescriptor valueField = classCreator.getFieldCreator(pythonBindingFieldName, Value.class)
                    .setModifiers(Modifier.PUBLIC).getFieldDescriptor();
            generateWrapperMethods(classCreator, valueField, optaplannerMethodAnnotations);
        }
        classNameToBytecode.put(className, classBytecodeHolder.get());
        try {
            return gizmoClassLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Impossible State: the class (" + className + ") should exists since it was just created");
        }
    }

    public static Class<?> definePlanningSolutionClass(String className, Object[][] optaplannerMethodAnnotations) {
        className = "org.optaplanner.optapy.generated." + className + ".GeneratedClass";
        if (classNameToBytecode.containsKey(className)) {
            try {
                return gizmoClassLoader.loadClass(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Impossible State: the class (" + className + ") should exists since it was created");
            }
        }
        AtomicReference<byte[]> classBytecodeHolder = new AtomicReference<>();
        ClassOutput classOutput = (path, byteCode) -> {
            classBytecodeHolder.set(byteCode);
        };
        try(ClassCreator classCreator = ClassCreator.builder()
                .className(className)
                .interfaces(ProxyObject.class, ProxyNativeObject.class)
                .classOutput(classOutput)
                .build()) {
            classCreator.addAnnotation(PlanningSolution.class)
                .addValue("solutionCloner", Type.getType(PythonPlanningSolutionCloner.class));
            FieldDescriptor valueField = classCreator.getFieldCreator(pythonBindingFieldName, Value.class)
                    .setModifiers(Modifier.PUBLIC).getFieldDescriptor();
            generateWrapperMethods(classCreator, valueField, optaplannerMethodAnnotations);
        }
        classNameToBytecode.put(className, classBytecodeHolder.get());
        try {
            return gizmoClassLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Impossible State: the class (" + className + ") should exists since it was just created");
        }
    }

    private static void print(MethodCreator methodCreator, ResultHandle toPrint) {
        ResultHandle out = methodCreator.readStaticField(FieldDescriptor.of(System.class, "out", PrintStream.class));
        methodCreator.invokeVirtualMethod(MethodDescriptor.ofMethod(PrintStream.class, "println", void.class, Object.class),
                out, toPrint);
    }

    private static void generateProxyObjectMethods(ClassCreator classCreator, FieldDescriptor valueField) {
        MethodCreator methodCreator;
        ResultHandle valueResultHandle;

        methodCreator = classCreator.getMethodCreator("getMember", Object.class, String.class);
        valueResultHandle = methodCreator.readInstanceField(valueField, methodCreator.getThis());
        methodCreator.returnValue(methodCreator.invokeVirtualMethod(MethodDescriptor.ofMethod(Value.class, "getMember", Value.class, String.class),
                valueResultHandle, methodCreator.getMethodParam(0)));

        methodCreator = classCreator.getMethodCreator("getMemberKeys", Object.class);
        valueResultHandle = methodCreator.readInstanceField(valueField, methodCreator.getThis());
        methodCreator.returnValue(methodCreator.invokeVirtualMethod(MethodDescriptor.ofMethod(Value.class, "getMemberKeys", Set.class),
                valueResultHandle));

        methodCreator = classCreator.getMethodCreator("hasMember", boolean.class, String.class);
        valueResultHandle = methodCreator.readInstanceField(valueField, methodCreator.getThis());
        methodCreator.returnValue(methodCreator.invokeVirtualMethod(MethodDescriptor.ofMethod(Value.class, "hasMember", boolean.class, String.class),
                valueResultHandle, methodCreator.getMethodParam(0)));

        methodCreator = classCreator.getMethodCreator("putMember", void.class, String.class, Value.class);
        methodCreator.returnValue(null);
    }

    private static void generateAsPointer(ClassCreator classCreator, FieldDescriptor valueField) {
        MethodCreator methodCreator = classCreator.getMethodCreator("asPointer", long.class);
        ResultHandle valueResultHandle = methodCreator.readInstanceField(valueField, methodCreator.getThis());
        methodCreator.returnValue(methodCreator.invokeVirtualMethod(MethodDescriptor.ofMethod(Value.class, "asNativePointer", long.class),
                valueResultHandle));
    }

    private static void generateWrapperMethods(ClassCreator classCreator, FieldDescriptor valueField, Object[][] optaplannerMethodAnnotations) {
        generateAsPointer(classCreator, valueField);
        generateProxyObjectMethods(classCreator, valueField);

        List<FieldDescriptor> fieldDescriptorList = new ArrayList<>(optaplannerMethodAnnotations.length);
        List<Class<?>> returnTypeList = new ArrayList<>(optaplannerMethodAnnotations.length);
        for (int i = 0; i < optaplannerMethodAnnotations.length; i++) {
            String methodName = (String) (optaplannerMethodAnnotations[i][0]);
            Class<?> returnType = (Class<?>) (optaplannerMethodAnnotations[i][1]);
            if (returnType == null) {
                returnType = Object.class;
            }
            Value annotations = Value.asValue(optaplannerMethodAnnotations[i][2]);
            fieldDescriptorList.add(generateWrapperMethod(classCreator, valueField, methodName, returnType, annotations, returnTypeList));
        }
        createConstructor(classCreator, valueField, fieldDescriptorList, returnTypeList);
    }

    private static void createConstructor(ClassCreator classCreator, FieldDescriptor valueField, List<FieldDescriptor> fieldDescriptorList,
            List<Class<?>> returnTypeList) {
        FieldDescriptor mapField = classCreator.getFieldCreator(valueToInstanceMapFieldName, Map.class).getFieldDescriptor();

        MethodCreator methodCreator = classCreator.getMethodCreator(MethodDescriptor.ofConstructor(classCreator.getClassName(), Value.class, Map.class));
        methodCreator.invokeSpecialMethod(MethodDescriptor.ofConstructor(Object.class), methodCreator.getThis());
        ResultHandle value = methodCreator.getMethodParam(0);
        methodCreator.writeInstanceField(valueField, methodCreator.getThis(), value);
        ResultHandle map = methodCreator.getMethodParam(1);
        methodCreator.writeInstanceField(mapField, methodCreator.getThis(), map);

        for (int i = 0; i < fieldDescriptorList.size(); i++) {
            FieldDescriptor fieldDescriptor = fieldDescriptorList.get(i);
            Class returnType = returnTypeList.get(i);
            String methodName = fieldDescriptor.getName().substring(0, fieldDescriptor.getName().length() - 6);
            ResultHandle valueResultHandle = methodCreator.readInstanceField(valueField, methodCreator.getThis());
            ResultHandle getterResultHandle = methodCreator.invokeVirtualMethod(MethodDescriptor.ofMethod(Value.class, "getMember", Value.class, String.class),
                    valueResultHandle, methodCreator.load(methodName));
            ResultHandle outResultHandle = methodCreator.invokeVirtualMethod(MethodDescriptor.ofMethod(Value.class, "execute", Value.class, Object[].class),
                    getterResultHandle, methodCreator.newArray(Object.class, 0));
            if ( Comparable.class.isAssignableFrom(returnType) ) {
                methodCreator.writeInstanceField(fieldDescriptor, methodCreator.getThis(), methodCreator.invokeVirtualMethod(MethodDescriptor.ofMethod(Value.class, "as", Object.class, Class.class),
                        outResultHandle, methodCreator.loadClass(returnType)));
            } else {
                methodCreator.writeInstanceField(fieldDescriptor, methodCreator.getThis(),
                        methodCreator.invokeStaticMethod(MethodDescriptor.ofMethod(PythonWrapperGenerator.class,
                            "wrap", Object.class, Class.class, Value.class, Map.class),
                            methodCreator.loadClass(returnType),
                            outResultHandle,
                            map));
            }
        }
        // methodCreator.returnValue(null);
        methodCreator.returnValue(methodCreator.getThis());
    }

    private static FieldDescriptor generateWrapperMethod(ClassCreator classCreator, FieldDescriptor valueField, String methodName, Class<?> returnType, Value annotations,
            List<Class<?>> returnTypeList) {
        for (int i = 0; i < annotations.getArraySize(); i++) {
            Value value = annotations.getArrayElement(i);
            Annotation annotation = value.as(Annotation.class);
            if (PlanningId.class.isAssignableFrom(annotation.annotationType()) && !Comparable.class.isAssignableFrom(returnType)) {
                returnType = Comparable.class;
            } else if ((ProblemFactCollectionProperty.class.isAssignableFrom(annotation.annotationType()) || PlanningEntityCollectionProperty.class.isAssignableFrom(annotation.annotationType())) && !(Collection.class.isAssignableFrom(returnType) || returnType.isArray())) {
                returnType = Object[].class;
            }
        }
        returnTypeList.add(returnType);
        FieldDescriptor fieldDescriptor = classCreator.getFieldCreator(methodName + "$field", returnType).getFieldDescriptor();
        MethodCreator methodCreator = classCreator.getMethodCreator(methodName, returnType);
        for (int i = 0; i < annotations.getArraySize(); i++) {
            Value value = annotations.getArrayElement(i);
            Annotation annotation = value.as(Annotation.class);
            AnnotationCreator annotationCreator = methodCreator.addAnnotation(annotation.annotationType());
            for (Method method : annotation.annotationType().getMethods()) {
                if (method.getParameterCount() != 0 || !method.getDeclaringClass().equals(annotation.annotationType())) {
                    continue;
                }
                Object annotationValue = value.getMember(method.getName()).execute().as(method.getReturnType());
                if (annotationValue != null) {
                    annotationCreator.addValue(method.getName(), annotationValue);
                }
            }
        }
        methodCreator.returnValue(methodCreator.readInstanceField(fieldDescriptor, methodCreator.getThis()));

        if (methodName.startsWith("get")) {
            String setterMethodName = "set" + methodName.substring(3);
            MethodCreator setterMethodCreator = classCreator.getMethodCreator(setterMethodName, void.class, returnType);

            ResultHandle pythonSetter = setterMethodCreator.invokeVirtualMethod(MethodDescriptor.ofMethod(Value.class, "getMember", Value.class, String.class),
                    setterMethodCreator.readInstanceField(valueField, setterMethodCreator.getThis()),
                    setterMethodCreator.load(setterMethodName));
            ResultHandle argsArray = setterMethodCreator.newArray(Object.class, 1);
            if (ProxyObject.class.isAssignableFrom(returnType)) {
                ResultHandle parmeter = setterMethodCreator.getMethodParam(0);
                BranchResult branchResult = setterMethodCreator.ifNull(parmeter);
                branchResult.trueBranch().writeArrayValue(argsArray, 0, parmeter);
                BytecodeCreator notNullBranch = branchResult.falseBranch();
                notNullBranch.writeArrayValue(argsArray, 0, notNullBranch.readInstanceField(FieldDescriptor.of(returnType, valueField.getName(), valueField.getType()),
                                                                                                        parmeter));
            } else {
                setterMethodCreator.writeArrayValue(argsArray, 0, setterMethodCreator.getMethodParam(0));
            }
            setterMethodCreator.invokeVirtualMethod(MethodDescriptor.ofMethod(Value.class, "execute", Value.class, Object[].class),
                                                    pythonSetter, argsArray);
            setterMethodCreator.writeInstanceField(fieldDescriptor, setterMethodCreator.getThis(), setterMethodCreator.getMethodParam(0));
            setterMethodCreator.returnValue(null);
        }
        return fieldDescriptor;
    }
}
