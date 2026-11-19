"""Record docs/demo.gif, the README walkthrough of the staff console, from the running stack.

    make reset
    cd automation
    PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers PYTHONPATH=<site-packages with Pillow> \
        .venv/bin/python scripts/record_demo.py

Pillow is not a project dependency; any install of it for the same Python works. Each step is
a screenshot with a caption and a hold time, so the GIF stays small and the text stays sharp.
The run opens real sub-accounts and writes audit events; `make reset` restores the seed data.
"""
import io
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from playwright.sync_api import Page, sync_playwright

from mock_bank_automation.replay import APP_SETTLED_SCRIPT

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
CONSUMER_URL = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8081"
OUT = Path(__file__).resolve().parents[2] / "docs" / "demo.gif"
WIDTH, HEIGHT, BAR = 960, 600, 40
FONT = ImageFont.load_default(size=18)
TYPE_MS = 70

frames: list[tuple[Image.Image, int]] = []


def snap(page: Page, caption: str, ms: int) -> None:
    page.wait_for_function(APP_SETTLED_SCRIPT)
    shot = Image.open(io.BytesIO(page.screenshot())).convert("RGB")
    frame = Image.new("RGB", (WIDTH, HEIGHT + BAR), "#111827")
    frame.paste(shot, (0, BAR))
    ImageDraw.Draw(frame).text((16, BAR // 2), caption, font=FONT, fill="white", anchor="lm")
    frames.append((frame, ms))


def type_into(page: Page, selector: str, text: str, caption: str) -> None:
    page.click(selector)
    for char in text:
        page.keyboard.type(char)
        snap(page, caption, TYPE_MS)


def look_up(page: Page, member_id: str, caption: str) -> None:
    page.get_by_role("link", name="Member lookup").click()
    page.wait_for_selector("#memberId")
    type_into(page, "#memberId", member_id, caption)
    page.get_by_role("button", name="Search member records").click()


def open_sub_account(page: Page, product: str, deposit: str, caption: str) -> None:
    page.get_by_role("link", name="Open sub-account").click()
    page.wait_for_selector("#productName")
    type_into(page, "#productName", product, caption)
    type_into(page, "#initialDeposit", deposit, caption)
    snap(page, caption, 800)
    page.get_by_role("button", name="Open sub-account").click()


def record(page: Page) -> None:
    page.goto(f"{BASE_URL}/login")
    page.wait_for_selector("#operatorId")
    snap(page, "Mock bank staff console: React, Spring Boot, PostgreSQL, Kafka", 3500)
    type_into(page, "#operatorId", "operator", "Staff sign-in (server-side session)")
    type_into(page, "#password", "password", "Staff sign-in (server-side session)")
    page.get_by_role("button", name="Sign in").click()
    page.wait_for_selector("#memberId")
    snap(page, "Signed in", 1500)

    look_up(page, "12345", "Look up a member by their 5-digit ID")
    page.wait_for_url("**/members/12345")
    snap(page, "Member details and accounts, loaded from PostgreSQL", 4000)

    caption = "Open a sub-account with a $250 deposit"
    open_sub_account(page, "Savings Plus", "250.00", caption)
    page.wait_for_selector("#confirmation-code")
    snap(page, "Opened: the account and its audit row commit in one transaction", 5000)
    page.get_by_role("link", name="Return to member detail").click()
    page.wait_for_url("**/members/12345")
    snap(page, "The new account is on the member", 3500)

    open_sub_account(page, "Savings Plus", "7000.00", "Try a $7,000 deposit")
    page.wait_for_selector(".banner.warning")
    snap(page, "Over $5,000: refused with 409 and flagged for human approval", 5000)

    look_up(page, "77777", "Look up a restricted member")
    page.wait_for_url("**/members/77777")
    snap(page, "Restricted members cannot open sub-accounts", 4000)

    look_up(page, "99999", "Look up an unknown ID")
    page.wait_for_selector(".banner.warning")
    snap(page, "Not found is a business outcome (404), not a crash", 3000)

    page.get_by_role("link", name="Audit log").click()
    page.wait_for_url("**/ops/audit")
    snap(page, "Every action is in the audit log, and published to Kafka", 5000)

    page.get_by_role("link", name="Reports").click()
    page.wait_for_url("**/ops/reports")
    snap(page, "Reports built from hand-written SQL joins", 4000)
    page.get_by_text("Member activity", exact=True).evaluate(
        "el => el.scrollIntoView({block: 'start', behavior: 'instant'})")
    snap(page, "Per-member activity: COUNT(*) FILTER over the audit log", 3500)

    page.goto(f"{CONSUMER_URL}/api/events/summary")
    page.evaluate("document.body.style.zoom = '1.6'")  # the browser's raw JSON view is tiny
    snap(page, "audit-consumer read the same events from Kafka into its own database", 5000)

    page.goto(f"{BASE_URL}/swagger-ui.html")
    page.wait_for_selector(".opblock")
    snap(page, "OpenAPI docs generated from the code: /swagger-ui.html", 4500)


def save_gif() -> None:
    # One palette for every frame keeps colours stable, so Pillow only re-encodes what changed.
    keyframes = [frame for frame, ms in frames if ms >= 1000]
    sample = Image.new("RGB", (WIDTH, (HEIGHT + BAR) * len(keyframes)))
    for i, frame in enumerate(keyframes):
        sample.paste(frame, (0, i * (HEIGHT + BAR)))
    palette = sample.quantize(colors=256, method=Image.Quantize.MEDIANCUT)
    images = [frame.quantize(palette=palette, dither=Image.Dither.NONE) for frame, _ in frames]
    images[0].save(OUT, save_all=True, append_images=images[1:],
                   duration=[ms for _, ms in frames], loop=0)


def main() -> None:
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        try:
            record(browser.new_page(viewport={"width": WIDTH, "height": HEIGHT}))
        finally:
            browser.close()
    save_gif()
    seconds = sum(ms for _, ms in frames) / 1000
    print(f"Wrote {OUT} ({len(frames)} frames, {seconds:.0f}s, {OUT.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
