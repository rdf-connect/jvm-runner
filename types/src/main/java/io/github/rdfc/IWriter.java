package io.github.rdfc;

import java.util.concurrent.CompletableFuture;

import com.google.protobuf.ByteString;

public abstract class IWriter extends Stream<ByteString> {
    public abstract String id();

    /**
     * Send a stream of data over the channel.
     * 
     * @return a stream of ByteStrings
     */
    public abstract CompletableFuture<Stream<ByteString>> stream();
}
