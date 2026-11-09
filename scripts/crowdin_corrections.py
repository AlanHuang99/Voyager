"""Purpose: apply the reviewed Crowdin corrections without deleting contributor suggestions.
Inputs: checked-in correction mapping, English resources, and the Crowdin Actions secret.
Outputs: approved corrections and a verified approved export when requested separately.
Notes: validates the complete plan before writing; fails on changed sources or unexpected approvals.
"""

import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

from crowdin_drafts import PROJECT_ID, request
from prepare_translation_export import placeholders
from translation_rollout import LOCALES

BASE = f"/projects/{PROJECT_ID}"


def items(api, path):
    offset = 0
    separator = "&" if "?" in path else "?"
    while True:
        page = api("GET", f"{path}{separator}limit=100&offset={offset}")["data"]
        yield from (item["data"] for item in page)
        if len(page) < 100:
            return
        offset += len(page)


def load_mapping(path):
    mapping = json.loads(path.read_text())
    if mapping["project_id"] != PROJECT_ID:
        raise ValueError("Unexpected correction project")
    return mapping["corrections"]


def check_mapping(entries, source_path):
    source = {item.get("name"): item for item in ET.parse(source_path).getroot()}
    seen = set()
    for entry in entries:
        key = (entry["language"], entry["identifier"], entry.get("quantity"))
        if key in seen or entry["language"] not in LOCALES:
            raise ValueError("Duplicate correction or unsupported language")
        seen.add(key)
        original = source[entry["identifier"]]
        text = {item.get("quantity"): item.text for item in original} if original.tag == "plurals" else original.text
        if entry["source"] != text:
            raise ValueError(f"Changed local source: {entry['identifier']}")
        quantity = entry.get("quantity")
        if isinstance(text, dict):
            if quantity not in text:
                raise ValueError("Missing or invalid plural category")
            text = text[quantity]
        elif quantity is not None:
            raise ValueError("Plural category on an ordinary string")
        if not entry["text"].strip() or placeholders(text) != placeholders(entry["text"]):
            raise ValueError(f"Invalid correction placeholder: {entry['identifier']}")


def normalized(text):
    return " ".join(text.replace("\\n", "\n").split())


def plan(api, entries, sources):
    pending = []
    by_name = {}
    for source in sources:
        by_name.setdefault(source["identifier"], []).append(source)
    # Validate every source before reading or changing translations.
    for entry in entries:
        matches = by_name.get(entry["identifier"], [])
        if len(matches) != 1 or matches[0]["text"] != entry["source"]:
            raise ValueError(f"Changed or ambiguous Crowdin source: {entry['identifier']}")
    for entry in entries:
        source = by_name[entry["identifier"]][0]
        query = f"stringId={int(source['id'])}&languageId={entry['language']}"
        translations = list(items(api, BASE + "/translations?" + query))
        quantity = entry.get("quantity")
        candidates = [item for item in translations if item.get("pluralCategoryName") == quantity]
        previous = [item for item in candidates if normalized(item["text"]) == normalized(entry["previous"])]
        corrected = [item for item in candidates if item["text"] == entry["text"]]
        if not previous and not corrected:
            raise ValueError(f"Expected previous translation missing: {entry['identifier']}")
        approvals = list(items(api, BASE + "/approvals?" + query))
        allowed = {item["id"] for item in [*previous, *corrected]}
        form_ids = {item["id"] for item in candidates}
        if any(item["translationId"] in form_ids - allowed or item["translationId"] not in {t["id"] for t in translations}
               for item in approvals):
            raise ValueError(f"Unexpected approved translation: {entry['identifier']}")
        pending.append((entry, source["id"], corrected[0] if corrected else None, approvals))
    return pending


def apply(api, pending):
    for entry, string_id, translation, approvals in pending:
        if translation is None:
            body = {"stringId": string_id, "languageId": entry["language"], "text": entry["text"], "addToTm": False}
            if entry.get("quantity"):
                body["pluralCategoryName"] = entry["quantity"]
            translation = api("POST", BASE + "/translations", body)["data"]
            if translation["text"] != entry["text"]:
                raise RuntimeError("Crowdin returned a different correction")
        translation_id = int(translation["id"])
        if not any(item["translationId"] == translation_id for item in approvals):
            api("POST", BASE + "/approvals", {"translationId": translation_id})
        query = f"stringId={int(string_id)}&languageId={entry['language']}"
        verified = list(items(api, BASE + "/approvals?" + query))
        if not any(item["translationId"] == translation_id for item in verified):
            raise RuntimeError(f"Correction approval not verified: {entry['identifier']}")
        print(f"Verified approved correction: {entry['language']}/{entry['identifier']} ({translation_id})", flush=True)


def verify_export(entries, directory):
    for entry in entries:
        resource = directory / f"app/src/main/res/values-{LOCALES[entry['language']]}/strings.xml"
        matches = [item for item in ET.parse(resource).getroot() if item.get("name") == entry["identifier"]]
        if len(matches) != 1:
            raise ValueError(f"Missing exported correction: {entry['identifier']}")
        item = matches[0]
        if entry.get("quantity"):
            item = item.find(f"item[@quantity='{entry['quantity']}']")
        if item is None or item.text != entry["text"]:
            raise ValueError(f"Export did not select correction: {entry['identifier']}")
    print(f"Verified all {len(entries)} corrections in export", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--verify-export", type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    entries = load_mapping(repo / "scripts/crowdin_corrections.json")
    check_mapping(entries, repo / "app/src/main/res/values/strings.xml")
    if args.verify_export:
        verify_export(entries, args.verify_export)
    elif args.apply:
        project = request("GET", BASE)["data"]
        if project["sourceLanguageId"] != "en" or not {e["language"] for e in entries}.issubset(project["targetLanguageIds"]):
            raise ValueError("Unexpected project languages")
        files = [item for item in items(request, BASE + "/files") if item["name"] == "strings.xml"]
        if len(files) != 1:
            raise ValueError("Expected one strings.xml source")
        sources = list(items(request, BASE + f"/strings?fileId={int(files[0]['id'])}"))
        pending = plan(request, entries, sources)
        print(f"Validated all {len(pending)} corrections before applying", flush=True)
        apply(request, pending)
    else:
        print(f"Validated {len(entries)} reviewed corrections locally")


if __name__ == "__main__":
    main()
