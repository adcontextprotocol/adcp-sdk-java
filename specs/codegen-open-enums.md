# Open enum generation

**Status:** Design spec for Track 2 generator invariants
**Tracks:** [`codegen`](../ROADMAP.md#track-2--l0-types--codegen), [`transport`](../ROADMAP.md#track-3--l0-transport-mcp--a2a)
**Decisions referenced:** D23

## Why this exists

Some AdCP vocabularies are intentionally open from an SDK compatibility point of view. A Java strict enum parse would turn a newly added protocol value into a deserialization failure before caller code can inspect recovery hints or raw values. That is wrong for values like `error.code`, where older SDKs should remain able to receive, log, and conservatively classify new codes.

Closed spec vocabularies can still generate Java `enum` types. Open vocabularies use an unknown-safe shape.

## Default shape

```java
public sealed interface ErrorCode permits ErrorCode.Known, ErrorCode.Unknown {
    String rawValue();

    record Known(KnownErrorCode value) implements ErrorCode {
        @Override
        public String rawValue() {
            return value.wireValue();
        }
    }

    record Unknown(String rawValue) implements ErrorCode {
    }
}
```

The generated JSON adapter maps recognized wire values to `Known` and unrecognized wire values to `Unknown`. Serialization preserves `rawValue()` exactly.

## Jackson binding

Open enum wrappers do not use Jackson `@JsonTypeInfo`; they serialize as the flat protocol string. The generator emits a custom `JsonDeserializer<T>` for each open vocabulary that maps the incoming string to `Known` or `Unknown`, plus a matching `JsonSerializer<T>` that writes `rawValue()`.

This is separate from polymorphic envelope handling in Track 2. Envelope types may use discriminator-based Jackson handling; open vocabularies must not, because the wire value is a scalar string.

## Generator rules

- Open vocabularies generate a sealed wrapper with `Known` and `Unknown`.
- The nested known-value type may be a Java enum when the known value set is useful for switch exhaustiveness.
- Unknown raw values are never rewritten, lowercased, uppercased, or mapped to a generic `UNKNOWN` sentinel that loses the original string.
- Each open vocabulary emits Jackson serializer/deserializer bindings that preserve the flat scalar wire shape.
- Closed vocabularies may generate plain Java enums.
- The schema post-processor owns the open/closed classification; contributors must not infer it from value count.

## Error-code handling

`ErrorCode` is open. Transport and caller helpers classify known values directly, and classify unknown values from recovery metadata when present. If no recovery metadata is present, callers default conservatively and preserve the raw code for logs and telemetry.

## Initial open vocabulary list

The first generator pass treats at least these vocabularies as open:

- `ErrorCode`
- `media_buy_status`
- `creative_status`
- `recovery`
- action-discovery enums used in `allowed_actions[]` and `available_actions[]`

The schema post-processor may mark more vocabularies open as the protocol evolves. Contributors should not treat this list as exhaustive.
