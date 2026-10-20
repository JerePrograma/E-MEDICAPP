import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { HashRouter } from 'react-router-dom';
import { CssBaseline, ThemeProvider } from '@mui/material';
import { App } from './App';
import { theme } from './ui/theme';
import { IonApp, IonPage, setupIonicReact } from '@ionic/react';
import { NativeNavigation } from './native/NativeNavigation';
import '@ionic/react/css/core.css';
import '@ionic/react/css/normalize.css';
import '@ionic/react/css/structure.css';
import './ui/ionic.css';

setupIonicReact({ mode: 'md', hardwareBackButton: false });

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false, refetchOnWindowFocus: true },
    mutations: { retry: false },
  },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <IonApp>
          <HashRouter>
            <NativeNavigation />
            <IonPage>
              <App />
            </IonPage>
          </HashRouter>
        </IonApp>
      </ThemeProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
