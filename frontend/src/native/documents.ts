import { Capacitor } from '@capacitor/core';
import { Directory, Filesystem } from '@capacitor/filesystem';
import { FileViewer } from '@capacitor/file-viewer';

const extensions: Record<string, string> = {
  'application/pdf': 'pdf',
  'image/png': 'png',
  'image/jpeg': 'jpg',
};
const documentPath = (extension: string) => `emedicapp-document.${extension}`;

export async function clearNativeDocuments() {
  if (!Capacitor.isNativePlatform()) return;
  await Promise.allSettled(
    Object.values(extensions).map((extension) =>
      Filesystem.deleteFile({ path: documentPath(extension), directory: Directory.Cache }),
    ),
  );
}

export async function openNativeDocument(blob: Blob) {
  const extension = extensions[blob.type.split(';')[0] ?? ''];
  if (!extension) throw new Error('El teléfono puede abrir documentos PDF, PNG o JPG.');
  const data = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('No se pudo preparar el documento.'));
    reader.onload = () => resolve(String(reader.result).split(',')[1] ?? '');
    reader.readAsDataURL(blob);
  });
  const file = await Filesystem.writeFile({
    path: documentPath(extension),
    data,
    directory: Directory.Cache,
  });
  await FileViewer.openDocumentFromLocalPath({ path: file.uri });
}
