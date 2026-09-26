"""Exercise the real Tk UI against temporary data; no SSH connections or user data."""
from pathlib import Path
import sys
import tempfile
import time
import tkinter as tk
from unittest.mock import patch

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import core
from app import App


def main():
    with tempfile.TemporaryDirectory(prefix="chaosbox-ui-test-") as temporary:
        home = Path(temporary)
        settings = core.Settings(home, home / ".config")
        settings.ensure()
        source = home / "source.jpg"
        Image.new("RGB", (320, 180), "teal").save(source)
        errors = []
        root = tk.Tk()
        root.withdraw()
        with patch("tkinter.messagebox.showerror", lambda *a, **k: errors.append(a)), \
             patch("tkinter.messagebox.showwarning", lambda *a, **k: errors.append(a)):
            app = App(root, settings)
            def settle():
                deadline = time.monotonic() + 30
                quiet = None
                while time.monotonic() < deadline:
                    root.update()
                    if not app.busy and app.events.empty():
                        quiet = quiet or time.monotonic()
                        if time.monotonic() - quiet > .35:
                            assert not errors, errors
                            return
                    else:
                        quiet = None
                    time.sleep(.01)
                raise AssertionError("UI operation timed out")
            settle()
            app.open_media([source])
            settle()
            app.fill(dict(zip(core.FIELDS, ["A11", "2", "Camera", "Test alias", "Desktop test", "Grüße", "P1"])))
            app.save()
            settle()
            saved = app.media[0]
            assert saved != source and saved.is_file()
            assert "Desktop test" in app.profile.categories
            assert core.read_metadata(saved)["comment"] == "Grüße"
            app.search_clicked()
            app.fill({"comment": "Camera"})
            app.run_search()
            settle()
            assert not app.search_mode and app.media == [saved]
            app.clear()
            app.repeat_search()
            settle()
            assert app.media == [saved]
            app.clear()
            app.fill(dict(zip(core.FIELDS, ["NewBox", "4", "New device", "", "Desktop test", "JSON", ""])))
            app.save()
            settle()
            assert app.box_path.name == "newbox.json"
            assert core.load_box(app.box_path)[0]["anzahl"] == 4
            app.variables["anzahl"].set("7")
            app.save()
            settle()
            assert len(core.load_box(app.box_path)) == 1
            assert core.load_box(app.box_path)[0]["anzahl"] == 7
            app.search_clicked()
            app.fill({"comment": "temporary search"})
            app.cancel_search()
            assert app.variables["anzahl"].get() == "7"
            # Full-screen window and canvas zoom work without altering the source.
            app.open_media([saved])
            settle()
            app.fullscreen()
            settle()
            dialogs = [child for child in root.winfo_children() if isinstance(child, tk.Toplevel)]
            assert len(dialogs) == 1
            viewer = next(child for child in dialogs[0].winfo_children() if isinstance(child, tk.Canvas))
            viewer.scale(2)
            root.update()
            assert viewer.zoom == 2
            viewer.reset()
            assert viewer.zoom == 1
            dialogs[0].destroy()
            app.profile_var.set("Bilderbox")
            app.select_profile()
            settle()
            assert app.profile.id == "Bilderbox" and not app.profile.labels[1]
            hidden_records = app.profile.data / "hidden.json"
            core.atomic_write(hidden_records, '[{"device":"first"},{"device":"second"}]')
            app.load_json(hidden_records)
            settle()
            chooser = next(child for child in root.winfo_children() if isinstance(child, tk.Toplevel))
            listing = next(child for child in chooser.winfo_children() if isinstance(child, tk.Listbox))
            listing.selection_clear(0, "end")
            listing.selection_set(1)
            next(child for child in chooser.winfo_children() if child.winfo_class() == "TButton").invoke()
            settle()
            assert app.record_index == 1 and app.variables["device"].get() == "second"
            app.close()
        print("PASS: Tk open/save, category addition, search/repeat, JSON editing, full-screen zoom, profiles")


if __name__ == "__main__":
    main()
