/**
 * Approximates the server's list preview (plain text, ~160 chars) so the grid
 * can update in place after a save; the next refetch replaces it with the
 * server's exact text.
 */
export function previewOf(markdown: string): string {
  const text = markdown
    .split('\n')
    .map((line) =>
      line
        .replace(/^\s*(?:#{1,6}\s+|>\s?|[-*+]\s+(?:\[[ xX]\]\s+)?|\d+[.)]\s+)/, '')
        .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
        .replace(/(\*\*|__|\*|_|~~|`)/g, '')
        .replace(/\\$/, '')
        .trim(),
    )
    .filter(Boolean)
    .join(' ')
  return text.length > 160 ? `${text.slice(0, 159).trimEnd()}…` : text
}
