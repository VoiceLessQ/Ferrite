#!/usr/bin/env bash
# Apply Yarn -> Mojmap translation table from yarn_to_mojmap.txt across src/.
#
# Two passes:
#   [PACKAGES]  plain prefix replacement on import lines.  Order matters; longer/
#               more-specific prefixes are listed first in the table.
#   [CLASSES]   word-boundary replacement on bare identifiers.  Order matters less
#               here because each class name is a unique token.
#
# Idempotent: re-running on already-migrated files is a no-op.

set -euo pipefail

TABLE="$(dirname "$0")/yarn_to_mojmap.txt"
SRC_ROOT="$(dirname "$0")/../src"

if [[ ! -f "$TABLE" ]]; then
    echo "translation table not found: $TABLE" >&2
    exit 1
fi

# Collect the list of source files once.
mapfile -t FILES < <(find "$SRC_ROOT" -type f -name '*.java')
echo "[apply_mappings] found ${#FILES[@]} java files under $SRC_ROOT"

section=""
pkg_count=0
cls_count=0

while IFS= read -r line; do
    # Skip blanks and comments.
    [[ -z "$line" || "${line:0:1}" == "#" ]] && continue

    # Section markers.
    if [[ "$line" == "[PACKAGES]" ]]; then section="pkg"; continue; fi
    if [[ "$line" == "[CLASSES]"  ]]; then section="cls"; continue; fi
    [[ -z "$section" ]] && continue

    # Split on first '|'.
    old="${line%%|*}"
    new="${line#*|}"
    [[ -z "$old" || -z "$new" ]] && continue

    # Escape for sed: only / matters because we use / as the sed delimiter.
    # (Both sides will contain dots and braces, which are fine in BRE when paired
    # with literal text.  We use the alternative delimiter | actually no, | is
    # already in our table format — use # as sed delimiter.)
    old_esc=$(printf '%s' "$old" | sed 's/[#&]/\\&/g')
    new_esc=$(printf '%s' "$new" | sed 's/[#&]/\\&/g')

    if [[ "$section" == "pkg" ]]; then
        # Plain string replacement (regex but only literal chars matter).
        sed -i "s#${old_esc}#${new_esc}#g" "${FILES[@]}"
        pkg_count=$((pkg_count + 1))
    elif [[ "$section" == "cls" ]]; then
        # Word-boundary replacement.  GNU sed supports \b.
        sed -i "s#\b${old_esc}\b#${new_esc}#g" "${FILES[@]}"
        cls_count=$((cls_count + 1))
    fi
done < "$TABLE"

echo "[apply_mappings] applied $pkg_count package rules and $cls_count class rules"
