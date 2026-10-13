import type { Editor } from '@tiptap/core'
import { EditorContent, useEditor, useEditorState } from '@tiptap/react'
import { useEffect, useImperativeHandle, useLayoutEffect, useRef, useState, type ReactNode, type Ref } from 'react'
import { editorExtensions, isAllowedLink } from './extensions'
import { parse, serialize } from './markdown'
import styles from './editor.module.css'

export type NoteBodyHandle = {
  /** Serializes the document. Call at save time, never per keystroke (spec §7.2). */
  markdown: () => string
  /** Replaces the document, e.g. with the server's version after a conflict. */
  replace: (markdown: string) => void
  focusStart: () => void
}

type Props = {
  ref: Ref<NoteBodyHandle>
  initialMarkdown: string
  onChange: () => void
  /** Backspace in an empty body goes back to the title. */
  onExitToTitle: () => void
  /** The final Markdown, while the editor still exists; the ref is already gone by then. */
  onUnmount: (markdown: string) => void
}

export function NoteBody({ ref, initialMarkdown, onChange, onExitToTitle, onUnmount }: Props) {
  const [linkOpen, setLinkOpen] = useState(false)
  // Latest callbacks for the editor's handlers, which are bound once.
  const handlers = useRef({ onChange, onExitToTitle, onUnmount, openLink: () => setLinkOpen(true) })
  useEffect(() => {
    handlers.current = { onChange, onExitToTitle, onUnmount, openLink: () => setLinkOpen(true) }
  })

  const editor = useEditor({
    extensions: editorExtensions,
    content: parse(initialMarkdown).toJSON(),
    // The toolbar subscribes to what it needs (useEditorState).
    shouldRerenderOnTransaction: false,
    editorProps: {
      attributes: {
        'aria-label': 'Note text',
        'aria-multiline': 'true',
        role: 'textbox',
        class: styles.prose ?? '',
      },
      handleKeyDown: (view, event) => {
        const mod = event.metaKey || event.ctrlKey
        if (mod && event.key.toLowerCase() === 'k') {
          event.preventDefault()
          handlers.current.openLink()
          return true
        }
        if (event.key === 'Backspace' && view.state.doc.textContent === '' && view.state.doc.childCount <= 1) {
          event.preventDefault()
          handlers.current.onExitToTitle()
          return true
        }
        return false
      },
    },
    onUpdate: ({ transaction }) => {
      if (transaction.docChanged) handlers.current.onChange()
    },
  })

  // Layout cleanup runs before useEditor destroys the editor (a passive cleanup).
  useLayoutEffect(() => () => handlers.current.onUnmount(serialize(editor.state.doc)), [editor])

  useImperativeHandle(
    ref,
    () => ({
      markdown: () => serialize(editor.state.doc),
      replace: (markdown) => {
        editor.commands.setContent(parse(markdown).toJSON(), { emitUpdate: false })
      },
      focusStart: () => {
        editor.commands.focus('start')
      },
    }),
    [editor],
  )

  return (
    <>
      <div className={styles.toolbarRow}>
        <Toolbar editor={editor} linkOpen={linkOpen} onLink={() => setLinkOpen((v) => !v)} />
        {linkOpen && <LinkForm editor={editor} onDone={() => setLinkOpen(false)} />}
      </div>
      <div className={styles.scroller}>
        <EditorContent editor={editor} className={styles.content} />
      </div>
    </>
  )
}

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)
const mod = isMac ? '⌘' : 'Ctrl+'
const shift = isMac ? '⇧' : 'Shift+'
const alt = isMac ? '⌥' : 'Alt+'

function Toolbar({ editor, linkOpen, onLink }: { editor: Editor; linkOpen: boolean; onLink: () => void }) {
  const active = useEditorState({
    editor,
    selector: ({ editor: e }) => ({
      bold: e.isActive('bold'),
      italic: e.isActive('italic'),
      strike: e.isActive('strike'),
      h1: e.isActive('heading', { level: 1 }),
      h2: e.isActive('heading', { level: 2 }),
      h3: e.isActive('heading', { level: 3 }),
      bullets: e.isActive('bulletList'),
      numbers: e.isActive('orderedList'),
      tasks: e.isActive('taskList'),
      quote: e.isActive('blockquote'),
      link: e.isActive('link'),
    }),
  })
  const chain = () => editor.chain().focus()

  return (
    <div className={styles.toolbar} role="toolbar" aria-label="Formatting">
      <Tool label="Bold" keys={`${mod}B`} on={active.bold} run={() => chain().toggleBold().run()}>
        <b>B</b>
      </Tool>
      <Tool label="Italic" keys={`${mod}I`} on={active.italic} run={() => chain().toggleItalic().run()}>
        <span className={styles.italicGlyph}>I</span>
      </Tool>
      <Tool label="Strikethrough" keys={`${mod}${shift}S`} on={active.strike} run={() => chain().toggleStrike().run()}>
        <s>S</s>
      </Tool>
      <Sep />
      {([1, 2, 3] as const).map((level) => (
        <Tool
          key={level}
          label={`Heading ${level}`}
          keys={`${mod}${alt}${level}`}
          on={active[`h${level}`]}
          wide
          run={() => chain().toggleHeading({ level }).run()}
        >
          H{level}
        </Tool>
      ))}
      <Sep />
      <Tool label="Bulleted list" keys={`${mod}${shift}8`} on={active.bullets} run={() => chain().toggleBulletList().run()}>
        <svg width="16" height="14" viewBox="0 0 16 14" fill="currentColor" aria-hidden="true">
          <circle cx="2" cy="2" r="1.5" />
          <circle cx="2" cy="7" r="1.5" />
          <circle cx="2" cy="12" r="1.5" />
          <rect x="6" y="1.2" width="10" height="1.6" rx=".8" />
          <rect x="6" y="6.2" width="10" height="1.6" rx=".8" />
          <rect x="6" y="11.2" width="10" height="1.6" rx=".8" />
        </svg>
      </Tool>
      <Tool label="Numbered list" keys={`${mod}${shift}7`} on={active.numbers} run={() => chain().toggleOrderedList().run()}>
        <span className={styles.monoGlyph}>1.</span>
      </Tool>
      <Tool label="Checklist" keys={`${mod}${shift}9`} on={active.tasks} run={() => chain().toggleTaskList().run()}>
        <span className={styles.checkGlyph} />
      </Tool>
      <Tool label="Quote" keys={`${mod}${shift}B`} on={active.quote} run={() => chain().toggleBlockquote().run()}>
        <span className={styles.quoteGlyph}>“</span>
      </Tool>
      <Sep />
      <Tool label="Link" keys={`${mod}K`} on={active.link || linkOpen} wide run={onLink}>
        <span className={styles.linkGlyph}>Link</span>
      </Tool>
    </div>
  )
}

type ToolProps = { label: string; keys: string; on: boolean; wide?: boolean; run: () => void; children: ReactNode }

function Tool({ label, keys, on, wide = false, run, children }: ToolProps) {
  return (
    <button
      type="button"
      className={[styles.tool, wide && styles.wide].filter(Boolean).join(' ')}
      aria-label={label}
      aria-pressed={on}
      title={`${label} (${keys})`}
      // mousedown, not click: keep the editor's selection and focus.
      onMouseDown={(e) => {
        e.preventDefault()
        run()
      }}
      // Keyboard activation (Enter/Space) arrives as click without mousedown.
      onClick={(e) => {
        if (e.detail === 0) run()
      }}
    >
      {children}
    </button>
  )
}

function Sep() {
  return <span className={styles.sep} aria-hidden="true" />
}

function LinkForm({ editor, onDone }: { editor: Editor; onDone: () => void }) {
  const current = (editor.getAttributes('link').href as string | undefined) ?? ''
  const [url, setUrl] = useState(current)
  const [error, setError] = useState<string>()
  // The selection to link, captured before focus moves into the URL field.
  const selection = useRef({ from: editor.state.selection.from, to: editor.state.selection.to })

  const apply = () => {
    const raw = url.trim()
    const range = selection.current
    if (!raw) {
      editor.chain().focus().setTextSelection(range).extendMarkRange('link').unsetLink().run()
      onDone()
      return
    }
    const href = /^[a-z][a-z0-9+.-]*:/i.test(raw) ? raw : raw.includes('@') && !raw.includes('/') ? `mailto:${raw}` : `https://${raw}`
    if (!isAllowedLink(href)) {
      setError('Use a web (https://) or email (mailto:) link.')
      return
    }
    const chain = editor.chain().focus().setTextSelection(range)
    if (range.from === range.to && !editor.isActive('link')) {
      const text = href.replace(/^(https?:\/\/|mailto:)/, '')
      chain
        .insertContent({ type: 'text', text, marks: [{ type: 'link', attrs: { href } }] })
        .run()
    } else {
      chain.extendMarkRange('link').setLink({ href }).run()
    }
    onDone()
  }

  return (
    <form
      className={styles.linkForm}
      onSubmit={(e) => {
        e.preventDefault()
        apply()
      }}
    >
      <label htmlFor="link-url" className="visually-hidden">
        Link address
      </label>
      <input
        id="link-url"
        className={styles.linkInput}
        value={url}
        placeholder="https://"
        spellCheck={false}
        autoComplete="off"
        // oxlint-disable-next-line jsx-a11y/no-autofocus -- the field opens on request (button or ⌘K)
        autoFocus
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? 'link-error' : undefined}
        onChange={(e) => {
          setUrl(e.target.value)
          setError(undefined)
        }}
        onKeyDown={(e) => {
          if (e.key === 'Escape') {
            e.preventDefault()
            e.stopPropagation()
            editor.commands.focus()
            onDone()
          }
        }}
      />
      <button type="submit" className={styles.linkApply}>
        {current ? 'Update' : 'Add'}
      </button>
      {error && (
        <span id="link-error" role="alert" className={styles.linkError}>
          {error}
        </span>
      )}
    </form>
  )
}
