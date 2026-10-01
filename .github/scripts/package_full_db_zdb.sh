#!/usr/bin/env bash
# Packages a finished schema-6+ seforim.db as the release's full-DB asset:
# seforim-schema<N>.zdb plus seforim-schema<N>.zdb.manifest.json.
#
# Usage: package_full_db_zdb.sh <seforim.db> <out-dir> <scratch-dir>
# Env:   ZVFS_CLI ZVFS_REPOSITORY ZVFS_COMMIT ZDB_LEVEL DB_VERSION
#        DB_SCHEMA_VERSION CONTENT_HASH [PATCH_MANIFEST_DIR] [ZDB_DICT]
# ZDB_DICT=trained (default) trains a 1MB dictionary on this DB and embeds it as
# seforim-v2-<DB_VERSION>; ZDB_DICT=builtin embeds the CLI's seforim-v1.
# <scratch-dir> needs one uncompressed DB free:
# the zdb is exported there and compared byte for byte with <seforim.db>.
set -euo pipefail

db="$1" out="$2" scratch="$3"
: "${ZVFS_CLI:?}" "${ZVFS_REPOSITORY:?}" "${ZVFS_COMMIT:?}" "${ZDB_LEVEL:?}"
: "${DB_VERSION:?}" "${DB_SCHEMA_VERSION:?}" "${CONTENT_HASH:?}"
case "$ZDB_LEVEL" in
  ''|*[!0-9]*) echo "::error::ZDB_LEVEL '$ZDB_LEVEL' is not an integer 1..22" >&2; exit 1 ;;
esac
[ "$ZDB_LEVEL" -ge 1 ] && [ "$ZDB_LEVEL" -le 22 ] || {
  echo "::error::ZDB_LEVEL $ZDB_LEVEL is outside 1..22" >&2; exit 1; }
[[ "$CONTENT_HASH" =~ ^[0-9a-f]{64}$ ]] || {
  echo "::error::CONTENT_HASH '$CONTENT_HASH' is not a sha256" >&2; exit 1; }
[[ "$DB_VERSION" =~ ^[1-9][0-9]{0,9}$ ]] || {
  echo "::error::DB_VERSION '$DB_VERSION' is not a positive integer" >&2; exit 1; }
dict_mode="${ZDB_DICT:-trained}"
case "$dict_mode" in
  trained) dict_name="seforim-v2-$DB_VERSION" ;;
  builtin) dict_name=seforim-v1 ;;
  *) echo "::error::ZDB_DICT '$dict_mode' is neither trained nor builtin" >&2; exit 1 ;;
esac

# shellcheck source=db_asset_names.sh
. "$(dirname "${BASH_SOURCE[0]}")/db_asset_names.sh"
name=$(full_db_asset_name "$DB_SCHEMA_VERSION")
case "$name" in
  *.zdb) ;;
  *) echo "::error::schema $DB_SCHEMA_VERSION ships as $name, not as a zdb" >&2; exit 1 ;;
esac
zdb="$out/$name"
manifest="$zdb.manifest.json"
info="$scratch/$name.info.json"
roundtrip="$scratch/$name.export.db"
dict_file="$scratch/$dict_name.dict"
mkdir -p "$scratch"
rm -f "$zdb" "$zdb.part" "$manifest" "$info" "$roundtrip" "$roundtrip.part" "$dict_file"
trap 'rm -f "$dict_file" "$info"' EXIT

# The output bytes do not depend on the thread count; the CLI accepts 1..16.
threads=$(nproc 2>/dev/null || echo 4)
[ "$threads" -le 16 ] || threads=16
dict_args=(--dict seforim-v1)
trained=""
if [ "$dict_mode" = trained ]; then
  # Deterministic and single-threaded (train has no --threads): a fixed page
  # sampler and fastcover with explicit k/d. Prints "dict N bytes id I xxh64 H in Ts".
  started=$(date +%s)
  trained=$("$ZVFS_CLI" train "$db" "$dict_file" 12000 1024 --fastcover --k 2000 --d 8)
  echo "zdb: trained $dict_name: $trained, wall $(( $(date +%s) - started ))s"
  dict_args=(--dict-file "$dict_file" --dict-name "$dict_name")
fi
# --created-ms 0 + --uuid-from-content: the same DB, level, dictionary and CLI give
# the same file. --keep-freelist: the zdb must export back to this DB byte for byte.
started=$(date +%s)
"$ZVFS_CLI" convert "$db" "$zdb" "${dict_args[@]}" --level "$ZDB_LEVEL" \
  --threads "$threads" --keep-freelist --uuid-from-content --created-ms 0
rm -f "$dict_file"
size=$(stat -c %s "$zdb")
echo "zdb: $name $size bytes, level $ZDB_LEVEL, dict $dict_name, $threads threads, $(( $(date +%s) - started ))s"
# GitHub refuses a release asset of 2 GiB or more; a zdb is never split.
if [ "$size" -gt 2147483647 ]; then
  echo "::error::$name is $size bytes, over GitHub's 2 GiB per-asset limit — raise ZDB_LEVEL or shrink the DB; a zdb is not split" >&2
  exit 1
fi

"$ZVFS_CLI" verify "$zdb"
started=$(date +%s)
"$ZVFS_CLI" export "$zdb" "$roundtrip"
if ! cmp "$db" "$roundtrip"; then
  echo "::error::$name does not export back to $db byte for byte (a WAL-mode source differs at byte 19)" >&2
  rm -f "$roundtrip"
  exit 1
fi
rm -f "$roundtrip"
echo "zdb: export is byte-identical to $db ($(( $(date +%s) - started ))s)"
"$ZVFS_CLI" info --json "$zdb" > "$info"

sha=$(sha256sum "$zdb" | cut -d' ' -f1)
python3 - "$info" "$manifest" "$name" "$size" "$sha" "$(stat -c %s "$db")" \
  "${PATCH_MANIFEST_DIR:-}" "$dict_name" "$trained" <<'PY'
import json, os, re, sys
from pathlib import Path

info_path, manifest_path, name, size, sha, db_size, patch_dir, dict_name, trained = sys.argv[1:]
info = json.loads(Path(info_path).read_text(encoding="utf-8"))
env = os.environ
level = int(env["ZDB_LEVEL"])

def fail(message):
    print(f"::error::{name}: {message}", file=sys.stderr)
    raise SystemExit(1)

def integer(key, minimum=0):
    value = info.get(key)
    if type(value) is not int or value < minimum:
        fail(f"info --json {key} is {value!r}")
    return value

def hexa(key, length):
    value = info.get(key)
    if not isinstance(value, str) or not re.fullmatch(f"[0-9a-f]{{{length}}}", value):
        fail(f"info --json {key} is {value!r}, expected {length} lowercase hex digits")
    return value

zdb = {
    "formatMajor": integer("formatMajor", 1),
    "formatMinor": integer("formatMinor"),
    "fileUuid": hexa("fileUuid", 32),
    "contentXxh64": hexa("contentXxh64", 16),
    "logicalSize": integer("logicalSize", 1),
    "pageSize": integer("pageSize", 512),
    "dictName": info.get("dictName"),
    "dictId": integer("dictId", 1),
    "level": integer("level", 1),
}
if zdb["dictName"] != dict_name:
    fail(f"dictName is {zdb['dictName']!r}, expected {dict_name!r}")
if trained:
    # The embedded dictionary must be the one `train` just wrote.
    match = re.fullmatch(r"dict ([0-9]+) bytes id ([0-9]+) xxh64 ([0-9a-f]{16}) in .*", trained)
    if not match:
        fail(f"zvfs_cli train printed {trained!r}")
    want = (int(match[1]), int(match[2]), match[3])
    got = (info.get("dictLength"), zdb["dictId"], info.get("dictXxh64"))
    if got != want:
        fail(f"embedded dictionary (length, id, xxh64) {got} is not the trained {want}")
if zdb["level"] != level:
    fail(f"level is {zdb['level']}, expected {level}")
if zdb["logicalSize"] != int(db_size):
    fail(f"logicalSize {zdb['logicalSize']} differs from the DB's {db_size} bytes")
if info.get("physicalSize") != int(size):
    fail(f"physicalSize {info.get('physicalSize')!r} differs from the file's {size} bytes")
if info.get("overlay", {}).get("present") is not False or info.get("lockGap") is not False:
    fail("a release zdb must carry no overlay and no lock gap")

content_hash = env["CONTENT_HASH"]
db_version = int(env["DB_VERSION"])
# Every delta this release publishes ends at this DB; its toContentHash must agree.
checked = 0
if patch_dir:
    for path in sorted(Path(patch_dir).glob("patch-*.db.zst.manifest.json")):
        patch = json.loads(path.read_text(encoding="utf-8"))
        if patch.get("fullRebase") is True:
            continue
        if patch.get("toVersion") != db_version or patch.get("toContentHash") != content_hash:
            fail(f"{path.name} ends at v{patch.get('toVersion')} {patch.get('toContentHash')}, "
                 f"not at v{db_version} {content_hash}")
        checked += 1

manifest = {
    "manifestVersion": 1,
    "file": name,
    "size": int(size),
    "sha256": sha,
    "zdb": zdb,
    "dbVersion": db_version,
    "dbSchemaVersion": int(env["DB_SCHEMA_VERSION"]),
    "contentHash": content_hash,
    "converter": {"repository": env["ZVFS_REPOSITORY"], "commit": env["ZVFS_COMMIT"]},
}
Path(manifest_path).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
print(f"zdb manifest: {Path(manifest_path).name} (contentHash {content_hash[:12]}, "
      f"agrees with {checked} delta manifest(s))")
PY
rm -f "$info"
