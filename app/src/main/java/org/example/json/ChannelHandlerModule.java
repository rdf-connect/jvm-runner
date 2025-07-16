
package org.example.json;

import java.io.IOException;

import org.example.IReader;
import org.example.IWriter;
import org.example.Reader;
import org.example.Runner;
import org.example.Writer;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.module.SimpleModule;

public class ChannelHandlerModule extends SimpleModule {
    private final Runner runner;

    public ChannelHandlerModule(Runner runner) {
        super("ChannelHandlerModule");
        this.runner = runner;
        addDeserializer(IReader.class, new ReaderDeserializer(runner));
        addDeserializer(IWriter.class, new WriterDeserializer(runner));

        // DynamicJsonLdDeserializer dynamic = new DynamicJsonLdDeserializer();
        // dynamic.register("http://example.com/ns#Reader", this::buildReader);
        // dynamic.register("http://example.com/ns#Writer", this::buildWriter);
        // addDeserializer(Object.class, dynamic);
        //
        // addAbstractTypeMapping(IReader.class, Reader.class);
        // addAbstractTypeMapping(IWriter.class, Writer.class);

    }

    private Reader buildReader(JsonNode node) {
        JsonNode idNode = node.get("@id");
        String id = idNode != null && idNode.isTextual() ? idNode.asText() : null;
        return new Reader(id);

    }

    private Writer buildWriter(JsonNode node) {
        JsonNode idNode = node.get("@id");
        String id = idNode != null && idNode.isTextual() ? idNode.asText() : null;
        return new Writer(id, this.runner);

    }

    private static class ReaderDeserializer extends JsonDeserializer<Reader> {
        private final Runner runner;

        ReaderDeserializer(Runner runner) {
            this.runner = runner;
        }

        @Override
        public Reader deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException, JsonProcessingException {

            ObjectCodec codec = p.getCodec();
            JsonNode node = codec.readTree(p);

            // Only handle objects
            if (node.isObject()) {
                JsonNode typeNode = node.get("@type");
                JsonNode idNode = node.get("@id");

                if (typeNode != null && typeNode.isTextual()) {
                    String type = typeNode.asText();

                    if ("https://w3id.org/rdf-connect#Reader".equals(type)) {
                        String id = idNode != null && idNode.isTextual() ? idNode.asText() : null;
                        var out = new Reader(id);
                        this.runner.setReader(id, out);
                        return out;
                    }
                }
            }

            // return codec.treeToValue(node, Object.class);
            throw new JsonParseException("Failed to parse reader");
        }
    }

    private static class WriterDeserializer extends JsonDeserializer<IWriter> {
        private final Runner runner;

        WriterDeserializer(Runner runner) {
            this.runner = runner;
        }

        @Override
        public Writer deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException, JsonProcessingException {

            ObjectCodec codec = p.getCodec();
            JsonNode node = codec.readTree(p);

            // Only handle objects
            if (node.isObject()) {
                JsonNode typeNode = node.get("@type");
                JsonNode idNode = node.get("@id");

                if (typeNode != null && typeNode.isTextual()) {
                    String type = typeNode.asText();

                    if ("https://w3id.org/rdf-connect#Writer".equals(type)) {
                        String id = idNode != null && idNode.isTextual() ? idNode.asText() : null;
                        return new Writer(id, this.runner);
                    }
                }
            }

            throw new JsonParseException("Failed to parse reader");
        }
    }
}
