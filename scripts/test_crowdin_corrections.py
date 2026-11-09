import copy
import unittest
from pathlib import Path
from unittest.mock import Mock

import crowdin_corrections as corrections


class CorrectionTests(unittest.TestCase):
    def setUp(self):
        self.entry = {"language": "zh-TW", "identifier": "archive_details_more",
                      "source": {"one": "and %d more", "other": "and %d more"},
                      "quantity": "other", "previous": "%d如下", "text": "另有 %d 個項目"}
        self.source = {"id": 10, "identifier": self.entry["identifier"],
                       "text": self.entry["source"], "hasPlurals": True}

    def api(self, *, approved=None, existing=None):
        old = {"id": 20, "text": self.entry["previous"], "pluralCategoryName": "other"}
        new = {"id": 21, "text": self.entry["text"], "pluralCategoryName": "other"}
        state = {"translations": [old, *(existing or [])], "approved": approved or []}
        calls = []

        def api(method, path, body=None):
            calls.append((method, path, body))
            if method == "GET":
                values = state["approved"] if "/approvals?" in path else state["translations"]
                return {"data": [{"data": value} for value in values]}
            if path.endswith("/translations"):
                self.assertEqual(body["pluralCategoryName"], "other")
                state["translations"].append(new)
                return {"data": new}
            self.assertEqual(body, {"translationId": 21})
            state["approved"] = [{"id": 30, "translationId": 21}]
            return {"data": state["approved"][0]}

        return api, state, calls

    def test_changed_source_prevents_any_mutation(self):
        api = Mock()
        changed = copy.deepcopy(self.source)
        changed["text"]["other"] = "new source"
        with self.assertRaisesRegex(ValueError, "source"):
            corrections.plan(api, [self.entry], [changed])
        api.assert_not_called()

    def test_unexpected_approved_human_correction_prevents_mutation(self):
        api, _, calls = self.api(approved=[{"translationId": 99}], existing=[
            {"id": 99, "text": "human correction", "pluralCategoryName": "other"}])
        with self.assertRaisesRegex(ValueError, "approved"):
            corrections.plan(api, [self.entry], [self.source])
        self.assertTrue(all(method == "GET" for method, _, _ in calls))

    def test_adds_and_approves_only_requested_plural_preserving_old_translation(self):
        api, state, calls = self.api()
        pending = corrections.plan(api, [self.entry], [self.source])
        corrections.apply(api, pending)
        self.assertEqual([x["id"] for x in state["translations"]], [20, 21])
        self.assertEqual(state["approved"][0]["translationId"], 21)
        self.assertFalse(any(method in {"DELETE", "PATCH"} for method, _, _ in calls))

    def test_reuses_existing_correction_and_is_idempotent(self):
        new = {"id": 21, "text": self.entry["text"], "pluralCategoryName": "other"}
        api, _, calls = self.api(approved=[{"translationId": 21}], existing=[new])
        corrections.apply(api, corrections.plan(api, [self.entry], [self.source]))
        self.assertTrue(all(method == "GET" for method, _, _ in calls))

    def test_missing_previous_translation_prevents_mutation(self):
        self.entry["previous"] = "unknown"
        api, _, _ = self.api()
        self.entry["previous"] = "different"
        with self.assertRaisesRegex(ValueError, "previous"):
            corrections.plan(api, [self.entry], [self.source])

    def test_mapping_preserves_source_placeholders(self):
        entries = corrections.load_mapping(Path(__file__).with_name("crowdin_corrections.json"))
        corrections.check_mapping(entries, Path(__file__).parents[1] / "app/src/main/res/values/strings.xml")
        entries[0]["text"] += " %1$s"
        with self.assertRaisesRegex(ValueError, "placeholder"):
            corrections.check_mapping(entries, Path(__file__).parents[1] / "app/src/main/res/values/strings.xml")

    def test_pagination_keeps_existing_query_filters(self):
        api = Mock(side_effect=[{"data": [{"data": {"id": n}} for n in range(100)]}, {"data": []}])
        self.assertEqual(len(list(corrections.items(api, "/path?languageId=zh-TW"))), 100)
        self.assertIn("languageId=zh-TW&limit=100&offset=100", api.call_args_list[1].args[1])


if __name__ == "__main__":
    unittest.main()
