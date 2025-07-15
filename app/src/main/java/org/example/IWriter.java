package org.example;

import com.google.protobuf.ByteString;

public interface IWriter {
    void msg(ByteString buffer);

    Stream stream();

    void close();

    public static interface Stream {
        void push(ByteString chunk);

        void close();
    }
}
