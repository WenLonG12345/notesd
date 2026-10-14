import { request } from './client'
import type { EmailSettings } from './types'

export const settings = {
  email: () => request<EmailSettings>('GET', '/settings/email'),
  /** Omit apiKey to keep the saved one. */
  saveEmail: (from: string, apiKey?: string) =>
    request<EmailSettings>('PUT', '/settings/email', { from, ...(apiKey && { apiKey }) }),
  removeEmail: () => request<undefined>('DELETE', '/settings/email'),
}
