/**
 * Settings' categories, in the order docs/settings.md gives. The native apps' Sync & data and About
 * have nothing on the web, so they aren't here.
 */
export const SECTIONS = [
  { key: 'server', title: 'Server', group: 'account' },
  { key: 'security', title: 'Security', group: 'account' },
  { key: 'spaces', title: 'Spaces', group: 'account' },
  { key: 'integrations', title: 'Integrations', group: 'account' },
  { key: 'appearance', title: 'Appearance', group: 'device' },
] as const

export type SettingsSection = (typeof SECTIONS)[number]['key']

export function isSettingsSection(key: string): key is SettingsSection {
  return SECTIONS.some((s) => s.key === key)
}
