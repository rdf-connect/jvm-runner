package org.example;

import com.google.protobuf.ByteString;

public abstract class IWriter {
    String id;

    public IWriter(String id) {
        this.id = id;
    }

    abstract void msg(ByteString buffer);

    abstract Stream stream();

    abstract void close();

    public static interface Stream {
        void push(ByteString chunk);

        void close();
    }
}
