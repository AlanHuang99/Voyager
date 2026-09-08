"""Purpose: omit new locales containing only English fallback text.
Inputs: tracked English resources and downloaded Android locale resources.
Outputs: untranslated, untracked locale files are removed before validation.
Notes: existing locales and any locale with translated text are preserved.
"""

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
    source = resource_values(resources / "values/strings.xml")
    tracked = set(subprocess.check_output(
        ["git", "ls-files", "-z", "--", "app/src/main/res"], cwd=repo,
    ).decode().split("\0"))
    removed = []
    for path in sorted(resources.glob("values-*/strings.xml")):
        if path.relative_to(repo).as_posix() in tracked:
            continue
        if not path.resolve().is_relative_to(resources.resolve()) or path.is_symlink() or path.parent.is_symlink():
            raise ValueError(f"Unexpected translation path: {path}")
        translated = resource_values(path)
        if any(value != source.get(key) for key, value in translated.items()):
            continue
        path.unlink()
        if not any(path.parent.iterdir()):
            path.parent.rmdir()
        removed.append(path.relative_to(repo))
    return removed


if __name__ == "__main__":
    for omitted in prepare_export(Path.cwd()):
        print(f"Omitted untranslated new locale: {omitted}")
