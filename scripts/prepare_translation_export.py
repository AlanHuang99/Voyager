"""Purpose: preserve repository translations while preparing partial Crowdin exports.
Inputs: English resources, indexed locales, approved exports, and optional provisional exports.
Outputs: merged locale files with repository or English fallbacks; empty new locales are removed.
Notes: explicit Crowdin translations take precedence, including text identical to English.
"""

from copy import deepcopy
from collections import Counter
import argparse
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
from translation_rollout import LOCALES, is_protected


FORMAT = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(<]*)(\d*)(?:\.(\d+))?([tT][a-zA-Z]|[a-zA-Z%])")


def placeholders(value):
    result = Counter()
    position = 0
    previous = None
    for match in FORMAT.finditer(value):
        index, flags, _, _, kind = match.groups()
        if kind in {"%", "n"}:
            continue
        if index:
            argument = int(index)
        elif "<" in flags:
            argument = previous
        else:
            position += 1
            argument = position
        previous = argument
        result[(argument, kind)] += 1
    return result


def usable_draft(source, translated):
    if source.tag != translated.tag:
        return False
    if source.tag == "plurals":
        originals = {item.get("quantity"): item for item in source}
        quantities = [item.get("quantity") for item in translated]
        if "other" not in quantities or len(quantities) != len(set(quantities)):
            return False
        if not set(quantities).issubset({"zero", "one", "two", "few", "many", "other"}):
            return False
        pairs = [(originals.get(item.get("quantity"), originals["other"]), item) for item in translated]
    elif source.tag == "string-array":
        if len(source) != len(translated):
            return False
        pairs = list(zip(source, translated))
    else:
        pairs = [(source, translated)]
    return all("".join(candidate.itertext()).strip() and placeholders("".join(original.itertext())) ==
               placeholders("".join(candidate.itertext())) for original, candidate in pairs)


def write_locale_config(path, language):
    root = ET.Element("resources")
    ET.SubElement(root, "bool", name="translation_provisional").text = "true"
    ET.SubElement(root, "string", name="translation_crowdin_url", translatable="false").text = (
        f"https://crowdin.com/project/voyagerandroid/{language}"
    )
    ET.indent(root, space="    ")
    path.write_text('<?xml version="1.0" encoding="utf-8"?>\n' + ET.tostring(root, encoding="unicode") + "\n")


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


def prepare_export(repo: Path, *, approved: Path | None = None, drafts: Path | None = None) -> list[Path]:
    resources = repo / "app/src/main/res"
    source_path = resources / "values/strings.xml"
    source = resource_values(source_path)
    source_root = ET.parse(source_path).getroot()
    originals = {(item.tag, item.get("name")): item for item in source_root}
    tracked = set(subprocess.check_output(
        ["git", "ls-files", "-z", "--", "app/src/main/res"], cwd=repo,
    ).decode().split("\0"))
    removed = []
    paths = set(resources.glob("values-*/strings.xml"))
    if approved:
        paths.update(resources / p.parent.name / p.name for p in (approved / "app/src/main/res").glob("values-*/strings.xml"))
    if drafts:
        paths.update(resources / f"values-{locale}" / "strings.xml" for locale in LOCALES.values()
                     if (drafts / f"app/src/main/res/values-{locale}/strings.xml").is_file())
    for path in sorted(paths):
        if not path.resolve().is_relative_to(resources.resolve()) or path.is_symlink() or path.parent.is_symlink():
            raise ValueError(f"Unexpected translation path: {path}")
        relative = path.relative_to(repo)
        is_tracked = relative.as_posix() in tracked
        export_path = (approved / relative) if approved else path
        draft_path = (drafts / relative) if drafts and path.parent.name.removeprefix("values-") in LOCALES.values() else None
        translated = resource_values(export_path) if export_path.is_file() else {}
        draft_values = resource_values(draft_path) if draft_path and draft_path.is_file() else {}
        translated.update({key: value for key, value in draft_values.items() if not is_protected(key[1])})
        if not is_tracked and not any(value != source.get(key) for key, value in translated.items()):
            path.unlink(missing_ok=True)
            if path.parent.is_dir() and not any(path.parent.iterdir()):
                path.parent.rmdir()
            removed.append(relative)
            continue
        baseline = ET.fromstring(subprocess.check_output(
            ["git", "show", f":{relative.as_posix()}"], cwd=repo,
        )) if is_tracked else ET.Element("resources")
        existing = {(item.tag, item.get("name")): item for item in baseline}
        exported = {(item.tag, item.get("name")): item for item in ET.parse(export_path).getroot()} if export_path.is_file() else {}
        provisional = {(item.tag, item.get("name")): item for item in ET.parse(draft_path).getroot()} if draft_path and draft_path.is_file() else {}
        merged = ET.Element("resources")
        for original in source_root:
            if original.get("translatable") == "false":
                continue
            key = (original.tag, original.get("name"))
            chosen = exported.get(key)
            baseline_value = existing.get(key)
            if chosen is None and baseline_value is not None and resource_text(baseline_value) != resource_text(original):
                chosen = baseline_value
            if chosen is None and key in provisional and not is_protected(key[1]):
                if usable_draft(original, provisional[key]):
                    chosen = provisional[key]
                else:
                    print(f"Rejected invalid draft: {path.parent.name}/{key[1]}")
            if chosen is None:
                chosen = baseline_value if baseline_value is not None else original
            merged.append(deepcopy(chosen))
        if not is_tracked and all(resource_text(item) == resource_text(originals[(item.tag, item.get("name"))]) for item in merged):
            path.unlink(missing_ok=True)
            if path.parent.is_dir() and not any(path.parent.iterdir()):
                path.parent.rmdir()
            removed.append(relative)
            continue
        ET.indent(merged, space="    ")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('<?xml version="1.0" encoding="utf-8"?>\n' + ET.tostring(merged, encoding="unicode") + "\n", encoding="utf-8")
        locale = path.parent.name.removeprefix("values-")
        if locale in LOCALES.values():
            language = next(language for language, code in LOCALES.items() if code == locale)
            write_locale_config(path.parent / "translation_config.xml", language)
    return removed


def resource_text(element):
    return [(child.tag, child.get("quantity"), "".join(child.itertext())) for child in element] if len(element) else "".join(element.itertext())


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--approved", type=Path)
    parser.add_argument("--drafts", type=Path)
    args = parser.parse_args()
    for omitted in prepare_export(Path.cwd(), approved=args.approved, drafts=args.drafts):
        print(f"Omitted untranslated new locale: {omitted}")
