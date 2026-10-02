package com.hush

import android.app.Application
import com.hush.feature.resolver.AndroidYtDlpEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class HushApplication : Application() {
	@Inject lateinit var ytDlp: AndroidYtDlpEngine

	override fun onCreate() {
		super.onCreate()
		CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ytDlp.warmUp() }
	}
}