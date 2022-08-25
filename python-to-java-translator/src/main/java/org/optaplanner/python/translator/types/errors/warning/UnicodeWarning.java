package org.optaplanner.python.translator.types.errors.warning;

import java.util.List;

import org.optaplanner.python.translator.PythonLikeObject;
import org.optaplanner.python.translator.types.PythonLikeType;

public class UnicodeWarning extends Warning {
    public final static PythonLikeType UNICODE_WARNING_TYPE =
            new PythonLikeType("UnicodeWarning", UnicodeWarning.class, List.of(WARNING_TYPE)),
            $TYPE = UNICODE_WARNING_TYPE;

    static {
        UNICODE_WARNING_TYPE.setConstructor(
                ((positionalArguments, namedArguments) -> new UnicodeWarning(UNICODE_WARNING_TYPE, positionalArguments)));
    }

    public UnicodeWarning() {
        super(UNICODE_WARNING_TYPE);
    }

    public UnicodeWarning(String message) {
        super(UNICODE_WARNING_TYPE, message);
    }

    public UnicodeWarning(PythonLikeType type, List<PythonLikeObject> args) {
        super(type, args);
    }

    public UnicodeWarning(PythonLikeType type) {
        super(type);
    }

    public UnicodeWarning(PythonLikeType type, String message) {
        super(type, message);
    }
}
