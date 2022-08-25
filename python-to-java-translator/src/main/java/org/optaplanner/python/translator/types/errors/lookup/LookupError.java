package org.optaplanner.python.translator.types.errors.lookup;

import java.util.List;

import org.optaplanner.python.translator.PythonLikeObject;
import org.optaplanner.python.translator.types.PythonLikeType;
import org.optaplanner.python.translator.types.errors.PythonException;

/**
 * The base class for the exceptions that are raised when a key or index used on a mapping or sequence is invalid.
 */
public class LookupError extends PythonException {
    final public static PythonLikeType LOOKUP_ERROR_TYPE =
            new PythonLikeType("LookupError", LookupError.class, List.of(EXCEPTION_TYPE)),
            $TYPE = LOOKUP_ERROR_TYPE;

    static {
        LOOKUP_ERROR_TYPE.setConstructor(
                ((positionalArguments, namedArguments) -> new LookupError(LOOKUP_ERROR_TYPE, positionalArguments)));
    }

    public LookupError(PythonLikeType type) {
        super(type);
    }

    public LookupError(PythonLikeType type, List<PythonLikeObject> args) {
        super(type, args);
    }

    public LookupError(PythonLikeType type, String message) {
        super(type, message);
    }
}
