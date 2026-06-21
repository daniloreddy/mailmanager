from contextlib import contextmanager
from datetime import datetime
from typing import Generator

from nicegui import ui

VERSION = "1.0.0"

NAV_ITEMS = [
    {"label": "Status", "icon": "monitor", "path": "/"},
    {"label": "IMAP", "icon": "email", "path": "/imap"},
    {"label": "Rules", "icon": "rule", "path": "/rules"},
    {"label": "Settings", "icon": "settings", "path": "/settings"},
]

_CSS = """
body.body--dark {
  --bg: #121212; --surface: #1E1E1E; --border: #2A2A2A;
  --primary: #90CAF9; --accent: #80CBC4;
  --text: #E0E0E0; --muted: #616161;
}
body.body--light {
  --bg: #F5F5F5; --surface: #FFFFFF; --border: #E0E0E0;
  --primary: #1565C0; --accent: #00695C;
  --text: #212121; --muted: #9E9E9E;
}
body { background: var(--bg) !important; }

body.body--dark .q-header  { background: #1E1E1E !important; border-bottom: 1px solid #2A2A2A !important; }
body.body--dark .q-footer  { background: #161616 !important; border-top:    1px solid #2A2A2A !important; }
body.body--dark .q-drawer  { background: #161616 !important; border-right:  1px solid #2A2A2A !important; }
body.body--light .q-header { background: #FFFFFF !important; border-bottom: 1px solid #E0E0E0 !important; }
body.body--light .q-footer { background: #F5F5F5 !important; border-top:    1px solid #E0E0E0 !important; }
body.body--light .q-drawer { background: #FFFFFF !important; border-right:  1px solid #E0E0E0 !important; }

.mm-nav-item {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 16px; text-decoration: none; cursor: pointer;
  color: var(--text); transition: background .15s;
}
.mm-nav-item:hover { background: rgba(144,202,249,.08); }
body.body--dark  .mm-nav-item.active { background: #1A2F45; color: #90CAF9 !important; }
body.body--light .mm-nav-item.active { background: #E3F2FD; color: #1565C0 !important; }
.mm-nav-item.active .mm-nav-icon  { color: inherit !important; }
.mm-nav-item .mm-nav-icon { color: var(--muted); font-size: 20px; }
.mm-nav-label { font-size: 14px; }
.mm-version   { color: var(--muted); font-size: 11px; padding: 8px 16px; }
.mm-footer-text { color: #424242; font-size: 0.75rem; }

@media (max-width: 768px) {
  .q-drawer--left { display: none !important; }
  .q-page-container { padding-left: 0 !important; padding-bottom: 64px !important; }
  #mm-bottom-nav { display: flex !important; }
}
@media (min-width: 769px) {
  #mm-bottom-nav { display: none !important; }
}
"""


@contextmanager
def base_layout(current_path: str = "/") -> Generator[None, None, None]:
    dark = ui.dark_mode(value=True)
    ui.add_head_html(f"<style>{_CSS}</style>")

    with ui.header(elevated=False).style("min-height:48px; padding:0 1rem;").classes("items-center"):
        ui.label("MailManager").classes("text-h6").style("color:#90CAF9;")
        ui.space()

        def _toggle() -> None:
            dark.toggle()
            _btn.props(f"icon={'light_mode' if dark.value else 'dark_mode'}")

        _btn = (
            ui.button(icon="dark_mode", on_click=_toggle)
            .props("flat round dense")
            .style("color:var(--muted);")
        )

    with ui.left_drawer(fixed=True, bordered=False).style("width:180px; padding-top:8px;"):
        for item in NAV_ITEMS:
            active = item["path"] == current_path
            cls = "mm-nav-item active" if active else "mm-nav-item"
            with ui.element("a").props(f'href="{item["path"]}"').classes(cls):
                ui.icon(item["icon"]).classes("mm-nav-icon")
                ui.label(item["label"]).classes("mm-nav-label")
        ui.space()
        ui.label(f"v{VERSION}").classes("mm-version")

    # Bottom navigation (mobile)
    with (
        ui.element("div")
        .props('id="mm-bottom-nav"')
        .style(
            "display:none; position:fixed; bottom:0; left:0; right:0;"
            " height:56px; background:#1E1E1E; border-top:1px solid #2A2A2A;"
            " z-index:200; justify-content:space-around; align-items:center;"
        )
    ):
        for item in NAV_ITEMS:
            active = item["path"] == current_path
            color = "color:#90CAF9;" if active else "color:#616161;"
            with (
                ui.element("a")
                .props(f'href="{item["path"]}"')
                .style(
                    f"display:flex; flex-direction:column; align-items:center;"
                    f" gap:2px; padding:4px 12px; text-decoration:none; {color}"
                )
            ):
                ui.icon(item["icon"]).style(f"{color} font-size:22px;")
                ui.label(item["label"]).style(f"{color} font-size:10px;")

    with ui.footer(fixed=True).style("min-height:36px; padding:0 1rem;").classes("items-center"):
        ui.label(f"MailManager · v{VERSION}").classes("mm-footer-text")
        ui.space()
        ui.label(f"Updated: {datetime.now().strftime('%m/%d/%Y %H:%M')}").classes("mm-footer-text")

    yield
