package com.pcmobilelink.nearshare.ui

import android.app.AlertDialog
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

object ThemedAlertDialog {
    fun apply(dialog: AlertDialog) {
        val root = dialog.window?.decorView ?: return
        applyTypeface(root)
        listOf(
            AlertDialog.BUTTON_POSITIVE,
            AlertDialog.BUTTON_NEGATIVE,
            AlertDialog.BUTTON_NEUTRAL,
        ).forEach { buttonId ->
            dialog.getButton(buttonId)?.typeface = AppTypeface.bold
        }
    }

    private fun applyTypeface(view: View) {
        if (view is TextView) {
            val style = view.typeface?.style ?: Typeface.NORMAL
            view.typeface = AppTypeface.getTypeface("", style)
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyTypeface(view.getChildAt(index))
            }
        }
    }
}
