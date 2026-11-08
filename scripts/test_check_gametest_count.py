import unittest

from check_gametest_count import RuntimeEvidenceError, verify_runtime_log

NAMED_TESTS_LOG = (
    "Running test batch 'legacyRegistryNamesStillResolveAfterTheSplit:0' (1 tests)...\n"
    "Running test batch 'preSplitSavedContractNbtLoadsThroughTheRelocatedStorage:0' (1 tests)...\n"
)


class CheckGameTestCountTest(unittest.TestCase):
    def test_consistent_terminal_pair_with_named_tests_passes(self):
        log = NAMED_TESTS_LOG + "=== 3 GAME TESTS COMPLETE IN 12.3s ===\nAll 3 required tests passed :)"
        self.assertEqual(verify_runtime_log(log), 3)

    def test_zero_tests_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("=== 0 GAME TESTS COMPLETE IN 0.1s ===\nAll 0 required tests passed :)")

    def test_startup_failure_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("Failed to start the minecraft server")

    def test_reported_required_failure_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log(NAMED_TESTS_LOG + "=== 3 GAME TESTS COMPLETE IN 12.3s ===\n1 required tests failed :(")

    def test_mismatched_completed_and_passed_counts_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log(NAMED_TESTS_LOG + "=== 3 GAME TESTS COMPLETE IN 12.3s ===\nAll 2 required tests passed :)")

    def test_missing_summary_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("nothing useful here")

    def test_passing_other_tests_without_the_named_ones_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("Running test batch 'someUnrelatedTest:0' (1 tests)...\n"
                                "=== 1 GAME TESTS COMPLETE IN 1.0s ===\nAll 1 required tests passed :)")

    def test_only_one_of_the_two_named_tests_present_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("Running test batch 'legacyRegistryNamesStillResolveAfterTheSplit:0' (1 tests)...\n"
                                "=== 1 GAME TESTS COMPLETE IN 1.0s ===\nAll 1 required tests passed :)")


if __name__ == "__main__":
    unittest.main()
