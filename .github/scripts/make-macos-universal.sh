#!/bin/bash
#
# Merge a macOS x64 and an aarch64 JDK image into a single universal image.
#
# Usage: make-macos-universal.sh <x64-home> <aarch64-home> <out-home>
#
# Every Mach-O file present in both inputs is combined with 'lipo -create'.
# Everything else must be byte-identical between the two inputs and is copied
# through. Anything that is neither is a hard error, because silently keeping
# one architecture's copy of a file that genuinely differs is how you end up
# with a JDK that looks universal and misbehaves on one architecture only.
#
# Known exceptions to that rule are listed in ALLOWED_DIFFERENT below and are
# handled explicitly rather than ignored.
set -euo pipefail

# Absolute, because the passes below cd into each tree to walk it relatively.
mkdir -p "$3"
X64_HOME="$(cd "$1" && pwd)"
ARM_HOME="$(cd "$2" && pwd)"
OUT_HOME="$(cd "$3" && pwd)"

# Files that legitimately differ between the two builds and cannot be lipo'd.
#   release            - records OS_ARCH; rewritten below
#   *.jsa              - CDS archives; architecture-specific, dropped (see below)
#   *.jmod             - see the jmods note below; aarch64 copy is kept
ALLOWED_DIFFERENT='^\./release$|\.jsa$|\.jmod$'

# --- about jmods -------------------------------------------------------------
# A .jmod carries that architecture's native libraries, so the two builds differ.
# They cannot be merged: java.base's module-info records hashes of every other
# module, so repacking any .jmod with lipo'd contents invalidates those hashes
# and jlink then refuses the whole module path.
#
# The aarch64 copies are kept so the image is a complete, working JDK. The
# consequence is that *jlink from this image produces an aarch64-only runtime*,
# which is exactly what diktalaunch's create_mac_installer.sh does. To get a
# universal runtime, jlink once per architecture from the two single-arch JDKs
# and run this same script over the two jlink outputs, which have no jmods and
# so merge cleanly.
n_jmod=0

n_lipo=0; n_copy=0; n_drop=0; n_only_arm=0; n_only_x64=0
mismatch_list=""

# Octal mode of a file. BSD (macOS, where this runs) and GNU spell this
# differently, and GNU 'stat -f' exits 0 while meaning something else entirely
# -- it reports filesystem info -- so validate the output rather than trusting
# the exit status.
file_mode() {
    local m
    m="$(stat -f '%Lp' "$1" 2>/dev/null)" || true
    if [[ ! "$m" =~ ^[0-7]+$ ]]; then
        m="$(stat -c '%a' "$1")"
    fi
    printf '%s' "$m"
}

mkdir -p "$OUT_HOME"

# --- pass 1: everything present in the aarch64 image -------------------------
cd "$ARM_HOME"
while IFS= read -r -d '' rel; do
    arm="$ARM_HOME/$rel"
    x64="$X64_HOME/$rel"
    out="$OUT_HOME/$rel"

    if [[ -L "$arm" ]]; then
        mkdir -p "$(dirname "$out")"
        cp -RP "$arm" "$out"
        continue
    fi
    if [[ -d "$arm" ]]; then
        mkdir -p "$out"
        continue
    fi

    mkdir -p "$(dirname "$out")"

    # CDS archives cannot be merged: the path lib/server/classes.jsa is fixed,
    # so a universal image can hold at most one architecture's archive. Keeping
    # one would leave the other architecture loading a foreign archive; the JVM
    # rejects it and silently continues without CDS. Dropping both makes that
    # explicit and identical on each architecture.
    if [[ "$rel" =~ \.jsa$ ]]; then
        n_drop=$((n_drop + 1))
        continue
    fi

    if [[ ! -e "$x64" ]]; then
        cp -p "$arm" "$out"
        n_only_arm=$((n_only_arm + 1))
        echo "only-in-aarch64: $rel"
        continue
    fi

    if file -b "$arm" | grep -q 'Mach-O' && file -b "$x64" | grep -q 'Mach-O'; then
        lipo -create "$x64" "$arm" -output "$out"
        # lipo does not carry the mode over, and the executables must stay
        # executable.
        chmod "$(file_mode "$arm")" "$out"
        n_lipo=$((n_lipo + 1))
        continue
    fi

    if cmp -s "$arm" "$x64"; then
        cp -p "$arm" "$out"
        n_copy=$((n_copy + 1))
        continue
    fi

    if [[ "$rel" =~ \.jmod$ ]]; then
        cp -p "$arm" "$out"
        n_jmod=$((n_jmod + 1))
        continue
    fi

    if [[ "$rel" =~ $ALLOWED_DIFFERENT ]]; then
        cp -p "$arm" "$out"
        n_copy=$((n_copy + 1))
        continue
    fi

    mismatch_list="$mismatch_list  $rel"$'\n'
done < <(find . -mindepth 1 -print0)

# --- pass 2: anything only the x64 image has --------------------------------
cd "$X64_HOME"
while IFS= read -r -d '' rel; do
    [[ -d "$X64_HOME/$rel" || -L "$X64_HOME/$rel" ]] && continue
    if [[ ! -e "$ARM_HOME/$rel" ]]; then
        mkdir -p "$(dirname "$OUT_HOME/$rel")"
        cp -p "$X64_HOME/$rel" "$OUT_HOME/$rel"
        n_only_x64=$((n_only_x64 + 1))
        echo "only-in-x64: $rel"
    fi
done < <(find . -mindepth 1 -print0)

# --- the release file -------------------------------------------------------
# Mark the image as universal so anything reading OS_ARCH does not believe it is
# aarch64-only. Both slices are really present.
if [[ -f "$OUT_HOME/release" ]]; then
    sed -i.bak 's/^OS_ARCH=.*/OS_ARCH="universal"/' "$OUT_HOME/release"
    rm -f "$OUT_HOME/release.bak"
fi

echo
echo "=============== universal merge summary ==============="
echo "  lipo'd (Mach-O, both arches) : $n_lipo"
echo "  copied (identical)           : $n_copy"
echo "  dropped (CDS archives)       : $n_drop"
echo "  only in aarch64              : $n_only_arm"
echo "  only in x64                  : $n_only_x64"
echo "  .jmod kept as aarch64        : $n_jmod"
echo "======================================================="

if [[ $n_jmod -gt 0 ]]; then
    echo
    echo "WARNING: $n_jmod .jmod files could not be merged and are aarch64-only."
    echo "         Running jlink against this image's jmods yields an"
    echo "         AARCH64-ONLY runtime, not a universal one. For a universal"
    echo "         runtime, jlink separately from each single-arch JDK and merge"
    echo "         the two jlink outputs with this script."
fi

if [[ -n "$mismatch_list" ]]; then
    echo
    echo "ERROR: these files differ between the two builds, are not Mach-O, and" >&2
    echo "are not a known exception. Refusing to guess which one to ship:" >&2
    printf '%s' "$mismatch_list" >&2
    exit 1
fi
