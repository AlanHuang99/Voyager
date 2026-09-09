import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from prepare_translation_export import prepare_export


class ExportTest(unittest.TestCase):
    def test_missing_exports_keep_repository_translations_and_fill_new_source_strings(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            resources = repo / "app/src/main/res"
            source = '<resources><string name="hello">Hello</string><string name="root">Root access</string><string name="new">New feature</string><string name="brand" translatable="false">Voyager</string></resources>'
            baseline = '<resources><string name="hello">Bonjour</string><string name="root">Accès root</string></resources>'
            for locale, content in {"values": source, "values-fr": baseline}.items():
                path = resources / locale / "strings.xml"
                path.parent.mkdir(parents=True)
                path.write_text(content)
            subprocess.run(["git", "add", "app/src/main/res"], cwd=repo, check=True)
            path = resources / "values-fr/strings.xml"
            path.write_text('<resources><string name="hello">Salut</string></resources>')

            self.assertEqual([], prepare_export(repo))
            values = {item.get("name"): item.text for item in ET.parse(path).getroot()}
            self.assertEqual({"hello": "Salut", "root": "Accès root", "new": "New feature"}, values)

    def test_explicit_source_equal_translation_is_kept_and_missing_plural_is_restored(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            resources = repo / "app/src/main/res"
            source = '<resources><string name="stop">Stop</string><plurals name="count"><item quantity="one">One</item><item quantity="other">Many</item></plurals></resources>'
            baseline = source.replace('>Stop<', '>Arrêter<').replace('>One<', '>Un<').replace('>Many<', '>Plusieurs<')
            for locale, content in {"values": source, "values-fr": baseline}.items():
                path = resources / locale / "strings.xml"
                path.parent.mkdir(parents=True)
                path.write_text(content)
            subprocess.run(["git", "add", "app/src/main/res"], cwd=repo, check=True)
            path = resources / "values-fr/strings.xml"
            path.write_text('<resources><string name="stop">Stop</string></resources>')

            prepare_export(repo)
            root = ET.parse(path).getroot()
            self.assertEqual("Stop", root.find("string").text)
            self.assertEqual(["Un", "Plusieurs"], [item.text for item in root.find("plurals")])

    def test_preserves_existing_and_partial_translations_and_omits_english_only(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            resources = repo / "app/src/main/res"
            source = '<resources><string name="hello">Hello</string><plurals name="count"><item quantity="one">One</item><item quantity="other">Many</item></plurals></resources>'
            for locale, content in {
                "values": source,
                "values-fr": source.replace("Hello", "Bonjour"),
                "values-de": source,
                "values-zh-rTW": source.replace('<item quantity="one">One</item>', ""),
                "values-es": source.replace("Hello", "Hola"),
                "values-it": "<resources/>",
            }.items():
                path = resources / locale / "strings.xml"
                path.parent.mkdir(parents=True)
                path.write_text(content)
            subprocess.run(["git", "add", "app/src/main/res/values", "app/src/main/res/values-de"], cwd=repo, check=True)
            removed = prepare_export(repo)
            self.assertEqual({p.parts[-2] for p in removed}, {"values-zh-rTW", "values-it"})
            for locale in ["values", "values-de", "values-fr", "values-es"]:
                self.assertTrue((resources / locale / "strings.xml").is_file())
            spanish = ET.parse(resources / "values-es/strings.xml").getroot()
            self.assertEqual("Hola", spanish.find("string").text)
            self.assertEqual(["One", "Many"], [item.text for item in spanish.find("plurals")])

    def test_new_partial_locale_gets_missing_source_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            resources = repo / "app/src/main/res"
            source = '<resources><string name="hello">Hello</string><string name="new">New feature</string></resources>'
            for locale, content in {"values": source, "values-es": '<resources><string name="hello">Hola</string></resources>'}.items():
                path = resources / locale / "strings.xml"
                path.parent.mkdir(parents=True)
                path.write_text(content)
            subprocess.run(["git", "add", "app/src/main/res/values"], cwd=repo, check=True)
            self.assertEqual([], prepare_export(repo))
            values = {item.get("name"): item.text for item in ET.parse(resources / "values-es/strings.xml").getroot()}
            self.assertEqual({"hello": "Hola", "new": "New feature"}, values)

    def test_rejects_invalid_export_before_publishing(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            resources = repo / "app/src/main/res"
            for locale, content in {"values": "<resources/>", "values-fr": "<invalid"}.items():
                path = resources / locale / "strings.xml"
                path.parent.mkdir(parents=True)
                path.write_text(content)
            with self.assertRaises(ET.ParseError):
                prepare_export(repo)
            self.assertTrue((resources / "values-fr/strings.xml").exists())


if __name__ == "__main__":
    unittest.main()
