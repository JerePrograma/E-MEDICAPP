import { useState } from 'react';
import { Alert, Box, Button, Chip, Typography } from '@mui/material';
import { api } from '../api';

export function DocumentInput({
  label,
  kind = 'EVIDENCIA',
  branchId,
  value,
  onChange,
}: {
  label: string;
  kind?: string;
  branchId?: string;
  value?: string;
  onChange: (id: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  return (
    <Box>
      <Typography variant="body2" sx={{ mb: 1 }}>
        {label}
      </Typography>
      <Button component="label" variant="outlined" disabled={busy}>
        {busy ? 'Guardando…' : value ? 'Reemplazar archivo' : 'Adjuntar archivo'}
        <input
          hidden
          type="file"
          aria-label={label}
          accept="application/pdf,image/png,image/jpeg"
          onChange={async (event) => {
            const input = event.currentTarget;
            const file = input.files?.[0];
            if (!file) return;
            setBusy(true);
            setError('');
            try {
              const body = new FormData();
              body.append('file', file);
              body.append('kind', kind);
              if (branchId) body.append('branchId', branchId);
              const result = await api<{ id: string }>('/api/documents', { method: 'POST', body });
              onChange(result.id);
            } catch (e) {
              setError(e instanceof Error ? e.message : 'No se pudo adjuntar.');
            } finally {
              setBusy(false);
              input.value = '';
            }
          }}
        />
      </Button>
      {value && <Chip size="small" color="success" label="Archivo guardado" sx={{ ml: 1 }} />}
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
        PDF, PNG o JPEG. Acceso privado.
      </Typography>
      {error && (
        <Alert severity="error" sx={{ mt: 1 }}>
          {error}
        </Alert>
      )}
    </Box>
  );
}
