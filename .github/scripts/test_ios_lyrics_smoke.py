import tempfile
from pathlib import Path
import unittest

import ios_lyrics_smoke as smoke


class FixtureReadinessTests(unittest.TestCase):
    def wait_for(self, case, markers):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "console.log"
            log.write_text("\n".join(markers) + "\n", encoding="utf-8")
            return smoke.wait_ready(case, log, lambda: True, timeout=0.2)

    def test_each_configured_layout_becomes_ready(self):
        for case, stages in smoke.CASES.items():
            with self.subTest(case=case):
                follow = f"{smoke.MARKER}:FOLLOW_SETTLED:{case}"
                ready = f"{smoke.MARKER}:READY:{case}:" + ":".join(stages)
                self.assertEqual(self.wait_for(case, [follow, ready]), ready)

    def test_wrong_case_and_layout_are_rejected(self):
        case = "romanized-off"
        follow = f"{smoke.MARKER}:FOLLOW_SETTLED:{case}"
        for suffix in [
            "romanized-on:none:phonetic",
            "romanized-off:none:phonetic",
            "romanized-off:none:phonetic-hidden-extra",
        ]:
            with self.subTest(suffix=suffix):
                with self.assertRaisesRegex(smoke.SmokeError, "Wrong case or visual layout"):
                    self.wait_for(case, [follow, f"{smoke.MARKER}:READY:{suffix}"])

    def test_readiness_requires_initial_follow_to_settle(self):
        ready = f"{smoke.MARKER}:READY:timed-cover:decoded:timed"
        with self.assertRaisesRegex(smoke.SmokeError, "before initial lyric follow settled"):
            self.wait_for("timed-cover", [ready])

    def test_embedded_or_suffixed_tokens_are_not_markers(self):
        ready = f"{smoke.MARKER}:READY:timed-cover:decoded:timed"
        for text in ["prefix" + ready, ready + ":extra"]:
            with self.subTest(text=text):
                self.assertEqual(smoke.fixture_markers(text.encode("ascii")), [])


if __name__ == "__main__":
    unittest.main()
