"""Purpose: enable one requested Crowdin target language without removing existing languages.
Inputs: project ID, language ID, and CROWDIN_PERSONAL_TOKEN from the environment.
Outputs: an updated Crowdin target-language list, verified by a second read.
Notes: merges the current target list and verifies the result; never prints credentials or full response bodies.
"""

import json
import os
import re
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def add_language(request, project_id: str, language_id: str) -> None:
    if not re.fullmatch(r"[1-9][0-9]*", project_id):
        raise ValueError("Invalid project ID")
    if not re.fullmatch(r"[a-z]{2,3}(?:-[A-Za-z0-9]+)*", language_id):
        raise ValueError("Invalid language ID")
    language = request("GET", f"/languages/{language_id}")["data"]
    if language["id"] != language_id:
        raise ValueError("Unexpected language response")
    project_path = f"/projects/{project_id}"
    project = request("GET", project_path)["data"]
    existing = project["targetLanguageIds"]
    if not isinstance(existing, list) or not all(isinstance(item, str) for item in existing):
        raise ValueError("Unexpected target-language list")
    if language_id in existing:
        print(f"Target language already enabled: {language_id}")
        return
    if project["sourceLanguageId"] == language_id:
        raise ValueError("The source language cannot be added as a target")
    # Crowdin's test operation compares internal numeric IDs with the public language codes and rejects an otherwise unchanged list.
    request("PATCH", project_path, [
        {"op": "replace", "path": "/targetLanguageIds", "value": [*existing, language_id]},
    ])
    verified = request("GET", project_path)["data"]["targetLanguageIds"]
    if not set([*existing, language_id]).issubset(verified):
        raise RuntimeError("Target-language verification failed")
    print(f"Target language enabled and verified: {language_id}")


def main() -> None:
    token = os.environ["CROWDIN_PERSONAL_TOKEN"]

    def request(method, path, body=None):
        payload = None if body is None else json.dumps(body).encode()
        req = Request("https://api.crowdin.com/api/v2" + path, data=payload, method=method, headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        })
        try:
            with urlopen(req, timeout=30) as response:
                return json.load(response)
        except HTTPError as error:
            details = json.loads(error.read(65536))
            messages = []
            def collect_messages(value):
                if isinstance(value, dict):
                    if isinstance(value.get("message"), str):
                        messages.append(value["message"].replace(token, "[redacted]"))
                    for child in value.values():
                        collect_messages(child)
                elif isinstance(value, list):
                    for child in value:
                        collect_messages(child)
            collect_messages(details)
            raise RuntimeError(f"Crowdin {method} failed with HTTP {error.code}: {'; '.join(messages)}") from None

    add_language(request, os.environ["CROWDIN_PROJECT_ID"], os.environ["CROWDIN_LANGUAGE_ID"])


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError, RuntimeError) as error:
        sys.exit(str(error))
