"""Purpose: download approved translations and provisional drafts separately.
Inputs: Crowdin token, enabled project languages, and existing Android locale aliases.
Outputs: two resource trees, locale-to-Crowdin language IDs, and required plural forms.
Notes: never changes approval state; archive entries are filtered before writing.
"""

import argparse
from io import BytesIO
import json
from pathlib import Path, PurePosixPath
import re
import time
from urllib.parse import urlparse
from urllib.request import urlopen
from zipfile import ZipFile

from crowdin_drafts import PROJECT_ID, request
from translation_rollout import LOCALES, POLICY


ANDROID_LOCALE = re.compile(
    r"(?:[a-z]{2,3}(?:-r[A-Z]{2}|-r[0-9]{3})?"
    r"|b\+[a-z]{2,3}(?:\+[A-Z][a-z]{3})?(?:\+(?:[A-Z]{2}|[0-9]{3}))?"
    r"(?:\+(?:[a-zA-Z0-9]{5,8}|[0-9][a-zA-Z0-9]{3}))?)"
)


def extract_resources(content, output, *, approved):
    written = set()
    with ZipFile(BytesIO(content)) as archive:
        for info in archive.infolist():
            path = PurePosixPath(info.filename)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError("Unexpected path in translation archive")
            if len(path.parts) != 6 or path.parts[:4] != ("app", "src", "main", "res") or path.name != "strings.xml":
                continue
            locale = path.parts[4].removeprefix("values-")
            if not path.parts[4].startswith("values-") or not ANDROID_LOCALE.fullmatch(locale):
                continue
            locale = POLICY["android_aliases"].get(locale, locale)
            path = PurePosixPath("app/src/main/res", f"values-{locale}", "strings.xml")
            if info.file_size > 5_000_000 or path in written:
                raise ValueError("Oversized or duplicate resource in translation archive")
            written.add(path)
            destination = output / str(path)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(archive.read(info))
    if not written:
        raise ValueError("No locale resources matched the export; check Crowdin export paths")
    print(f"Downloaded {len(written)} {'approved' if approved else 'provisional'} resource files", flush=True)


def export(api, output, *, approved, sleep=time.sleep):
    body = {"exportApprovedOnly": approved, "skipUntranslatedStrings": True, "skipUntranslatedFiles": False}
    locale_languages = {}
    locale_plurals = {}
    if not approved:
        project = api("GET", f"/projects/{PROJECT_ID}")["data"]
        for language in project["targetLanguages"]:
            locale = LOCALES.get(language["id"], language["androidCode"])
            locale = POLICY["android_aliases"].get(locale, locale)
            locale_languages[locale] = language["id"]
            locale_plurals[locale] = language["pluralCategoryNames"]
    base = f"/projects/{PROJECT_ID}/translations/builds"
    build = api("POST", base, body)["data"]
    build_path = f"{base}/{int(build['id'])}"
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        status = api("GET", build_path)["data"]["status"]
        if status == "finished":
            url = api("GET", build_path + "/download")["data"]["url"]
            parsed = urlparse(url)
            if parsed.scheme != "https" or not parsed.hostname:
                raise ValueError("Expected an HTTPS translation download")
            with urlopen(url, timeout=90) as response:
                content = response.read(25_000_001)
            if len(content) > 25_000_000:
                raise ValueError("Translation download is unexpectedly large")
            extract_resources(content, output, approved=approved)
            if not approved:
                (output / "locale_languages.json").write_text(json.dumps(locale_languages, indent=2) + "\n", encoding="utf-8")
                (output / "locale_plurals.json").write_text(json.dumps(locale_plurals, indent=2) + "\n", encoding="utf-8")
            return
        if status in {"failed", "canceled"}:
            raise RuntimeError(f"Translation export {status}")
        sleep(5)
    raise TimeoutError("Translation export did not finish")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    export(request, args.output / "approved", approved=True)
    export(request, args.output / "drafts", approved=False)
