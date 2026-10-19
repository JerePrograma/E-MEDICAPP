import { useState } from 'react';
import { Alert, Button, type ButtonProps } from '@mui/material';
import { apiFetch, apiUrl } from '../api';

// A plain link cannot send the session header used from GitHub Pages.
export function DocumentButton({
  href,
  children,
  ...props
}: Omit<ButtonProps<'a'>, 'href' | 'component'> & {
  href: string;
  target?: string;
  rel?: string;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  return (
    <>
      <Button
        {...props}
        component="a"
        href={apiUrl(href)}
        disabled={props.disabled || busy}
        onClick={async (event) => {
          event.preventDefault();
          const tab = window.open('about:blank', '_blank');
          if (tab) tab.opener = null;
          setBusy(true);
          setError('');
          try {
            const response = await apiFetch(href, {
              headers: { Accept: 'application/pdf,image/*,application/octet-stream' },
            });
            if (!response.ok) {
              const problem = await response.json().catch(() => undefined);
              throw new Error(
                problem?.detail ??
                  problem?.message ??
                  'No se pudo abrir el documento. Verificá que tu sesión siga activa.',
              );
            }
            const blobUrl = URL.createObjectURL(await response.blob());
            if (tab) tab.location.replace(blobUrl);
            else {
              const link = document.createElement('a');
              link.href = blobUrl;
              link.download =
                response.headers.get('Content-Disposition')?.match(/filename="([^"]+)"/)?.[1] ??
                'documento';
              link.click();
            }
            window.setTimeout(() => URL.revokeObjectURL(blobUrl), 60_000);
          } catch (cause) {
            tab?.close();
            setError(cause instanceof Error ? cause.message : 'No se pudo abrir el documento.');
          } finally {
            setBusy(false);
          }
        }}
      >
        {busy ? 'Abriendo documento…' : children}
      </Button>
      {error && <Alert severity="error">{error}</Alert>}
    </>
  );
}
