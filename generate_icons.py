#!/usr/bin/env python3
"""Generate adaptive launcher icons from SVG files for Android project."""

import os
import sys

import cairosvg

# Project paths
PROJECT_ROOT = "/home/tigran/gusketch/GUPet"
ICONS_SRC_DIR = os.path.join(PROJECT_ROOT, "Иконки")
APP_RES_DIR = os.path.join(PROJECT_ROOT, "app/src/main/res")

# Target mipmap directories and sizes (size x size in pixels)
MIPMAP_CONFIG = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Output filenames
PNG_FILE = "ic_launcher.png"
ROUND_PNG_FILE = "ic_launcher_round.png"


def create_mipmap_dirs(res_dir):
    """Create all required mipmap directories."""
    dirs_created = []
    for size_name in MIPMAP_CONFIG:
        dir_path = os.path.join(res_dir, size_name)
        if not os.path.exists(dir_path):
            os.makedirs(dir_path, exist_ok=True)
            dirs_created.append(size_name)
    return dirs_created


def generate_png(svg_src_path, dest_path, width, height):
    """Generate a PNG from SVG with white background."""
    # cairosvg can convert SVG to PNG with specified dimensions
    # We'll add a white background by compositing
    tmp_png = dest_path + ".tmp.png"
    cairosvg.svg2png(
        file_obj=open(svg_src_path, "rb"),
        write_to=tmp_png,
        output_width=width,
        output_height=height,
    )
    # Now add white background - composite the PNG over a white square
    from PIL import Image

    # Open the generated PNG (may have transparency)
    img = Image.open(tmp_png).convert("RGBA")
    new_img = Image.new("RGBA", (width, height), (255, 255, 255, 255))

    # Calculate position to center the original image
    x = (width - img.width) // 2
    y = (height - img.height) // 2

    # Paste the original onto white background
    new_img.paste(img, (x, y), img)  # img as mask to use alpha channel

    # Save final image
    new_img.save(dest_path, "PNG")
    # Clean up temp file
    if os.path.exists(tmp_png):
        os.remove(tmp_png)


def generate_round_mask_png(svg_src_path, dest_path, width, height):
    """Generate round PNG with circular mask."""
    from PIL import Image, ImageDraw

    # First generate regular PNG with white background
    tmp_png = dest_path + ".tmp.png"
    cairosvg.svg2png(
        file_obj=open(svg_src_path, "rb"),
        write_to=tmp_png,
        output_width=width,
        output_height=height,
    )

    # Open and convert to RGBA
    img = Image.open(tmp_png).convert("RGBA")
    # Remove temp file
    if os.path.exists(tmp_png):
        os.remove(tmp_png)

    # Create circular mask
    mask = Image.new("L", (width, height), 0)
    draw = ImageDraw.Draw(mask)
    # Draw filled circle
    draw.ellipse((0, 0, width, height), fill=255)

    # Apply mask to image
    result = Image.new("RGBA", (width, height), (255, 255, 255, 255))
    result.paste(img, mask=mask)

    # Save
    result.save(dest_path, "PNG")


def main():
    print("=== GUPet Android Icon Generator ===")
    print()

    # Get SVG files
    light_svg = os.path.join(ICONS_SRC_DIR, "for_light_theme.svg")
    dark_svg = os.path.join(ICONS_SRC_DIR, "for_dark_theme.svg")

    if not os.path.exists(light_svg):
        print(f"ERROR: Light SVG not found: {light_svg}")
        sys.exit(1)
    if not os.path.exists(dark_svg):
        print(f"ERROR: Dark SVG not found: {dark_svg}")
        sys.exit(1)

    # Create mipmap directories
    print("Creating mipmap directories...")
    created = create_mipmap_dirs(APP_RES_DIR)
    if created:
        print(f"  Created directories: {', '.join(created)}")
    else:
        print("  Directories already exist.")

    # Generate icons for each size
    print()
    print("Generating icons...")
    for size_name, size_px in MIPMAP_CONFIG.items():
        dir_path = os.path.join(APP_RES_DIR, size_name)

        # Generate regular PNG (from light SVG - white G on white bg)
        light_png = os.path.join(dir_path, PNG_FILE)
        generate_png(light_svg, light_png, size_px, size_px)

        # Generate round PNG (from dark SVG with circular mask)
        round_png = os.path.join(dir_path, ROUND_PNG_FILE)
        generate_round_mask_png(dark_svg, round_png, size_px, size_px)

        print(f"  {size_name}: {size_px}x{size_px} - generated")

    print()
    print("=== Verification ===")
    # Verify files exist and are not empty
    all_ok = True
    for size_name, size_px in MIPMAP_CONFIG.items():
        dir_path = os.path.join(APP_RES_DIR, size_name)
        png_path = os.path.join(dir_path, PNG_FILE)
        round_png_path = os.path.join(dir_path, ROUND_PNG_FILE)

        if os.path.exists(png_path) and os.path.getsize(png_path) > 0:
            png_size = os.path.getsize(png_path)
            print(f"  {size_name}/{PNG_FILE}: EXISTS ({png_size} bytes)")
        else:
            print(f"  {size_name}/{PNG_FILE}: MISSING or EMPTY!")
            all_ok = False

        if os.path.exists(round_png_path) and os.path.getsize(round_png_path) > 0:
            round_size = os.path.getsize(round_png_path)
            print(f"  {size_name}/{ROUND_PNG_FILE}: EXISTS ({round_size} bytes)")
        else:
            print(f"  {size_name}/{ROUND_PNG_FILE}: MISSING or EMPTY!")
            all_ok = False

    if all_ok:
        print()
        print("✅ All icons generated and verified successfully!")
    else:
        print()
        print("⚠️ Some files are missing or empty. Check the output above.")
        sys.exit(1)


if __name__ == "__main__":
    main()