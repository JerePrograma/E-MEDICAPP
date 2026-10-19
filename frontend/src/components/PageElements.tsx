import type { ReactNode } from 'react';
import { Box, Card, CardContent, Chip, Stack, Typography } from '@mui/material';
import { label } from '../api';

export function PageHeader({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow?: string;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <Stack
      direction={{ xs: 'column', sm: 'row' }}
      spacing={2}
      sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' } }}
    >
      <Box>
        <Typography variant="overline" color="primary">
          {eyebrow ?? 'Tu espacio de trabajo'}
        </Typography>
        <Typography component="h1" variant="h4" sx={{ mb: 0.8 }}>
          {title}
        </Typography>
        <Typography color="text.secondary" sx={{ maxWidth: 650 }}>
          {description}
        </Typography>
      </Box>
      {action && <Box sx={{ flexShrink: 0 }}>{action}</Box>}
    </Stack>
  );
}

export function StatusChip({ status, text }: { status: string; text?: string }) {
  const success = ['COMPLETED', 'DELIVERED', 'COLLECTED', 'PAID', 'VERIFIED', 'REFUNDED', 'ACTIVE'];
  const warning = [
    'INCIDENT',
    'COMPENSATING',
    'PARTIALLY_PAID',
    'REFUND_PENDING',
    'REQUESTED',
    'GRACE',
    'REVIEW_PENDING',
    'RETURN_REQUIRED',
    'RETURNED',
  ];
  const subdued = ['CANCELLED', 'RESOLVED', 'UNAVAILABLE', 'REJECTED', 'SUSPENDED'];
  const color = success.includes(status)
    ? 'success'
    : warning.includes(status)
      ? 'warning'
      : subdued.includes(status)
        ? 'default'
        : 'primary';
  return <Chip size="small" variant="outlined" color={color} label={text ?? label(status)} />;
}

export function MetricRow({
  metrics,
}: {
  metrics: { label: string; value: string | number; hint?: string }[];
}) {
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: {
          xs: 'repeat(2,minmax(0,1fr))',
          md: `repeat(${metrics.length},minmax(0,1fr))`,
        },
        gap: 1.5,
      }}
    >
      {metrics.map((metric) => (
        <Card key={metric.label}>
          <CardContent sx={{ py: '18px !important' }}>
            <Typography variant="body2" color="text.secondary">
              {metric.label}
            </Typography>
            <Typography variant="h5" sx={{ mt: 0.7 }}>
              {metric.value}
            </Typography>
            {metric.hint && (
              <Typography variant="caption" color="text.secondary">
                {metric.hint}
              </Typography>
            )}
          </CardContent>
        </Card>
      ))}
    </Box>
  );
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <Card>
      <CardContent
        sx={{ py: { xs: '32px !important', sm: '56px !important' }, textAlign: 'center' }}
      >
        <Box
          sx={{
            width: 48,
            height: 48,
            display: 'grid',
            placeItems: 'center',
            mx: 'auto',
            mb: 2,
            color: 'primary.main',
            bgcolor: 'primary.light',
            borderRadius: 3,
          }}
        >
          <Typography sx={{ fontSize: 24 }} aria-hidden>
            ＋
          </Typography>
        </Box>
        <Typography variant="h5">{title}</Typography>
        <Typography color="text.secondary" sx={{ mt: 1, maxWidth: 460, mx: 'auto' }}>
          {description}
        </Typography>
        {action && <Box sx={{ mt: 3 }}>{action}</Box>}
      </CardContent>
    </Card>
  );
}
