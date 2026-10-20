from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import hashlib
import json

from PIL import Image
from pypdf import PdfReader
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader

root = Path(__file__).resolve().parents[2]
output_dir = root / 'outputs' / 'presentacion_20261019'
pages = sorted((root / '.artifact-build' / 'deck' / 'pdf-pages').glob('slide-*.png'))
assert len(pages) == 9
deck_path = output_dir / '03_Pitch_Deck_EMEDICAPP.pdf'
c = canvas.Canvas(str(deck_path), pagesize=(960, 540), pageCompression=1)
c.setTitle('Pitch Deck E-MEDICAPP')
c.setAuthor('E-MEDICAPP')
c.setSubject('Presentación del proyecto al 19 de octubre de 2026')
for image_path in pages:
    with Image.open(image_path) as im:
        assert im.size == (2560, 1440)
    c.drawImage(ImageReader(str(image_path)), 0, 0, width=960, height=540)
    c.showPage()
c.save()

expected = {
    '01_Plan_de_Negocio_EMEDICAPP.pdf': 6,
    '02_Datos_Financieros_EMEDICAPP.pdf': 2,
    '03_Pitch_Deck_EMEDICAPP.pdf': 9,
    '04_Resumen_Ejecutivo_EMEDICAPP.pdf': 2,
    '05_Anexo_Tecnico_y_Documental_EMEDICAPP.pdf': 4,
}
for name, count in expected.items():
    path = output_dir / name
    reader = PdfReader(path)
    assert not reader.is_encrypted
    assert len(reader.pages) == count
    assert path.stat().st_size < 10_000_000

zip_path = root / 'outputs' / 'EMEDICAPP_Documentos_PDF_20261019.zip'
with ZipFile(zip_path, 'w', compression=ZIP_DEFLATED, compresslevel=9) as archive:
    for name in expected:
        archive.write(output_dir / name, arcname=name)
with ZipFile(zip_path) as archive:
    assert archive.namelist() == list(expected)
    assert archive.testzip() is None
    for name in expected:
        assert hashlib.sha256(archive.read(name)).digest() == hashlib.sha256((output_dir / name).read_bytes()).digest()
print(json.dumps({'zip': str(zip_path), 'bytes': zip_path.stat().st_size, 'files': expected}, ensure_ascii=False, indent=2))
