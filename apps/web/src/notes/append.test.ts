import { describe, expect, it } from 'vitest'
import { appendBlock } from './saver'

const img = `![](notesd:image/${'a'.repeat(64)}?w=4&h=3)`

describe('appendBlock', () => {
  it('goes on its own line after the text', () => {
    expect(appendBlock('Text\n', img)).toBe(`Text\n\n${img}\n`)
    expect(appendBlock('', img)).toBe(`${img}\n`)
  })

  it('closes a code fence left open at the end, so the picture is not code', () => {
    expect(appendBlock('Before\n\n```go\nfmt.Println()\n', img)).toBe(`Before\n\n\`\`\`go\nfmt.Println()\n\`\`\`\n\n${img}\n`)
    expect(appendBlock('~~~~\ncode', img)).toBe(`~~~~\ncode\n~~~~\n\n${img}\n`)
  })

  it('leaves closed fences alone', () => {
    expect(appendBlock('```\ncode\n```\n', img)).toBe(`\`\`\`\ncode\n\`\`\`\n\n${img}\n`)
  })
})
