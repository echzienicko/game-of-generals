import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { installTheme } from './state/theme'
import './styles.css'

// before any render, so the first paint is already the right theme
installTheme()

const container = document.getElementById('root')
if (!container) {
  throw new Error('root element is missing from index.html')
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
