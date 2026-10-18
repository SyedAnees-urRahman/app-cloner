package com.cloner.multiinstance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.cloner.multiinstance.data.ClonerDatabase
import com.cloner.multiinstance.engine.CloneEngine
import kotlinx.coroutines.launch

class CloneLauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val cloneId = intent.getStringExtra("clone_id")
            val clone = cloneId?.let {
                ClonerDatabase.get(this@CloneLauncherActivity).cloneDao().getById(it)
            }
            if (clone != null) {
                runCatching { CloneEngine.launchClone(this@CloneLauncherActivity, clone) }
            }
            finish()
        }
    }
}
