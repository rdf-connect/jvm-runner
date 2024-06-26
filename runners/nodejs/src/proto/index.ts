// Code generated by protoc-gen-ts_proto. DO NOT EDIT.
// versions:
//   protoc-gen-ts_proto  v1.180.0
//   protoc               v5.27.0
// source: index.proto

/* eslint-disable */
import {
  type CallOptions,
  ChannelCredentials,
  Client,
  ClientDuplexStream,
  type ClientOptions,
  type ClientUnaryCall,
  handleBidiStreamingCall,
  type handleUnaryCall,
  makeGenericClientConstructor,
  Metadata,
  type ServiceError,
  type UntypedServiceImplementation,
} from "@grpc/grpc-js";
import * as _m0 from "protobufjs/minimal";
import { Empty } from "./empty";
import { IRStage } from "./intermediate";

export const protobufPackage = "";

export interface ChannelData {
  destinationUri: string;
  data: Uint8Array;
}

function createBaseChannelData(): ChannelData {
  return { destinationUri: "", data: new Uint8Array(0) };
}

export const ChannelData = {
  encode(
    message: ChannelData,
    writer: _m0.Writer = _m0.Writer.create(),
  ): _m0.Writer {
    if (message.destinationUri !== "") {
      writer.uint32(10).string(message.destinationUri);
    }
    if (message.data.length !== 0) {
      writer.uint32(18).bytes(message.data);
    }
    return writer;
  },

  decode(input: _m0.Reader | Uint8Array, length?: number): ChannelData {
    const reader =
      input instanceof _m0.Reader ? input : _m0.Reader.create(input);
    let end = length === undefined ? reader.len : reader.pos + length;
    const message = createBaseChannelData();
    while (reader.pos < end) {
      const tag = reader.uint32();
      switch (tag >>> 3) {
        case 1:
          if (tag !== 10) {
            break;
          }

          message.destinationUri = reader.string();
          continue;
        case 2:
          if (tag !== 18) {
            break;
          }

          message.data = reader.bytes();
          continue;
      }
      if ((tag & 7) === 4 || tag === 0) {
        break;
      }
      reader.skipType(tag & 7);
    }
    return message;
  },

  fromJSON(object: any): ChannelData {
    return {
      destinationUri: isSet(object.destinationUri)
        ? globalThis.String(object.destinationUri)
        : "",
      data: isSet(object.data)
        ? bytesFromBase64(object.data)
        : new Uint8Array(0),
    };
  },

  toJSON(message: ChannelData): unknown {
    const obj: any = {};
    if (message.destinationUri !== "") {
      obj.destinationUri = message.destinationUri;
    }
    if (message.data.length !== 0) {
      obj.data = base64FromBytes(message.data);
    }
    return obj;
  },

  create<I extends Exact<DeepPartial<ChannelData>, I>>(base?: I): ChannelData {
    return ChannelData.fromPartial(base ?? ({} as any));
  },
  fromPartial<I extends Exact<DeepPartial<ChannelData>, I>>(
    object: I,
  ): ChannelData {
    const message = createBaseChannelData();
    message.destinationUri = object.destinationUri ?? "";
    message.data = object.data ?? new Uint8Array(0);
    return message;
  },
};

export type RunnerService = typeof RunnerService;
export const RunnerService = {
  load: {
    path: "/Runner/load",
    requestStream: false,
    responseStream: false,
    requestSerialize: (value: IRStage) =>
      Buffer.from(IRStage.encode(value).finish()),
    requestDeserialize: (value: Buffer) => IRStage.decode(value),
    responseSerialize: (value: Empty) =>
      Buffer.from(Empty.encode(value).finish()),
    responseDeserialize: (value: Buffer) => Empty.decode(value),
  },
  exec: {
    path: "/Runner/exec",
    requestStream: false,
    responseStream: false,
    requestSerialize: (value: Empty) =>
      Buffer.from(Empty.encode(value).finish()),
    requestDeserialize: (value: Buffer) => Empty.decode(value),
    responseSerialize: (value: Empty) =>
      Buffer.from(Empty.encode(value).finish()),
    responseDeserialize: (value: Buffer) => Empty.decode(value),
  },
  channel: {
    path: "/Runner/channel",
    requestStream: true,
    responseStream: true,
    requestSerialize: (value: ChannelData) =>
      Buffer.from(ChannelData.encode(value).finish()),
    requestDeserialize: (value: Buffer) => ChannelData.decode(value),
    responseSerialize: (value: ChannelData) =>
      Buffer.from(ChannelData.encode(value).finish()),
    responseDeserialize: (value: Buffer) => ChannelData.decode(value),
  },
} as const;

export interface RunnerServer extends UntypedServiceImplementation {
  load: handleUnaryCall<IRStage, Empty>;
  exec: handleUnaryCall<Empty, Empty>;
  channel: handleBidiStreamingCall<ChannelData, ChannelData>;
}

export interface RunnerClient extends Client {
  load(
    request: IRStage,
    callback: (error: ServiceError | null, response: Empty) => void,
  ): ClientUnaryCall;
  load(
    request: IRStage,
    metadata: Metadata,
    callback: (error: ServiceError | null, response: Empty) => void,
  ): ClientUnaryCall;
  load(
    request: IRStage,
    metadata: Metadata,
    options: Partial<CallOptions>,
    callback: (error: ServiceError | null, response: Empty) => void,
  ): ClientUnaryCall;
  exec(
    request: Empty,
    callback: (error: ServiceError | null, response: Empty) => void,
  ): ClientUnaryCall;
  exec(
    request: Empty,
    metadata: Metadata,
    callback: (error: ServiceError | null, response: Empty) => void,
  ): ClientUnaryCall;
  exec(
    request: Empty,
    metadata: Metadata,
    options: Partial<CallOptions>,
    callback: (error: ServiceError | null, response: Empty) => void,
  ): ClientUnaryCall;
  channel(): ClientDuplexStream<ChannelData, ChannelData>;
  channel(
    options: Partial<CallOptions>,
  ): ClientDuplexStream<ChannelData, ChannelData>;
  channel(
    metadata: Metadata,
    options?: Partial<CallOptions>,
  ): ClientDuplexStream<ChannelData, ChannelData>;
}

export const RunnerClient = makeGenericClientConstructor(
  RunnerService,
  "Runner",
) as unknown as {
  new (
    address: string,
    credentials: ChannelCredentials,
    options?: Partial<ClientOptions>,
  ): RunnerClient;
  service: typeof RunnerService;
  serviceName: string;
};

function bytesFromBase64(b64: string): Uint8Array {
  if ((globalThis as any).Buffer) {
    return Uint8Array.from(globalThis.Buffer.from(b64, "base64"));
  } else {
    const bin = globalThis.atob(b64);
    const arr = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; ++i) {
      arr[i] = bin.charCodeAt(i);
    }
    return arr;
  }
}

function base64FromBytes(arr: Uint8Array): string {
  if ((globalThis as any).Buffer) {
    return globalThis.Buffer.from(arr).toString("base64");
  } else {
    const bin: string[] = [];
    arr.forEach((byte) => {
      bin.push(globalThis.String.fromCharCode(byte));
    });
    return globalThis.btoa(bin.join(""));
  }
}

type Builtin =
  | Date
  | Function
  | Uint8Array
  | string
  | number
  | boolean
  | undefined;

export type DeepPartial<T> = T extends Builtin
  ? T
  : T extends globalThis.Array<infer U>
    ? globalThis.Array<DeepPartial<U>>
    : T extends ReadonlyArray<infer U>
      ? ReadonlyArray<DeepPartial<U>>
      : T extends {}
        ? { [K in keyof T]?: DeepPartial<T[K]> }
        : Partial<T>;

type KeysOfUnion<T> = T extends T ? keyof T : never;
export type Exact<P, I extends P> = P extends Builtin
  ? P
  : P & { [K in keyof P]: Exact<P[K], I[K]> } & {
      [K in Exclude<keyof I, KeysOfUnion<P>>]: never;
    };

function isSet(value: any): boolean {
  return value !== null && value !== undefined;
}
