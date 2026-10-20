package com.github.bookkeeping.automation

import android.content.Context
import com.github.bookkeeping.CategoryCatalog

class LearnedCategories(context: Context) {
    private val prefs = context.getSharedPreferences("learned_categories", Context.MODE_PRIVATE)
    private fun key(platform: String, merchant: String, direction: String) = ShoppingParser.hash("$platform|${merchant.trim()}|$direction")
    fun save(platform: String, merchant: String, direction: String, category: String) {
        if (merchant.isNotBlank() && category in CategoryCatalog.forDirection(direction).map { it.key })
            prefs.edit().putString(key(platform, merchant, direction), category).apply()
    }
    fun get(platform: String, merchant: String, direction: String): String? = prefs.getString(key(platform, merchant, direction), null)
}
