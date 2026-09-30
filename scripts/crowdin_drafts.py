"""Purpose: inspect MT engines or draft missing translations for the ten rollout locales.
Inputs: Crowdin token, operation mode, and explicit MT engine ID in environment variables.
Outputs: engine metadata or a completed pre-translation report, without credentials.
Notes: never replaces or approves translations; export policy controls provisional use.
"""

import json
import os
import time
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen
from translation_rollout import LOCALES

PROJECT_ID = 927407
LANGUAGES = list(LOCALES)


def request(method, path, body=None):
    token = os.environ["CROWDIN_PERSONAL_TOKEN"]
    payload = None if body is None else json.dumps(body).encode()
    req = Request("https://api.crowdin.com/api/v2" + path, data=payload, method=method,
                  headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    try:
        with urlopen(req, timeout=60) as response:
            return json.load(response)
    except HTTPError as error:
        # Engine responses may contain provider credentials, so never log response bodies.
        raise RuntimeError(f"Crowdin {method} {path} returned HTTP {error.code}") from None


def list_items(api, path):
    offset = 0
    while True:
        items = api("GET", f"{path}?limit=100&offset={offset}")["data"]
        yield from (item["data"] for item in items)
        if len(items) < 100:
            return
        offset += len(items)


def draft_body(engine_id, file_id, *, languages=None):
    if engine_id <= 0:
        raise ValueError("A positive MT engine ID is required")
    languages = LANGUAGES.copy() if languages is None else languages
    if not languages or not set(languages).issubset(LANGUAGES):
        raise ValueError("Draft targets must belong to the configured rollout")
    return {
        "languageIds": languages, "fileIds": [file_id],
        "method": "mt", "engineId": engine_id,
        "scope": "untranslated", "replaceTranslationsOption": "none",
        "autoApproveOption": "none", "skipApprovedTranslations": True,
        "duplicateTranslations": False,
    }


def run(api, mode, engine_id=None, *, sleep=time.sleep):
    if mode not in {"inspect", "pretranslate"}:
        raise ValueError("Mode must be inspect or pretranslate")
    if mode == "inspect":
        for engine in list_items(api, "/mts"):
            fields = ("id", "name", "type", "isEnabled", "enabledProjectIds",
                      "supportedLanguageIds", "enabledLanguageIds")
            print(json.dumps({key: engine[key] for key in fields if key in engine}), flush=True)
        for progress in list_items(api, f"/projects/{PROJECT_ID}/languages/progress"):
            if progress["languageId"] in LANGUAGES:
                print("Translation progress: " + json.dumps(progress), flush=True)
        jobs = api("GET", f"/projects/{PROJECT_ID}/pre-translations?limit=5&orderBy=createdAt%20desc")["data"]
        for item in jobs:
            job = item["data"]
            fields = ("identifier", "status", "progress", "attributes", "createdAt", "finishedAt")
            print("Recent job: " + json.dumps({key: job[key] for key in fields if key in job}), flush=True)
        return
    if engine_id is None or engine_id <= 0:
        raise ValueError("A positive MT engine ID is required")
    project_path = f"/projects/{PROJECT_ID}"
    project = api("GET", project_path)["data"]
    if project["sourceLanguageId"] != "en" or not set(LANGUAGES).issubset(project["targetLanguageIds"]):
        raise ValueError("Expected English source and all rollout targets enabled")
    engine = api("GET", f"/mts/{engine_id}")["data"]
    languages = [language for language in LANGUAGES if language in engine["supportedLanguageIds"]]
    missing = sorted(set(LANGUAGES) - set(languages))
    if missing:
        print(f"Engine does not support these targets; drafts remain pending: {', '.join(missing)}", flush=True)
    if not languages:
        raise ValueError("Engine does not support any rollout language")
    files = [item for item in list_items(api, project_path + "/files") if item["name"] == "strings.xml"]
    if len(files) != 1:
        raise ValueError("Expected exactly one strings.xml source file")
    job = api("POST", project_path + "/pre-translations", draft_body(engine_id, files[0]["id"], languages=languages))["data"]
    job_path = project_path + "/pre-translations/" + quote(str(job["identifier"]), safe="")
    print(f"Pre-translation started: {job['identifier']}", flush=True)
    deadline = time.monotonic() + 900
    while time.monotonic() < deadline:
        status = api("GET", job_path)["data"]["status"]
        if status == "finished":
            report = api("GET", job_path + "/report")["data"]
            print(json.dumps(report), flush=True)
            if not report.get("languages"):
                print("No new translations were reported; inspect translation progress before considering drafts complete.", flush=True)
            return
        if status in {"failed", "canceled"}:
            raise RuntimeError(f"Pre-translation {status}; inspect the job before retrying")
        sleep(10)
    raise TimeoutError("Pre-translation still pending; inspect the job before retrying")


if __name__ == "__main__":
    mode = os.environ.get("CROWDIN_DRAFT_MODE", "inspect")
    engine = os.environ.get("CROWDIN_MT_ENGINE_ID", "")
    run(request, mode, int(engine) if engine else None)
