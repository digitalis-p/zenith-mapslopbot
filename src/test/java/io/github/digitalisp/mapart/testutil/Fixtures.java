package io.github.digitalisp.mapart.testutil;

import io.github.digitalisp.mapart.model.NamedFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Loads the committed fixtures under src/test/resources/fixtures. */
public final class Fixtures {
    public static final String A = "mapartcraft/A_carpet_flat_important__mapart.nbt";
    public static final String B = "mapartcraft/B_everything_valley_allopt__mapart.nbt";
    public static final String C = "mapartcraft/C_carpet_flat_2x1_split__mapart.zip";
    public static final String D = "mapartcraft/D_everything_classic_none__mapart.nbt";
    public static final String E = "mapartcraft/E_carpet_flat_none__mapart.nbt";
    public static final String F = "mapartcraft/F_carpet_flat_2x1_joined__mapart.nbt";
    public static final String G = "mapartcraft/G_orient_flat_allopt__orient_test.nbt";
    public static final String H = "mapartcraft/H_orient_classic_double__orient_test.nbt";
    public static final List<String> SIZEDREISEN = List.of(
        "sizedreisen/0001_sizedreisen_0_0.nbt", "sizedreisen/0002_sizedreisen_3_0.nbt",
        "sizedreisen/0003_sizedreisen_2_0.nbt", "sizedreisen/0004_sizedreisen_3_1.nbt",
        "sizedreisen/0005_sizedreisen_2_1.nbt", "sizedreisen/0006_sizedreisen_1_1.nbt",
        "sizedreisen/0007_sizedreisen_1_0.nbt", "sizedreisen/0008_sizedreisen_0_1.nbt");

    private Fixtures() { }

    public static byte[] bytes(String path) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) throw new IllegalArgumentException("no fixture " + path);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static NamedFile file(String path) {
        return new NamedFile(path.substring(path.lastIndexOf('/') + 1), bytes(path));
    }

    /** The same fixture under another file name. */
    public static NamedFile renamed(String path, String name) {
        return new NamedFile(name, bytes(path));
    }

    public static List<NamedFile> files(List<String> paths) {
        List<NamedFile> l = new ArrayList<>();
        for (String p : paths) l.add(file(p));
        return l;
    }

    /** Zips {@code entries} (entry name → data) in order. */
    public static byte[] zip(List<NamedFile> entries) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (NamedFile f : entries) {
                z.putNextEntry(new ZipEntry(f.name()));
                if (!f.name().endsWith("/")) z.write(f.data());
                z.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }
}
