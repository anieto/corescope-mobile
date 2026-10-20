import io
import json
import unittest
import urllib.error
from unittest.mock import Mock

from probe import Probe, NoRedirect


class Response(io.BytesIO):
    status = 200
    headers = {"Content-Type": "application/json"}


class ProbeTests(unittest.TestCase):
    def test_budget_caps_actual_requests(self):
        probe = Probe("analyzer.meshtexas.org")
        probe.opener = Mock()
        probe.opener.open.side_effect = lambda *args, **kwargs: Response(b"{}")
        for _ in range(20):
            probe.get("/api/stats", "version")
        self.assertEqual(probe.opener.open.call_count, 12)
        self.assertEqual(len(probe.records), 12)

    def test_rate_limit_stops_source_without_retry(self):
        probe = Probe("analyzer.meshtexas.org")
        probe.opener = Mock()
        probe.opener.open.side_effect = urllib.error.HTTPError("https://example.invalid", 429, "limited", {}, None)
        probe.get("/api/stats", "version")
        probe.get("/api/stats", "retry")
        self.assertEqual(probe.opener.open.call_count, 1)
        self.assertTrue(probe.records[0]["cooldown"])

    def test_report_omits_payload_and_entity_path(self):
        probe = Probe("analyzer.meshtexas.org")
        probe.opener = Mock()
        probe.opener.open.return_value = Response(b'{"name":"PRIVATE-NAME","public_key":"PRIVATE-KEY"}')
        value = probe.get("/api/nodes/PRIVATE-KEY", "node")
        self.assertEqual(value["name"], "PRIVATE-NAME")
        self.assertNotIn("PRIVATE", json.dumps(probe.records))
        request = probe.opener.open.call_args.args[0]
        self.assertEqual(request.get_method(), "GET")
        self.assertIsNone(request.get_header("Authorization"))
        self.assertIsNone(request.get_header("Cookie"))

    def test_redirects_are_not_followed(self):
        self.assertIsNone(NoRedirect().redirect_request(None, None, 302, "redirect", {}, "https://other.invalid"))


if __name__ == "__main__":
    unittest.main()
