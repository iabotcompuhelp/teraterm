package com.opentermx.app.i18n

import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

object Strings {

    private const val BASE = "i18n.messages"

    private var bundle: ResourceBundle = ResourceBundle.getBundle(BASE, Locale("es"))

    var locale: Locale = Locale("es")
        private set

    fun setLocale(localeCode: String) {
        locale = Locale(localeCode)
        bundle = ResourceBundle.getBundle(BASE, locale)
    }

    operator fun get(key: String): String = try {
        bundle.getString(key)
    } catch (e: MissingResourceException) {
        key
    }

    fun format(key: String, vararg args: Any?): String =
        MessageFormat.format(get(key), *args)
}