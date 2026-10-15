// Image references in Markdown (architecture §7.6):
//   ![alt](notesd:image/<sha256 hex>?w=<px>&h=<px>)
// Android parses the same form in notes.core.images.ImageRef.

export type ImageRef = { id: string; w: number; h: number }

const REF = /^notesd:image\/([0-9a-f]{64})\?w=([1-9]\d{0,5})&h=([1-9]\d{0,5})$/

/** The image an app added, from a Markdown image's destination; null for any other image. */
export function parseRef(src: string): ImageRef | null {
  const m = REF.exec(src)
  if (!m?.[1] || !m[2] || !m[3]) return null
  return { id: m[1], w: Number(m[2]), h: Number(m[3]) }
}

export function refURL({ id, w, h }: ImageRef): string {
  return `notesd:image/${id}?w=${w}&h=${h}`
}

/** The Markdown for an image; alt text is escaped so it stays one plain run. */
export function imageMarkdown(ref: ImageRef, alt = ''): string {
  return `![${alt.replace(/[\\[\]*_`~<>!#&]/g, '\\$&').replace(/\s+/g, ' ')}](${refURL(ref)})`
}

export async function sha256Hex(bytes: BufferSource): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))
  return Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join('')
}
