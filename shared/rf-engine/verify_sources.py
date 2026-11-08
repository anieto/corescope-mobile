#!/usr/bin/env python3
"""Check the exact vendored source set against the recorded upstream pin's hashes."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent / "vendor/kp1812"
manifest = json.loads((root / "manifest.json").read_text())
actual = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
          for p in root.rglob("*") if p.is_file() and p.name != "manifest.json"}
if actual != manifest["files"]:
    raise SystemExit("Vendored kp1812 source set differs from manifest")
print(f"Verified {len(actual)} unchanged files at {manifest['revision']}")
