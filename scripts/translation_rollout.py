"""Shared provisional-locale and sensitive-string policy for Crowdin preparation."""

import json
from pathlib import Path

POLICY = json.loads(Path(__file__).with_suffix(".json").read_text())
LOCALES = POLICY["locales"]


def is_protected(name):
    return name in POLICY["protected_names"] or name.startswith(tuple(POLICY["protected_prefixes"]))
