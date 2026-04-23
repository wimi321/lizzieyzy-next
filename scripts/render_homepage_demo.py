from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
TARGET = (1120, 714)
INTERFACE_OVERVIEW = ROOT / 'assets' / 'interface-overview.png'

COLOR_TEXT = '#171717'
COLOR_MUTED = '#6E6E73'
COLOR_PANEL = (255, 255, 255, 244)
COLOR_PANEL_STROKE = (236, 236, 240, 255)

FONT_ARIAL_BLACK = '/System/Library/Fonts/Supplemental/Arial Black.ttf'
FONT_ARIAL_BOLD = '/System/Library/Fonts/Supplemental/Arial Bold.ttf'
FONT_ARIAL = '/System/Library/Fonts/Supplemental/Arial.ttf'
FONT_CJK = '/System/Library/Fonts/Hiragino Sans GB.ttc'

ACCENTS = [
    (23, 60, 52, 255),
    (132, 86, 38, 255),
    (56, 92, 122, 255),
    (78, 139, 87, 255),
]

LOCALES = {
    'en': {
        'input': INTERFACE_OVERVIEW,
        'gif': ROOT / 'assets' / 'fox-id-demo.gif',
        'cover': ROOT / 'assets' / 'fox-id-demo-cover.png',
        'fonts': {
            'header_chip': (FONT_ARIAL_BOLD, 15),
            'header_title': (FONT_ARIAL_BLACK, 28),
            'header_sub': (FONT_ARIAL, 18),
            'step_chip': (FONT_ARIAL_BOLD, 16),
            'callout_title': (FONT_ARIAL_BLACK, 28),
            'callout_body': (FONT_ARIAL_BOLD, 20),
            'callout_small': (FONT_ARIAL, 17),
            'bar_num': (FONT_ARIAL_BLACK, 16),
            'bar_label': (FONT_ARIAL_BOLD, 15),
            'helper_title': (FONT_ARIAL_BLACK, 24),
            'helper_body': (FONT_ARIAL_BOLD, 18),
            'helper_chip': (FONT_ARIAL_BOLD, 16),
            'modal_title': (FONT_ARIAL_BLACK, 26),
            'modal_body': (FONT_ARIAL, 18),
            'modal_button': (FONT_ARIAL_BOLD, 18),
            'modal_input': (FONT_ARIAL_BOLD, 22),
        },
        'header_chip': 'Quick Demo',
        'header_title_lines': ['Enter a Fox nickname', 'and keep reviewing'],
        'header_sub': 'A short preview of the simplest path after download.',
        'step_prefix': 'Step',
        'step_of': 'of 4',
        'step_labels': ['Install', 'Fox', 'Nickname', 'Review'],
        'step2_badge': 'Open Fox Kifu',
        'step1_helper_title': 'Recommended first download',
        'step1_helper_lines': [
            'Start with the bundled package.',
            'Switch later only if you manage your own engine.',
        ],
        'step1_helper_chips': ['windows64.with-katago', 'mac-apple-silicon.dmg', 'mac-intel.dmg', 'linux64.with-katago'],
        'modal_chip': 'Fox nickname',
        'modal_title': 'Enter a Fox nickname',
        'modal_sub': 'Fetch recent public games automatically.',
        'modal_input_value': 'Enter nickname',
        'modal_input_fill': COLOR_MUTED,
        'modal_button': 'Fetch',
        'scenes': [
            {
                'title': 'Pick the right package',
                'lines': ['For most people, start with', 'the bundled release.'],
                'chips': [('Windows', False), ('mac-apple-silicon', False), ('mac-intel', False), ('Linux', False), ('with-katago', True)],
            },
            {
                'title': 'Open the Fox sync entry',
                'lines': ['Launch the app, then open', 'the Fox Kifu entry.'],
            },
            {
                'title': 'Type the Fox nickname',
                'lines': ['Use the nickname you already know.', 'The app resolves the account for you.'],
            },
            {
                'title': 'Start KataGo review',
                'lines': ['Keep going with winrate,', 'mistake review, and analysis.'],
            },
        ],
    },
    'cn': {
        'input': INTERFACE_OVERVIEW,
        'gif': ROOT / 'assets' / 'fox-id-demo-cn.gif',
        'cover': ROOT / 'assets' / 'fox-id-demo-cn-cover.png',
        'fonts': {
            'header_chip': (FONT_CJK, 15),
            'header_title': (FONT_CJK, 27),
            'header_sub': (FONT_CJK, 18),
            'step_chip': (FONT_CJK, 16),
            'callout_title': (FONT_CJK, 28),
            'callout_body': (FONT_CJK, 20),
            'callout_small': (FONT_CJK, 17),
            'bar_num': (FONT_ARIAL_BLACK, 16),
            'bar_label': (FONT_CJK, 15),
            'helper_title': (FONT_CJK, 24),
            'helper_body': (FONT_CJK, 18),
            'helper_chip': (FONT_CJK, 16),
            'modal_title': (FONT_CJK, 26),
            'modal_body': (FONT_CJK, 18),
            'modal_button': (FONT_CJK, 18),
            'modal_input': (FONT_CJK, 22),
        },
        'header_chip': '快速演示',
        'header_title_lines': ['输入野狐昵称', '继续抓谱和分析'],
        'header_sub': '下载后最常见、也最简单的使用路径。',
        'step_prefix': '第',
        'step_of': '步 / 共 4 步',
        'step_labels': ['安装包', '野狐棋谱', '昵称', '分析'],
        'step2_badge': '打开野狐棋谱',
        'step1_helper_title': '推荐先下载整合包',
        'step1_helper_lines': [
            '大多数用户先用内置 KataGo 的版本。',
            '后面再按需要换成自己的引擎。',
        ],
        'step1_helper_chips': ['windows64.with-katago', 'mac-apple-silicon.dmg', 'mac-intel.dmg', 'linux64.with-katago'],
        'modal_chip': '野狐昵称',
        'modal_title': '输入野狐昵称',
        'modal_sub': '自动匹配账号并获取最近公开棋谱',
        'modal_input_value': '请输入昵称',
        'modal_input_fill': COLOR_MUTED,
        'modal_button': '抓谱',
        'scenes': [
            {
                'title': '先选安装包',
                'lines': ['大多数用户先选', 'with-katago 就行'],
                'chips': [('Windows', False), ('mac-apple-silicon', False), ('mac-intel', False), ('Linux', False), ('with-katago', True)],
            },
            {
                'title': '打开野狐同步',
                'lines': ['启动程序后，从菜单进入', '野狐棋谱入口'],
            },
            {
                'title': '输入野狐昵称',
                'lines': ['输入你知道的昵称。', '程序会自动匹配账号。'],
            },
            {
                'title': '继续分析复盘',
                'lines': ['继续查看胜率、失误', '和 KataGo 分析。'],
            },
        ],
    },
}


def load_font(spec):
    path, size = spec
    return ImageFont.truetype(path, size=size)


def font(cfg, key):
    return load_font(cfg['fonts'][key])


def scale_rect(rect, sx, sy):
    x, y, w, h = rect
    return (int(x * sx), int(y * sy), int((x + w) * sx), int((y + h) * sy))


def rounded_overlay(size, xy, radius, fill, shadow=(0, 12, 22, (21, 21, 21, 35)), stroke=None):
    layer = Image.new('RGBA', size, (0, 0, 0, 0))
    sx, sy, blur, shadow_color = shadow
    if shadow_color[3] > 0:
        shadow_layer = Image.new('RGBA', size, (0, 0, 0, 0))
        sdraw = ImageDraw.Draw(shadow_layer)
        x0, y0, x1, y1 = xy
        sdraw.rounded_rectangle((x0 + sx, y0 + sy, x1 + sx, y1 + sy), radius=radius, fill=shadow_color)
        shadow_layer = shadow_layer.filter(ImageFilter.GaussianBlur(blur))
        layer = Image.alpha_composite(layer, shadow_layer)
    draw = ImageDraw.Draw(layer)
    draw.rounded_rectangle(xy, radius=radius, fill=fill, outline=stroke)
    return layer


def draw_text(draw, pos, text, font_obj, fill, anchor='la'):
    draw.text(pos, text, font=font_obj, fill=fill, anchor=anchor)


def draw_chip(draw, xy, text, bg, fg, font_obj):
    x0, y0, x1, y1 = xy
    draw.rounded_rectangle(xy, radius=(y1 - y0) // 2, fill=bg)
    draw_text(draw, ((x0 + x1) // 2, (y0 + y1) // 2 + 1), text, font_obj, fg, anchor='mm')


def highlight_overlay(size, rects, pulse=0.8):
    overlay = Image.new('RGBA', size, (18, 18, 18, 0))
    mask = Image.new('L', size, 136)
    mdraw = ImageDraw.Draw(mask)
    for rect in rects:
        mdraw.rounded_rectangle(rect, radius=24, fill=12)
    overlay.putalpha(mask)

    glow = Image.new('RGBA', size, (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(glow)
    for rect in rects:
        x0, y0, x1, y1 = rect
        for inset, width, alpha in ((0, 4, int(210 * pulse)), (8, 2, int(120 * pulse))):
            gdraw.rounded_rectangle(
                (x0 - inset, y0 - inset, x1 + inset, y1 + inset),
                radius=26,
                outline=(255, 246, 231, alpha),
                width=width,
            )
    glow = glow.filter(ImageFilter.GaussianBlur(1))
    return overlay, glow


def draw_header(canvas, cfg):
    header = rounded_overlay(
        canvas.size,
        (40, 28, 760, 196),
        30,
        COLOR_PANEL,
        shadow=(0, 14, 24, (17, 17, 17, 28)),
        stroke=COLOR_PANEL_STROKE,
    )
    canvas.alpha_composite(header)
    draw = ImageDraw.Draw(canvas)
    draw_chip(draw, (66, 50, 170, 82), cfg['header_chip'], ACCENTS[0], (250, 250, 252, 255), font(cfg, 'header_chip'))
    title_font = font(cfg, 'header_title')
    sub_font = font(cfg, 'header_sub')
    y = 118
    for line in cfg['header_title_lines']:
        draw_text(draw, (66, y), line, title_font, COLOR_TEXT)
        bbox = draw.textbbox((66, y), line, font=title_font)
        y = bbox[3] + 10
    draw_text(draw, (66, y + 8), cfg['header_sub'], sub_font, COLOR_MUTED)


def draw_step_bar(canvas, active_step, cfg):
    draw = ImageDraw.Draw(canvas)
    bar_rect = (188, 626, 932, 706)
    x0, y0, x1, y1 = bar_rect
    bar = rounded_overlay(
        canvas.size,
        bar_rect,
        28,
        (255, 255, 255, 232),
        shadow=(0, 10, 18, (17, 17, 17, 26)),
        stroke=COLOR_PANEL_STROKE,
    )
    canvas.alpha_composite(bar)
    inner_left = x0 + 60
    inner_right = x1 - 60
    circle_y = y0 + 44
    label_y = y0 + 66
    xs = [round(inner_left + idx * (inner_right - inner_left) / 3) for idx in range(4)]
    draw.line((xs[0], circle_y, xs[-1], circle_y), fill=(218, 205, 184, 200), width=6)
    for idx, (label, color, cx) in enumerate(zip(cfg['step_labels'], ACCENTS, xs), start=1):
        r = 28 if idx == active_step else 22
        fill = color if idx == active_step else (235, 229, 217, 255)
        draw.ellipse((cx - r, circle_y - r, cx + r, circle_y + r), fill=fill)
        num_fill = (255, 255, 255, 255) if idx == active_step else (90, 82, 69, 255)
        draw_text(draw, (cx, circle_y), f'{idx:02d}', font(cfg, 'bar_num'), num_fill, anchor='mm')
        label_fill = color if idx == active_step else COLOR_MUTED
        draw_text(draw, (cx, label_y), label, font(cfg, 'bar_label'), label_fill, anchor='mm')


def draw_callout(canvas, step_num, scene, cfg):
    accent = ACCENTS[step_num - 1]
    panel = rounded_overlay(
        canvas.size,
        (48, 204, 492, 446),
        32,
        COLOR_PANEL,
        shadow=(0, 18, 26, (17, 17, 17, 34)),
        stroke=COLOR_PANEL_STROKE,
    )
    canvas.alpha_composite(panel)
    draw = ImageDraw.Draw(canvas)

    if cfg['step_prefix'] == 'Step':
        chip_text = f"{cfg['step_prefix']} {step_num} {cfg['step_of']}"
    else:
        chip_text = f"{cfg['step_prefix']} {step_num} {cfg['step_of']}"
    draw_chip(draw, (74, 226, 188, 260), chip_text, accent, (255, 255, 255, 255), font(cfg, 'step_chip'))
    title_font = font(cfg, 'callout_title')
    body_font = font(cfg, 'callout_body')
    chip_font = font(cfg, 'callout_small')
    draw_text(draw, (74, 316), scene['title'], title_font, COLOR_TEXT)
    title_bbox = draw.textbbox((74, 316), scene['title'], font=title_font)
    y = title_bbox[3] + 20
    for line in scene['lines']:
        draw_text(draw, (74, y), line, body_font, COLOR_MUTED)
        line_bbox = draw.textbbox((74, y), line, font=body_font)
        y = line_bbox[3] + 12
    if scene.get('chips'):
        x = 74
        y += 12
        for text, selected in scene['chips']:
            width = int(draw.textlength(text, font=chip_font) + 34)
            if x + width > 456:
                x = 74
                y += 42
            bg = accent if selected else (241, 236, 228, 255)
            fg = (255, 255, 255, 255) if selected else COLOR_MUTED
            draw_chip(draw, (x, y, x + width, y + 30), text, bg, fg, chip_font)
            x += width + 10


def draw_step1_helper(canvas, cfg):
    helper = rounded_overlay(
        canvas.size,
        (620, 208, 1068, 430),
        30,
        COLOR_PANEL,
        shadow=(0, 18, 24, (17, 17, 17, 30)),
        stroke=COLOR_PANEL_STROKE,
    )
    canvas.alpha_composite(helper)
    draw = ImageDraw.Draw(canvas)
    draw_text(draw, (650, 250), cfg['step1_helper_title'], font(cfg, 'helper_title'), COLOR_TEXT)
    y = 292
    for line in cfg['step1_helper_lines']:
        draw_text(draw, (650, y), line, font(cfg, 'helper_body'), COLOR_MUTED)
        y += 30
    x = 650
    y = 358
    helper_chip_font = font(cfg, 'helper_chip')
    for idx, label in enumerate(cfg['step1_helper_chips']):
        width = int(draw.textlength(label, font=helper_chip_font) + 34)
        if x + width > 1024:
            x = 650
            y += 42
        selected = idx == 0
        draw_chip(
            draw,
            (x, y, x + width, y + 30),
            label,
            ACCENTS[0] if selected else (241, 236, 228, 255),
            (255, 255, 255, 255) if selected else COLOR_MUTED,
            helper_chip_font,
        )
        x += width + 10


def draw_step2_badge(canvas, rect, cfg):
    x0, y0, x1, y1 = rect
    badge = rounded_overlay(
        canvas.size,
        (x1 - 56, y1 + 14, x1 + 146, y1 + 60),
        23,
        COLOR_PANEL,
        shadow=(0, 10, 16, (17, 17, 17, 24)),
        stroke=COLOR_PANEL_STROKE,
    )
    canvas.alpha_composite(badge)
    draw = ImageDraw.Draw(canvas)
    draw_text(draw, (x1 + 45, y1 + 38), cfg['step2_badge'], font(cfg, 'step_chip'), ACCENTS[1], anchor='mm')


def draw_step3_modal(canvas, cfg):
    modal = rounded_overlay(
        canvas.size,
        (620, 208, 1062, 442),
        30,
        COLOR_PANEL,
        shadow=(0, 18, 24, (17, 17, 17, 32)),
        stroke=COLOR_PANEL_STROKE,
    )
    canvas.alpha_composite(modal)
    draw = ImageDraw.Draw(canvas)
    modal_chip_font = font(cfg, 'step_chip')
    modal_chip_width = int(draw.textlength(cfg['modal_chip'], font=modal_chip_font) + 36)
    draw_chip(
        draw,
        (648, 230, 648 + modal_chip_width, 262),
        cfg['modal_chip'],
        ACCENTS[2],
        (255, 255, 255, 255),
        modal_chip_font,
    )
    draw_text(draw, (648, 316), cfg['modal_title'], font(cfg, 'modal_title'), COLOR_TEXT)
    draw_text(draw, (648, 350), cfg['modal_sub'], font(cfg, 'modal_body'), COLOR_MUTED)
    draw.rounded_rectangle((648, 376, 948, 422), radius=20, fill=(251, 248, 242, 255), outline=(226, 211, 184, 255), width=2)
    draw_text(
        draw,
        (672, 399),
        cfg['modal_input_value'],
        font(cfg, 'modal_input'),
        cfg.get('modal_input_fill', COLOR_TEXT),
        anchor='lm',
    )
    draw.rounded_rectangle((962, 376, 1026, 422), radius=20, fill=ACCENTS[0])
    draw_text(draw, (994, 401), cfg['modal_button'], font(cfg, 'modal_button'), (255, 255, 255, 255), anchor='mm')


def prepare_base(cfg):
    base = Image.open(cfg['input']).convert('RGBA')
    sx = TARGET[0] / base.size[0]
    sy = TARGET[1] / base.size[1]
    resized = base.resize(TARGET, Image.LANCZOS)
    tint = Image.new('RGBA', TARGET, (248, 242, 228, 18))
    return Image.alpha_composite(resized, tint), sx, sy


def build_frame(base, scene_index, pulse, cfg, highlight_rects):
    canvas = base.copy()
    canvas = Image.alpha_composite(canvas, Image.new('RGBA', canvas.size, (255, 248, 238, 12)))

    if highlight_rects:
        overlay, glow = highlight_overlay(canvas.size, highlight_rects, pulse=pulse)
        canvas = Image.alpha_composite(canvas, overlay)
        canvas = Image.alpha_composite(canvas, glow)

    draw_header(canvas, cfg)
    draw_step_bar(canvas, scene_index + 1, cfg)
    draw_callout(canvas, scene_index + 1, cfg['scenes'][scene_index], cfg)

    if scene_index == 0:
        draw_step1_helper(canvas, cfg)
    elif scene_index == 1:
        draw_step2_badge(canvas, highlight_rects[0], cfg)
    elif scene_index == 2:
        draw_step3_modal(canvas, cfg)

    return canvas


def main():
    for cfg in LOCALES.values():
        base, sx, sy = prepare_base(cfg)
        sync_rect = scale_rect((18, 1318, 138, 48), sx, sy)
        board_rect = scale_rect((454, 108, 1144, 1180), sx, sy)
        chart_rect = scale_rect((0, 100, 446, 1212), sx, sy)
        list_rect = scale_rect((1625, 288, 500, 1048), sx, sy)
        modal_rect = scale_rect((640, 180, 398, 250), sx, sy)
        highlights = [[], [sync_rect], [modal_rect], [board_rect, chart_rect, list_rect]]

        cfg['gif'].parent.mkdir(parents=True, exist_ok=True)
        frames = []
        durations = []
        for idx in range(4):
            for pulse, duration in ((0.72, 820), (1.0, 1180)):
                frame = build_frame(base, idx, pulse, cfg, highlights[idx])
                frames.append(frame.convert('P', palette=Image.ADAPTIVE, colors=160, dither=Image.Dither.FLOYDSTEINBERG))
                durations.append(duration)

        cover = build_frame(base, 2, 1.0, cfg, highlights[2])
        cover.save(cfg['cover'])
        frames[0].save(
            cfg['gif'],
            save_all=True,
            append_images=frames[1:],
            duration=durations,
            loop=0,
            optimize=True,
            disposal=2,
        )
        print('Wrote', cfg['gif'])
        print('Wrote', cfg['cover'])


if __name__ == '__main__':
    main()
