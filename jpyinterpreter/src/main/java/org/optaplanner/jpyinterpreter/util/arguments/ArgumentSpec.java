package org.optaplanner.jpyinterpreter.util.arguments;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.optaplanner.jpyinterpreter.MethodDescriptor;
import org.optaplanner.jpyinterpreter.PythonFunctionSignature;
import org.optaplanner.jpyinterpreter.PythonLikeObject;
import org.optaplanner.jpyinterpreter.implementors.JavaPythonTypeConversionImplementor;
import org.optaplanner.jpyinterpreter.types.BuiltinTypes;
import org.optaplanner.jpyinterpreter.types.PythonLikeType;
import org.optaplanner.jpyinterpreter.types.PythonString;
import org.optaplanner.jpyinterpreter.types.collections.PythonLikeDict;
import org.optaplanner.jpyinterpreter.types.collections.PythonLikeTuple;
import org.optaplanner.jpyinterpreter.types.errors.TypeError;

public final class ArgumentSpec<Out_> {
    private final Class<Out_> functionReturnType;
    private final String functionName;
    private final List<String> argumentNameList;
    private final List<Class<?>> argumentTypeList;
    private final List<ArgumentKind> argumentKindList;
    private final List<Object> argumentDefaultList;
    private final BitSet nullableArgumentSet;
    private final Optional<Integer> extraPositionalsArgumentIndex;
    private final Optional<Integer> extraKeywordsArgumentIndex;

    private final int numberOfPositionalArguments;
    private final int requiredPositionalArguments;

    private ArgumentSpec(String functionName, Class<Out_> functionReturnType) {
        this.functionReturnType = functionReturnType;
        this.functionName = functionName + "()";
        requiredPositionalArguments = 0;
        numberOfPositionalArguments = 0;
        argumentNameList = List.of();
        argumentTypeList = List.of();
        argumentKindList = List.of();
        argumentDefaultList = List.of();
        extraPositionalsArgumentIndex = Optional.empty();
        extraKeywordsArgumentIndex = Optional.empty();
        nullableArgumentSet = new BitSet();
    }

    private ArgumentSpec(String argumentName, Class<?> argumentType, ArgumentKind argumentKind, Object defaultValue,
            Optional<Integer> extraPositionalsArgumentIndex, Optional<Integer> extraKeywordsArgumentIndex,
            boolean allowNull, ArgumentSpec<Out_> previousSpec) {
        functionName = previousSpec.functionName;
        functionReturnType = previousSpec.functionReturnType;

        if (previousSpec.numberOfPositionalArguments < previousSpec.getArgCount()) {
            numberOfPositionalArguments = previousSpec.numberOfPositionalArguments;
        } else {
            if (argumentKind.allowPositional) {
                numberOfPositionalArguments = previousSpec.getArgCount() + 1;
            } else {
                numberOfPositionalArguments = previousSpec.getArgCount();
            }
        }

        if (argumentKind == ArgumentKind.POSITIONAL_ONLY) {
            if (previousSpec.requiredPositionalArguments != previousSpec.getArgCount()) {
                throw new IllegalArgumentException("All required positional arguments must come before all other arguments");
            } else {
                requiredPositionalArguments = previousSpec.getArgCount() + 1;
            }
        } else {
            requiredPositionalArguments = previousSpec.requiredPositionalArguments;
        }

        argumentNameList = new ArrayList<>(previousSpec.argumentNameList.size() + 1);
        argumentTypeList = new ArrayList<>(previousSpec.argumentTypeList.size() + 1);
        argumentKindList = new ArrayList<>(previousSpec.argumentKindList.size() + 1);
        argumentDefaultList = new ArrayList<>(previousSpec.argumentDefaultList.size() + 1);

        argumentNameList.addAll(previousSpec.argumentNameList);
        argumentNameList.add(argumentName);

        argumentTypeList.addAll(previousSpec.argumentTypeList);
        argumentTypeList.add(argumentType);

        argumentKindList.addAll(previousSpec.argumentKindList);
        argumentKindList.add(argumentKind);

        argumentDefaultList.addAll(previousSpec.argumentDefaultList);
        argumentDefaultList.add(defaultValue);

        if (extraPositionalsArgumentIndex.isPresent() && previousSpec.extraPositionalsArgumentIndex.isPresent()) {
            throw new IllegalArgumentException("Multiple positional vararg arguments");
        }
        if (previousSpec.extraPositionalsArgumentIndex.isPresent()) {
            extraPositionalsArgumentIndex = previousSpec.extraPositionalsArgumentIndex;
        }

        if (extraKeywordsArgumentIndex.isPresent() && previousSpec.extraKeywordsArgumentIndex.isPresent()) {
            throw new IllegalArgumentException("Multiple keyword vararg arguments");
        }
        if (previousSpec.extraKeywordsArgumentIndex.isPresent()) {
            extraKeywordsArgumentIndex = previousSpec.extraKeywordsArgumentIndex;
        }

        this.extraPositionalsArgumentIndex = extraPositionalsArgumentIndex;
        this.extraKeywordsArgumentIndex = extraKeywordsArgumentIndex;
        this.nullableArgumentSet = (BitSet) previousSpec.nullableArgumentSet.clone();
        if (allowNull) {
            nullableArgumentSet.set(argumentNameList.size() - 1);
        }
    }

    public static <T extends PythonLikeObject> ArgumentSpec<T> forFunctionReturning(String functionName,
            Class<T> outClass) {
        return new ArgumentSpec<>(functionName, outClass);
    }

    private int getArgCount() {
        return argumentNameList.size();
    }

    public List<PythonLikeObject> extractArgumentList(List<PythonLikeObject> positionalArguments,
            Map<PythonString, PythonLikeObject> keywordArguments) {
        List<PythonLikeObject> out = new ArrayList<>(argumentNameList.size());

        if (positionalArguments.size() > numberOfPositionalArguments &&
                extraPositionalsArgumentIndex.isEmpty()) {
            throw new TypeError(functionName + " takes " + numberOfPositionalArguments + " positional arguments but "
                    + positionalArguments.size() + " were given");
        }

        if (positionalArguments.size() < requiredPositionalArguments) {
            int missing = (requiredPositionalArguments - positionalArguments.size());
            String argumentString = (missing == 1) ? "argument" : "arguments";
            List<String> missingArgumentNames = argumentNameList.subList(argumentNameList.size() - missing,
                    argumentNameList.size());
            throw new TypeError(functionName + " missing " + (requiredPositionalArguments - positionalArguments.size()) +
                    " required positional " + argumentString + ": '" + String.join("', ", missingArgumentNames) + "'");
        }

        int numberOfSetArguments = Math.min(numberOfPositionalArguments, positionalArguments.size());
        out.addAll(positionalArguments.subList(0, numberOfSetArguments));
        for (int i = numberOfSetArguments; i < argumentNameList.size(); i++) {
            out.add(null);
        }

        int remaining = argumentNameList.size() - numberOfSetArguments;

        PythonLikeDict extraKeywordArguments = null;
        if (extraPositionalsArgumentIndex.isPresent()) {
            remaining--;
            out.set(extraPositionalsArgumentIndex.get(),
                    PythonLikeTuple
                            .fromList(positionalArguments.subList(numberOfSetArguments, positionalArguments.size())));
        }

        if (extraKeywordsArgumentIndex.isPresent()) {
            remaining--;
            extraKeywordArguments = new PythonLikeDict();
            out.set(extraKeywordsArgumentIndex.get(),
                    extraKeywordArguments);
        }

        for (Map.Entry<PythonString, PythonLikeObject> keywordArgument : keywordArguments.entrySet()) {
            PythonString argumentName = keywordArgument.getKey();

            int position = argumentNameList.indexOf(argumentName.value);
            if (position == -1) {
                if (extraKeywordsArgumentIndex.isPresent()) {
                    extraKeywordArguments.put(argumentName, keywordArgument.getValue());
                    continue;
                } else {
                    throw new TypeError(functionName + " got an unexpected keyword argument " + argumentName.repr().value);
                }
            }

            if (out.get(position) != null) {
                throw new TypeError(functionName + " got multiple values for argument " + argumentName.repr().value);
            }

            if (!argumentKindList.get(position).allowKeyword) {
                throw new TypeError(functionName + " got some positional-only arguments passed as keyword arguments: "
                        + argumentName.repr().value);
            }

            remaining--;
            out.set(position, keywordArgument.getValue());
        }

        if (remaining > 0) {
            List<Integer> missing = new ArrayList<>(remaining);
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i) == null) {
                    if (argumentDefaultList.get(i) != null || nullableArgumentSet.get(i)) {
                        out.set(i, (PythonLikeObject) argumentDefaultList.get(i));
                        remaining--;
                    } else {
                        missing.add(i);
                    }
                }
            }

            if (remaining > 0) {
                if (missing.stream().anyMatch(index -> argumentKindList.get(index).allowPositional)) {
                    List<String> missingAllowsPositional = new ArrayList<>(remaining);
                    for (int index : missing) {
                        if (argumentKindList.get(index).allowPositional) {
                            missingAllowsPositional.add(argumentNameList.get(index));
                        }
                    }
                    String argumentString = (missingAllowsPositional.size() == 1) ? "argument" : "arguments";
                    throw new TypeError(functionName + " missing " + remaining + " required positional " + argumentString
                            + ": '" + String.join("', ", missingAllowsPositional) + "'");
                } else {
                    List<String> missingKeywordOnly = new ArrayList<>(remaining);
                    for (int index : missing) {
                        missingKeywordOnly.add(argumentNameList.get(index));
                    }
                    String argumentString = (missingKeywordOnly.size() == 1) ? "argument" : "arguments";
                    throw new TypeError(functionName + " missing " + remaining + " required keyword-only " + argumentString
                            + ": '" + String.join("', ", missingKeywordOnly) + "'");
                }
            }
        }

        for (int i = 0; i < argumentNameList.size(); i++) {
            if ((out.get(i) == null && !nullableArgumentSet.get(i))
                    || (out.get(i) != null && !argumentTypeList.get(i).isInstance(out.get(i)))) {
                throw new TypeError(functionName + "'s argument '" + argumentNameList.get(i) + "' has incorrect type: " +
                        "'" + argumentNameList.get(i) + "' must be a " +
                        JavaPythonTypeConversionImplementor.getPythonLikeType(argumentTypeList.get(i)) +
                        " (got "
                        + ((out.get(i) != null) ? JavaPythonTypeConversionImplementor.getPythonLikeType(out.get(i).getClass())
                                : "NULL")
                        + " instead)");
            }
        }
        return out;
    }

    private <ArgumentType_ extends PythonLikeObject> ArgumentSpec<Out_> addArgument(String argumentName,
            Class<ArgumentType_> argumentType, ArgumentKind argumentKind, ArgumentType_ defaultValue,
            Optional<Integer> extraPositionalsArgumentIndex, Optional<Integer> extraKeywordsArgumentIndex, boolean allowNull) {
        return new ArgumentSpec<>(argumentName, argumentType, argumentKind, defaultValue,
                extraPositionalsArgumentIndex, extraKeywordsArgumentIndex, allowNull, this);
    }

    public <ArgumentType_ extends PythonLikeObject> ArgumentSpec<Out_> addArgument(String argumentName,
            Class<ArgumentType_> argumentType) {
        return addArgument(argumentName, argumentType, ArgumentKind.POSITIONAL_AND_KEYWORD, null,
                Optional.empty(), Optional.empty(), false);
    }

    public <ArgumentType_ extends PythonLikeObject> ArgumentSpec<Out_>
            addPositionalOnlyArgument(String argumentName, Class<ArgumentType_> argumentType) {
        return addArgument(argumentName, argumentType, ArgumentKind.POSITIONAL_ONLY, null,
                Optional.empty(), Optional.empty(), false);
    }

    public <ArgumentType_ extends PythonLikeObject> ArgumentSpec<Out_>
            addKeywordOnlyArgument(String argumentName, Class<ArgumentType_> argumentType) {
        return addArgument(argumentName, argumentType, ArgumentKind.KEYWORD_ONLY, null,
                Optional.empty(), Optional.empty(), false);
    }

    public <ArgumentType_ extends PythonLikeObject> ArgumentSpec<Out_> addArgument(String argumentName,
            Class<ArgumentType_> argumentType, ArgumentType_ defaultValue) {
        return addArgument(argumentName, argumentType, ArgumentKind.POSITIONAL_AND_KEYWORD, defaultValue,
                Optional.empty(), Optional.empty(), false);
    }

    public <ArgumentType_ extends PythonLikeObject> ArgumentSpec<Out_>
            addPositionalOnlyArgument(String argumentName, Class<ArgumentType_> argumentType, ArgumentType_ defaultValue) {
        return addArgument(argumentName, argumentType, ArgumentKind.POSITIONAL_ONLY, defaultValue,
                Optional.empty(), Optional.empty(), false);
    }

    public <ArgumentType_ extends PythonLikeObject> ArgumentSpec<Out_>
            addKeywordOnlyArgument(String argumentName, Class<ArgumentType_> argumentType, ArgumentType_ defaultValue) {
        return addArgument(argumentName, argumentType, ArgumentKind.KEYWORD_ONLY, defaultValue,
                Optional.empty(), Optional.empty(), false);
    }

    public <ArgumentType_ extends PythonLikeObject> ArgumentSpec<Out_> addNullableArgument(String argumentName,
            Class<ArgumentType_> argumentType) {
        return addArgument(argumentName, argumentType, ArgumentKind.KEYWORD_ONLY, null,
                Optional.empty(), Optional.empty(), true);
    }

    public <ArgumentType_ extends PythonLikeObject> ArgumentSpec<Out_> addNullablePositionalOnlyArgument(String argumentName,
            Class<ArgumentType_> argumentType) {
        return addArgument(argumentName, argumentType, ArgumentKind.KEYWORD_ONLY, null,
                Optional.empty(), Optional.empty(), true);
    }

    public <ArgumentType_ extends PythonLikeObject> ArgumentSpec<Out_> addNullableKeywordOnlyArgument(String argumentName,
            Class<ArgumentType_> argumentType) {
        return addArgument(argumentName, argumentType, ArgumentKind.KEYWORD_ONLY, null,
                Optional.empty(), Optional.empty(), true);
    }

    public ArgumentSpec<Out_> addExtraPositionalVarArgument(String argumentName) {
        return addArgument(argumentName, PythonLikeTuple.class, ArgumentKind.VARARGS, null,
                Optional.of(getArgCount()), Optional.empty(), false);
    }

    public ArgumentSpec<Out_> addExtraKeywordVarArgument(String argumentName) {
        return addArgument(argumentName, PythonLikeDict.class, ArgumentKind.VARARGS, null,
                Optional.empty(), Optional.of(getArgCount()), false);
    }

    public PythonFunctionSignature asPythonFunctionSignature(Method method) {
        verifyMethodMatchesSpec(method);
        return getPythonFunctionSignatureForMethodDescriptor(new MethodDescriptor(method),
                method.getReturnType());
    }

    public PythonFunctionSignature asStaticPythonFunctionSignature(Method method) {
        verifyMethodMatchesSpec(method);
        return getPythonFunctionSignatureForMethodDescriptor(new MethodDescriptor(method, MethodDescriptor.MethodType.STATIC),
                method.getReturnType());
    }

    public PythonFunctionSignature asClassPythonFunctionSignature(Method method) {
        verifyMethodMatchesSpec(method);
        return getPythonFunctionSignatureForMethodDescriptor(new MethodDescriptor(method, MethodDescriptor.MethodType.CLASS),
                method.getReturnType());
    }

    public PythonFunctionSignature asPythonFunctionSignature(String internalClassName, String methodName,
            String methodDescriptor) {
        MethodDescriptor method = new MethodDescriptor(internalClassName, MethodDescriptor.MethodType.VIRTUAL,
                methodName, methodDescriptor);
        try {
            return getPythonFunctionSignatureForMethodDescriptor(method,
                    BuiltinTypes.asmClassLoader.loadClass(
                            method.getReturnType().getClassName().replace('/', '.')));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public PythonFunctionSignature asStaticPythonFunctionSignature(String internalClassName, String methodName,
            String methodDescriptor) {
        MethodDescriptor method = new MethodDescriptor(internalClassName, MethodDescriptor.MethodType.STATIC,
                methodName, methodDescriptor);
        try {
            return getPythonFunctionSignatureForMethodDescriptor(method,
                    BuiltinTypes.asmClassLoader.loadClass(
                            method.getReturnType().getClassName().replace('/', '.')));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public PythonFunctionSignature asClassPythonFunctionSignature(String internalClassName, String methodName,
            String methodDescriptor) {
        MethodDescriptor method = new MethodDescriptor(internalClassName, MethodDescriptor.MethodType.CLASS,
                methodName, methodDescriptor);
        try {
            return getPythonFunctionSignatureForMethodDescriptor(method,
                    BuiltinTypes.asmClassLoader.loadClass(
                            method.getReturnType().getClassName().replace('/', '.')));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private void verifyMethodMatchesSpec(Method method) {
        if (!functionReturnType.isAssignableFrom(method.getReturnType())) {
            throw new IllegalArgumentException("Method (" + method + ") does not match the given spec (" + this +
                    "): its return type (" + method.getReturnType() + ") is not " +
                    "assignable to the spec return type (" + functionReturnType + ").");
        }

        if (method.getParameterCount() != argumentNameList.size()) {
            throw new IllegalArgumentException("Method (" + method + ") does not match the given spec (" + this +
                    "): they have different parameter counts.");
        }

        for (int i = 0; i < method.getParameterCount(); i++) {
            if (!method.getParameterTypes()[i].isAssignableFrom(argumentTypeList.get(i))) {
                throw new IllegalArgumentException("Method (" + method + ") does not match the given spec (" + this +
                        "): its " + i + " parameter (" + method.getParameters()[i].toString() + ") cannot " +
                        " be assigned from the spec " + i + " parameter (" + argumentTypeList.get(i) + " "
                        + argumentNameList.get(i) + ").");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private PythonFunctionSignature getPythonFunctionSignatureForMethodDescriptor(MethodDescriptor methodDescriptor,
            Class<?> javaReturnType) {
        int firstDefault = 0;

        while (firstDefault < argumentDefaultList.size() && argumentDefaultList.get(firstDefault) == null &&
                !nullableArgumentSet.get(firstDefault)) {
            firstDefault++;
        }
        List<PythonLikeObject> defaultParameterValueList;

        if (firstDefault != argumentDefaultList.size()) {
            defaultParameterValueList = (List<PythonLikeObject>) (List<?>) argumentDefaultList.subList(firstDefault,
                    argumentDefaultList.size());
        } else {
            defaultParameterValueList = List.of();
        }

        List<PythonLikeType> parameterTypeList = argumentTypeList.stream()
                .map(JavaPythonTypeConversionImplementor::getPythonLikeType)
                .collect(Collectors.toList());

        PythonLikeType returnType = JavaPythonTypeConversionImplementor.getPythonLikeType(javaReturnType);
        Map<String, Integer> keywordArgumentToIndexMap = new HashMap<>();

        for (int i = 0; i < argumentNameList.size(); i++) {
            if (argumentKindList.get(i).allowKeyword) {
                keywordArgumentToIndexMap.put(argumentNameList.get(i), i);
            }
        }

        return new PythonFunctionSignature(methodDescriptor, defaultParameterValueList,
                keywordArgumentToIndexMap, returnType,
                parameterTypeList, extraPositionalsArgumentIndex, extraKeywordsArgumentIndex,
                this);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("ArgumentSpec(");
        out.append("name=").append(functionName)
                .append(", returnType=").append(functionReturnType)
                .append(", arguments=[");

        for (int i = 0; i < argumentNameList.size(); i++) {
            out.append(argumentTypeList.get(i));
            out.append(" ");
            out.append(argumentNameList.get(i));

            if (nullableArgumentSet.get(i)) {
                out.append(" (nullable)");
            }

            if (argumentDefaultList.get(i) != null) {
                out.append(" (default: ");
                out.append(argumentDefaultList.get(i));
                out.append(")");
            }

            if (argumentKindList.get(i) != ArgumentKind.POSITIONAL_AND_KEYWORD) {
                if (extraPositionalsArgumentIndex.isPresent() && extraPositionalsArgumentIndex.get() == i) {
                    out.append(" (vargs)");
                } else if (extraKeywordsArgumentIndex.isPresent() && extraKeywordsArgumentIndex.get() == i) {
                    out.append(" (kwargs)");
                } else {
                    out.append(" (");
                    out.append(argumentKindList.get(i));
                    out.append(")");
                }
            }
            if (i != argumentNameList.size() - 1) {
                out.append(", ");
            }
        }
        out.append("])");

        return out.toString();
    }
}
