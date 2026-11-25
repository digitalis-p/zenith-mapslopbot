package io.github.digitalisp.mapart.model;

/** An uploaded file (Discord attachment, or a local file for terminal submissions). CONTRACT (lead-owned). */
public record NamedFile(String name, byte[] data) { }
