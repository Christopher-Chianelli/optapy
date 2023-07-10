package org.optaplanner.jpyinterpreter.types.wrappers;

import org.optaplanner.jpyinterpreter.PythonLikeObject;
import org.optaplanner.jpyinterpreter.implementors.JavaPythonTypeConversionImplementor;
import org.optaplanner.jpyinterpreter.types.BuiltinTypes;
import org.optaplanner.jpyinterpreter.types.PythonLikeFunction;
import org.optaplanner.jpyinterpreter.types.PythonLikeType;
import org.optaplanner.jpyinterpreter.types.PythonString;
import org.optaplanner.jpyinterpreter.types.errors.TypeError;
import org.optaplanner.jpyinterpreter.types.errors.ValueError;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class AmbiguousJavaMethodReference implements PythonLikeFunction {
    private final Method[] methods;

    public AmbiguousJavaMethodReference(Method... methods) {
        this.methods = methods;
    }

    @Override
    public PythonLikeObject $call(List<PythonLikeObject> positionalArguments,
            Map<PythonString, PythonLikeObject> namedArguments, PythonLikeObject callerInstance) {
        return callMatchingMethod(callerInstance != null?
                        ((JavaObjectWrapper) callerInstance).getWrappedObject() :
                        null,
                positionalArguments);
    }

    private PythonLikeObject callMatchingMethod(Object self, List<PythonLikeObject> positionalArguments) {
        Object[] convertedArgs = new Object[positionalArguments.size()];
        for (Method method : methods) {
            try {
                if (positionalArguments.size() != method.getParameterCount()) {
                    continue;
                }

                Class<?>[] parameterTypes = method.getParameterTypes();

                for (int i = 0; i < method.getParameterCount(); i++) {
                    PythonLikeObject argument = positionalArguments.get(i);
                    convertedArgs[i] = JavaPythonTypeConversionImplementor.convertPythonObjectToJavaType(parameterTypes[i], argument);
                }
            } catch (TypeError e) {
                continue;
            }

            try {
                return JavaPythonTypeConversionImplementor.wrapJavaObject(method.invoke(self, convertedArgs));
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw (RuntimeException) e.getCause();
            }
        }
        throw new ValueError("Arguments (" + positionalArguments + ") do not match any function signature in (" +
                Arrays.toString(methods) + ")");
    }

    @Override
    public PythonLikeType __getType() {
        if (Modifier.isStatic(methods[0].getModifiers())) {
            return BuiltinTypes.STATIC_FUNCTION_TYPE;
        } else {
            return BuiltinTypes.FUNCTION_TYPE;
        }
    }
}
