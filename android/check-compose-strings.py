#!/usr/bin/env python3
"""Reject direct hardcoded text arguments in the main Kotlin sources."""
import re
import sys
from pathlib import Path

SOURCE = Path(__file__).resolve().parent / "app/src/main/java"
UI_LITERAL = re.compile(
    r'\b(?:Text\s*\(\s*(?:text\s*=\s*)?|'
    r'(?:text|label|title|contentDescription|placeholder|subtitle)\s*=\s*)'
    r'(?P<literal>"(?:\\.|[^"\\])*")',
    re.MULTILINE,
)

# Examples readers must type verbatim; retain the technical syntax.
ALLOW = {
    ("AddChannelScreen.kt", '"#wardriving"'),
    ("SourceScreen.kt", '"analyzer.example.org"'),
    # Compose animation inspector label; it does not appear in the app.
    ("MapScreen.kt", '"replay-inset"'),
    # An imported study name built from user data and a fixed breadcrumb separator.
    ("SavedPlansLibrary.kt", '"${plan.name} › ${site.name}"'),
}
# Numbers and mathematical symbols drawn on controls and charts are fixed notation.
NOTATION = re.compile(r'"[\d+−–—….,:×/% °]*"')
failures = []
for path in sorted(SOURCE.rglob("*.kt")):
    source = path.read_text(encoding="utf-8")
    for match in UI_LITERAL.finditer(source):
        literal = match["literal"]
        if (path.name, literal) in ALLOW or NOTATION.fullmatch(literal):
            continue
        line = source.count("\n", 0, match.start()) + 1
        failures.append(f"{path.relative_to(SOURCE)}:{line}: {literal}")

if failures:
    print("\n".join(failures), file=sys.stderr)
    print("Move visible text into an Android string or plural resource.", file=sys.stderr)
    sys.exit(1)
print("No direct hardcoded Compose text found.")
