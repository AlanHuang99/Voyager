"""Check provisional export boundaries, fallbacks, and preservation of human wording."""

import contextlib
from io import BytesIO, StringIO
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from export_translation_drafts import extract_resources
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

    def test_archive_filters_out_languages_outside_the_rollout(self):
        content = BytesIO()
        with ZipFile(content, "w") as archive:
            archive.writestr("app/src/main/res/values-it/strings.xml", "<resources/>")
            archive.writestr("app/src/main/res/values-pl/strings.xml", "<resources/>")
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(StringIO()):
            output = Path(directory)
            extract_resources(content.getvalue(), output, approved=False)
            self.assertTrue((output / "app/src/main/res/values-it/strings.xml").exists())
            self.assertFalse((output / "app/src/main/res/values-pl/strings.xml").exists())

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
