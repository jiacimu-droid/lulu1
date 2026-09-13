package com.jiacimu.lulu.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.system.LuluAlarmSystem

class LuluStartupProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        MigratedDomainStores.initialize(appContext)
        LuluRepositories.lexicon.initialize(appContext)
        LuluAlarmSystem.initialize(appContext)
        CommitmentTaskStore.initialize(appContext)
        CommitmentTurnAutomation.initialize(appContext)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
