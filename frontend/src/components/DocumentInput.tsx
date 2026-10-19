import { useState } from 'react';
import { Alert, Box, Button, Chip, Typography } from '@mui/material';
import { api } from '../api';

export function DocumentInput({
  label,
  kind = 'EVIDENCIA',
  branchId,
  value,
  onChange,
  disabled = false,
  onBusyChange,
}: {
  label: string;
  kind?: string;
  branchId?: string;
  value?: string;
  onChange: (id: string) => void;
  disabled?: boolean;
  onBusyChange?: (busy: boolean) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [fileName, setFileName] = useState('');
  return (
    <Box>
      <Typography variant="body2" sx={{ mb: 1 }}>
        {label}
      </Typography>
      <Button component="label" variant="outlined" disabled={busy || disabled}>
        {busy ? 'Guardando…' : value ? 'Reemplazar archivo' : 'Adjuntar archivo'}
        <input
          hidden
          type="file"
          disabled={busy || disabled}
          aria-label={label}
          accept="application/pdf,image/png,image/jpeg"
          onChange={async (event) => {
            const input = event.currentTarget;
            const file = input.files?.[0];
            if (!file) return;
            setBusy(true);
            onBusyChange?.(true);
            setError('');
            try {
              const body = new FormData();
              body.append('file', file);
              body.append('kind', kind);
              if (branchId) body.append('branchId', branchId);
              const result = await api<{ id: string }>('/api/documents', { method: 'POST', body });
              onChange(result.id);
              setFileName(file.name);
            } catch (e) {
              setError(e instanceof Error ? e.message : 'No se pudo adjuntar.');
            } finally {
              setBusy(false);
              onBusyChange?.(false);
              input.value = '';
            }
          }}
        />
      </Button>
      {value && (
        <Chip
          size="small"
          color="success"
          label="Archivo guardado"
          sx={{ ml: 1, mt: 0.5, maxWidth: '100%' }}
        />
      )}
      {value && fileName && (
        <Typography variant="caption" sx={{ display: 'block', mt: 1, overflowWrap: 'anywhere' }}>
          {fileName}
        </Typography>
      )}
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
