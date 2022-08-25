package org.optaplanner.python.translator.types.errors.io;

import java.util.List;

import org.optaplanner.python.translator.PythonLikeObject;
import org.optaplanner.python.translator.types.PythonLikeType;
import org.optaplanner.python.translator.types.errors.PythonBaseException;

/**
 * Raised when a buffer related operation cannot be performed.
 */
public class MemoryError extends PythonBaseException {
    final public static PythonLikeType MEMORY_ERROR_TYPE =
            new PythonLikeType("MemoryError", MemoryError.class, List.of(PythonBaseException.BASE_EXCEPTION_TYPE)),
            $TYPE = MEMORY_ERROR_TYPE;

    static {
        MEMORY_ERROR_TYPE.setConstructor(
                ((positionalArguments, namedArguments) -> new MemoryError(MEMORY_ERROR_TYPE, positionalArguments)));
    }

    public MemoryError(PythonLikeType type) {
        super(type);
    }

    public MemoryError(PythonLikeType type, List<PythonLikeObject> args) {
        super(type, args);
    }

    public MemoryError(PythonLikeType type, String message) {
        super(type, message);
    }
}
