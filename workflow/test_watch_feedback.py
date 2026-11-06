import importlib.util
from pathlib import Path
import unittest

module_spec = importlib.util.spec_from_file_location("watch_feedback", Path(__file__).with_name("watch_feedback.py"))
watch = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(watch)

NO_RESPONSE = {"tick": 39, "requests": [{"method": "POST", "path": "/speakers", "status": None}]}
WRONG_ANSWER = {"tick": 50, "requests": [{"method": "DELETE", "path": "/talks/1448/co-speakers/1576", "status": 200}]}


def feedback(tick, checks, claimed=True, down=False, slug="talks"):
    """Feedback with one feature whose checks are {name: (status, transcript)}."""
    return {"tick": tick, "down": {"current": down, "reason": "no answer" if down else None},
            "features": [{"slug": slug, "claimed": claimed, "claimedAtTick": 0,
                          "checks": [{"name": n, "status": s, "transcript": t} for n, (s, t) in checks.items()]}]}


def timeline(*statuses, start=40, where=("POST", "/speakers")):
    """A check's timeline; FAULTY entries fail at `where` (method, templated path)."""
    return [{"tick": start + i, "status": s,
             "failure": {"method": where[0], "path": where[1]} if s == "FAULTY" else None}
            for i, s in enumerate(statuses)]


POST_NON_WORKSHOP = {"tick": 107, "requests": [
    {"method": "POST", "path": "/workshops/3403/registrations", "status": 404}]}
GET_NON_WORKSHOP = {"tick": 125, "requests": [
    {"method": "POST", "path": "/workshops/4205/registrations", "status": 409},
    {"method": "GET", "path": "/workshops/4205/registrations", "status": 404}]}
POST_REG = ("POST", "/workshops/{id}/registrations")
GET_REG = ("GET", "/workshops/{id}/registrations")


class WatchFeedbackTest(unittest.TestCase):
    def setUp(self):
        # Seen once, all OK, so later polls aren't a first run.
        self.state = {}
        self.timelines = {}
        self.poll(feedback(40, {"check-1": ("OK", None)}))

    def poll(self, fb, now=10_000.0, last_deploy=None):
        events = watch.observe(self.state, fb, lambda name: self.timelines.get(name, []), now, last_deploy)
        return [(kind, message) for kind, _, _, message in events]

    def kinds(self, events):
        return [kind for kind, _ in events]

    def test_one_tick_with_no_response_is_a_blip_not_an_alert(self):
        self.timelines["talks/check-1"] = timeline("OK", "FAULTY")
        events = self.poll(feedback(41, {"check-1": ("FAULTY", NO_RESPONSE)}))
        self.assertEqual(["blip"], self.kinds(events))
        self.assertIn("no response", events[0][1])
        self.timelines["talks/check-1"] = timeline("OK", "FAULTY", "OK")
        self.assertEqual(["blip-over"], self.kinds(self.poll(feedback(42, {"check-1": ("OK", None)}))))

    def test_blip_still_faulty_next_tick_becomes_real_once_then_recovers(self):
        self.timelines["talks/check-1"] = timeline("OK", "FAULTY")
        self.poll(feedback(41, {"check-1": ("FAULTY", NO_RESPONSE)}))
        self.timelines["talks/check-1"] = timeline("OK", "FAULTY", "FAULTY")
        events = self.poll(feedback(42, {"check-1": ("FAULTY", NO_RESPONSE)}))
        self.assertEqual(["REAL"], self.kinds(events))
        self.assertIn("since tick 41 (2 tick(s))", events[0][1])
        self.assertIn("Run /retro talks", events[0][1])
        self.timelines["talks/check-1"] = timeline("OK", "FAULTY", "FAULTY", "FAULTY")
        self.assertEqual([], self.poll(feedback(43, {"check-1": ("FAULTY", NO_RESPONSE)})))
        events = self.poll(feedback(44, {"check-1": ("OK", None)}))
        self.assertEqual(["RECOVERED"], self.kinds(events))
        self.assertIn("OK again at tick 44 (FAULTY since tick 41)", events[0][1])

    def test_wrong_answer_with_no_deploy_is_real_at_once(self):
        self.timelines["talks/check-1"] = timeline("OK", "FAULTY")
        events = self.poll(feedback(41, {"check-1": ("FAULTY", WRONG_ANSWER)}))
        self.assertEqual(["REAL"], self.kinds(events))
        self.assertIn("fails at DELETE /talks/1448/co-speakers/1576 -> 200", events[0][1])

    def test_wrong_answer_right_after_a_deploy_is_a_blip(self):
        self.timelines["talks/check-1"] = timeline("OK", "FAULTY")
        events = self.poll(feedback(41, {"check-1": ("FAULTY", WRONG_ANSWER)}), now=10_000.0, last_deploy=9_900.0)
        self.assertEqual(["blip"], self.kinds(events))
        self.assertIn("after a deploy", events[0][1])

    def test_old_deploy_does_not_excuse_a_failure(self):
        self.timelines["talks/check-1"] = timeline("OK", "FAULTY")
        events = self.poll(feedback(41, {"check-1": ("FAULTY", WRONG_ANSWER)}), now=10_000.0, last_deploy=9_000.0)
        self.assertEqual(["REAL"], self.kinds(events))

    def test_first_run_reports_a_long_standing_fault_with_its_start(self):
        # co-speakers/check-1 was FAULTY from tick 46 to 60 before anyone looked.
        state = {}
        self.timelines["co-speakers/check-1"] = timeline(*["OK"] * 6 + ["FAULTY"] * 15)
        events = watch.observe(state, feedback(60, {"check-1": ("FAULTY", WRONG_ANSWER)}, slug="co-speakers"),
                               lambda name: self.timelines[name], 10_000.0, None)
        self.assertEqual([("REAL", "co-speakers/check-1 FAULTY since tick 46 (15 tick(s)), "
                                   "fails at DELETE /talks/1448/co-speakers/1576 -> 200. Run /retro co-speakers")],
                         [(k, m) for k, _, _, m in events])

    def test_first_run_announces_no_claims(self):
        self.assertEqual(["talks"], self.state["claimed"])
        self.assertEqual(["talks"], self.state["confirmed"])

    def test_new_claim_then_harness_ok_once(self):
        fb = feedback(41, {"check-1": ("OK", None)})
        fb["features"].append({"slug": "acceptance", "claimed": True, "claimedAtTick": 41,
                               "checks": [{"name": "check-1", "status": "OK"}, {"name": "check-2", "status": "OK"}]})
        self.assertEqual([("CLAIMED", "acceptance claimed at tick 41, 2 checks"),
                          ("HARNESS-OK", "acceptance: all 2 checks OK at tick 41")], self.poll(fb))
        fb["tick"] = 42
        self.assertEqual([], self.poll(fb))

    def test_unclaimed_features_are_ignored(self):
        self.assertEqual([], self.poll(feedback(41, {}, claimed=False, slug="spring-boot-4")))

    def test_down_and_up(self):
        self.assertEqual(["DOWN"], self.kinds(self.poll(feedback(41, {"check-1": ("OK", None)}, down=True))))
        self.assertEqual(["UP"], self.kinds(self.poll(feedback(42, {"check-1": ("OK", None)}))))

    def test_trailing_faulty(self):
        self.assertEqual((0, None, None), watch.trailing_faulty(timeline("FAULTY", "OK")))
        self.assertEqual((2, 41, "POST /speakers"), watch.trailing_faulty(timeline("OK", "FAULTY", "FAULTY")))
        self.assertEqual((0, None, None), watch.trailing_faulty([]))

    def test_moved_when_a_fix_gets_the_check_further_then_recovered(self):
        # workshop-registration/check-2: POST answered 404 (ticks 107-124), then once that was fixed the
        # GET answered 404 (125-133), with a restart blip in between, then OK (134).
        name = "talks/check-1"
        self.timelines[name] = timeline("OK", "FAULTY", "FAULTY", where=POST_REG)
        events = self.poll(feedback(42, {"check-1": ("FAULTY", POST_NON_WORKSHOP)}))
        self.assertEqual(["REAL"], self.kinds(events))
        self.assertIn("fails at POST /workshops/3403/registrations -> 404", events[0][1])
        # A restart in between: no answer at all. Not a move, and doesn't overwrite where it fails.
        self.timelines[name] = self.timelines[name] + [{"tick": 43, "status": "FAULTY",
                                                        "failure": {"method": "POST", "path": "/speakers"}}]
        self.assertEqual([], self.poll(feedback(43, {"check-1": ("FAULTY", NO_RESPONSE)})))
        # Now it fails one step later.
        self.timelines[name] = self.timelines[name] + [{"tick": 44, "status": "FAULTY",
                                                        "failure": {"method": GET_REG[0], "path": GET_REG[1]}}]
        events = self.poll(feedback(44, {"check-1": ("FAULTY", GET_NON_WORKSHOP)}))
        self.assertEqual(["MOVED"], self.kinds(events))
        self.assertIn("now fails at GET /workshops/4205/registrations -> 404 (was POST /workshops/{id}/registrations)",
                      events[0][1])
        # Same failure next tick: nothing new.
        self.timelines[name] = self.timelines[name] + [{"tick": 45, "status": "FAULTY",
                                                        "failure": {"method": GET_REG[0], "path": GET_REG[1]}}]
        self.assertEqual([], self.poll(feedback(45, {"check-1": ("FAULTY", GET_NON_WORKSHOP)})))
        events = self.poll(feedback(46, {"check-1": ("OK", None)}))
        self.assertEqual(["RECOVERED"], self.kinds(events))
        self.assertIn("FAULTY since tick 41", events[0][1])

    def test_harness_notes_are_included(self):
        self.timelines["talks/check-1"] = timeline("OK", "FAULTY")
        fb = feedback(41, {"check-1": ("FAULTY", WRONG_ANSWER)})
        fb["features"][0]["checks"][0]["notes"] = ["expected a 4xx"]
        events = self.poll(fb)
        self.assertIn("Harness notes: expected a 4xx.", events[0][1])

    def test_repeated_polls_of_the_same_faulty_state_say_nothing(self):
        self.timelines["talks/check-1"] = timeline("OK", "FAULTY")
        self.poll(feedback(41, {"check-1": ("FAULTY", WRONG_ANSWER)}))
        self.timelines["talks/check-1"] = timeline("OK", "FAULTY", "FAULTY")
        self.assertEqual([], self.poll(feedback(42, {"check-1": ("FAULTY", WRONG_ANSWER)})))


if __name__ == "__main__":
    unittest.main()
