import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import { SessionProvider } from './session';
import './styles.css';

createRoot(document.getElementById('root')).render(
  <StrictMode>
    {/* Synchronous navigation keeps the data-app-state signal accurate (see appState.js). */}
    <BrowserRouter future={{ v7_startTransition: false, v7_relativeSplatPath: true }}>
      <SessionProvider>
        <App />
      </SessionProvider>
    </BrowserRouter>
  </StrictMode>,
);
