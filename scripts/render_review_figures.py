"""Offline SVG rasterization; no model, network, or research authoring."""
import json
import pathlib
import sys
import cairosvg
from PIL import Image

folder = pathlib.Path(sys.argv[1]).resolve()
manifest = json.loads((folder / 'figures.json').read_text(encoding='utf-8'))
for figure in manifest:
    source = (folder / figure['file']).resolve()
    if source.parent != folder or source.suffix != '.svg':
        raise ValueError('Invalid figure path')
    # MuPDF's SVG reader omits markers/dashes and inherited text-anchor,
    # which changes scientific diagrams. CairoSVG preserves these features.
    cairosvg.svg2png(url=str(source), write_to=str(source.with_suffix('.png')),
                    scale=1.25, background_color='white')
    with Image.open(source.with_suffix('.png')) as image:
        if not (750 <= image.width <= 3000 and 375 <= image.height <= 2500):
            raise ValueError('Figure dimensions outside review bounds')
    if source.with_suffix('.png').stat().st_size > 4_000_000:
        raise ValueError('Rendered image exceeds audit input limit')
print(json.dumps({'rendered': len(manifest)}))
