"""Verify that draft operations preserve translations and do not expose MT credentials."""

import contextlib
import io
import unittest
from unittest.mock import Mock

from crowdin_drafts import draft_body, run


class DraftTests(unittest.TestCase):
    def test_drafts_are_unapproved_and_do_not_replace_translations(self):
        body = draft_body(12, 34)
        self.assertEqual(body["languageIds"], ["it", "vi"])
        self.assertEqual(body["fileIds"], [34])
        self.assertEqual(body["scope"], "untranslated")
        self.assertEqual(body["replaceTranslationsOption"], "none")
        self.assertEqual(body["autoApproveOption"], "none")
        self.assertTrue(body["skipApprovedTranslations"])

    def test_inspection_never_exposes_provider_credentials_or_writes(self):
        api = Mock(side_effect=[{"data": [{"data": {
            "id": 12, "name": "Test engine", "credentials": {"apiKey": "private-key"},
        }}]}, {"data": []}, {"data": []}])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            run(api, "inspect")
        self.assertNotIn("private-key", output.getvalue())
        self.assertNotIn("credentials", output.getvalue())
        self.assertEqual(api.call_args.args[0], "GET")
        self.assertEqual(api.call_count, 3)
        self.assertTrue(all(call.args[0] == "GET" for call in api.call_args_list))

    def test_missing_target_prevents_mutation(self):
        api = Mock(return_value={"data": {"sourceLanguageId": "en", "targetLanguageIds": ["it"]}})
        with self.assertRaises(ValueError):
            run(api, "pretranslate", 12)
        self.assertEqual(api.call_count, 1)

    def test_failed_job_is_not_reported_as_completed_or_retried(self):
        api = Mock(side_effect=[
            {"data": {"sourceLanguageId": "en", "targetLanguageIds": ["it", "vi"]}},
            {"data": {"id": 12, "supportedLanguageIds": ["it", "vi"]}},
            {"data": [{"data": {"id": 34, "name": "strings.xml"}}]},
            {"data": {"identifier": "job-id"}},
            {"data": {"status": "failed"}},
        ])
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(RuntimeError):
            run(api, "pretranslate", 12)
        self.assertEqual(sum(call.args[0] == "POST" for call in api.call_args_list), 1)

    def test_completed_job_returns_report_after_waiting(self):
        api = Mock(side_effect=[
            {"data": {"sourceLanguageId": "en", "targetLanguageIds": ["it", "vi"]}},
            {"data": {"id": 12, "supportedLanguageIds": ["it", "vi"]}},
            {"data": [{"data": {"id": 34, "name": "strings.xml"}}]},
            {"data": {"identifier": "job-id"}},
            {"data": {"status": "inProgress"}},
            {"data": {"status": "finished"}},
            {"data": {"languages": [{"id": "vi"}], "preTranslateType": "mt"}},
        ])
        sleep = Mock()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            run(api, "pretranslate", 12, sleep=sleep)
        sleep.assert_called_once_with(10)
        self.assertIn('"preTranslateType": "mt"', output.getvalue())
        self.assertEqual(api.call_args_list[3].args[2], draft_body(12, 34))

    def test_ambiguous_source_files_prevent_mutation(self):
        api = Mock(side_effect=[
            {"data": {"sourceLanguageId": "en", "targetLanguageIds": ["it", "vi"]}},
            {"data": {"id": 12, "supportedLanguageIds": ["it", "vi"]}},
            {"data": [{"data": {"id": value, "name": "strings.xml"}} for value in [34, 35]]},
        ])
        with self.assertRaises(ValueError):
            run(api, "pretranslate", 12)
        self.assertTrue(all(call.args[0] == "GET" for call in api.call_args_list))

    def test_unsupported_target_is_reported_and_excluded(self):
        api = Mock(side_effect=[
            {"data": {"sourceLanguageId": "en", "targetLanguageIds": ["it", "vi"]}},
            {"data": {"id": 12, "supportedLanguageIds": ["it"]}},
            {"data": [{"data": {"id": 34, "name": "strings.xml"}}]},
            {"data": {"identifier": "job-id"}},
            {"data": {"status": "finished"}},
            {"data": {"languages": [{"id": "it"}], "preTranslateType": "mt"}},
        ])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            run(api, "pretranslate", 12)
        self.assertIn("drafts remain pending: vi", output.getvalue())
        self.assertEqual(api.call_args_list[3].args[2]["languageIds"], ["it"])

    def test_out_of_scope_language_is_rejected(self):
        with self.assertRaises(ValueError):
            draft_body(12, 34, languages=["fr"])


if __name__ == "__main__":
    unittest.main()
