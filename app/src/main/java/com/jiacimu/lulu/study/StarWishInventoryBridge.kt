package com.jiacimu.lulu.study

/**
 * Temporary compatibility bridge until the large migrated exam store is split into smaller stores.
 * It invokes the store's own private atomic update function, so persistence, StateFlow emission and
 * repair rules remain exactly the same as every other study-state mutation.
 */
internal object StarWishInventoryBridge {
    private val updateMethod by lazy {
        PostgraduateExamStore::class.java.declaredMethods
            .firstOrNull { method ->
                method.name == "update" && method.parameterTypes.size == 1
            }
            ?.apply { isAccessible = true }
            ?: error("PostgraduateExamStore.update 未找到")
    }

    fun consumeTheaterFragment(store: PostgraduateExamStore): Boolean =
        consume(store) { inventory ->
            if (inventory.theaterFragments < 1) null
            else inventory.copy(theaterFragments = inventory.theaterFragments - 1)
        }

    private fun consume(
        store: PostgraduateExamStore,
        transform: (StudyInventory) -> StudyInventory?,
    ): Boolean {
        var consumed = false
        val updater: (StudyState) -> StudyState = { current ->
            val nextInventory = transform(current.inventory)
            if (nextInventory == null) {
                current
            } else {
                consumed = true
                current.copy(inventory = nextInventory)
            }
        }
        runCatching { updateMethod.invoke(store, updater) }
            .getOrElse { error -> throw IllegalStateException("心愿馆库存更新失败", error) }
        return consumed
    }
}
