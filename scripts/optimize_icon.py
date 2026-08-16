from pathlib import Path
from PIL import Image

source = Path('/home/ubuntu/webdev-static-assets/spatial-safety-ar-icon.png')
target = Path('/home/ubuntu/spatial-safety-ar/assets/images/icon-lite.png')
target.parent.mkdir(parents=True, exist_ok=True)

with Image.open(source).convert('RGB') as image:
    image.thumbnail((512, 512), Image.Resampling.LANCZOS)
    image.save(target, format='PNG', optimize=True, compress_level=9)
print(target)
