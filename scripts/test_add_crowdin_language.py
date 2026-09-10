import io
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError

from add_crowdin_language import add_language, main


class AddLanguageTest(unittest.TestCase):
    def test_preserves_languages_and_verifies_polish_was_added(self):
        request = Mock(side_effect=[
            {"data": {"id": "pl"}},
            {"data": {"sourceLanguageId": "en", "targetLanguageIds": ["fr", "zh-CN"]}},
            {},
            {"data": {"targetLanguageIds": ["fr", "zh-CN", "pl"]}},
        ])
        add_language(request, "927407", "pl")
        self.assertEqual(request.call_args_list[2].args, ("PATCH", "/projects/927407", [
            {"op": "replace", "path": "/targetLanguageIds", "value": ["fr", "zh-CN", "pl"]},
        ]))

    def test_already_enabled_is_read_only(self):
        request = Mock(side_effect=[
            {"data": {"id": "pl"}},
            {"data": {"sourceLanguageId": "en", "targetLanguageIds": ["pl", "fr"]}},
        ])
        add_language(request, "927407", "pl")
        self.assertEqual(request.call_count, 2)

    def test_invalid_language_cannot_reach_api(self):
        request = Mock()
        with self.assertRaises(ValueError):
            add_language(request, "927407", "../projects")
        request.assert_not_called()

    def test_missing_existing_language_fails_verification(self):
        request = Mock(side_effect=[
            {"data": {"id": "pl"}},
            {"data": {"sourceLanguageId": "en", "targetLanguageIds": ["fr"]}},
            {},
            {"data": {"targetLanguageIds": ["pl"]}},
        ])
        with self.assertRaises(RuntimeError):
            add_language(request, "927407", "pl")

    def test_api_validation_error_reports_reason_without_credentials(self):
        response = io.BytesIO(b'{"error":{"message":"Validation failed: secret-test-token"}}')
        error = HTTPError("https://api.crowdin.com/api/v2/languages/pl", 400, "Bad Request", {}, response)
        with patch.dict("os.environ", {
            "CROWDIN_PERSONAL_TOKEN": "secret-test-token",
            "CROWDIN_PROJECT_ID": "927407",
            "CROWDIN_LANGUAGE_ID": "pl",
        }), patch("add_crowdin_language.urlopen", side_effect=error):
            with self.assertRaises(RuntimeError) as caught:
                main()
        self.assertIn("HTTP 400: Validation failed: [redacted]", str(caught.exception))
        self.assertNotIn("secret-test-token", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
