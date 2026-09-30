"""Purpose: download approved translations and provisional drafts separately.
Inputs: Crowdin token and checked-in rollout locale mapping.
Outputs: two resource trees under the requested output directory.
Notes: never changes approval state; archive entries are filtered before writing.
"""

import argparse
from io import BytesIO
from pathlib import Path, PurePosixPath
import re
import time
from urllib.parse import urlparse
from urllib.request import urlopen
from zipfile import ZipFile

from crowdin_drafts import PROJECT_ID, request
from translation_rollout import LOCALES, POLICY


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
            if path.parts[4] == "values" or not re.fullmatch(r"[a-z]{2,3}(?:-r[A-Z]{2}|-r[0-9]{3})?", locale):
                continue
            locale = POLICY["android_aliases"].get(locale, locale)
            path = PurePosixPath("app/src/main/res", f"values-{locale}", "strings.xml")
            if not approved and locale not in LOCALES.values():
                continue
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
    if not approved:
        body["targetLanguageIds"] = list(LOCALES)
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
