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
                    {"id": "fr", "androidCode": "fr-rFR", "pluralCategoryNames": ["one", "other"]},
                    {"id": "ru", "androidCode": "ru-rRU", "pluralCategoryNames": ["one", "few", "many", "other"]},
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
            self.assertEqual(json.loads((output / "locale_plurals.json").read_text()), {
                "fr": ["one", "other"], "ru-rRU": ["one", "few", "many", "other"],
            })

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

    def test_new_approved_locales_have_their_own_link_and_no_provisional_notice(self):
        for locale, language in [("ru-rRU", "ru"), ("b+sr+Latn", "sr-CS")]:
            with self.subTest(locale=locale), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                repo, approved, drafts = root / "repo", root / "approved", root / "drafts"
                repo.mkdir()
                subprocess.run(["git", "init", "-q", str(repo)], check=True)
                source = repo / "app/src/main/res/values/strings.xml"
                source.parent.mkdir(parents=True)
                source.write_text('<resources><string name="hello">Hello</string></resources>')
                relative = f"app/src/main/res/values-{locale}/strings.xml"
                for base in [approved, drafts]:
                    target = base / relative
                    target.parent.mkdir(parents=True)
                    target.write_text('<resources><string name="hello">Translated greeting</string></resources>')
                (drafts / "locale_languages.json").write_text(json.dumps({locale: language}))
                prepare_export(repo, approved=approved, drafts=drafts)
                config_path = (repo / relative).with_name("translation_config.xml")
                self.assertTrue(config_path.is_file(), "Approved locales need explicit locale configuration")
                config = ET.parse(config_path).getroot()
                self.assertEqual(config.find("bool").text, "false")
                self.assertEqual(config.find("string").text, f"https://crowdin.com/project/voyagerandroid/{language}")

    def test_missing_russian_plural_forms_use_english_and_survive_later_source_updates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, drafts = root / "repo", root / "drafts"
            repo.mkdir()
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            source = repo / "app/src/main/res/values/strings.xml"
            source.parent.mkdir(parents=True)
            source.write_text('<resources><string name="hello">Hello</string><plurals name="count"><item quantity="one">%d file</item><item quantity="other">%d files</item></plurals></resources>')
            relative = "app/src/main/res/values-ru-rRU/strings.xml"
            target = drafts / relative
            target.parent.mkdir(parents=True)
            target.write_text('<resources><string name="hello">Привет</string><plurals name="count"><item quantity="other">%d файла</item></plurals></resources>')
            (drafts / "locale_languages.json").write_text(json.dumps({"ru-rRU": "ru"}))
            (drafts / "locale_plurals.json").write_text(json.dumps({"ru-rRU": ["one", "few", "many", "other"]}))
            prepare_export(repo, drafts=drafts)
            plural = ET.parse(repo / relative).getroot().find("plurals")
            self.assertEqual({e.get("quantity"): e.text for e in plural}, {
                "one": "%d file", "other": "%d файла", "few": "%d files", "many": "%d files",
            })
            subprocess.run(["git", "add", "app/src/main/res"], cwd=repo, check=True)
            source.write_text(source.read_text().replace('</resources>', '<plurals name="new"><item quantity="one">%d folder</item><item quantity="other">%d folders</item></plurals></resources>'))
            prepare_export(repo)
            plural = ET.parse(repo / relative).getroot().find("plurals[@name='new']")
            self.assertEqual({e.get("quantity"): e.text for e in plural}, {
                "one": "%d folder", "other": "%d folders", "few": "%d folders", "many": "%d folders",
            })

    def test_later_russian_plural_translation_replaces_expanded_english_fallbacks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, drafts, approved = root / "repo", root / "drafts", root / "approved"
            repo.mkdir()
            approved.mkdir()
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            source = repo / "app/src/main/res/values/strings.xml"
            source.parent.mkdir(parents=True)
            source.write_text('<resources><string name="hello">Hello</string><plurals name="count"><item quantity="one">%d file</item><item quantity="other">%d files</item></plurals></resources>')
            relative = "app/src/main/res/values-ru-rRU/strings.xml"
            target = drafts / relative
            target.parent.mkdir(parents=True)
            target.write_text('<resources><string name="hello">Привет</string></resources>')
            (drafts / "locale_plurals.json").write_text(json.dumps({"ru-rRU": ["one", "few", "many", "other"]}))
            prepare_export(repo, approved=approved, drafts=drafts)
            subprocess.run(["git", "add", "app/src/main/res"], cwd=repo, check=True)
            target.write_text('<resources><string name="hello">Привет</string><plurals name="count"><item quantity="one">%d файл</item><item quantity="few">%d файла</item><item quantity="many">%d файлов</item><item quantity="other">%d файла</item></plurals></resources>')
            prepare_export(repo, approved=approved, drafts=drafts)
            plural = ET.parse(repo / relative).getroot().find("plurals")
            self.assertEqual({e.get("quantity"): e.text for e in plural}, {
                "one": "%d файл", "few": "%d файла", "many": "%d файлов", "other": "%d файла",
            })

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
            source.write_text('<resources><string name="count">%d files</string><plurals name="items"><item quantity="one">%d item</item><item quantity="other">%d items</item></plurals></resources>')
            target = drafts / "app/src/main/res/values-ru-rRU/strings.xml"
            target.parent.mkdir(parents=True)
            target.write_text('<resources><string name="count">file</string></resources>')
            (drafts / "locale_plurals.json").write_text(json.dumps({"ru-rRU": ["one", "few", "many", "other"]}))
            with contextlib.redirect_stdout(StringIO()):
                removed = prepare_export(repo, drafts=drafts)
            self.assertEqual([Path("app/src/main/res/values-ru-rRU/strings.xml")], removed)
            self.assertFalse((repo / "app/src/main/res/values-ru-rRU/strings.xml").exists())


if __name__ == "__main__":
    unittest.main()
