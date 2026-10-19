#!/usr/bin/env python3
"""List the classes *defined* in one or more .dex files, without any dependency.

Why this exists
---------------
Komikku and Mihon load extension APKs with a parent-**last** class loader
(`ChildFirstPathClassLoader`): it looks in the system loader, then in the extension APK, and only
then in the app itself. Anything bundled inside the extension therefore *shadows* the app's own
copy of that class. Bundling the Kotlin standard library is fatal, because the app passes its
coroutines into the extension's `suspend` functions:

    java.lang.IncompatibleClassChangeError: Class '...SourceFeedScreenModel$getFeed$1$1$1$page$1'
    does not implement interface 'kotlin.coroutines.Continuation' in call to
    'kotlin.coroutines.CoroutineContext kotlin.coroutines.Continuation.getContext()'
    (declaration of 'kotlin.coroutines.jvm.internal.ContinuationImpl' appears in
     /data/app/.../eu.kanade.tachiyomi.extension.all.mangaball-.../base.apk)

So the build must prove that the APK contains *only* this extension's own classes (plus purely
referenced types, which is what `compileOnly` gives us). Merely grepping the dex string pool is
not enough: the pool also holds the types the extension *references*, such as
`Lkotlin/coroutines/Continuation;`. This script instead walks the dex class_defs table, which is
the list of classes the APK actually ships.

Usage
-----
    python3 tools/dex_classes.py classes.dex [classes2.dex ...]
        [--forbid kotlin/] [--require eu/kanade/.../MangaBallFactory]
        [--list-prefix eu/kanade/]

Exit codes
----------
    0  nothing forbidden, and every --require prefix was found
    1  at least one class starts with a --forbid prefix  (fatal: do not ship)
    2  the check could not be trusted: a --require prefix is missing, or no classes were read at
       all (warn loudly, but do not fail the build on a tooling problem)
"""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

DEX_MAGIC = b"dex\n"
CLASS_DEFS_OFFSET = 56  # first of the twelve u4 fields: string_ids_size


def read_uleb128(buf: bytes, offset: int) -> tuple[int, int]:
    """Read an unsigned LEB128 value, returning (value, new_offset)."""
    result = 0
    shift = 0
    while True:
        if offset >= len(buf):
            raise ValueError("truncated uleb128")
        byte = buf[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if byte & 0x80 == 0:
            return result, offset
        shift += 7
        if shift > 28:
            raise ValueError("uleb128 value is too long")


def read_string(buf: bytes, offset: int) -> str:
    size, offset = read_uleb128(buf, offset)
    return buf[offset:offset + size].decode("utf-8", "replace")


def class_descriptors(data: bytes) -> list[str]:
    """Return the class descriptors defined by this dex file, e.g. 'Lkotlin/Int;'."""
    if data[:4] != DEX_MAGIC:
        raise ValueError("not a dex file")

    (
        string_ids_size,
        string_ids_off,
        type_ids_size,
        type_ids_off,
        _proto_ids_size,
        _proto_ids_off,
        _field_ids_size,
        _field_ids_off,
        _method_ids_size,
        _method_ids_off,
        class_defs_size,
        class_defs_off,
    ) = struct.unpack_from("<12I", data, CLASS_DEFS_OFFSET)

    strings = [
        read_string(data, struct.unpack_from("<I", data, string_ids_off + 4 * i)[0])
        for i in range(string_ids_size)
    ]
    types = [
        strings[struct.unpack_from("<I", data, type_ids_off + 4 * i)[0]]
        for i in range(type_ids_size)
    ]
    return [
        types[struct.unpack_from("<I", data, class_defs_off + 32 * i)[0]]
        for i in range(class_defs_size)
    ]


def normalize(descriptor: str) -> str:
    """'Lkotlin/Int;' -> 'kotlin/Int' (leaves primitives such as 'V' untouched)."""
    if descriptor.startswith("L") and descriptor.endswith(";"):
        return descriptor[1:-1]
    return descriptor


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dex", nargs="+", type=Path, help="dex file(s) to inspect")
    parser.add_argument("--forbid", action="append", default=[], help="class-name prefix that must not be shipped")
    parser.add_argument("--require", action="append", default=[], help="class-name prefix that must be present")
    parser.add_argument("--list-prefix", action="append", default=[], help="print every class under this prefix")
    args = parser.parse_args(argv)

    names: set[str] = set()
    for path in args.dex:
        try:
            descriptors = class_descriptors(path.read_bytes())
        except Exception as error:  # noqa: BLE001 - the exit code carries the meaning
            print(f"{path.name}: could not be read ({error.__class__.__name__}: {error})")
            return 2
        print(f"{path.name}: {len(descriptors)} classes defined")
        names.update(normalize(d) for d in descriptors)

    for prefix in args.list_prefix:
        listed = sorted(n for n in names if n.startswith(prefix))
        print(f"\nclasses under {prefix} ({len(listed)}):")
        for name in listed:
            print(f"  {name}")

    forbidden = sorted(n for n in names if any(n.startswith(p) for p in args.forbid))
    if forbidden:
        print(f"\n!! {len(forbidden)} class(es) bundled that must come from the reader app:")
        for name in forbidden[:40]:
            print(f"   {name}")
        print("\nThese shadow the app's own copies (the extension class loader is parent-last) and")
        print("break suspend calls with java.lang.IncompatibleClassChangeError.")
        return 1

    missing = [p for p in args.require if not any(n.startswith(p) for n in names)]
    if missing or not names:
        reason = f"missing required class prefix(es): {', '.join(missing)}" if missing else "no classes were read at all"
        print(f"\n?? could not verify the dex contents - {reason}")
        return 2

    print(f"\nOK: {len(names)} classes, none of them from {', '.join(args.forbid) if args.forbid else 'the forbidden list'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
