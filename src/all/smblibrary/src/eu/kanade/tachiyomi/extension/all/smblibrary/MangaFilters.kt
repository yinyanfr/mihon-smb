package eu.kanade.tachiyomi.extension.all.smblibrary

import eu.kanade.tachiyomi.source.model.Filter

enum class MangaSortField {
    Name,
    LastModified,
}

data class MangaSort(
    val field: MangaSortField,
    val ascending: Boolean,
) {
    companion object {
        val DEFAULT = MangaSort(MangaSortField.LastModified, ascending = false)
    }
}

class MangaSortFilter : Filter.Sort(
    name = "Sort",
    values = arrayOf("Name", "Modified time"),
    state = Selection(MangaSortField.LastModified.ordinal, false),
) {
    val selected: MangaSort
        get() {
            val selection = state ?: return MangaSort.DEFAULT
            val field = MangaSortField.entries.getOrElse(selection.index) { MangaSort.DEFAULT.field }
            return MangaSort(field, selection.ascending)
        }
}

object MangaListingSorter {
    fun sorted(entries: Iterable<RemoteEntry>, sort: MangaSort): List<RemoteEntry> = entries.sortedWith { left, right ->
        val primary = when (sort.field) {
            MangaSortField.Name -> NaturalSort.compare(left.name, right.name)
            MangaSortField.LastModified -> left.lastModifiedMillis.compareTo(right.lastModifiedMillis)
        }
        val directed = if (sort.ascending) primary else -primary
        directed.takeIf { it != 0 }
            ?: NaturalSort.compare(left.name, right.name).takeIf { it != 0 }
            ?: left.relativePath.compareTo(right.relativePath)
    }
}
