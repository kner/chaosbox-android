"""Queue recovery tests that run without a graphical display."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import queue
import io
import sys
import unittest
from unittest.mock import Mock, patch
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import App, copy_image_clipboard


class ClipboardTests(unittest.TestCase):
    def test_copy_publishes_png_pixels(self):
        image = Image.new("RGB", (17, 11), "teal")
        with patch("app.shutil.which", return_value="/usr/bin/xclip"), patch("app.subprocess.run") as run:
            copy_image_clipboard(image)
        self.assertIn("image/png", run.call_args.args[0])
        pasted = Image.open(io.BytesIO(run.call_args.kwargs["input"]))
        self.assertEqual(pasted.size, image.size)
        self.assertEqual(pasted.tobytes(), image.tobytes())

    def test_missing_clipboard_helper_is_reported(self):
        with patch("app.shutil.which", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "sudo apt install xclip"):
                copy_image_clipboard(Image.new("RGB", (1, 1)))


class QueueRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.app = App.__new__(App)
        self.app.root = Mock()
        self.app.status = Mock()
        self.app.progress = Mock()
        self.app.update_controls = Mock()
        self.app.events = queue.Queue()
        self.app.busy = True
        self.app.closing = False
        self.app.uploading = False
        self.app.upload_text = None
        self.app.executor = ThreadPoolExecutor(max_workers=1)
        self.addCleanup(self.app.executor.shutdown)

    def test_callback_failure_does_not_strand_next_file_operation(self):
        app = self.app
        finished = Mock()

        def broken_callback(result):
            app.task(lambda: "second file", finished)
            raise RuntimeError("viewer could not be opened")

        app.events.put(("done", None, broken_callback))
        app.drain()
        # Wait for the worker, then simulate the next scheduled Tk poll.
        app.executor.submit(lambda: None).result(timeout=5)
        app.drain()
        finished.assert_called_once_with("second file")
        self.assertFalse(app.busy)
        app.root.report_callback_exception.assert_called_once()
        self.assertEqual(app.root.after.call_count, 2)
        app.close()
        app.root.destroy.assert_called_once()

    def test_failed_error_handler_keeps_polling(self):
        handler = Mock(side_effect=RuntimeError("dialog closed"))
        self.app.events.put(("error", ValueError("bad image"), handler))
        self.app.drain()
        self.assertFalse(self.app.busy)
        self.app.root.after.assert_called_once_with(80, self.app.drain)

    def test_progress_error_does_not_hide_completion(self):
        self.app.status.set.side_effect = [RuntimeError("status error"), None]
        done = Mock()
        self.app.events.put(("progress", "loading"))
        self.app.events.put(("done", None, done))
        self.app.drain()
        done.assert_called_once_with(None)
        self.assertFalse(self.app.busy)

    def test_existing_viewer_is_reused(self):
        self.app.fullscreen_window = Mock()
        self.app.fullscreen_window.winfo_exists.return_value = True
        self.app.fullscreen()
        self.app.fullscreen_window.lift.assert_called_once()
        self.assertTrue(self.app.events.empty())

    def test_closing_during_write_is_still_blocked(self):
        self.app.close()
        self.app.root.destroy.assert_not_called()
        self.assertFalse(self.app.closing)


if __name__ == "__main__":
    unittest.main()
