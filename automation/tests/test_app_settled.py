import pytest
from playwright.sync_api import TimeoutError as PlaywrightTimeoutError

from mock_bank_automation.replay import APP_SETTLED_SCRIPT, wait_for_app_settled
from mock_bank_automation.replay_errors import ReplayFailure


class FakePage:
    def __init__(self, error=None):
        self.error = error
        self.calls = []

    def wait_for_function(self, script, timeout):
        self.calls.append((script, timeout))
        if self.error:
            raise self.error


def test_waits_for_the_app_settled_signal_with_the_step_timeout():
    page = FakePage()

    wait_for_app_settled(page, "search_member", 2_500)

    assert page.calls == [(APP_SETTLED_SCRIPT, 2_500)]
    assert "appState !== 'busy'" in APP_SETTLED_SCRIPT


def test_a_page_that_never_settles_fails_the_step_it_followed():
    page = FakePage(error=PlaywrightTimeoutError("Timeout 2500ms exceeded."))

    with pytest.raises(ReplayFailure) as failure:
        wait_for_app_settled(page, "submit_subaccount", 2_500)

    assert failure.value.step_id == "submit_subaccount"
    assert "did not finish loading" in str(failure.value)
