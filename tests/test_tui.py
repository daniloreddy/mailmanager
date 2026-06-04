import pytest
from mailmanager.tui import MailManagerApp


@pytest.mark.asyncio
async def test_tui_startup():
    app = MailManagerApp()
    async with app.run_test() as pilot:
        # Check if tabs are present
        assert app.query_one("TabbedContent") is not None
        # Quit
        await pilot.press("q")
