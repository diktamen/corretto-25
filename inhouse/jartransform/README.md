# Deployed-archive obfuscation transform

In-house addition to this JDK. **Not part of upstream OpenJDK** — expect to
re-apply parts of it on every upstream rebase (see [Merge surface](#merge-surface)).

## What it does

A deployed application archive is an ordinary ZIP/JAR whose bytes have been
XORed with a repeating 32-byte key. This JDK undoes that in its read layer, so
class loading, resource loading, manifest parsing and JAR signature
verification all behave exactly as they do for a plain jar.

The transform is keyed on the **absolute file offset**:

```
cipher[i] = plain[i] ^ key[i % 32]
```

That is the whole reason this is cheap. Because any byte can be decoded knowing
only its offset, the transform can be undone inside the two functions that read
file bytes, without buffering the file and without touching ZIP parsing,
inflation, or signature verification. It is also length-preserving, so anything
that checks file sizes keeps working.

`java JarTransformTool key` prints the key and the exact rule the serving side
must follow.

## This is obfuscation, not security

Its only purpose is that a deployed archive does not open in a generic archive
tool. Specifically:

- The key ships inside this runtime and is recoverable by anyone who looks.
- The plaintext begins with a known signature (`PK`), and the archive contains
  long stretches of predictable structure, so the key can be recovered from a
  single artifact in a few minutes.

Integrity and authenticity come from the **JAR signature**, which is unchanged:
the signature covers the plaintext, and it is verified after the transform is
undone. Do not describe this transform as protecting anything.

## Both formats are accepted

Classification happens per file, at open, from the first two bytes:

| First bytes | Treated as |
|---|---|
| `50 4B` (`PK`) | plain archive, read as-is |
| `2A 74` | transformed archive, reads are XORed |
| anything else | read as-is; ZIP parsing reports its usual error |

So build output stays plain and only artifacts served to clients are
transformed. The same runtime handles both, which keeps local development,
debugging, and the build pipeline on ordinary jars.

A file that is neither reports `zip END header not found (unrecognized
container format)`. That extra clause is the signal for "wrong key, truncated
download, or not an archive at all" as opposed to a genuinely corrupt jar.

## Tool

Runs from source, no build step:

```
java JarTransformTool apply    app.jar app.internaldata
java JarTransformTool remove   app.internaldata app.jar   # same operation
java JarTransformTool inspect  app.internaldata
java JarTransformTool verify   app.jar                    # round-trip self-test
java JarTransformTool key                                 # key for the serving side
```

`verify` round-trips an archive, confirms the bytes are reproduced exactly,
re-transforms with a deliberately awkward chunk size to catch key misalignment,
and then reads every entry back through `ZipFile` with CRCs checked. Run it on
this JDK; on a stock JDK the last step fails by design.

## Note for the serving side

The key index comes from the absolute file offset and **must be carried across
chunk boundaries**. Restarting the key per chunk is the classic bug here and it
does not fail loudly: the archive still opens, and then a class deep inside
fails to inflate. Keeping the chunk size a multiple of 32 makes the two
formulations coincide, which hides the bug rather than fixing it — carry the
offset regardless.

Publishing a checksum of a build artifact and comparing it against what a client
downloaded will not match, since the transform changes every byte. Compare
before transforming, or checksum the transformed bytes.

## Merge surface

Four implementations of the transform must agree. `key` in the tool is the
canonical value.

| File | Role |
|---|---|
| `src/java.base/share/classes/java/util/zip/JarTransform.java` | key + detect + apply (Java) |
| `src/java.base/share/classes/java/util/zip/ZipFile.java` | `Source.readAt` / `readFullyAt` / `readAtRaw`, classification at open, error message |
| `src/java.base/share/native/libzip/zip_util.c` | `dl_*` helpers; `readFully` / `readFullyAt` take an `xored` flag. Used by HotSpot for `-Xbootclasspath/a` and CDS |
| `src/java.base/share/native/libzip/zip_util.h` | `jzfile.xored`; build guard |
| `src/java.base/share/native/libjli/parse_manifest.c` | the launcher parses manifests itself for `java -jar`; without this, `-jar` fails with "Invalid or corrupt jarfile" |
| `inhouse/jartransform/JarTransformTool.java` | producer, and the reference the serving side follows |
| `test/jdk/java/util/zip/ObfuscatedArchive.java` | regression test; runs in `tier1_part2`, so CI gates it |

`ZipFile.java` and `zip_util.c` do change upstream, so expect small conflicts.
The read functions are the only hot spots; keep the transform confined to them.

### USE_MMAP

`libzip` maps the central directory instead of reading it when `USE_MMAP` is
defined. `CoreLibraries.gmk` passes `-DUSE_MMAP` via `CFLAGS_unix`, so **this
path is live on macOS and Linux** and absent only on Windows — a Windows-only
build will not exercise it.

An mmapped CEN bypasses the read layer where the transform is undone.
`ZIP_Put_In_Cache0` therefore clears `zip->usemmap` when it detects a
transformed archive, and since every mmap path is gated on that flag, those
archives take the ordinary read path. Plain archives still map the CEN exactly
as before, so the footprint optimisation that mapping exists for is retained
for the common case.

The cost for a transformed archive is one `malloc` plus one read of the central
directory — which is what Windows already does for every archive.

The alternative would be to XOR the mapping itself, which needs a private
writable mapping (`MAP_PRIVATE | PROT_WRITE`) and gives up the shared-page
footprint benefit that motivated mapping the CEN in the first place. Not worth
it for the artifacts this applies to.

## What is intentionally not covered

- **Extension recognition.** Nothing in the JVM keys off the file extension:
  explicit `-cp` entries, `-jar`, and `URLClassLoader` URLs all work with any
  name. Not covered are the places that *enumerate* by suffix — `-cp dir/*`
  (`libjli/wildcard.c`) and `--module-path` (`jdk/internal/module/ModulePath.java`,
  which also strips `.jar` to derive automatic module names). Add `.internaldata`
  there if a launch path ever needs them.
- **`ZipInputStream` / `JarInputStream`.** These read a sequential stream and
  never go through `ZipFile.Source`, so they see raw transformed bytes. Keep
  archive access on `ZipFile` / `JarFile` / `URLClassLoader`.
- **Build tooling.** `javac`, `jar`, `jarsigner`, `jlink`, `jmod` and
  `jdk.zipfs` were left alone deliberately: the build pipeline works on plain
  jars and only the download handler transforms them.
