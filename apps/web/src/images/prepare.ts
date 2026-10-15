// Downscaling before upload (architecture §7.6): the long side is capped,
// orientation applied, and the picture re-encoded, which also drops EXIF
// metadata such as location. GIFs go as they are, to keep animation.

export const MAX_SIDE = 2048
/** AES-GCM's nonce and tag, added when a locked note's image is encrypted. */
const SEAL_OVERHEAD = 12 + 16
/** The server's 10 MiB cap, less what encrypting adds, so a picture fits locked or not. */
export const MAX_BYTES = (10 << 20) - SEAL_OVERHEAD
const QUALITY = 0.85

export type Prepared = { bytes: Uint8Array<ArrayBuffer>; type: string; w: number; h: number }

/** A file that can't become a note image; the message is for the user. */
export class ImageError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ImageError'
  }
}

export async function prepareImage(file: Blob): Promise<Prepared> {
  if (!file.type.startsWith('image/') || file.type === 'image/svg+xml') {
    throw new ImageError('Only photos and pictures can be added (JPEG, PNG, WebP, GIF, HEIC where supported).')
  }
  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
  } catch {
    throw new ImageError('This browser can’t read that picture.')
  }
  try {
    if (file.type === 'image/gif') {
      if (file.size > MAX_BYTES) throw new ImageError('GIFs can be at most 10 MB.')
      return { bytes: new Uint8Array(await file.arrayBuffer()), type: 'image/gif', w: bitmap.width, h: bitmap.height }
    }
    const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height))
    const w = Math.max(1, Math.round(bitmap.width * scale))
    const h = Math.max(1, Math.round(bitmap.height * scale))
    const canvas = document.createElement('canvas')
    canvas.width = w
    canvas.height = h
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new ImageError('This browser can’t resize pictures.')
    ctx.drawImage(bitmap, 0, 0, w, h)
    // Any format but JPEG may hold transparency (PNG, WebP, AVIF, …); it stays PNG if used.
    const keepAlpha = file.type !== 'image/jpeg' && hasAlpha(ctx, w, h)
    const type = keepAlpha ? 'image/png' : 'image/jpeg'
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, type, QUALITY))
    if (!blob) throw new ImageError('This browser can’t resize pictures.')
    if (blob.size > MAX_BYTES) throw new ImageError('That picture is too large, even resized.')
    return { bytes: new Uint8Array(await blob.arrayBuffer()), type, w, h }
  } finally {
    bitmap.close()
  }
}

function hasAlpha(ctx: CanvasRenderingContext2D, w: number, h: number): boolean {
  const { data } = ctx.getImageData(0, 0, w, h)
  for (let i = 3; i < data.length; i += 4) if (data[i] !== 255) return true
  return false
}

/** The image type of decrypted bytes, from their magic numbers. */
export function sniffType(b: Uint8Array): string | null {
  if (b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'image/jpeg'
  if (b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) return 'image/png'
  if (b[0] === 0x47 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x38) return 'image/gif'
  const ascii = (from: number, to: number) => String.fromCharCode(...b.subarray(from, to))
  if (b.length >= 12 && ascii(0, 4) === 'RIFF' && ascii(8, 12) === 'WEBP') return 'image/webp'
  return null
}
