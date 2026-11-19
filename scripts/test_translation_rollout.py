"""Check provisional export boundaries, fallbacks, and preservation of human wording."""

import contextlib
from io import BytesIO, StringIO
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from export_translation_drafts import export, extract_resources
from prepare_translation_export import prepare_export, usable_draft


class RolloutTest(unittest.TestCase):
    def test_provisional_merge_preserves_existing_and_approved_and_protects_dangerous_strings(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, approved, drafts = root / "repo", root / "approved", root / "drafts"
            repo.mkdir()
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            relative = "app/src/main/res/values-fr/strings.xml"
            source = '<resources><string name="hello">Hello</string><string name="new">New</string><string name="dialog_delete_choice_message">Delete forever?</string><string name="number">%1$d files</string><string name="ready">Ready</string></resources>'
            baseline = '<resources><string name="hello">Bonjour</string><string name="new">New</string></resources>'
            for path, text in [(repo / "app/src/main/res/values/strings.xml", source), (repo / relative, baseline)]:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            subprocess.run(["git", "add", "app/src/main/res"], cwd=repo, check=True)
            for base, text in [
                (approved, '<resources><string name="ready">Prêt</string></resources>'),
                (drafts, '<resources><string name="hello">Salut</string><string name="new">Nouveau</string><string name="dialog_delete_choice_message">Wrong</string><string name="number">%1$s fichiers</string><string name="ready">Machine</string></resources>'),
            ]:
                path = base / relative
                path.parent.mkdir(parents=True)
                path.write_text(text)
            with contextlib.redirect_stdout(StringIO()):
                prepare_export(repo, approved=approved, drafts=drafts)
            values = {e.get("name"): e.text for e in ET.parse(repo / relative).getroot()}
            self.assertEqual(values, {"hello": "Bonjour", "new": "Nouveau", "dialog_delete_choice_message": "Delete forever?", "number": "%1$d files", "ready": "Prêt"})
            config = ET.parse((repo / relative).with_name("translation_config.xml")).getroot()
            self.assertEqual(config.find("bool").text, "true")
            self.assertEqual(config.find("string").text, "https://crowdin.com/project/voyagerandroid/fr")

    def test_plural_formatting_is_checked_for_every_quantity(self):
        source = ET.fromstring('<plurals><item quantity="one">%d file</item><item quantity="other">%d files</item></plurals>')
        valid = ET.fromstring('<plurals><item quantity="other">%d fichiers</item></plurals>')
        invalid = ET.fromstring('<plurals><item quantity="other">fichiers</item></plurals>')
        self.assertTrue(usable_draft(source, valid))
        self.assertFalse(usable_draft(source, invalid))

    def test_reordered_indexed_arguments_are_allowed(self):
        self.assertTrue(usable_draft(ET.fromstring('<string>%1$s: %2$d</string>'), ET.fromstring('<string>%2$d: %1$s</string>')))

    def test_archive_accepts_languages_outside_the_machine_translation_rollout(self):
        content = BytesIO()
        with ZipFile(content, "w") as archive:
            archive.writestr("app/src/main/res/values-it/strings.xml", "<resources/>")
            archive.writestr("app/src/main/res/values-pl/strings.xml", "<resources/>")
            archive.writestr("app/src/main/res/values-ru-rRU/strings.xml", "<resources/>")
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(StringIO()):
            output = Path(directory)
            extract_resources(content.getvalue(), output, approved=False)
            self.assertTrue((output / "app/src/main/res/values-it/strings.xml").exists())
            self.assertTrue((output / "app/src/main/res/values-pl/strings.xml").exists())
            self.assertTrue((output / "app/src/main/res/values-ru-rRU/strings.xml").exists())

    def test_archive_accepts_script_and_numeric_region_locales_without_other_qualifiers(self):
        content = BytesIO()
        with ZipFile(content, "w") as archive:
            for locale in ["fr", "b+sr+Latn", "b+es+419", "b+zh+Hant+TW", "fr-night", "b+sr+Latn-night"]:
                archive.writestr(f"app/src/main/res/values-{locale}/strings.xml", "<resources/>")
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(StringIO()):
            output = Path(directory)
            extract_resources(content.getvalue(), output, approved=False)
            self.assertEqual(
                {p.parent.name for p in output.rglob("strings.xml")},
                {"values-fr", "values-b+sr+Latn", "values-b+es+419", "values-b+zh+Hant+TW"},
            )

    def test_export_requests_all_targets_and_saves_crowdin_language_ids(self):
        content = BytesIO()
        with ZipFile(content, "w") as archive:
            archive.writestr("app/src/main/res/values-fr-rFR/strings.xml", "<resources/>")
            archive.writestr("app/src/main/res/values-ru-rRU/strings.xml", "<resources/>")

        def api(method, path, body=None):
            if method == "GET" and path == "/projects/927407":
                return {"data": {"targetLanguages": [
                    {"id": "fr", "androidCode": "fr-rFR"},
                    {"id": "ru", "androidCode": "ru-rRU"},
                ]}}
            if method == "POST" and path == "/projects/927407/translations/builds":
                self.assertEqual(body, {
                    "exportApprovedOnly": False, "skipUntranslatedStrings": True, "skipUntranslatedFiles": False,
                })
                return {"data": {"id": 42}}
            if method == "GET" and path == "/projects/927407/translations/builds/42":
                return {"data": {"status": "finished"}}
            if method == "GET" and path == "/projects/927407/translations/builds/42/download":
                return {"data": {"url": "https://example.org/translations.zip"}}
            self.fail(f"Unexpected API request: {method} {path}")

        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(StringIO()):
            output = Path(directory)
            with patch("export_translation_drafts.urlopen", return_value=BytesIO(content.getvalue())):
                export(api, output, approved=False)
            self.assertTrue((output / "app/src/main/res/values-ru-rRU/strings.xml").is_file())
            self.assertEqual(json.loads((output / "locale_languages.json").read_text()), {"fr": "fr", "ru-rRU": "ru"})

    def test_new_russian_locale_imports_valid_contributions_with_fallbacks_and_notice(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, drafts = root / "repo", root / "drafts"
            repo.mkdir()
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            source = repo / "app/src/main/res/values/strings.xml"
            source.parent.mkdir(parents=True)
            source.write_text('<resources><string name="hello">Hello</string><string name="missing">Missing</string><string name="number">%1$d files</string><string name="dialog_delete_choice_message">Delete forever?</string></resources>')
            relative = "app/src/main/res/values-ru-rRU/strings.xml"
            target = drafts / relative
            target.parent.mkdir(parents=True)
            target.write_text('<resources><string name="hello">Привет</string><string name="number">%1$s</string><string name="dialog_delete_choice_message">Удалить навсегда?</string></resources>')
            (drafts / "locale_languages.json").write_text(json.dumps({"ru-rRU": "ru"}))
            with contextlib.redirect_stdout(StringIO()):
                prepare_export(repo, drafts=drafts)
            self.assertTrue((repo / relative).is_file(), "Russian contributions must not depend on the MT rollout list")
            values = {item.get("name"): item.text for item in ET.parse(repo / relative).getroot()}
            self.assertEqual(values, {"hello": "Привет", "missing": "Missing", "number": "%1$d files", "dialog_delete_choice_message": "Delete forever?"})
            config_path = (repo / relative).with_name("translation_config.xml")
            config = ET.parse(config_path).getroot()
            self.assertEqual(config.find("bool").text, "true")
            self.assertEqual(config.find("string").text, "https://crowdin.com/project/voyagerandroid/ru")
            subprocess.run(["git", "add", "app/src/main/res"], cwd=repo, check=True)
            with contextlib.redirect_stdout(StringIO()):
                prepare_export(repo, drafts=drafts)
            self.assertEqual(ET.parse(config_path).getroot().find("bool").text, "true")

    def test_archive_traversal_is_rejected(self):
        content = BytesIO()
        with ZipFile(content, "w") as archive:
            archive.writestr("../../outside", "unsafe")
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            extract_resources(content.getvalue(), Path(directory), approved=False)

    def test_new_locale_with_only_invalid_drafts_is_omitted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, drafts = root / "repo", root / "drafts"
            repo.mkdir()
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            source = repo / "app/src/main/res/values/strings.xml"
            source.parent.mkdir(parents=True)
            source.write_text('<resources><string name="count">%d files</string></resources>')
            target = drafts / "app/src/main/res/values-it/strings.xml"
            target.parent.mkdir(parents=True)
            target.write_text('<resources><string name="count">file</string></resources>')
            with contextlib.redirect_stdout(StringIO()):
                removed = prepare_export(repo, drafts=drafts)
            self.assertEqual([Path("app/src/main/res/values-it/strings.xml")], removed)
            self.assertFalse((repo / "app/src/main/res/values-it/strings.xml").exists())


if __name__ == "__main__":
    unittest.main()
