
package io.github.rdfc.json;

import java.io.IOException;
import java.util.logging.Logger;

import io.github.rdfc.IReader;
import io.github.rdfc.IWriter;
import io.github.rdfc.Reader;
import io.github.rdfc.Runner;
import io.github.rdfc.Writer;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.module.SimpleModule;

public class ChannelHandlerModule extends SimpleModule {

    public ChannelHandlerModule(Runner runner, Logger logger) {
        super("ChannelHandlerModule");
        addDeserializer(IReader.class, new ReaderDeserializer(runner, logger));
        addDeserializer(IWriter.class, new WriterDeserializer(runner, logger));
    }

    private static class ReaderDeserializer extends JsonDeserializer<Reader> {
        final private Logger logger;
        private final Runner runner;

        ReaderDeserializer(Runner runner, Logger logger) {
            this.runner = runner;
            this.logger = logger;
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
                        var out = new Reader(id, this.logger);
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
        private final Logger logger;

        WriterDeserializer(Runner runner, Logger logger) {
            this.runner = runner;
            this.logger = logger;
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
                        var out = new Writer(id, this.runner, this.logger);
                        this.runner.setWriter(id, out);
                        return out;
                    }
                }
            }

            throw new JsonParseException("Failed to parse reader");
        }
    }
}
