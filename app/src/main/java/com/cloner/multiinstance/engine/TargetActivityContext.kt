package com.cloner.multiinstance.engine

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources

/** Target ایپ کی Activity کا base context: resources اصل target ایپ کے، storage اس clone کی sandbox میں۔ */
class TargetActivityContext(
    base: Context,
    cloneId: String,
    private val targetResources: Resources
) : CloneContext(base, cloneId) {
    override fun getResources(): Resources = targetResources
    override fun getAssets(): AssetManager = targetResources.assets

    companion object {
        /** ہماری host package — system-service attribution کے لیے (OpPackageName)۔ */
        const val HOST_PACKAGE = "com.cloner.multiinstance"
    }

    /**
     * System-service attribution (AppOps, Settings.Global وغیرہ) کے لیے HOST
     * package بتاتے ہیں۔ getPackageName() اب بھی target کا ہی رہتا ہے (app
     * logic/resources کے لیے)، لیکن سسٹم کو ہماری UID والی package نظر آنی
     * چاہیے ورنہ "Package <target> does not belong to <uid>" SecurityException
     * آتا ہے (مثلاً PhoneWindow -> Settings.Global.getInt in attach())۔
     */
    override fun getOpPackageName(): String = HOST_PACKAGE

    /**
     * Target ایپ کی theme: اس Infinix Android build میں ContextThemeWrapper کا
     * mTheme field موجود نہیں، اس لیے getTheme() یہیں override کرتے ہیں تاکہ
     * activity کو target ایپ کی اصل theme ملے (AppCompat کے لیے ضروری)۔
     */
    var targetThemeRes: Int = 0
    var targetAppThemeRes: Int = 0
    private var cachedTheme: Resources.Theme? = null

    override fun getTheme(): Resources.Theme {
        val tr = targetThemeRes
        if (tr != 0) {
            var t = cachedTheme
            if (t == null) {
                t = targetResources.newTheme()
                // Replicate ContextThemeWrapper.initializeTheme(): seed with the
                // APPLICATION theme first, then apply the activity theme on top.
                // Without the app-theme base, overlay-style activity themes lack
                // base attributes (e.g. windowActionBar) -> AppCompat crash.
                val ar = targetAppThemeRes
                if (ar != 0) {
                    val base = targetResources.newTheme()
                    base.applyStyle(ar, true)
                    t.setTo(base)
                }
                t.applyStyle(tr, true)
                cachedTheme = t
                HookDiagnostics.record(this, "theme: built full theme (app=0x" + ar.toString(16) + " + activity=0x" + tr.toString(16) + ")")
            }
            return t
        }
        return super.getTheme()
    }

    fun resetThemeCache() { cachedTheme = null }
}
