package notes.app.notes

import android.content.SharedPreferences
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import notes.app.ui.Icons
import notes.app.ui.Kicker
import notes.app.ui.LocalTokens
import notes.app.ui.Type
import notes.core.db.NoteFilter
import notes.core.db.TagCount

/** Whether the tag section is open and which parent tags are expanded, kept in [prefs]. */
class TagTreeState(private val prefs: SharedPreferences) {
    private val _open = MutableStateFlow(prefs.getBoolean(OPEN, true))
    val open: StateFlow<Boolean> = _open

    private val _expanded = MutableStateFlow(prefs.getStringSet(EXPANDED, emptySet()).orEmpty().toSet())
    val expanded: StateFlow<Set<String>> = _expanded

    fun setOpen(open: Boolean) {
        _open.value = open
        prefs.edit { putBoolean(OPEN, open) }
    }

    fun setExpanded(key: String, expanded: Boolean) {
        val next = if (expanded) _expanded.value + key else _expanded.value - key
        _expanded.value = next
        prefs.edit { putStringSet(EXPANDED, next) }
    }

    private companion object {
        const val OPEN = "tags_open"
        const val EXPANDED = "tags_expanded"
    }
}

class TagNode(val tag: TagCount, val label: String, val children: List<TagNode>)

/** Nests the store's flat list (parents included) by key; a tag whose parent is missing goes at the top. */
fun buildTagTree(tags: List<TagCount>): List<TagNode> {
    val sorted = tags.sortedBy { it.key }
    val keys = sorted.map { it.key }.toSet()
    fun parentOf(key: String) = key.substringBeforeLast('/', "").takeIf { it.isNotEmpty() && it in keys }
    val byParent = sorted.groupBy { parentOf(it.key) }
    fun build(parent: String?): List<TagNode> = byParent[parent].orEmpty().map { t ->
        val label = if (parent == null) t.name else t.name.substringAfterLast('/')
        TagNode(t, label, build(t.key))
    }
    return build(null)
}

/** The rows the open section shows: each node, then its children if expanded. */
private fun visibleRows(nodes: List<TagNode>, expanded: Set<String>, depth: Int = 0): List<Pair<TagNode, Int>> =
    nodes.flatMap { n ->
        listOf(n to depth) + if (n.tag.key in expanded) visibleRows(n.children, expanded, depth + 1) else emptyList()
    }

/** All notes, Untagged and the tag tree, as list items above the notes. */
fun LazyListScope.tagSection(
    tree: List<TagNode>,
    filter: NoteFilter?,
    open: Boolean,
    expanded: Set<String>,
    onOpen: (Boolean) -> Unit,
    onExpand: (String, Boolean) -> Unit,
    onFilter: (NoteFilter?) -> Unit
) {
    item(key = "tags:header", contentType = "tagsHeader") {
        SectionHeader(open, onOpen, Modifier.animateItem())
    }
    if (!open) return
    item(key = "tags:all", contentType = "tag") {
        TagRow("All notes", null, 0, filter == null, null, { onFilter(null) }, null, Modifier.animateItem())
    }
    item(key = "tags:untagged", contentType = "tag") {
        TagRow(
            "Untagged",
            null,
            0,
            filter == NoteFilter.Untagged,
            null,
            { onFilter(NoteFilter.Untagged) },
            null,
            Modifier.animateItem()
        )
    }
    for ((node, depth) in visibleRows(tree, expanded)) {
        val key = node.tag.key
        item(key = "tag:$key", contentType = "tag") {
            val isOpen = key in expanded
            TagRow(
                label = node.label,
                count = node.tag.count,
                depth = depth,
                selected = (filter as? NoteFilter.Tag)?.key == key,
                expandedState = if (node.children.isEmpty()) null else isOpen,
                onClick = { onFilter(NoteFilter.Tag(key, node.tag.name)) },
                onExpand = { onExpand(key, !isOpen) },
                modifier = Modifier.animateItem(),
                description = "#${node.tag.name}, ${notesCount(node.tag.count)}"
            )
        }
    }
}

private fun notesCount(n: Int) = if (n == 1) "1 note" else "$n notes"

@Composable
private fun SectionHeader(open: Boolean, onOpen: (Boolean) -> Unit, modifier: Modifier) {
    val t = LocalTokens.current
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "tagsChevron")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = if (open) "Hide tags" else "Show tags") { onOpen(!open) }
            .padding(start = 18.dp, end = 16.dp)
            .semantics {
                heading()
                stateDescription = if (open) "Shown" else "Hidden"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Chevron, null, Modifier.size(12.dp).rotate(turn), tint = t.ink3)
        Spacer(Modifier.width(6.dp))
        Kicker("Tags")
    }
}

/**
 * One row: a 48dp expander for parents (or the same space empty), then the
 * name and its count. [expandedState] is null for rows without children.
 */
@Composable
private fun TagRow(
    label: String,
    count: Int?,
    depth: Int,
    selected: Boolean,
    expandedState: Boolean?,
    onClick: () -> Unit,
    onExpand: (() -> Unit)?,
    modifier: Modifier = Modifier,
    description: String? = null
) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp + (depth * 16).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (expandedState != null && onExpand != null) {
            val turn by animateFloatAsState(if (expandedState) 90f else 0f, label = "tagChevron")
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(role = Role.Button, onClick = onExpand)
                    .semantics { contentDescription = "${if (expandedState) "Collapse" else "Expand"} $label" },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Chevron, null, Modifier.size(12.dp).rotate(turn), tint = t.ink3)
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(if (selected) t.on else Color.Transparent)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp)
                .then(
                    if (description != null) {
                        Modifier.clearAndSetSemantics {
                            contentDescription = description
                            this.selected = selected
                        }
                    } else {
                        Modifier.semantics { this.selected = selected }
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (count != null) {
                Icon(Icons.Hash, null, Modifier.size(15.dp), tint = t.ink3)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                label,
                style = Type.body,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (count != null) Text(count.toString(), style = Type.small, color = t.ink3)
        }
    }
}
