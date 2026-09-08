import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from prepare_translation_export import prepare_export


class ExportTest(unittest.TestCase):
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
