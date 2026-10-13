import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app'
import { applyTheme, readTheme } from './theme'
import './styles/fonts.css'
import './styles/tokens.css'
import './styles/base.css'

// Before the first render so a picked theme doesn't flash the OS one.
applyTheme(readTheme())

const root = document.getElementById('root')
if (!root) throw new Error('#root missing from index.html')

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
