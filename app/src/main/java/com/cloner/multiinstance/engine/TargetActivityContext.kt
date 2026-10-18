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

    /**
     * v2.28: AppCompat theme override. جب non-zero ہو تو getTheme() target ایپ کی
     * theme کے بجائے HOST کے resources سے اصلی Theme.AppCompat بناتا ہے۔
     * Facebook کا obfuscated AppCompat delegate wrapper context کی theme دیکھتا ہے
     * (redirected X.AkD.A0l field کے ذریعے) — وہ Theme.AppCompat ہونی چاہیے ورنہ
     * X.001.A0t throw کرتا ہے۔
     */
    private var appCompatThemeOverride: Int = 0

    fun forceAppCompatTheme(resId: Int) {
        appCompatThemeOverride = resId
        cachedTheme = null
    }

    override fun getTheme(): Resources.Theme {
        val override = appCompatThemeOverride
        if (override != 0) {
            var t = cachedTheme
            if (t == null) {
                // HOST base context کے resources سے بنائیں تاکہ AppCompat style
                // resource ID resolve ہو (target کے resources میں یہ ID نہیں)۔
                t = baseContext.resources.newTheme()
                t.applyStyle(override, true)
                cachedTheme = t
                HookDiagnostics.record(this, "theme: forced AppCompat theme on wrapper (res=0x" + override.toString(16) + ")")
            }
            return t
        }
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

    /**
     * v2.29 (ROOT CAUSE FIX): ContextWrapper.obtainStyledAttributes() delegates
     * directly to mBase, BYPASSING our getTheme() override entirely. So AppCompat's
     * mContext.obtainStyledAttributes(R.styleable.AppCompatTheme) never saw our
     * theme — every AppCompat app crashed. Route all three variants through
     * getTheme() like ContextThemeWrapper does. One-time log only (hot path).
     */
    private var styledLogged = false

    private fun logStyledOnce() {
        if (!styledLogged) {
            styledLogged = true
            HookDiagnostics.record(this, "theme: obtainStyledAttributes routed via wrapper theme")
        }
    }

    override fun obtainStyledAttributes(attrs: IntArray): android.content.res.TypedArray {
        logStyledOnce()
        return theme.obtainStyledAttributes(attrs)
    }

    override fun obtainStyledAttributes(resid: Int, attrs: IntArray): android.content.res.TypedArray {
        logStyledOnce()
        return theme.obtainStyledAttributes(resid, attrs)
    }

    override fun obtainStyledAttributes(
        set: android.util.AttributeSet?,
        attrs: IntArray,
        defStyleAttr: Int,
        defStyleRes: Int
    ): android.content.res.TypedArray {
        logStyledOnce()
        return theme.obtainStyledAttributes(set, attrs, defStyleAttr, defStyleRes)
    }
}
