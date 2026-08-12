/*
 * Copyright (c) 2026, Diktamen Oy. All rights reserved.
 *
 * In-house tool paired with the obfuscation transform in this JDK. Not part of
 * upstream OpenJDK.
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Applies, removes and verifies the obfuscation transform used for deployed
 * application archives.
 *
 * <p>Run with a JDK 11+ launcher directly from source, no build step:
 * <pre>
 *   java JarTransformTool apply    app.jar app.internaldata
 *   java JarTransformTool remove   app.internaldata app.jar
 *   java JarTransformTool inspect  app.internaldata
 *   java JarTransformTool verify   app.jar          # round-trip self-test
 *   java JarTransformTool key                       # print key for the serving side
 * </pre>
 *
 * <p><b>This is obfuscation, not security.</b> It exists only so a deployed
 * archive does not open in a generic archive tool. The key ships inside the
 * runtime that reads these files and is trivially recoverable from a single
 * artifact, since the plaintext begins with a known signature. Integrity and
 * authenticity come from the JAR signature, which is unaffected: the signature
 * covers the plaintext, and the runtime verifies it after undoing the transform.
 *
 * <p>The transform must stay in sync with three implementations:
 * <ul>
 *   <li>{@code java.util.zip.JarTransform} (this JDK, Java read path)</li>
 *   <li>{@code dl_apply()} in {@code libzip/zip_util.c} (this JDK, native read
 *       path used by HotSpot for {@code -Xbootclasspath/a} and CDS)</li>
 *   <li>the serving side that transforms artifacts on download</li>
 * </ul>
 */
public final class JarTransformTool {

    /** Key length in bytes. A power of two, so a producer that streams in
     *  chunks cannot misalign as long as its chunk size is a multiple of it. */
    static final int KEY_LEN = 32;

    static final byte[] KEY = new byte[KEY_LEN];
    static {
        // Same two-halves layout as the JDK-side implementations. See the note
        // in JarTransform: this only keeps the key from appearing as one
        // contiguous run of bytes, which is a speed bump, not a protection.
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

    /**
     * Applies the transform in place. Self-inverse: the same call undoes it.
     *
     * @param buf buffer holding the bytes
     * @param off offset in {@code buf} where the bytes start
     * @param len number of bytes to transform
     * @param pos absolute file offset the bytes came from -- this, not the
     *            buffer offset, is what indexes the key
     */
    static void xor(byte[] buf, int off, int len, long pos) {
        int k = (int)(pos % KEY_LEN);
        for (int i = 0; i < len; i++) {
            buf[off + i] ^= KEY[k];
            if (++k == KEY_LEN) {
                k = 0;
            }
        }
    }

    /** Transforms {@code in} to {@code out}, streaming, with the key phase
     *  carried across chunk boundaries exactly as a server must do it. */
    static void transformFile(Path in, Path out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long pos = 0;
        try (InputStream is = Files.newInputStream(in);
             java.io.OutputStream os = Files.newOutputStream(out)) {
            int n;
            while ((n = is.read(buf)) > 0) {
                // NOTE for the serving side: the key index is derived from the
                // absolute offset, NOT reset per chunk. Resetting per chunk is
                // the classic bug here and it does not fail loudly -- the
                // archive still opens, then a class deep inside fails to
                // inflate. Keeping the chunk size a multiple of KEY_LEN makes
                // the two formulations coincide, which hides the bug; carry the
                // offset regardless.
                xor(buf, 0, n, pos);
                os.write(buf, 0, n);
                pos += n;
            }
        }
    }

    /** Reports whether a file looks plain, transformed, or neither. */
    static String classify(Path p) throws IOException {
        byte[] head = new byte[2];
        try (InputStream is = Files.newInputStream(p)) {
            int n = is.readNBytes(head, 0, 2);
            if (n < 2) {
                return "unrecognized (file shorter than 2 bytes)";
            }
        }
        if (head[0] == 'P' && head[1] == 'K') {
            return "plain archive";
        }
        if ((byte)(head[0] ^ KEY[0]) == 'P' && (byte)(head[1] ^ KEY[1]) == 'K') {
            return "transformed archive";
        }
        return "unrecognized (neither a plain nor a transformed archive)";
    }

    /**
     * Round-trips an archive through the transform and reads every entry back
     * with the CRC checked, proving key alignment end to end.
     *
     * <p>Note this verifies with {@link ZipFile}, which on a patched runtime
     * undoes the transform in its read layer. Run it on the patched JDK to test
     * the JDK; the {@code remove}-then-compare check below is what validates
     * the transform itself independently of the runtime.
     */
    static void verify(Path jar) throws IOException {
        Path dir = Files.createTempDirectory("jartransform-verify");
        Path transformed = dir.resolve("t.internaldata");
        Path back = dir.resolve("back.jar");
        try {
            transformFile(jar, transformed);
            transformFile(transformed, back);

            byte[] original = Files.readAllBytes(jar);
            byte[] roundTripped = Files.readAllBytes(back);
            if (!java.util.Arrays.equals(original, roundTripped)) {
                throw new IOException("FAIL: round trip did not reproduce the input");
            }
            System.out.println("ok   round trip is byte-identical ("
                               + original.length + " bytes)");

            if (!classify(transformed).equals("transformed archive")) {
                throw new IOException("FAIL: transformed file not detected as transformed");
            }
            System.out.println("ok   transformed file is detected as transformed");

            // Chunk-boundary check: transform the same input with a deliberately
            // awkward chunk size and confirm the bytes match. This is what
            // catches a producer that restarts the key per chunk.
            byte[] whole = Files.readAllBytes(jar);
            byte[] chunked = whole.clone();
            int odd = 7;   // not a divisor of KEY_LEN
            for (int off = 0; off < chunked.length; off += odd) {
                int n = Math.min(odd, chunked.length - off);
                xor(chunked, off, n, off);
            }
            byte[] expected = Files.readAllBytes(transformed);
            if (!java.util.Arrays.equals(expected, chunked)) {
                throw new IOException("FAIL: chunked transform disagrees with streamed transform");
            }
            System.out.println("ok   chunked transform matches (chunk size " + odd + ")");

            // Read every entry through ZipFile and check the stored CRC. On a
            // patched runtime this reads the transformed file directly.
            int entries = 0;
            try (ZipFile zf = new ZipFile(transformed.toFile())) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) {
                        continue;
                    }
                    CRC32 crc = new CRC32();
                    byte[] buf = new byte[16 * 1024];
                    try (InputStream is = zf.getInputStream(e)) {
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            crc.update(buf, 0, n);
                        }
                    }
                    if (e.getCrc() != -1 && crc.getValue() != e.getCrc()) {
                        throw new IOException("FAIL: CRC mismatch for entry " + e.getName());
                    }
                    entries++;
                }
            } catch (IOException x) {
                throw new IOException("FAIL: could not read the transformed archive"
                                      + " via ZipFile -- is this the patched JDK? ("
                                      + x.getMessage() + ")", x);
            }
            System.out.println("ok   read " + entries
                               + " entries from the transformed archive, CRCs match");
            System.out.println("PASS");
        } finally {
            Files.deleteIfExists(transformed);
            Files.deleteIfExists(back);
            Files.deleteIfExists(dir);
        }
    }

    static void printKey() {
        StringBuilder hex = new StringBuilder();
        StringBuilder php = new StringBuilder();
        for (int i = 0; i < KEY_LEN; i++) {
            hex.append(String.format("%02x", KEY[i] & 0xff));
            php.append(String.format("\\x%02X", KEY[i] & 0xff));
        }
        System.out.println("key length : " + KEY_LEN + " bytes");
        System.out.println("key (hex)  : " + hex);
        System.out.println("key (PHP)  : \"" + php + "\"");
        System.out.println();
        System.out.println("A transformed archive begins with the bytes "
                           + String.format("%02X %02X",
                                           ('P' ^ KEY[0]) & 0xff,
                                           ('K' ^ KEY[1]) & 0xff)
                           + " where a plain one begins 50 4B (\"PK\").");
        System.out.println();
        System.out.println("Serving side: XOR byte at absolute file offset i with");
        System.out.println("key[i % " + KEY_LEN + "]. Carry the offset across chunks;");
        System.out.println("do not restart the key per chunk.");
    }

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "";
        switch (cmd) {
            // apply and remove are the same operation; both names are accepted
            // so call sites can say what they mean.
            case "apply":
            case "remove":
                if (args.length != 3) {
                    usage();
                    return;
                }
                transformFile(Path.of(args[1]), Path.of(args[2]));
                System.out.println(args[1] + " -> " + args[2] + " ("
                                   + Files.size(Path.of(args[2])) + " bytes, length unchanged)");
                break;
            case "inspect":
                if (args.length != 2) {
                    usage();
                    return;
                }
                System.out.println(args[1] + ": " + classify(Path.of(args[1])));
                break;
            case "verify":
                if (args.length != 2) {
                    usage();
                    return;
                }
                verify(Path.of(args[1]));
                break;
            case "key":
                printKey();
                break;
            default:
                usage();
        }
    }

    static void usage() {
        System.err.println("usage: java JarTransformTool <command> [args]");
        System.err.println("  apply   <in> <out>   transform an archive for deployment");
        System.err.println("  remove  <in> <out>   undo the transform (same operation)");
        System.err.println("  inspect <file>       report plain / transformed / unrecognized");
        System.err.println("  verify  <jar>        round-trip self-test, needs the patched JDK");
        System.err.println("  key                  print key material for the serving side");
        System.exit(2);
    }

    private JarTransformTool() {}
}
