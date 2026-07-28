package com.jiacimu.lulu

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.ChatMemoryAutomation
import com.jiacimu.lulu.data.MigratedDomainStores

/** Internal non-exported provider used only to initialize persistent migration services. */
class MigrationInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        LuluRepositories.initialize(appContext)
        LuluRepositories.lexicon.initialize(appContext)
        LuluRepositories.worldBook.initialize(appContext)
        MigratedDomainStores.initialize(appContext)
        LuluAiServices.initialize(appContext)
        ChatMemoryAutomation.initialize()
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
