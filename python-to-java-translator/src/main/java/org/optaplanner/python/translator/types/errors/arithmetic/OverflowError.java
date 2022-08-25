package org.optaplanner.python.translator.types.errors.arithmetic;

import java.util.List;

import org.optaplanner.python.translator.PythonLikeObject;
import org.optaplanner.python.translator.types.PythonLikeType;

/**
 * The base class for those built-in exceptions that are raised for various arithmetic errors
 */
public class OverflowError extends ArithmeticError {
    final public static PythonLikeType OVERFLOW_ERROR_TYPE =
            new PythonLikeType("OverflowError", OverflowError.class, List.of(ARITHMETIC_ERROR_TYPE)),
            $TYPE = OVERFLOW_ERROR_TYPE;

    static {
        OVERFLOW_ERROR_TYPE.setConstructor(
                ((positionalArguments, namedArguments) -> new OverflowError(OVERFLOW_ERROR_TYPE, positionalArguments)));
    }

    public OverflowError(PythonLikeType type) {
        super(type);
    }

    public OverflowError(PythonLikeType type, List<PythonLikeObject> args) {
        super(type, args);
    }

    public OverflowError(PythonLikeType type, String message) {
        super(type, message);
    }
}
