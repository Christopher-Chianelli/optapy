package org.optaplanner.python.translator.types.errors.io;

import java.util.List;

import org.optaplanner.python.translator.PythonLikeObject;
import org.optaplanner.python.translator.types.PythonLikeType;

/**
 * Raised when a buffer related operation cannot be performed.
 */
public class IsADirectoryError extends OSError {
    final public static PythonLikeType IS_A_DIRECTORY_ERROR_TYPE =
            new PythonLikeType("IsADirectoryError", IsADirectoryError.class, List.of(OS_ERROR_TYPE)),
            $TYPE = IS_A_DIRECTORY_ERROR_TYPE;

    static {
        IS_A_DIRECTORY_ERROR_TYPE.setConstructor(((positionalArguments,
                namedArguments) -> new IsADirectoryError(IS_A_DIRECTORY_ERROR_TYPE, positionalArguments)));
    }

    public IsADirectoryError(PythonLikeType type) {
        super(type);
    }

    public IsADirectoryError(PythonLikeType type, List<PythonLikeObject> args) {
        super(type, args);
    }

    public IsADirectoryError(PythonLikeType type, String message) {
        super(type, message);
    }
}
