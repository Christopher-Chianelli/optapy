package org.optaplanner.python.translator.types.errors.unicode;

import java.util.List;

import org.optaplanner.python.translator.PythonLikeObject;
import org.optaplanner.python.translator.types.PythonLikeType;
import org.optaplanner.python.translator.types.errors.ValueError;

public class UnicodeError extends ValueError {
    public final static PythonLikeType UNICODE_ERROR_TYPE =
            new PythonLikeType("UnicodeError", UnicodeError.class, List.of(VALUE_ERROR_TYPE)),
            $TYPE = UNICODE_ERROR_TYPE;

    static {
        UNICODE_ERROR_TYPE.setConstructor(
                ((positionalArguments, namedArguments) -> new UnicodeError(UNICODE_ERROR_TYPE, positionalArguments)));
    }

    public UnicodeError() {
        super(UNICODE_ERROR_TYPE);
    }

    public UnicodeError(String message) {
        super(UNICODE_ERROR_TYPE, message);
    }

    public UnicodeError(PythonLikeType type, List<PythonLikeObject> args) {
        super(type, args);
    }

    public UnicodeError(PythonLikeType type) {
        super(type);
    }

    public UnicodeError(PythonLikeType type, String message) {
        super(type, message);
    }
}
