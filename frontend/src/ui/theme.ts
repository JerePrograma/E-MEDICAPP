import { createTheme } from '@mui/material';

export const theme = createTheme({
  palette: {
    primary: { main: '#145CDB', dark: '#104BB5', light: '#EEF5FA' },
    secondary: { main: '#496479' },
    background: { default: '#EEF5FA', paper: '#FFFFFF' },
    text: { primary: '#0B1F33', secondary: '#496479' },
    divider: '#D7E3ED',
    success: { main: '#257A57' },
    warning: { main: '#976216' },
    error: { main: '#AD4141' },
  },
  typography: {
    fontFamily: '"Segoe UI", system-ui, sans-serif',
    h4: { fontWeight: 720, fontSize: '1.8rem', letterSpacing: '-0.035em', lineHeight: 1.2 },
    h5: { fontWeight: 700, fontSize: '1.35rem', letterSpacing: '-0.025em' },
    h6: { fontWeight: 680, fontSize: '1.08rem', letterSpacing: '-0.015em' },
    subtitle1: { fontWeight: 650 },
    body1: { lineHeight: 1.65 },
    body2: { lineHeight: 1.6 },
    overline: { fontSize: '0.65rem', letterSpacing: '0.12em', fontWeight: 750 },
    button: { textTransform: 'none', fontWeight: 650, letterSpacing: 0 },
  },
  shape: { borderRadius: 14 },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        html: { scrollBehavior: 'smooth' },
        body: { minWidth: 320 },
        'button, a, input, select, textarea': { WebkitTapHighlightColor: 'transparent' },
        ':focus-visible': { outline: '3px solid #145CDB', outlineOffset: 3 },
        '@media (prefers-reduced-motion: reduce)': { html: { scrollBehavior: 'auto' } },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: {
          borderRadius: 10,
          minHeight: 40,
          paddingLeft: 16,
          paddingRight: 16,
          '@media (max-width:599.95px)': { minHeight: 44 },
        },
        contained: { paddingTop: 10, paddingBottom: 10 },
        outlined: { borderColor: '#D7E3ED' },
      },
    },
    MuiPaper: { defaultProps: { elevation: 0 } },
    MuiCard: {
      styleOverrides: {
        root: { border: '1px solid #D7E3ED', boxShadow: '0 2px 7px rgba(11,31,51,0.025)' },
      },
    },
    MuiCardContent: {
      styleOverrides: {
        root: {
          padding: 24,
          '&:last-child': { paddingBottom: 24 },
          '@media (max-width:600px)': { padding: 18, '&:last-child': { paddingBottom: 18 } },
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: { fontWeight: 620, borderRadius: 7 },
        sizeSmall: { fontSize: '0.7rem' },
      },
    },
    MuiAlert: {
      styleOverrides: {
        root: { borderRadius: 10, alignItems: 'flex-start' },
        message: { minWidth: 0 },
      },
    },
    MuiTextField: { defaultProps: { fullWidth: true } },
    MuiOutlinedInput: {
      styleOverrides: {
        root: { borderRadius: 10, backgroundColor: '#FFFFFF', minWidth: 0 },
        input: { '@media (max-width:599.95px)': { fontSize: 16 } },
      },
    },
    MuiDialog: {
      styleOverrides: {
        paper: {
          borderRadius: 18,
          backgroundImage: 'none',
          '@media (max-width:599.95px)': {
            margin: 8,
            width: 'calc(100% - 16px)',
            maxHeight: 'calc(100% - 16px)',
          },
          '&.MuiDialog-paperFullScreen': {
            margin: 0,
            width: '100%',
            maxWidth: '100%',
            height: '100%',
            maxHeight: '100%',
            borderRadius: 0,
          },
        },
      },
    },
    MuiDialogTitle: {
      styleOverrides: {
        root: {
          padding: '24px 24px 18px',
          fontSize: '1.2rem',
          fontWeight: 700,
          '@media (max-width:599.95px)': { padding: '16px', fontSize: '1.1rem' },
        },
      },
    },
    MuiDialogContent: {
      styleOverrides: {
        root: {
          '@media (max-width:599.95px)': { paddingLeft: 16, paddingRight: 16, paddingBottom: 16 },
        },
      },
    },
    MuiDialogActions: {
      styleOverrides: {
        root: {
          padding: '16px 24px 20px',
          borderTop: '1px solid #D7E3ED',
          gap: 8,
          '@media (max-width:599.95px)': { padding: '12px 16px', flexWrap: 'wrap' },
        },
      },
    },
    MuiStepLabel: {
      styleOverrides: {
        label: {
          fontSize: '0.75rem',
          '@media (max-width:600px)': { fontSize: '0.62rem', lineHeight: 1.4 },
        },
      },
    },
    MuiTab: { styleOverrides: { root: { textTransform: 'none', minHeight: 48, fontWeight: 620 } } },
    MuiTableCell: {
      styleOverrides: {
        head: { fontWeight: 700, color: '#496479', background: '#EEF5FA', fontSize: '0.75rem' },
        root: { borderColor: '#D7E3ED' },
      },
    },
  },
});
