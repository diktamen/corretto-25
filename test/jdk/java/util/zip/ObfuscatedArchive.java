/*
 * Copyright (c) 2026, Diktamen Oy. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/* @test
 * @summary In-house addition: ZipFile transparently reads archives carrying the
 *          obfuscation transform (repeating 32-byte XOR keyed on absolute file
 *          offset), while plain archives keep working unchanged. Also covers the
 *          launcher's own manifest reader, so a divergence between the key in
 *          java.util.zip.JarTransform and the copy in libjli/parse_manifest.c is
 *          caught here rather than at deployment.
 * @run main ObfuscatedArchive
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ObfuscatedArchive {

    // Must match java.util.zip.JarTransform and dl_apply() in libzip/zip_util.c.
    // Duplicated rather than shared because JarTransform is package-private.
    private static final int KEY_LEN = 32;
    private static final byte[] KEY = new byte[KEY_LEN];
    static {
        final byte[] a = {
            (byte)0x3C, (byte)0x91, (byte)0x4E, (byte)0x07,
            (byte)0xB2, (byte)0x65, (byte)0x1A, (byte)0xD3,
            (byte)0x88, (byte)0x2F, (byte)0x70, (byte)0xC6,
            (byte)0x19, (byte)0xE4, (byte)0x5B, (byte)0xA0,
            (byte)0xD7, (byte)0x32, (byte)0x8C, (byte)0x61,
            (byte)0x0F, (byte)0xB4, (byte)0x59, (byte)0xE2,
            (byte)0x27, (byte)0x9D, (byte)0x4A, (byte)0xF1,
            (byte)0x06, (byte)0xC8, (byte)0x35, (byte)0x7B,
        };
        final byte[] b = {
            (byte)0x46, (byte)0xAE, (byte)0x8F, (byte)0x02,
            (byte)0x2C, (byte)0x27, (byte)0xC2, (byte)0xB8,
            (byte)0x99, (byte)0x88, (byte)0x40, (byte)0x32,
            (byte)0x45, (byte)0x6D, (byte)0x75, (byte)0x13,
            (byte)0xB3, (byte)0xE3, (byte)0x84, (byte)0xF6,
            (byte)0x4A, (byte)0x4E, (byte)0x75, (byte)0x9C,
            (byte)0x97, (byte)0x8E, (byte)0x25, (byte)0x34,
            (byte)0x3E, (byte)0x6A, (byte)0xEC, (byte)0x3A,
        };
        for (int i = 0; i < KEY_LEN; i++) {
            KEY[i] = (byte)(a[i] ^ b[i]);
        }
    }

    private static void xor(byte[] buf, int off, int len, long pos) {
        int k = (int)(pos % KEY_LEN);
        for (int i = 0; i < len; i++) {
            buf[off + i] ^= KEY[k];
            if (++k == KEY_LEN) {
                k = 0;
            }
        }
    }

    /** Entries chosen to span many key periods and to cover both compression
     *  methods, since STORED and DEFLATED take different read paths. */
    private record Item(String name, byte[] data, int method) {}

    private static Item[] items() {
        Random rnd = new Random(20260811);

        // Highly compressible, so DEFLATED entry data is much shorter than the
        // plaintext: exercises reads whose length is not a key multiple.
        byte[] repetitive = new byte[300_000];
        Arrays.fill(repetitive, (byte)'x');

        // Incompressible, so the deflated stream is ~as long as the input.
        byte[] random = new byte[250_000];
        rnd.nextBytes(random);

        // Deliberately awkward lengths around the key period.
        byte[] short31 = new byte[31];
        rnd.nextBytes(short31);
        byte[] exact32 = new byte[32];
        rnd.nextBytes(exact32);
        byte[] short33 = new byte[33];
        rnd.nextBytes(short33);

        return new Item[] {
            new Item("a/repetitive.bin", repetitive, ZipEntry.DEFLATED),
            new Item("a/random.bin", random, ZipEntry.DEFLATED),
            new Item("stored/random.bin", random, ZipEntry.STORED),
            new Item("edge/len31", short31, ZipEntry.DEFLATED),
            new Item("edge/len32", exact32, ZipEntry.STORED),
            new Item("edge/len33", short33, ZipEntry.STORED),
            new Item("empty", new byte[0], ZipEntry.DEFLATED),
            new Item("text.txt", "hello äö world".getBytes(StandardCharsets.UTF_8),
                     ZipEntry.DEFLATED),
        };
    }

    private static byte[] buildZip(Item[] items) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            // A comment forces the END-header comment read path.
            zos.setComment("in-house test archive");
            for (Item it : items) {
                ZipEntry e = new ZipEntry(it.name());
                e.setMethod(it.method());
                if (it.method() == ZipEntry.STORED) {
                    // STORED requires size and CRC up front.
                    CRC32 crc = new CRC32();
                    crc.update(it.data());
                    e.setSize(it.data().length);
                    e.setCompressedSize(it.data().length);
                    e.setCrc(crc.getValue());
                }
                zos.putNextEntry(e);
                zos.write(it.data());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** Opens the archive and checks every entry round-trips byte for byte. */
    private static void checkReadsBack(Path file, Item[] items) throws IOException {
        int seen = 0;
        try (ZipFile zf = new ZipFile(file.toFile())) {
            for (Item it : items) {
                ZipEntry e = zf.getEntry(it.name());
                if (e == null) {
                    throw new RuntimeException("missing entry " + it.name()
                                               + " in " + file);
                }
                byte[] got;
                try (InputStream is = zf.getInputStream(e)) {
                    got = readAll(is);
                }
                if (!Arrays.equals(it.data(), got)) {
                    throw new RuntimeException("content mismatch for " + it.name()
                                               + " in " + file + ": expected "
                                               + it.data().length + " bytes, got "
                                               + got.length);
                }
            }
            // Enumeration must agree with lookup: this walks the CEN, which is
            // read through the same transformed read path.
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                en.nextElement();
                seen++;
            }
            if (seen != items.length) {
                throw new RuntimeException("expected " + items.length
                                           + " entries in " + file + ", found " + seen);
            }
            if (!"in-house test archive".equals(zf.getComment())) {
                throw new RuntimeException("archive comment not read back from " + file
                                           + ": " + zf.getComment());
            }
        }
    }

    /** Must match JarTransform.VERSION and the value SystemProps publishes. */
    private static final String EXPECTED_VERSION = "v1";

    /**
     * Verifies the capability property is declared with putIfAbsent rather than
     * put, i.e. that a command-line -D beats the built-in value. Support relies
     * on this to turn the feature off on a customer machine.
     */
    private static void checkPropertyIsOverridable() throws Exception {
        String home = System.getProperty("test.jdk", System.getProperty("java.home"));
        Path java = Path.of(home, "bin", "java");
        if (!Files.exists(java)) {
            java = Path.of(home, "bin", "java.exe");
        }
        String override = "off-for-test";
        ProcessBuilder pb = new ProcessBuilder(java.toString(),
                "-Ddiktamen.archive.transform=" + override,
                "-cp", System.getProperty("test.classes", "."),
                PropertyEcho.class.getName());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(),
                                StandardCharsets.UTF_8).trim();
        int rc = p.waitFor();
        if (rc != 0 || !out.contains(override)) {
            throw new RuntimeException("-D did not override the advertised"
                    + " version (exit " + rc + "): " + out
                    + " -- SystemProps must use putIfAbsent, not put, so support"
                    + " can disable the feature on a customer machine");
        }
    }

    /** Helper main class for {@link #checkPropertyIsOverridable}. */
    public static final class PropertyEcho {
        public static void main(String[] args) {
            System.out.println(System.getProperty("diktamen.archive.transform"));
        }
    }

    /** Main class of the jar built by {@link #checkLauncher}. */
    public static final class Child {
        public static void main(String[] args) {
            System.out.println("child-ran-from-transformed-archive");
        }
    }

    /**
     * Builds an executable jar, transforms it, and runs {@code java -jar} on the
     * result in a subprocess.
     */
    private static void checkLauncher(Path dir) throws Exception {
        String childName = Child.class.getName();          // ObfuscatedArchive$Child
        String classFile = childName.replace('.', '/') + ".class";

        // jtreg puts compiled test classes here; fall back for a manual run.
        String testClasses = System.getProperty("test.classes", ".");
        Path childClass = Path.of(testClasses, classFile);
        if (!Files.exists(childClass)) {
            throw new RuntimeException("cannot find " + childClass
                                       + " to build the launcher test jar");
        }

        Path jar = dir.resolve("launch.internaldata");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zos.write(("Manifest-Version: 1.0\r\nMain-Class: " + childName + "\r\n\r\n")
                      .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(classFile));
            zos.write(Files.readAllBytes(childClass));
            zos.closeEntry();
        }
        byte[] transformed = bos.toByteArray();
        xor(transformed, 0, transformed.length, 0);
        Files.write(jar, transformed);

        // Prefer the JDK under test; fall back to the running one.
        String home = System.getProperty("test.jdk", System.getProperty("java.home"));
        Path java = Path.of(home, "bin", "java");
        if (!Files.exists(java)) {
            java = Path.of(home, "bin", "java.exe");
        }

        ProcessBuilder pb = new ProcessBuilder(java.toString(), "-jar", jar.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(),
                                StandardCharsets.UTF_8).trim();
        int rc = p.waitFor();
        try {
            if (rc != 0 || !out.contains("child-ran-from-transformed-archive")) {
                throw new RuntimeException("launcher failed on a transformed archive"
                                           + " (exit " + rc + "): " + out
                                           + " -- if this says \"Invalid or corrupt"
                                           + " jarfile\", the key in"
                                           + " libjli/parse_manifest.c disagrees with"
                                           + " java.util.zip.JarTransform");
            }
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("obfuscated-archive");
        Item[] items = items();
        byte[] plain = buildZip(items);

        Path plainFile = dir.resolve("plain.zip");
        Path xored = dir.resolve("deployed.internaldata");
        Path garbage = dir.resolve("garbage.internaldata");

        try {
            // 1. A plain archive still works. The transform must not regress the
            //    ordinary path, which is what every other zip test exercises.
            Files.write(plainFile, plain);
            checkReadsBack(plainFile, items);
            System.out.println("ok   plain archive reads unchanged");

            // 2. The transformed archive reads identically.
            byte[] t = plain.clone();
            xor(t, 0, t.length, 0);
            Files.write(xored, t);
            if (t.length != plain.length) {
                throw new RuntimeException("transform changed the file length");
            }
            if (t[0] == 'P' && t[1] == 'K') {
                throw new RuntimeException("transformed archive still starts with PK;"
                                           + " it would open in an archive tool");
            }
            checkReadsBack(xored, items);
            System.out.println("ok   transformed archive reads identically");

            // 3. Both remain open at once, so the per-archive transform state is
            //    not leaking between Source instances.
            try (ZipFile a = new ZipFile(plainFile.toFile());
                 ZipFile b = new ZipFile(xored.toFile())) {
                byte[] fromPlain;
                byte[] fromXored;
                try (InputStream is = a.getInputStream(a.getEntry("text.txt"))) {
                    fromPlain = readAll(is);
                }
                try (InputStream is = b.getInputStream(b.getEntry("text.txt"))) {
                    fromXored = readAll(is);
                }
                if (!Arrays.equals(fromPlain, fromXored)) {
                    throw new RuntimeException("plain and transformed archives"
                                               + " disagree while both are open");
                }
            }
            System.out.println("ok   plain and transformed archives coexist");

            // 4. A file that is neither must report the distinguishing message,
            //    so a wrong key or truncated download is diagnosable.
            byte[] junk = new byte[4096];
            new Random(7).nextBytes(junk);
            junk[0] = (byte)0x00;   // neither "PK" nor the transformed signature
            junk[1] = (byte)0x01;
            Files.write(garbage, junk);
            try {
                new ZipFile(garbage.toFile()).close();
                throw new RuntimeException("expected opening " + garbage + " to fail");
            } catch (ZipException expected) {
                if (!expected.getMessage().contains("unrecognized container format")) {
                    throw new RuntimeException("unhelpful message for an unrecognized"
                                               + " file: " + expected.getMessage());
                }
            }
            System.out.println("ok   unrecognized file reports a distinguishable error");

            // 5. The launcher reads the manifest of a transformed archive. This
            //    exercises libjli/parse_manifest.c, which parses the jar itself
            //    to find Main-Class and does not go through java.util.zip -- so
            //    it is the check that catches a key that has drifted out of sync
            //    between the Java and launcher implementations.
            checkLauncher(dir);
            System.out.println("ok   launcher runs -jar against a transformed archive");

            // 6. The capability property must be present and must agree with what
            //    the runtime can actually do. A client uses it to decide whether
            //    to ask a server for transformed artifacts, so a build that reads
            //    them but forgets the property would silently lose obfuscation,
            //    and one that advertises the property but cannot read them would
            //    leave clients unable to load anything. Step 2 above already
            //    proved the reading half, so reaching here means both hold.
            String advertised = System.getProperty("diktamen.archive.transform");
            if (!EXPECTED_VERSION.equals(advertised)) {
                throw new RuntimeException("system property"
                        + " diktamen.archive.transform is \"" + advertised + "\","
                        + " expected \"" + EXPECTED_VERSION + "\" -- it is set in"
                        + " jdk/internal/util/SystemProps.java and must match"
                        + " JarTransform.VERSION");
            }
            System.out.println("ok   advertises diktamen.archive.transform="
                               + advertised + " and can read what that promises");

            // The property is putIfAbsent, so -D must win: that is what lets
            // support disable the feature on a customer machine without a
            // separate switch.
            checkPropertyIsOverridable();
            System.out.println("ok   -D overrides the advertised version");

            System.out.println("PASS");
        } finally {
            Files.deleteIfExists(plainFile);
            Files.deleteIfExists(xored);
            Files.deleteIfExists(garbage);
            Files.deleteIfExists(dir);
        }
    }
}
