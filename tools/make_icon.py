"""Draws the launcher icon foreground: "Sweet Spot" between rounded brackets, the o of Spot being
a red record dot. Writes PNGs for every density into app/src/main/res.

    python3 tools/make_icon.py
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
FONT = os.path.join(ROOT, "app/src/main/assets/fonts/rajdhani_bold.ttf")
SIZE = 432  # 108 dp at xxxhdpi; the safe zone is the central 66 dp circle
RED = (255, 59, 48, 255)
DARK_RED = (122, 15, 10, 255)
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def draw(monochrome: bool) -> Image.Image:
    white = (255, 255, 255, 255)
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, 64)
    c = SIZE / 2

    # The o of the font gives the dot's size and place: on the baseline, x-height tall.
    o_left, o_top, o_right, o_bottom = d.textbbox((0, 0), "o", font=font, anchor="ls")
    o_w, o_h = o_right - o_left, o_bottom - o_top

    # Two lines, "Sweet" and "Sp" + dot + "t", centred as a block on their baselines.
    line_gap = 56
    cap_top = d.textbbox((0, 0), "S", font=font, anchor="ls")[1]
    p_bottom = d.textbbox((0, 0), "p", font=font, anchor="ls")[3]
    # Centre the block from the top of the capitals to the bottom of the p.
    base1 = c - (line_gap + p_bottom + cap_top) / 2
    base2 = base1 + line_gap
    d.text((c, base1), "Sweet", font=font, fill=white, anchor="ms")
    sp_w = d.textlength("Sp", font=font)
    t_w = d.textlength("t", font=font)
    o_adv = d.textlength("o", font=font)
    x = c - (sp_w + o_adv + t_w) / 2
    d.text((x, base2), "Sp", font=font, fill=white, anchor="ls")
    dot_x = x + sp_w + (o_adv - o_w) / 2 + o_left
    box = [dot_x, base2 - o_h, dot_x + o_w, base2]
    if monochrome:
        d.ellipse(box, fill=white)
    else:
        d.ellipse([box[0], box[1] + 2, box[2], box[3] + 2], fill=DARK_RED)
        d.ellipse(box, fill=RED)
        r = o_w * 0.12
        hx, hy = box[0] + o_w * 0.33, box[1] + o_h * 0.3
        # Highlight: white blended into the red (drawing semi-transparent would replace, not blend).
        d.ellipse([hx - r, hy - r, hx + r, hy + r], fill=(255, 150, 144, 255))
    d.text((x + sp_w + o_adv, base2), "t", font=font, fill=white, anchor="ls")

    # Rounded brackets, like a camera's REC frame: a rounded box with the middle of the top
    # and bottom edges cut away. Corners stay inside the launcher's round mask.
    half_w, half_h, stroke, radius = 108, 86, 14, 32
    frame = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    f = ImageDraw.Draw(frame)
    f.rounded_rectangle([c - half_w, c - half_h, c + half_w, c + half_h], radius=radius, outline=white, width=stroke)
    cut = half_w - radius - 14
    f.rectangle([c - cut, 0, c + cut, SIZE], fill=(0, 0, 0, 0))
    img.alpha_composite(frame)
    return img


def main():
    for name, mono in (("ic_launcher_foreground", False), ("ic_launcher_monochrome", True)):
        big = draw(mono)
        for density, px in DENSITIES.items():
            out = os.path.join(RES, f"drawable-{density}")
            os.makedirs(out, exist_ok=True)
            big.resize((px, px), Image.LANCZOS).save(os.path.join(out, f"{name}.png"))


if __name__ == "__main__":
    main()
