"""Purpose: preserve repository translations while preparing partial Crowdin exports.
Inputs: English resources, indexed locale resources, and exports with untranslated strings omitted.
Outputs: merged locale files with repository or English fallbacks; empty new locales are removed.
Notes: explicit Crowdin translations take precedence, including text identical to English.
"""

from copy import deepcopy
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def resource_values(path: Path) -> dict[tuple[str, str, str], str]:
    root = ET.parse(path).getroot()
    if root.tag != "resources":
        raise ValueError(f"Expected Android resources: {path}")
    values = {}
    for resource in root:
        if resource.get("translatable") == "false":
            continue
        name = resource.attrib["name"]
        if resource.tag in {"plurals", "string-array"}:
            for index, item in enumerate(resource):
                key = (resource.tag, name, item.get("quantity", str(index)))
                values[key] = "".join(item.itertext()).strip()
        else:
            values[(resource.tag, name, "")] = "".join(resource.itertext()).strip()
    return values


def prepare_export(repo: Path) -> list[Path]:
    resources = repo / "app/src/main/res"
    source_path = resources / "values/strings.xml"
    source = resource_values(source_path)
    source_root = ET.parse(source_path).getroot()
    tracked = set(subprocess.check_output(
        ["git", "ls-files", "-z", "--", "app/src/main/res"], cwd=repo,
    ).decode().split("\0"))
    removed = []
    for path in sorted(resources.glob("values-*/strings.xml")):
        if not path.resolve().is_relative_to(resources.resolve()) or path.is_symlink() or path.parent.is_symlink():
            raise ValueError(f"Unexpected translation path: {path}")
        relative = path.relative_to(repo)
        is_tracked = relative.as_posix() in tracked
        translated = resource_values(path)
        if not is_tracked and not any(value != source.get(key) for key, value in translated.items()):
            path.unlink()
            if not any(path.parent.iterdir()):
                path.parent.rmdir()
            removed.append(relative)
            continue
        baseline = ET.fromstring(subprocess.check_output(
            ["git", "show", f":{relative.as_posix()}"], cwd=repo,
        )) if is_tracked else ET.Element("resources")
        existing = {(item.tag, item.get("name")): item for item in baseline}
        exported = {(item.tag, item.get("name")): item for item in ET.parse(path).getroot()}
        merged = ET.Element("resources")
        for original in source_root:
            if original.get("translatable") == "false":
                continue
            key = (original.tag, original.get("name"))
            chosen = exported.get(key)
            if chosen is None:
                chosen = existing.get(key)
            if chosen is None:
                chosen = original
            merged.append(deepcopy(chosen))
        ET.indent(merged, space="    ")
        path.write_text('<?xml version="1.0" encoding="utf-8"?>\n' + ET.tostring(merged, encoding="unicode") + "\n", encoding="utf-8")
    return removed


if __name__ == "__main__":
    for omitted in prepare_export(Path.cwd()):
        print(f"Omitted untranslated new locale: {omitted}")
