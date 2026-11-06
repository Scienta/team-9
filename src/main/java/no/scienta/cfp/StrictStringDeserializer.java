package no.scienta.cfp;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * A String field that only accepts a JSON string. By default Jackson turns 123 or true into "123" or "true", which
 * would store a wrong type instead of answering 400 (spec-questions.md, malformed input). The mismatch surfaces as
 * HttpMessageNotReadableException, a 400. JSON null still reaches the field as null, for @NotBlank to reject.
 */
public class StrictStringDeserializer extends StdDeserializer<String> {

    public StrictStringDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            return (String) ctx.handleUnexpectedToken(String.class, p);
        }
        return p.getText();
    }
}
