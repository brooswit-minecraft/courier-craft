import unittest

from check_gametest_count import RuntimeEvidenceError, verify_runtime_log


class CheckGameTestCountTest(unittest.TestCase):
    def test_consistent_terminal_pair_passes(self):
        log = "=== 3 GAME TESTS COMPLETE IN 12.3s ===\nAll 3 required tests passed :)"
        self.assertEqual(verify_runtime_log(log), 3)

    def test_zero_tests_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("=== 0 GAME TESTS COMPLETE IN 0.1s ===\nAll 0 required tests passed :)")

    def test_startup_failure_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("Failed to start the minecraft server")

    def test_reported_required_failure_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("=== 3 GAME TESTS COMPLETE IN 12.3s ===\n1 required tests failed :(")

    def test_mismatched_completed_and_passed_counts_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("=== 3 GAME TESTS COMPLETE IN 12.3s ===\nAll 2 required tests passed :)")

    def test_missing_summary_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("nothing useful here")


if __name__ == "__main__":
    unittest.main()
