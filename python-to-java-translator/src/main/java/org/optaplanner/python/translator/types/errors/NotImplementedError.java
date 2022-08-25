package org.optaplanner.python.translator.types.errors;

import java.util.List;

import org.optaplanner.python.translator.PythonLikeObject;
import org.optaplanner.python.translator.types.PythonLikeType;

/**
 * Raised when a buffer related operation cannot be performed.
 */
public class NotImplementedError extends RuntimeError {
    final public static PythonLikeType NOT_IMPLEMENTED_ERROR_TYPE =
            new PythonLikeType("NotImplementedError", NotImplementedError.class, List.of(RUNTIME_ERROR_TYPE)),
            $TYPE = NOT_IMPLEMENTED_ERROR_TYPE;

    static {
        NOT_IMPLEMENTED_ERROR_TYPE.setConstructor(((positionalArguments,
                namedArguments) -> new NotImplementedError(NOT_IMPLEMENTED_ERROR_TYPE, positionalArguments)));
    }

    public NotImplementedError(PythonLikeType type) {
        super(type);
    }

    public NotImplementedError(PythonLikeType type, String message) {
        super(type, message);
    }

    public NotImplementedError(PythonLikeType type, List<PythonLikeObject> args) {
        super(type, args);
    }
}
