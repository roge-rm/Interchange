"""Checks shadow mask limits and the exact sprite data used by snow and transparency."""
import tempfile
import unittest
from pathlib import Path

from PIL import Image
from art.sheet_encoding import pack_snow, save_sheet


class SheetEncodingTest(unittest.TestCase):
    def test_shadow_mask_preserves_zero_and_stays_within_two_levels(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "shadows_0.webp"
            img = Image.new("L", (256, 1))
            img.putdata(range(256))
            save_sheet(img, path)
            values = list(Image.open(path).convert("RGB").getdata())
            self.assertEqual(values[0], (0, 0, 0))
            self.assertEqual(values[-1], (255, 255, 255))
            for original, pixel in enumerate(values):
                self.assertEqual(pixel[0], pixel[1])
                self.assertEqual(pixel[1], pixel[2])
                self.assertLessEqual(abs(original - pixel[2]), 2)

    def test_sprites_keep_colour_bits_and_transparent_pixels_exact(self):
        with tempfile.TemporaryDirectory() as folder:
            pixels = [(v, 255 - v, v, v) for v in range(256)]
            img = Image.new("RGBA", (256, 1))
            img.putdata(pixels)
            for name in ("static_2", "signals_0", "vehicle_sample_2"):
                path = Path(folder) / f"{name}.webp"
                save_sheet(img, path)
                self.assertEqual(list(Image.open(path).convert("RGBA").getdata()), pixels)

    def test_scenery_keeps_snow_and_transparency_exact(self):
        with tempfile.TemporaryDirectory() as folder:
            pixels = [(v, 255 - v, v, v) for v in range(256)]
            img = Image.new("RGBA", (16, 16))
            img.putdata(pixels)
            for level in (0, 1):
                path = Path(folder) / f"static_{level}.webp"
                save_sheet(img, path)
                snow = (Path(folder) / f"static_snow_{level}.bin").read_bytes()
                self.assertEqual(len(snow), 64)
                self.assertEqual(snow, bytes([0xe4] * 64))
                for i, pixel in enumerate(pixels):
                    self.assertEqual((snow[i // 4] >> ((i % 4) * 2)) & 3, pixel[2] & 3)
                with Image.open(path) as decoded:
                    self.assertEqual(decoded.size, img.size)
                    self.assertEqual(decoded.convert("RGBA").getchannel("A").tobytes(),
                                     img.getchannel("A").tobytes())

    def test_snow_mask_pads_last_byte_without_changing_pixels(self):
        img = Image.new("RGBA", (5, 1))
        img.putdata([(0, 0, v, 255) for v in (3, 2, 1, 0, 3)])
        self.assertEqual(pack_snow(img), bytes([0x1b, 0x03]))

    def test_large_vehicle_sheets_keep_every_alpha_value(self):
        with tempfile.TemporaryDirectory() as folder:
            img = Image.new("RGBA", (64, 64))
            img.putdata([((i * 19) % 256, (i * 31) % 256, (i * 47) % 256, i % 256)
                         for i in range(64 * 64)])
            for level in (0, 1):
                path = Path(folder) / f"vehicle_sample_{level}.webp"
                save_sheet(img, path)
                with Image.open(path) as decoded:
                    self.assertEqual(decoded.size, img.size)
                    self.assertEqual(decoded.convert("RGBA").getchannel("A").tobytes(),
                                     img.getchannel("A").tobytes())

    def test_simple_vehicle_uses_smaller_lossless_encoding(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "vehicle_sample_0.webp"
            img = Image.new("RGBA", (64, 64), (31, 127, 93, 255))
            save_sheet(img, path)
            self.assertEqual(path.read_bytes()[12:16], b"VP8L")
            self.assertEqual(Image.open(path).convert("RGBA").tobytes(), img.tobytes())

    def test_ground_keeps_dimensions(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "ground_0.webp"
            save_sheet(Image.new("RGB", (32, 16), (82, 130, 61)), path)
            with Image.open(path) as img:
                self.assertEqual(img.size, (32, 16))


if __name__ == "__main__":
    unittest.main()
