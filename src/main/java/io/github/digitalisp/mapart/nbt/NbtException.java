package io.github.digitalisp.mapart.nbt;

import java.io.IOException;

/** Malformed, truncated or oversized NBT data. */
public class NbtException extends IOException {
    public NbtException(String message) {
        super(message);
    }

    public NbtException(String message, Throwable cause) {
        super(message, cause);
    }
}
