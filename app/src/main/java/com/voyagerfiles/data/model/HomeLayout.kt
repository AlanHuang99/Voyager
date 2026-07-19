package com.voyagerfiles.data.model

enum class HomeSection(val label: String) {
    STORAGE("Storage"),
    ACTIVE_SESSIONS("Active sessions"),
    QUICK_ACCESS("Quick access"),
    REMOTE_CONNECTIONS("Remote connections"),
    BOOKMARKS("Bookmarks"),
    FOLDERS("Folders"),
}

data class HomeLayout(
    val sectionOrder: List<HomeSection> = HomeSection.entries,
    val hiddenSections: Set<HomeSection> = emptySet(),
) {
    init {
        require(sectionOrder.size == HomeSection.entries.size)
        require(sectionOrder.toSet() == HomeSection.entries.toSet())
        require(hiddenSections.all { it in sectionOrder })
    }

    val visibleSections: List<HomeSection>
        get() = sectionOrder.filterNot { it in hiddenSections }

    val persistedOrder: String
        get() = sectionOrder.joinToString(",") { it.name }

    val persistedHidden: String
        get() = sectionOrder.filter { it in hiddenSections }.joinToString(",") { it.name }

    fun withVisibility(section: HomeSection, visible: Boolean): HomeLayout = copy(
        hiddenSections = if (visible) hiddenSections - section else hiddenSections + section,
    )

    fun move(section: HomeSection, offset: Int): HomeLayout {
        val currentIndex = sectionOrder.indexOf(section)
        val targetIndex = (currentIndex + offset).coerceIn(sectionOrder.indices)
        if (targetIndex == currentIndex) return this
        val reordered = sectionOrder.toMutableList().apply {
            removeAt(currentIndex)
            add(targetIndex, section)
        }
        return copy(sectionOrder = reordered)
    }

    companion object {
        val DEFAULT = HomeLayout()

        fun fromPersisted(order: String?, hidden: String?): HomeLayout {
            val persistedOrder = parseSections(order)
            val normalizedOrder = buildList {
                persistedOrder.forEach { section -> if (section !in this) add(section) }
                HomeSection.entries.forEach { section -> if (section !in this) add(section) }
            }
            return HomeLayout(
                sectionOrder = normalizedOrder,
                hiddenSections = parseSections(hidden).toSet(),
            )
        }

        private fun parseSections(value: String?): List<HomeSection> {
            if (value.isNullOrBlank()) return emptyList()
            return value.split(',').mapNotNull { name ->
                HomeSection.entries.firstOrNull { it.name == name.trim() }
            }
        }
    }
}
