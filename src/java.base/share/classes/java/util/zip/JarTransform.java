/*
 * Copyright (c) 2026, Diktamen Oy. All rights reserved.
 *
 * In-house addition to this JDK. Not part of upstream OpenJDK.
 */

package java.util.zip;

/**
 * Obfuscation transform for deployed application archives.
 *
 * <p>Deployed archives are ordinary ZIP/JAR files whose bytes have been XORed
 * with a repeating 32-byte key. Because the transform is a byte-for-byte XOR
 * keyed on the <em>absolute</em> file offset, it can be undone in the read
 * layer without buffering the file and without any change to ZIP parsing,
 * inflation, manifest handling, or signature verification: everything above
 * {@code ZipFile.Source} sees plaintext.
 *
 * <p><strong>This is obfuscation, not security.</strong> Its only purpose is to
 * stop a deployed archive from opening in a generic archive tool. The key is
 * embedded in this runtime and is recoverable by anyone who looks; the archive
 * plaintext also starts with a known signature, which makes the key trivially
 * recoverable from a single artifact. Do not rely on this for confidentiality.
 * Integrity and authenticity come from the JAR signature, which is verified
 * against the plaintext exactly as before.
 *
 * <p>Both a plain archive and a transformed archive are accepted, decided per
 * file by {@link #detect}. Build output stays plain; only artifacts served to
 * clients are transformed, so the same runtime must handle both.
 *
 * <p>The equivalent native implementation lives in
 * {@code src/java.base/share/native/libzip/zip_util.c} and is used by HotSpot
 * for {@code -Xbootclasspath/a} and CDS. <strong>The key material must be kept
 * in sync between the two.</strong>
 *
 * @see java.util.zip.ZipFile.Source
 */
final class JarTransform {

    /**
     * Version of the transform this runtime implements, as advertised in the
     * {@code diktamen.archive.transform} system property. Bump it if the key or
     * the scheme changes, so a client can tell whether the artifacts it would be
     * served are ones this runtime can actually read.
     *
     * <p>{@code jdk.internal.util.SystemProps}, which publishes the property,
     * cannot reference this constant -- this class is package-private to
     * java.util.zip. The two are kept in agreement by
     * {@code test/jdk/java/util/zip/ObfuscatedArchive.java}.
     */
    static final String VERSION = "v1";

    /** Key length in bytes. A power of two so producers cannot misalign on a
     *  chunk boundary as long as their chunk size is a multiple of it. */
    static final int KEY_LEN = 32;

    /**
     * Key material, stored as two halves that are combined at class
     * initialization so the effective key never appears as a contiguous run of
     * bytes in the class file. This is a speed bump for casual inspection, not
     * a protection; see the class comment.
     */
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

    /** File opens as-is; no transform. */
    static final int PLAIN = 0;
    /** File is transformed; reads must be XORed. */
    static final int TRANSFORMED = 1;
    /** Neither. Read as-is and let ZIP parsing produce its usual error. */
    static final int UNKNOWN = 2;

    private JarTransform() {}

    /**
     * Classifies an archive from the first bytes at offset 0.
     *
     * <p>Every ZIP file starts with a {@code PK} signature -- {@code PK\3\4}
     * for the usual case, {@code PK\5\6} for an empty archive -- so two bytes
     * are enough to tell plain from transformed without assuming the archive
     * has any entries.
     *
     * @param head bytes read from offset 0, untransformed
     * @param len  number of valid bytes in {@code head}, may be short or -1
     * @return {@link #PLAIN}, {@link #TRANSFORMED} or {@link #UNKNOWN}
     */
    static int detect(byte[] head, int len) {
        if (len < 2) {
            return UNKNOWN;
        }
        if (head[0] == 'P' && head[1] == 'K') {
            return PLAIN;
        }
        if ((byte)(head[0] ^ KEY[0]) == 'P' && (byte)(head[1] ^ KEY[1]) == 'K') {
            return TRANSFORMED;
        }
        return UNKNOWN;
    }

    /**
     * Undoes the transform in place over {@code len} bytes that were read from
     * absolute file offset {@code pos}.
     *
     * <p>Keying on the absolute offset is what makes this work for arbitrary
     * random-access reads: a caller never has to know how the region it asked
     * for is aligned relative to the key.
     *
     * @param buf buffer holding the bytes read
     * @param off offset in {@code buf} where those bytes start
     * @param len number of bytes to transform
     * @param pos absolute file offset the bytes were read from
     */
    static void apply(byte[] buf, int off, int len, long pos) {
        int k = (int)(pos % KEY_LEN);
        if (k < 0) {              // defensive; callers pass pos >= 0
            k += KEY_LEN;
        }
        for (int i = 0; i < len; i++) {
            buf[off + i] ^= KEY[k];
            if (++k == KEY_LEN) {
                k = 0;
            }
        }
    }
}
