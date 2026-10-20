import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { App as NativeApp } from '@capacitor/app';
import { Capacitor } from '@capacitor/core';
import { clearNativeDocuments } from './documents';

export function NativeNavigation() {
  const location = useLocation();
  const navigate = useNavigate();
  useEffect(() => {
    void clearNativeDocuments();
  }, []);
  useEffect(() => {
    if (Capacitor.getPlatform() !== 'android') return;
    const listener = NativeApp.addListener('backButton', ({ canGoBack }) => {
      const dialog = document.querySelector('[role="dialog"]');
      if (dialog) {
        dialog.dispatchEvent(
          new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }),
        );
      } else if (canGoBack && !['/', '/app'].includes(location.pathname)) {
        navigate(-1);
      } else if (!['/', '/app'].includes(location.pathname)) {
        navigate('/app', { replace: true });
      } else {
        void NativeApp.exitApp();
      }
    });
    return () => {
      void listener.then((handle) => handle.remove());
    };
  }, [location.pathname, navigate]);
  return null;
}
